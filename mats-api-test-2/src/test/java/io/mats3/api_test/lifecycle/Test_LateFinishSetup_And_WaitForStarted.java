/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.api_test.lifecycle;

import static io.mats3.test.MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

public class Test_LateFinishSetup_And_WaitForStarted {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static MatsEndpoint<Void, StateTO> _ep;
    private final CountDownLatch _waitThreadStarted = new CountDownLatch(1);
    private final CountDownLatch _waitedForStartupFinished = new CountDownLatch(1);

    @BeforeClass
    public static void setupButDontStartTerminator() {
        _ep = MATS.getMatsFactory().staged(TERMINATOR, Void.TYPE, StateTO.class);
        _ep.stage(DataTO.class, (context, sto, dto) -> {
            log.debug("Stage:\n" + context.toString());
            MATS.getMatsTestLatch().resolve(sto, dto);
        });
    }

    @Test
    public void doTest() throws InterruptedException {
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto));

        // Make and start thread that will wait for Endpoint to start.
        Thread waiter = new Thread(() -> {
            _waitThreadStarted.countDown();
            _ep.waitForReceiving(30_000);
            _waitedForStartupFinished.countDown();
        }, "UnitTestWaiter");
        waiter.start();

        // :: Now wait for the waiter-thread to actually fire up - should go instantly.
        boolean threadStarted = _waitThreadStarted.await(5, TimeUnit.SECONDS);
        Assert.assertTrue("Waiter thread should have started!", threadStarted);

        // Wait for the answer in 250 ms - which should not come.
        try {
            MATS.getMatsTestLatch().waitForResult(WAIT_MILLIS_FOR_NON_OCCURRENCE);
            Assert.fail("This should not have happened, since the Endpoint isn't started yet.");
        }
        catch (AssertionError ae) {
            // This is good - we're expecting this "no-show".
        }

        // Assert that waiter-thread has not gotten past waiting
        Assert.assertEquals("Should not have gotten past waiting for ep to start,"
                + " since we haven't started it!", 1, _waitedForStartupFinished.getCount());

        // THIS IS IT! Finish the endpoint!
        _ep.finishSetup();

        // Wait synchronously for terminator to finish, which now should happen pretty fast.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());

        // The waiter should either already have gotten through the waiting, or is in the process of doing so.
        boolean waitedFinishedOk = _waitedForStartupFinished.await(5, TimeUnit.SECONDS);

        // Assert that waiter-thread actually got through.
        Assert.assertTrue("The Waiter thread should have gotten through the waiting,"
                + " since we started the endpoint", waitedFinishedOk);

    }
}
