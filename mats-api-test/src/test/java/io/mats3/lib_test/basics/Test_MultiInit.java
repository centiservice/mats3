package io.mats3.lib_test.basics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * This tests multi-init, with a simple send-to-terminator. Cropping of TraceIds (i.e. not concatenating all of them)
 * happens if numberOfMessages is > 15, and therefore we also test a few times with sizes through that number.
 */
public class Test_MultiInit {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static volatile CountDownLatch _latch;

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    _latch.countDown();
                });
    }

    @Test
    public void send_14() throws InterruptedException {
        doTest(14);
    }

    @Test
    public void send_15() throws InterruptedException {
        doTest(15);
    }

    @Test
    public void send_16() throws InterruptedException {
        doTest(16);
    }

    @Test
    public void send_17() throws InterruptedException {
        doTest(17);
    }

    public void doTest(int numberOfMessages) throws InterruptedException {
        _latch = new CountDownLatch(numberOfMessages);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                    for (int i = 0; i < numberOfMessages; i++) {
                        DataTO dto = new DataTO(i, "Number " + i);
                        msg.traceId(MatsTestHelp.traceId() + "_" + i)
                                .from(MatsTestHelp.from("test"))
                                .to(TERMINATOR)
                                .send(dto);
                    }
                });

        // Wait synchronously for terminator to finish.
        boolean gotAll = _latch.await(1, TimeUnit.MINUTES);
        Assert.assertTrue("Didn't get all expected messages.", gotAll);
    }
}
