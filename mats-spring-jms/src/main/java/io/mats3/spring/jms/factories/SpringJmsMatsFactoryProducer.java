package io.mats3.spring.jms.factories;

import java.sql.Connection;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.impl.jms.JmsMatsTransactionManager;
import io.mats3.impl.jms.JmsMatsTransactionManager_Jms;
import io.mats3.serial.MatsSerializer;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;

/**
 * Provides an easy way to get hold of the most probable {@link JmsMatsFactory} transaction manager configuration in the
 * Spring world (using {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx}, or only the
 * {@link JmsMatsTransactionManager_Jms} if no DataSource is needed). You may either supply a {@link DataSource}, or a
 * Spring {@link PlatformTransactionManager} (typically <code>DataSourceTransactionManager</code> or
 * <code>HibernateTransactionManager</code>). The code here is very simple, really just creating the {@link MatsFactory}
 * normally and then wrapping it up in a {@link SpringJmsMatsFactoryWrapper} which provides hook-in to the Spring
 * context. Read more about the features on its {@link SpringJmsMatsFactoryWrapper JavaDoc}.
 * <p />
 * <b>NOTE: It returns an instance of {@link SpringJmsMatsFactoryWrapper}, which it is assumed that you put in the
 * Spring context as a bean, so that Spring property injection and <code>@PostConstruct</code> is run on it.</b> If you
 * instead employ a FactoryBean <i>(e.g. because you have made a cool Mats single-annotation-configuration solution
 * for your multiple codebases)</i>, then you need to invoke
 * {@link SpringJmsMatsFactoryWrapper#postConstructForFactoryBean(Environment, ApplicationContext)} - read up!
 *
 * @see SpringJmsMatsFactoryWrapper
 * @see SpringJmsMatsFactoryWrapper#postConstructForFactoryBean(Environment, ApplicationContext)
 *
 * @author Endre Stølsvik 2019-06-10 02:45 - http://stolsvik.com/, endre@stolsvik.com
 */
public class SpringJmsMatsFactoryProducer {
    private static final Logger log = LoggerFactory.getLogger(SpringJmsMatsFactoryProducer.class);
    private static final String LOG_PREFIX = "#SPRINGJMATS# ";

    /**
     * If you need a {@link MatsFactory} employing Spring's DataSourceTransactionManager (which you probably do in a
     * Spring environment utilizing SQL), this is your factory method.
     * <p />
     * Usage: Make a @Bean-annotated method which returns the result of this method, or employ a Spring
     * <code>FactoryBean</code> where you then need to invoke
     * {@link SpringJmsMatsFactoryWrapper#postConstructForFactoryBean(Environment, ApplicationContext)}.
     *
     * @param appName
     *            the containing application's name (for debugging purposes, you'll find it in the trace).
     * @param appVersion
     *            the containing application's version (for debugging purposes, you'll find it in the trace).
     * @param matsSerializer
     *            the {@link JmsMatsFactory} utilizes the {@link MatsSerializer}, so you need to provide one. (It is
     *            probably the one from the 'mats-serial-json' package).
     * @param jmsConnectionFactory
     *            the JMS {@link ConnectionFactory} to fetch JMS Connections from, using
     *            {@link ConnectionFactory#createConnection()}. It is assumed that if username and password is needed,
     *            you have configured that on the ConnectionFactory.
     * @param sqlDataSource
     *            the SQL DataSource which to stash into a Spring {@link DataSourceTransactionManager}, and from which
     *            SQL {@link Connection}s are fetched from, using {@link DataSource#getConnection()}. It is assumed that
     *            if username and password is needed, you have configured that on the DataSource.
     * @return the produced {@link SpringJmsMatsFactoryWrapper}
     */
    public static SpringJmsMatsFactoryWrapper createSpringDataSourceTxMatsFactory(String appName, String appVersion,
            MatsSerializer<?> matsSerializer, ConnectionFactory jmsConnectionFactory, DataSource sqlDataSource) {
        // :: Create the JMS and Spring DataSourceTransactionManager-backed JMS MatsFactory.
        log.info(LOG_PREFIX + "createSpringDataSourceTxMatsFactory(" + appName + ", " + appVersion + ", "
                + matsSerializer + ", " + jmsConnectionFactory + ", " + sqlDataSource + ")");
        // JMS + Spring's DataSourceTransactionManager-based MatsTransactionManager
        JmsMatsTransactionManager springSqlTxMgr = JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.create(
                sqlDataSource);

        return createJmsMatsFactory(appName, appVersion, matsSerializer, jmsConnectionFactory,
                springSqlTxMgr);
    }

    /**
     * If you need a {@link MatsFactory} employing a {@link PlatformTransactionManager} of your choosing, which you
     * quite possibly want in a Spring environment using e.g. Spring JDBC or Hibernate, this is your factory method.
     * <p />
     * Usage: Make a @Bean-annotated method which returns the result of this method, or employ a Spring
     * <code>FactoryBean</code> where you then need to invoke
     * {@link SpringJmsMatsFactoryWrapper#postConstructForFactoryBean(Environment, ApplicationContext)}.
     *
     * @param appName
     *            the containing application's name (for debugging purposes, you'll find it in the trace).
     * @param appVersion
     *            the containing application's version (for debugging purposes, you'll find it in the trace).
     * @param matsSerializer
     *            the {@link JmsMatsFactory} utilizes the {@link MatsSerializer}, so you need to provide one. (It is
     *            probably the one from the 'mats-serial-json' package).
     * @param jmsConnectionFactory
     *            the JMS {@link ConnectionFactory} to fetch JMS Connections from, using
     *            {@link ConnectionFactory#createConnection()}. It is assumed that if username and password is needed,
     *            you have configured that on the ConnectionFactory.
     * @param platformTransactionManager
     *            the {@link PlatformTransactionManager} (typically <code>DataSourceTransactionManager</code> or
     *            <code>HibernateTransactionManager)</code> that the MatsFactory should employ.
     * @return the produced {@link SpringJmsMatsFactoryWrapper}
     */
    public static SpringJmsMatsFactoryWrapper createSpringPlatformTransactionManagerTxMatsFactory(
            String appName, String appVersion, MatsSerializer<?> matsSerializer, ConnectionFactory jmsConnectionFactory,
            PlatformTransactionManager platformTransactionManager) {
        // :: Create the JMS and Spring PlatformTransactionManager-backed JMS MatsFactory.
        log.info(LOG_PREFIX + "createSpringPlatformTransactionManagerTxMatsFactory(" + appName + ", " + appVersion
                + ", " + matsSerializer + ", " + jmsConnectionFactory + ", " + platformTransactionManager + ")");
        // JMS + Spring's PlatformTransactionManager-based MatsTransactionManager
        JmsMatsTransactionManager springSqlTxMgr = JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.create(
                platformTransactionManager);

        return createJmsMatsFactory(appName, appVersion, matsSerializer, jmsConnectionFactory,
                springSqlTxMgr);
    }

    /**
     * TODO: Deprecated: Wrongly named method. Remove when > v0.16.0.
     *
     * @deprecated use
     *             {@link #createSpringPlatformTransactionManagerTxMatsFactory(String, String, MatsSerializer, ConnectionFactory, PlatformTransactionManager)}
     */
    @Deprecated
    public static SpringJmsMatsFactoryWrapper createSpringDataSourceTxMatsFactory(String appName, String appVersion,
            MatsSerializer<?> matsSerializer, ConnectionFactory jmsConnectionFactory,
            PlatformTransactionManager platformTransactionManager) {
        return createSpringPlatformTransactionManagerTxMatsFactory(appName, appVersion, matsSerializer,
                jmsConnectionFactory, platformTransactionManager);
    }

    /**
     * If you need a {@link MatsFactory} that only handles the JMS transactions, this is your factory method - but if
     * you DO make any database calls within any Mats endpoint lambda, you will now have no or poor transactional
     * demarcation, use
     * {@link #createSpringDataSourceTxMatsFactory(String, String, MatsSerializer, ConnectionFactory, DataSource)
     * createSpringDataSourceTxMatsFactory(..)} instead.
     * <p />
     * Usage: Make a @Bean-annotated method which returns the result of this method, or employ a Spring
     * <code>FactoryBean</code> where you then need to invoke
     * {@link SpringJmsMatsFactoryWrapper#postConstructForFactoryBean(Environment, ApplicationContext)}.
     *
     * @param appName
     *            the containing application's name (for debugging purposes, you'll find it in the trace).
     * @param appVersion
     *            the containing application's version (for debugging purposes, you'll find it in the trace).
     * @param matsSerializer
     *            the {@link JmsMatsFactory} utilizes the {@link MatsSerializer}, so you need to provide one. (It is
     *            probably the one from the 'mats-serial-json' package).
     * @param jmsConnectionFactory
     *            the JMS {@link ConnectionFactory} to fetch JMS Connections from, using
     *            {@link ConnectionFactory#createConnection()}. It is assumed that if username and password is needed,
     *            you have configured that on the ConnectionFactory.
     * @return the produced {@link SpringJmsMatsFactoryWrapper}
     */
    public static SpringJmsMatsFactoryWrapper createJmsTxOnlyMatsFactory(String appName, String appVersion,
            MatsSerializer<?> matsSerializer, ConnectionFactory jmsConnectionFactory) {
        // :: Create the JMS and Spring DataSourceTransactionManager-backed JMS MatsFactory.
        log.info(LOG_PREFIX + "createJmsTxOnlyMatsFactory(" + appName + ", " + appVersion + ", " + matsSerializer + ", "
                + jmsConnectionFactory + ")");
        // JMS only MatsTransactionManager
        JmsMatsTransactionManager jmsOnlyTxMgr = JmsMatsTransactionManager_Jms.create();

        return createJmsMatsFactory(appName, appVersion, matsSerializer, jmsConnectionFactory,
                jmsOnlyTxMgr);
    }

    private static SpringJmsMatsFactoryWrapper createJmsMatsFactory(String appName, String appVersion,
            MatsSerializer<?> matsSerializer, ConnectionFactory jmsConnectionFactory, JmsMatsTransactionManager txMgr) {
        // JmsSessionHandler (pooler)
        JmsMatsJmsSessionHandler jmsSessionHandler = JmsMatsJmsSessionHandler_Pooling.create(jmsConnectionFactory);
        // The MatsFactory itself, supplying the JmsSessionHandler and MatsTransactionManager.
        JmsMatsFactory<?> matsFactory = JmsMatsFactory
                .createMatsFactory(appName, appVersion, jmsSessionHandler, txMgr, matsSerializer);

        return new SpringJmsMatsFactoryWrapper(jmsConnectionFactory, matsFactory);
    }
}
