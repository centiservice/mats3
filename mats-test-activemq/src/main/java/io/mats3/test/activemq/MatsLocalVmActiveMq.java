package io.mats3.test.activemq;

import java.util.concurrent.ThreadLocalRandom;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.policy.IndividualDeadLetterStrategy;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsTrace;

/**
 * A special utility class utilized in tests and Mats test infrastructure - it starts an in-vm ActiveMQ Broker (unless a
 * special system property is set), and a corresponding {@link ConnectionFactory} to this broker. It also has a
 * convenience DLQ fetcher for testing.
 *
 * If the system property "{@link #SYSPROP_MATS_TEST_ACTIVEMQ mats.test.activemq}" is set to any string, the in-vm
 * ActiveMQ Broker instance <i>will not</i> be created, and the supplied string will be used for the
 * {@link ActiveMQConnectionFactory ActiveMQ ConnectionFactory} (i.e. the client) brokerURL. The special value
 * "{@link #SYSPROP_VALUE_LOCALHOST LOCALHOST}" implies "tcp://localhost:61616", which is the default for a localhost
 * ActiveMQ connection.
 *
 * @author Endre Stølsvik 2019-05-06 22:42, factored out of <code>Rule_Mats</code> from 2015 - http://stolsvik.com/,
 *         endre@stolsvik.com
 */
public class MatsLocalVmActiveMq {
    private static final Logger log = LoggerFactory.getLogger(MatsLocalVmActiveMq.class);

    /**
     * System property ("-D" jvm argument) that if set will a. Not start in-vm ActiceMQ instance, and b. make the
     * ConnectionFactory use the value as brokerURL - with the special case that if the value is
     * "{@link #SYSPROP_VALUE_LOCALHOST LOCALHOST}", it will be <code>"tcp://localhost:61616"</code>.
     * <p>
     * Value is {@code "mats.test.activemq"}
     */
    public static final String SYSPROP_MATS_TEST_ACTIVEMQ = "mats.test.activemq";

    /**
     * If the value of {@link #SYSPROP_MATS_TEST_ACTIVEMQ} is this value, the ConnectionFactory will use
     * "tcp://localhost:61616" as the brokerURL.
     * <p>
     * Value is {@code "LOCALHOST"}
     */
    public static final String SYSPROP_VALUE_LOCALHOST = "LOCALHOST";

    /**
     * Name of the local broker if created with {@link #createDefaultInVmActiveMq()}, or the prefix if created with
     * {@link #createRandomInVmActiveMq()}.
     * <p>
     * Value is {@code "MatsLocalVmBroker"}
     */
    private static final String BROKER_NAME = "MatsLocalVmBroker";

    private final BrokerService _brokerService;
    private final ActiveMQConnectionFactory _activeMQConnectionFactory;

    /**
     * @return an instance whose brokername is {@link #BROKER_NAME}.
     */
    public static MatsLocalVmActiveMq createDefaultInVmActiveMq() {
        return new MatsLocalVmActiveMq(BROKER_NAME);
    }

    /**
     * @return an instance whose brokername is {@link #BROKER_NAME} + '_' + a random String of 10 chars.
     */
    public static MatsLocalVmActiveMq createRandomInVmActiveMq() {
        return new MatsLocalVmActiveMq(BROKER_NAME + '_' + randomString(10));
    }

    /**
     * @return an instance whose brokername is the provided brokername.
     */
    public static MatsLocalVmActiveMq createInVmActiveMq(String brokername) {
        return new MatsLocalVmActiveMq(brokername);
    }

    private MatsLocalVmActiveMq(String brokername) {
        _brokerService = createBrokerService(brokername);
        _activeMQConnectionFactory = createConnectionFactory(brokername);
    }

    /**
     * @return the test ActiveMQ BrokerService - which might be <code>null</code> depending on system property
     *         {@link #SYSPROP_MATS_TEST_ACTIVEMQ}.
     */
    public BrokerService getBrokerService() {
        return _brokerService;
    }

    /**
     * @return the instance of ActiveMQ ConnectionFactory connecting to the ActiveMQ instance.
     */
    public ActiveMQConnectionFactory getConnectionFactory() {
        return _activeMQConnectionFactory;
    }

    /**
     * Stops the ActiveMQ BrokerService, if it was created (read {@link #SYSPROP_MATS_TEST_ACTIVEMQ}). Called "close()"
     * to hook into the default Spring lifecycle if it is instantiated as a Spring Bean.
     */
    public void close() {
        stopBrokerService(_brokerService);
    }

    /**
     * Waits a couple of seconds for a message to appear on the Dead Letter Queue for the provided endpointId - useful
     * if the test is designed to fail a stage (i.e. that a stage raises some {@link RuntimeException}, or the special
     * <code>MatsRefuseMessageException</code>.
     *
     * @param endpointId
     *            the endpoint which is expected to generate a DLQ message.
     * @return the {@link MatsTrace} of the DLQ'ed message.
     */
    public <Z> MatsTrace<Z> getDlqMessage(MatsSerializer<Z> matsSerializer,
            String matsDestinationPrefix, String matsTraceKey,
            String endpointId) {
        String dlqQueueName = "DLQ." + matsDestinationPrefix + endpointId;
        try {
            Connection jmsConnection = getConnectionFactory().createConnection();
            try {
                Session jmsSession = jmsConnection.createSession(true, Session.SESSION_TRANSACTED);
                Queue dlqQueue = jmsSession.createQueue(dlqQueueName);
                MessageConsumer dlqConsumer = jmsSession.createConsumer(dlqQueue);
                jmsConnection.start();

                final int maxWaitMillis = 5000;
                log.info("Listening for message on queue [" + dlqQueueName + "].");
                Message msg = dlqConsumer.receive(maxWaitMillis);

                if (msg == null) {
                    throw new AssertionError("Did not get a message on the queue [" + dlqQueueName + "] within "
                            + maxWaitMillis + "ms.");
                }

                MapMessage matsMM = (MapMessage) msg;
                byte[] matsTraceBytes = matsMM.getBytes(matsTraceKey);
                log.info("!! Got a DLQ Message! Length of byte serialized&compressed MatsTrace: "
                        + matsTraceBytes.length);
                jmsSession.commit();
                jmsConnection.close(); // Closes session and consumer
                return matsSerializer.deserializeMatsTrace(matsTraceBytes,
                        matsMM.getString(matsTraceKey + ":meta")).getMatsTrace();
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

    // --- STATIC METHODS

    /**
     * <b>Remember to {@link #stopBrokerService(BrokerService) stop the broker} after use!</b>
     *
     * @return a newly created ActiveMQ BrokerService, <b>unless</b> the {@link #SYSPROP_MATS_TEST_ACTIVEMQ} is set to
     *         something, <b>in which case it returns <code>null</code></b> (The broker is then assumed to be running
     *         outside of this JVM).
     */
    protected static BrokerService createBrokerService(String brokername) {
        String sysprop_matsTestActiveMq = System.getProperty(SYSPROP_MATS_TEST_ACTIVEMQ);

        // :? Do we have specific brokerUrl to connect to?
        if (sysprop_matsTestActiveMq == null) {
            // -> No - the system property was not set, hence start the in-vm broker.
            log.info("Setting up in-vm ActiveMQ BrokerService '" + brokername + "' (i.e. the MQ server).");
            BrokerService _amqBrokerService = new BrokerService();
            _amqBrokerService.setBrokerName(brokername);
            _amqBrokerService.setUseJmx(false); // No need for JMX registry.
            _amqBrokerService.setPersistent(false); // No need for persistence (prevents KahaDB dirs from being
                                                    // created).
            _amqBrokerService.setAdvisorySupport(false); // No need Advisory Messages.
            _amqBrokerService.setUseShutdownHook(false);

            // :: Set Individual DLQ
            // Hear, hear: http://activemq.2283324.n4.nabble.com/PolicyMap-api-is-really-bad-td4284307.html
            PolicyMap destinationPolicy = new PolicyMap();
            _amqBrokerService.setDestinationPolicy(destinationPolicy);
            PolicyEntry policyEntry = new PolicyEntry();
            policyEntry.setQueue(">");
            destinationPolicy.put(policyEntry.getDestination(), policyEntry);

            IndividualDeadLetterStrategy individualDeadLetterStrategy = new IndividualDeadLetterStrategy();
            individualDeadLetterStrategy.setQueuePrefix("DLQ.");
            policyEntry.setDeadLetterStrategy(individualDeadLetterStrategy);

            try {
                _amqBrokerService.start();
            }
            catch (Exception e) {
                throw new AssertionError("Could not start ActiveMQ BrokerService '" + brokername + "'.", e);
            }
            return _amqBrokerService;
        }
        // E-> Yes, there is specified a brokerUrl to connect to, so we don't start in-vm ActiveMQ.
        log.info("SKIPPING setup of in-vm ActiveMQ BrokerService (MQ server), since System Property '"
                + SYSPROP_MATS_TEST_ACTIVEMQ + "' was set (to [" + sysprop_matsTestActiveMq + "]).");
        return null;
    }

    /**
     * Public since it can be nice to have in other completely unrelated scenarios - but you should probably be
     * using {@link #getConnectionFactory()}.
     *
     * @return an ActiveMq JMS ConnectionFactory, based on the value of system property
     *         {@link #SYSPROP_MATS_TEST_ACTIVEMQ} - if this is not set, it connects to the BrokerService that was
     *         created with {@link #createBrokerService(String)}, assuming the provided brokername is the same.
     */
    public static ActiveMQConnectionFactory createConnectionFactory(String brokername) {
        String sysprop_matsTestActiveMq = System.getProperty(SYSPROP_MATS_TEST_ACTIVEMQ);

        // :: Find which broker URL to use
        String brokerUrl;
        if (sysprop_matsTestActiveMq == null) {
            brokerUrl = "vm://" + brokername + "?create=false";
        }
        else if (SYSPROP_VALUE_LOCALHOST.equals(sysprop_matsTestActiveMq)) {
            brokerUrl = "tcp://localhost:61616";
        }
        else {
            brokerUrl = sysprop_matsTestActiveMq;
        }
        // :: Connect to the broker
        log.info("Setting up ActiveMQ ConnectionFactory to broker '" + brokername + "', brokerUrl: ["
                + brokerUrl + "].");
        ActiveMQConnectionFactory amqClient = new ActiveMQConnectionFactory(brokerUrl);
        RedeliveryPolicy redeliveryPolicy = amqClient.getRedeliveryPolicy();
        // :: Only try redelivery once, since the unit tests does not need any more to prove that they work.
        redeliveryPolicy.setInitialRedeliveryDelay(100);
        redeliveryPolicy.setUseExponentialBackOff(false);
        redeliveryPolicy.setMaximumRedeliveries(1);

        return amqClient;
    }

    /**
     * Stops the supplied BrokerService <b>if non-null</b> (that is, nothing happens if null is supplied).
     *
     * @param brokerService
     *            the BrokerService that was gotten with {@link #createBrokerService(String)}, which might be null.
     */
    protected static void stopBrokerService(BrokerService brokerService) {
        if (brokerService != null) {
            log.info("AMQ BrokerService '" + brokerService.getBrokerName() + "'.stop().");
            try {
                brokerService.stop();
            }
            catch (Exception e) {
                throw new IllegalStateException("Couldn't stop AMQ Broker!", e);
            }
            log.info("AMQ BrokerService '" + brokerService.getBrokerName() + "'.waitUntilStopped().");
            brokerService.waitUntilStopped();
            log.info("AMQ BrokerService '" + brokerService.getBrokerName() + "' exited.");
        }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * @param length
     *            the desired length of the returned random string.
     * @return a random string of the specified length.
     */
    private static String randomString(int length) {
        StringBuilder buf = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            buf.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        return buf.toString();
    }
}
