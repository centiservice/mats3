package io.mats3.lib_test.failure;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsEndpoint;
import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;
import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestMqInterface.MatsMessageRepresentation;
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
        String SERVICE = MatsTestHelp.endpointId("infiniteRequestRecursionToSelf");

        MatsEndpoint<DataTO, DataTO> ep = MATS.getMatsFactory().staged(SERVICE, DataTO.class,
                DataTO.class);
        ep.stage(DataTO.class, (ctx, state, msg) -> ctx.request(SERVICE, msg));
        ep.stage(DataTO.class, (ctx, state, msg) -> Assert.fail("Should never come here"));
        ep.finishSetup();

        // Act
        MATS.getMatsInitiator().initiate(init -> init
                .traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("test"))
                .to(SERVICE)
                .send(null));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestMqInterface().getDlqMessage(
                SERVICE);
        Assert.assertEquals(SERVICE, dlqMessage.getFrom());
    }

    @Test
    public void infiniteSendChildFlow() throws MatsMessageSendException, MatsBackendException {
        // Arrange
        MATS.cleanMatsFactories();
        String SERVICE = MatsTestHelp.endpointId("infiniteSendChildFlow");

        MATS.getMatsFactory().terminator(SERVICE, StateTO.class, DataTO.class,
                (ctx, state, msg) -> ctx.initiate(init -> init
                        .to(SERVICE)
                        .send(msg)));

        // Act
        MATS.getMatsInitiator().initiate(init -> init
                .traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("test"))
                .to(SERVICE)
                .send(null));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestMqInterface().getDlqMessage(
                SERVICE);
        Assert.assertEquals(SERVICE, dlqMessage.getFrom());
    }

    @Test
    public void infiniteSendChildFlow_UsingDefaultInitiator() throws MatsMessageSendException, MatsBackendException {
        // Arrange
        MATS.cleanMatsFactories();
        String SERVICE = MatsTestHelp.endpointId("infiniteSendChildFlow_UsingDefaultInitiator");

        // Here employing "magic" DefaultInitiator, which "shortcuts" to just be an invocation on ctx.initiate()..
        MATS.getMatsFactory().terminator(SERVICE, StateTO.class, DataTO.class,
                (ctx, state, msg) ->
                // This initiation is "hoisted" due to the use of DefaultInitiator, so it is exactly as if directly
                // employing the "ctx.initiate()" method
                MATS.getMatsFactory().getDefaultInitiator()
                        .initiateUnchecked(init -> init
                                // Note: Do not need to specify 'traceId' and 'from', since we're effectively using
                                // "ctx.initiate()"..
                                .to(SERVICE)
                                .send(msg)));

        // Act
        MATS.getMatsInitiator().initiate(init -> init
                .traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("test"))
                .to(SERVICE)
                .send(null));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestMqInterface().getDlqMessage(
                SERVICE);
        Assert.assertEquals(SERVICE, dlqMessage.getFrom());
    }

    @Test
    public void infiniteSendChildFlow_UsingNonDefaultInitiator() throws MatsMessageSendException, MatsBackendException {
        // Arrange
        MATS.cleanMatsFactories();
        String SERVICE = MatsTestHelp.endpointId("infiniteSendChildFlow_UsingNonDefaultInitiator");

        // Here employing "magic" DefaultInitiator, which "shortcuts" to just be an invocation on ctx.initiate()..
        MATS.getMatsFactory().terminator(SERVICE, StateTO.class, DataTO.class,
                (ctx, state, msg) ->
                // This initiation is done in a separate context, currently a new Thread - and is as such
                // completely separate from the outside Stage processing. However, we carry over the current
                // Stage context to this thread.
                MATS.getMatsFactory().getOrCreateInitiator("OtherInitiator")
                        .initiateUnchecked(init -> init
                                // Note: Do not need to specify 'traceId' and 'from', because .. magic.
                                // (Current msg is set in a "WithinStageContext" MatsFactory-specific ThreadLocal)
                                .to(SERVICE)
                                .send(msg)));

        // Act
        MATS.getMatsInitiator().initiate(init -> init
                .traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("actual_initiate"))
                .to(SERVICE)
                .send(null));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestMqInterface().getDlqMessage(
                SERVICE);
        Assert.assertEquals(SERVICE, dlqMessage.getFrom());
    }
}
