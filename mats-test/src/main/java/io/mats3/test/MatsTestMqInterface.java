package io.mats3.test;

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
 * Tool that makes it possible to query for a DLQ message on the underlying broker of a test MatsFactory - useful if the
 * test is designed to fail a stage, i.e. that a stage under test raises some {@link RuntimeException}, or the special
 * <code>MatsRefuseMessageException</code> due to some internal validation performed.
 * <p />
 * Usage: If employing the Mats test tools, one of these should have been created for you. For the JUnit Rule_Mats, and
 * JUnit Jupiter Extension_Mats, there's a getter directly on the rule/extension instance. For Spring, employing the
 * <code>@MatsTestContext</code> or corresponding <code>MatsTestInfrastructureConfiguration</code>, there is one in
 * the context. If you do not use those tools, you will have to create a instance of this class as a Spring bean
 * yourself - either directly by using the {@link #create(ConnectionFactory, MatsSerializer, String, String) create(..)}
 * methods taking parameters, or indirectly by using the {@link #createForLaterPopulation()} variant, and rely on the
 * <code>SpringJmsMatsFactoryWrapper</code> finding it from the Spring context and populating it for you.
 */
public class MatsTestMqInterface {
    private static final Logger log = LoggerFactory.getLogger(MatsTestMqInterface.class);

    private ConnectionFactory _connectionFactory;
    private MatsSerializer<?> _matsSerializer;
    private String _matsDestinationPrefix;
    private String _matsTraceKey;

    private MatsTestMqInterface() {
        /* must be filled later */
    }

    private MatsTestMqInterface(ConnectionFactory connectionFactory, MatsSerializer<?> matsSerializer,
            String matsDestinationPrefix, String matsTraceKey) {
        _connectionFactory = connectionFactory;
        _matsSerializer = matsSerializer;
        _matsDestinationPrefix = matsDestinationPrefix;
        _matsTraceKey = matsTraceKey;
    }

    /**
     * Factory method taking the necessary pieces of information needed to fetch DLQs.
     * 
     * @see #create(ConnectionFactory, JmsMatsFactory)
     * @param connectionFactory
     *            the {@link ConnectionFactory} which the {@link MatsFactory} employs.
     * @param matsSerializer
     *            the {@link MatsSerializer} which the {@link MatsFactory} employs.
     * @param matsDestinationPrefix
     *            gotten via <code>matsFactory.getFactoryConfig().getMatsDestinationPrefix()</code>
     * @param matsTraceKey
     *            gotten via <code>matsFactory.getFactoryConfig().getMatsTraceKey()</code>
     * @return a ready-for-action instance.
     */
    public static MatsTestMqInterface create(ConnectionFactory connectionFactory, MatsSerializer<?> matsSerializer,
            String matsDestinationPrefix, String matsTraceKey) {
        return new MatsTestMqInterface(connectionFactory, matsSerializer, matsDestinationPrefix, matsTraceKey);
    }

    /**
     * Convenience variant of {@link #create(ConnectionFactory, MatsSerializer, String, String)} if you have the
     * JmsMatsFactory available.
     */
    public static MatsTestMqInterface create(ConnectionFactory connectionFactory, JmsMatsFactory<?> matsFactory) {
        return create(connectionFactory, matsFactory.getMatsSerializer(), matsFactory.getFactoryConfig()
                .getMatsDestinationPrefix(), matsFactory.getFactoryConfig().getMatsTraceKey());
    }

    /**
     * Special factory variant, where the needed parameters must be supplied by
     * {@link #_latePopulate(ConnectionFactory, MatsFactory)}, which typically will be handled by the
     * <code>SpringJmsMatsFactoryWrapper</code>.
     * 
     * @return an empty, not still ready instance.
     */
    public static MatsTestMqInterface createForLaterPopulation() {
        return new MatsTestMqInterface();
    }

    /**
     * <i>This method is most probably not for you!</i>. It is employed by <code>SpringJmsMatsFactoryWrapper</code>, by
     * reflection invocation when it is both on classpath and as an instance in the Spring context, to perform "late
     * setting" of the properties which the tool needs to perform its job. (Reason for reflection: This class resides in
     * the 'mats-test' project - which is for testing, while the <code>SpringJmsMatsFactoryWrapper</code> is in the
     * 'mats-spring-jms' project - which is for "production").
     * <p />
     * <b>Note: The MatsFactory provided may be a {@link MatsFactoryWrapper}, but it must resolve to a
     * {@link JmsMatsFactory} via the {@link MatsFactory#unwrapFully()}!</b> Otherwise, it'll throw an
     * {@link IllegalArgumentException}.
     */
    public void _latePopulate(ConnectionFactory connectionFactory, MatsFactory matsFactory) {
        MatsFactory unwrappedMatsFactory = matsFactory.unwrapFully();
        if (!(unwrappedMatsFactory instanceof JmsMatsFactory)) {
            throw new IllegalArgumentException("The _latePopuplate method was invoked with a MatsFactory, which"
                    + " when 'unwrapFully()' did not give a JmsMatsFactory. Sorry, no can do.");
        }
        JmsMatsFactory<?> jmsMatsFactory = (JmsMatsFactory<?>) unwrappedMatsFactory;
        _connectionFactory = connectionFactory;
        _matsSerializer = jmsMatsFactory.getMatsSerializer();
        _matsDestinationPrefix = matsFactory.getFactoryConfig().getMatsDestinationPrefix();
        _matsTraceKey = matsFactory.getFactoryConfig().getMatsTraceKey();
    }

    /**
     * Waits a couple of seconds for a message to appear on the Dead Letter Queue for the provided endpointId
     *
     * @param endpointId
     *            the endpoint which is expected to generate a DLQ message.
     * @return the {@link MatsTrace} of the DLQ'ed message.
     */
    public MatsMessageRepresentation getDlqMessage(String endpointId) {
        String dlqQueueName = "DLQ." + _matsDestinationPrefix + endpointId;
        try {
            Connection jmsConnection = _connectionFactory.createConnection();
            try {
                Session jmsSession = jmsConnection.createSession(true, Session.SESSION_TRANSACTED);
                Queue dlqQueue = jmsSession.createQueue(dlqQueueName);
                MessageConsumer dlqConsumer = jmsSession.createConsumer(dlqQueue);
                jmsConnection.start();

                final int maxWaitMillis = 10_000;
                log.info("Listening for DLQ message on queue [" + dlqQueueName + "] for max [" + maxWaitMillis
                        + "] millis.");
                Message msg = dlqConsumer.receive(maxWaitMillis);

                if (msg == null) {
                    throw new AssertionError("Did not get a message on the queue [" + dlqQueueName
                            + "] within " + maxWaitMillis + "ms.");
                }

                MapMessage matsMM = (MapMessage) msg;
                byte[] matsTraceBytes = matsMM.getBytes(_matsTraceKey);
                if (matsTraceBytes == null) {
                    throw new AssertionError("Missing MatsTrace bytes on the DLQ JMS Message!");
                }

                log.info("Found a DLQ Message! Length of byte serialized&compressed MatsTrace: "
                        + matsTraceBytes.length);
                jmsSession.commit();
                jmsConnection.close(); // Closes session and consumer
                return genericsHack(matsMM, matsTraceBytes);
            }
            finally {
                jmsConnection.close();
            }
        }
        catch (JMSException e) {
            throw new IllegalStateException("Got a JMSException when trying to receive Mats message on [" + dlqQueueName
                    + "].", e);
        }
    }

    /**
     * Representation of the Mats message that sat on the DLQ.
     */
    public interface MatsMessageRepresentation {
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
         * @return who this message was <code>from</code>, i.e. the stage that sent the message.
         */
        String getFrom();

        /**
         * @return who this message was <b>for</b>, which <b>obviously</b> should be the value of the endpointId that
         *         you requested in the call to {@link MatsTestMqInterface#getDlqMessage(String)}!
         */
        String getTo();
    }

    /**
     * Just a way to "fix" the '?' of MatsSerializer (the Z type), which don't really matter here, so do not want to
     * expose it for consumers of the tool.
     */
    @SuppressWarnings("unchecked")
    private <Z> MatsMessageRepresentation genericsHack(MapMessage matsMM, byte[] matsTraceBytes) throws JMSException {
        MatsSerializer<Z> matsSerializer = (MatsSerializer<Z>) _matsSerializer;
        MatsTrace<Z> matsTrace = matsSerializer.deserializeMatsTrace(matsTraceBytes,
                matsMM.getString(_matsTraceKey + ":meta")).getMatsTrace();

        return new MatsMessageRepresentationImpl<Z>(matsSerializer, matsTrace);
    }

    private static class MatsMessageRepresentationImpl<Z> implements MatsMessageRepresentation {
        private final MatsSerializer<Z> _matsSerializer;

        private final MatsTrace<Z> _matsTrace;

        public MatsMessageRepresentationImpl(MatsSerializer<Z> matsSerializer, MatsTrace<Z> matsTrace) {
            _matsSerializer = matsSerializer;
            _matsTrace = matsTrace;
        }

        @Override
        public String getTraceId() {
            return _matsTrace.getTraceId();
        }

        @Override
        public <I> I getIncomingMessage(Class<I> type) {
            Call<Z> currentCall = _matsTrace.getCurrentCall();
            return _matsSerializer.deserializeObject(currentCall.getData(), type);
        }

        @Override
        public <S> S getIncomingState(Class<S> type) {
            return _matsTrace.getCurrentState()
                    .map(StackState::getState)
                    .map(z -> _matsSerializer.deserializeObject(z, type))
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
