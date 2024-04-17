package io.mats3.api_test.failure;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionConsumer;
import javax.jms.ConnectionFactory;
import javax.jms.ConnectionMetaData;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.ServerSessionPool;
import javax.jms.Session;
import javax.jms.StreamMessage;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicSubscriber;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;
import io.mats3.api_test.StateTO;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.broker.MatsTestBroker;

import junit.framework.AssertionFailedError;

/**
 * Tests that the "very bad" scenario is handled as expected: When the JMS Session cannot be committed.
 *
 * @author Endre Stølsvik 2024-04-17 22:59 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_VeryBad {

    private static final Logger log = LoggerFactory.getLogger(Test_VeryBad.class);

    @Test
    public void testInfrastructure() throws MatsBackendException, MatsMessageSendException {
        AtomicInteger commitCounter = new AtomicInteger();
        JmsMatsFactory<String> matsFactory = getJmsMatsFactory(commitCounter, 100);

        // Create a single-stage endpoint
        matsFactory.single("Endpoint", String.class, String.class, (ctx, msg) -> msg + "!");

        MatsTestLatch matsTestLatch = new MatsTestLatch();

        // Create a terminator
        matsFactory.terminator("Terminator", StateTO.class, String.class, (ctx, state, msg) -> {
            ctx.doAfterCommit(() -> {
                // Resolve the latch
                matsTestLatch.resolve(state, msg);
            });
        });

        // Send a message to the endpoint
        matsFactory.getDefaultInitiator().initiate(init -> init.traceId("VeryBadTest")
                .from("VeryBadTest")
                .to("Endpoint")
                .replyTo("Terminator", new StateTO(42, 0))
                .request("Hello"));

        // ASSERT
        Result<StateTO, String> expected = matsTestLatch.waitForResult();
        System.out.println("Got expected result: " + expected);
        Assert.assertEquals("Hello!", expected.getData());
        Assert.assertEquals(42, expected.getState().number1);

        // Assert the expected number of commits: Init + Endpoint + Terminator = 3
        Assert.assertEquals(3, commitCounter.get());

        matsFactory.close();
    }

    @Test
    public void jmsExceptionWhenInitiate() throws MatsBackendException {
        AtomicInteger commitCounter = new AtomicInteger();
        JmsMatsFactory<String> matsFactory = getJmsMatsFactory(commitCounter, 1);

        // Send a message to the endpoint
        try {
            matsFactory.getDefaultInitiator().initiate(init -> init.traceId("VeryBadTest")
                    .from("VeryBadTest")
                    .to("Endpoint")
                    .replyTo("Terminator", new StateTO(42, 42.42))
                    .request("Hello"));

            throw new AssertionFailedError("Should not come here - the init should have thrown!");
        }
        catch (MatsMessageSendException e) {
            // Yes! Good!
            log.info("Got expected MatsMessageSendException: " + e.getMessage());
        }

        // Should only be the one attempt from the init
        Assert.assertEquals(1, commitCounter.get());

        matsFactory.close();
    }

    @Test
    public void jmsExceptionInStage() throws MatsBackendException, MatsMessageSendException {
        AtomicInteger commitCounter = new AtomicInteger();
        JmsMatsFactory<String> matsFactory = getJmsMatsFactory(commitCounter, 2);

        // Create a single-stage endpoint
        matsFactory.single("Endpoint", String.class, String.class, (ctx, msg) -> msg + "!");

        MatsTestLatch matsTestLatch = new MatsTestLatch();

        // Create a terminator
        matsFactory.terminator("Terminator", StateTO.class, String.class, (ctx, state, msg) -> {
            ctx.doAfterCommit(() -> {
                // Resolve the latch
                matsTestLatch.resolve(state, msg);
            });
        });

        // Send a message to the endpoint
        matsFactory.getDefaultInitiator().initiate(init -> init.traceId("VeryBadTest")
                .from("VeryBadTest")
                .to("Endpoint")
                .replyTo("Terminator", new StateTO(42, 0))
                .request("Hello"));

        // ASSERT
        Result<StateTO, String> expected = matsTestLatch.waitForResult();
        System.out.println("Got expected result: " + expected);
        Assert.assertEquals("Hello!", expected.getData());
        Assert.assertEquals(42, expected.getState().number1);

        // Assert the expected number of commits: Init + Endpoint x 2 (retry) + Terminator = 3
        Assert.assertEquals(4, commitCounter.get());

        matsFactory.close();
    }

    private static JmsMatsFactory<String> getJmsMatsFactory(AtomicInteger commitCounter, int latchCount) {
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        ConnectionFactory connFactory = matsTestBroker.getConnectionFactory();
        AtomicInteger latch = new AtomicInteger(latchCount);
        ConnectionFactoryWrapper connFactoryWrapped = new ConnectionFactoryWrapper(connFactory, latch, commitCounter);

        // JmsMatsJmsSessionHandler_Simple sessionHandler = JmsMatsJmsSessionHandler_Simple.create(connFactoryWrapped);
        JmsMatsJmsSessionHandler_Pooling sessionHandler = JmsMatsJmsSessionHandler_Pooling.create(connFactoryWrapped);
        JmsMatsFactory<String> matsFactory = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(
                "VeryBadApp", "*testing*", sessionHandler, MatsSerializerJson.create());
        matsFactory.getFactoryConfig().setConcurrency(1); // Only need one (we do also get the "priority" one)
        return matsFactory;
    }

    public static class ConnectionFactoryWrapper implements ConnectionFactory {
        private final ConnectionFactory _delegate;
        private final AtomicInteger _latch;
        private final AtomicInteger _commitCounter;

        public ConnectionFactoryWrapper(ConnectionFactory delegate, AtomicInteger latch, AtomicInteger commitCounter) {
            _delegate = delegate;
            _latch = latch;
            _commitCounter = commitCounter;
        }

        @Override
        public Connection createConnection() throws JMSException {
            return new ConnectionWrapper(_delegate.createConnection(), _latch, _commitCounter);
        }

        @Override
        public Connection createConnection(String userName, String password) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public JMSContext createContext() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public JMSContext createContext(String userName, String password) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public JMSContext createContext(String userName, String password, int sessionMode) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public JMSContext createContext(int sessionMode) {
            throw new IllegalStateException("unexpected invocation");
        }
    }

    private static class ConnectionWrapper implements Connection {
        private final Connection _delegate;
        private final AtomicInteger _latch;
        private final AtomicInteger _commitCounter;

        public ConnectionWrapper(Connection delegate, AtomicInteger latch, AtomicInteger commitCounter) {
            _delegate = delegate;
            _latch = latch;
            _commitCounter = commitCounter;
        }

        @Override
        public Session createSession(boolean transacted, int acknowledgeMode) throws JMSException {
            return new SessionWrapper(_delegate.createSession(transacted, acknowledgeMode), _latch, _commitCounter);
        }

        @Override
        public Session createSession(int sessionMode) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public Session createSession() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public String getClientID() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public void setClientID(String clientID) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public ConnectionMetaData getMetaData() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public ExceptionListener getExceptionListener() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public void setExceptionListener(ExceptionListener listener) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public void start() throws JMSException {
            _delegate.start();
        }

        @Override
        public void stop() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public void close() throws JMSException {
            _delegate.close();
        }

        @Override
        public ConnectionConsumer createConnectionConsumer(Destination destination, String messageSelector,
                ServerSessionPool sessionPool, int maxMessages) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public ConnectionConsumer createSharedConnectionConsumer(Topic topic, String subscriptionName,
                String messageSelector, ServerSessionPool sessionPool, int maxMessages) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public ConnectionConsumer createDurableConnectionConsumer(Topic topic, String subscriptionName,
                String messageSelector, ServerSessionPool sessionPool, int maxMessages) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public ConnectionConsumer createSharedDurableConnectionConsumer(Topic topic, String subscriptionName,
                String messageSelector, ServerSessionPool sessionPool, int maxMessages) {
            throw new IllegalStateException("unexpected invocation");
        }
    }

    private static class SessionWrapper implements Session {
        private final Session _delegate;
        private final AtomicInteger _latch;
        private final AtomicInteger _commitCounter;

        public SessionWrapper(Session delegate, AtomicInteger latch, AtomicInteger commitCounter) {
            _delegate = delegate;
            _latch = latch;
            _commitCounter = commitCounter;
        }

        @Override
        public BytesMessage createBytesMessage() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MapMessage createMapMessage() throws JMSException {
            return _delegate.createMapMessage();
        }

        @Override
        public Message createMessage() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public ObjectMessage createObjectMessage() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public ObjectMessage createObjectMessage(Serializable object) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public StreamMessage createStreamMessage() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public TextMessage createTextMessage() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public TextMessage createTextMessage(String text) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public boolean getTransacted() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public int getAcknowledgeMode() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public void commit() throws JMSException {
            _commitCounter.incrementAndGet();
            if (_latch.decrementAndGet() == 0) {
                throw new JMSException("Very bad TEST SCENARIO!");
            }
            _delegate.commit();
        }

        @Override
        public void rollback() throws JMSException {
            _delegate.rollback();
        }

        @Override
        public void close() throws JMSException {
            _delegate.close();
        }

        @Override
        public void recover() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MessageListener getMessageListener() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public void setMessageListener(MessageListener listener) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public void run() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MessageProducer createProducer(Destination destination) throws JMSException {
            return _delegate.createProducer(destination);
        }

        @Override
        public MessageConsumer createConsumer(Destination destination) throws JMSException {
            return _delegate.createConsumer(destination);
        }

        @Override
        public MessageConsumer createConsumer(Destination destination, String messageSelector) throws JMSException {
            return _delegate.createConsumer(destination, messageSelector);
        }

        @Override
        public MessageConsumer createConsumer(Destination destination, String messageSelector, boolean noLocal) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MessageConsumer createSharedConsumer(Topic topic, String sharedSubscriptionName) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MessageConsumer createSharedConsumer(Topic topic, String sharedSubscriptionName,
                String messageSelector) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public Queue createQueue(String queueName) throws JMSException {
            return _delegate.createQueue(queueName);
        }

        @Override
        public Topic createTopic(String topicName) throws JMSException {
            return _delegate.createTopic(topicName);
        }

        @Override
        public TopicSubscriber createDurableSubscriber(Topic topic, String name) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public TopicSubscriber createDurableSubscriber(Topic topic, String name, String messageSelector,
                boolean noLocal) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MessageConsumer createDurableConsumer(Topic topic, String name) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MessageConsumer createDurableConsumer(Topic topic, String name, String messageSelector,
                boolean noLocal) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MessageConsumer createSharedDurableConsumer(Topic topic, String name) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public MessageConsumer createSharedDurableConsumer(Topic topic, String name, String messageSelector) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public QueueBrowser createBrowser(Queue queue) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public QueueBrowser createBrowser(Queue queue, String messageSelector) {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public TemporaryQueue createTemporaryQueue() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public TemporaryTopic createTemporaryTopic() {
            throw new IllegalStateException("unexpected invocation");
        }

        @Override
        public void unsubscribe(String name) {
            throw new IllegalStateException("unexpected invocation");
        }
    }

}
