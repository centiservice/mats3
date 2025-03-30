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

package io.mats3.api_test.concurrency;

import org.junit.BeforeClass;
import org.junit.Test;

import io.mats3.MatsFactory;
import io.mats3.api_test.DataTO;
import io.mats3.test.MatsTestHelp;

/**
 * Tests concurrency by sending 8 requests to a service, where the processing takes 500 ms, but where the concurrency is
 * also set to 8, thereby all those 8 requests should go through in just a tad over 500 ms, not 4000 ms as if there was
 * only 1 processor. Implicitly tests "lambdaconfig" for endpoint.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator] x 1, firing off NUM_MESSAGES requests.
 *     [Service] x 8 StageProcessors (sleeping PROCESSING_TIME ms) - reply
 * [Terminator] x 1 StageProcessor, getting all the NUM_MESSAGES replies, counting down a latch.
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_ConcurrencyForEndpoint extends ATest_AbstractConcurrency {

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (endpointConfig) -> endpointConfig.setConcurrency(CONCURRENCY),
                MatsFactory.NO_CONFIG,
                (context, dto) -> {
                    // Emulate some lengthy processing...
                    MatsTestHelp.takeNap(PROCESSING_TIME);
                    return new DataTO(dto.number * 2, dto.string + ":FromService:" + (int) dto.number);
                });
    }

    @Test
    public void doTest() throws InterruptedException {
        performTest(2, "TheAnswer:FromService:");
    }
}
