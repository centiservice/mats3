package io.mats3.api_test.stash;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

/**
 * More involved test of stash/unstash: Two-stage service invoking "Single-stage" leaf service, where a stash/unstash
 * cycle is performed on each of the two stages and in the leaf service. Elements of the Context are tested within the
 * "continuation" process lambda, i.e. checking that it "feels identical" to be within the unstashed process lambda as
 * within the original stage's process lambda). Furthermore, unstashing more than once is tested: The stash from the
 * second stage of the multistage is unstashed with a reply to the waiting terminator TWICE.
 *
 * @author Endre Stølsvik - 2018-10-24 - http://endre.stolsvik.com
 */
public class Test_MultiLevelStash {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    // Stash Stage0
    private static byte[] _stash_stage0;
    private static final MatsTestLatch _stashLatch_Stage0 = new MatsTestLatch();

    // Stash Stage1
    private static byte[] _stash_stage1;
    private static final MatsTestLatch _stashLatch_Stage1 = new MatsTestLatch();

    // Stash Leaf
    private static byte[] _stash_leaf;
    private static MatsTestLatch _stashLatch_leaf = new MatsTestLatch();

    @BeforeClass
    public static void setupStagedService() {
        MatsEndpoint<DataTO, StateTO> staged = MATS.getMatsFactory().staged(ENDPOINT, DataTO.class, StateTO.class);
        staged.stage(DataTO.class, ((context, state, incomingDto) -> {
            _stash_stage0 = context.stash();
            _stashLatch_Stage0.resolve(context, state, incomingDto);
            // NOTE! Not request, next, nor reply..! (We've stashed)
        }));
        // NOTICE!! NOT employing "lastStage(..)", as that requires us to reply with something.
        // Instead using ordinary stage(..), and then invoking .finishedSetup().
        staged.stage(DataTO.class, ((context, state, incomingDto) -> {
            _stash_stage1 = context.stash();
            _stashLatch_Stage1.resolve(context, state, incomingDto);
            // NOTE! Not request, next, nor reply..! (We've stashed)
        }));
        staged.finishSetup();
    }

    @BeforeClass
    public static void setupService() {
        MatsEndpoint<DataTO, StateTO> staged = MATS.getMatsFactory().staged(ENDPOINT + ".Leaf", DataTO.class,
                StateTO.class);
        // Cannot employ a single-stage, since that requires a reply (by returning something, even null).
        // Thus, employing multistage, with only one stage, where we do not invoke context.reply(..)
        staged.stage(DataTO.class, ((context, state, incomingDto) -> {
            _stash_leaf = context.stash();
            _stashLatch_leaf.resolve(context, state, incomingDto);
            // NOTE! Not request, next, nor reply..! (We've stashed)
        }));
        staged.finishSetup();
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(context, sto, dto);
                });

    }

    @Test
    public void doTest() throws InterruptedException, MatsMessageSendException, MatsBackendException {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        String traceId = MatsTestHelp.traceId();
        String from = MatsTestHelp.from("test");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(traceId)
                        .from(from)
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // ### STASHED AT ENDPOINT (stage0) - Wait synchronously for stash to appear
        Result<StateTO, DataTO> stashContext_stage0 = _stashLatch_Stage0.waitForResult();

        Assert.assertEquals(from, stashContext_stage0.getContext().getFromStageId());
        Assert.assertEquals(ENDPOINT, stashContext_stage0.getContext().getEndpointId());
        Assert.assertEquals(ENDPOINT, stashContext_stage0.getContext().getStageId());

        // Unstash!
        MATS.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash_stage0,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    state.number1 = 1337;
                    state.number2 = Math.PI;
                    context.request(ENDPOINT + ".Leaf", new DataTO(incomingDto.number * 2, incomingDto.string
                            + ":RequestToLeaf"));
                }));

        // ### STASHED AT LEAF - Wait synchronously for stash to appear
        Result<StateTO, DataTO> stashContext_leaf = _stashLatch_leaf.waitForResult();

        Assert.assertEquals(ENDPOINT, stashContext_leaf.getContext().getFromStageId());
        Assert.assertEquals(ENDPOINT + ".Leaf", stashContext_leaf.getContext().getEndpointId());
        Assert.assertEquals(ENDPOINT + ".Leaf", stashContext_leaf.getContext().getStageId());

        byte[][] restash = new byte[1][];
        // Unstash!
        MATS.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash_leaf,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    // Restashing bytes. Absurd idea, but everything should work inside the "continued" process lambda.
                    restash[0] = context.stash();
                    context.reply(new DataTO(incomingDto.number * 3, incomingDto.string + ":FromLeaf"));
                }));

        // ### STASHED AT ENDPOINT.stage1 - Wait synchronously for stash to appear
        Result<StateTO, DataTO> stashContext_stage1 = _stashLatch_Stage1.waitForResult();

        Assert.assertEquals(ENDPOINT + ".Leaf", stashContext_stage1.getContext().getFromStageId());
        Assert.assertEquals(ENDPOINT, stashContext_stage1.getContext().getEndpointId());
        Assert.assertEquals(ENDPOINT + ".stage1", stashContext_stage1.getContext().getStageId());
        Assert.assertEquals(1337, stashContext_stage1.getState().number1);
        Assert.assertEquals(Math.PI, stashContext_stage1.getState().number2, 0d);
        // Check that the restash is the same as the stash (which with current impl is true)
        Assert.assertArrayEquals(_stash_leaf, restash[0]);

        String messageId_AtStage1 = stashContext_stage1.getContext().getSystemMessageId();
        Assert.assertNotNull(messageId_AtStage1);
        log.info("Here's the MessageId at stage1: [" + messageId_AtStage1 + "]");

        // Unstash!
        MATS.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash_stage1,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    // State is present
                    Assert.assertEquals(1337, state.number1);
                    Assert.assertEquals(Math.PI, state.number2, 0d);
                    Assert.assertEquals(messageId_AtStage1, context.getSystemMessageId());
                    context.reply(new DataTO(incomingDto.number * 5, incomingDto.string + ":FromService"));
                }));

        // ### PROCESS FINISHED @ Terminator, first time! - Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        // :: Assert that the flow went through as expected, with the traceId intact.
        Assert.assertEquals(traceId, result.getContext().getTraceId());
        Assert.assertNotNull(result.getContext().getSystemMessageId());
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 5, dto.string + ":RequestToLeaf:FromLeaf:FromService"),
                result.getData());

        // :: USING STASH FROM ENDPOINT.stage1 AGAIN TO UNSTASH TWICE - the terminator will get its answer once more.

        // Unstash AGAIN!
        MATS.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash_stage1,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    // Different reply this time around
                    Assert.assertEquals(messageId_AtStage1, context.getSystemMessageId());
                    context.reply(new DataTO(incomingDto.number * 7, incomingDto.string + ":FromServiceAGAIN"));
                }));

        // ### PROCESS FINISHED @ Terminator, second time! - Wait synchronously for terminator to finish for a second
        // time.
        Result<StateTO, DataTO> result_Again = MATS.getMatsTestLatch().waitForResult();
        // :: Assert that the flow went through as expected, with the traceId intact.
        Assert.assertEquals(traceId, result_Again.getContext().getTraceId());
        Assert.assertNotNull(result_Again.getContext().getSystemMessageId());
        Assert.assertEquals(sto, result_Again.getState());
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 7, dto.string + ":RequestToLeaf:FromLeaf:FromServiceAGAIN"),
                result_Again.getData());
    }
}