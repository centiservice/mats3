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

import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests MatsTrace properties functionality: A single-stage service that reads some MatsTrace properties, and sets one
 * extra, is set up. A Terminator which reads all properties is set up. Then an initiator does a request to the service,
 * setting some MatsTrace properties, and which sets replyTo(Terminator).
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - request, adding a String property, and a DataTO property.
 *     [Service] - reply - and reads the two properties (also checking the "read as JSON" functionality), and sets a new property.
 * [Terminator]  - reads all three properties.
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_TraceProperties {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static String _stringProp_service;
    private static DataTO _objectProp_service;

    private static String _stringProp_terminator;
    private static DataTO _objectProp_terminator;
    private static DataTO _objectPropFromService_terminator;

    private static String _stringProp_terminator_initiatedWithinService;
    private static DataTO _objectProp_terminator_initiatedWithinService;
    private static DataTO _objectPropFromService_terminator_initiatedWithinService;
    private static DataTO _objectPropFromService_terminator_initiatedWithinService_setInInitiation;

    private static final String TERMINATOR_FOR_MESSAGE_INITIATED_WITHIN_ENDPOINT = TERMINATOR+"-ForMessageInitiatedInService";

    private static final MatsTestLatch _secondLatch = new MatsTestLatch();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Get the String prop
                    _stringProp_service = context.getTraceProperty("stringProp", String.class);
                    // Get the Object prop
                    _objectProp_service = context.getTraceProperty("objectProp", DataTO.class);

                    // Add a new Object prop
                    // NOTE: This will NOT be a part of the following initiation!
                    context.setTraceProperty("objectPropFromService", new DataTO(Math.PI, "xyz"));

                    context.initiate(msg -> {
                        msg.traceId(MatsTestHelp.traceId())
                                .from(MatsTestHelp.from("inner_initiate"))
                                .to(TERMINATOR_FOR_MESSAGE_INITIATED_WITHIN_ENDPOINT)
                                .setTraceProperty("objectPropInitiatedWithinService", new DataTO(Math.exp(5), "123"))
                                .send(new DataTO(13.14, "qwerty"), new StateTO(42, 420.024));
                    });

                    // Return this service's value to caller.
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    // Get the String prop
                    _stringProp_terminator = context.getTraceProperty("stringProp", String.class);
                    // Get the Object prop
                    _objectProp_terminator = context.getTraceProperty("objectProp", DataTO.class);

                    // Get the Object prop set in the Service
                    _objectPropFromService_terminator = context.getTraceProperty("objectPropFromService", DataTO.class);

                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    @BeforeClass
    public static void setupTerminatorForMessageInitiatedWithinService() {
        MATS.getMatsFactory().terminator(TERMINATOR_FOR_MESSAGE_INITIATED_WITHIN_ENDPOINT, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    // Get the String prop
                    _stringProp_terminator_initiatedWithinService = context.getTraceProperty("stringProp", String.class);
                    // Get the Object prop
                    _objectProp_terminator_initiatedWithinService = context.getTraceProperty("objectProp", DataTO.class);

                    // Get the Object prop set in the Service (SHALL BE NULL)
                    _objectPropFromService_terminator_initiatedWithinService = context.getTraceProperty("objectPropFromService", DataTO.class);

                    // Get the Object prop set in initiation within the Service
                    _objectPropFromService_terminator_initiatedWithinService_setInInitiation = context.getTraceProperty("objectPropInitiatedWithinService", DataTO.class);

                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    _secondLatch.resolve(sto, dto);
                });

    }

    @Test
    public void doTest() {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                msg -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR, sto)
                        .setTraceProperty("objectProp", new DataTO(Math.E, "abc"))
                        .setTraceProperty("stringProp", "xyz")
                        .request(dto));

        // ::: Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> primaryResult = MATS.getMatsTestLatch().waitForResult();

        Assert.assertEquals(sto, primaryResult.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), primaryResult.getData());

        // :: Assert all the properties when read from Service
        Assert.assertEquals("xyz", _stringProp_service);
        Assert.assertEquals(new DataTO(Math.E, "abc"), _objectProp_service);

        // :: Assert all the properties when read from Terminator
        Assert.assertEquals("xyz", _stringProp_terminator);
        Assert.assertEquals(new DataTO(Math.E, "abc"), _objectProp_terminator);
        // .. also the one set inside the Service.
        Assert.assertEquals(new DataTO(Math.PI, "xyz"), _objectPropFromService_terminator);

        // ::: Now get the result for the secondary Terminator, which gets a message initiated from within the service.
        Result<StateTO, DataTO> resultInitiatedWithinService = _secondLatch.waitForResult();
        Assert.assertEquals(new StateTO(42, 420.024), resultInitiatedWithinService.getState());
        Assert.assertEquals(new DataTO(13.14, "qwerty"), resultInitiatedWithinService.getData());

        // :: Assert all the properties when read from second Terminator (for the message initiated within the service)
        Assert.assertEquals("xyz", _stringProp_terminator_initiatedWithinService);
        Assert.assertEquals(new DataTO(Math.E, "abc"), _objectProp_terminator_initiatedWithinService);
        // .. also the one set in the initiation
        Assert.assertEquals(new DataTO(Math.exp(5), "123"), _objectPropFromService_terminator_initiatedWithinService_setInInitiation);
        // The one set inside the Service, before the initiation started, should NOT be a part of the new initiation
        Assert.assertNull(_objectPropFromService_terminator_initiatedWithinService);
    }
}
