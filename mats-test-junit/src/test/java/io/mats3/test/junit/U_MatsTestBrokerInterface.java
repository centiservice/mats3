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

package io.mats3.test.junit;

import java.util.Objects;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.MatsTestHelp;

/**
 * Tests the {@link MatsTestBrokerInterface}, which is an interfacing facility to the underlying broker, allowing you to
 * inspect DLQs. The test sets up a Terminator which throws, and then sends a message to it, and then inspects the DLQ
 * to see that the message is there.
 */
public class U_MatsTestBrokerInterface {

    private static final String TERMINATOR_WHICH_THROWS = MatsTestHelp.terminator();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @SuppressWarnings("overrides")
    public static class MatsTestData {
        String string;
        Double number;

        public MatsTestData() {
            // For Jacson.
        }

        public MatsTestData(String string, Double number) {
            this.string = string;
            this.number = number;
        }

        /**
         * Used for the test's assertions.
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MatsTestData that = (MatsTestData) o;
            return Objects.equals(string, that.string) &&
                    Objects.equals(number, that.number);
        }
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR_WHICH_THROWS, MatsTestData.class, MatsTestData.class,
                (ctx, state, msg) -> {
                    throw new MatsRefuseMessageException("DLQ THIS THING!");
                });
    }

    @Test
    public void doTest() {
        // :: Arrange

        MatsTestData dto = new MatsTestData("data", Math.PI);
        MatsTestData sto = new MatsTestData("state", Math.E);

        String from = MatsTestHelp.from("test");

        // :: Act

        MessageReference[] messageReference = new MessageReference[1];

        MATS.getMatsInitiator().initiateUnchecked(init -> {
            messageReference[0] = init.traceId(MatsTestHelp.traceId())
                    .from(from)
                    .to(TERMINATOR_WHICH_THROWS)
                    .send(dto, sto);
        });

        // :: Assert

        // Fetch the magic MatsTestMqInterface for interfacing "directly" with the underlying ActiveMQ
        MatsTestBrokerInterface matsTestBrokerInterface = MATS.getMatsTestBrokerInterface();

        // Note: Throws AssertionError if not gotten in 10 seconds.
        MatsMessageRepresentation dlqMessage = matsTestBrokerInterface.getDlqMessage(TERMINATOR_WHICH_THROWS);

        // :: Assert that all the values are correct.
        Assert.assertEquals(dto, dlqMessage.getIncomingMessage(MatsTestData.class));
        Assert.assertEquals(sto, dlqMessage.getIncomingState(MatsTestData.class));
        Assert.assertEquals(from, dlqMessage.getFrom());
        Assert.assertEquals(TERMINATOR_WHICH_THROWS, dlqMessage.getTo());
        Assert.assertEquals(messageReference[0].getMatsMessageId(), dlqMessage.getMatsMessageId());
    }
}
