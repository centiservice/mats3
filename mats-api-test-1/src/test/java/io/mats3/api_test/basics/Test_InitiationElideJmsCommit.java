package io.mats3.api_test.basics;

import java.io.Serializable;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionConsumer;
import javax.jms.ConnectionFactory;
import javax.jms.ConnectionMetaData;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
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

import io.mats3.MatsFactory.MatsWrapper;
import io.mats3.MatsInitiator;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_PoolingSerial;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.util.wrappers.ConnectionFactoryWrapper;

/**
 * Checks that if we do not send any messages in an initiation, no JMS Commit will occur.
 *
 * @author Endre Stølsvik - 2021-02-01 - http://endre.stolsvik.com
 */
public class Test_InitiationElideJmsCommit {
    private static final String TERMINATOR = MatsTestHelp.terminator();

    /**
     * This test is for visual inspection of logs.
     */
    @Test
    public void doSingle() {
        Rule_Mats MATS = Rule_Mats.create();
        MATS.beforeAll();

        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

        // Initiate without sending message:
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                });

        // Initiate an actual message:
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());

        MATS.afterAll();
    }

    @Test
    public void runManyTests() throws InterruptedException {
        // :: Arrange

        MatsTestBroker inVmActiveMq = MatsTestBroker.create();
        ConnectionFactory connectionFactory = inVmActiveMq.getConnectionFactory();
        ConnectionFactoryWithCommitCounter wrapper = new ConnectionFactoryWithCommitCounter(connectionFactory);
        JmsMatsJmsSessionHandler sessionPool = JmsMatsJmsSessionHandler_PoolingSerial.create(wrapper);
        JmsMatsFactory<String> matsFactory = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions("test", "testversion",
                sessionPool, MatsSerializerJson.create());
        matsFactory.getFactoryConfig().setConcurrency(5);

        CopyOnWriteArrayList<String> strings = new CopyOnWriteArrayList<>();

        int count = 50;

        CountDownLatch _latch = new CountDownLatch(count);

        matsFactory.terminator("Terminator", StateTO.class, DataTO.class,
                (ctx, state, msg) -> {
                    strings.add(msg.string);
                    // AFTER the commit, let's count down, to communicate to the test.
                    ctx.doAfterCommit(_latch::countDown);
                });

        TreeSet<String> expected = new TreeSet<>();

        // :: Act

        MatsInitiator initiator = matsFactory.getDefaultInitiator();
        for (int i = 0; i < count; i++) {
            String testMsg = "RockRoll:" + i;
            expected.add(testMsg);

            // :: Initiate without a message
            initiator.initiateUnchecked(init -> {
            });

            // :: Initiate WITH a message
            initiator.initiateUnchecked(init -> init
                    .traceId(MatsTestHelp.traceId())
                    .from(MatsTestHelp.from("ManyTests"))
                    .to("Terminator")
                    .send(new DataTO(0, testMsg)));

            // :: Initiate without a message
            initiator.initiateUnchecked(init -> {
            });

            // :: Initiate throwing before sending message
            try {
                initiator.initiateUnchecked(init -> {
                    throw new RuntimeException("Test");
                });
                Assert.fail("Should have been thrown out!");
            }
            catch (RuntimeException e) {
                Assert.assertEquals("Test", e.getMessage());
            }
        }

        // :: Assert

        boolean await = _latch.await(10, TimeUnit.SECONDS);
        if (!await) {
            throw new AssertionError("Didn't get the expected number of messages.");
        }

        TreeSet<String> actual = new TreeSet<>(strings);

        // Assert expected messages
        Assert.assertEquals(expected, actual);

        // :: Now, the magic:
        // There should be exactly 2 x count commits: 1 for each of the sending of the actual message,
        // and 1 for each of the terminator receiving it. The non-sending initiations shall not have counted.
        Assert.assertEquals(2 * count, wrapper.getCommitCount());

        // :: Also, we threw once per send loop, and rollbacks aren't elided (at least yet)
        Assert.assertEquals(count, wrapper.getRollbackCount());

        // :: Clean

        matsFactory.close();
        // Note, this will be a double close, as MatsFactory also has closed the pool. But just to assert that we
        // do not have any lingering JMS Connections:
        int liveConnectionsAfterClose = sessionPool.closeAllAvailableSessions();
        Assert.assertEquals("There should be no live JMS Connections.", 0, liveConnectionsAfterClose);
        inVmActiveMq.close();
    }

    private static class ConnectionFactoryWithCommitCounter extends ConnectionFactoryWrapper {
        private final AtomicInteger _commitCount = new AtomicInteger();
        private final AtomicInteger _rollbackCount = new AtomicInteger();

        public ConnectionFactoryWithCommitCounter(ConnectionFactory targetConnectionFactory) {
            super(targetConnectionFactory);
        }

        @Override
        public Connection createConnection() throws JMSException {
            Connection connection = unwrap().createConnection();
            return new ConnectionWithCommitCallback(connection,
                    _commitCount::incrementAndGet,
                    _rollbackCount::incrementAndGet);
        }

        int getCommitCount() {
            return _commitCount.get();
        }

        int getRollbackCount() {
            return _rollbackCount.get();
        }
    }

    private static class ConnectionWithCommitCallback implements Connection, MatsWrapper<Connection> {
        private final Connection _connection;
        private final Runnable _commitCallback;
        private final Runnable _rollbackCallback;

        public ConnectionWithCommitCallback(Connection connection, Runnable commitCallback, Runnable rollbackCallback) {
            _connection = connection;
            _commitCallback = commitCallback;
            _rollbackCallback = rollbackCallback;
        }

        @Override
        public void setWrappee(Connection target) {
            throw new UnsupportedOperationException("setWrappee");
        }

        @Override
        public Connection unwrap() {
            return _connection;
        }

        @Override
        public Session createSession(boolean transacted, int acknowledgeMode) throws JMSException {
            Session session = unwrap().createSession(transacted, acknowledgeMode);
            return new SessionWithCommitCallback(session, _commitCallback, _rollbackCallback);
        }

        @Override
        public Session createSession(int sessionMode) throws JMSException {
            return unwrap().createSession(sessionMode);
        }

        @Override
        public Session createSession() throws JMSException {
            return unwrap().createSession();
        }

        @Override
        public String getClientID() throws JMSException {
            return unwrap().getClientID();
        }

        @Override
        public void setClientID(String clientID) throws JMSException {
            unwrap().setClientID(clientID);
        }

        @Override
        public ConnectionMetaData getMetaData() throws JMSException {
            return unwrap().getMetaData();
        }

        @Override
        public ExceptionListener getExceptionListener() throws JMSException {
            return unwrap().getExceptionListener();
        }

        @Override
        public void setExceptionListener(ExceptionListener listener) throws JMSException {
            unwrap().setExceptionListener(listener);
        }

        @Override
        public void start() throws JMSException {
            unwrap().start();
        }

        @Override
        public void stop() throws JMSException {
            unwrap().stop();
        }

        @Override
        public void close() throws JMSException {
            unwrap().close();
        }

        @Override
        public ConnectionConsumer createConnectionConsumer(Destination destination, String messageSelector,
                ServerSessionPool sessionPool, int maxMessages) throws JMSException {
            return unwrap().createConnectionConsumer(destination, messageSelector, sessionPool, maxMessages);
        }

        @Override
        public ConnectionConsumer createSharedConnectionConsumer(Topic topic, String subscriptionName,
                String messageSelector, ServerSessionPool sessionPool, int maxMessages) throws JMSException {
            return unwrap().createSharedConnectionConsumer(topic, subscriptionName,
                    messageSelector, sessionPool, maxMessages);
        }

        @Override
        public ConnectionConsumer createDurableConnectionConsumer(Topic topic, String subscriptionName,
                String messageSelector, ServerSessionPool sessionPool, int maxMessages) throws JMSException {
            return unwrap().createDurableConnectionConsumer(topic, subscriptionName, messageSelector, sessionPool,
                    maxMessages);
        }

        @Override
        public ConnectionConsumer createSharedDurableConnectionConsumer(Topic topic, String subscriptionName,
                String messageSelector, ServerSessionPool sessionPool, int maxMessages) throws JMSException {
            return unwrap().createSharedDurableConnectionConsumer(topic, subscriptionName,
                    messageSelector, sessionPool, maxMessages);
        }
    }

    private static class SessionWithCommitCallback implements Session, MatsWrapper<Session> {
        private final Session _session;
        private final Runnable _commitCallback;
        private final Runnable _rollbackCallback;

        public SessionWithCommitCallback(Session session, Runnable commitCallback, Runnable rollbackCallback) {
            _session = session;
            _commitCallback = commitCallback;
            _rollbackCallback = rollbackCallback;
        }

        @Override
        public void setWrappee(Session target) {
            throw new UnsupportedOperationException("setWrappee");
        }

        @Override
        public Session unwrap() {
            return _session;
        }

        @Override
        public BytesMessage createBytesMessage() throws JMSException {
            return unwrap().createBytesMessage();
        }

        @Override
        public MapMessage createMapMessage() throws JMSException {
            return unwrap().createMapMessage();
        }

        @Override
        public Message createMessage() throws JMSException {
            return unwrap().createMessage();
        }

        @Override
        public ObjectMessage createObjectMessage() throws JMSException {
            return unwrap().createObjectMessage();
        }

        @Override
        public ObjectMessage createObjectMessage(Serializable object) throws JMSException {
            return unwrap().createObjectMessage(object);
        }

        @Override
        public StreamMessage createStreamMessage() throws JMSException {
            return unwrap().createStreamMessage();
        }

        @Override
        public TextMessage createTextMessage() throws JMSException {
            return unwrap().createTextMessage();
        }

        @Override
        public TextMessage createTextMessage(String text) throws JMSException {
            return unwrap().createTextMessage(text);
        }

        @Override
        public boolean getTransacted() throws JMSException {
            return unwrap().getTransacted();
        }

        @Override
        public int getAcknowledgeMode() throws JMSException {
            return unwrap().getAcknowledgeMode();
        }

        @Override
        public void commit() throws JMSException {
            _commitCallback.run();
            unwrap().commit();
        }

        @Override
        public void rollback() throws JMSException {
            _rollbackCallback.run();
            unwrap().rollback();
        }

        @Override
        public void close() throws JMSException {
            unwrap().close();
        }

        @Override
        public void recover() throws JMSException {
            unwrap().recover();
        }

        @Override
        public MessageListener getMessageListener() throws JMSException {
            return unwrap().getMessageListener();
        }

        @Override
        public void setMessageListener(MessageListener listener) throws JMSException {
            unwrap().setMessageListener(listener);
        }

        @Override
        public void run() {
            unwrap().run();
        }

        @Override
        public MessageProducer createProducer(Destination destination) throws JMSException {
            return unwrap().createProducer(destination);
        }

        @Override
        public MessageConsumer createConsumer(Destination destination) throws JMSException {
            return unwrap().createConsumer(destination);
        }

        @Override
        public MessageConsumer createConsumer(Destination destination, String messageSelector) throws JMSException {
            return unwrap().createConsumer(destination, messageSelector);
        }

        @Override
        public MessageConsumer createConsumer(Destination destination, String messageSelector, boolean NoLocal)
                throws JMSException {
            return unwrap().createConsumer(destination, messageSelector, NoLocal);
        }

        @Override
        public MessageConsumer createSharedConsumer(Topic topic, String sharedSubscriptionName) throws JMSException {
            return unwrap().createSharedConsumer(topic, sharedSubscriptionName);
        }

        @Override
        public MessageConsumer createSharedConsumer(Topic topic, String sharedSubscriptionName,
                String messageSelector) throws JMSException {
            return unwrap().createSharedConsumer(topic, sharedSubscriptionName, messageSelector);
        }

        @Override
        public Queue createQueue(String queueName) throws JMSException {
            return unwrap().createQueue(queueName);
        }

        @Override
        public Topic createTopic(String topicName) throws JMSException {
            return unwrap().createTopic(topicName);
        }

        @Override
        public TopicSubscriber createDurableSubscriber(Topic topic, String name) throws JMSException {
            return unwrap().createDurableSubscriber(topic, name);
        }

        @Override
        public TopicSubscriber createDurableSubscriber(Topic topic, String name, String messageSelector,
                boolean noLocal) throws JMSException {
            return unwrap().createDurableSubscriber(topic, name, messageSelector, noLocal);
        }

        @Override
        public MessageConsumer createDurableConsumer(Topic topic, String name) throws JMSException {
            return unwrap().createDurableConsumer(topic, name);
        }

        @Override
        public MessageConsumer createDurableConsumer(Topic topic, String name, String messageSelector,
                boolean noLocal) throws JMSException {
            return unwrap().createDurableConsumer(topic, name, messageSelector, noLocal);
        }

        @Override
        public MessageConsumer createSharedDurableConsumer(Topic topic, String name) throws JMSException {
            return unwrap().createSharedDurableConsumer(topic, name);
        }

        @Override
        public MessageConsumer createSharedDurableConsumer(Topic topic, String name,
                String messageSelector) throws JMSException {
            return unwrap().createSharedDurableConsumer(topic, name, messageSelector);
        }

        @Override
        public QueueBrowser createBrowser(Queue queue) throws JMSException {
            return unwrap().createBrowser(queue);
        }

        @Override
        public QueueBrowser createBrowser(Queue queue, String messageSelector) throws JMSException {
            return unwrap().createBrowser(queue, messageSelector);
        }

        @Override
        public TemporaryQueue createTemporaryQueue() throws JMSException {
            return unwrap().createTemporaryQueue();
        }

        @Override
        public TemporaryTopic createTemporaryTopic() throws JMSException {
            return unwrap().createTemporaryTopic();
        }

        @Override
        public void unsubscribe(String name) throws JMSException {
            unwrap().unsubscribe(name);
        }
    }
}
