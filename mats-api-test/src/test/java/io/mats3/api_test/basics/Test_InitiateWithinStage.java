package io.mats3.api_test.basics;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests the initiation within a stage functionality.
 * <p/>
 * FIVE Terminators are set up: One for the normal service return, two for the initiations that are done using the
 * service's context.initiate(...), plus one for an initiation done directly on the MatsFactory.getDefaultInitiator()
 * (which when running within a Mats Stage should take part in the Stage's transactional demarcation), plus one more for
 * an initiation done directly on a MatsFactory.getOrCreateInitiator("NON default") from within the Stage (NOT
 * recommended way to code!).
 * <p/>
 * A single-stage service is set up - which initiates three new messages to the three extra Terminators (2 x initiations
 * on ProcessContext, and 1 initiation directly on the MatsFactory), and returns a result to the normal Terminator.
 * <p/>
 * TWO tests are performed:
 * <ol>
 * <li>An initiator does a request to the service, setting replyTo(Terminator) - which should result in all FIVE
 * Terminators getting its message</li>
 * <li>An initiator does a request to the service, setting replyTo(Terminator), <b>BUT DIRECTS THE SERVICE TO THROW
 * after it has done all the in-stage initiations</b>. This shall result in the message to the SERVICE going to DLQ, and
 * all the In-stage initiated messages should NOT be sent - EXCEPT for the one using Non-Default Initiator, which do not
 * participate in the Stage-specific transactional demarcation.</li>
 * </ol>
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - request
 *     [Service] - reply + sends to Terminator "stageInit1", to "stageInit2", to "stageInit_withMatsFactory_DefaultInitiator", AND to "stageInit_withMatsFactory_NonDefaultInitiator"
 * [Terminator]          +         [Terminator "stageInit1"] [T "stageInit2"] [T "stageInit_withMatsFactory_DefaultInitiator"]     [T "stageInit_withMatsFactory_NonDefaultInitiator"]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_InitiateWithinStage {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final MatsTestLatch matsTestLath = new MatsTestLatch();
    private static final MatsTestLatch matsTestLath_stageInit1 = new MatsTestLatch();
    private static final MatsTestLatch matsTestLath_stageInit2 = new MatsTestLatch();
    private static final MatsTestLatch matsTestLath_stageInit_withMatsFactory_DefaultInitiator = new MatsTestLatch();
    private static final MatsTestLatch matsTestLath_stageInit_withMatsFactory_NonDefaultInitiator = new MatsTestLatch();

    private static volatile String _traceId;
    private static volatile String _traceId_stageInit1;
    private static volatile String _traceId_stageInit2;
    private static volatile String _traceId_stageInit_withMatsFactory_DefaultInitiator;
    private static volatile String _traceId_stageInit_withMatsFactory_NonDefaultInitiator;

    private static volatile boolean _alreadySentUsingNonDefaultInitiator;

    @Before
    public void resetAlreadySentUsingNonDefaultInitiator() {
        _alreadySentUsingNonDefaultInitiator = false;
    }

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Fire off two new initiations to the two Terminators
                    context.initiate(
                            msg -> {
                                /*
                                 * Initiate on the ProcessContext.
                                 *
                                 * These will partake in the transaction demarcation of this stage, thus if the stage
                                 * later exits with an exception (which it will in one of the tests, look further down
                                 * in this stage's code), the messages will not be sent after all.
                                 */
                                msg.traceId("subtraceId1")
                                        .to(TERMINATOR + "_stageInit1")
                                        .send(new DataTO(Math.E, "xyz"));
                                msg.traceId("subtraceId2")
                                        .to(TERMINATOR + "_stageInit2")
                                        .send(new DataTO(-Math.E, "abc"), new StateTO(Integer.MAX_VALUE, Math.PI));

                                /*
                                 * Initiate on MatsFactory.getDefaultInitiator(), not the ProcessContext.
                                 *
                                 * NOTICE!!! THIS WILL ALSO HAPPEN *INSIDE* THE TRANSACTION DEMARCATION FOR THIS STAGE!
                                 * Thus, this initiation is exactly similar to the ones above, and thus if the stage
                                 * later exits with an exception, this message won't be sent after all.
                                 *
                                 * Such an initiation, using matsFactory.getDefaultInitiator(), USED to happen outside
                                 * the transaction demarcation for this stage, but I've changed this so that getting the
                                 * *default* initiator inside a stage's thread context will return you a ThreadLocal
                                 * bound "magic initiator". The rationale here is that you then can make methods which
                                 * can be invoked "out of context", OR be invoked "in stage", and if the latter, will
                                 * participate in the same transactional demarcation as the stage.
                                 */
                                MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init -> {
                                    init.traceId("subtraceId3_with_MatsFactory.getDefaultInitiator()")
                                            .from("MatsFactory.getDefaultInitiator")
                                            .to(TERMINATOR + "_stageInit_withMatsFactory_DefaultInitiator")
                                            .send(new DataTO(Math.PI, "Endre"));
                                });

                                /*
                                 * Initiate on MatsFactory.getOrCreateInitiator("NOT default!"), not the ProcessContext.
                                 *
                                 * NOTICE!!! THIS WILL HAPPEN *OUTSIDE* THE TRANSACTION DEMARCATION FOR THIS STAGE! That
                                 * means that this 'send' basically happens immediately, and it will happen even if some
                                 * exception is raised later in the code. It will not be committed nor rolled back along
                                 * with the rest of operations happening in the stage - it is separate.
                                 *
                                 * It is very much like (actually: pretty much literally) getting a new Connection from
                                 * a DataSource while already being within a transaction on an existing Connection:
                                 * Anything happening on this new Connection is totally separate from the existing
                                 * transaction demarcation.
                                 *
                                 * This also means that the send initiated on such an independent Initiator will happen
                                 * before the two sends initiated on the ProcessContext above, since this new
                                 * transaction will commit before the transaction surrounding the Mats process lambda
                                 * that we're within.
                                 *
                                 * It also means that if this stage later exits out with an exception (as it does in
                                 * one of the tests), the MQ retries will result in /multiple/ messages being sent, as
                                 * the sending is immediate.
                                 *
                                 * Note that with the change 2021-04-12 (one year later!), where I finalized the logic
                                 * of inside/outside, now the original "WithinStageContext" is kept even through such an
                                 * "outside transaction demarcation", thus the TraceId set below ("New TraceId") will be
                                 * appended to the existing traceId.
                                 *
                                 * Please check through the logs to see what happens.
                                 *
                                 * TAKEAWAY: This should not be a normal way to code! But it is possible to do.
                                 */
                                // ?: Have we already sent this message in this test round?
                                // (NOTE: This guard is to avoid sending it multiple times in face of redeliveries, read
                                // comment about this above).
                                if (!_alreadySentUsingNonDefaultInitiator) {
                                    // -> No, not already sent in this test, so do it now.
                                    MATS.getMatsFactory().getOrCreateInitiator("NOT default").initiateUnchecked(
                                            init -> {
                                                init.traceId("subtraceId4_with_MatsFactory.getOrCreateInitiator(...)")
                                                        .from("New Init")
                                                        .to(TERMINATOR
                                                                + "_stageInit_withMatsFactory_NonDefaultInitiator")
                                                        .send(new DataTO(-Math.PI, "Stølsvik"));
                                            });
                                    _alreadySentUsingNonDefaultInitiator = true;
                                }

                                // NOTE! This is where we in one of the tests specify that the stage should throw.

                                // ?: Should we throw at this point?
                                if (dto.string.equals("THROW!")) {
                                    throw new RuntimeException("This should lead to DLQ after MQ has performed"
                                            + " its retries!");
                                }

                            });
                    // Return our result
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
    }

    @BeforeClass
    public static void setupTerminators() {
        // :: Termintor for the normal service REPLY
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("Normal TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId = context.getTraceId();
                    matsTestLath.resolve(sto, dto);
                });
        // :: Two terminators for the initiations within the stage executed on the ProcessContext of the stage
        MATS.getMatsFactory().terminator(TERMINATOR + "_stageInit1", StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("StageInit1 TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId_stageInit1 = context.getTraceId();
                    matsTestLath_stageInit1.resolve(sto, dto);
                });
        MATS.getMatsFactory().terminator(TERMINATOR + "_stageInit2", StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("StageInit2 TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId_stageInit2 = context.getTraceId();
                    matsTestLath_stageInit2.resolve(sto, dto);
                });
        // :: Terminator for the initiation within the stage executed on MatsFactory.getDefaultInitiator()
        MATS.getMatsFactory().terminator(TERMINATOR + "_stageInit_withMatsFactory_DefaultInitiator", StateTO.class,
                DataTO.class,
                (context, sto, dto) -> {
                    log.debug("StageInit2 TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId_stageInit_withMatsFactory_DefaultInitiator = context.getTraceId();
                    matsTestLath_stageInit_withMatsFactory_DefaultInitiator.resolve(sto, dto);
                });
        // :: Terminator for the initiation within the stage executed on MatsFactory.getOrCreateInitiator(...)
        MATS.getMatsFactory().terminator(TERMINATOR + "_stageInit_withMatsFactory_NonDefaultInitiator",
                StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("StageInit2 TERMINATOR MatsTrace:\n" + context.toString());
                    _traceId_stageInit_withMatsFactory_NonDefaultInitiator = context.getTraceId();
                    matsTestLath_stageInit_withMatsFactory_NonDefaultInitiator.resolve(sto, dto);
                });
    }

    @Test
    public void serviceCompletesSuccessfully() {
        // :: Send message to the single stage service.
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        String traceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                msg -> msg.traceId(traceId)
                        .from(MatsTestHelp.from("serviceCompletesSuccessfully"))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // :: Wait synchronously for all terminators terminators to finish.
        // "Normal" Terminator from the service call
        Result<StateTO, DataTO> result = matsTestLath.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
        Assert.assertEquals(traceId, _traceId);

        // Terminator "stageInit1", for the first initiation within the service's stage
        Result<StateTO, DataTO> result_stageInit1 = matsTestLath_stageInit1.waitForResult();
        Assert.assertEquals(new StateTO(0, 0), result_stageInit1.getState());
        Assert.assertEquals(new DataTO(Math.E, "xyz"), result_stageInit1.getData());
        Assert.assertEquals(traceId + "|subtraceId1", _traceId_stageInit1);

        // Terminator "stageInit2", for the second initiation within the service's stage
        Result<StateTO, DataTO> result_stageInit2 = matsTestLath_stageInit2.waitForResult();
        Assert.assertEquals(new StateTO(Integer.MAX_VALUE, Math.PI), result_stageInit2.getState());
        Assert.assertEquals(new DataTO(-Math.E, "abc"), result_stageInit2.getData());
        Assert.assertEquals(traceId + "|subtraceId2", _traceId_stageInit2);

        // Terminator "stageInit_withMatsFactory", for the initiation using MatsFactory within the service's stage
        Result<StateTO, DataTO> result_withMatsFactory_DefaultInitiator = matsTestLath_stageInit_withMatsFactory_DefaultInitiator
                .waitForResult();
        Assert.assertEquals(new StateTO(0, 0), result_withMatsFactory_DefaultInitiator.getState());
        Assert.assertEquals(new DataTO(Math.PI, "Endre"), result_withMatsFactory_DefaultInitiator.getData());
        Assert.assertEquals(traceId + "|subtraceId3_with_MatsFactory.getDefaultInitiator()",
                _traceId_stageInit_withMatsFactory_DefaultInitiator);

        // Terminator "stageInit_withMatsFactory", for the initiation using MatsFactory within the service's stage
        Result<StateTO, DataTO> result_withMatsFactory_NonDefaultInitiator = matsTestLath_stageInit_withMatsFactory_NonDefaultInitiator
                .waitForResult();
        Assert.assertEquals(new StateTO(0, 0), result_withMatsFactory_NonDefaultInitiator.getState());
        Assert.assertEquals(new DataTO(-Math.PI, "Stølsvik"), result_withMatsFactory_NonDefaultInitiator.getData());
        Assert.assertEquals(traceId + "|subtraceId4_with_MatsFactory.getOrCreateInitiator(...)",
                _traceId_stageInit_withMatsFactory_NonDefaultInitiator);
    }

    @Test
    public void serviceThrowsAndOnlyNonDefaultInitiatorShouldPersevere() throws InterruptedException {
        // :: Send message to the single stage service.
        DataTO dto = new DataTO(42, "THROW!");
        StateTO sto = new StateTO(420, 420.024);
        String traceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                msg -> msg.traceId(traceId)
                        .from(MatsTestHelp.from("serviceThrowsAndOnlyNonDefaultInitiatorShouldPersevere"))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // The the messages sent to the service should appear in the DLQ!

        MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(SERVICE);
        Assert.assertEquals(traceId, dlqMessage.getTraceId());
        Assert.assertEquals(SERVICE, dlqMessage.getTo());

        // NOTE NOTE! The SINGLE message sent with the NON-default initiator should come through!

        // Terminator "stageInit_withMatsFactory_NonDefaultInitiator"
        Result<StateTO, DataTO> result_withMatsFactory_NonDefaultInitiator = matsTestLath_stageInit_withMatsFactory_NonDefaultInitiator
                .waitForResult();
        Assert.assertEquals(new StateTO(0, 0), result_withMatsFactory_NonDefaultInitiator.getState());
        Assert.assertEquals(new DataTO(-Math.PI, "Stølsvik"), result_withMatsFactory_NonDefaultInitiator.getData());
        Assert.assertEquals(traceId + "|subtraceId4_with_MatsFactory.getOrCreateInitiator(...)",
                _traceId_stageInit_withMatsFactory_NonDefaultInitiator);

        // HOWEVER, NONE of the IN-STAGE-DEMARCATED messages should have come!

        // "Normal" Terminator from the service call - Wait a bit for this in case these messages slacks in the queue.
        try {
            matsTestLath.waitForResult(500);
            throw new RuntimeException("NOTE: This cannot be an AssertionError! It should not have happened.");
        }
        catch (AssertionError ae) {
            /* good - there should not be a message */
        }

        // Terminator "stageInit1", for the first initiation within the service's stage - we've already waited a bit.
        try {
            matsTestLath_stageInit1.waitForResult(5);
            throw new RuntimeException("NOTE: This cannot be an AssertionError! It should not have happened.");
        }
        catch (AssertionError ae) {
            /* good - there should not be a message */
        }

        // Terminator "stageInit2", for the second initiation within the service's stage
        try {
            matsTestLath_stageInit2.waitForResult(5);
            throw new RuntimeException("NOTE: This cannot be an AssertionError! It should not have happened.");
        }
        catch (AssertionError ae) {
            /* good - there should not be a message */
        }

        // Terminator "stageInit_withMatsFactory", for the initiation using MatsFactory.getDefaultInitiator()
        try {
            matsTestLath_stageInit_withMatsFactory_DefaultInitiator.waitForResult(5);
            throw new RuntimeException("NOTE: This cannot be an AssertionError! It should not have happened.");
        }
        catch (AssertionError ae) {
            /* good - there should not be a message */
        }
    }
}
