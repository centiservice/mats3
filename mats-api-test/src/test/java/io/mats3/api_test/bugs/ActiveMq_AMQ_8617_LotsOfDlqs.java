package io.mats3.api_test.bugs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.mats3.api_test.StateTO;
import io.mats3.test.broker.MatsTestBroker;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQPrefetchPolicy;
import org.apache.activemq.RedeliveryPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;
import org.slf4j.Logger;

import javax.jms.ConnectionFactory;

/**
 * Testing a failure scenario observed with ActiveMQ with connectionFactory.setNonBlockingRedelivery() and
 * redeliveryPolicy set to exponential backoff with collision avoidance.
 * <p/>
 * The bug is described in <a href="https://issues.apache.org/jira/browse/AMQ-8617">AMQ-8617</a>, and now fixed for
 * ActiveMQ >= 5.17.3.
 * <p/>
 * Tests both a whole heap DLQing messages in a row (which was the problem in AMQ-8617), and then a set where some DLQs
 * (exception all the time), while others go through, after 0 or 1 exception - the latter to validate that ordinary DLQ
 * and deliveries still work.
 */
public class ActiveMq_AMQ_8617_LotsOfDlqs {
    private static final Logger log = MatsTestHelp.getClassLogger();

    private static final String ENDPOINT = MatsTestHelp.endpoint();

    private static class RetryAndDlqRuntimeException extends RuntimeException {
        public RetryAndDlqRuntimeException() {
        }
    }

    /**
     * These tests are only run if we're on ActiveMQ.
     */
    public static class ActiveMQSpecific extends ActiveMq_AMQ_8617_LotsOfDlqs {
        @ClassRule
        public static final Rule_Mats MATS = Rule_Mats.create();

        @Before
        public void cleanMatsFactories() {
            MATS.cleanMatsFactories();
        }

        private static final int TEST_SPECIFIC_TOTAL_REDELIVERY_ATTEMPTS = 3;

        @BeforeClass
        public static void setActiveMqRedeliveries() {
            if (!shouldRunActiveMqSpecifics(MATS.getJmsConnectionFactory())) {
                return;
            }

            ActiveMQConnectionFactory amqConnectionFactory = (ActiveMQConnectionFactory) MATS.getJmsConnectionFactory();

            // To trig the ActiveMQ-specific AMQ-8617 bug, we need multiple redeliveries.
            int totalAttempts = 3; // Must be >=3.

            // Check failure with e.g. ActiveMQ v5.16.6 - it'll throw a 'IllegalArgumentException: Illegal execution
            // time' when trying to schedule the next redelivery on 'java.util.Timer.sched(..)'.
            // It was fixed in 5.17.3+. Those are Java 11+, while Mats is (per 2023-07-16) Java 8.
            RedeliveryPolicy redeliveryPolicy = amqConnectionFactory.getRedeliveryPolicy();
            redeliveryPolicy.setMaximumRedeliveries(totalAttempts - 1); // Total = delivery + #redeliveries.

            // Increase the prefetch to get the test to run faster. Does not compromise test integrity.
            // (All the 200 messages will then be "downloaded" right away, thus we only get a single set of
            // attempt+retries.)
            ActiveMQPrefetchPolicy prefetchPolicy = amqConnectionFactory.getPrefetchPolicy();
            prefetchPolicy.setQueuePrefetch(500);
        }

        @Test
        public void bunchOfDlqs_amq8617() throws MatsBackendException, MatsMessageSendException, InterruptedException {
            if (!shouldRunActiveMqSpecifics(MATS.getJmsConnectionFactory())) {
                return;
            }

            /*
             * Note: Wrt. asserting that all the messages are present. They should be expected to be in the order [0, 1,
             * 2, 3, ..., 0, 1, 2, 3, ...] if things run smoothly.
             *
             * Note: The retry-delay is 250 ms. One could and should be inclined to think that we have a problem with
             * timings here, whereby if we had a little spurious lag (like we do on Github Actions all the time), the
             * first attempt might take more than 500 ms, and then we'd get interleaving of messages. HOWEVER, with
             * ActiveMQ, evidently the messages for redelivery are put at the end of the client queue, so they won't be
             * attempted delivered interleaved: The current prefetch-batch of messages must first be attempted
             * delivered, before any of the redeliveries can be performed. This can be validated by adding a
             * MatsTestUtil.takeNap(10) in the receive - which should have ensured that the redelivery-delay was
             * overshot. They are still presented all the first attempt, then all the second attempt. If you however
             * then increase the numMessages to something that exceeds prefetch, you will get the expected interleaving.
             *
             * HOWEVER, to avoid any async problems, we'll just sort it, so that we get [0, 0, 0, 1, 1, 1, 2, 2, 2, ..].
             */

            bunchOfDlqs(MATS, 200, TEST_SPECIFIC_TOTAL_REDELIVERY_ATTEMPTS);
        }

        @Test
        public void interleavedDlqsAndDeliveries_amq8617() throws MatsBackendException,
                MatsMessageSendException,
                InterruptedException {
            if (!shouldRunActiveMqSpecifics(MATS.getJmsConnectionFactory())) {
                return;
            }

            interleavedDlqsAndDeliveries(MATS, 100, TEST_SPECIFIC_TOTAL_REDELIVERY_ATTEMPTS);
        }

    }

    /**
     * These tests should run on all supported brokers.
     */
    public static class NotActiveMqSpecific extends ActiveMq_AMQ_8617_LotsOfDlqs {
        @ClassRule
        public static final Rule_Mats MATS = Rule_Mats.create();

        @Before
        public void cleanMatsFactories() {
            MATS.cleanMatsFactories();
        }

        @Test
        public void bunchOfDlqs_NonActiveMqSpecific() throws MatsBackendException, MatsMessageSendException,
                InterruptedException {
            // The default MatsTest setup is 2 attempts of delivery.
            bunchOfDlqs(MATS, 50, MatsTestBroker.TEST_TOTAL_DELIVERY_ATTEMPTS);
        }

        @Test
        public void interleavedDlqsAndDeliveries_NonActiveMqSpecific() throws MatsBackendException,
                MatsMessageSendException,
                InterruptedException {
            // The default MatsTest setup is 2 attempts of delivery.
            interleavedDlqsAndDeliveries(MATS, 20, MatsTestBroker.TEST_TOTAL_DELIVERY_ATTEMPTS);
        }
    }

    private static boolean shouldRunActiveMqSpecifics(ConnectionFactory jmsConnectionFactory) {
        if (!(jmsConnectionFactory instanceof ActiveMQConnectionFactory)) {
            log.info("Test skipped, since ConnectionFactory isn't ActiveMQ.");
            return false;
        }
        return true;
    }

    private static void bunchOfDlqs(Rule_Mats MATS, int numMessages, int totalAttempts) throws MatsBackendException,
            MatsMessageSendException,
            InterruptedException {
        // We want 1 for concurreny, so that one thread and thus one consumer must handle all messages.
        MATS.getMatsFactory().getFactoryConfig().setConcurrency(1);

        // Each message will be presented totalAttempts due to redelivery settings.
        CountDownLatch countDownLatch = new CountDownLatch(numMessages * totalAttempts);

        // The messages
        CopyOnWriteArrayList<Integer> numbers = new CopyOnWriteArrayList<>();

        // Create the single receiving endpoint.
        MATS.getMatsFactory().terminator(ENDPOINT, StateTO.class, Integer.class,
                (context, state, sequence) -> {
                    numbers.add(sequence);
                    countDownLatch.countDown();
                    log.info("RECEIVED NUMBER: " + sequence);
                    throw new RetryAndDlqRuntimeException();
                });

        // :: ACT

        MATS.getMatsInitiator().initiate(init -> {
            for (int i = 0; i < numMessages; i++) {
                init.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .send(i);
            }
        });

        boolean gotIt = countDownLatch.await(30, TimeUnit.MINUTES);

        // :: ASSERT
        Assert.assertTrue("Didn't get messages.", gotIt);

        log.info("Messages/numbers:" + numbers);

        numbers.sort(Comparator.naturalOrder());

        ArrayList<Integer> expected = new ArrayList<>();
        for (int i = 0; i < numMessages; i++) {
            for (int j = 0; j < totalAttempts; j++) {
                expected.add(i);
            }
        }

        Assert.assertEquals(expected, numbers);
    }

    public static void interleavedDlqsAndDeliveries(Rule_Mats MATS, int perGroup, int totalAttempts) throws MatsBackendException,
            MatsMessageSendException, InterruptedException {
        // We want 1 for concurreny, so that one thread and thus one consumer must handle all messages.
        MATS.getMatsFactory().getFactoryConfig().setConcurrency(1);

        int numMessages = perGroup * 3; // There will be 3 types of deliveries

        // Each DLQing message will be presented totalAttempts due to redelivery settings.
        CountDownLatch ok_countDownLatch = new CountDownLatch(perGroup);
        CountDownLatch redeliveryThenOk_countDownLatch = new CountDownLatch(perGroup * 2);
        CountDownLatch dlq_countDownLatch = new CountDownLatch(perGroup * totalAttempts);

        // The messages
        CopyOnWriteArrayList<Integer> ok_numbers = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Integer> redeliveryThenOk_numbers = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Integer> dlq_numbers = new CopyOnWriteArrayList<>();

        // Create the single receiving endpoint.
        MATS.getMatsFactory().terminator(ENDPOINT, StateTO.class, Integer.class,
                (context, state, sequence) -> {
                    int messageType = sequence % 3;
                    System.out.println("XXXX: MESSAGETYPE: " + messageType);
                    if (messageType == 0) {
                        // -> OK
                        ok_numbers.add(sequence);
                        ok_countDownLatch.countDown();
                        System.out.println("OK NUMBER: " + sequence);
                        // Good, so don't throw
                    }
                    else if (messageType == 1) {
                        // -> RedeliveryThenOk
                        // Have we already seen this message?
                        boolean alreadySeen = redeliveryThenOk_numbers.contains(sequence);
                        redeliveryThenOk_numbers.add(sequence);
                        redeliveryThenOk_countDownLatch.countDown();
                        System.out.println("REDELIVERY-THEN-OK NUMBER: " + sequence);
                        // ?: Had we already seen this number?
                        if (!alreadySeen) {
                            // -> No, so then we throw this time - waiting for the redelivery.
                            throw new RetryAndDlqRuntimeException();
                        }
                        // If we've seen it before, we accept it this time.
                    }
                    else {
                        // -> Go to DLQ
                        dlq_numbers.add(sequence);
                        dlq_countDownLatch.countDown();
                        System.out.println("DLQ NUMBER: " + sequence);
                        // This should DLQ, so throw each time
                        throw new RetryAndDlqRuntimeException();

                    }
                });

        // :: ACT

        MATS.getMatsInitiator().initiate(init -> {
            for (int i = 0; i < numMessages; i++) {
                init.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .send(i);
            }
        });

        boolean gotOks = ok_countDownLatch.await(30, TimeUnit.MINUTES);
        Assert.assertTrue("Didn't get all OK messages.", gotOks);
        log.info("Gotten all OK messages");

        boolean gotRedeliveryThenOks = redeliveryThenOk_countDownLatch.await(30, TimeUnit.MINUTES);
        Assert.assertTrue("Didn't get all RedeliveryThenOk messages.", gotRedeliveryThenOks);
        log.info("Gotten all RedeliveryThenOk messages");

        boolean gotDlqs = dlq_countDownLatch.await(30, TimeUnit.MINUTES);
        Assert.assertTrue("Didn't get all DLQ messages.", gotDlqs);
        log.info("Gotten all DLQ messages");

        // :: ASSERT

        log.info("OK Messages/numbers:" + ok_numbers);
        log.info("RedeliveryThenOk Messages/numbers:" + redeliveryThenOk_numbers);
        log.info("DLQ Messages/numbers:" + dlq_numbers);

        ok_numbers.sort(Comparator.naturalOrder());
        redeliveryThenOk_numbers.sort(Comparator.naturalOrder());
        dlq_numbers.sort(Comparator.naturalOrder());

        ArrayList<Integer> ok_expected = new ArrayList<>();
        ArrayList<Integer> redeliveryThenOk_expected = new ArrayList<>();
        ArrayList<Integer> dlq_expected = new ArrayList<>();
        for (int i = 0; i < numMessages; i++) {
            int messageType = i % 3;
            if (messageType == 0) {
                // -> Delivered OK right away
                ok_expected.add(i);
            }
            else if (messageType == 1) {
                // -> Delivered after one failed attempt
                // Add Twice.
                redeliveryThenOk_expected.add(i);
                redeliveryThenOk_expected.add(i);
            }
            else {
                // -> DLQed after 'totalAttempts'
                // Add the number 'totalAttempts' times.
                for (int j = 0; j < totalAttempts; j++) {
                    dlq_expected.add(i);
                }
            }
        }

        Assert.assertEquals(ok_expected, ok_numbers);
        Assert.assertEquals(redeliveryThenOk_expected, redeliveryThenOk_numbers);
        Assert.assertEquals(dlq_expected, dlq_numbers);
    }
}