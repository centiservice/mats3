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

package io.mats3.api_test.failure;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that throwing an {@link OutOfMemoryError} (an Error) will DLQ.
 *
 * @author Endre Stølsvik 2023-12-05 00:15 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_ThrowsOutOfMemoryError {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Test
    public void doTest() {
        String TERMINATOR = MatsTestHelp.terminator();
        String INITIATOR_ID = MatsTestHelp.from("test");

        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    throw new OutOfMemoryError("FAKE! TEST! This is not really an out of memory!");
                });

        // Send message directly to the "Terminator" endpoint.
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(INITIATOR_ID)
                        .to(TERMINATOR)
                        .send(dto));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(
                TERMINATOR);
        Assert.assertEquals(INITIATOR_ID, dlqMessage.getFrom());
        Assert.assertEquals(TERMINATOR, dlqMessage.getTo());
    }
}
