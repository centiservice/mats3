package io.mats3.spring.jms.factories;

import java.util.function.Supplier;

import javax.jms.ConnectionFactory;

import io.mats3.MatsFactory;
import io.mats3.spring.jms.factories.ScenarioConnectionFactoryWrapper.ConnectionFactoryProvider;
import io.mats3.spring.jms.factories.ScenarioConnectionFactoryWrapper.ScenarioDecider;
import io.mats3.test.broker.MatsTestBroker;

/**
 * Provides a factory for a Spring-integrated Wrapper/Facade around a JMS ConnectionFactory, which in addition to
 * supporting the production setup, also facilitates the development situation where you often want to run against
 * either an in-vm MQ Broker or against a MQ Broker running on localhost, and also integrates with the
 * "mats-spring-test" integration test library where when run with the <code>MatsTestProfile</code> you will most
 * probably want an in-vm setup (typically mocking up the project-external Mats endpoints that the tested endpoints
 * collaborate with). In the in-vm broker situations, the JMS ConnectionFactory instantiation lambda you provide for
 * what will be used in the production setup will <b>not</b> be invoked, but instead an in-vm ActiveMQ Broker will be
 * started, and an ActiveMQ ConnectionFactory will be produced.
 * <p>
 * Note: The actual decision logic and "Spring interfacing" is done in {@link ScenarioConnectionFactoryWrapper}, which
 * is the actual class of instances that will end up as Spring beans - this class is a producer of such instances,
 * simplifying the configuration, providing sane defaults.
 * <p>
 * Do note that this is purely a convenience functionality, and is in no way required for the Mats system to work.
 * <p>
 * As a matter of fact, you can achieve the exact same with Spring's Profiles (or any other "switch configuration based
 * on some property or state"-logic you can cook up), but this class effectively represents an opinionated
 * implementation of the config switching that will be needed in most scenarios working with Mats, and will probably
 * yield less configuration overall. Moreover, Mats will most probably be employed in a micro/multiple service
 * architecture, and in such situations, it is nice to make some in-house library that constitute basic building blocks
 * that all the services will need. With that in mind, this class also facilitates so you can easily construct an
 * <code>@CompanyMatsSetup</code>-type annotation which alone will pull up both the needed ConnectionFactory (with URL
 * and whatever needed already set) and MatsFactory, which handles all the mentioned scenarios, by merely placing that
 * annotation on the main config class of your service.
 * <p>
 * The point is this: If you are new to Message Queue based development, you will probably soon sit with the problem of
 * how to create a code base that efficiently lets you run it in production, while also is easy to develop on, and being
 * simple to perform integration test on your endpoints - the problem being that you need different types of
 * ConnectionFactories for the different scenarios, and in some cases also needing an in-vm MQ Broker.
 * <p>
 * There are typically four different scenarios that you need to juggle between, the two latter being identical wrt. the
 * Mats setup (which is the reason that {@link MatsScenario} only has three scenarios defined):
 *
 * <ul>
 * <li><b>Production / External Broker</b> (any production-like environments, e.g. Staging/Pre-prod): This is the
 * standard/regular situation for a MQ-based application or service: Connect to the common external production or
 * staging MQ Broker that all other services and their Mats endpoints connect to. <i>(Note that for the scenarios taken
 * into account by this mechanism, all "external environments" (i.e. "production-like") are considered the same.
 * However, you will most probably want the URLs to the external MQ Broker to be different between production and
 * staging and any other such environment. Such switching must be handled by another mechanism.)</i></li>
 *
 * <li><b>Localhost development</b>, where you are developing on one project employing Mats Endpoints, but also run this
 * project's required collaborating projects/services with their Mats Endpoints on your local development box (typically
 * running the project you currently develop on directly in the IDE, while the other can either run in IDE or on command
 * line): Connect to a MQ Broker that you've fired up on your local development box, i.e. localhost, which the other
 * services running locally also connects to.</li>
 *
 * <li><b>Local-VM development</b>, where you just want to run the service alone, probably with some of the other
 * collaborating Mats Endpoints from other projects/services mocked up in the same project: Connect to an in-vm MQ
 * Broker. <b>An important thing to ensure here is that these mocks, which by definition resides on the same classpath,
 * must <i>NOT</i> end up running in the production environment!</b></li>
 *
 * <li><b>Local-VM integration testing</b>, where you have integration tests either utilizing the full app config with
 * overwriting of certain beans/endpoints using "Remock" or @MockBean, and mocking out collaborating Mats Endpoints from
 * other projects/services; or piece together the required beans/endpoints using e.g. @Import and mocking out other
 * endpoints: Connect to an in-vm MQ Broker.</li>
 * </ul>
 *
 * Please now read (all the) JavaDoc of {@link MatsProfiles} and its enum values!
 * <p>
 * For the localhost and localVM scenarios, this class by default utilizes the ActiveMQ Broker and ConnectionFactory.
 * For all scenarios, but in particular for the "regular" (production-like), you can provide whatever ConnectionFactory
 * you want (configured as you desire) - this class will just wrap it.
 *
 * @author Endre Stølsvik 2019-06-08 00:26 - http://stolsvik.com/, endre@stolsvik.com
 */
public class ScenarioConnectionFactoryProducer implements MatsProfiles {

    /**
     * TODO: Deprectated. Remove once >= 0.16
     *
     * @deprecated Use the factory method {@link #withRegularConnectionFactory(ConnectionFactoryProvider)} instead.
     */
    @Deprecated
    public ScenarioConnectionFactoryProducer() {
    }

    /**
     * Creates a {@link ScenarioConnectionFactoryProducer}, where you need to provide the {@link MatsScenario#REGULAR
     * REGULAR} scenario's {@link ConnectionFactoryProvider}. This is the one that will be used in Production and
     * Production-like environments, e.g. Staging, Acceptance Testing, Pre-Prod or whatever you call those environments.
     * You may invoke the other "withXXX" methods to tailor further, but this is optional. After finished setup, invoke
     * {@link #build()} to get the {@link ScenarioConnectionFactoryWrapper} that you then can feed to a
     * {@link MatsFactory}.
     * <p>
     * Notice that deciding between Production and e.g. Staging and which corresponding ConnectionFactory URL or even
     * type of ConnectionFactory you should employ here, is up to you: You might be using configuration properties,
     * Spring's Profile concept, System Properties (java cmd line "-D"-properties), or system environment variables -
     * only you know how you differentiate between these environments.
     * <p>
     * Notice: You are required to set up this provider lambda. However, if you are just setting up your project,
     * wanting to start writing tests, or otherwise only use the {@link MatsScenario#LOCALVM LocalVM scenario}, and thus
     * will not utilize the "regular" scenario just yet, you could just supply a lambda here that throws e.g. an
     * IllegalStateException or somesuch - which probably will remind you to fix this before going into production!
     *
     * @param regularConnectionFactoryProvider
     *            a provider for the ConnectionFactory to use in the {@link MatsScenario#REGULAR REGULAR} scenario.
     * @return a {@link ScenarioConnectionFactoryProducer} on which you then optionally can invoke
     *         {@link #withLocalhostConnectionFactory(ConnectionFactoryProvider)},
     *         {@link #withLocalVmConnectionFactory(ConnectionFactoryProvider)} and
     *         {@link #withScenarioDecider(ScenarioDecider)}, before invoking {@link #build()}.
     */
    public static ScenarioConnectionFactoryProducer withRegularConnectionFactory(
            ConnectionFactoryProvider regularConnectionFactoryProvider) {
        ScenarioConnectionFactoryProducer ret = new ScenarioConnectionFactoryProducer();
        ret._regularConnectionFactoryProvider = regularConnectionFactoryProvider;
        return ret;
    }

    /**
     * TODO: Deprecated, remove once >= 0.16.0
     *
     * @deprecated use {@link #withRegularConnectionFactory(ConnectionFactoryProvider)} instead.
     */
    @Deprecated
    public ScenarioConnectionFactoryProducer regularConnectionFactory(ConnectionFactoryProvider providerLambda) {
        _regularConnectionFactoryProvider = providerLambda;
        return this;
    }

    /**
     * Optional: Provide a ConnectionFactoryProvider lambda for the {@link MatsScenario#LOCALHOST LOCALHOST} scenario
     * (which only is meant to be used for development and possibly testing). If this is not provided, it will be a
     * somewhat specially tailored ActiveMQ ConnectionFactory with the URL <code>"tcp://localhost:61616"</code>, read
     * more in the <a href="https://activemq.apache.org/tcp-transport-reference">ActiveMQ documentation</a>. The
     * tailoring entails DLQ after 1 retry, and 100 ms delay between delivery and the sole redelivery attempt.
     *
     * @param localhostConnectionFactoryProvider
     *            a provider for the ConnectionFactory to use in the {@link MatsScenario#LOCALHOST LOCALHOST} scenario.
     * @return <code>this</code>, for chaining.
     * @see <a href="https://activemq.apache.org/tcp-transport-reference">ActiveMQ TCP Transport Reference</a>
     */
    public ScenarioConnectionFactoryProducer withLocalhostConnectionFactory(
            ConnectionFactoryProvider localhostConnectionFactoryProvider) {
        _localhostConnectionFactoryProvider = localhostConnectionFactoryProvider;
        return this;
    }

    /**
     * TODO: Deprecated, remove once >= 0.16.0
     *
     * @deprecated use {@link #withLocalhostConnectionFactory(ConnectionFactoryProvider)} instead.
     */
    @Deprecated
    public ScenarioConnectionFactoryProducer localhostConnectionFactory(
            ConnectionFactoryProvider providerLambda) {
        return withLocalhostConnectionFactory(providerLambda);
    }

    /**
     * Very optional: Provide a ConnectionFactoryProvider lambda for the {@link MatsScenario#LOCALVM LOCALVM} scenario
     * (which only is meant to be used for development and testing). The implementation of the lambda is expected to
     * return a ConnectionFactory to an in-vm MQ Broker. You probably want to have this ConnectionFactory wrapped in
     * your own extension of {@link ConnectionFactoryWithStartStopWrapper}, which provide a way to start and stop the
     * in-vm MQ Broker. If this configuration is not provided (and not having to handle this mess is a big point of this
     * class, so do not provide this unless you want a different broker entirely!), the class
     * {@link MatsTestBroker} will be instantiated by invoking
     * {@link MatsTestBroker#createUniqueInVmActiveMq()}. From the instance of {@code MatsTestBroker}, the
     * {@link MatsTestBroker#getConnectionFactory() ConnectionFactory will be gotten}.
     * <p>
     * Notice that the {@code MatsTestBroker} has a small extra feature, where you can have it produce a
     * ConnectionFactory to localhost or a specific URL instead of the in-vm MQ Broker it otherwise produces, by means
     * of specifying a special system property ("-D" options) (In this case, it does not create the in-vm MQ Broker
     * either). The rationale for this extra feature is if you wonder how big a difference it makes to run your tests
     * against a proper MQ Broker instead of the in-vm and fully non-persistent MQ Broker you by default get. Read
     * JavaDoc at {@link MatsTestBroker}.
     *
     * @param localVmConnectionFactoryProvider
     *            a provider for the ConnectionFactory to use in the {@link MatsScenario#LOCALVM LOCALVM} scenario.
     * @return <code>this</code>, for chaining.
     * @see MatsTestBroker
     */
    public ScenarioConnectionFactoryProducer withLocalVmConnectionFactory(
            ConnectionFactoryProvider localVmConnectionFactoryProvider) {
        _localVmConnectionFactoryProvider = localVmConnectionFactoryProvider;
        return this;
    }

    /**
     * TODO: Deprecated, remove once >= 0.16.0
     *
     * @deprecated use {@link #withLocalhostConnectionFactory(ConnectionFactoryProvider)} instead.
     */
    @Deprecated
    public ScenarioConnectionFactoryProducer localVmConnectionFactory(ConnectionFactoryProvider providerLambda) {
        return withLocalVmConnectionFactory(providerLambda);
    }

    /**
     * Very optional: If you do not set the {@link ScenarioDecider} using the
     * {@link #withScenarioDecider(ScenarioDecider)} method, you get a
     * {@link ConfigurableScenarioDecider#createDefaultScenarioDecider()}. This default is configured to throw
     * {@link IllegalStateException} if none of the {@link MatsScenario}s are chosen. This method can override that by
     * invoking the {@link ConfigurableScenarioDecider#setDefaultScenario(Supplier)} method for you.
     * <p />
     * Note: This should not be used in conjunction with invoking the {@link #withScenarioDecider(ScenarioDecider)}, as
     * you should have handled the default scenario situation in the ScenarioDecider you provide!
     *
     * @param defaultScenario
     *            which {@link MatsScenario} should be chosen if the {@link ScenarioDecider} does not choose one.
     * @return <code>this</code>, for chaining.
     */
    public ScenarioConnectionFactoryProducer withDefaultScenario(MatsScenario defaultScenario) {
        if (!(_scenarioDecider instanceof ConfigurableScenarioDecider)) {
            throw new IllegalStateException("The ScenarioDecider in this " + this.getClass().getSimpleName() + " is not"
                    + " of type " + ConfigurableScenarioDecider.class.getSimpleName()
                    + ", implying that you've used the"
                    + " method .scenarioDecider(..) to set a different implementation, thus I do not know how to set"
                    + " the default scenario on it.");
        }
        ((ConfigurableScenarioDecider) _scenarioDecider).setDefaultScenario(() -> defaultScenario);
        return this;
    }

    /**
     * Can optionally be overridden should you decide to use a different decision-scheme than the
     * {@link ConfigurableScenarioDecider#createDefaultScenarioDecider() default}. We need a decision for which
     * MatsScenario is in effect for this JVM, and you can override the standard here. <i>(Do note that if you want to
     * run integration tests against a specific Active MQ Broker, e.g. on your localhost, there is already a feature for
     * that in {@link MatsTestBroker}, as mentioned in the JavaDoc of
     * {@link #withLocalVmConnectionFactory(ConnectionFactoryProvider) localVmConnectionFactory(..)}).</i>
     * <p />
     * How you otherwise decide between {@link MatsScenario#REGULAR REGULAR}, {@link MatsScenario#LOCALHOST LOCALHOST}
     * and {@link MatsScenario#LOCALVM LOCALVM} is up to you. <i>(For REGULAR, you will again have to decide whether you
     * are in Production or Staging or whatever, if this e.g. gives different URLs or changes which ConnectionFactory
     * class to instantiate - read more at {@link #withRegularConnectionFactory(ConnectionFactoryProvider)}.</i>
     * <p />
     * Note: This method should not be used in conjunction with invoking the {@link #withDefaultScenario(MatsScenario)}
     * method, as you should have handled the default scenario situation in the ScenarioDecider you provide!
     *
     * @param scenarioDecider
     *            the {@link ScenarioDecider} that should decide between the three {@link MatsScenario}s.
     * @return <code>this</code>, for chaining.
     */
    public ScenarioConnectionFactoryProducer withScenarioDecider(ScenarioDecider scenarioDecider) {
        _scenarioDecider = scenarioDecider;
        return this;
    }

    /**
     * TODO: Deprecated, remove once >= 0.16.0
     *
     * @deprecated use {@link #withScenarioDecider(ScenarioDecider)} instead.
     */
    @Deprecated
    public ScenarioConnectionFactoryProducer scenarioDecider(ScenarioDecider scenarioDecider) {
        return withScenarioDecider(scenarioDecider);
    }

    /**
     * Creates the appropriate {@link ConnectionFactory}, which is a wrapper integrating with Spring - the decision
     * between the configured scenarios is done after all Spring beans are defined.
     *
     * @return the appropriate {@link ConnectionFactory}, which is a wrapper integrating with Spring.
     */
    public ScenarioConnectionFactoryWrapper build() {
        if (_regularConnectionFactoryProvider == null) {
            throw new IllegalStateException("You have not provided the regular ConnectionFactory lambda.");
        }
        return new ScenarioConnectionFactoryWrapper(_regularConnectionFactoryProvider,
                _localhostConnectionFactoryProvider,
                _localVmConnectionFactoryProvider,
                _scenarioDecider);
    }

    /**
     * TODO: Deprecated, remove once >= 0.16.0
     *
     * @deprecated use {@link #build()} instead.
     */
    @Deprecated
    public ScenarioConnectionFactoryWrapper create() {
        return build();
    }

    // ====== Implementation

    private ConnectionFactoryProvider _regularConnectionFactoryProvider;
    private ConnectionFactoryProvider _localhostConnectionFactoryProvider;
    private ConnectionFactoryProvider _localVmConnectionFactoryProvider;
    private ScenarioDecider _scenarioDecider;

    {
        // :: Scenario "mats-localhost":
        // Employing MatsTestBroker, but setting the special LOCALHOST system property to get it to connect to
        // an external localhost broker, default ActiveMQ.
        withLocalhostConnectionFactory((springEnvironment) -> new ConnectionFactoryWithStartStopWrapper() {
            private MatsTestBroker _matsTestBroker;

            @Override
            public ConnectionFactory start(String beanName) {
                // Set System property to get MatsTestBroker to connect to external localhost broker, default ActiveMQ
                System.setProperty(MatsTestBroker.SYSPROP_MATS_TEST_BROKERURL,
                        MatsTestBroker.SYSPROP_MATS_TEST_BROKERURL_VALUE_LOCALHOST);
                _matsTestBroker = MatsTestBroker.create();
                return _matsTestBroker.getConnectionFactory();
            }

            @Override
            public void stop() {
                _matsTestBroker.close();
            }
        });

        // :: Scenario "mats-localvm":
        // Employing MatsTestBroker directly, getting a in-vm broker, default ActiveMQ.
        // (Note that the MatsTestBroker has features to choose which broker type, and can also be directed to connect
        // to localhost instead of employing an in-vm broker - which is what is done in the 'mats-localhost' scenario
        // right above.)
        withLocalVmConnectionFactory((springEnvironment) -> new ConnectionFactoryWithStartStopWrapper() {
            private final MatsTestBroker _matsTestBroker = MatsTestBroker.create();

            @Override
            public ConnectionFactory start(String beanName) {
                return _matsTestBroker.getConnectionFactory();
            }

            @Override
            public void stop() {
                _matsTestBroker.close();
            }
        });

        // :: Using Default ScenarioDecider
        withScenarioDecider(ConfigurableScenarioDecider.createDefaultScenarioDecider());
    }
}
