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

package io.mats3.api_test.nestedinitiate;

import org.junit.After;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests the functionality whereby a nested initiate shall not prefix with existing stageId if the traceId starts with a
 * exclamation.
 *
 * @author Endre Stølsvik 2023-05-15 23:07 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_ExclamationCharTraceId {

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @After
    public void cleanMatsFactory() {
        MATS.cleanMatsFactories();
    }

    @Test
    public void nestedInitiateStandard() {
        String terminatorThatInitiatesId = MatsTestHelp.terminator("initiator");
        String terminatorThatIsSentToId = MatsTestHelp.terminator("receives");

        MATS.getMatsFactory().terminator(terminatorThatIsSentToId, StateTO.class, DataTO.class,
                (ctx, state, msg) -> {
                    MATS.getMatsTestLatch().resolve(ctx, state, msg);
                });

        MATS.getMatsFactory().terminator(terminatorThatInitiatesId, StateTO.class, DataTO.class,
                (ctx, state, msg) -> ctx.initiate(init -> init.traceId("Appended")
                        .from(MatsTestHelp.from("appends"))
                        .to(terminatorThatIsSentToId)
                        .send(new DataTO(1, "two"))));

        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("!Test")
                .from(MatsTestHelp.from())
                .to(terminatorThatInitiatesId)
                .send(new DataTO(2, "three")));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals("Test|Appended", result.getContext().getTraceId());
    }

    @Test
    public void nestedInitiateWithPipeCharPreceedingTraceId() {
        String terminatorThatInitiatesId = MatsTestHelp.terminator("initiator");
        String terminatorThatIsSentToId = MatsTestHelp.terminator("receives");

        MATS.getMatsFactory().terminator(terminatorThatIsSentToId, StateTO.class, DataTO.class,
                (ctx, state, msg) -> {
                    MATS.getMatsTestLatch().resolve(ctx, state, msg);
                });

        MATS.getMatsFactory().terminator(terminatorThatInitiatesId, StateTO.class, DataTO.class,
                (ctx, state, msg) -> ctx.initiate(init -> init.traceId("!Absolute")
                        .from(MatsTestHelp.from("appends"))
                        .to(terminatorThatIsSentToId)
                        .send(new DataTO(1, "two"))));

        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("!Test")
                .from(MatsTestHelp.from())
                .to(terminatorThatInitiatesId)
                .send(new DataTO(2, "three")));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals("Absolute", result.getContext().getTraceId());
    }

}
