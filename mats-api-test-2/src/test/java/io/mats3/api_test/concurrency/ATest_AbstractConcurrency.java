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
import io.mats3.test.MatsTestLatch;
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

    protected static final String ENDPOINT = MatsTestHelp.endpoint();
    protected static final String TERMINATOR = MatsTestHelp.terminator();

    protected static final int CONCURRENCY = 10; // 10 processors/threads per stage

    protected static final int MESSAGES_MULTIPLE = 6;

    protected static final int NUM_MESSAGES = CONCURRENCY * MESSAGES_MULTIPLE;

    protected static final int PROCESSING_TIME = 100;

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
        _latch = new CountDownLatch(NUM_MESSAGES);
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

        // First "standard" waitForReceiving, to get at least one StageProcessor running for all stages.
        MATS.getMatsFactory().getEndpoint(ENDPOINT).orElseThrow(() -> new AssertionError("Could not get endpoint ["
                + ENDPOINT + "]"))
                .waitForReceiving(30_0000);

        // .. then wait a little more, in hope that all the StageProcessors has gotten into receive()
        MatsTestHelp.takeNap(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE * 2);

        // .. Now fire off the messages.
        MATS.getMatsInitiator().initiateUnchecked((msg) -> {
            for (int i = 0; i < NUM_MESSAGES; i++) {
                DataTO dto = new DataTO(i, "TheAnswer");
                StateTO sto = new StateTO(i, i);
                msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from(expectedString))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR, sto)
                        .request(dto);
            }
        });

        // :: Wait synchronously for all messages to reach terminator

        // The messages should go through much faster than if there was only one processor per stage.
        // It should take a tad more than PROCESSING_TIME * MESSAGES_MULTIPLE ms, but we give it a good bit more.
        // Note: The Windows hosts on GitHub Actions are seemingly absurdly slow, so we need to give it a lot more time.
        // (On local dev machine, it typically runs in a multiple of 1.1 from cold start)
        // Well, actually the Mac hosts aren't that fast either, so we give them a bit more time as well.
        boolean windowsOs = System.getProperty("os.name", "x").toLowerCase().contains("windows");
        boolean macOs = System.getProperty("os.name", "x").toLowerCase().contains("mac");
        long maxWait = windowsOs
                ? (long) (PROCESSING_TIME * MESSAGES_MULTIPLE * 6.0)
                : macOs
                ? (long) (PROCESSING_TIME * MESSAGES_MULTIPLE * 4.0)
                : (long) (PROCESSING_TIME * MESSAGES_MULTIPLE * 2.5);
        log.info("Waiting for " + CONCURRENCY + " messages to reach terminator, with a maxWait of [" + maxWait
                + " ms] (Windows OS: " + windowsOs + ", Mac OS: " + macOs + ")");
        long startMillis = System.currentTimeMillis();
        boolean gotToZero = _latch.await(30, TimeUnit.SECONDS);
        long millisTaken = System.currentTimeMillis() - startMillis;
        Assert.assertTrue("The CountDownLatch did not reach zero.", gotToZero);
        Assert.assertTrue("The CountDownLatch did not reach zero fast enough, in " + maxWait + " ms, it took "
                + millisTaken + " ms.", millisTaken < maxWait);
        log.info("@@ Test passed - Waited for " + CONCURRENCY + " messages, took " + millisTaken
                + " ms - less than the maxWait of [" + maxWait + " ms].");

        // :: Assert the processed data
        for (int i = 0; i < CONCURRENCY; i++) {
            DataTO dto = _map.get(i);
            Assert.assertEquals(i * expectedMultiple, dto.number, 0);
            Assert.assertEquals(expectedString + i, dto.string);
        }
    }
}
