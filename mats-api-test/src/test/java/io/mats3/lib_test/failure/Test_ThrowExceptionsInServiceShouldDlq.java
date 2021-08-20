package io.mats3.lib_test.failure;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests 2 scenarios:
 * <ol>
 * <li>The simplest failure in a single-stage service: A single-stage endpoint is invoked from the Initiator, but the
 * service throws a {@link RuntimeException}, which should put the message on the MQ DLQ for that endpoint's queue after
 * the MQ has retried its configured number of times (in test there is one initial delivery, and one retry).</li>
 * <li>The special "insta-DLQ" feature: A single-stage endpoint is invoked from the Initiator, but the service throws a
 * {@link MatsRefuseMessageException}, which should put the message on the MQ DLQ for that endpoint's queue right away,
 * without retries.</li>
 * </ol>
 * <p/>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - request
 *     [Service] - throws RuntimeException or MatsRefuseMessageException, message ends up on DLQ (after MQ retries).
 * [Terminator]  - <i>does not get message!</i>
 * </pre>
 *
 * @author Endre Stølsvik - 2015 + 2019-09-21 21:50 - http://endre.stolsvik.com
 */
public class Test_ThrowExceptionsInServiceShouldDlq {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static volatile AtomicInteger _serviceInvocations;

    public static final String THROW_NOTHING = "No throwing, please!";
    public static final String THROW_RUNTIME = "THROW_RUNTIME";
    public static final String THROW_MATSREFUSE = "THROW_MATSREFUSE";

    @BeforeClass
    public static void setupServiceAndTerminator() {
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    _serviceInvocations.incrementAndGet();
                    if (dto.string.equals(THROW_RUNTIME)) {
                        context.reply(dto);
                        throw new RuntimeException("Should send message to DLQ after retries.");
                    }
                    if (dto.string.equals(THROW_MATSREFUSE)) {
                        context.reply(dto);
                        throw new MatsRefuseMessageException("Should send message directly to DLQ, w/o retries.");
                    }
                    return dto;
                });

        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> MATS.getMatsTestLatch().resolve(context, sto, dto));
    }

    @Test
    public void checkTestInfrastructure() {
        log.info("-------- #### running test: checkTestInfrastructure()");
        DataTO dto = doTest(THROW_NOTHING, 1, false);

        // Wait for the reply that the TERMINATOR gets
        Result<StateTO, DataTO> reply = MATS.getMatsTestLatch().waitForResult();

        // Assert that the TERMINATOR got what we expected.
        Assert.assertEquals(dto, reply.getData());
        Assert.assertEquals(new StateTO(420, 420.024), reply.getState());
    }

    @Test
    public void throwRuntimeExceptionInStageShouldRedeliverAndDlq() {
        log.info("-------- #### running test: throwRuntimeExceptionInStageShouldRedeliverAndDlq()");
        doTest(THROW_RUNTIME, 2, true);
    }

    @Test
    public void throwMatsRefuseExceptionInStageShoudInstaDlq() {
        log.info("-------- #### running test: throwMatsRefuseExceptionInStageShoudInstaDlq()");
        doTest(THROW_MATSREFUSE, 1, true);
    }

    public DataTO doTest(String sendString, int expectedInvocationCount, boolean expectDlq) {
        log.info(".. sending string ["+sendString+"]");
        _serviceInvocations = new AtomicInteger();
        DataTO dto = new DataTO(42, sendString);
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // ?: Should we expect this test to DLQ?
        if (expectDlq) {
            // Wait for the DLQ
            MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(SERVICE);
            Assert.assertEquals(SERVICE, dlqMessage.getTo());

            // Assert that we got the expected number of invocations
            /*
             * Adding hack here 2021-08-18, to allow for usage of external Artemis MQ:
             * 
             * 1. We seemingly cannot set the number of redeliveries on the client side with Artemis. In Mats testing
             * scenario on ActiveMQ - both in-vm, and external - it is set to 1 delivery and 1 redelivery, with a total
             * of 2 (it is set on the ActiveMQConnectionFactory). A default Artemis broker has 10 attempts total. When
             * we run Artemis in-vm, we configure the server to have 3 total deliveries.
             * 
             * 2. I do not currently know of a way to implement the MatsRefuseMessage (aka "insta-DLQ") solution for
             * Artemis (the insta-DLQ code resides in the class JmsMatsMessageBrokerSpecifics, and relies on the
             * mentioned ability to set the number of redeliveries client side). Thus, the distinction between
             * RuntimeException and MatsRefuseException tested in the two tests does not exist when using Artemis.
             * 
             * Therefore, we accept both the "expectedInvocationCount" (which is 1 (insta-DLQ) or 2 (with retries)) for
             * ActiveMQ, and 10 for external Artemis, and 3 for in-vm Artemis, as the number of correct invocations.
             */
            log.info("The number of service invocations was: [" + _serviceInvocations + "]");
            boolean okNumberOfInvocations = _serviceInvocations.get() == expectedInvocationCount
                    || _serviceInvocations.get() == 10
                    || _serviceInvocations.get() == 3;
            Assert.assertTrue("Did not get the correct number of deliveries, which should be ["
                    + expectedInvocationCount + "] or 10. It was [" + _serviceInvocations.get() + "]",
                    okNumberOfInvocations);

            // Assert that the reply was not received by terminator
            // Note: If we've found the message on DLQ, there are pretty slim chances that it also has gotten
            // to the terminator.
            try {
                MATS.getMatsTestLatch().waitForResult(50);
            }
            catch (AssertionError ae) {
                log.info("When waiting for latch, we got the expected AssertionError,"
                        + " meaning that the TERMINATOR did NOT get a message, good!");
                return null;
            }

            Assert.fail("The TERMINATOR actually received the reply, while it should NOT have received it!");
        }
        return dto;
    }

    public static void main(String[] args) {
        Test_ThrowExceptionsInServiceShouldDlq.MATS.beforeAll();
        Test_ThrowExceptionsInServiceShouldDlq.setupServiceAndTerminator();
        Test_ThrowExceptionsInServiceShouldDlq test = new Test_ThrowExceptionsInServiceShouldDlq();
        while (true) {
            System.out.println("");
            System.out.println("----XXXX New Round---------------------------------------------------------------");
            System.out.println("");
            test.checkTestInfrastructure();
            test.throwRuntimeExceptionInStageShouldRedeliverAndDlq();
            test.throwMatsRefuseExceptionInStageShoudInstaDlq();
        }
    }
}
