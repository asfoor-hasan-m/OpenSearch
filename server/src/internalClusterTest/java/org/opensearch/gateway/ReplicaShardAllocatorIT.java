/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.gateway;

import org.opensearch.LegacyESVersion;
import org.opensearch.action.admin.indices.flush.SyncedFlushResponse;
import org.opensearch.action.admin.indices.stats.ShardStats;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.routing.UnassignedInfo;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.Priority;
import org.opensearch.common.breaker.CircuitBreaker;
import org.opensearch.common.breaker.CircuitBreakingException;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.Index;
import org.opensearch.index.IndexService;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.seqno.ReplicationTracker;
import org.opensearch.index.seqno.RetentionLease;
import org.opensearch.index.seqno.SequenceNumbers;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.IndicesService;
import org.opensearch.indices.recovery.PeerRecoveryTargetService;
import org.opensearch.indices.recovery.RecoveryCleanFilesRequest;
import org.opensearch.indices.recovery.RecoveryState;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.InternalSettingsPlugin;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.transport.MockTransportService;
import org.opensearch.transport.TransportService;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 0)
public class ReplicaShardAllocatorIT extends OpenSearchIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(MockTransportService.TestPlugin.class, InternalSettingsPlugin.class);
    }

    /**
     * Verify that if we found a new copy where it can perform a no-op recovery,
     * then we will cancel the current recovery and allocate replica to the new copy.
     */
    public void testPreferCopyCanPerformNoopRecovery() throws Exception {
        String indexName = "test";
        String nodeWithPrimary = internalCluster().startNode();
        assertAcked(
            client().admin().indices().prepareCreate(indexName)
                .setSettings(Settings.builder()
                    .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), randomBoolean())
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                    .put(IndexSettings.FILE_BASED_RECOVERY_THRESHOLD_SETTING.getKey(), 1.0f)
                    .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), "100ms")
                    .put(IndexService.GLOBAL_CHECKPOINT_SYNC_INTERVAL_SETTING.getKey(), "100ms")
                    .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "1ms")));
        String nodeWithReplica = internalCluster().startDataOnlyNode();
        Settings nodeWithReplicaSettings = internalCluster().dataPathSettings(nodeWithReplica);
        ensureGreen(indexName);
        indexRandom(randomBoolean(), randomBoolean(), randomBoolean(), IntStream.range(0, between(100, 500))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        client().admin().indices().prepareFlush(indexName).get();
        if (randomBoolean()) {
            indexRandom(randomBoolean(), false, randomBoolean(), IntStream.range(0, between(0, 80))
                .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        }
        ensureActivePeerRecoveryRetentionLeasesAdvanced(indexName);
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(nodeWithReplica));
        if (randomBoolean()) {
            client().admin().indices().prepareForceMerge(indexName).setFlush(true).get();
        }
        CountDownLatch blockRecovery = new CountDownLatch(1);
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        MockTransportService transportServiceOnPrimary
            = (MockTransportService) internalCluster().getInstance(TransportService.class, nodeWithPrimary);
        transportServiceOnPrimary.addSendBehavior((connection, requestId, action, request, options) -> {
            if (PeerRecoveryTargetService.Actions.FILES_INFO.equals(action)) {
                recoveryStarted.countDown();
                try {
                    blockRecovery.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            connection.sendRequest(requestId, action, request, options);
        });
        internalCluster().startDataOnlyNode();
        recoveryStarted.await();
        nodeWithReplica = internalCluster().startDataOnlyNode(nodeWithReplicaSettings);
        // AllocationService only calls GatewayAllocator if there're unassigned shards
        assertAcked(client().admin().indices().prepareCreate("dummy-index").setWaitForActiveShards(0));
        ensureGreen(indexName);
        assertThat(internalCluster().nodesInclude(indexName), hasItem(nodeWithReplica));
        assertNoOpRecoveries(indexName);
        blockRecovery.countDown();
        transportServiceOnPrimary.clearAllRules();
    }

    /**
     * Ensure that we fetch the latest shard store from the primary when a new node joins so we won't cancel the current recovery
     * for the copy on the newly joined node unless we can perform a noop recovery with that node.
     */
    public void testRecentPrimaryInformation() throws Exception {
        String indexName = "test";
        String nodeWithPrimary = internalCluster().startNode();
        assertAcked(
            client().admin().indices().prepareCreate(indexName)
                .setSettings(Settings.builder()
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                    .put(IndexSettings.FILE_BASED_RECOVERY_THRESHOLD_SETTING.getKey(), 0.1f)
                    .put(IndexService.GLOBAL_CHECKPOINT_SYNC_INTERVAL_SETTING.getKey(), "100ms")
                    .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), "100ms")
                    .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "1ms")));
        String nodeWithReplica = internalCluster().startDataOnlyNode();
        DiscoveryNode discoNodeWithReplica = internalCluster().getInstance(ClusterService.class, nodeWithReplica).localNode();
        Settings nodeWithReplicaSettings = internalCluster().dataPathSettings(nodeWithReplica);
        ensureGreen(indexName);

        indexRandom(randomBoolean(), false, randomBoolean(), IntStream.range(0, between(10, 100))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        assertBusy(() -> {
            SyncedFlushResponse syncedFlushResponse = client().admin().indices().prepareSyncedFlush(indexName).get();
            assertThat(syncedFlushResponse.successfulShards(), equalTo(2));
        });
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(nodeWithReplica));
        if (randomBoolean()) {
            indexRandom(randomBoolean(), false, randomBoolean(), IntStream.range(0, between(10, 100))
                .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        }
        CountDownLatch blockRecovery = new CountDownLatch(1);
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        MockTransportService transportServiceOnPrimary
            = (MockTransportService) internalCluster().getInstance(TransportService.class, nodeWithPrimary);
        transportServiceOnPrimary.addSendBehavior((connection, requestId, action, request, options) -> {
            if (PeerRecoveryTargetService.Actions.FILES_INFO.equals(action)) {
                recoveryStarted.countDown();
                try {
                    blockRecovery.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            connection.sendRequest(requestId, action, request, options);
        });
        String newNode = internalCluster().startDataOnlyNode();
        recoveryStarted.await();
        // Index more documents and flush to destroy sync_id and remove the retention lease (as file_based_recovery_threshold reached).
        indexRandom(randomBoolean(), randomBoolean(), randomBoolean(), IntStream.range(0, between(50, 200))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        client().admin().indices().prepareFlush(indexName).get();
        assertBusy(() -> {
            for (ShardStats shardStats : client().admin().indices().prepareStats(indexName).get().getShards()) {
                for (RetentionLease lease : shardStats.getRetentionLeaseStats().retentionLeases().leases()) {
                    assertThat(lease.id(), not(equalTo(ReplicationTracker.getPeerRecoveryRetentionLeaseId(discoNodeWithReplica.getId()))));
                }
            }
        });
        // AllocationService only calls GatewayAllocator if there are unassigned shards
        assertAcked(client().admin().indices().prepareCreate("dummy-index").setWaitForActiveShards(0)
            .setSettings(Settings.builder().put("index.routing.allocation.require.attr", "not-found")));
        internalCluster().startDataOnlyNode(nodeWithReplicaSettings);
        // need to wait for events to ensure the reroute has happened since we perform it async when a new node joins.
        client().admin().cluster().prepareHealth(indexName).setWaitForYellowStatus().setWaitForEvents(Priority.LANGUID).get();
        blockRecovery.countDown();
        ensureGreen(indexName);
        assertThat(internalCluster().nodesInclude(indexName), hasItem(newNode));
        for (RecoveryState recovery : client().admin().indices().prepareRecoveries(indexName).get().shardRecoveryStates().get(indexName)) {
            if (recovery.getPrimary() == false) {
                assertThat(recovery.getIndex().fileDetails(), not(empty()));
            }
        }
        transportServiceOnPrimary.clearAllRules();
    }

    public void testFullClusterRestartPerformNoopRecovery() throws Exception {
        int numOfReplicas = randomIntBetween(1, 2);
        internalCluster().ensureAtLeastNumDataNodes(numOfReplicas + 2);
        String indexName = "test";
        assertAcked(
            client().admin().indices().prepareCreate(indexName)
                .setSettings(Settings.builder()
                    .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), randomBoolean())
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexSettings.INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE_SETTING.getKey(), randomIntBetween(10, 100) + "kb")
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numOfReplicas)
                    .put(IndexSettings.FILE_BASED_RECOVERY_THRESHOLD_SETTING.getKey(), 0.5)
                    .put(IndexService.GLOBAL_CHECKPOINT_SYNC_INTERVAL_SETTING.getKey(), "100ms")
                    .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), "100ms")));
        ensureGreen(indexName);
        indexRandom(randomBoolean(), randomBoolean(), randomBoolean(), IntStream.range(0, between(200, 500))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        client().admin().indices().prepareFlush(indexName).get();
        indexRandom(randomBoolean(), false, randomBoolean(), IntStream.range(0, between(0, 80))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        if (randomBoolean()) {
            client().admin().indices().prepareForceMerge(indexName).get();
        }
        ensureActivePeerRecoveryRetentionLeasesAdvanced(indexName);
        if (randomBoolean()) {
            assertAcked(client().admin().indices().prepareClose(indexName));
        }
        assertAcked(client().admin().cluster().prepareUpdateSettings()
            .setPersistentSettings(Settings.builder().put("cluster.routing.allocation.enable", "primaries").build()));
        internalCluster().fullRestart();
        ensureYellow(indexName);
        assertAcked(client().admin().cluster().prepareUpdateSettings()
            .setPersistentSettings(Settings.builder().putNull("cluster.routing.allocation.enable").build()));
        ensureGreen(indexName);
        assertNoOpRecoveries(indexName);
    }

    public void testPreferCopyWithHighestMatchingOperations() throws Exception {
        String indexName = "test";
        internalCluster().startMasterOnlyNode();
        internalCluster().startDataOnlyNodes(3);
        assertAcked(
            client().admin().indices().prepareCreate(indexName)
                .setSettings(Settings.builder()
                    .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), randomBoolean())
                    .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexSettings.INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE_SETTING.getKey(), randomIntBetween(10, 100) + "kb")
                    .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                    .put(IndexSettings.FILE_BASED_RECOVERY_THRESHOLD_SETTING.getKey(), 3.0)
                    .put(IndexService.GLOBAL_CHECKPOINT_SYNC_INTERVAL_SETTING.getKey(), "100ms")
                    .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "0ms")
                    .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), "100ms")));
        ensureGreen(indexName);
        indexRandom(randomBoolean(), randomBoolean(), randomBoolean(), IntStream.range(0, between(200, 500))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        client().admin().indices().prepareFlush(indexName).get();
        String nodeWithLowerMatching = randomFrom(internalCluster().nodesInclude(indexName));
        Settings nodeWithLowerMatchingSettings = internalCluster().dataPathSettings(nodeWithLowerMatching);
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(nodeWithLowerMatching));
        ensureGreen(indexName);

        indexRandom(randomBoolean(), false, randomBoolean(), IntStream.range(0, between(1, 100))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        ensureActivePeerRecoveryRetentionLeasesAdvanced(indexName);
        String nodeWithHigherMatching = randomFrom(internalCluster().nodesInclude(indexName));
        Settings nodeWithHigherMatchingSettings = internalCluster().dataPathSettings(nodeWithHigherMatching);
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(nodeWithHigherMatching));
        indexRandom(randomBoolean(), false, randomBoolean(), IntStream.range(0, between(0, 100))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));

        assertAcked(client().admin().cluster().prepareUpdateSettings()
            .setPersistentSettings(Settings.builder().put("cluster.routing.allocation.enable", "primaries").build()));
        nodeWithLowerMatching = internalCluster().startNode(nodeWithLowerMatchingSettings);
        nodeWithHigherMatching = internalCluster().startNode(nodeWithHigherMatchingSettings);
        assertAcked(client().admin().cluster().prepareUpdateSettings()
            .setPersistentSettings(Settings.builder().putNull("cluster.routing.allocation.enable").build()));
        ensureGreen(indexName);
        assertThat(internalCluster().nodesInclude(indexName), allOf(hasItem(nodeWithHigherMatching), not(hasItem(nodeWithLowerMatching))));
    }

    /**
     * Make sure that we do not repeatedly cancel an ongoing recovery for a noop copy on a broken node.
     */
    public void testDoNotCancelRecoveryForBrokenNode() throws Exception {
        internalCluster().startMasterOnlyNode();
        String nodeWithPrimary = internalCluster().startDataOnlyNode();
        String indexName = "test";
        assertAcked(client().admin().indices().prepareCreate(indexName).setSettings(Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1).put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexService.GLOBAL_CHECKPOINT_SYNC_INTERVAL_SETTING.getKey(), "100ms")
            .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), "100ms")));
        indexRandom(randomBoolean(), randomBoolean(), randomBoolean(), IntStream.range(0, between(200, 500))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        client().admin().indices().prepareFlush(indexName).get();
        String brokenNode = internalCluster().startDataOnlyNode();
        MockTransportService transportService =
            (MockTransportService) internalCluster().getInstance(TransportService.class, nodeWithPrimary);
        CountDownLatch newNodeStarted = new CountDownLatch(1);
        transportService.addSendBehavior((connection, requestId, action, request, options) -> {
            if (action.equals(PeerRecoveryTargetService.Actions.TRANSLOG_OPS)) {
                if (brokenNode.equals(connection.getNode().getName())) {
                    try {
                        newNodeStarted.await();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    throw new CircuitBreakingException("not enough memory for indexing", 100, 50, CircuitBreaker.Durability.TRANSIENT);
                }
            }
            connection.sendRequest(requestId, action, request, options);
        });
        assertAcked(client().admin().indices().prepareUpdateSettings(indexName)
            .setSettings(Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)));
        internalCluster().startDataOnlyNode();
        newNodeStarted.countDown();
        ensureGreen(indexName);
        transportService.clearAllRules();
    }

    public void testPeerRecoveryForClosedIndices() throws Exception {
        String indexName = "peer_recovery_closed_indices";
        internalCluster().ensureAtLeastNumDataNodes(1);
        createIndex(indexName, Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), randomBoolean())
            .put(IndexService.GLOBAL_CHECKPOINT_SYNC_INTERVAL_SETTING.getKey(), "100ms")
            .put(IndexService.RETENTION_LEASE_SYNC_INTERVAL_SETTING.getKey(), "100ms")
            .build());
        indexRandom(randomBoolean(), randomBoolean(), randomBoolean(), IntStream.range(0, randomIntBetween(1, 100))
            .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("num", n)).collect(Collectors.toList()));
        ensureActivePeerRecoveryRetentionLeasesAdvanced(indexName);
        assertAcked(client().admin().indices().prepareClose(indexName));
        int numberOfReplicas = randomIntBetween(1, 2);
        internalCluster().ensureAtLeastNumDataNodes(2 + numberOfReplicas);
        assertAcked(client().admin().indices().prepareUpdateSettings(indexName)
            .setSettings(Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numberOfReplicas)));
        ensureGreen(indexName);
        ensureActivePeerRecoveryRetentionLeasesAdvanced(indexName);
        assertAcked(client().admin().cluster().prepareUpdateSettings()
            .setPersistentSettings(Settings.builder().put("cluster.routing.allocation.enable", "primaries").build()));
        internalCluster().fullRestart();
        ensureYellow(indexName);
        if (randomBoolean()) {
            assertAcked(client().admin().indices().prepareOpen(indexName));
            client().admin().indices().prepareForceMerge(indexName).get();
        }
        assertAcked(client().admin().cluster().prepareUpdateSettings()
            .setPersistentSettings(Settings.builder().putNull("cluster.routing.allocation.enable").build()));
        ensureGreen(indexName);
        assertNoOpRecoveries(indexName);
    }

    /**
     * If the recovery source is on an old node (before <pre>{@link LegacyESVersion#V_7_2_0}</pre>) then the recovery target
     * won't have the safe commit after phase1 because the recovery source does not send the global checkpoint in the clean_files
     * step. And if the recovery fails and retries, then the recovery stage might not transition properly. This test simulates
     * this behavior by changing the global checkpoint in phase1 to unassigned.
     */
    public void testSimulateRecoverySourceOnOldNode() throws Exception {
        internalCluster().startMasterOnlyNode();
        String source = internalCluster().startDataOnlyNode();
        String indexName = "test";
        assertAcked(client().admin().indices().prepareCreate(indexName).setSettings(
            Settings.builder()
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)));
        ensureGreen(indexName);
        if (randomBoolean()) {
            indexRandom(randomBoolean(), randomBoolean(), randomBoolean(), IntStream.range(0, between(200, 500))
                .mapToObj(n -> client().prepareIndex(indexName, "_doc").setSource("f", "v")).collect(Collectors.toList()));
        }
        if (randomBoolean()) {
            client().admin().indices().prepareFlush(indexName).get();
        }
        if (randomBoolean()) {
            assertBusy(() -> {
                SyncedFlushResponse syncedFlushResponse = client().admin().indices().prepareSyncedFlush(indexName).get();
                assertThat(syncedFlushResponse.successfulShards(), equalTo(1));
            });
        }
        internalCluster().startDataOnlyNode();
        MockTransportService transportService = (MockTransportService) internalCluster().getInstance(TransportService.class, source);
        Semaphore failRecovery = new Semaphore(1);
        transportService.addSendBehavior((connection, requestId, action, request, options) -> {
            if (action.equals(PeerRecoveryTargetService.Actions.CLEAN_FILES)) {
                RecoveryCleanFilesRequest cleanFilesRequest = (RecoveryCleanFilesRequest) request;
                request = new RecoveryCleanFilesRequest(cleanFilesRequest.recoveryId(), cleanFilesRequest.requestSeqNo(),
                    cleanFilesRequest.shardId(), cleanFilesRequest.sourceMetaSnapshot(),
                    cleanFilesRequest.totalTranslogOps(), SequenceNumbers.UNASSIGNED_SEQ_NO);
            }
            if (action.equals(PeerRecoveryTargetService.Actions.FINALIZE)) {
                if (failRecovery.tryAcquire()) {
                    throw new IllegalStateException("simulated");
                }
            }
            connection.sendRequest(requestId, action, request, options);
        });
        assertAcked(client().admin().indices().prepareUpdateSettings()
            .setIndices(indexName).setSettings(Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1).build()));
        ensureGreen(indexName);
        transportService.clearAllRules();
    }

    private void ensureActivePeerRecoveryRetentionLeasesAdvanced(String indexName) throws Exception {
        assertBusy(() -> {
            Index index = resolveIndex(indexName);
            Set<String> activeRetentionLeaseIds = clusterService().state().routingTable().index(index).shard(0).shards().stream()
                .map(shardRouting -> ReplicationTracker.getPeerRecoveryRetentionLeaseId(shardRouting.currentNodeId()))
                .collect(Collectors.toSet());
            for (String node : internalCluster().nodesInclude(indexName)) {
                IndexService indexService = internalCluster().getInstance(IndicesService.class, node).indexService(index);
                if (indexService != null) {
                    for (IndexShard shard : indexService) {
                        assertThat(shard.getLastSyncedGlobalCheckpoint(), equalTo(shard.seqNoStats().getMaxSeqNo()));
                        Set<RetentionLease> activeRetentionLeases = shard.getPeerRecoveryRetentionLeases().stream()
                            .filter(lease -> activeRetentionLeaseIds.contains(lease.id())).collect(Collectors.toSet());
                        assertThat(activeRetentionLeases, hasSize(activeRetentionLeaseIds.size()));
                        for (RetentionLease lease : activeRetentionLeases) {
                            assertThat(lease.retainingSequenceNumber(), equalTo(shard.getLastSyncedGlobalCheckpoint() + 1));
                        }
                    }
                }
            }
        });
    }

    private void assertNoOpRecoveries(String indexName) {
        for (RecoveryState recovery : client().admin().indices().prepareRecoveries(indexName).get().shardRecoveryStates().get(indexName)) {
            if (recovery.getPrimary() == false) {
                assertThat(recovery.getIndex().fileDetails(), empty());
                assertThat(recovery.getTranslog().totalLocal(), equalTo(recovery.getTranslog().totalOperations()));
            }
        }
    }
}
