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
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.cluster.coordination;

import org.opensearch.OpenSearchException;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.Diff;
import org.opensearch.cluster.coordination.CoordinationMetadata.VotingConfiguration;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.node.Node;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.transport.CapturingTransport;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;

public class PublicationTransportHandlerTests extends OpenSearchTestCase {

    public void testDiffSerializationFailure() {
        DeterministicTaskQueue deterministicTaskQueue =
            new DeterministicTaskQueue(Settings.builder().put(Node.NODE_NAME_SETTING.getKey(), "test").build(), random());
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        final DiscoveryNode localNode = new DiscoveryNode("localNode", buildNewFakeTransportAddress(), Version.CURRENT);
        final TransportService transportService = new CapturingTransport().createTransportService(Settings.EMPTY,
            deterministicTaskQueue.getThreadPool(),
            TransportService.NOOP_TRANSPORT_INTERCEPTOR,
            x -> localNode,
            clusterSettings, Collections.emptySet());
        final PublicationTransportHandler handler = new PublicationTransportHandler(transportService,
            writableRegistry(), pu -> null, (pu, l) -> {});
        transportService.start();
        transportService.acceptIncomingRequests();

        final DiscoveryNode otherNode = new DiscoveryNode("otherNode", buildNewFakeTransportAddress(), Version.CURRENT);
        final ClusterState clusterState = CoordinationStateTests.clusterState(2L, 1L,
            DiscoveryNodes.builder().add(localNode).add(otherNode).localNodeId(localNode.getId()).build(),
            VotingConfiguration.EMPTY_CONFIG, VotingConfiguration.EMPTY_CONFIG, 0L);

        final ClusterState unserializableClusterState = new ClusterState(clusterState.version(),
            clusterState.stateUUID(), clusterState) {
            @Override
            public Diff<ClusterState> diff(ClusterState previousState) {
                return new Diff<ClusterState>() {
                    @Override
                    public ClusterState apply(ClusterState part) {
                        fail("this diff shouldn't be applied");
                        return part;
                    }

                    @Override
                    public void writeTo(StreamOutput out) throws IOException {
                        throw new IOException("Simulated failure of diff serialization");
                    }
                };
            }
        };

        OpenSearchException e = expectThrows(OpenSearchException.class, () ->
            handler.newPublicationContext(new ClusterChangedEvent("test", unserializableClusterState, clusterState)));
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(IOException.class));
        assertThat(e.getCause().getMessage(), containsString("Simulated failure of diff serialization"));
    }
}
