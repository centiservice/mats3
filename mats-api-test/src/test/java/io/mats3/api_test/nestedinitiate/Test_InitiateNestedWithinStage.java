package io.mats3.api_test.nestedinitiate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.util.RandomString;

/**
 * This is similar to {@link Test_NestedInInitiate}, only that it checks how this works within a stage processing. An
 * additional concept here is the "context initiate", since a ProcessContext directly has the ability to initiate
 * messages. This should work identical to the {@link MatsFactory#getDefaultInitiator() default initiator}, also with
 * respect to nesting.
 *
 * @author Endre Stølsvik 2022-10-20 20:08 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_InitiateNestedWithinStage {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final int NAP_TIME = 1000;

    // The endpoint which performs initiations whose ordering we want to test
    private static final String ENDPOINT_INITIATING = MatsTestHelp.endpoint();

    private static final String TERMINATOR0 = MatsTestHelp.terminator("0");
    private static final String TERMINATOR1 = MatsTestHelp.terminator("1");
    private static final String TERMINATOR2 = MatsTestHelp.terminator("2");

    private static final MatsTestLatch __latch0 = new MatsTestLatch();
    private static final MatsTestLatch __latch1 = new MatsTestLatch();
    private static final MatsTestLatch __latch2 = new MatsTestLatch();

    @BeforeClass
    public static void setupBrokerAndMatsFactoryAndTerminators() {
        // :: Create the "generic" terminators used for all the tests.
        MATS.getMatsFactory().terminator(TERMINATOR0, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("####\nTerminator0 MatsTrace:\n" + context.toString());
                    __latch0.resolve(context, sto, dto);
                });
        MATS.getMatsFactory().terminator(TERMINATOR1, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("####\nTerminator1 MatsTrace:\n" + context.toString());
                    __latch1.resolve(context, sto, dto);
                });
        MATS.getMatsFactory().terminator(TERMINATOR2, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("####\nTerminator2 MatsTrace:\n" + context.toString());
                    __latch2.resolve(context, sto, dto);
                });

        MATS.getMatsFactory().waitForReceiving(30_000);
    }

    @After
    public void removeTestEndpont() {
        MATS.getMatsFactory().getEndpoint(ENDPOINT_INITIATING)
                .map(matsEndpoint -> matsEndpoint.remove(30_000));
    }

    @Test
    public void direct_Context_and_Default_and_Named() throws MatsBackendException, MatsMessageSendException,
            InterruptedException {
        // :: ARRANGE

        DataTO d0 = new DataTO(1, "one_" + RandomString.randomCorrelationId());
        DataTO d1 = new DataTO(2, "two_" + RandomString.randomCorrelationId());
        DataTO d2 = new DataTO(3, "three_" + RandomString.randomCorrelationId());

        String initiatingTraceId = MatsTestHelp.traceId();

        String traceId0 = "nestingIsOk_outer_1:" + RandomString.partTraceId();
        String traceId1 = "nestingIsOk_nested_A_2:" + RandomString.partTraceId();
        String traceId2 = "nestingIsOk_nested_B_3:" + RandomString.partTraceId();

        @SuppressWarnings("unchecked")
        Result<StateTO, DataTO>[] results = new Result[3];
        long[] timestamp = new long[3];

        Thread t1 = new Thread(() -> {
            results[0] = __latch0.waitForResult();
            timestamp[0] = System.currentTimeMillis();
        }, "one");
        t1.start();

        Thread t2 = new Thread(() -> {
            results[1] = __latch1.waitForResult();
            timestamp[1] = System.currentTimeMillis();
        }, "two");
        t2.start();

        Thread t3 = new Thread(() -> {
            results[2] = __latch2.waitForResult();
            timestamp[2] = System.currentTimeMillis();
        }, "three");
        t3.start();

        MATS.getMatsFactory().terminator(ENDPOINT_INITIATING, void.class, void.class,
                (ctx, state, msg) -> {
                    // Directly on the stage's context's initiator
                    ctx.initiate(init0 -> init0.traceId(traceId0)
                            .from(traceId0)
                            .to(TERMINATOR0)
                            .send(d0));

                    // Fetch DefaultInitiator, and initiate
                    // NOTICE: This shall go INSIDE the existing transactional demarcation!
                    // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
                    MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(
                            init1 -> init1.traceId(traceId1)
                                    .from(traceId1)
                                    .to(TERMINATOR1)
                                    .send(d1));

                    // Fetch Specific Initiator, and initiate
                    // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                    // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                    MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(
                            init2 -> init2.traceId(traceId2)
                                    .from(traceId2)
                                    .to(TERMINATOR2)
                                    .send(d2));

                    // Sleep here, to prove a point..!
                    // -> Message 3 shall be received way before Term0 and Term1, since they won't be sent until after
                    // the lambda and hence the tx context finishes.
                    MatsTestHelp.takeNap(NAP_TIME);
                });

        // :: ACT

        // Perform the initiation to the endpoint above which executes the actual test initiations..!

        long startTime = System.currentTimeMillis();

        MATS.getMatsFactory().getDefaultInitiator().initiate(init -> init.traceId(initiatingTraceId)
                .from("nesting")
                .to(ENDPOINT_INITIATING)
                .send(null));

        // Wait for the threads.
        t1.join(30_000);
        t2.join(30_000);
        t3.join(30_000);

        // :: ASSERT

        // .. Assert the data and in particular the traceIds

        Assert.assertNotNull("No result for Term0", results[0]);
        Assert.assertEquals(d0, results[0].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId0, results[0].getContext().getTraceId());

        Assert.assertNotNull("No result for Term1", results[1]);
        Assert.assertEquals(d1, results[1].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId1, results[1].getContext().getTraceId());

        Assert.assertNotNull("No result for Term3", results[2]);
        Assert.assertEquals(d2, results[2].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId2, results[2].getContext().getTraceId());

        // .. Now assert the ordering:
        // Term2 shall have gotten its message way before Term0 and Term1.
        Assert.assertTrue("Term2 should have gotten message before Term0", timestamp[0] > timestamp[2]);
        Assert.assertTrue("Term2 should have gotten message before Term1", timestamp[1] > timestamp[2]);
        // Term0 and Term1 shall have taken at least NAP_TIME
        Assert.assertTrue("Term0 should have taken at least NAP_TIME time.", timestamp[0] - startTime > NAP_TIME);
        Assert.assertTrue("Term1 should have taken at least NAP_TIME time.", timestamp[1] - startTime > NAP_TIME);
    }

    @Test
    public void nested_Within_Context_Do_Default_and_Named() throws MatsBackendException,
            MatsMessageSendException,
            InterruptedException {
        // :: ARRANGE

        DataTO d0 = new DataTO(1, "one_" + RandomString.randomCorrelationId());
        DataTO d1 = new DataTO(2, "two_" + RandomString.randomCorrelationId());
        DataTO d2 = new DataTO(3, "three_" + RandomString.randomCorrelationId());

        String initiatingTraceId = MatsTestHelp.traceId();

        String traceId0 = "nestingIsOk_outer_1:" + RandomString.partTraceId();
        String traceId1 = "nestingIsOk_nested_A_2:" + RandomString.partTraceId();
        String traceId2 = "nestingIsOk_nested_B_3:" + RandomString.partTraceId();

        @SuppressWarnings("unchecked")
        Result<StateTO, DataTO>[] results = new Result[3];
        long[] timestamp = new long[3];

        Thread t1 = new Thread(() -> {
            results[0] = __latch0.waitForResult();
            timestamp[0] = System.currentTimeMillis();
        }, "one");
        t1.start();

        Thread t2 = new Thread(() -> {
            results[1] = __latch1.waitForResult();
            timestamp[1] = System.currentTimeMillis();
        }, "two");
        t2.start();

        Thread t3 = new Thread(() -> {
            results[2] = __latch2.waitForResult();
            timestamp[2] = System.currentTimeMillis();
        }, "three");
        t3.start();

        MATS.getMatsFactory().terminator(ENDPOINT_INITIATING, void.class, void.class, (ctx, state, msg) -> {
            // Directly on the stage's context's initiator
            ctx.initiate(init0 -> {
                init0.traceId(traceId0)
                        .from(traceId0)
                        .to(TERMINATOR0)
                        .send(d0);

                // .. within the stage's context's initiator:

                // Fetch DefaultInitiator, and initiate
                // NOTICE: This shall go INSIDE the existing transactional demarcation!
                // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
                MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init1 -> {
                    init1.traceId(traceId1)
                            .from(traceId1)
                            .to(TERMINATOR1)
                            .send(d1);
                });

                // Fetch Specific Initiator, and initiate
                // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init2 -> {
                    init2.traceId(traceId2)
                            .from(traceId2)
                            .to(TERMINATOR2)
                            .send(d2);
                });
            });

            // Sleep here, to prove a point..!
            // -> Message 3 shall be received way before Term0 and Term1, since they won't be sent until after the
            // lambda and hence the tx context finishes.
            MatsTestHelp.takeNap(NAP_TIME);

        });

        // :: ACT

        // Perform the initiation to the endpoint above which executes the actual test initiations..!

        long startTime = System.currentTimeMillis();

        MATS.getMatsFactory().getDefaultInitiator().initiate(init -> init.traceId(initiatingTraceId)
                .from("nesting")
                .to(ENDPOINT_INITIATING)
                .send(null));

        // Wait for the threads.
        t1.join(30_000);
        t2.join(30_000);
        t3.join(30_000);

        // :: ASSERT

        // .. Assert the data and in particular the traceIds

        Assert.assertNotNull("No result for Term0", results[0]);
        Assert.assertEquals(d0, results[0].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId0, results[0].getContext().getTraceId());

        Assert.assertNotNull("No result for Term1", results[1]);
        Assert.assertEquals(d1, results[1].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId1, results[1].getContext().getTraceId());

        Assert.assertNotNull("No result for Term3", results[2]);
        Assert.assertEquals(d2, results[2].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId2, results[2].getContext().getTraceId());

        // .. Now assert the ordering:
        // Term2 shall have gotten its message way before Term0 and Term1.
        Assert.assertTrue("Term2 should have gotten message before Term0", timestamp[0] > timestamp[2]);
        Assert.assertTrue("Term2 should have gotten message before Term1", timestamp[1] > timestamp[2]);
        // Term0 and Term1 shall have taken at least NAP_TIME
        Assert.assertTrue("Term0 should have taken at least NAP_TIME time.", timestamp[0] - startTime > NAP_TIME);
        Assert.assertTrue("Term1 should have taken at least NAP_TIME time.", timestamp[1] - startTime > NAP_TIME);
    }

    @Test
    public void nested_Within_Default_Do_Context_and_Nested_Named() throws MatsBackendException,
            MatsMessageSendException,
            InterruptedException {
        // :: ARRANGE

        DataTO d0 = new DataTO(1, "one_" + RandomString.randomCorrelationId());
        DataTO d1 = new DataTO(2, "two_" + RandomString.randomCorrelationId());
        DataTO d2 = new DataTO(3, "three_" + RandomString.randomCorrelationId());

        String initiatingTraceId = MatsTestHelp.traceId();

        String traceId0 = "nestingIsOk_outer_1:" + RandomString.partTraceId();
        String traceId1 = "nestingIsOk_nested_A_2:" + RandomString.partTraceId();
        String traceId2 = "nestingIsOk_nested_B_3:" + RandomString.partTraceId();

        @SuppressWarnings("unchecked")
        Result<StateTO, DataTO>[] results = new Result[3];
        long[] timestamp = new long[3];

        Thread t1 = new Thread(() -> {
            results[0] = __latch0.waitForResult();
            timestamp[0] = System.currentTimeMillis();
        }, "one");
        t1.start();

        Thread t2 = new Thread(() -> {
            results[1] = __latch1.waitForResult();
            timestamp[1] = System.currentTimeMillis();
        }, "two");
        t2.start();

        Thread t3 = new Thread(() -> {
            results[2] = __latch2.waitForResult();
            timestamp[2] = System.currentTimeMillis();
        }, "three");
        t3.start();

        MATS.getMatsFactory().terminator(ENDPOINT_INITIATING, void.class, void.class, (ctx, state, msg) -> {
            // Fetch DefaultInitiator, and initiate
            // NOTICE: This shall go INSIDE the existing transactional demarcation, i.e. stage.
            // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
            MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init0 -> {
                init0.traceId(traceId0)
                        .from(traceId0)
                        .to(TERMINATOR0)
                        .send(d0);

                // .. within the default initiator:

                // Init on the stage's context's initiator
                // This should be equivalent to getDefaultInitiator(), so since it is nested within default, it should
                // be hoisted to that, and thus only be sent when stage lambda exits
                ctx.initiate(init1 -> {
                    init1.traceId(traceId1)
                            .from(traceId1)
                            .to(TERMINATOR1)
                            .send(d1);

                    // .. within the stage's context's initiator:

                    // Fetch named Initiator, and initiate
                    // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                    // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                    MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init2 -> {
                        init2.traceId(traceId2)
                                .from(traceId2)
                                .to(TERMINATOR2)
                                .send(d2);
                    });
                });
            });

            // Sleep here, to prove a point..!
            // -> Message 3 shall be received way before Term0 and Term1, since they won't be sent until after the
            // lambda and hence the tx context finishes.
            MatsTestHelp.takeNap(NAP_TIME);

        });

        // :: ACT

        // Perform the initiation to the endpoint above which executes the actual test initiations..!

        long startTime = System.currentTimeMillis();

        MATS.getMatsFactory().getDefaultInitiator().initiate(init -> init.traceId(initiatingTraceId)
                .from("nesting")
                .to(ENDPOINT_INITIATING)
                .send(null));

        // Wait for the threads.
        t1.join(30_000);
        t2.join(30_000);
        t3.join(30_000);

        // :: ASSERT

        // .. Assert the data and in particular the traceIds

        Assert.assertNotNull("No result for Term0", results[0]);
        Assert.assertEquals(d0, results[0].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId0, results[0].getContext().getTraceId());

        Assert.assertNotNull("No result for Term1", results[1]);
        Assert.assertEquals(d1, results[1].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId1, results[1].getContext().getTraceId());

        Assert.assertNotNull("No result for Term3", results[2]);
        Assert.assertEquals(d2, results[2].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId2, results[2].getContext().getTraceId());

        // .. Now assert the ordering:
        // Term2 shall have gotten its message way before Term0 and Term1.
        Assert.assertTrue("Term2 should have gotten message before Term0", timestamp[0] > timestamp[2]);
        Assert.assertTrue("Term2 should have gotten message before Term1", timestamp[1] > timestamp[2]);
        // Term0 and Term1 shall have taken at least NAP_TIME
        Assert.assertTrue("Term0 should have taken at least NAP_TIME time.", timestamp[0] - startTime > NAP_TIME);
        Assert.assertTrue("Term1 should have taken at least NAP_TIME time.", timestamp[1] - startTime > NAP_TIME);
    }

    @Test
    public void nested_Within_Default_Do_Named_and_Nested_Context() throws MatsBackendException,
            MatsMessageSendException,
            InterruptedException {
        // :: ARRANGE

        DataTO d0 = new DataTO(1, "one_" + RandomString.randomCorrelationId());
        DataTO d1 = new DataTO(2, "two_" + RandomString.randomCorrelationId());
        DataTO d2 = new DataTO(3, "three_" + RandomString.randomCorrelationId());

        String initiatingTraceId = MatsTestHelp.traceId();

        String traceId0 = "nestingIsOk_outer_1:" + RandomString.partTraceId();
        String traceId1 = "nestingIsOk_nested_A_2:" + RandomString.partTraceId();
        String traceId2 = "nestingIsOk_nested_B_3:" + RandomString.partTraceId();

        @SuppressWarnings("unchecked")
        Result<StateTO, DataTO>[] results = new Result[3];
        long[] timestamp = new long[3];

        Thread t1 = new Thread(() -> {
            results[0] = __latch0.waitForResult();
            timestamp[0] = System.currentTimeMillis();
        }, "one");
        t1.start();

        Thread t2 = new Thread(() -> {
            results[1] = __latch1.waitForResult();
            timestamp[1] = System.currentTimeMillis();
        }, "two");
        t2.start();

        Thread t3 = new Thread(() -> {
            results[2] = __latch2.waitForResult();
            timestamp[2] = System.currentTimeMillis();
        }, "three");
        t3.start();

        MATS.getMatsFactory().terminator(ENDPOINT_INITIATING, void.class, void.class, (ctx, state, msg) -> {
            // Fetch DefaultInitiator, and initiate
            // NOTICE: This shall go INSIDE the existing transactional demarcation, i.e. stage.
            // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
            MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init0 -> {
                init0.traceId(traceId0)
                        .from(traceId0)
                        .to(TERMINATOR0)
                        .send(d0);

                // .. within the default initiator:

                // Fetch named Initiator, and initiate
                // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init1 -> {
                    init1.traceId(traceId1)
                            .from(traceId1)
                            .to(TERMINATOR1)
                            .send(d1);

                    // .. within the named initiator:

                    // Init on the stage's context's initiator
                    // This should be equivalent to getDefaultInitiator(), so since it is nested within named, it
                    // should be hoisted to that, and thus be sent before stage lambda exits
                    ctx.initiate(init2 -> {
                        init2.traceId(traceId2)
                                .from(traceId2)
                                .to(TERMINATOR2)
                                .send(d2);
                    });
                });
            });

            // Sleep here, to prove a point..!
            // -> Term1 and Term2 shall be received way before Term0, since the first won't be sent until after the
            // stage lambda and hence the tx context finishes.
            MatsTestHelp.takeNap(NAP_TIME);
        });

        // :: ACT

        // Perform the initiation to the endpoint above which executes the actual test initiations..!

        long startTime = System.currentTimeMillis();

        MATS.getMatsFactory().getDefaultInitiator().initiate(init -> init.traceId(initiatingTraceId)
                .from("nesting")
                .to(ENDPOINT_INITIATING)
                .send(null));

        // Wait for the threads.
        t1.join(30_000);
        t2.join(30_000);
        t3.join(30_000);

        // :: ASSERT

        // .. Assert the data and in particular the traceIds

        Assert.assertNotNull("No result for Term0", results[0]);
        Assert.assertEquals(d0, results[0].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId0, results[0].getContext().getTraceId());

        Assert.assertNotNull("No result for Term1", results[1]);
        Assert.assertEquals(d1, results[1].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId1, results[1].getContext().getTraceId());

        Assert.assertNotNull("No result for Term3", results[2]);
        Assert.assertEquals(d2, results[2].getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId2, results[2].getContext().getTraceId());

        // .. Now assert the ordering:
        // Term1 and Term2 shall have gotten its message way before Term0.
        Assert.assertTrue("Term1 should have gotten message before Term0", timestamp[0] > timestamp[1]);
        Assert.assertTrue("Term2 should have gotten message before Term0", timestamp[0] > timestamp[2]);
        // Term0 shall have taken at least NAP_TIME
        Assert.assertTrue("Term0 should have taken at least NAP_TIME time.", timestamp[0] - startTime > NAP_TIME);
    }

    @Test
    public void nested_Within_Named_do_Context_and_Nested_Named() throws MatsBackendException,
            MatsMessageSendException,
            InterruptedException {
        // :: ARRANGE

        DataTO d0 = new DataTO(1, "one_" + RandomString.randomCorrelationId());
        DataTO d1 = new DataTO(2, "two_" + RandomString.randomCorrelationId());
        DataTO d2 = new DataTO(3, "three_" + RandomString.randomCorrelationId());

        String initiatingTraceId = MatsTestHelp.traceId();

        String traceId0 = "nestingIsOk_outer_1:" + RandomString.partTraceId();
        String traceId1 = "nestingIsOk_nested_A_2:" + RandomString.partTraceId();
        String traceId2 = "nestingIsOk_nested_B_3:" + RandomString.partTraceId();

        CountDownLatch stageWait = new CountDownLatch(1);
        CountDownLatch testWait = new CountDownLatch(1);

        MATS.getMatsFactory().terminator(ENDPOINT_INITIATING, void.class, void.class, (ctx, state, msg) -> {

            // FIRST Fetch Specific Initiator, and initiate
            // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
            // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
            MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init0 -> {
                init0.traceId(traceId0)
                        .from(traceId0)
                        .to(TERMINATOR0)
                        .send(d0);

                // .. within the named initiator:

                // Initiate on the stage's context's initiator
                // This should be equivalent to getDefaultInitiator(), so since it is nested within named, it should
                // be hoisted to that, and thus sent outside of the stage.
                ctx.initiate(init1 -> {
                    init1.traceId(traceId1)
                            .from(traceId1)
                            .to(TERMINATOR1)
                            .send(d1);

                    // .. within the stage's context's initiator:

                    // Fetch DefaultInitiator, and initiate
                    // NOTICE: This shall go INSIDE the existing transactional demarcation!
                    // Which, two levels up is a named initiator
                    MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init2 -> {
                        init2.traceId(traceId2)
                                .from(traceId2)
                                .to(TERMINATOR2)
                                .send(d2);
                    });
                });
            });

            // Wait here, to prove a point..!
            try {
                boolean notified = stageWait.await(30, TimeUnit.SECONDS);
                if (!notified) {
                    throw new AssertionError("Didn't get notification from test!");
                }
            }
            catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            // Now let the test through
            testWait.countDown();
        });

        // :: ACT

        // Perform the initiation to the endpoint above which executes the actual test initiations..!

        MATS.getMatsFactory().getDefaultInitiator().initiate(init -> init.traceId(initiatingTraceId)
                .from("nesting")
                .to(ENDPOINT_INITIATING)
                .send(null));

        // Wait for the Terminators
        Result<Object, Object> result0 = __latch0.waitForResult();
        Result<Object, Object> result1 = __latch1.waitForResult();
        Result<Object, Object> result2 = __latch2.waitForResult();

        // :: ASSERT

        // .. Assert the data and in particular the traceIds

        Assert.assertNotNull("No result for Term0", result0);
        Assert.assertEquals(d0, result0.getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId0, result0.getContext().getTraceId());

        Assert.assertNotNull("No result for Term1", result1);
        Assert.assertEquals(d1, result1.getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId1, result1.getContext().getTraceId());

        Assert.assertNotNull("No result for Term3", result2);
        Assert.assertEquals(d2, result2.getData());
        Assert.assertEquals(initiatingTraceId + "|" + traceId2, result2.getContext().getTraceId());

        // Assert that stage is waiting
        Assert.assertEquals(1, stageWait.getCount());
        // Let the stage through
        stageWait.countDown();
        // Assert that we're now notified back from stage
        boolean notified = testWait.await(30, TimeUnit.SECONDS);
        if (!notified) {
            throw new AssertionError("Was not notified from stage");
        }

    }
}
