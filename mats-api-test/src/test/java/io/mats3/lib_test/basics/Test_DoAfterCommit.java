package io.mats3.lib_test.basics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Simple test to check that {@link ProcessContext#doAfterCommit(Runnable)} is run.
 */
public class Test_DoAfterCommit {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final CountDownLatch _countDownLatch = new CountDownLatch(1);

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                    context.doAfterCommit(_countDownLatch::countDown);
                });
    }

    @Test
    public void doTest() throws InterruptedException {
        DataTO dto = new DataTO(10, "ThisIsIt");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());

        // Wait for the doAfterCommit lambda runs.
        boolean await = _countDownLatch.await(1, TimeUnit.SECONDS);
        Assert.assertTrue("The doAfterCommit() lambda didn't occur!", await);
    }
}
