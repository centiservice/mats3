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

import jakarta.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.MatsStage;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.Sto;
import io.mats3.spring.test.MatsTestContext;

/**
 * Test that the concurrency is set correctly for {@link MatsMapping}.
 *
 * @author Endre Stølsvik 2023-12-11 11:26 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class Test_SetConcurrency_MatsMapping {

    public static final String ENDPOINT_ID = "MatsMappingConcurrencyTest";
    public static final String CONCURRENCY_DEFAULT = ".Concurrency_default";
    public static final String CONCURRENCY_5 = ".Concurrency_5";
    public static final String CONCURRENCY_7 = ".Concurrency_7";

    @Configuration
    static class MatsMappingConfiguration {
        /**
         * Terminator endpoint (no return value specified == void) - non-specified param, thus Dto.
         */
        @MatsMapping(ENDPOINT_ID + CONCURRENCY_DEFAULT)
        public void concurrencyDefault(SpringTestDataTO msg) {
        }

        /**
         * Terminator endpoint (no return value specified == void) - specified params: Dto.
         */
        @MatsMapping(endpointId = ENDPOINT_ID + CONCURRENCY_5, concurrency = "5")
        public void concurrency5(@Dto SpringTestDataTO msg) {
        }

        /**
         * "Single w/State"-endpoint (no return value specified == void) - specified params: Dto, Sto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + CONCURRENCY_7, concurrency = "7")
        public void concurrency7(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        }
    }

    @Inject
    private MatsFactory _matsFactory;

    @Test
    public void matsMappingConcurrencies() {
        Optional<MatsEndpoint<?, ?>> endpoint = _matsFactory.getEndpoint(ENDPOINT_ID + CONCURRENCY_DEFAULT);
        if (!endpoint.isPresent()) {
            throw new AssertionError("Could not get endpoint with id [" + ENDPOINT_ID + CONCURRENCY_DEFAULT + "]");
        }
        int defaultConcurrency = _matsFactory.getFactoryConfig().getConcurrency();
        Assert.assertTrue(endpoint.get().getEndpointConfig().isConcurrencyDefault());
        Assert.assertEquals(defaultConcurrency, endpoint.get().getEndpointConfig().getConcurrency());
        MatsStage<?, ?, ?> singleStage = endpoint.get().getStages().get(0);
        Assert.assertTrue(singleStage.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(defaultConcurrency, singleStage.getStageConfig().getConcurrency());

        endpoint = _matsFactory.getEndpoint(ENDPOINT_ID + CONCURRENCY_5);
        if (!endpoint.isPresent()) {
            throw new AssertionError("Could not get endpoint with id [" + ENDPOINT_ID + CONCURRENCY_5 + "]");
        }
        Assert.assertFalse(endpoint.get().getEndpointConfig().isConcurrencyDefault());
        Assert.assertEquals(5, endpoint.get().getEndpointConfig().getConcurrency());
        // NOTE: The concurrency from annotation is set on the Endpoint, not the Stage
        singleStage = endpoint.get().getStages().get(0);
        Assert.assertTrue(singleStage.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(5, singleStage.getStageConfig().getConcurrency());

        endpoint = _matsFactory.getEndpoint(ENDPOINT_ID + CONCURRENCY_7);
        if (!endpoint.isPresent()) {
            throw new AssertionError("Could not get endpoint with id [" + ENDPOINT_ID + CONCURRENCY_7 + "]");
        }
        Assert.assertFalse(endpoint.get().getEndpointConfig().isConcurrencyDefault());
        Assert.assertEquals(7, endpoint.get().getEndpointConfig().getConcurrency());
        // NOTE: The concurrency from annotation is set on the Endpoint, not the Stage
        singleStage = endpoint.get().getStages().get(0);
        Assert.assertTrue(singleStage.getStageConfig().isConcurrencyDefault());
        Assert.assertEquals(7, singleStage.getStageConfig().getConcurrency());
    }

}
