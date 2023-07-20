package io.mats3.util.futurizer;

import java.util.ArrayList;
import java.util.List;
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
 * Basic tests of the MatsFuturizer.
 *
 * @author Endre Stølsvik 2019-08-28 00:22 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_MatsFuturizer_Basics {
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
    public void normalMessage() throws ExecutionException, InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futurizer.futurizeNonessential(
                MatsTestHelp.traceId(), "OneSingleMessage", ENDPOINT, DataTO.class, dto);

        Reply<DataTO> reply = future.get(1, TimeUnit.SECONDS);

        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), reply.get());

        log.info("Got the reply from the Future - the latency was " + (reply.getRoundTripNanos() / 1_000_000d)
                + " milliseconds");
    }

    @Test
    public void manyMessages() throws ExecutionException, InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();
        // Warm-up:
        runManyMessagesTest(futurizer, 50); // 1000 msgs -> 1197.108 ms total -> 1.197 ms per message

        // Timed run:
        runManyMessagesTest(futurizer, 50); // 1000 msgs -> 679.391 ms total -> 0.679 ms per message

        // For 10k messages, with logging set to INFO level, "timed run":
        // Got the reply from all [10000] Futures - total time:[1975.412943 ms] , per message:[0.1975412943 ms]
    }

    private void runManyMessagesTest(MatsFuturizer futurizer, int number) throws InterruptedException, ExecutionException,
            TimeoutException {
        // :: Send a bunch of messages
        long startNanos = System.nanoTime();
        List<CompletableFuture<Reply<DataTO>>> futures = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            DataTO dto = new DataTO(i, "TheAnswer");

            futures.add(futurizer.futurizeNonessential(
                    MatsTestHelp.traceId(), "SeveralMessages.futurized", ENDPOINT, DataTO.class, dto));
        }

        // :: Wait for each of them to complete
        for (int i = 0; i < number; i++) {
            Reply<DataTO> reply = futures.get(i).get(60, TimeUnit.SECONDS);
            Assert.assertEquals(new DataTO(i * 2, "TheAnswer:FromService"), reply.get());
        }
        double totalTimeMs = (System.nanoTime() - startNanos) / 1_000_000d;
        String msg = "#TIMED# Got the reply from all [" + number + "] Futures - total time:[" + (totalTimeMs)
                + " ms] , per message:[" + (totalTimeMs / number) + " ms]";
        log.warn(msg);
    }

    @Test
    public void thenApplyWithGet() throws ExecutionException, InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        // :: ARRANGE

        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futurizer.futurizeNonessential(
                MatsTestHelp.traceId(), "OneSingleMessage", ENDPOINT, DataTO.class, dto);

        // :: ACT

        String[] completedOnThreadName = new String[1];
        @SuppressWarnings({"unchecked", "rawtypes"})
        Reply<DataTO>[] reply = new Reply[1];

        // Do a ".thenApply(...)", followed by a ".get()" to get the value..
        CompletableFuture<DataTO> completeFuture = future.thenApply(r -> {
            completedOnThreadName[0] = Thread.currentThread().getName();
            reply[0] = r;
            MatsTestHelp.takeNap(100);
            DataTO in = r.get();
            return new DataTO(in.number * 3, in.string + ":FromThenApply");
        });
        DataTO replyTo = completeFuture.get(5, TimeUnit.SECONDS);

        // :: ASSERT

        log.info("The future was completed on thread [" + completedOnThreadName[0] + "} - the latency was "
                + ((System.nanoTime() - reply[0].getInitiationNanos()) / 1_000_000d) + " milliseconds");

        Assert.assertEquals(new DataTO(dto.number * 2 * 3, dto.string + ":FromService:FromThenApply"), replyTo);
    }
}
