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

package io.mats3.api_test.types;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests an endpoint specifying incomingClass=Void.TYPE - <b>which results in the incoming message always being
 * null</b>, even if it was sent an actual object.
 *
 * @author Endre Stølsvik 2020-04-03 21:12 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_IncomingClass_Void {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupTerminator() {
        // Notice how this ignores the incoming message in the Assert inside here.
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, Void.class,
                (context, sto, msg) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());

                    // When accepting Void.TYPE (i.e. void.class), we get null as message, whatever is sent in.
                    Assert.assertNull(msg);

                    MATS.getMatsTestLatch().resolve(sto, new DataTO(sto.number2, "" + sto.number1));
                });
    }

    public void test(Object messageToSend) {
        // To have something to "resolve" with and assert against, we give it additional state.
        StateTO sto = new StateTO(42, Math.E);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.randomId())
                        .from(MatsTestHelp.from("test_" + toString()))
                        .to(TERMINATOR)
                        // NOTE: The Terminator will roundly ignore whatever we send it.
                        .send(messageToSend, sto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(new DataTO(sto.number2, "" + sto.number1), result.getData());
        Assert.assertEquals(sto, result.getState());
    }

    @Test
    public void testWithIncomingDataTO() {
        test(new DataTO(42, "TheAnswer"));
    }

    @Test
    public void testWithIncomingString() {
        test("test123");
    }

    @Test
    public void testWithNullAsIncoming() {
        test(null);
    }

}
