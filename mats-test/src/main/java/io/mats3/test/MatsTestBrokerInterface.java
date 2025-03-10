package io.mats3.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory;
import io.mats3.MatsFactory.MatsFactoryWrapper;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.Call;
import io.mats3.serial.MatsTrace.StackState;

/**
 * Tool that makes it possible to query the underlying broker of a test MatsFactory for messages, in particular
 * "residual" messages and DLQs. DLQ-fetching is useful if the test is designed to fail a stage, i.e. that a stage under
 * test raises some {@link RuntimeException}, or the special <code>MatsRefuseMessageException</code> due to some
 * internal validation performed.
 * <p/>
 * Usage: If employing the Mats test tools, one of these should have been created for you. For the JUnit
 * <code>Rule_Mats</code>, and JUnit Jupiter <code>Extension_Mats</code>, there's a getter directly on the
 * rule/extension instance. For Spring, employing the <code>@MatsTestContext</code> or corresponding
 * <code>MatsTestInfrastructureConfiguration</code>, an instance is put into the Spring context for you. If you do not
 * use those tools, you will have to create a instance of this class as a Spring bean yourself - either directly by
 * using the {@link #create(ConnectionFactory, MatsFactory) create(..)} methods taking parameters, or indirectly by
 * using the {@link #createForLaterPopulation()} variant, and rely on the <code>SpringJmsMatsFactoryWrapper</code>
 * finding it from the Spring context and populating it for you.
 */
public interface MatsTestBrokerInterface {
    /**
     * If you have both the JMS ConnectionFactory and a JmsMatsFactory available from start, then you can create a
     * working instance right away. Otherwise, typically in a Spring bean context, check out
     * {@link #_latePopulate(ConnectionFactory, MatsFactory)}. Note that the MatsFactory parameter currently needs to be
     * a {@link JmsMatsFactory} - read more at _latePopulate().
     */
    static MatsTestBrokerInterface create(ConnectionFactory connectionFactory, MatsFactory matsFactory) {
        return createForLaterPopulation()._latePopulate(connectionFactory, matsFactory);
    }

    /**
     * Special factory variant, where the needed parameters must be supplied by
     * {@link #_latePopulate(ConnectionFactory, MatsFactory)}, which typically will be handled by the
     * <code>SpringJmsMatsFactoryWrapper</code>.
     *
     * @return an empty, not still ready instance.
     */
    static MatsTestBrokerInterface createForLaterPopulation() {
        return new MatsTestBrokerInterface_JmsMatsFactory();
    }

    /**
     * Waits a couple of seconds for a message to appear on the Dead Letter Queue for the provided endpoint- or stageId
     * (Queue name "DLQ."+matsendpointPrefix+endpointOrStageId) - and also checks the standard common ActiveMQ DLQ
     * (Queue name "ActiveMQ.DLQ") in case the broker is not configured with specific DLQs per Queue, which is relevant
     * if you fire up an unmodified ActiveMQ distribution on the command line, check the {@code MatsTestBroker} class
     * JavaDoc for how to use an external MQ instead of the in-VM which otherwise is fired up. (NOTE: It is HIGHLY
     * suggested to use the "specific DLQ" pattern in any production setting, as this is much easier to reason about
     * when ugly things starts hitting fans).
     *
     * @param endpointOrStageId
     *            the endpoint which is expected to generate a DLQ message.
     * @return the {@link MatsTrace} of the DLQ'ed message.
     */
    MatsMessageRepresentation getDlqMessage(String endpointOrStageId);

    /**
     * <i>This method is most probably not for you!</i>. It is employed by <code>SpringJmsMatsFactoryWrapper</code>, by
     * reflection invocation when it is both on classpath and as an instance in the Spring context, to perform "late
     * setting" of the properties which the tool needs to perform its job. (Reason for reflection: This class resides in
     * the 'mats-test' project - which is for testing, while the <code>SpringJmsMatsFactoryWrapper</code> is in the
     * 'mats-spring-jms' project - which is for "production").
     * <p />
     * The matsFactory parameter currently needs to be a JmsMatsFactory. We need all of the following from it
     * <ul>
     * <li><code>matsFactory.getFactoryConfig().getMatsDestinationPrefix()</code> (standard MatsFactory)</li>
     * <li><code>matsFactory.getFactoryConfig().getMatsTraceKey()</code> (standard MatsFactory)</li>
     * <li>The {@link MatsSerializer} to provide access to contents from messages (from JmsMatsFactory)</li>
     * </ul>
     * <p />
     * <b>Note: The MatsFactory provided may be a {@link MatsFactoryWrapper}, but it must resolve to a
     * {@link JmsMatsFactory} via the {@link MatsFactory#unwrapFully()}!</b> Otherwise, it'll throw an
     * {@link IllegalArgumentException}.
     *
     * @return <code>this</code>
     */
    MatsTestBrokerInterface _latePopulate(ConnectionFactory connectionFactory, MatsFactory matsFactory);

    /**
     * Representation of the Mats message that sat on the DLQ.
     */
    interface MatsMessageRepresentation {
        /**
         * @return the TraceId this message has as being a part of a "call flow" that was initiated with a TraceId.
         */
        String getTraceId();

        /**
         * The message DTO that was provided to the Mats Endpoint which DLQed the message.
         *
         * @param type
         *            the type of the message
         * @param <I>
         *            the type of the message
         * @return the deserialized DTO.
         */
        <I> I getIncomingMessage(Class<I> type);

        /**
         * The state DTO that was provided to the Mats Endpoint which DLQed the message.
         *
         * @param type
         *            the type of the state
         * @param <S>
         *            the type of the state
         * @return the deserialized STO.
         */
        <S> S getIncomingState(Class<S> type);

        /**
         * @return the Mats MessageId of this message.
         */
        String getMatsMessageId();

        /**
         * @return who this message was <code>from</code>, i.e. the stage or initiator that sent the message.
         */
        String getFrom();

        /**
         * @return who this message was <b>for</b>, which <b>obviously</b> should be the value of the endpointId that
         *         you requested in the call to {@link MatsTestBrokerInterface#getDlqMessage(String)}!
         */
        String getTo();
    }

    class MatsTestBrokerInterface_JmsMatsFactory implements MatsTestBrokerInterface {

        private static final Logger log = LoggerFactory.getLogger(MatsTestBrokerInterface.class);

        private ConnectionFactory _connectionFactory;
        private MatsSerializer _matsSerializer;
        private String _matsDestinationPrefix;
        private String _matsTraceKey;

        private MatsTestBrokerInterface_JmsMatsFactory() {
            /* must be filled later */
        }

        private MatsTestBrokerInterface_JmsMatsFactory(ConnectionFactory connectionFactory,
                MatsSerializer matsSerializer,
                String matsDestinationPrefix, String matsTraceKey) {
            _connectionFactory = connectionFactory;
            _matsSerializer = matsSerializer;
            _matsDestinationPrefix = matsDestinationPrefix;
            _matsTraceKey = matsTraceKey;
        }

        public MatsTestBrokerInterface_JmsMatsFactory _latePopulate(ConnectionFactory connectionFactory,
                MatsFactory matsFactory) {
            _connectionFactory = connectionFactory;

            _matsDestinationPrefix = matsFactory.getFactoryConfig().getMatsDestinationPrefix();
            _matsTraceKey = matsFactory.getFactoryConfig().getMatsTraceKey();

            MatsFactory unwrappedMatsFactory = matsFactory.unwrapFully();
            if (!(unwrappedMatsFactory instanceof JmsMatsFactory)) {
                throw new IllegalArgumentException("The _latePopuplate method was invoked with a MatsFactory, which"
                        + " when 'unwrapFully()' did not give a JmsMatsFactory. Sorry, no can do.");
            }
            JmsMatsFactory jmsMatsFactory = (JmsMatsFactory) unwrappedMatsFactory;
            _matsSerializer = jmsMatsFactory.getMatsSerializer();

            return this;
        }

        protected void checkCorrectSetup() {
            if (_connectionFactory == null) {
                throw new IllegalStateException("Missing _connectionFactory, _latePopulate(..) not run.");
            }
            if (_matsSerializer == null) {
                throw new IllegalStateException("Missing _matsSerializer, _latePopulate(..) not run.");
            }
            if (_matsDestinationPrefix == null) {
                throw new IllegalStateException("Missing _matsDestinationPrefix, _latePopulate(..) not run.");
            }
            if (_matsTraceKey == null) {
                throw new IllegalStateException("Missing _matsTraceKey, _latePopulate(..) not run.");
            }
        }

        /**
         * Waits a couple of seconds for a message to appear on the Dead Letter Queue for the provided endpoint- or
         * stageId (Queue name "DLQ."+matsendpointPrefix+endpointOrStageId) - and also checks the standard common
         * ActiveMQ DLQ (Queue name "ActiveMQ.DLQ") in case the broker is not configured with specific DLQs per Queue,
         * which is relevant if you fire up an unmodified ActiveMQ distribution on the command line, check the
         * {@code MatsTestBroker} class JavaDoc for how to use an external MQ instead of the in-VM which otherwise is
         * fired up. (NOTE: It is HIGHLY suggested to use the "specific DLQ" pattern in any production setting, as this
         * is much easier to reason about when ugly things starts hitting fans).
         *
         * @param endpointOrStageId
         *            the endpoint which is expected to generate a DLQ message.
         * @return the {@link MatsTrace} of the DLQ'ed message.
         */
        public MatsMessageRepresentation getDlqMessage(String endpointOrStageId) {
            checkCorrectSetup();
            String specificDlqName = "DLQ." + _matsDestinationPrefix + endpointOrStageId;
            String activeMqStandardDlqName = "ActiveMQ.DLQ";
            String artemisMqStandardDlqName = "DLQ";
            // Note: Evidently, ActiveMq sets the JMSDestination to the original destination when msg is on DLQ
            String activeMqStandardDlqSelector = "JMSDestination = 'queue://" + _matsDestinationPrefix
                    + endpointOrStageId + "'";
            // Artemis has a special property for the original queue of a DLQ'ed message
            String artemisMqStandardDlqSelector = "_AMQ_ORIG_QUEUE = '" + _matsDestinationPrefix
                    + endpointOrStageId + "'";

            log.debug("getDlqMessage(endpointOrStageId:\"" + endpointOrStageId + "\")");
            try {
                Connection jmsConnection = _connectionFactory.createConnection();
                try {
                    jmsConnection.start();

                    int maxWaitMillis = 30_000;

                    // We'll wait for threads to get into receive before we close connection, even if one of the
                    // threads get the DLQ right away, otherwise there's risk of javax.jms.IllegalStateException:
                    // session closed.
                    CountDownLatch threadsReceiving = new CountDownLatch(3);
                    // The latch to trigger when one of the threads finds a DLQ.
                    CountDownLatch latch = new CountDownLatch(1);

                    Message[] lambdaHackMessage = new Message[1];

                    // :: Fire up threads, which each listens for the dead letter from two different queues
                    // Specific DLQ, i.e. "DLQ.<queuename>".
                    new Thread(() -> {
                        try {
                            Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                            Queue specificDlq = jmsSession.createQueue(specificDlqName);
                            MessageConsumer specificDlqConsumer = jmsSession.createConsumer(specificDlq);
                            log.debug("Listening for DLQ message on Specific DLQ [" + specificDlq + "]("
                                    + specificDlq.getClass().getName() + ") for max [" + maxWaitMillis + "] millis.");
                            threadsReceiving.countDown();
                            Message msg = specificDlqConsumer.receive(maxWaitMillis);
                            if (msg != null) {
                                log.info("Found DLQ on Specific DLQ [" + specificDlqName + "]!");
                                lambdaHackMessage[0] = msg;
                            }
                        }
                        catch (JMSException e) {
                            log.warn("Got a JMSException when trying to receive message on"
                                    + " Specific DLQ queue [" + specificDlqName + "].", e);
                        }
                        finally {
                            log.debug("Exiting: DLQ consumer thread for Specific DLQ [" + specificDlqName + "].");
                            latch.countDown();
                        }
                    }, this.getClass().getSimpleName() + "-DlqConsumerThread:SpecificDlq:" + specificDlqName)
                            .start();

                    // ActiveMQ's standard common DLQ, i.e. "ActiveMQ.DLQ".
                    new Thread(() -> {
                        try {
                            Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                            Queue activeMqCommonDlq = jmsSession.createQueue(activeMqStandardDlqName);
                            MessageConsumer activeMqStandardDlqConsumer = jmsSession.createConsumer(activeMqCommonDlq,
                                    activeMqStandardDlqSelector);
                            log.debug("Listening for DLQ message on ActiveMQ's common DLQ ["
                                    + activeMqCommonDlq + "](" + activeMqCommonDlq.getClass().getName()
                                    + ") for max [" + maxWaitMillis + "] millis.");
                            threadsReceiving.countDown();
                            Message msg = activeMqStandardDlqConsumer.receive(maxWaitMillis);
                            if (msg != null) {
                                log.info("Found DLQ on ActiveMQ's Generic DLQ [" + activeMqCommonDlq + "]!");
                                lambdaHackMessage[0] = msg;
                            }
                        }
                        catch (JMSException e) {
                            log.warn("Got a JMSException when trying to receive message on"
                                    + " ActiveMQ common DLQ queue [" + activeMqStandardDlqName + "].", e);
                        }
                        finally {
                            log.debug("Exiting: DLQ consumer thread for ActiveMQ common DLQ ["
                                    + activeMqStandardDlqName + "].");
                            latch.countDown();
                        }
                    }, this.getClass().getSimpleName() + "-DlqConsumerThread:ActiveMqCommonDlq:"
                            + activeMqStandardDlqName)
                            .start();

                    // Artemis's standard common DLQ, i.e. "DLQ".
                    new Thread(() -> {
                        try {
                            Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                            Queue artemisMqCommonDlq = jmsSession.createQueue(artemisMqStandardDlqName);
                            MessageConsumer artemisMqStandardDlqConsumer = jmsSession.createConsumer(artemisMqCommonDlq,
                                    artemisMqStandardDlqSelector);
                            log.debug("Listening for DLQ message on Artemis's common DLQ ["
                                    + artemisMqCommonDlq + "](" + artemisMqCommonDlq.getClass().getName()
                                    + ") for max [" + maxWaitMillis + "] millis.");
                            threadsReceiving.countDown();
                            Message msg = artemisMqStandardDlqConsumer.receive(maxWaitMillis);
                            if (msg != null) {
                                log.info("Found DLQ on Artemis's common DLQ [" + artemisMqCommonDlq + "]!");
                                lambdaHackMessage[0] = msg;
                            }
                        }
                        catch (JMSException e) {
                            log.warn("Got a JMSException when trying to receive message on"
                                    + " Artemis common DLQ queue [" + artemisMqStandardDlqName + "].", e);
                        }
                        finally {
                            log.debug("Exiting: DLQ consumer thread for Artemis common DLQ ["
                                    + artemisMqStandardDlqName + "].");
                            latch.countDown();
                        }
                    }, this.getClass().getSimpleName() + "-DlqConsumerThread:ArtemisCommonDlq:"
                            + artemisMqStandardDlqName)
                            .start();

                    try {
                        threadsReceiving.await(1000, TimeUnit.MILLISECONDS);
                    }
                    catch (InterruptedException e) {
                        throw new IllegalStateException("Got interrupted while waiting for threads"
                                + " to enter receive.", e);
                    }

                    try {
                        latch.await(maxWaitMillis, TimeUnit.MILLISECONDS);
                    }
                    catch (InterruptedException e) {
                        throw new IllegalStateException("Got interrupted while waiting for DLQ listening threads"
                                + " to receive a DLQ message.", e);
                    }

                    Message msg = lambdaHackMessage[0];
                    if (msg == null) {
                        throw new AssertionError("Did not get a message on either of the queues [" + specificDlqName
                                + "], [" + activeMqStandardDlqName + "] or [" + artemisMqStandardDlqName + "] within "
                                + maxWaitMillis + "ms.");
                    }
                    if (!(msg instanceof MapMessage)) {
                        throw new AssertionError("The message gotten from DLQ is not a MapMessage!");
                    }

                    MapMessage matsMM = (MapMessage) msg;

                    byte[] matsTraceBytes = matsMM.getBytes(_matsTraceKey);
                    String matsTraceMeta = matsMM.getString(_matsTraceKey + ":meta");
                    if (matsTraceBytes == null) {
                        throw new AssertionError("Missing MatsTrace bytes on the DLQ JMS Message!");
                    }
                    if (matsTraceMeta == null) {
                        throw new AssertionError("Missing MatsTrace \"meta\" on the DLQ JMS Message!");
                    }

                    log.debug("Length of byte serialized&compressed MatsTrace: " + matsTraceBytes.length);
                    return genericsHack(matsTraceBytes, matsTraceMeta);
                }
                finally {
                    jmsConnection.close(); // Closes both sessions and consumers
                }
            }
            catch (JMSException e) {
                throw new AssertionError("Got a JMSException when trying to receive Mats message on [" + specificDlqName
                        + "] or [" + activeMqStandardDlqName + "].", e);
            }
        }

        /**
         * Just a way to "fix" the '?' of MatsSerializer (the Z type), which don't really matter here, so do not want to
         * expose it for consumers of the tool.
         */
        @SuppressWarnings("unchecked")
        private MatsMessageRepresentation genericsHack(byte[] matsTraceBytes, String matsTraceMeta)
                throws JMSException {
            MatsTrace matsTrace = _matsSerializer.deserializeMatsTrace(matsTraceBytes, matsTraceMeta).getMatsTrace();
            return new MatsMessageRepresentationImpl(_matsSerializer, matsTrace);
        }

        private static class MatsMessageRepresentationImpl implements MatsMessageRepresentation {
            private final MatsSerializer _matsSerializer;

            private final MatsTrace _matsTrace;

            public MatsMessageRepresentationImpl(MatsSerializer matsSerializer, MatsTrace matsTrace) {
                _matsSerializer = matsSerializer;
                _matsTrace = matsTrace;
            }

            @Override
            public String getTraceId() {
                return _matsTrace.getTraceId();
            }

            @Override
            public <I> I getIncomingMessage(Class<I> type) {
                Call currentCall = _matsTrace.getCurrentCall();
                return _matsSerializer
                        .deserializeObject(currentCall.getData(), type, _matsTrace.getMatsSerializerMeta());
            }

            @Override
            public <S> S getIncomingState(Class<S> type) {
                return _matsTrace.getCurrentState()
                        .map(StackState::getState)
                        .map(z -> _matsSerializer.deserializeObject(z, type, _matsTrace.getMatsSerializerMeta()))
                        .orElse(null);
            }

            @Override
            public String getMatsMessageId() {
                return _matsTrace.getCurrentCall().getMatsMessageId();
            }

            @Override
            public String getFrom() {
                return _matsTrace.getCurrentCall().getFrom();
            }

            @Override
            public String getTo() {
                return _matsTrace.getCurrentCall().getTo().getId();
            }
        }
    }
}
