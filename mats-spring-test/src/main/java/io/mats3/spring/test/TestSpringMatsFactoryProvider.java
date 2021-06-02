package io.mats3.spring.test;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

import io.mats3.MatsFactory;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsFactory.MatsFactoryWrapper;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.impl.jms.JmsMatsTransactionManager;
import io.mats3.impl.jms.JmsMatsTransactionManager_Jms;
import io.mats3.localinspect.LocalStatsMatsInterceptor;
import io.mats3.serial.MatsSerializer;
import io.mats3.spring.jms.factories.SpringJmsMatsFactoryWrapper;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;
import io.mats3.util_activemq.MatsLocalVmActiveMq;

/**
 * A testing-oriented {@link MatsFactory}-provider which also create a separate LocalVM ActiveMQ broker for the produced
 * MatsFactory to connect to - this is for the scenarios where you do <i>NOT</i> have your test load the entire
 * application's Spring configuration, but instead "piece together" the relevant Spring
 * <code>{@literal @Components}</code> containing test-relevant Mats endpoints and other beans from your application
 * along with test-specific mocked-out endpoints: You will then probably not have the MatsFactory present in the Spring
 * context. You can then use this class to get a "quick and dirty" MatsFactory (with an in-vm MQ Broker backing it) on
 * which your application's endpoints, and mocked-out endpoints in the test, can run.
 * <p />
 * <i>If your requirements aren't all that exotic, you may get this indirectly invoked by using the
 * {@literal @}{@link MatsTestContext} annotation directly on the Test-class. If your requirements are slightly more
 * involved, check out the {@link MatsTestInfrastructureConfiguration} and {@link MatsTestInfrastructureDbConfiguration}
 * Spring Configuration classes (loaded in a test with {@literal @}{@link ContextConfiguration}), which employs the
 * methods in this class.</i>
 *
 * @see MatsTestContext
 * @see MatsTestInfrastructureConfiguration
 * @see MatsTestInfrastructureDbConfiguration
 * @author Endre Stølsvik 2019-06-17 21:26 - http://stolsvik.com/, endre@stolsvik.com
 */
public class TestSpringMatsFactoryProvider {

    private static final Logger log = LoggerFactory.getLogger(TestSpringMatsFactoryProvider.class);

    private static final String LOG_PREFIX = "#SPRINGJMATS(test)# ";

    private static final AtomicInteger _sequence = new AtomicInteger(0);

    /**
     * If you need a {@link MatsFactory} employing Spring's DataSourceTransactionManager (which you probably do in a
     * Spring environment utilizing SQL), this is your factory method. If you need to make y
     * <p />
     * Usage: In the test, make a @Bean-annotated method which returns the result of this method - or employ the
     * convenience {@link MatsTestInfrastructureDbConfiguration}.
     *
     * @param concurrency
     *            The concurrency of the created {@link MatsFactory}, set with
     *            {@link FactoryConfig#setConcurrency(int)}.
     * @param sqlDataSource
     *            the SQL DataSource which to stash into a Spring {@link DataSourceTransactionManager}, and from which
     *            SQL {@link Connection}s are fetched from, using {@link DataSource#getConnection()}. It is assumed that
     *            if username and password is needed, you have configured that on the DataSource.
     * @param matsSerializer
     *            the MatsSerializer to use. If you employ the standard, use <code>MatsSerializerJson.create()</code>.
     *
     * @return the produced {@link MatsFactory}
     */
    public static MatsFactory createSpringDataSourceTxTestMatsFactory(int concurrency, DataSource sqlDataSource,
            MatsSerializer<?> matsSerializer) {
        // JMS + Spring's DataSourceTransactionManager-based MatsTransactionManager
        JmsMatsTransactionManager springSqlTxMgr = JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.create(
                sqlDataSource);
        return getMatsFactoryStopLocalVmBrokerWrapper(concurrency, matsSerializer, springSqlTxMgr);
    }

    /**
     * Convenience variant of {@link #createSpringDataSourceTxTestMatsFactory(int, DataSource, MatsSerializer)} where
     * concurrency is 1, which should be adequate for most testing - unless you explicitly want to test concurrency.
     *
     * @param sqlDataSource
     *            the SQL DataSource which to stash into a Spring {@link DataSourceTransactionManager}, and from which
     *            SQL {@link Connection}s are fetched from, using {@link DataSource#getConnection()}. It is assumed that
     *            if username and password is needed, you have configured that on the DataSource.
     * @param matsSerializer
     *            the MatsSerializer to use. If you employ the standard, use <code>MatsSerializerJson.create()</code>.
     *
     * @return the produced {@link MatsFactory}
     */
    public static MatsFactory createSpringDataSourceTxTestMatsFactory(DataSource sqlDataSource,
            MatsSerializer<?> matsSerializer) {
        return createSpringDataSourceTxTestMatsFactory(1, sqlDataSource, matsSerializer);
    }

    /**
     * If you need a {@link MatsFactory}, but you need to make your own {@link PlatformTransactionManager}, this is your
     * factory method - <b>please note that the contained {@link DataSource} should have been wrapped using the static
     * method {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx#wrapLazyConnectionDatasource(DataSource)}</b>.
     * <p />
     * Usage: In the test, make a @Bean-annotated method which returns the result of this method - or employ the
     * convenience {@link MatsTestInfrastructureDbConfiguration}.
     *
     * @param concurrency
     *            The concurrency of the created {@link MatsFactory}, set with
     *            {@link FactoryConfig#setConcurrency(int)}.
     * @param platformTransactionManager
     *            the {@link PlatformTransactionManager} that the SpringJmsMats transaction manager should employ. From
     *            this, the {@link DataSource} is gotten using refelction to find a method 'getDataSource()'. <b>please
     *            note that the contained {@link DataSource} should have been wrapped using the static * method
     *            {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx#wrapLazyConnectionDatasource(DataSource)}</b>.
     *            It is assumed that if username and password is needed, you have configured that on the DataSource.
     * @param matsSerializer
     *            the MatsSerializer to use. If you employ the standard, use <code>MatsSerializerJson.create()</code>.
     *
     * @return the produced {@link MatsFactory}
     */
    public static MatsFactory createSpringDataSourceTxTestMatsFactory(int concurrency,
            PlatformTransactionManager platformTransactionManager, MatsSerializer<?> matsSerializer) {
        // JMS + Spring's DataSourceTransactionManager-based MatsTransactionManager
        JmsMatsTransactionManager springSqlTxMgr = JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.create(
                platformTransactionManager);
        return getMatsFactoryStopLocalVmBrokerWrapper(concurrency, matsSerializer, springSqlTxMgr);
    }

    /**
     * Convenience variant of
     * {@link #createSpringDataSourceTxTestMatsFactory(int, PlatformTransactionManager, MatsSerializer)} where
     * concurrency is 1, which should be adequate for most testing - unless you explicitly want to test concurrency.
     *
     * @param platformTransactionManager
     *            the {@link PlatformTransactionManager} that the SpringJmsMats transaction manager should employ. From
     *            this, the {@link DataSource} is gotten using reflection to find a method 'getDataSource()'. <b>please
     *            note that the contained {@link DataSource} should have been wrapped using the static method
     *            {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx#wrapLazyConnectionDatasource(DataSource)}</b>.
     *            It is assumed that if username and password is needed, you have configured that on the DataSource.
     * @param matsSerializer
     *            the MatsSerializer to use. If you employ the standard, use <code>MatsSerializerJson.create()</code>.
     *
     * @return the produced {@link MatsFactory}
     */
    public static MatsFactory createSpringDataSourceTxTestMatsFactory(
            PlatformTransactionManager platformTransactionManager, MatsSerializer<?> matsSerializer) {
        return createSpringDataSourceTxTestMatsFactory(1, platformTransactionManager, matsSerializer);
    }

    /**
     * If you need a {@link MatsFactory} that only handles the JMS transactions, this is your factory method - but if
     * you DO make any database calls within any Mats endpoint lambda, you will now have no or poor transactional
     * demarcation, use {@link #createSpringDataSourceTxTestMatsFactory(int, DataSource, MatsSerializer)
     * createSpringDataSourceTxTestMatsFactory(..)} instead.
     * <p />
     * Usage: In the test, make a @Bean-annotated method which returns the result of this method - or employ the
     * convenience {@link MatsTestInfrastructureConfiguration}.
     *
     * @param concurrency
     *            The concurrency of the created {@link MatsFactory}, set with
     *            {@link FactoryConfig#setConcurrency(int)}.
     * @param matsSerializer
     *            the MatsSerializer to use. If you employ the standard, use <code>MatsSerializerJson.create()</code>.
     *
     * @return the produced {@link MatsFactory}
     */
    public static MatsFactory createJmsTxOnlyTestMatsFactory(int concurrency,
            MatsSerializer<?> matsSerializer) {
        // JMS only MatsTransactionManager
        JmsMatsTransactionManager jmsOnlyTxMgr = JmsMatsTransactionManager_Jms.create();

        return getMatsFactoryStopLocalVmBrokerWrapper(concurrency, matsSerializer, jmsOnlyTxMgr);
    }

    /**
     * Convenience variant of {@link #createJmsTxOnlyTestMatsFactory(int, MatsSerializer)} where concurrency is 1, which
     * should be adequate for most testing - unless you explicitly want to test concurrency.
     *
     * @param matsSerializer
     *            the MatsSerializer to use. If you employ the standard, use <code>MatsSerializerJson.create()</code>.
     *
     * @return the produced {@link MatsFactory}
     */
    public static MatsFactory createJmsTxOnlyTestMatsFactory(MatsSerializer<?> matsSerializer) {
        return createJmsTxOnlyTestMatsFactory(1, matsSerializer);
    }

    private static SpringJmsMatsFactoryWrapper getMatsFactoryStopLocalVmBrokerWrapper(int concurrency,
            MatsSerializer<?> matsSerializer, JmsMatsTransactionManager springSqlTxMgr) {
        // Naming broker as calling class, performing replacement of illegal chars according to ActiveMQ rules.
        String appName = getAppNamePrefix().replaceAll("[^a-zA-Z0-9._\\-:]", ".")
                + "_" + _sequence.getAndIncrement();
        MatsLocalVmActiveMq inVmActiveMq = MatsLocalVmActiveMq.createInVmActiveMq(appName);
        ConnectionFactory jmsConnectionFactory = inVmActiveMq.getConnectionFactory();
        // :: Create the JMS and Spring DataSourceTransactionManager-backed JMS MatsFactory.
        // JmsSessionHandler (pooler)
        JmsMatsJmsSessionHandler jmsSessionHandler = JmsMatsJmsSessionHandler_Pooling.create(jmsConnectionFactory);
        // The MatsFactory itself, supplying the JmsSessionHandler and MatsTransactionManager.
        JmsMatsFactory<?> matsFactory = JmsMatsFactory
                .createMatsFactory(appName, "#testing#", jmsSessionHandler, springSqlTxMgr, matsSerializer);
        // Set concurrency.
        matsFactory.getFactoryConfig().setConcurrency(concurrency);

        // TEMPORARY? Install the LocalStatsMatsInterceptor, to get it tested.
        LocalStatsMatsInterceptor.install(matsFactory);

        // Now wrap this in a wrapper that will close the LocalVM ActiveMQ broker upon matsFactory.stop().
        MatsFactoryStopLocalVmBrokerWrapper matsFactoryStopLocalVmBrokerWrapper = new MatsFactoryStopLocalVmBrokerWrapper(
                matsFactory, inVmActiveMq);
        // And then finally wrap this in the Spring wrapper
        return new SpringJmsMatsFactoryWrapper(inVmActiveMq.getConnectionFactory(),
                matsFactoryStopLocalVmBrokerWrapper);
    }

    private static class MatsFactoryStopLocalVmBrokerWrapper extends MatsFactoryWrapper {
        private final MatsLocalVmActiveMq _matsLocalVmActiveMq;

        public MatsFactoryStopLocalVmBrokerWrapper(JmsMatsFactory<?> targetMatsFactory,
                MatsLocalVmActiveMq matsLocalVmActiveMq) {
            super(targetMatsFactory);
            _matsLocalVmActiveMq = matsLocalVmActiveMq;
        }

        @Override
        public boolean stop(int gracefulShutdownMillis) {
            log.info(LOG_PREFIX + "Stopping test JmsMatsFactory.");
            boolean stopped = super.stop(gracefulShutdownMillis);
            log.info(LOG_PREFIX + "Stopping test ActiveMQ instance.");
            _matsLocalVmActiveMq.close();
            return stopped;
        }
    }

    /**
     * from <a href="https://stackoverflow.com/a/11306854">Stackoverflow - Denys Séguret</a>.
     */
    private static String getAppNamePrefix() {
        StackTraceElement[] stElements = Thread.currentThread().getStackTrace();
        for (int i = 1; i < stElements.length; i++) {
            StackTraceElement ste = stElements[i];
            if (!ste.getClassName().equals(TestSpringMatsFactoryProvider.class.getName())
                    && !ste.getClassName().startsWith(MatsTestInfrastructureConfiguration.class.getName())
                    && !ste.getClassName().startsWith(MatsTestInfrastructureDbConfiguration.class.getName())
                    && !ste.getClassName().startsWith("org.springframework.")
                    && !ste.getClassName().startsWith("sun.")
                    && !ste.getClassName().startsWith("java.")
                    && !ste.getClassName().startsWith("org.junit.")
                    && !ste.getClassName().startsWith("com.intellij.")) {
                return ste.getClassName();
            }
        }
        // E-> Could not determine a reasonable suggestion for "app name" based on stack frames.
        return "Testing_" + TestSpringMatsFactoryProvider.class.getSimpleName();
    }
}
