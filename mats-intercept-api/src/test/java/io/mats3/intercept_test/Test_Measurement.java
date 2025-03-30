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

package io.mats3.intercept_test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Testing the metrics-adding of stages and initiators, picked up by interceptors.
 *
 * TODO: De-shit this test, i.e. make it into a test.
 *
 * @author Endre Stølsvik 2021-10-21 12:04 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_Measurement {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final CountDownLatch _latch = new CountDownLatch(2);

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Measurements
                    context.logMeasurement("stage.metric1", "Stage Unit test metric #1, 0 tags", "units", Math.E);
                    context.logMeasurement("stage.metric2", "Stage Unit test metric #2, 1 tag", "units", Math.PI,
                            "labelkey2A-M", "labelvalue2A");
                    context.logMeasurement("stage.metric3", "Stage Unit test metric #3, 2 tags", "units", Math.E * 2,
                            "labelkey3A-M", "labelvalue3A", "labelkey3B-M", "labelvalue3B");

                    // Timings
                    context.logTimingMeasurement("stage.timing1", "Stage Unit test timing #1, 0 tags", 1_000);
                    context.logTimingMeasurement("stage.timing2", "Stage Unit test timing #2, 1 tag", 1_000_000,
                            "labelkey2A-T", "labelvalue2A");
                    context.logTimingMeasurement("stage.timing3", "Stage Unit test timing #3, 2 tags", 1_000_000_000,
                            "labelkey3A-T", "labelvalue3A", "labelkey3B-T", "labelvalue3B");
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    _latch.countDown();
                });

    }

    @Test
    public void doTest_1() throws InterruptedException {
        doTest_internal();
    }

    @Test
    public void doTest_2() throws InterruptedException {
        doTest_internal();
    }

    public void doTest_internal() throws InterruptedException {
        MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId(MatsTestHelp.traceId() + "_First")
                    .from(MatsTestHelp.from("test"))
                    .to(ENDPOINT)
                    .replyTo(TERMINATOR, new DataTO(0, "null"))
                    .request(new DataTO(1, "First message"));
            init.traceId(MatsTestHelp.traceId() + "_Second")
                    .from(MatsTestHelp.from("test"))
                    .to(ENDPOINT)
                    .replyTo(TERMINATOR, new DataTO(0, "null"))
                    .request(new DataTO(2, "Second message"));

            // Measurements
            init.logMeasurement("init.metric1", "Init Unit test metric #1, 0 tags", "units", Math.E);
            init.logMeasurement("init.metric2", "Init Unit test metric #2, 1 tag", "units", Math.PI,
                    "labelkey2A-M", "labelvalue2A");
            init.logMeasurement("init.metric3", "Init Unit test metric #3, 2 tags", "units", Math.E * 2,
                    "labelkey3A-M", "labelvalue3A", "labelkey3B-M", "labelvalue3B");

            // Timings
            init.logTimingMeasurement("init.timing1", "Init Unit test timing #1, 0 tags", 1_000);
            init.logTimingMeasurement("init.timing2", "Init Unit test timing #2, 1 tag", 1_000_000,
                    "labelkey2A-T", "labelvalue2A");
            init.logTimingMeasurement("init.timing3", "Init Unit test timing #3, 2 tags", 1_000_000_000,
                    "labelkey3A-T", "labelvalue3A", "labelkey3B-T", "labelvalue3B");
        });

        _latch.await(5, TimeUnit.SECONDS);
    }
}
