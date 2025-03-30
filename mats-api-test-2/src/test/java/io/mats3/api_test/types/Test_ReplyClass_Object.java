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
 * Tests that a service that specifies Object as its Reply type can really return whatever it wants, and that whatever
 * is returned will be serialized as the type it is, not what was specified (i.e. Object), which the receiver will
 * receive correctly. And that if it returns null, the receiver will return null.
 * <p />
 * <b>NOTE: If the ReplyClass==Void.TYPE (i.e. void.class), the only thing you can answer is 'null'.</b>
 *
 * @see Test_DifferingFromSpecifiedTypes_ForReplyAndIncoming
 * @see Test_ServiceInvokedWithDifferentClass
 *
 * @author Endre Stølsvik 2020-04-03 22:00 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_ReplyClass_Object {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService_ReplyClass_Object() {
        MATS.getMatsFactory().single(ENDPOINT, Object.class, String.class,
                (context, incoming) -> {
                    switch (incoming) {
                        case "String":
                            return "Stringeling";
                        case "DataTO":
                            return new DataTO(1, "DataTO");
                        case "StateTO":
                            return new StateTO(2, Math.PI);
                        default:
                            return null;
                    }
                });
    }

    @BeforeClass
    public static void setupTerminator_String() {
        MATS.getMatsFactory().terminator(TERMINATOR + ".String", StateTO.class, String.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR.String MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    @BeforeClass
    public static void setupTerminator_DataTO() {
        MATS.getMatsFactory().terminator(TERMINATOR + ".DataTO", StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR.DataTO MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    @BeforeClass
    public static void setupTerminator_StateTO() {
        MATS.getMatsFactory().terminator(TERMINATOR + ".StateTO", StateTO.class, StateTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR.StateTO MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    public void test(String replyToTerminator, String serviceRequestedReply, Object expected) {
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.randomId())
                        .from(MatsTestHelp.from(replyToTerminator + serviceRequestedReply))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR + "." + replyToTerminator, sto)
                        .request(serviceRequestedReply));

        // Wait synchronously for terminator to finish.
        Result<StateTO, Object> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(expected, result.getData());
    }

    @Test
    public void testWithTerminatorString() {
        test("String", "String", "Stringeling");
    }

    @Test
    public void testWithTerminatorDataTO() {
        test("DataTO", "DataTO", new DataTO(1, "DataTO"));
    }

    @Test
    public void testWithTerminatorStateTO() {
        test("StateTO", "StateTO", new StateTO(2, Math.PI));
    }

    @Test
    public void testWithTerminatorString_null() {
        test("String", "null", null);
    }

    @Test
    public void testWithTerminatorDataTO_null() {
        test("DataTO", "null", null);
    }

    @Test
    public void testWithTerminatorStateTO_null() {
        test("StateTO", "null", null);
    }
}
