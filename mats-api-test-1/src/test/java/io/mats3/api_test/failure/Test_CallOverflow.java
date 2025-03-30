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

import io.mats3.MatsEndpoint;
import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that the "stack overflow" and "call overflow" protections works.
 *
 * @author Endre Stølsvik 2021-04-11 23:52 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_CallOverflow {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Test
    public void infiniteRequestRecursionToSelf() throws MatsMessageSendException, MatsBackendException {
        // Arrange
        MATS.cleanMatsFactories();
        String ENDPOINT = MatsTestHelp.endpoint("infiniteRequestRecursionToSelf");

        MatsEndpoint<DataTO, DataTO> ep = MATS.getMatsFactory().staged(ENDPOINT, DataTO.class, DataTO.class);
        ep.stage(DataTO.class, (ctx, state, msg) -> ctx.request(ENDPOINT, msg)); // Requesting ourselves
        ep.stage(DataTO.class, (ctx, state, msg) -> Assert.fail("Should never come here"));
        ep.finishSetup();

        // Act
        MATS.getMatsInitiator().initiate(init -> init
                .traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("test"))
                .to(ENDPOINT)
                .send(null));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(
                ENDPOINT);
        Assert.assertEquals(ENDPOINT, dlqMessage.getFrom());
    }

    @Test
    public void infiniteSendChildFlow() throws MatsMessageSendException, MatsBackendException {
        // Arrange
        MATS.cleanMatsFactories();
        String ENDPOINT = MatsTestHelp.endpoint("infiniteSendChildFlow");

        MATS.getMatsFactory().terminator(ENDPOINT, StateTO.class, DataTO.class,
                (ctx, state, msg) -> ctx.initiate(init -> init.to(ENDPOINT).send(msg))); // Creating child flow

        // Act
        MATS.getMatsInitiator().initiate(init -> init
                .traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("test"))
                .to(ENDPOINT)
                .send(null));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(
                ENDPOINT);
        Assert.assertEquals(ENDPOINT, dlqMessage.getFrom());
    }

    @Test
    public void infiniteSendChildFlow_UsingDefaultInitiator() throws MatsMessageSendException, MatsBackendException {
        // Arrange
        MATS.cleanMatsFactories();
        String ENDPOINT = MatsTestHelp.endpoint("infiniteSendChildFlow_UsingDefaultInitiator");

        // Here employing "magic" DefaultInitiator, which "shortcuts" to just be an invocation on ctx.initiate()..
        MATS.getMatsFactory().terminator(ENDPOINT, StateTO.class, DataTO.class,
                (ctx, state, msg) ->
                // This initiation is "hoisted" due to the use of DefaultInitiator, so it is exactly as if directly
                // employing the "ctx.initiate()" method
                MATS.getMatsFactory().getDefaultInitiator()
                        .initiateUnchecked(init -> init
                                // Note: Do not need to specify 'traceId' and 'from', since we're effectively using
                                // "ctx.initiate()"..
                                .to(ENDPOINT)
                                .send(msg)));

        // Act
        MATS.getMatsInitiator().initiate(init -> init
                .traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("test"))
                .to(ENDPOINT)
                .send(null));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(
                ENDPOINT);
        Assert.assertEquals(ENDPOINT, dlqMessage.getFrom());
    }

    @Test
    public void infiniteSendChildFlow_UsingNonDefaultInitiator() throws MatsMessageSendException, MatsBackendException {
        // Arrange
        MATS.cleanMatsFactories();
        String ENDPOINT = MatsTestHelp.endpoint("infiniteSendChildFlow_UsingNonDefaultInitiator");

        // Here employing "magic" DefaultInitiator, which "shortcuts" to just be an invocation on ctx.initiate()..
        MATS.getMatsFactory().terminator(ENDPOINT, StateTO.class, DataTO.class,
                (ctx, state, msg) ->
                // This initiation is done in a separate context, currently a new Thread - and is as such
                // completely separate from the outside Stage processing. However, we carry over the current
                // Stage context to this thread.
                MATS.getMatsFactory().getOrCreateInitiator("OtherInitiator")
                        .initiateUnchecked(init -> init
                                // Note: Do not need to specify 'traceId' and 'from', because .. magic.
                                // (Current msg is set in a "WithinStageContext" MatsFactory-specific ThreadLocal)
                                .to(ENDPOINT)
                                .send(msg)));

        // Act
        MATS.getMatsInitiator().initiate(init -> init
                .traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("actual_initiate"))
                .to(ENDPOINT)
                .send(null));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(
                ENDPOINT);
        Assert.assertEquals(ENDPOINT, dlqMessage.getFrom());
    }
}
