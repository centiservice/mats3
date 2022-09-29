package io.mats3.api_test.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.api.intercept.MatsInterceptable.MatsLoggingInterceptor;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * This test sends off quite a few standard priority messages, <i>and then</i> fires off a set of interactive priority
 * messages. The expectation is that the smaller set of interactive messages shall finish before the standard, even
 * though they were added after all the standard flows were initiated - i.e. they get a "cut the line" flag. This is
 * accomplished in Mats JMS impl by having two sets of consumers, one set for standard priority, and one set for
 * interactive priority. These consumers use a JMS Selector to either pick only standard messages, or pick only
 * interactive messages. Therefore, all the standard messages (flows) makes the standard consumers busy, while the later
 * added interactive messages (flows) "bypass" that queue by being consumed by the interactive consumers.
 *
 * @author Endre Stølsvik 2022-09-28 23:09 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_InteractivePriority {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static CountDownLatch _standardLatch;
    private static CountDownLatch _interactiveLatch;

    @BeforeClass
    public static void setupEndpoints() {
        // Run pretty high concurrency
        MATS.getMatsFactory().getFactoryConfig().setConcurrency(10);

        // :: Service
        MatsEndpoint<DataTO, Void> single = MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (ctx, msg) -> {
                    return msg;
                });
        single.getEndpointConfig().setAttribute(MatsLoggingInterceptor.SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY,
                true);

        // :: Terminator
        MatsEndpoint<Void, StateTO> terminator = MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class,
                DataTO.class,
                (ctx, state, msg) -> {
                    if (ctx.isInteractive()) {
                        _interactiveLatch.countDown();
                        // log.warn("INTERACTIVE!");
                    }
                    else {
                        _standardLatch.countDown();
                        // log.error("STANDARD!");
                    }
                });
        terminator.getEndpointConfig().setAttribute(
                MatsLoggingInterceptor.SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY, true);
    }

    @Test
    public void interactive_should_bypass_standard() throws InterruptedException {
        int outerStandardMax = 10;
        int innerStandardMax = 3000;

        _standardLatch = new CountDownLatch(outerStandardMax * innerStandardMax);

        Thread[] standardSenderThreads = new Thread[outerStandardMax];
        for (int outer = 0; outer < outerStandardMax; outer++) {
            final int outerF = outer;
            standardSenderThreads[outer] = new Thread(() -> {
                MATS.getMatsInitiator().initiateUnchecked(init -> {
                    for (int inner = 0; inner < innerStandardMax; inner++) {
                        init.traceId("Standard" + MatsTestHelp.traceId())
                                .from(MatsTestHelp.from("standard"))
                                .to(SERVICE)
                                .setTraceProperty(MatsLoggingInterceptor.SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, true)
                                .replyTo(TERMINATOR, new StateTO(outerF * inner, Math.E))
                                .request(new DataTO(Math.PI, "" + inner + "" + outerF));
                    }
                });
            }, "Standard Sender Thread #" + outer);
            standardSenderThreads[outer].start();
        }
        for (int outer = 0; outer < outerStandardMax; outer++) {
            standardSenderThreads[outer].join();
        }

        log.info("##### Finished sending " + outerStandardMax + " x " + innerStandardMax + " = "
                + (outerStandardMax * innerStandardMax) + " standard priority messages.");

        int outerInteractiveMax = 5;
        int innerInteractiveMax = 100;

        _interactiveLatch = new CountDownLatch(outerInteractiveMax * innerInteractiveMax);

        Thread[] interactiveSenderThreads = new Thread[outerInteractiveMax];
        for (int outer = 0; outer < outerInteractiveMax; outer++) {
            final int outerF = outer;
            interactiveSenderThreads[outer] = new Thread(() -> {
                MATS.getMatsInitiator().initiateUnchecked(init -> {
                    for (int inner = 0; inner < innerInteractiveMax; inner++) {
                        init.traceId("Interactive" + MatsTestHelp.traceId())
                                .from(MatsTestHelp.from("interactive"))
                                .interactive()
                                .to(SERVICE)
                                .setTraceProperty(MatsLoggingInterceptor.SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, true)
                                .replyTo(TERMINATOR, new StateTO(outerF * inner, Math.E))
                                .request(new DataTO(Math.PI, "" + inner + "" + outerF));
                    }
                });
            }, "Interactive Sender Thread #" + outer);
            interactiveSenderThreads[outer].start();
        }
        for (int outer = 0; outer < outerInteractiveMax; outer++) {
            interactiveSenderThreads[outer].join();
        }
        log.info("##### Finished sending " + outerInteractiveMax + " x " + innerInteractiveMax + " = "
                + (outerInteractiveMax * innerInteractiveMax) + " INTERACTIVE priority messages.");

        long nanosStart_wait = System.nanoTime();
        long[] nanosTaken_standard = new long[1];
        long[] nanosTaken_interactive = new long[1];

        Thread standard_waiter = new Thread(() -> {
            try {
                _standardLatch.await(60, TimeUnit.SECONDS);
                nanosTaken_standard[0] = System.nanoTime() - nanosStart_wait;
            }
            catch (InterruptedException e) {
                /* ignore */
            }
        }, "Standard waiter");

        Thread interactive_waiter = new Thread(() -> {
            try {
                _interactiveLatch.await(60, TimeUnit.SECONDS);
                nanosTaken_interactive[0] = System.nanoTime() - nanosStart_wait;
            }
            catch (InterruptedException e) {
                /* ignore */
            }
        }, "Interactive waiter");

        standard_waiter.start();
        interactive_waiter.start();
        standard_waiter.join();
        interactive_waiter.join();

        log.info("Standard finished in    [" + (nanosTaken_standard[0] / 1_000_000d) + "] millis.");
        log.info("Interactive finished in [" + (nanosTaken_interactive[0] / 1_000_000d) + "] millis.");

        Assert.assertTrue("The interactive didn't finish before the standard messages",
                nanosTaken_standard[0] > nanosTaken_interactive[0]);
    }
}
