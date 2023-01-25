package io.mats3.api_test.basics;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Exercising feature of initiating within stage. Notice also the {@link io.mats3.api_test.nestedinitiate} package,
 * where more special features of "nested initiations" are exercised.
 *
 * @author Endre Stølsvik 2023-01-18 13:20 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_InitWithinStage {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SENDING_TERMINATOR = MatsTestHelp.terminator("Sending");
    private static final String RECEIVING_TERMINATOR = MatsTestHelp.terminator("Receiving");

    private static final int NUM_MESSAGES = 10;

    private static final CountDownLatch __countdownLatch = new CountDownLatch(NUM_MESSAGES);

    private static final CopyOnWriteArrayList<DataTO> __resultingMessages = new CopyOnWriteArrayList<>();

    @BeforeClass
    public static void setupEndpointsAndTerminators() {
        // Terminator which initiates multiple new messages
        MATS.getMatsFactory().terminator(SENDING_TERMINATOR, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            ctx.initiate(init -> {
                for (int i = 0; i < NUM_MESSAGES; i++) {
                    init.traceId("Stage-inited message #" + i)
                            .to(RECEIVING_TERMINATOR)
                            .send(new DataTO(Math.PI * i, "Number" + i));
                }
            });
        });

        // Terminator which receives the messages from the above
        MATS.getMatsFactory().terminator(RECEIVING_TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    __resultingMessages.add(dto);
                    __countdownLatch.countDown();
                });
    }

    @Test
    public void doTest() throws InterruptedException {
        // Send message directly to the "Terminator" endpoint.
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(SENDING_TERMINATOR)
                        .send(dto));

        boolean gotten = __countdownLatch.await(30, TimeUnit.SECONDS);
        Assert.assertTrue(gotten);
        List<String> expected = Arrays.asList(
                "Number0",
                "Number1",
                "Number2",
                "Number3",
                "Number4",
                "Number5",
                "Number6",
                "Number7",
                "Number8",
                "Number9");
        List<String> collectSorted = __resultingMessages.stream()
                .map(dataTO -> dataTO.string)
                .sorted()
                .collect(Collectors.toList());

        Assert.assertEquals(expected, collectSorted);
    }
}