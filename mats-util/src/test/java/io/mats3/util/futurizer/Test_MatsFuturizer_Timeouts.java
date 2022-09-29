package io.mats3.util.futurizer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.MatsFuturizerTimeoutException;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Basic tests of timeouts of MatsFuturizer, of which there are two completely independent variants: Either you
 * synchronously timeout on the {@link CompletableFuture#get(long, TimeUnit) get(...)}, which really should be your
 * preferred way, IMHO, <b>or</b> you let the MatsFuturizer do the timeout by its timeout thread.
 *
 * @author Endre Stølsvik 2019-08-30 21:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_MatsFuturizer_Timeouts {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    /**
     * This Terminator is set up just to consume all messages produced by this test, so that they do not linger on the
     * MQ - which is a point if we use an external, persistent broker, as MatsTestBroker (within Rule_Mats) can be
     * directed to utilize.
     */
    @BeforeClass
    public static void setupCleanupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, Object.class, DataTO.class, (ctx, state, msg) -> {
        });
    }

    private CompletableFuture<Reply<DataTO>> futureToEmptiness(MatsFuturizer futurizer, String from,
            int sequence, int timeoutMillis, Consumer<Throwable> exceptionallyConsumer) {
        CompletableFuture<Reply<DataTO>> future = futurizer.futurize(
                "" + timeoutMillis, from, TERMINATOR, timeoutMillis,
                TimeUnit.MILLISECONDS, DataTO.class, new DataTO(sequence, "" + timeoutMillis),
                MatsInitiate::nonPersistent);
        if (exceptionallyConsumer != null) {
            future.exceptionally((in) -> {
                exceptionallyConsumer.accept(in);
                return null;
            });
        }
        return future;
    }

    @Test
    public void oneShotTimeoutByMatsFuturizer() throws InterruptedException, TimeoutException {
        CompletableFuture<Reply<DataTO>> future = futureToEmptiness(MATS.getMatsFuturizer(),
                "TimeoutTester.oneShotTimeoutByMatsFuturizer", 42, 10, null);

        // ----- There will never be a reply, as there is no consumer for the sent message..!

        try {
            future.get(1, TimeUnit.MINUTES);
            // We should not get to the next line.
            Assert.fail("We should have gotten an ExecutionException with getCause() MatsFuturizerTimeoutException,"
                    + " as the MatzFuturizer should have timed us out.");
        }
        catch (ExecutionException e) {
            // The cause of this ExecutionException should be a MatsFuturizerTimeoutException, as we were happy
            // to wait for 1 minute, but the timeout was specified to 10 ms.
            Assert.assertEquals(MatsFuturizerTimeoutException.class, e.getCause().getClass());
            // There should be 0 outstanding promises, as the one we added just got timed out.
            Assert.assertEquals(0, MATS.getMatsFuturizer().getOutstandingPromiseCount());
        }
    }

    @Test
    public void oneShotTimeoutByCompletableFuture() throws ExecutionException, InterruptedException {
        // =============================================================================================
        // == NOTE: Using try-with-resources in this test - NOT TO BE USED IN NORMAL CIRCUMSTANCES!!!
        // ==
        // == NOTE: The reason for creating a different MatsFuturizer for this one test, is that we do
        // == not want to pollute the Rule_Mats-instance of the MatsFuturizer with an outstanding
        // == promise for 5000 ms, ref. the futurizer.getOutstandingPromiseCount() calls in all tests.
        // =============================================================================================
        try (MatsFuturizer futurizer = MatsFuturizer.createMatsFuturizer(MATS.getMatsFactory(),
                this.getClass().getSimpleName())) {
            int futurizerTimeout = 5000;
            CompletableFuture<Reply<DataTO>> future = futureToEmptiness(futurizer,
                    "TimeoutTester.oneShotTimeoutByCompletableFuture", 42, futurizerTimeout, null);

            // ----- There will never be a reply, as there is no consumer for the sent message..!

            try {
                log.info("Going into future.get(50 MILLISECONDS), which should time out much before the Futurizer"
                        + " times out by [" + futurizerTimeout + "] millis.");
                future.get(50, TimeUnit.MILLISECONDS);
                // We should not get to the next line, no matter.
                // Since there is no-one answering on that endpoint, it should not resolve.
                // Also, this should have timed out with a TimeoutException, while if the Futurizer times it out,
                // we'll get a java.util.concurrent.ExecutionException
                // -> cause: MatsFuturizer$MatsFuturizerTimeoutException.
                Assert.fail("We should have gotten an TimeoutException, as the CompletableFuture should have timed"
                        + " out our wait..");
            }
            catch (TimeoutException e) {
                // Top notch: We were expecting the TimeoutException: good-stuff!
                // ASSERT: There should still be one outstanding Promise, i.e. the one we just added.
                Assert.assertEquals(1, futurizer.getOutstandingPromiseCount());
            }
        }
    }

    /**
     * Massive cop-out, making a retry-logic for this test. The problem is that I simply cannot get this to pass on the
     * Github Action Windows runners, as they "time skip" so hard that I'd have to use many seconds between each of the
     * "scheduled" futures.
     *
     * I've collected one log run here:
     * <pre>
     What can you do when this happens?

     13:40:24.571 [Test worker] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Creating Promise for TraceId [200], from [TimeoutTester.severalTimeoutsByMatsFuturizer], to [Test_MatsFuturizer_Timeouts.Terminator], timeout in [200] millis.  {}
     ...
     13:40:24.572 [MatsFuturizer Timeouter] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Promise at head of timeout queue has NOT timed out, will time out in [199] millis - traceId [200].  {}
     13:40:24.572 [MatsFuturizer Timeouter] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Will now go to sleep for [199] millis.  {}
     ...
     Quite big jump - this is the very next line in the test's source code:
     13:40:24.627 [Test worker] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Creating Promise for TraceId [600], from [TimeoutTester.severalTimeoutsByMatsFuturizer], to [Test_MatsFuturizer_Timeouts.Terminator], timeout in [600] millis.  {}
     ...

     Observe the MASSIVE jump in timing here - this is again the very next line:
     13:40:24.948 [Test worker] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Creating Promise for TraceId [10], from [TimeoutTester.severalTimeoutsByMatsFuturizer], to [Test_MatsFuturizer_Timeouts.Terminator], timeout in [10] millis.  {}
     ...
     This is also biggish:
     13:40:25.028 [Test worker] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Creating Promise for TraceId [800], from [TimeoutTester.severalTimeoutsByMatsFuturizer], to [Test_MatsFuturizer_Timeouts.Terminator], timeout in [800] millis.  {}
     ...
     while this is what we really would expect
     13:40:25.030 [Test worker] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Creating Promise for TraceId [400], from [TimeoutTester.severalTimeoutsByMatsFuturizer], to [Test_MatsFuturizer_Timeouts.Terminator], timeout in [400] millis.  {}
     ...
     and here
     13:40:25.033 [Test worker] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Creating Promise for TraceId [1000], from [TimeoutTester.severalTimeoutsByMatsFuturizer], to [Test_MatsFuturizer_Timeouts.Terminator], timeout in [1000] millis.  {}


     Due to the MASSIVE jump in time above, this happens when timing out:
     13:40:25.034 [MatsFuturizer Timeouter] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# .. slept [462.3062] millis (should have slept [199] millis, difference [263.3062] millis too much).  {}
     13:40:25.034 [MatsFuturizer Timeouter] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Promise at head of timeout queue HAS timed out [263] millis ago - traceId [200].  {}
     13:40:25.034 [MatsFuturizer Timeouter] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Promise at head of timeout queue HAS timed out [76] millis ago - traceId [10].  {}
     13:40:25.034 [MatsFuturizer Timeouter] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Promise at head of timeout queue has NOT timed out, will time out in [193] millis - traceId [600].  {}
     13:40:25.034 [MatsFuturizer Timeouter] DEBUG io.mats3.util.MatsFuturizer - #MATS-UTIL# Will now timeout [2] Promise(s).  {}

     Thus, the 200 ms timeout is way before the 10 ms timeout, since the latter was /scheduled/ so very much later.
     Thus, the test fails, since the ordering is not as expected, where the 10 ms was expected to timeout before the 200 ms.
     * </pre>
     *
     * So, I'll now run this test up to 10 times hoping that it passes.
     *
     *
     */
    @Test
    public void severalTimeoutsByMatsFuturizer() throws Throwable {
        log.info("###### Retry mechanism to get this test to pass.");
        int i = 0;
        while (true) {
            log.info("### RETRY Attempt #"+i);
            try {
                severalTimeoutsByMatsFuturizer_inner();
                log.info("### RETRY Attempt #"+i+" SUCCEEDED!");
                return;
            }
            catch (Throwable t) {
                log.warn("### RETRY Attempt #"+i+" FAILED.", t);
                i ++;
                if (i == 10) {
                    log.error("This was the last attempt. Throwing out to fail the test.");
                    throw t;
                }
                log.info("Chill some seconds before attempting again.");
                Thread.sleep(5000);
            }
        }
    }

    private void severalTimeoutsByMatsFuturizer_inner() throws InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();
        // :: PRE-ARRANGE:

        // :: First do a warm-up of the infrastructure, as we need somewhat good performance of the code
        // to do the test, which relies on asynchronous timings.
        for (int i = 0; i < 10; i++) {
            oneShotTimeoutByMatsFuturizer();
        }
        Assert.assertEquals(0, futurizer.getOutstandingPromiseCount());

        // ----- The test infrastructure should now be somewhat warm, not incurring sudden halts to timings.

        // :: ARRANGE:

        // We will receive each of the timeout exceptions as they happen, by using future.thenAccept(..)
        // We'll stick the "results" in this COWAL.
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();
        Consumer<Throwable> exceptionallyConsumer = (t) -> {
            MatsFuturizerTimeoutException mfte = (MatsFuturizerTimeoutException) t;
            log.info("Got timeout for traceId [" + mfte.getTraceId() + "]!");
            results.add(mfte.getTraceId());
        };

        // :: Stack up a specific set of futures out-of-order, which should time out in timeout-order
        // NOTICE how the sequence of the entered futures' timeouts are NOT in order.
        // This is to test that the Timeouter handles both adding new entries that are later than the current
        // earliest, but also earlier than the current earliest.
        String from = "TimeoutTester.severalTimeoutsByMatsFuturizer";
        futureToEmptiness(futurizer, from, 1, 200, exceptionallyConsumer);
        futureToEmptiness(futurizer, from, 2, 600, exceptionallyConsumer);
        futureToEmptiness(futurizer, from, 5, 10, exceptionallyConsumer);
        futureToEmptiness(futurizer, from, 3, 800, exceptionallyConsumer);
        futureToEmptiness(futurizer, from, 4, 400, exceptionallyConsumer);
        // .. we add the last timeout with the longest timeout, which we will wait for.
        CompletableFuture<Reply<DataTO>> last = futureToEmptiness(futurizer,
                from, 6, 1000, exceptionallyConsumer);

        // "ACT": (well, each of the above futures have /already/ started executing, but wait for them to finish)

        // :: Now we wait for the last future to timeout.
        try {
            last.get(30, TimeUnit.SECONDS);
            // We should not get to the next line.
            Assert.fail("We should have gotten an ExecutionException with getCause() MatsFuturizerTimeoutException,"
                    + " as the MatzFuturizer should have timed us out.");
        }
        catch (ExecutionException e) {
            // expected.
        }

        // NOTICE: There is a race here. The addition of the traceId to the COWAL happens on a Futurizer thread pool
        // thread, so we can get here before the addition has happened, thus the "500" will not have been added yet.
        // Ask me how I know.. ;-p (Okay, Github Actions crazy sloppy runners fretted it out..)
        // We'll thus wait in a loop here hoping for all the results to appear.
        List<String> expected = Arrays.asList("10", "200", "400", "600", "800", "1000");
        for (int i = 0; i < 200; i++) {
            if (results.size() == expected.size()) {
                break;
            }
            Thread.sleep(20);
        }

        // ASSERT:

        // :: All the futures should now have timed out, and they shall have timed out in the order of timeouts.
        Assert.assertEquals(expected, results);
        // .. and there should not be any Promises left in the MatsFuturizer.
        Assert.assertEquals(0, futurizer.getOutstandingPromiseCount());
    }
}
