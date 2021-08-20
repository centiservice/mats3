package io.mats3.intercept_test;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.api.intercept.MatsInitiateInterceptor.MatsInitiateInterceptOutgoingMessages;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsEditableOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MessageType;
import io.mats3.api.intercept.MatsStageInterceptor.MatsStageInterceptOutgoingMessages;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests the extra-state functionality, by setting up a 4-stage Service which invokes a leaf, then do next, then invoke
 * another leaf, then return. Initiator calls the Service, with replyTo a Terminator. Extra-state is added to initiation
 * REQUEST to Service (destined for Terminator), and added to to the REQUEST from Service.initial (destined for
 * Service.stage1, and all later stages of Service)
 *
 * @author Endre Stølsvik - 2021-04-14 20:58 - http://endre.stolsvik.com
 */
public class Test_ExtraState {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String EPID_LEAF1 = MatsTestHelp.endpointId("leaf1");
    private static final String EPID_LEAF2 = MatsTestHelp.endpointId("leaf2");
    private static final String EPID_SERVICE = MatsTestHelp.service();
    private static final String EPID_TERMINATOR = MatsTestHelp.terminator();

    private static DataTO __data1ForTerminator;
    private static DataTO __data2ForTerminator;

    private static DataTO __data1ForServiceStage1;
    private static DataTO __data2ForServiceStage1;

    private static DataTO __data1ForServiceStage2;
    private static DataTO __data2ForServiceStage2;
    private static DataTO __data3ForServiceStage2;

    private static DataTO __data1ForServiceStage3;
    private static DataTO __data2ForServiceStage3;
    private static DataTO __data3ForServiceStage3;

    @Test
    public void doTest() {
        MATS.cleanMatsFactories();

        // :: ARRANGE

        // :: Create the endpoints and terminators
        MATS.getMatsFactory().single(EPID_LEAF1, DataTO.class, DataTO.class, (ctx, msg) -> msg);

        MATS.getMatsFactory().single(EPID_LEAF2, DataTO.class, DataTO.class, (ctx, msg) -> msg);

        MatsEndpoint<DataTO, StateTO> ep_service = MATS.getMatsFactory().staged(EPID_SERVICE, DataTO.class,
                StateTO.class);
        ep_service.stage(DataTO.class, (ctx, state, msg) -> ctx.request(EPID_LEAF1, msg));
        ep_service.stage(DataTO.class, (ctx, state, msg) -> ctx.next(msg));
        ep_service.stage(DataTO.class, (ctx, state, msg) -> ctx.request(EPID_LEAF2, msg));
        ep_service.lastStage(DataTO.class, (ctx, state, msg) -> msg);

        MATS.getMatsFactory().terminator(EPID_TERMINATOR, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            MATS.getMatsTestLatch().resolve(state, msg);
        });

        InitiateAndStageInterceptor initiateAndStageInterceptor = new InitiateAndStageInterceptor();
        MATS.getMatsInterceptableMatsFactory().addInitiationInterceptor(initiateAndStageInterceptor);
        MATS.getMatsInterceptableMatsFactory().addStageInterceptor(initiateAndStageInterceptor);

        // :: Create the interceptor adding extra-state, and validating that state

        // :: ACT

        MATS.getMatsInitiator().initiateUnchecked(init -> {
            init.traceId(MatsTestHelp.traceId())
                    .from(MatsTestHelp.from("doTest"))
                    .to(EPID_SERVICE)
                    .replyTo(EPID_TERMINATOR, new StateTO(42, Math.E))
                    .request(new DataTO(2, "two"));
        });

        // :: ASSERT

        Result<Object, Object> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(new DataTO(2, "two"), result.getData());
        Assert.assertEquals(new StateTO(42, Math.E), result.getState());

        // Assert terminator extra state
        Assert.assertEquals(new DataTO(1, "one"), __data1ForTerminator);
        Assert.assertEquals(new DataTO(2, "two"), __data2ForTerminator);

        // Assert stage1 extra state
        Assert.assertEquals(new DataTO(3, "three"), __data1ForServiceStage1);
        Assert.assertEquals(new DataTO(4, "four"), __data2ForServiceStage1);
        // Assert stage2 extra state
        Assert.assertEquals(new DataTO(3, "three"), __data1ForServiceStage2);
        Assert.assertEquals(new DataTO(4, "four"), __data2ForServiceStage2);
        Assert.assertEquals(new DataTO(5, "five"), __data3ForServiceStage2);
        // Assert stage3 extra state
        Assert.assertEquals(new DataTO(3, "three"), __data1ForServiceStage3);
        Assert.assertEquals(new DataTO(4, "four"), __data2ForServiceStage3);
        Assert.assertEquals(new DataTO(5, "five"), __data3ForServiceStage3);
    }

    /**
     * Interceptor implementing both Initiate and Stage outgoing messages, AND stage received. Adds extra-state on the
     * initiation REQUEST to Service, destined for Terminator, AND on outgoing REQUEST of Service, destined for
     * Service.stage1.
     */
    private static class InitiateAndStageInterceptor implements
            MatsInitiateInterceptOutgoingMessages, MatsStageInterceptOutgoingMessages {
        @Override
        public void initiateInterceptOutgoingMessages(InitiateInterceptOutgoingMessagesContext context) {
            // There should only be one message here, as it is the REQUEST up to the service.
            Assert.assertEquals(1, context.getOutgoingMessages().size());
            // Fetch it
            MatsEditableOutgoingMessage outgoing = context.getOutgoingMessages().get(0);
            // Assert that it is a REQUEST
            Assert.assertEquals(MessageType.REQUEST, outgoing.getMessageType());

            // Add 2 x extra-state, which should be available at Terminator
            outgoing.setExtraStateForReplyOrNext("extra1_to_terminator", new DataTO(1, "one"));
            outgoing.setExtraStateForReplyOrNext("extra2_to_terminator", new DataTO(2, "two"));
        }

        @Override
        public void stageInterceptOutgoingMessages(StageInterceptOutgoingMessageContext context) {
            // If this is SERVICE entry point, add extra state to outgoing
            // NOTE: This should exist for all subsequent stages!
            if (EPID_SERVICE.equals(context.getStage().getStageConfig().getStageId())) {
                // There should only be one message here, as it is the REQUEST up to the Leaf.
                Assert.assertEquals(1, context.getOutgoingMessages().size());
                // Fetch it
                MatsEditableOutgoingMessage outgoing = context.getOutgoingMessages().get(0);
                // Assert that it is a REQUEST
                Assert.assertEquals(MessageType.REQUEST, outgoing.getMessageType());

                // Add 2 x extra-state, which should be available at next stage (stage1)
                outgoing.setExtraStateForReplyOrNext("extra1_to_Service.stage1", new DataTO(3, "three"));
                outgoing.setExtraStateForReplyOrNext("extra2_to_Service.stage1", new DataTO(4, "four"));
            }

            // ?: Is this the next-call?
            if ((EPID_SERVICE + ".stage1").equals(context.getStage().getStageConfig().getStageId())) {
                // -> Yes, this is the next-call
                // There should only be one message here, as it is the REQUEST up to the Leaf.
                Assert.assertEquals(1, context.getOutgoingMessages().size());
                // Fetch it
                MatsEditableOutgoingMessage outgoing = context.getOutgoingMessages().get(0);
                // Assert that it is a NEXT
                Assert.assertEquals(MessageType.NEXT, outgoing.getMessageType());
                // Add 1 more extra-state
                outgoing.setExtraStateForReplyOrNext("extra3_to_Service.nextCall", new DataTO(5, "five"));
            }
        }

        @Override
        public void stageReceived(StageReceivedContext context) {
            // If this is Terminator, read extra-state
            if (EPID_TERMINATOR.equals(context.getStage().getStageConfig().getStageId())) {
                __data1ForTerminator = context.getIncomingExtraState("extra1_to_terminator", DataTO.class).orElse(null);
                __data2ForTerminator = context.getIncomingExtraState("extra2_to_terminator", DataTO.class).orElse(null);
            }

            // If FROM Leaf1 (i.e. this is stage1 of Service), read extra-state
            if (EPID_LEAF1.equals(context.getProcessContext().getFromStageId())) {
                __data1ForServiceStage1 = context.getIncomingExtraState("extra1_to_Service.stage1", DataTO.class)
                        .orElse(null);
                __data2ForServiceStage1 = context.getIncomingExtraState("extra2_to_Service.stage1", DataTO.class)
                        .orElse(null);
            }

            // If the incoming next-call to stage 2 (from stage 1), read extra-state
            if (MessageType.NEXT == context.getIncomingMessageType()) {
                __data1ForServiceStage2 = context.getIncomingExtraState("extra1_to_Service.stage1", DataTO.class)
                        .orElse(null);
                __data2ForServiceStage2 = context.getIncomingExtraState("extra2_to_Service.stage1", DataTO.class)
                        .orElse(null);
                __data3ForServiceStage2 = context.getIncomingExtraState("extra3_to_Service.nextCall", DataTO.class)
                        .orElse(null);
            }

            // If FROM Leaf2 (i.e. this is stage3 of Service), read extra-state
            if (EPID_LEAF2.equals(context.getProcessContext().getFromStageId())) {
                __data1ForServiceStage3 = context.getIncomingExtraState("extra1_to_Service.stage1", DataTO.class)
                        .orElse(null);
                __data2ForServiceStage3 = context.getIncomingExtraState("extra2_to_Service.stage1", DataTO.class)
                        .orElse(null);
                __data3ForServiceStage3 = context.getIncomingExtraState("extra3_to_Service.nextCall", DataTO.class)
                        .orElse(null);
            }
        }
    }
}
