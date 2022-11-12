package io.mats3.util.futurizer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Tests that shows that the completer thread-pool inside MatsFuturizer is handling the completion of the futures. After
 * a good while (some 3 years) and a bunch of failures on Github Actions, I think I finally understand how this works.
 * Read the code comments.
 *
 * @author Endre Stølsvik 2019-08-31 16:54 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_MatsFuturizer_WhichThread {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();

    private static final String MATSFUTURIZER_COMPLETER_THREAD_NAME_START = "MatsFuturizer completer";

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, msg) -> {
                    log.info("Inside ENDPOINT, context:\n" + context);
                    /*
                     * Nap some millis, just to ensure that the main thread has already gotten to its wait point. The
                     * idea is that we're trying to make sure that .get() is actively holding the main thread so that we
                     * can check if the CompletableFuture was implemented in such a way that if any thread was actively
                     * waiting on the result of a chain of CompletableFutures, it would be executed on that waiting
                     * thread. It seems like this is not the case: The "stack" of CompletableFutures's "thenApply" and
                     * "thenAccept" is executed on the thread that invokes ".complete(..)", and when the final result is
                     * ready, only then is the result handed over to the thread "hanging" on .get(). Good stuff with the
                     * completer thread-pool then, so that we do not hold on to the Mats topic receiver thread (in the
                     * SubscriptionTerminator) longer than absolutely necessary.
                     *
                     * Notice, new info 2022-10-02: If the CompletableFuture has already completed when .thenApply() etc
                     * is invoked, /then/ it will be executed by the calling thread (i.e. 'main' or the test worker). If
                     * the .thenApply() is invoked /before/ the CompletableFuture, then it will be the completer (i.e.
                     * the "MatsFuturizer completer") that executes it. Basically, if you have an already completed
                     * future on your hands, invoking methods like .thenApply() will have to be invoked by you - there
                     * are no other threads available. But if a new CompleteableFuture is already made (i.e. what
                     * thenApply() does), the "stack" will be executed by the completer.
                     *
                     * Therefore, to ensure that it will be the "MatsFuturizer completer" which runs it (which these
                     * tests assume), we'll wait a good while here.
                     */
                    MatsTestHelp.takeNap(1000);
                    return new DataTO(msg.number * 2, msg.string + ":FromService");
                });
    }

    @Test
    public void whichThread_ThenAccept_WithoutGet() throws InterruptedException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        // ARRANGE

        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futurizer.futurizeNonessential(
                "traceId", "OneSingleMessage", ENDPOINT, DataTO.class, dto);

        // ACT

        String[] completedOnThreadName = new String[1];
        @SuppressWarnings("unchecked")
        Reply<DataTO>[] reply = new Reply[1];
        CountDownLatch latch = new CountDownLatch(1);

        future.thenAccept(r -> {
            completedOnThreadName[0] = Thread.currentThread().getName();
            reply[0] = r;
            latch.countDown();
        });

        // ASSERT
        latch.await(5, TimeUnit.SECONDS);

        log.info("The future was completed on thread [" + completedOnThreadName[0] + "} - the latency was "
                + (System.currentTimeMillis() - reply[0].initiationTimestamp) + " milliseconds");

        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), reply[0].reply);

        // The "thenAccept" should have been executed on the MatsFuturizer thread pool.
        Assert.assertTrue("Completed on thread [" + completedOnThreadName[0]
                + "], expected a thread name starting with \"" + MATSFUTURIZER_COMPLETER_THREAD_NAME_START + "\"",
                completedOnThreadName[0].startsWith(MATSFUTURIZER_COMPLETER_THREAD_NAME_START));

    }

    @Test
    public void whichThread_ThenApplyWithGet() throws ExecutionException, InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        // :: ARRANGE

        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futurizer.futurizeNonessential(
                "traceId", "OneSingleMessage", ENDPOINT, DataTO.class, dto);

        // :: ACT

        String[] completedOnThreadName = new String[1];
        @SuppressWarnings("unchecked")
        Reply<DataTO>[] reply = new Reply[1];

        // Do a ".thenApply(...)", followed by a ".get()" to get the value..
        CompletableFuture<DataTO> completeFuture = future.thenApply(r -> {
            completedOnThreadName[0] = Thread.currentThread().getName();
            reply[0] = r;
            DataTO in = r.reply;
            return new DataTO(in.number * 3, in.string + ":FromThenApply");
        });
        DataTO replyTo = completeFuture.get(5, TimeUnit.SECONDS);

        // :: ASSERT

        log.info("The future was completed on thread [" + completedOnThreadName[0] + "} - the latency was "
                + (System.currentTimeMillis() - reply[0].initiationTimestamp) + " milliseconds");

        Assert.assertEquals(new DataTO(dto.number * 2 * 3, dto.string + ":FromService:FromThenApply"), replyTo);

        // The "thenAccept" should have been executed on the MatsFuturizer thread pool.
        Assert.assertTrue("Completed on thread [" + completedOnThreadName[0]
                + "], expected a thread name starting with \"" + MATSFUTURIZER_COMPLETER_THREAD_NAME_START + "\"",
                completedOnThreadName[0].startsWith(MATSFUTURIZER_COMPLETER_THREAD_NAME_START));
    }

    @Test
    public void whichThread_ThrowsInAccept() throws InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        // :: ARRANGE

        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futurizer.futurizeNonessential(
                "traceId", "OneSingleMessage", ENDPOINT, DataTO.class, dto);

        // :: ACT

        String[] completedOnThreadName = new String[1];
        @SuppressWarnings("unchecked")
        Reply<DataTO>[] reply = new Reply[1];

        // Do a ".thenAccept(...)", followed by a ".get()" to get the value - which won't come due to exception.
        CompletableFuture<Void> finishedFuture = future.thenAccept(r -> {
            completedOnThreadName[0] = Thread.currentThread().getName();
            reply[0] = r;
            throw new IllegalStateException("Just testing..");
        });

        ExecutionException thrown = null;
        try {
            finishedFuture.get(5, TimeUnit.SECONDS);
            Assert.fail("Should not come here, since we should have thrown out!");
        }
        catch (ExecutionException e) {
            /* expected */
            thrown = e;
        }

        // :: ASSERT

        log.info("The future was completed on thread [" + completedOnThreadName[0] + "} - the latency was "
                + (System.currentTimeMillis() - reply[0].initiationTimestamp) + " milliseconds");

        Assert.assertNotNull(thrown);

        Assert.assertEquals(IllegalStateException.class, thrown.getCause().getClass());

        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), reply[0].reply);

        // The "thenAccept" should have been executed on the MatsFuturizer thread pool.
        Assert.assertTrue("Completed on thread [" + completedOnThreadName[0]
                + "], expected a thread name starting with \"" + MATSFUTURIZER_COMPLETER_THREAD_NAME_START + "\"",
                completedOnThreadName[0].startsWith(MATSFUTURIZER_COMPLETER_THREAD_NAME_START));
    }
}
