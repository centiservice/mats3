package io.mats3.test.abstractunit;

import java.util.concurrent.CopyOnWriteArrayList;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import io.mats3.MatsFactory.MatsPlugin;
import io.mats3.api.intercept.MatsLoggingInterceptor;
import io.mats3.api.intercept.MatsMetricsInterceptor;
import io.mats3.test.MatsTestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.MatsSerializer;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.TestH2DataSource;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.MatsFuturizer;

/**
 * Base class containing common code for Rule_Mats and Extension_Mats located in the following modules:
 * <ul>
 * <li>mats-test-junit</li>
 * <li>mats-test-jupiter</li>
 * </ul>
 * This class sets up an in-vm Active MQ broker through the use of {@link MatsTestBroker} which is again utilized to
 * create the {@link MatsFactory} which can be utilized to create unit tests which rely on testing functionality
 * utilizing MATS.
 * <p>
 * The setup and creation of these objects are located in the {@link #beforeAll()} method, this method should be called
 * through the use JUnit and Jupiters life cycle methods.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 * @author Kevin Mc Tiernan, 2020-10-18, kmctiernan@gmail.com
 */
public abstract class AbstractMatsTest<Z> {

    protected static final Logger log = LoggerFactory.getLogger(AbstractMatsTest.class);

    protected MatsSerializer<Z> _matsSerializer;
    protected DataSource _dataSource;

    protected MatsTestBroker _matsTestBroker;
    protected MatsFactory _matsFactory;
    protected CopyOnWriteArrayList<MatsFactory> _createdMatsFactories = new CopyOnWriteArrayList<>();

    // :: Lazy init:
    protected MatsInitiator _matsInitiator;
    protected MatsTestLatch _matsTestLatch;
    protected MatsFuturizer _matsFuturizer;
    protected MatsTestBrokerInterface _matsTestBrokerInterface;

    protected AbstractMatsTest(MatsSerializer<Z> matsSerializer) {
        _matsSerializer = matsSerializer;
    }

    protected AbstractMatsTest(MatsSerializer<Z> matsSerializer, DataSource dataSource) {
        _matsSerializer = matsSerializer;
        _dataSource = dataSource;
    }

    /**
     * Creates an in-vm ActiveMQ Broker which again is utilized to create a {@link JmsMatsFactory}.
     * <p>
     * This method should be called as a result of the following life cycle events for either JUnit or Jupiter:
     * <ul>
     * <li>BeforeClass - JUnit - static ClassRule</li>
     * <li>BeforeAllCallback - Jupiter - static Extension</li>
     * </ul>
     */
    public void beforeAll() {
        log.debug("+++ JUnit/Jupiter +++ BEFORE_CLASS on ClassRule/Extension '" + id(getClass()) + "', JMS and MATS:");

        // ::: ActiveMQ BrokerService and ConnectionFactory
        // ==================================================

        _matsTestBroker = MatsTestBroker.create();

        // ::: MatsFactory
        // ==================================================

        log.debug("Setting up JmsMatsFactory.");
        // Allow for override in specialization classes, in particular the one with DB.
        _matsFactory = createMatsFactory();
        log.debug("--- JUnit/Jupiter --- BEFORE_CLASS done on ClassRule/Extension '" + id(getClass())
                + "', JMS and MATS.");
    }

    /**
     * Tear down method, stopping all {@link MatsFactory} created during a test setup and close the AMQ broker.
     * <p>
     * This method should be called as a result of the following life cycle events for either JUnit or Jupiter:
     * <ul>
     * <li>AfterClass - JUnit - static ClassRule</li>
     * <li>AfterAllCallback - Jupiter - static Extension</li>
     * </ul>
     */
    public void afterAll() {
        log.info("+++ JUnit/Jupiter +++ AFTER_CLASS on ClassRule/Extension '" + id(getClass()) + "':");

        // :: Close the MatsFuturizer if we've made it
        if (_matsFuturizer != null) {
            _matsFuturizer.close();
        }

        // :: Close all MatsFactories (thereby closing all endpoints and initiators, and thus their connections).
        for (MatsFactory createdMatsFactory : _createdMatsFactories) {
            createdMatsFactory.stop(30_000);
        }

        // :: Close the Broker Connection
        _matsTestBroker.close();

        // :: If the DataSource is a TestH2DataSource, then close that
        if (_dataSource instanceof TestH2DataSource) {
            ((TestH2DataSource) _dataSource).close();
        }

        // :: Clean up all possibly created "sub pieces" of this. The reason is that if this is put as a @ClassRule
        // on a super class of multiple tests, then this instance will be re-used upon the next test class's run
        // (the next class that extends the "base test class" that contains the static @ClassRule).
        // NOTE: NOT doing that for H2 DataSource, as we cannot recreate that here, and instead rely on it re-starting.
        _matsTestBroker = null;
        _matsFactory = null;
        _matsInitiator = null;
        _matsTestLatch = null;
        _matsFuturizer = null;
        _matsTestBrokerInterface = null;

        log.info("--- JUnit/Jupiter --- AFTER_CLASS done on ClassRule/Extension '" + id(getClass()) + "' DONE.");
    }

    /**
     * @return the default {@link MatsInitiator} from this rule's {@link MatsFactory}.
     */
    public synchronized MatsInitiator getMatsInitiator() {
        if (_matsInitiator == null) {
            _matsInitiator = getMatsFactory().getDefaultInitiator();
        }
        return _matsInitiator;
    }

    /**
     * @return a singleton {@link MatsTestLatch}
     */
    public synchronized MatsTestLatch getMatsTestLatch() {
        if (_matsTestLatch == null) {
            _matsTestLatch = new MatsTestLatch();
        }
        return _matsTestLatch;
    }

    /**
     * @return a convenience singleton {@link MatsFuturizer} created with this rule's {@link MatsFactory}.
     */
    public synchronized MatsFuturizer getMatsFuturizer() {
        if (_matsFuturizer == null) {
            _matsFuturizer = MatsFuturizer.createMatsFuturizer(_matsFactory, "UnitTestingFuturizer");
        }
        return _matsFuturizer;
    }

    /**
     * @return a {@link MatsTestBrokerInterface} instance for getting DLQs (and hopefully other snacks at a later time).
     */
    public synchronized MatsTestBrokerInterface getMatsTestBrokerInterface() {
        if (_matsTestBrokerInterface == null) {
            _matsTestBrokerInterface = MatsTestBrokerInterface
                    .create(_matsTestBroker.getConnectionFactory(), _matsFactory);

        }
        return _matsTestBrokerInterface;
    }

    /**
     * @return the JMS ConnectionFactory that this JUnit Rule sets up.
     */
    public ConnectionFactory getJmsConnectionFactory() {
        return _matsTestBroker.getConnectionFactory();
    }

    /**
     * @return the {@link MatsFactory} that this JUnit Rule sets up.
     */
    public MatsFactory getMatsFactory() {
        return _matsFactory;
    }

    /**
     * <b>You should probably NOT use this method, but instead the {@link #getMatsFactory()}!</b>.
     * <p />
     * This method is public for a single reason: If you need a <i>new, separate</i> {@link MatsFactory} using the same
     * JMS ConnectionFactory as the one provided by {@link #getMatsFactory()}. The only currently known reason for this
     * is if you want to register two endpoints with the same endpointId, and the only reason for this again is to test
     * {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda)
     * subscriptionTerminators}.
     *
     * @return a <i>new, separate</i> {@link MatsFactory} in addition to the one provided by {@link #getMatsFactory()}.
     */
    public MatsFactory createMatsFactory() {
        MatsFactory matsFactory = MatsTestFactory.create(_matsTestBroker, _dataSource, _matsSerializer);

        // Add it to the list of created MatsFactories.
        _createdMatsFactories.add(matsFactory);
        return matsFactory;
    }

    /**
     * @return the DataSource if this Rule/Extension was created with one, throws {@link IllegalStateException}
     *         otherwise.
     * @throws IllegalStateException
     *             if this Rule/Extension wasn't created with a DataSource.
     */
    public DataSource getDataSource() {
        if (_dataSource == null) {
            throw new IllegalStateException("This " + this.getClass().getSimpleName()
                    + " was not created with a DataSource, use the 'createWithDb' factory methods.");
        }
        return _dataSource;
    }

    /**
     * Loops through all the {@link MatsFactory}s contained in this Rule (default + any specifically created), and
     * removes all Endpoints and unknown Plugins from each of them, this ensures that all factories are "clean".
     * <p />
     * You may want to utilize this if you have multiple tests in a class, and set up the Endpoints using a @Before type
     * annotation in the test, as opposed to @BeforeClass. This because otherwise you will on the second test try to
     * create the endpoints one more time, and they will already exist, thus you'll get an Exception from the
     * MatsFactory. Another scenario is that you have a bunch of @Test methods, which inside the test sets up an
     * endpoint in the "Arrange" section. If you employ the same endpointId for each of those setups (that is, inside
     * the @Test method itself), you will get "duplicate endpoint" (which is good, as your test would probably randomly
     * fail anyhow). Thus, as the first statement of each test, before creating the endpoint, invoke this method.
     */
    public void cleanMatsFactories() {
        // :: Since removing all endpoints will destroy the MatsFuturizer if it is made, we'll first close that
        synchronized (this) {
            // ?: Have we made the MatsFuturizer?
            if (_matsFuturizer != null) {
                // -> Yes, so close and null it.
                _matsFuturizer.close();
                _matsFuturizer = null;
            }
        }
        // :: Loop through all created MATS factories and remove the endpoints and unknown plugins
        for (MatsFactory createdMatsFactory : _createdMatsFactories) {
            // .. Removing Endpoints
            createdMatsFactory.getEndpoints()
                    .forEach(matsEndpoint -> matsEndpoint.remove(30_000));
            // .. Removing unknown Plugins
            for (MatsPlugin plugin : createdMatsFactory.getFactoryConfig().getPlugins(MatsPlugin.class)) {
                // ?: Is this the standard MatsMetricsLoggingInterceptor?
                if (plugin.getClass().getSimpleName().equals("MatsMetricsLoggingInterceptor")) {
                    // -> Yes, so don't remove it.
                    continue;
                }
                // ?: Is this the standard MatsMicrometerInterceptor?
                if (plugin.getClass().getSimpleName().equals("MatsMicrometerInterceptor")) {
                    // -> Yes, so don't remove it.
                    continue;
                }
                // E-> No, none of the standard, so remove it.
                createdMatsFactory.getFactoryConfig().removePlugin(plugin);
            }
        }
    }

    protected String id(Class<?> clazz) {
        return clazz.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this));
    }
}
