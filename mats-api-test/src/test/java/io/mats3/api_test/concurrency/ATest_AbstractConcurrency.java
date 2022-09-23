package io.mats3.api_test.concurrency;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.slf4j.Logger;

import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Abstract class for concurrency tests.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class ATest_AbstractConcurrency {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    protected static final String SERVICE = MatsTestHelp.service();
    protected static final String TERMINATOR = MatsTestHelp.terminator();

    protected static final int CONCURRENCY = 8;

    protected static final int PROCESSING_TIME = 500;

    private static CountDownLatch _latch;

    protected static final Map<Integer, DataTO> _map = new ConcurrentHashMap<>();

    @BeforeClass
    public static void setupTerminator() {
        // Set default concurrency to 1. This test should most definitely not pass then.
        MATS.getMatsFactory().getFactoryConfig().setConcurrency(1);
        // Create the terminator
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class, (context, sto, dto) -> {
            _map.put(sto.number1, dto);
            _latch.countDown();
        });
    }

    @Before
    public void clearMapsAndLatch() {
        _map.clear();
        _latch = new CountDownLatch(CONCURRENCY);
    }

    protected void performTest(double expectedMultiple, String expectedString) throws InterruptedException {
        /*
         * Sometimes get problem that all the processors has not gotten into consumer.receive()-call before we fire off
         * the 8 messages and the first processor gets a message. Evidently the first processors then get two of the
         * messages (the one that gets a message before the latecomer has gotten into receive()), while the latecomer
         * gets none, and then the test fails.
         *
         * Remedy by napping a little before firing off the messages, hoping that all the StageProcessors gets one
         * message each, which is a requirement for the test to pass.
         */
        MatsTestHelp.takeNap(PROCESSING_TIME);

        // .. Now fire off the messages.
        MATS.getMatsInitiator().initiateUnchecked((msg) -> {
            for (int i = 0; i < CONCURRENCY; i++) {
                DataTO dto = new DataTO(i, "TheAnswer");
                StateTO sto = new StateTO(i, i);
                msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from(expectedString))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto);
            }
        });

        // :: Wait synchronously for all messages to reach terminator

        // We set the max time to receive all messages to a multiple <2 - this means that if the messages does not
        // go through in parallel, the test should fail. We want as tight margin as possible, but since evidently
        // the test runner instances on Github Actions are pretty crowded, we'll have to give quite a bit of leeway.
        // Former x1.3 (650 ms) failed on MacOS (it took 685 ms!), upping to 1.75x, which still should catch if the
        // concurrency is severely off what is configured in the tests. Aaand, upping to 1.99, since MacOS still fails
        // us (took 888ms, when 1.75x gives max 875!). 1.99x is still short enough that a bad test cannot falsely get
        // green, but this will probably still fail sometimes since there is so little headroom.
        long maxWait = (long) (PROCESSING_TIME * 1.99);
        long startMillis = System.currentTimeMillis();
        boolean gotToZero = _latch.await((long) (PROCESSING_TIME * CONCURRENCY * 1.5), TimeUnit.MILLISECONDS);
        long millisTaken = System.currentTimeMillis() - startMillis;
        Assert.assertTrue("The CountDownLatch did not reach zero.", gotToZero);
        Assert.assertTrue("The CountDownLatch did not reach zero in " + maxWait + " ms (took " + millisTaken + "ms).",
                millisTaken < maxWait);
        log.info("@@ Test passed - Waiting for " + CONCURRENCY + " messages took " + millisTaken + " ms.");

        // :: Assert the processed data
        for (int i = 0; i < CONCURRENCY; i++) {
            DataTO dto = _map.get(i);
            Assert.assertEquals(i * expectedMultiple, dto.number, 0);
            Assert.assertEquals(expectedString + i, dto.string);
        }
    }
}
