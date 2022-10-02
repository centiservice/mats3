package io.mats3.api_test.failure;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;

import static io.mats3.test.MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE;

/**
 * Tests throwing inside the initiator, which should "propagate all the way out", while the about-to-be-sent message
 * will be rolled back and not be sent anyway. Note that in the logs, the situation will be logged on error by the MATS
 * implementation, but there will be no post on the Dead Letter Queue, since we're not in a message reception situation.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]    - init request, but throws RuntimeException, which should propagate all the way out.
 * [Terminator] - should not get the message (but we have a test asserting it will actually get it if do NOT throw!)
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_ThrowsExceptionInInitialization {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> MATS.getMatsTestLatch().resolve(sto, dto));
    }

    /**
     * Tests that an exception is thrown in the initiation block will propagate out of the initiator.
     */
    @Test(expected = TestRuntimeException.class)
    public void exceptionInInitiationShouldPropagateOut() {
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                    throw new TestRuntimeException("Should propagate all the way out.");
                });
    }

    /**
     * Tests that the infrastructure for checking that a message is NOT received is actually working, by sending a
     * message using the same code which we assert that we DO receive!
     */
    @Test
    public void checkTestInfrastructre() {
        // Send a message that does NOT throw in initiation
        sendMessageToTerminator(false);
        // .. thus, the message should be received at TERMINATOR
        Assert.assertNotNull(MATS.getMatsTestLatch().waitForResult(WAIT_MILLIS_FOR_NON_OCCURENCE));
    }

    /**
     * Tests that if an exception is thrown in the initiation block, any sent messages will be rolled back.
     */
    @Test
    public void exceptionInInitiationShouldNotSendMessage() {
        // Send a message that DOES throw in initiation
        sendMessageToTerminator(true);

        // .. thus, the message should NOT be received at TERMINATOR!

        // Wait synchronously for terminator to finish (which it shall not do!)
        try {
            MATS.getMatsTestLatch().waitForResult(WAIT_MILLIS_FOR_NON_OCCURENCE);
        }
        // NOTE! The MatsTestLatch throws AssertionError when the wait time overruns.
        catch (AssertionError e) {
            // Good that we came here! - we should NOT receive the message on the Terminator, due to init Exception.
            log.info("We as expected dit NOT get the message that was sent in the initiator!");
            return;
        }
        Assert.fail("Should NOT have gotten message!");
    }

    private void sendMessageToTerminator(boolean throwInInitiation) {
        DataTO dto = new DataTO(42, "TheAnswer");
        try {
            MATS.getMatsInitiator().initiateUnchecked(
                    (msg) -> {
                        msg.traceId(MatsTestHelp.traceId())
                                .from(MatsTestHelp.from("sendMessageToTerminator"))
                                .to(TERMINATOR)
                                .send(dto);
                        if (throwInInitiation) {
                            throw new TestRuntimeException("Should rollback the initiation, and not send message.");
                        }
                    });
        }
        catch (TestRuntimeException e) {
            log.info("Got expected " + e.getClass().getSimpleName() + " from the MATS Initiation.");
        }
    }

    private static class TestRuntimeException extends RuntimeException {
        public TestRuntimeException(String message) {
            super(message);
        }
    }
}
