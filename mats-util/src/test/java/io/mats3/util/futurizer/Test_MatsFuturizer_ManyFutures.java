package io.mats3.util.futurizer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * Tests running many futures in two ways - notice that the ThreadPoolExecutor of MatsFuturizer has 100 threads.
 * <ol>
 * <li>'manyFuturesSequentially()': Sequentially fire off 200 futures one by one, directly doing future.get() after
 * each, without any "thenApply()" or similar. <b>Observation:</b> The "StowFuturizer completer" threads cycles through
 * all the core pool, i.e. from #0 to #9, and then around and around. This is because there is never more than one task
 * running, so it could really have handled it with 1 thread, but evidently the ThreadPoolExecutor likes to exercise its
 * core threads.</li>
 * <li>'manyFuturesConcurrently()': Fire off 200 futures in one go, each having a
 * <code>thenApply({ ...Thread.sleep(300) })"</code>, simulating some processing. <b>Observation</b>: All of
 * "StowFuturizer completer" threads from #0 to #99 threads (i.e. maximumPoolSize) end up in thenApply(sleep) for the
 * first 100 futures, then they all instantly end up in sleep again doing the second set of 100 futures</li>
 * </ol>
 */
public class Test_MatsFuturizer_ManyFutures {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, msg) -> new DataTO(msg.number * 2, msg.string + ":FromService"));
    }

    @Test
    public void manyFuturesSequentially() throws InterruptedException, ExecutionException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        for (int i = 0; i < 200; i++) {
            CompletableFuture<Reply<DataTO>> future = futurizer.futurizeNonessential(
                    UUID.randomUUID().toString(), "futureGet", ENDPOINT, DataTO.class,
                    new DataTO(Math.PI, "FutureGet:" + i));
            Reply<DataTO> reply = future.get(2, TimeUnit.SECONDS);
            Assert.assertEquals(new DataTO(Math.PI * 2, "FutureGet:" + i + ":FromService"), reply.getReply());
        }
    }

    @Test
    public void manyFuturesConcurrently() throws InterruptedException, ExecutionException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        int sleepTimeMillis = 300;

        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            CompletableFuture<String> future = futurizer.futurizeNonessential(
                    UUID.randomUUID().toString(), "futureThenApply", ENDPOINT, DataTO.class,
                    new DataTO(Math.E, "FutureThenApply:" + i))
                    .thenApply(reply -> {
                        // !! Runs on 'StowFuturizer completer' thread.
                        log.info("From within the thenApply() - Thread.sleep(" + sleepTimeMillis + ") - happens on"
                                + " the 'MatsFuturizer completer' thread " + Thread.currentThread());
                        try {
                            Thread.sleep(sleepTimeMillis);
                        }
                        catch (InterruptedException e) {
                            throw new IllegalStateException("Thread.sleep(..) raised"
                                    + " unexpected InterruptedException.", e);
                        }
                        return reply.getReply().string;
                    });

            futures.add(future);
        }

        // Block on the derived future, to ensure that we wait until the thenApply actually evaluates.
        for (int i = 0; i < 200; i++) {
            String stringReply = futures.get(i).get(2, TimeUnit.SECONDS);
            log.info("future.get(...) returned.");
            Assert.assertEquals("FutureThenApply:" + i + ":FromService", stringReply);
        }
    }
}