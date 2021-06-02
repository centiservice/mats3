package io.mats3.lib_test.basics;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests the Time-To-Live feature, by sending 4 messages with TTL = 150, and then a "flushing" FINAL message without
 * setting the TTL. The service sleeps for 400 ms. The MatsBasicTest has a MatsFactory with concurrency = 1. Therefore,
 * only the first of the TTLed messages should come through, as the rest should have timed out when the service is ready
 * to accept them again. The FINAL message should come through anyway, since it does not have timeout. Therefore, the
 * expected number of delivered messages is 2. Also, a test of the "test infrastructure" is performed, by setting the
 * TTL for the 4 messages to 0, which is "forever", hence all should now be delivered, and the expected number of
 * delivered messages should then be 5.
 *
 * TODO: Unstable on Travis
 *
 * @author Endre Stølsvik 2019-08-25 22:40 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_TimeToLive {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        MatsEndpoint<DataTO, Void> single = MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                // Ensure that there is only processed ONE message per time
                endpointConfig -> endpointConfig.setConcurrency(1),
                // Stage config inherits from EndpointConfig
                MatsFactory.NO_CONFIG,
                (context, dto) -> {
                    if ("DELAY".equals(dto.string)) {
                        try {
                            Thread.sleep(400);
                        }
                        catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
    }

    private static final AtomicInteger _numberOfMessages = new AtomicInteger();

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    _numberOfMessages.incrementAndGet();
                    if (dto.string.startsWith("FINAL")) {
                        MATS.getMatsTestLatch().resolve(sto, dto);
                    }
                });

    }

    @Before
    public void resetStates() {
        _numberOfMessages.set(0);
    }


    @Test
    public void checkTestInfrastructure() {
        doTest(0, 5);
    }

    @Test
    public void testWithTimeToLive() {
        doTest(150, 2);
    }

    private void doTest(long timeToLive, int expectedMessages) {
        DataTO finalDto = new DataTO(42, "FINAL");
        StateTO sto = new StateTO(420, 420.024);

        // :: First send 4 messages with the specified TTL.
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                    for (int i = 0; i < 4; i++) {
                        DataTO dto = new DataTO(i, "DELAY");
                        msg.traceId(MatsTestHelp.traceId())
                                .from(MatsTestHelp.from("first_run_" + i))
                                .to(SERVICE)
                                .nonPersistent(timeToLive)
                                .replyTo(TERMINATOR, sto)
                                .request(dto);
                    }
                });

        // :: Then send a "flushing" FINAL message, which is the one that resolves the latch.
        // NOTE: This must be done in a separate transaction (i.e. separate initiation), or otherwise evidently the
        // persistent (not nonPersistent) final "flushing" message somehow gets prioritization over the nonPersistent
        // ones, and gets to the terminator before the above ones. So either I had to also make this one nonPersistent,
        // or like this, do it in a separate initiation. Since I've had several cases of Travis-CI bailing on me on
        // this specific test, I now do BOTH: Both nonPersistent (but with "forever" TTL), and separate transaction.
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                    msg.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("second_run"))
                            .to(SERVICE)
                            .nonPersistent()
                            .replyTo(TERMINATOR, sto)
                            .request(finalDto);
                });

        // Wait synchronously for terminator to finish (that is, receives the flushing "FINAL" message).
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult(10_000);
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(finalDto.number * 2, finalDto.string + ":FromService"), result.getData());
        Assert.assertEquals(expectedMessages, _numberOfMessages.get());
    }
}
