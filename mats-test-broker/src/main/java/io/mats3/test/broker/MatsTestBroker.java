package io.mats3.test.broker;

import java.util.concurrent.ThreadLocalRandom;

import javax.jms.ConnectionFactory;

import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.policy.IndividualDeadLetterStrategy;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A special utility class utilized in tests and Mats test infrastructure - it starts an in-vm ActiveMQ Broker (unless a
 * special system property is set), and a corresponding {@link ConnectionFactory} to this broker.
 *
 * If the system property "{@link #SYSPROP_MATS_TEST_BROKERURL mats.test.brokerurl}" is set to any string, the in-vm
 * broker instance <i>will not</i> be created, and the supplied string will be used for the relevant ConnectionFactory
 * (i.e. the client) brokerURL. The special value "{@link #SYSPROP_MATS_TEST_BROKERURL_VALUE_LOCALHOST LOCALHOST}"
 * implies "tcp://localhost:61616", which is the default for a localhost ActiveMQ connection.
 *
 * @author Endre Stølsvik 2019-05-06 22:42, factored out of <code>Rule_Mats</code> from 2015 - http://stolsvik.com/,
 *         endre@stolsvik.com
 * @author Endre Stølsvik 2021-08-05 15:16, remade from <code>MatsLocalVmActiveMq</code> to clean up: Handling
 *         connection to multiple external brokers (ActiveMQ, Artemis and RabbitMq), and remove DLQ fetcher (as that
 *         should be done with <code>MatsTestBrokerInterface</code>)
 */
public interface MatsTestBroker {

    /**
     * Which Broker client to use (which JMS {@link ConnectionFactory} implementation): activemq (default), artemis or
     * rabbitmq.
     */
    String SYSPROP_MATS_TEST_BROKER = "mats.test.broker";

    /**
     * DEFAULT: Use ActiveMQ as broker.
     */
    String SYSPROP_MATS_TEST_BROKER_VALUE_ACTIVEMQ = "activemq";

    /**
     * Use Artemis (Apache ActiveMQ Artemis) as broker - this was formerly JBoss HornetQ.
     */
    String SYSPROP_MATS_TEST_BROKER_VALUE_ARTEMIS = "artemis";

    /**
     * Use RabbitMQ as broker.
     */
    String SYSPROP_MATS_TEST_BROKER_VALUE_RABBITMQ = "rabbitmq";

    /**
     * System property ("-D" jvm argument) that if set will a. Not start in-vm ActiceMQ instance, and b. make the
     * ConnectionFactory use the value as brokerURL - with the special case that if the value is
     * "{@link #SYSPROP_MATS_TEST_BROKERURL_VALUE_LOCALHOST LOCALHOST}", it will be
     * <code>"tcp://localhost:61616"</code>.
     * <p>
     * Value is {@code "mats.test.brokerurl"}
     */
    String SYSPROP_MATS_TEST_BROKERURL = "mats.test.brokerurl";

    /**
     * If the value of {@link #SYSPROP_MATS_TEST_BROKERURL} is this value OR not set (it is the default), the
     * ConnectionFactory will use "tcp://localhost:61616" as the brokerURL.
     * <p>
     * Value is {@code "in-vm"}
     */
    String SYSPROP_MATS_TEST_BROKERURL_VALUE_IN_VM = "in-vm";

    /**
     * If the value of {@link #SYSPROP_MATS_TEST_BROKERURL} is this value, the ConnectionFactory will use
     * "tcp://localhost:61616" as the brokerURL.
     * <p>
     * Value is {@code "localhost"}
     */
    String SYSPROP_MATS_TEST_BROKERURL_VALUE_LOCALHOST = "localhost";

    /**
     * @return a MatsTestBroker implementation respecting the system properties set. // TODO: Better JavaDoc.
     */
    static MatsTestBroker create() {
        String sysprop_broker = System.getProperty(SYSPROP_MATS_TEST_BROKER);

        if (SYSPROP_MATS_TEST_BROKER_VALUE_ACTIVEMQ.equalsIgnoreCase(sysprop_broker) || sysprop_broker == null) {
            // -> ActiveMQ specified, or default
            return new MatsTestBroker_ActiveMq();
        }
        else if (SYSPROP_MATS_TEST_BROKER_VALUE_ARTEMIS.equalsIgnoreCase(sysprop_broker)) {
            // -> Artemis specified
            return new MatsTestBroker_Artemis();
        }
        else if (SYSPROP_MATS_TEST_BROKER_VALUE_RABBITMQ.equalsIgnoreCase(sysprop_broker)) {
            // -> RabbitMQ specified
            throw new IllegalArgumentException("RabbitMQ support is not yet done.");
        }
        // ?: Might this be a classname, judged by containing at least a dot?
        else if (sysprop_broker.contains(".")) {
            // -> Yes, it contains a dot - might be a class name
            // Try to load this String as a class name
            Class<?> matsTestBrokerClass;
            try {
                matsTestBrokerClass = Class.forName(sysprop_broker);
            }
            catch (ClassNotFoundException e) {
                throw new IllegalStateException("Do not support broker type [" + sysprop_broker + "],"
                        + " even tried instantiating this as a class since it contained a dot, but no such"
                        + " class found.");
            }
            Object matsTestBrokerInstance;
            try {
                matsTestBrokerInstance = matsTestBrokerClass.newInstance();
            }
            catch (InstantiationException | IllegalAccessException e) {
                throw new IllegalStateException("The '" + SYSPROP_MATS_TEST_BROKER + "' system property's value ["
                        + sysprop_broker + "] looked like a class, so we loaded it."
                        + " However, it was not possible to instantiate it.", e);
            }
            if (!(matsTestBrokerInstance instanceof MatsTestBroker)) {
                throw new IllegalStateException("The '" + SYSPROP_MATS_TEST_BROKER + "' system property's value ["
                        + sysprop_broker + "] looked like a class, so we loaded it."
                        + " However, when instantiating it, it was not an implementation of MatsTestBroker.");
            }
            Logger log = LoggerFactory.getLogger(MatsTestBroker.class);
            log.info("MatsTestBroker: The '" + SYSPROP_MATS_TEST_BROKER + "' system property's value ["
                    + sysprop_broker + "] looked like a class, so we loaded and instantiated it. Good luck!");
            return (MatsTestBroker) matsTestBrokerInstance;
        }
        else {
            // -> Don't know this value, and doesn't look like a class - sorry.
            throw new IllegalStateException("Do not support broker type [" + sysprop_broker + "].");
        }
    }

    /**
     * This is a special factory method, which creates a unique (randomly named) in-vm ActiveMQ instance, no matter what
     * the system properties says. This is only relevant in very specific test scenarios where you specifically want
     * more than a single broker, due to wanting multiple distinct MatsFactories utilizing these brokers. It is used by
     * the Mats test suites to check that the SpringConfig handles multiple MatsFactories each employing a different
     * broker with separate JMS ConnectionFactories (which is relevant if you have multiple brokers in your environment
     * to e.g. subdivide the traffic, but some services are relevant for more than one of those subdivisions, and thus
     * those services want "one leg in each broker").
     *
     * @return an in-vm ActiveMQ backed MatsTestBroker, no matter what the system properties says.
     */
    static MatsTestBroker createUniqueInVmActiveMq() {
        return new MatsTestBroker_InVmActiveMq();
    }

    /**
     * @return the ConnectionFactory connecting to the broker.
     */
    ConnectionFactory getConnectionFactory();

    /**
     * Stops the created in-vm broker, if it was created (read {@link #SYSPROP_MATS_TEST_BROKERURL}). Called "close()"
     * to hook into the default Spring lifecycle if it is instantiated as a Spring Bean.
     */
    void close();

    // --- IMPLEMENTATIONS

    /**
     * Creates an in-vm ActiveMQ no matter what the properties says.
     */
    class MatsTestBroker_InVmActiveMq implements MatsTestBroker {
        private static final Logger log = LoggerFactory.getLogger(MatsTestBroker_InVmActiveMq.class);

        protected final BrokerService _brokerService;
        protected final ConnectionFactory _connectionFactory;

        MatsTestBroker_InVmActiveMq() {
            _brokerService = createInVmActiveMqBroker();
            _connectionFactory = createActiveMQConnectionFactory("vm://" + _brokerService.getBrokerName()
                    + "?create=false");
        }

        @Override
        public ConnectionFactory getConnectionFactory() {
            return _connectionFactory;
        }

        @Override
        public void close() {
            closeBroker(_brokerService);
        }

        protected static BrokerService createInVmActiveMqBroker() {
            String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            StringBuilder brokername = new StringBuilder(10);
            brokername.append("MatsTestActiveMQ_");
            for (int i = 0; i < 10; i++)
                brokername.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));

            log.info("Setting up in-vm ActiveMQ BrokerService '" + brokername + "'.");
            BrokerService brokerService = new BrokerService();
            brokerService.setBrokerName(brokername.toString());
            // No need for JMX registry.
            brokerService.setUseJmx(false);
            // No need for persistence (prevents KahaDB dirs from being created).
            brokerService.setPersistent(false);
            // No need for Advisory Messages.
            brokerService.setAdvisorySupport(false);
            // We'll shut it down ourselves.
            brokerService.setUseShutdownHook(false);

            // :: Set Individual DLQ
            // Hear, hear: http://activemq.2283324.n4.nabble.com/PolicyMap-api-is-really-bad-td4284307.html
            // Create the individual DLQ policy, targeting all queues.
            PolicyEntry individualDlqPolicyEntry = new PolicyEntry();
            individualDlqPolicyEntry.setQueue(">"); // all queues
            IndividualDeadLetterStrategy individualDeadLetterStrategy = new IndividualDeadLetterStrategy();
            individualDeadLetterStrategy.setQueuePrefix("DLQ.");
            individualDlqPolicyEntry.setDeadLetterStrategy(individualDeadLetterStrategy);
            // .. Create the PolicyMap containing this DLQ policy.
            PolicyMap policyMap = new PolicyMap();
            policyMap.put(individualDlqPolicyEntry.getDestination(), individualDlqPolicyEntry);
            // .. set this individual DLQ policy on the broker.
            brokerService.setDestinationPolicy(policyMap);

            // Start the broker.
            try {
                brokerService.start();
            }
            catch (Exception e) {
                throw new AssertionError("Could not start ActiveMQ BrokerService '" + brokername + "'.", e);
            }
            return brokerService;
        }

        protected static ConnectionFactory createActiveMQConnectionFactory(String brokerUrl) {
            log.info("Setting up ActiveMQ ConnectionFactory to brokerUrl: [" + brokerUrl + "].");
            org.apache.activemq.ActiveMQConnectionFactory conFactory = new org.apache.activemq.ActiveMQConnectionFactory(
                    brokerUrl);
            RedeliveryPolicy redeliveryPolicy = conFactory.getRedeliveryPolicy();
            // :: Only try redelivery once, since the unit tests does not need any more to prove that they work.
            redeliveryPolicy.setInitialRedeliveryDelay(100);
            redeliveryPolicy.setUseExponentialBackOff(false);
            redeliveryPolicy.setMaximumRedeliveries(1);

            return conFactory;
        }

        protected static void closeBroker(BrokerService _brokerService) {
            log.info("ActiveMQ BrokerService '" + _brokerService.getBrokerName() + "'.stop().");
            try {
                _brokerService.stop();
            }
            catch (Exception e) {
                throw new IllegalStateException("Couldn't stop AMQ Broker!", e);
            }
            log.info("ActiveMQ BrokerService '" + _brokerService.getBrokerName() + "'.waitUntilStopped() - waiting.");
            _brokerService.waitUntilStopped();
            log.info("ActiveMQ BrokerService '" + _brokerService.getBrokerName() + "' exited.");
        }
    }

    /**
     * Either creates an in-vm ActiveMQ, or an ActiveMQ ConnectionFactory to an external URL, based on system
     * properties.
     */
    class MatsTestBroker_ActiveMq implements MatsTestBroker {
        private static final Logger log = LoggerFactory.getLogger(MatsTestBroker_ActiveMq.class);

        protected final BrokerService _brokerService;
        protected final ConnectionFactory _connectionFactory;

        MatsTestBroker_ActiveMq() {
            String sysprop_brokerUrl = System.getProperty(SYSPROP_MATS_TEST_BROKERURL);

            BrokerService brokerService = null;

            // :: Find which broker URL to use
            String brokerUrl;
            if (SYSPROP_MATS_TEST_BROKERURL_VALUE_IN_VM.equalsIgnoreCase(sysprop_brokerUrl)
                    || (sysprop_brokerUrl == null)) {
                brokerService = MatsTestBroker_InVmActiveMq.createInVmActiveMqBroker();
                brokerUrl = "vm://" + brokerService.getBrokerName() + "?create=false";
            }
            else if (SYSPROP_MATS_TEST_BROKERURL_VALUE_LOCALHOST.equalsIgnoreCase(sysprop_brokerUrl)) {
                brokerUrl = "tcp://localhost:61616";
                log.info("SKIPPING setup of in-vm ActiveMQ BrokerService (MQ server), since System Property '"
                        + SYSPROP_MATS_TEST_BROKERURL + "' was set to [" + sysprop_brokerUrl + "]"
                        + " - using [" + brokerUrl + "]");
            }
            else {
                brokerUrl = sysprop_brokerUrl;
                log.info("SKIPPING setup of in-vm ActiveMQ BrokerService (MQ server), since System Property '"
                        + SYSPROP_MATS_TEST_BROKERURL + "' was set to [" + sysprop_brokerUrl + "].");
            }
            _brokerService = brokerService;
            _connectionFactory = MatsTestBroker_InVmActiveMq.createActiveMQConnectionFactory(brokerUrl);
        }

        @Override
        public ConnectionFactory getConnectionFactory() {
            return _connectionFactory;
        }

        @Override
        public void close() {
            if (_brokerService != null) {
                MatsTestBroker_InVmActiveMq.closeBroker(_brokerService);
            }
        }
    }

    /**
     * Creates a connection to an Artemis broker.
     */
    class MatsTestBroker_Artemis implements MatsTestBroker {
        private static final Logger log = LoggerFactory.getLogger(MatsTestBroker_Artemis.class);

        protected final ConnectionFactory _connectionFactory;
        protected final EmbeddedActiveMQ _artemisServer;

        MatsTestBroker_Artemis() {
            String sysprop_brokerUrl = System.getProperty(SYSPROP_MATS_TEST_BROKERURL);

            String brokerUrl;
            EmbeddedActiveMQ server;
            if (SYSPROP_MATS_TEST_BROKERURL_VALUE_IN_VM.equalsIgnoreCase(sysprop_brokerUrl)
                    || (sysprop_brokerUrl == null)) {
                // Create the broker, random id.
                brokerUrl = "vm://" + Math.abs(ThreadLocalRandom.current().nextInt());
                server = createArtemisBroker(brokerUrl);
            }
            else if (SYSPROP_MATS_TEST_BROKERURL_VALUE_LOCALHOST.equalsIgnoreCase(sysprop_brokerUrl)) {
                // -> Yes, there is specified a brokerUrl to connect to, so we don't start in-vm ActiveMQ.
                brokerUrl = "tcp://localhost:61616";
                log.info("SKIPPING setup of Artemis broker, since System Property '"
                        + SYSPROP_MATS_TEST_BROKERURL + "' was set to [" + sysprop_brokerUrl + "]"
                        + " - using [" + brokerUrl + "].");
                server = null;
            }
            else {
                // -> Yes, there is specified a brokerUrl to connect to, so we don't start in-vm ActiveMQ.
                log.info("SKIPPING setup of Artemis broker, since System Property '"
                        + SYSPROP_MATS_TEST_BROKERURL + "' was set to [" + sysprop_brokerUrl + "].");
                brokerUrl = sysprop_brokerUrl;
                server = null;
            }
            // :: Connect to the Artemis broker
            log.info("Setting up Artemis ConnectionFactory to brokerUrl: [" + brokerUrl + "].");
            // Starting with Artemis 1.0.1 you can just use the URI to instantiate the object directly
            _connectionFactory = new org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory(brokerUrl);
            _artemisServer = server;
        }

        public static EmbeddedActiveMQ createArtemisBroker(String brokerUrl) {
            log.info("Setting up in-vm Artemis embedded broker on URL '" + brokerUrl + "'.");
            Configuration config = new ConfigurationImpl();
            try {
                config.setSecurityEnabled(false);
                config.setPersistenceEnabled(false);
                config.addAcceptorConfiguration("in-vm", brokerUrl);

                // :: Configuring for common DLQs (since we can't get individual DLQs to work!!)
                config.addAddressesSetting("#",
                        new AddressSettings()
                                .setDeadLetterAddress(SimpleString.toSimpleString("DLQ"))
                                .setExpiryAddress(SimpleString.toSimpleString("ExpiryQueue"))
                                .setMaxDeliveryAttempts(3));
                // :: This is just trying to emulate the default config from default broker.xml - inspired by
                // Spring Boot which also got problems with default config in embedded mode being a tad lacking.
                // https://github.com/spring-projects/spring-boot/pull/12680/commits/a252bb52b5106f3fec0d3b2b157507023aa04b2b
                // This effectively makes these address::queue combos "sticky" (i.e. not auto-deleting), AFAIK.
                config.addAddressConfiguration(
                        new CoreAddressConfiguration()
                                .setName("DLQ")
                                .addRoutingType(RoutingType.ANYCAST)
                                .addQueueConfiguration(new QueueConfiguration("DLQ")
                                        .setRoutingType(RoutingType.ANYCAST)));
                config.addAddressConfiguration(
                        new CoreAddressConfiguration()
                                .setName("ExpiryQueue")
                                .addRoutingType(RoutingType.ANYCAST)
                                .addQueueConfiguration(new QueueConfiguration("ExpiryQueue")
                                        .setRoutingType(RoutingType.ANYCAST)));
            }
            catch (Exception e) {
                throw new AssertionError("Can't config the Artemis Configuration.", e);
            }

            EmbeddedActiveMQ server = new EmbeddedActiveMQ();
            server.setConfiguration(config);
            try {
                server.start();
            }
            catch (Exception e) {
                throw new AssertionError("Can't start the Artemis Broker.", e);
            }
            return server;
        }

        /**
         * This is an attempt to get Artemis to use the "separate DLQ per queue" solution. They do reference this
         * directly in the documentation, but I cannot get this configuration to work. Needs more research.
         * https://github.com/apache/activemq-artemis/blob/main/docs/user-manual/en/undelivered-messages.md#automatically-creating-dead-letter-resources
         *
         * Note that I've encountered two different problems:
         * <ol>
         * <li>It seemingly just doesn't work! Evidently, what should happen is that all DLQs goes to the
         * "DeadLetterAddress" <i>Address</i> specified, but then there should be created original-queue specific DLQ
         * <i>Queues</i> that consume from these using a <i>Filter</i> that only consume the correct DLQs from the
         * Address. However, I cannot see these queues.</li>
         * <li>The DLQ address and queues are seemingly not "persistent" - they are auto-generated, and as such are
         * auto-deleted when not needed. This however seems to imply that if there are no consumer to the queue when a
         * dead letter is created, it will just not be delivered to that queue at all?! Thus, sometimes when the
         * MatsTestBrokerInterface starts looking for the DLQ, it is not there (and this logic would, if correct,
         * obviously make it useless in a production setup, as the entire idea there is that the actual dead letters
         * should stick around to someone gets to look at them). Note that this is not a problem with the current setup,
         * and the reason for that is, AFAIU, that the actual DLQ address and queue are specified <i>explicitly</i> in
         * the AddressConfiguration. I assume that this could be fixed by using an AddressSetting that specified
         * autoDeleteAddresses=false and autoDeleteQueues=false, as I've tried to do in the code below. However, since I
         * can't the original intent of getting a specific DLQ per queue to work, I do not know if that would work
         * either.</li>
         * </ol>
         */
        public static EmbeddedActiveMQ createArtemisBroker_Should_Be_Individual_DLQs_But_Does_Not_Work(
                String brokerUrl) {
            log.info("Setting up in-vm Artemis embedded broker on URL '" + brokerUrl + "'.");
            Configuration config = new ConfigurationImpl();
            try {
                config.setSecurityEnabled(false);
                config.setPersistenceEnabled(false);
                config.addAcceptorConfiguration("in-vm", brokerUrl);

                // Note: Searching through logs for "has exceeded max delivery attempts" yields information.

                // :: Configuring for separate DLQs, with pattern (which is default) "DLQ." as prefix.
                config.addAddressesSetting("#",
                        new AddressSettings()
                                .setExpiryAddress(SimpleString.toSimpleString("ExpiryQueue"))
                                .setDeadLetterAddress(SimpleString.toSimpleString("DLQ"))
                                .setMaxDeliveryAttempts(3)
                                .setAutoCreateQueues(true) // default true
                                .setAutoCreateAddresses(true) // default true
                                .setAutoDeleteQueues(false)
                                .setAutoCreateDeadLetterResources(true) // CHANGED! default false
                                .setDeadLetterQueuePrefix(SimpleString.toSimpleString("DLQ.")) // default "DLQ."
                                .setDeadLetterQueueSuffix(SimpleString.toSimpleString("")) // default ""
                );
                // :: This is just trying to emulate the default config from default broker.xml - inspired by Spring
                // Boot which also got problems with default config in embedded mode being a tad lacking.
                // https://github.com/spring-projects/spring-boot/pull/12680/commits/a252bb52b5106f3fec0d3b2b157507023aa04b2b
                // This effectively makes these address::queue combos "sticky" (i.e. not auto-deleting), AFAIK.
                config.addAddressConfiguration(
                        new CoreAddressConfiguration()
                                .setName("DLQ")
                                .addRoutingType(RoutingType.ANYCAST)
                                .addQueueConfiguration(new QueueConfiguration("DLQ")
                                        .setRoutingType(RoutingType.ANYCAST)));
                config.addAddressConfiguration(
                        new CoreAddressConfiguration()
                                .setName("ExpiryQueue")
                                .addRoutingType(RoutingType.ANYCAST)
                                .addQueueConfiguration(new QueueConfiguration("ExpiryQueue")
                                        .setRoutingType(RoutingType.ANYCAST)));
            }
            catch (Exception e) {
                throw new AssertionError("Can't config the Artemis Configuration.", e);
            }

            EmbeddedActiveMQ server = new EmbeddedActiveMQ();
            server.setConfiguration(config);
            try {
                server.start();
            }
            catch (Exception e) {
                throw new AssertionError("Can't start the Artemis Broker.", e);
            }
            return server;
        }

        @Override
        public ConnectionFactory getConnectionFactory() {
            return _connectionFactory;
        }

        @Override
        public void close() {
            if (_artemisServer != null) {
                try {
                    _artemisServer.stop();
                }
                catch (Exception e) {
                    log.error("Got problems shutting down the Artemis broker - ignoring.", e);
                }
            }
        }
    }
}
