package io.mats3.util.futurizer;

import java.util.Arrays;
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
 * TODO: Unstable on Travis
 *
 * @author Endre Stølsvik 2019-08-30 21:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_MatsFuturizer_Timeouts {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();

    /**
     * This Terminator is set up just to consume all messages produced by this test, so that they do not linger on the
     * MQ - which is a point if we use an external, persistent broker, as MatsTestBroker (within Rule_Mats) can be
     * directed to utilize.
     */
    @BeforeClass
    public static void setupCleanupTerminator() {
        MATS.getMatsFactory().terminator(SERVICE, Object.class, DataTO.class, (ctx, state, msg) -> {
        });
    }

    private CompletableFuture<Reply<DataTO>> futureToEmptiness(MatsFuturizer futurizer, DataTO dto, int timeoutMillis,
            Consumer<Throwable> exceptionallyConsumer) {
        CompletableFuture<Reply<DataTO>> future = futurizer.futurize(
                dto.string, "TimeoutTester.oneshot", SERVICE, timeoutMillis, TimeUnit.MILLISECONDS, DataTO.class, dto,
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
        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futureToEmptiness(MATS.getMatsFuturizer(), dto, 10, null);

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
        // == promise for 500 ms, ref. the futurizer.getOutstandingPromiseCount() calls in all tests.
        // =============================================================================================
        try (MatsFuturizer futurizer = MatsFuturizer.createMatsFuturizer(MATS.getMatsFactory(),
                this.getClass().getSimpleName())) {
            DataTO dto = new DataTO(42, "TheAnswer");
            CompletableFuture<Reply<DataTO>> future = futureToEmptiness(futurizer, dto, 500, null);

            // ----- There will never be a reply, as there is no consumer for the sent message..!

            try {
                future.get(50, TimeUnit.MILLISECONDS);
                // We should not get to the next line.
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

    // TODO: Unstable on Travis (this is the test that failed)
    @Test
    public void severalTimeoutsByMatsFuturizer() throws InterruptedException, TimeoutException {
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
            results.add(mfte.getTraceId());
        };

        // :: Stack up a specific set of futures out-of-order, which should time out in timeout-order
        // NOTICE how the order of the entered futures's timeouts are NOT in order.
        // This is to test that the Timeouter handles both adding new entries that are later than the current
        // earliest, but also earlier than the current earliest.
        // 60, 140, 200, 100, 20, 240
        // 120,320, 420, 220  20  520
        futureToEmptiness(futurizer, new DataTO(1, "120"), 120, exceptionallyConsumer);
        futureToEmptiness(futurizer, new DataTO(2, "320"), 320, exceptionallyConsumer);
        futureToEmptiness(futurizer, new DataTO(3, "420"), 420, exceptionallyConsumer);
        futureToEmptiness(futurizer, new DataTO(4, "220"), 220, exceptionallyConsumer);
        futureToEmptiness(futurizer, new DataTO(5, "20"), 20, exceptionallyConsumer);
        // .. we add the last timeout with the longest timeout.
        CompletableFuture<Reply<DataTO>> last = futureToEmptiness(futurizer, new DataTO(6, "520"), 520,
                exceptionallyConsumer);

        // "ACT": (well, each of the above futures have /already/ started executing, but wait for them to finish)

        // :: Now we wait for the last future to timeout.
        try {
            last.get(5, TimeUnit.SECONDS);
            // We should not get to the next line.
            Assert.fail("We should have gotten an ExecutionException with getCause() MatsFuturizerTimeoutException,"
                    + " as the MatzFuturizer should have timed us out.");
        }
        catch (ExecutionException e) {
            // expected.
        }

        // ASSERT:

        // :: All the futures should now have timed out, and they shall have timed out in the order of timeouts.
        Assert.assertEquals(Arrays.asList("20", "120", "220", "320", "420", "520"), results);
        // .. and there should not be any Promises left in the MatsFuturizer.
        Assert.assertEquals(0, futurizer.getOutstandingPromiseCount());
    }
}
