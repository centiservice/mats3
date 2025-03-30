/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.spring.basics;

import java.util.Optional;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.MatsStage;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.test.MatsTestContext;

/**
 * Test that the concurrency is set correctly for {@link MatsClassMapping} and {@link Stage}.
 *
 * @author Endre Stølsvik 2023-12-11 15:50 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class Test_SetConcurrency_MatsClassMapping {

    public static final String ENDPOINT_ID = "MatsClassMappingConcurrencyTest";
    public static final String CONCURRENCY_DEFAULT = ".Concurrency_default";
    public static final String CONCURRENCY_5 = ".Concurrency_5";

    @Configuration
    static class MatsClassMapping_ConcurrencyEndpointDefault {
        @MatsClassMapping(ENDPOINT_ID + CONCURRENCY_DEFAULT)
        public static class MatsClassMapping_Leaf {
            @Stage(Stage.INITIAL)
            public void initialStage_defaultConcurrency(SpringTestDataTO msg) {

            }

            @Stage(ordinal = 10, concurrency = "5")
            public void stage1_concurrency5(SpringTestDataTO msg) {
            }

            @Stage(ordinal = 20, concurrency = "7")
            public SpringTestDataTO stage2_concurrency7(SpringTestDataTO msg) {
                return null;
            }
        }
    }

    @Configuration
    static class MatsClassMapping_ConcurrencyEndpoint5 {
        @MatsClassMapping(endpointId = ENDPOINT_ID + CONCURRENCY_5, concurrency = "5")
        public static class MatsClassMapping_Leaf {
            @Stage(ordinal = Stage.INITIAL, concurrency = "9")
            public void initialStage_concurrency9(SpringTestDataTO msg) {
            }

            @Stage(10)
            public void stage1_defaultConcurrency(SpringTestDataTO msg) {
            }

            @Stage(ordinal = 20, concurrency = "3")
            public SpringTestDataTO stage2_concurrency3(SpringTestDataTO msg) {
                return null;
            }
        }
    }

    @Inject
    private MatsFactory _matsFactory;

    @Test
    public void matsClassMappingConcurrencies() {

        // :: Get the endpoint with default concurrency

        Optional<MatsEndpoint<?, ?>> endpoint = _matsFactory.getEndpoint(ENDPOINT_ID + CONCURRENCY_DEFAULT);
        if (!endpoint.isPresent()) {
            throw new AssertionError("Could not get endpoint with id [" + ENDPOINT_ID + CONCURRENCY_DEFAULT + "]");
        }
        int defaultConcurrency = _matsFactory.getFactoryConfig().getConcurrency();
        Assert.assertTrue(endpoint.get().getEndpointConfig().isConcurrencyDefault());
        Assert.assertEquals(defaultConcurrency, endpoint.get().getEndpointConfig().getConcurrency());

        MatsStage<?, ?, ?> stage0 = endpoint.get().getStages().get(0);
        Assert.assertTrue(stage0.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(defaultConcurrency, stage0.getStageConfig().getConcurrency());

        MatsStage<?, ?, ?> stage1 = endpoint.get().getStages().get(1);
        Assert.assertFalse(stage1.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(5, stage1.getStageConfig().getConcurrency());

        MatsStage<?, ?, ?> stage2 = endpoint.get().getStages().get(2);
        Assert.assertFalse(stage2.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(7, stage2.getStageConfig().getConcurrency());

        // :: Get the endpoint with concurrency 5

        endpoint = _matsFactory.getEndpoint(ENDPOINT_ID + CONCURRENCY_5);
        if (!endpoint.isPresent()) {
            throw new AssertionError("Could not get endpoint with id [" + ENDPOINT_ID + CONCURRENCY_5 + "]");
        }

        int endpointConcurrency = endpoint.get().getEndpointConfig().getConcurrency();

        Assert.assertFalse(endpoint.get().getEndpointConfig().isConcurrencyDefault());
        Assert.assertEquals(5, endpointConcurrency);

        stage0 = endpoint.get().getStages().get(0);
        Assert.assertFalse(stage0.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(9, stage0.getStageConfig().getConcurrency());

        stage1 = endpoint.get().getStages().get(1);
        Assert.assertTrue(stage1.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(endpointConcurrency, stage1.getStageConfig().getConcurrency());

        stage2 = endpoint.get().getStages().get(2);
        Assert.assertFalse(stage2.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(3, stage2.getStageConfig().getConcurrency());

    }

}
