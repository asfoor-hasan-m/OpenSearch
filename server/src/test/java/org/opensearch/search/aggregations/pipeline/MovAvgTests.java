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

package org.opensearch.search.aggregations.pipeline;

import static java.util.Collections.emptyList;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;

import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.search.aggregations.BasePipelineAggregationTestCase;
import org.opensearch.search.aggregations.PipelineAggregationBuilder;
import org.opensearch.search.aggregations.pipeline.BucketHelpers.GapPolicy;
import org.opensearch.search.aggregations.pipeline.HoltWintersModel.SeasonalityType;

public class MovAvgTests extends BasePipelineAggregationTestCase<MovAvgPipelineAggregationBuilder> {

    @Override
    protected MovAvgPipelineAggregationBuilder createTestAggregatorFactory() {
        String name = randomAlphaOfLengthBetween(3, 20);
        String bucketsPath = randomAlphaOfLengthBetween(3, 20);
        MovAvgPipelineAggregationBuilder factory = new MovAvgPipelineAggregationBuilder(name, bucketsPath);
        if (randomBoolean()) {
            factory.format(randomAlphaOfLengthBetween(1, 10));
        }
        if (randomBoolean()) {
            factory.gapPolicy(randomFrom(GapPolicy.values()));
        }
        if (randomBoolean()) {
            switch (randomInt(4)) {
            case 0:
                factory.modelBuilder(new SimpleModel.SimpleModelBuilder());
                factory.window(randomIntBetween(1, 100));
                break;
            case 1:
                factory.modelBuilder(new LinearModel.LinearModelBuilder());
                factory.window(randomIntBetween(1, 100));
                break;
            case 2:
                if (randomBoolean()) {
                    factory.modelBuilder(new EwmaModel.EWMAModelBuilder());
                    factory.window(randomIntBetween(1, 100));
                } else {
                    factory.modelBuilder(new EwmaModel.EWMAModelBuilder().alpha(randomDouble()));
                    factory.window(randomIntBetween(1, 100));
                }
                break;
            case 3:
                if (randomBoolean()) {
                    factory.modelBuilder(new HoltLinearModel.HoltLinearModelBuilder());
                    factory.window(randomIntBetween(1, 100));
                } else {
                    factory.modelBuilder(new HoltLinearModel.HoltLinearModelBuilder().alpha(randomDouble()).beta(randomDouble()));
                    factory.window(randomIntBetween(1, 100));
                }
                break;
            case 4:
            default:
                if (randomBoolean()) {
                    factory.modelBuilder(new HoltWintersModel.HoltWintersModelBuilder());
                    factory.window(randomIntBetween(2, 100));
                } else {
                    int period = randomIntBetween(1, 100);
                    factory.modelBuilder(
                            new HoltWintersModel.HoltWintersModelBuilder().alpha(randomDouble()).beta(randomDouble()).gamma(randomDouble())
                                    .period(period).seasonalityType(randomFrom(SeasonalityType.values())).pad(randomBoolean()));
                    factory.window(randomIntBetween(2 * period, 200 * period));
                }
                break;
            }
        }
        factory.predict(randomIntBetween(1, 50));
        if (factory.model().canBeMinimized() && randomBoolean()) {
            factory.minimize(randomBoolean());
        }
        return factory;
    }

    @Override
    public void testFromXContent() throws IOException {
        super.testFromXContent();
        assertWarnings("The moving_avg aggregation has been deprecated in favor of the moving_fn aggregation.");
    }

    public void testDefaultParsing() throws Exception {
        MovAvgPipelineAggregationBuilder expected = new MovAvgPipelineAggregationBuilder("commits_moving_avg", "commits");
        String json = "{" +
            "    \"commits_moving_avg\": {" +
            "        \"moving_avg\": {" +
            "            \"buckets_path\": \"commits\"" +
            "        }" +
            "    }" +
            "}";
        PipelineAggregationBuilder newAgg = parse(createParser(JsonXContent.jsonXContent, json));
        assertWarnings("The moving_avg aggregation has been deprecated in favor of the moving_fn aggregation.");
        assertNotSame(newAgg, expected);
        assertEquals(expected, newAgg);
        assertEquals(expected.hashCode(), newAgg.hashCode());
    }

    /**
     * The validation should verify the parent aggregation is allowed.
     */
    public void testValidate() throws IOException {
        assertThat(validate(PipelineAggregationHelperTests.getRandomSequentiallyOrderedParentAgg(),
            new MovAvgPipelineAggregationBuilder("name", "valid")), nullValue());
    }

    /**
     * The validation should throw an IllegalArgumentException, since parent
     * aggregation is not a type of HistogramAggregatorFactory,
     * DateHistogramAggregatorFactory or AutoDateHistogramAggregatorFactory.
     */
    public void testValidateException() throws IOException {
        assertThat(validate(emptyList(), new MovAvgPipelineAggregationBuilder("name", "invalid_agg>metric")), equalTo(
                "Validation Failed: 1: moving_avg aggregation [name] must have a histogram, date_histogram "
                    + "or auto_date_histogram as parent but doesn't have a parent;"));
    }
}
