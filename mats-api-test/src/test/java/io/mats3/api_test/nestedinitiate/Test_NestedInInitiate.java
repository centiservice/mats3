package io.mats3.api_test.nestedinitiate;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsInitiator;
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
 * The nested initiate stuff is so ridiculously complex.. This class tests nested initiates within initiates.
 *
 * @author Endre Stølsvik 2022-10-03 19:43 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_NestedInInitiate {

    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT1 = MatsTestHelp.endpointId("ep1");
    private static final String ENDPOINT2 = MatsTestHelp.endpointId("ep2");
    private static final String ENDPOINT3 = MatsTestHelp.endpointId("ep3");

    private static final MatsTestLatch __latch1 = new MatsTestLatch();
    private static final MatsTestLatch __latch2 = new MatsTestLatch();
    private static final MatsTestLatch __latch3 = new MatsTestLatch();

    @BeforeClass
    public static void setupEndpoints() {
        MATS.getMatsFactory().terminator(ENDPOINT1, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("####\nEP1 MatsTrace:\n" + context.toString());
                    __latch1.resolve(context, sto, dto);
                });
        MATS.getMatsFactory().terminator(ENDPOINT2, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("####\nEP2 MatsTrace:\n" + context.toString());
                    __latch2.resolve(context, sto, dto);
                });
        MATS.getMatsFactory().terminator(ENDPOINT3, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("####\nEP3 MatsTrace:\n" + context.toString());
                    __latch3.resolve(context, sto, dto);
                });
    }

    // ======================================================================================================

    @Test
    public void nesting_DefaultInitiator() throws MatsBackendException, MatsMessageSendException,
            InterruptedException {
        nesting(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void nesting_NamedInitiator() throws MatsBackendException, MatsMessageSendException,
            InterruptedException {
        nesting(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void nesting(MatsInitiator matsInitiator) throws MatsBackendException, MatsMessageSendException,
            InterruptedException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingIsOk_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingIsOk_nested_A_2:" + RandomString.partTraceId();
        String traceId3 = "nestingIsOk_nested_B_3:" + RandomString.partTraceId();

        @SuppressWarnings("unchecked")
        Result<StateTO, DataTO>[] results = new Result[3];
        long[] timestamp = new long[3];

        Thread t1 = new Thread(() -> {
            results[0] = __latch1.waitForResult();
            timestamp[0] = System.currentTimeMillis();
        }, "one");
        t1.start();

        Thread t2 = new Thread(() -> {
            results[1] = __latch2.waitForResult();
            timestamp[1] = System.currentTimeMillis();
        }, "two");
        t2.start();

        Thread t3 = new Thread(() -> {
            results[2] = __latch3.waitForResult();
            timestamp[2] = System.currentTimeMillis();
        }, "three");
        t3.start();

        // :: ACT

        matsInitiator.initiate(init1 -> {
            // Directly on this initiator
            init1.traceId(traceId1)
                    .from(traceId1)
                    .to(ENDPOINT1)
                    .send(d1);

            // Fetch DefaultInitiator, and initiate
            // NOTICE: This shall go INSIDE the existing transactional demarcation!
            // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
            MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init2 -> {
                // Directly on this initiator
                init2.traceId(traceId2)
                        .from(traceId2)
                        .to(ENDPOINT2)
                        .send(d2);
            });

            // Fetch Specific Initiator, and initiate
            // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
            // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
            MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init3 -> {
                // Directly on this initiator
                init3.traceId(traceId3)
                        .from(traceId3)
                        .to(ENDPOINT3)
                        .send(d3);
            });

            // Sleep here, to prove a point..!
            // -> Message 3 shall be received way before ep1 and ep2, since they won't be sent until after the
            // lambda and hence the tx context finishes.
            MatsTestHelp.takeNap(1000);

        });

        // Wait for the threads.
        t1.join(30_000);
        t2.join(30_000);
        t3.join(30_000);

        // :: ASSERT

        // .. Assert the data and in particular the traceIds

        Assert.assertNotNull("No result for ep1", results[0]);
        Assert.assertEquals(d1, results[0].getData());
        Assert.assertEquals(traceId1, results[0].getContext().getTraceId());

        Assert.assertNotNull("No result for ep2", results[1]);
        Assert.assertEquals(d2, results[1].getData());
        Assert.assertEquals(traceId2, results[1].getContext().getTraceId());

        Assert.assertNotNull("No result for ep3", results[2]);
        Assert.assertEquals(d3, results[2].getData());
        Assert.assertEquals(traceId3, results[2].getContext().getTraceId());

        // .. Now assert the ordering: Ep3 shall have gotten its message way before Ep1 and Ep2.

        Assert.assertTrue("Ep3 should have gotten message before Ep1", timestamp[0] > timestamp[2]);
        Assert.assertTrue("Ep3 should have gotten message before Ep2", timestamp[1] > timestamp[2]);
    }

    // ======================================================================================================

    @Test
    public void nestingWithException_DefaultInitiator() throws MatsBackendException, MatsMessageSendException,
            InterruptedException {
        nestingWithException(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void nestingWithException_NamedInitiator() throws MatsBackendException, MatsMessageSendException,
            InterruptedException {
        nestingWithException(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void nestingWithException(MatsInitiator matsInitiator) throws MatsBackendException, MatsMessageSendException,
            InterruptedException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingWithException_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingWithException_nested_A_2:" + RandomString.partTraceId();
        String traceId3 = "nestingWithException_nested_B_3:" + RandomString.partTraceId();

        // :: ACT

        try {
            matsInitiator.initiate(init1 -> {
                // Directly on this initiator
                init1.traceId(traceId1)
                        .from(traceId1)
                        .to(ENDPOINT1)
                        .send(d1);

                // Fetch DefaultInitiator, and initiate
                // NOTICE: This shall go INSIDE the existing transactional demarcation!
                // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
                MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init2 -> {
                    // Directly on this initiator
                    init2.traceId(traceId2)
                            .from(traceId2)
                            .to(ENDPOINT2)
                            .send(d2);
                });

                // Fetch Specific Initiator, and initiate
                // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init3 -> {
                    // Directly on this initiator
                    init3.traceId(traceId3)
                            .from(traceId3)
                            .to(ENDPOINT3)
                            .send(d3);
                });

                // Throw here, to prove a point..!
                // Only message to ep3 shall come through, as the others will roll back
                throw new RuntimeException("Throw out!");
            });

            Assert.fail("Should not reach here, since we're throwing out");
        }
        catch (RuntimeException e) {
            /* expected */
        }

        // :: ASSERT

        // We shall NOT get anything for ep1 and ep2, as they're rolled back.

        Result<StateTO, DataTO> result = null;
        try {
            result = __latch1.waitForResult(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep1", result);

        try {
            // We've already waited a good time above..
            result = __latch2.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep2", result);

        // We SHALL get the message for ep3

        result = __latch3.waitForResult();

        Assert.assertEquals(d3, result.getData());
        Assert.assertEquals(traceId3, result.getContext().getTraceId());

    }

    // ======================================================================================================

    @Test
    public void doubleNesting_DefaultDefaultNamed()
            throws MatsBackendException, MatsMessageSendException, InterruptedException {
        doubleNesting_DefaultNamed(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void doubleNesting_NamedDefaultNamed()
            throws MatsBackendException, MatsMessageSendException, InterruptedException {
        doubleNesting_DefaultNamed(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void doubleNesting_DefaultNamed(MatsInitiator matsInitiator)
            throws MatsBackendException, MatsMessageSendException, InterruptedException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingIsOk_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingIsOk_outer_2:" + RandomString.partTraceId();
        String traceId3 = "nestingIsOk_outer_3:" + RandomString.partTraceId();

        @SuppressWarnings("unchecked")
        Result<StateTO, DataTO>[] results = new Result[3];
        long[] timestamp = new long[3];

        Thread t1 = new Thread(() -> {
            results[0] = __latch1.waitForResult();
            timestamp[0] = System.currentTimeMillis();
        }, "one");
        t1.start();

        Thread t2 = new Thread(() -> {
            results[1] = __latch2.waitForResult();
            timestamp[1] = System.currentTimeMillis();
        }, "two");
        t2.start();

        Thread t3 = new Thread(() -> {
            results[2] = __latch3.waitForResult();
            timestamp[2] = System.currentTimeMillis();
        }, "three");
        t3.start();

        // :: ACT

        matsInitiator.initiate(init1 -> {
            // Directly on this initiator
            init1.traceId(traceId1)
                    .from(traceId1)
                    .to(ENDPOINT1)
                    .send(d1);

            // Fetch DefaultInitiator, and initiate
            // NOTICE: This shall go INSIDE the existing transactional demarcation!
            // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
            MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init2 -> {
                // Directly on this initiator
                init2.traceId(traceId2)
                        .from(traceId2)
                        .to(ENDPOINT2)
                        .send(d2);

                // Fetch Specific Initiator, and initiate
                // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init3 -> {
                    // Directly on this initiator
                    init3.traceId(traceId3)
                            .from(traceId3)
                            .to(ENDPOINT3)
                            .send(d3);
                });
            });

            // Sleep here, to prove a point..!
            // -> Message 3 shall be received way before ep1 and ep2, since they won't be sent until after the
            // lambda and hence the tx context finishes.
            MatsTestHelp.takeNap(1000);
        });

        // Wait for the threads.
        t1.join(30_000);
        t2.join(30_000);
        t3.join(30_000);

        // :: ASSERT

        // .. Assert the data and in particular the traceIds

        Assert.assertNotNull("No result for ep1", results[0]);
        Assert.assertEquals(d1, results[0].getData());
        Assert.assertEquals(traceId1, results[0].getContext().getTraceId());

        Assert.assertNotNull("No result for ep2", results[1]);
        Assert.assertEquals(d2, results[1].getData());
        Assert.assertEquals(traceId2, results[1].getContext().getTraceId());

        Assert.assertNotNull("No result for ep3", results[2]);
        Assert.assertEquals(d3, results[2].getData());
        Assert.assertEquals(traceId3, results[2].getContext().getTraceId());

        // .. Now assert the ordering: Ep3 shall have gotten its message way before Ep1 and Ep2.

        Assert.assertTrue("Ep3 should have gotten message before Ep1", timestamp[0] > timestamp[2]);
        Assert.assertTrue("Ep3 should have gotten message before Ep2", timestamp[1] > timestamp[2]);
    }

    // ======================================================================================================

    @Test
    public void doubleNestingWithException_DefaultDefaultNamed()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_DefaultNamed(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void doubleNestingWithException_NamedDefaultNamed()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_DefaultNamed(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void doubleNestingWithException_DefaultNamed(MatsInitiator matsInitiator)
            throws MatsBackendException, MatsMessageSendException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingWithException_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingWithException_nested_A_2:" + RandomString.partTraceId();
        String traceId3 = "nestingWithException_nested_B_3:" + RandomString.partTraceId();

        // :: ACT

        try {
            matsInitiator.initiate(init1 -> {
                // Directly on this initiator
                init1.traceId(traceId1)
                        .from(traceId1)
                        .to(ENDPOINT1)
                        .send(d1);

                // Fetch DefaultInitiator, and initiate
                // NOTICE: This shall go INSIDE the existing transactional demarcation!
                // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
                MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init2 -> {
                    // Directly on this initiator
                    init2.traceId(traceId2)
                            .from(traceId2)
                            .to(ENDPOINT2)
                            .send(d2);

                    // Fetch Specific Initiator, and initiate
                    // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                    // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                    MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init3 -> {
                        // Directly on this initiator
                        init3.traceId(traceId3)
                                .from(traceId3)
                                .to(ENDPOINT3)
                                .send(d3);
                    });
                });

                // Throw here, to prove a point..!
                // Only message to ep3 shall come through, as the others will roll back
                throw new RuntimeException("Throw out!");
            });

            Assert.fail("Should not reach here, since we're throwing out");
        }
        catch (RuntimeException e) {
            /* expected */
        }

        // :: ASSERT

        // We shall NOT get anything for ep1 and ep2, as they're rolled back.

        Result<StateTO, DataTO> result = null;
        try {
            result = __latch1.waitForResult(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep1", result);

        try {
            // We've already waited a good time above..
            result = __latch2.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep2", result);

        // We SHALL get the message for ep3

        result = __latch3.waitForResult();

        Assert.assertEquals(d3, result.getData());
        Assert.assertEquals(traceId3, result.getContext().getTraceId());

    }

    // ======================================================================================================

    @Test
    public void doubleNesting_DefaultNamedDefault()
            throws MatsBackendException, MatsMessageSendException, InterruptedException {
        doubleNesting_NamedDefault(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void doubleNesting_NamedNamedDefaultr()
            throws MatsBackendException, MatsMessageSendException, InterruptedException {
        doubleNesting_NamedDefault(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void doubleNesting_NamedDefault(MatsInitiator matsInitiator)
            throws MatsBackendException, MatsMessageSendException, InterruptedException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingIsOk_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingIsOk_outer_2:" + RandomString.partTraceId();
        String traceId3 = "nestingIsOk_outer_3:" + RandomString.partTraceId();

        @SuppressWarnings("unchecked")
        Result<StateTO, DataTO>[] results = new Result[3];
        long[] timestamp = new long[3];

        Thread t1 = new Thread(() -> {
            results[0] = __latch1.waitForResult();
            timestamp[0] = System.currentTimeMillis();
        }, "one");
        t1.start();

        Thread t2 = new Thread(() -> {
            results[1] = __latch2.waitForResult();
            timestamp[1] = System.currentTimeMillis();
        }, "two");
        t2.start();

        Thread t3 = new Thread(() -> {
            results[2] = __latch3.waitForResult();
            timestamp[2] = System.currentTimeMillis();
        }, "three");
        t3.start();

        // :: ACT

        matsInitiator.initiate(init1 -> {
            // Directly on this initiator
            init1.traceId(traceId1)
                    .from(traceId1)
                    .to(ENDPOINT1)
                    .send(d1);

            // Fetch Named (Specific) Initiator, and initiate
            // NOTICE: This shall go OUTSIDE the existing (outer) transactional demarcation!
            // .. that is, it opens a new transactional context, which both this, and the next, is within.
            MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init2 -> {
                // Directly on this initiator
                init2.traceId(traceId2)
                        .from(traceId2)
                        .to(ENDPOINT2)
                        .send(d2);

                // Fetch Default Initiator, and initiate
                // NOTICE: This GOES INSIDE the PREVIOUS (not outer), which means that it is outside the outer..!
                // .. that is, it will be sent when the current tx commits, not waiting for the outer lambda to exit.
                MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init3 -> {
                    // Directly on this initiator
                    init3.traceId(traceId3)
                            .from(traceId3)
                            .to(ENDPOINT3)
                            .send(d3);
                });
            });

            // Sleep here, to prove a point..!
            // -> Message 2 and 3 shall be received way before ep1, since the latter won't be sent until after
            // lambda and hence the tx context finishes.
            MatsTestHelp.takeNap(1000);
        });

        // Wait for the threads.
        t1.join(30_000);
        t2.join(30_000);
        t3.join(30_000);

        // :: ASSERT

        // .. Assert the data and in particular the traceIds

        Assert.assertNotNull("No result for ep1", results[0]);
        Assert.assertEquals(d1, results[0].getData());
        Assert.assertEquals(traceId1, results[0].getContext().getTraceId());

        Assert.assertNotNull("No result for ep2", results[1]);
        Assert.assertEquals(d2, results[1].getData());
        Assert.assertEquals(traceId2, results[1].getContext().getTraceId());

        Assert.assertNotNull("No result for ep3", results[2]);
        Assert.assertEquals(d3, results[2].getData());
        Assert.assertEquals(traceId3, results[2].getContext().getTraceId());

        // .. Now assert the ordering: Ep3 shall have gotten its message way before Ep1 and Ep2.

        Assert.assertTrue("Ep3 should have gotten message before Ep1", timestamp[0] > timestamp[2]);
        Assert.assertTrue("Ep2 should have gotten message before Ep1", timestamp[0] > timestamp[1]);
    }

    // ======================================================================================================

    @Test
    public void doubleNestingWithException_DefaultNamedDefault()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_NamedDefault(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void doubleNestingWithException_NamedNamedDefault()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_NamedDefault(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void doubleNestingWithException_NamedDefault(MatsInitiator matsInitiator)
            throws MatsBackendException, MatsMessageSendException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingWithException_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingWithException_nested_A_2:" + RandomString.partTraceId();
        String traceId3 = "nestingWithException_nested_B_3:" + RandomString.partTraceId();

        // :: ACT

        try {
            matsInitiator.initiate(init1 -> {
                // Directly on this initiator
                init1.traceId(traceId1)
                        .from(traceId1)
                        .to(ENDPOINT1)
                        .send(d1);

                // Fetch Named (Specific) Initiator, and initiate
                // NOTICE: This shall go OUTSIDE the existing (outer) transactional demarcation!
                // .. that is, it opens a new transactional context, which both this, and the next, is within.
                MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init2 -> {
                    // Directly on this initiator
                    init2.traceId(traceId2)
                            .from(traceId2)
                            .to(ENDPOINT2)
                            .send(d2);

                    // Fetch Default Initiator, and initiate
                    // NOTICE: This GOES INSIDE the PREVIOUS (not outer), which means that it is outside the outer..!
                    // .. that is, it will be sent when the current tx commits, not waiting for the outer lambda to exit.
                    MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init3 -> {
                        // Directly on this initiator
                        init3.traceId(traceId3)
                                .from(traceId3)
                                .to(ENDPOINT3)
                                .send(d3);
                    });
                });

                // Throw here, to prove a point..!
                // Only message to ep3 shall come through, as the others will roll back
                throw new RuntimeException("Throw out!");
            });

            Assert.fail("Should not reach here, since we're throwing out");
        }
        catch (RuntimeException e) {
            /* expected */
        }

        // :: ASSERT

        // We shall NOT get anything for ep1, as it was rolled back.

        Result<StateTO, DataTO> result = null;
        try {
            result = __latch1.waitForResult(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep1", result);

        // We SHALL get the message for both ep2 and ep3

        result = __latch2.waitForResult();
        Assert.assertEquals(d2, result.getData());
        Assert.assertEquals(traceId2, result.getContext().getTraceId());

        result = __latch3.waitForResult();
        Assert.assertEquals(d3, result.getData());
        Assert.assertEquals(traceId3, result.getContext().getTraceId());
    }

    // ======================================================================================================

    @Test
    public void doubleNestingWithException_DefaultDefaultExceptionNamed()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_DefaultExceptionNamed(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void doubleNestingWithException_NamedDefaultExceptionNamed()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_DefaultExceptionNamed(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void doubleNestingWithException_DefaultExceptionNamed(MatsInitiator matsInitiator)
            throws MatsBackendException, MatsMessageSendException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingWithException_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingWithException_nested_A_2:" + RandomString.partTraceId();
        String traceId3 = "nestingWithException_nested_B_3:" + RandomString.partTraceId();

        // :: ACT

        try {
            matsInitiator.initiate(init1 -> {
                // Directly on this initiator
                init1.traceId(traceId1)
                        .from(traceId1)
                        .to(ENDPOINT1)
                        .send(d1);

                // Fetch DefaultInitiator, and initiate
                // NOTICE: This shall go INSIDE the existing transactional demarcation!
                // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
                MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init2 -> {
                    // Directly on this initiator
                    init2.traceId(traceId2)
                            .from(traceId2)
                            .to(ENDPOINT2)
                            .send(d2);

                    // Fetch Specific Initiator, and initiate
                    // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                    // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                    MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init3 -> {
                        // Directly on this initiator
                        init3.traceId(traceId3)
                                .from(traceId3)
                                .to(ENDPOINT3)
                                .send(d3);
                    });
                    // Throw here, to prove a point..!
                    // Only message to ep3 shall come through, as the others will roll back
                    throw new RuntimeException("Throw out!");
                });
            });

            Assert.fail("Should not reach here, since we're throwing out");
        }
        catch (RuntimeException e) {
            /* expected */
        }

        // :: ASSERT

        // We shall NOT get anything for ep1 and ep2, as they're rolled back.

        Result<StateTO, DataTO> result = null;
        try {
            result = __latch1.waitForResult(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep1", result);

        try {
            // We've already waited a good time above..
            result = __latch2.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep2", result);

        // We SHALL get the message for ep3

        result = __latch3.waitForResult();

        Assert.assertEquals(d3, result.getData());
        Assert.assertEquals(traceId3, result.getContext().getTraceId());

    }

    // ======================================================================================================

    @Test
    public void doubleNestingWithException_DefaultDefaultNamedException()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_DefaultNamedException(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void doubleNestingWithException_NamedDefaultNamedException()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_DefaultNamedException(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void doubleNestingWithException_DefaultNamedException(MatsInitiator matsInitiator)
            throws MatsBackendException, MatsMessageSendException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingWithException_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingWithException_nested_A_2:" + RandomString.partTraceId();
        String traceId3 = "nestingWithException_nested_B_3:" + RandomString.partTraceId();

        // :: ACT

        try {
            matsInitiator.initiate(init1 -> {
                // Directly on this initiator
                init1.traceId(traceId1)
                        .from(traceId1)
                        .to(ENDPOINT1)
                        .send(d1);

                // Fetch DefaultInitiator, and initiate
                // NOTICE: This shall go INSIDE the existing transactional demarcation!
                // .. that is, it won't be sent until the current lambda exits, along with msg1 above.
                MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init2 -> {
                    // Directly on this initiator
                    init2.traceId(traceId2)
                            .from(traceId2)
                            .to(ENDPOINT2)
                            .send(d2);

                    // Fetch Specific Initiator, and initiate
                    // NOTICE: This GOES OUTSIDE this existing transactional demarcation!
                    // .. that is, it will be sent immediately, not waiting for the current lambda to exit.
                    MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init3 -> {
                        // Directly on this initiator
                        init3.traceId(traceId3)
                                .from(traceId3)
                                .to(ENDPOINT3)
                                .send(d3);
                        // Throw here, to prove a point..!
                        // No messages shall come through
                        throw new RuntimeException("Throw out!");
                    });
                });
            });

            Assert.fail("Should not reach here, since we're throwing out");
        }
        catch (RuntimeException e) {
            /* expected */
        }

        // :: ASSERT

        // We shall NOT get anything for none of the ep1, ep2, ep3, as they're all rolled back.

        Result<StateTO, DataTO> result = null;
        try {
            result = __latch1.waitForResult(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep1", result);

        try {
            // We've already waited a good time above..
            result = __latch3.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep2", result);

        try {
            // We've already waited a good time above..
            result = __latch3.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep3", result);
    }


    // ======================================================================================================

    @Test
    public void doubleNestingWithException_DefaultNamedExceptionDefault()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_NamedExceptionDefault(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void doubleNestingWithException_NamedNamedExceptionDefault()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_NamedExceptionDefault(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void doubleNestingWithException_NamedExceptionDefault(MatsInitiator matsInitiator)
            throws MatsBackendException, MatsMessageSendException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingWithException_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingWithException_nested_A_2:" + RandomString.partTraceId();
        String traceId3 = "nestingWithException_nested_B_3:" + RandomString.partTraceId();

        // :: ACT

        try {
            matsInitiator.initiate(init1 -> {
                init1.traceId(traceId1)
                        .from(traceId1)
                        .to(ENDPOINT1)
                        .send(d1);

                MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init2 -> {
                    init2.traceId(traceId2)
                            .from(traceId2)
                            .to(ENDPOINT2)
                            .send(d2);

                    MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init3 -> {
                        init3.traceId(traceId3)
                                .from(traceId3)
                                .to(ENDPOINT3)
                                .send(d3);
                    });
                    // Throw here, to prove a point..!
                    // No messages shall come through, as the inner is hanging on the mid, which throws (here) and
                    // rolls back. The exception propagates to the outer, which also rolls back.
                    throw new RuntimeException("Throw out!");
                });
            });

            Assert.fail("Should not reach here, since we're throwing out");
        }
        catch (RuntimeException e) {
            /* expected */
        }

        // :: ASSERT

        // We shall NOT get anything for none of the ep1, ep2, ep3, as they're all rolled back.

        Result<StateTO, DataTO> result = null;
        try {
            result = __latch1.waitForResult(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep1", result);

        try {
            // We've already waited a good time above..
            result = __latch3.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep2", result);

        try {
            // We've already waited a good time above..
            result = __latch3.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep3", result);
    }


    // ======================================================================================================

    @Test
    public void doubleNestingWithException_DefaultNamedDefaultException()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_NamedDefaultException(MATS.getMatsFactory().getDefaultInitiator());
    }

    @Test
    public void doubleNestingWithException_NamedNamedDefaultException()
            throws MatsBackendException, MatsMessageSendException {
        doubleNestingWithException_NamedDefaultException(MATS.getMatsFactory().getOrCreateInitiator("Testing"));
    }

    public void doubleNestingWithException_NamedDefaultException(MatsInitiator matsInitiator)
            throws MatsBackendException, MatsMessageSendException {
        // :: ARRANGE

        DataTO d1 = new DataTO(1, "one");
        DataTO d2 = new DataTO(2, "two");
        DataTO d3 = new DataTO(3, "three");
        String traceId1 = "nestingWithException_outer_1:" + RandomString.partTraceId();
        String traceId2 = "nestingWithException_nested_A_2:" + RandomString.partTraceId();
        String traceId3 = "nestingWithException_nested_B_3:" + RandomString.partTraceId();

        // :: ACT

        try {
            matsInitiator.initiate(init1 -> {
                init1.traceId(traceId1)
                        .from(traceId1)
                        .to(ENDPOINT1)
                        .send(d1);

                MATS.getMatsFactory().getOrCreateInitiator("Test").initiateUnchecked(init2 -> {
                    init2.traceId(traceId2)
                            .from(traceId2)
                            .to(ENDPOINT2)
                            .send(d2);

                    MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init3 -> {
                        init3.traceId(traceId3)
                                .from(traceId3)
                                .to(ENDPOINT3)
                                .send(d3);
                        // Throw here, to prove a point..!
                        // No messages shall come through, as the inner throws which is hanging on the mid, and thus
                        // this tx rolls back. The exception propagates, and rolls back the outer.
                        throw new RuntimeException("Throw out!");
                    });
                });
            });

            Assert.fail("Should not reach here, since we're throwing out");
        }
        catch (RuntimeException e) {
            /* expected */
        }

        // :: ASSERT

        // We shall NOT get anything for none of the ep1, ep2, ep3, as they're all rolled back.

        Result<StateTO, DataTO> result = null;
        try {
            result = __latch1.waitForResult(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep1", result);

        try {
            // We've already waited a good time above..
            result = __latch3.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep2", result);

        try {
            // We've already waited a good time above..
            result = __latch3.waitForResult(5);
        }
        catch (AssertionError ae) {
            /* expected */
        }
        Assert.assertNull("We should not have gotten anything for ep3", result);
    }

}
