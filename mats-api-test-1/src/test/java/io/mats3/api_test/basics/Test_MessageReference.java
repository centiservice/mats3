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

package io.mats3.api_test.basics;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsInitiator.MessageReference;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Test that the incoming MatsMessageId is the same as we got when sending it.
 *
 * @author Endre Stølsvik 2019-06-30 22:47 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_MessageReference {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(context, sto, dto);
                });
    }

    @Test
    public void doTest() {
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        MessageReference[] msgRef = new MessageReference[1];
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                    MessageReference messageReference = msg.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("test"))
                            .to(TERMINATOR)
                            .send(dto, sto);
                    msgRef[0] = messageReference;
                });

        log.info("MessageReference.getMatsMessageId() = [" + msgRef[0].getMatsMessageId() + "].");
        Assert.assertNotNull(msgRef[0].getMatsMessageId());

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(msgRef[0].getMatsMessageId(), result.getContext().getMatsMessageId());
    }

}
