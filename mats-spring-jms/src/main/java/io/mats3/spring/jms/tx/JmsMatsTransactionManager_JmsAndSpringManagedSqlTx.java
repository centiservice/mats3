package io.mats3.spring.jms.tx;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.InfrastructureProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory.ContextLocal;
import io.mats3.impl.jms.JmsMatsContextLocalCallback;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException;
import io.mats3.impl.jms.JmsMatsInternalExecutionContext;
import io.mats3.impl.jms.JmsMatsTransactionManager;
import io.mats3.impl.jms.JmsMatsTransactionManager_Jms;
import io.mats3.util.wrappers.DeferredConnectionProxyDataSourceWrapper;
import io.mats3.util.wrappers.DeferredConnectionProxyDataSourceWrapper.DeferredConnectionProxy;

/**
 * Implementation of {@link JmsMatsTransactionManager} that in addition to the JMS transaction keeps a Spring
 * {@link PlatformTransactionManager} employing a JDBC DataSource for which it keeps transaction demarcation along with
 * the JMS transaction, by means of <i>"Best Effort 1 Phase Commit"</i>. Note that you can choose between providing a
 * DataSource, in which case this JmsMatsTransactionManager internally creates a {@link DataSourceTransactionManager},
 * or you can provide a <code>PlatformTransactionManager</code> employing a <code>DataSource</code> that you've created
 * and employ externally (i.e. <code>DataSourceTransactionManager</code>, <code>JpaTransactionManager</code> or
 * <code>HibernateTransactionManager</code>). In the case where you provide a <code>PlatformTransactionManager</code>,
 * you should <i>definitely</i> wrap the DataSource employed when creating it by the method
 * {@link #wrapLazyConnectionDatasource(DataSource)}, so that you get lazy Connection fetching and so that Mats can know
 * whether the SQL Connection was actually employed within a Mats Stage - this also elides the committing of an empty DB
 * transaction if a Mats Stage does not actually employ a SQL Connection. Note that whether you use the wrapped
 * DataSource or the non-wrapped DataSource when creating e.g. {@link JdbcTemplate}s does not matter, as Spring's
 * {@link DataSourceUtils} and {@link TransactionSynchronizationManager} has an unwrapping strategy when retrieving the
 * transactionally demarcated Connection.
 * <p />
 * Explanation of <i>Best Effort 1 Phase Commit</i>:
 * <ol>
 * <li><b>JMS transaction is entered</b> (a transactional JMS Connection is always within a transaction)
 * <li>JMS Message is retrieved.
 * <li><b>SQL transaction is entered</b>
 * <li>Code is executed, <i>including SQL statements and production of new "outgoing" JMS Messages.</i>
 * <li><b>SQL transaction is committed - <font color="red">Any errors also rollbacks the JMS Transaction, so that none
 * of them have happened.</font></b>
 * <li><b>JMS transaction is committed.</b>
 * </ol>
 * Out of that order, one can see that if SQL transaction becomes committed, and then the JMS transaction fails, this
 * will be a pretty bad situation. However, of those two transactions, the SQL transaction is absolutely most likely to
 * fail, as this is where you can have business logic failures, concurrency problems (e.g. MS SQL's "Deadlock Victim"),
 * integrity constraints failing etc - that is, failures in both logic and timing. On the other hand, the JMS
 * transaction (which effectively boils down to <i>"yes, I received this message, and sent these"</i>) is much harder to
 * fail, where the only situation where it can fail is due to infrastructure/hardware failures (exploding server / full
 * disk on Message Broker). This is called "Best Effort 1PC", and is nicely explained in <a href=
 * "http://www.javaworld.com/article/2077963/open-source-tools/distributed-transactions-in-spring--with-and-without-xa.html?page=2">
 * this article</a>. If this failure occurs, it will be caught and logged on ERROR level (by
 * {@link JmsMatsTransactionManager_Jms}) - and then the Message Broker will probably try to redeliver the message. Also
 * read the <a href="http://activemq.apache.org/should-i-use-xa.html">Should I use XA Transactions</a> from Apache
 * Active MQ.
 * <p />
 * Wise tip when working with <i>Message Oriented Middleware</i>: Code idempotent! Handle double-deliveries!
 * <p />
 * The transactionally demarcated SQL Connection can be retrieved from within Mats Stage lambda code user code using
 * {@link ProcessContext#getAttribute(Class, String...) ProcessContext.getAttribute(Connection.class)} - which also is
 * available using {@link ContextLocal#getAttribute(Class, String...) ContextLocal.getAttribute(Connection.class)}.
 * <b>Notice:</b> In a Spring context, you can also get the transactionally demarcated thread-bound Connection via
 * {@link DataSourceUtils#getConnection(DataSource) DataSourceUtils.getConnection(dataSource)} - this is indeed what
 * Spring's JDBC Template and friends are doing. <b>If you go directly to the DataSource, you will get a new Connection
 * not participating in the transaction!</b> This "feature" might sometimes be of interest if you want something to be
 * performed regardless of whether the stage processing fails or not. <i>(However, if you do such a thing, you must
 * remember the built-in retry mechanism JMS Message Brokers has: If something fails, whatever database changes you
 * performed successfully with such a non-tx-managed Connection will not participate in the rollback, and will already
 * have been performed when the message is retried. This might, or might not, be what you want.).</i>
 *
 * @author Endre Stølsvik 2019-05-09 20:27 - http://stolsvik.com/, endre@stolsvik.com
 */
public class JmsMatsTransactionManager_JmsAndSpringManagedSqlTx extends JmsMatsTransactionManager_Jms {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.class);

    private final PlatformTransactionManager _platformTransactionManager;
    private final DataSource _dataSource; // Hopefully wrapped.
    private final Function<JmsMatsTxContextKey, DefaultTransactionDefinition> _transactionDefinitionFunction;

    private final static String LOG_PREFIX = "#SPRINGJMATS# ";

    /**
     * A <code>{@literal Supplier<Boolean>}</code> bound to {@link ContextLocal} when inside a Mats-transactional
     * demarcation.
     */
    public static final String CONTEXT_LOCAL_KEY_CONNECTION_EMPLOYED_STATE_SUPPLIER = "JmsAndSpringManagedSqlTx.connectionEmployedSupplier";

    private JmsMatsTransactionManager_JmsAndSpringManagedSqlTx(
            PlatformTransactionManager platformTransactionManager, DataSource dataSource,
            Function<JmsMatsTxContextKey, DefaultTransactionDefinition> transactionDefinitionFunction) {
        // Store the PlatformTransactionManager we got
        _platformTransactionManager = platformTransactionManager;
        // Store the DataSource
        _dataSource = dataSource;
        if (!(dataSource instanceof DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy)) {
            log.warn(LOG_PREFIX + "The DataSource provided with the PlatformTransactionManager is not wrapped with our"
                    + " special '" + DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy.class.getSimpleName()
                    + "', which will hinder the SpringJmsMats implementation from knowing whether the SQL Connection"
                    + " was actually employed by the MatsFactory's Endpoints' Stages. You can do this wrapping"
                    + " (before creating the PlatformTransactionManager) by invoking the static method"
                    + " 'wrapLazyConnectionDatasource(dataSource)'. PlatformTransactionManager in question: ["
                    + platformTransactionManager + "], of class [" + platformTransactionManager.getClass()
                            .getName() + "]");
        }
        // Use the supplied TransactionDefinition Function - which probably is our own default.
        _transactionDefinitionFunction = transactionDefinitionFunction;
    }

    private JmsMatsTransactionManager_JmsAndSpringManagedSqlTx(DataSource dataSource,
            Function<JmsMatsTxContextKey, DefaultTransactionDefinition> transactionDefinitionFunction) {

        // ?: Is the DataSource already wrapped with our proxy?
        if (dataSource instanceof DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy) {
            log.info(LOG_PREFIX + "The DataSource provided is already wrapped with "
                    + DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy.class.getSimpleName() + ", which"
                    + " is okay (we otherwise wrap it ourselves). [" + dataSource + "]");
            _dataSource = dataSource;
        }
        else {
            // Wrap the DataSource up in the insanity-inducing stack of wrappers.
            _dataSource = wrapLazyConnectionDatasource(dataSource);
        }

        // Make the internal DataSourceTransactionManager, using the wrapped DataSource.
        _platformTransactionManager = new DataSourceTransactionManager(_dataSource);
        log.info(LOG_PREFIX + "Created own DataSourceTransactionManager for the JmsMatsTransactionManager: ["
                + _platformTransactionManager + "] with magic wrapped DataSource [" + _dataSource + "].");
        // Use the supplied TransactionDefinition Function - which probably is our own default.
        _transactionDefinitionFunction = transactionDefinitionFunction;
    }

    /**
     * <b>Simplest, recommended if you do not need the PlatformTransactionManager in your Spring context!</b> - However,
     * if you need the PlatformTransaction manager in the Spring context, then make it externally (typically using a
     * {@literal @Bean} annotated method, <i> and make sure to wrap the contained DataSource first with
     * {@link #wrapLazyConnectionDatasource(DataSource)}</i>), and use the factory method
     * {@link #create(PlatformTransactionManager)} (it will find the DataSource from the PlatformTransactionManager by
     * introspection).
     * <p />
     * Creates an internal {@link DataSourceTransactionManager} for this created JmsMatsTransactionManager, and ensures
     * that the supplied {@link DataSource} is wrapped using the {@link #wrapLazyConnectionDatasource(DataSource)}
     * method. Also with this way to construct the instance, Mats will know whether the stage or initiation actually
     * performed any SQL data access.
     * <p />
     * Uses a default {@link TransactionDefinition} Function, which sets the transaction name, sets Isolation Level to
     * {@link TransactionDefinition#ISOLATION_READ_COMMITTED}, and sets Propagation Behavior to
     * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}.
     *
     * @param dataSource
     *            the DataSource to make a {@link DataSourceTransactionManager} from - which will be wrapped using
     *            {@link #wrapLazyConnectionDatasource(DataSource)} if it not already is.
     * @return a new {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx}.
     */
    public static JmsMatsTransactionManager_JmsAndSpringManagedSqlTx create(DataSource dataSource) {
        log.info(LOG_PREFIX + "create(DataSource) [" + dataSource + "]");
        return new JmsMatsTransactionManager_JmsAndSpringManagedSqlTx(dataSource,
                getStandardTransactionDefinitionFunctionFor(DataSourceTransactionManager.class));
    }

    /**
     * Creates an internal {@link DataSourceTransactionManager} for this created JmsMatsTransactionManager, and ensures
     * that the supplied {@link DataSource} is wrapped using the {@link #wrapLazyConnectionDatasource(DataSource)}
     * method. Also with this way to construct the instance, Mats will know whether the stage or initiation actually
     * performed any SQL data access.
     * <p />
     * Uses the supplied {@link TransactionDefinition} Function to define the transactions - consider
     * {@link #create(DataSource)} if you are OK with the standard.
     *
     * @param dataSource
     *            the DataSource to make a {@link DataSourceTransactionManager} from - which will be wrapped using
     *            {@link #wrapLazyConnectionDatasource(DataSource)} if it not already is.
     * @param transactionDefinitionFunction
     *            a {@link Function} which returns a {@link DefaultTransactionDefinition}, possibly based on the
     *            provided {@link JmsMatsTxContextKey} (e.g. different isolation level for a special endpoint).
     * @return a new {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx}.
     */
    public static JmsMatsTransactionManager_JmsAndSpringManagedSqlTx create(DataSource dataSource,
            Function<JmsMatsTxContextKey, DefaultTransactionDefinition> transactionDefinitionFunction) {
        log.info(LOG_PREFIX + "create(DataSource, transactionDefinitionFunction) [" + dataSource + "], ["
                + transactionDefinitionFunction + "]");
        return new JmsMatsTransactionManager_JmsAndSpringManagedSqlTx(dataSource, transactionDefinitionFunction);
    }

    /**
     * <b>Next simplest, recommended if you also need the PlatformTransactionManager in your Spring context!</b>
     * (otherwise, use the {@link #create(DataSource)} factory method). Creates an instance of this class from a
     * provided {@link PlatformTransactionManager} (of a type which manages a DataSource), where the supplied instance
     * is introspected to find a method <code>getDataSource()</code> from where to get the underlying DataSource. <b>Do
     * note that you should preferably have the {@link DataSource} within the
     * <code>{@link PlatformTransactionManager}</code> wrapped using the
     * {@link #wrapLazyConnectionDatasource(DataSource)} method.</b> If not wrapped as such, Mats will not be able to
     * know whether the stage or initiation actually performed data access.
     * <p />
     * Uses the standard {@link TransactionDefinition} Function, which sets the transaction name, sets Isolation Level
     * to {@link TransactionDefinition#ISOLATION_READ_COMMITTED} (unless HibernateTxMgr), and sets Propagation Behavior
     * to {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} - see
     * {@link #getStandardTransactionDefinitionFunctionFor(Class)}.
     *
     * @param platformTransactionManager
     *            the {@link DataSourceTransactionManager} to use for transaction management.
     * @return a new {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx}.
     */
    public static JmsMatsTransactionManager_JmsAndSpringManagedSqlTx create(
            PlatformTransactionManager platformTransactionManager) {
        log.info(LOG_PREFIX + "create(PlatformTransactionManager) [" + platformTransactionManager + "]");
        log.info(LOG_PREFIX + "Introspecting the supplied PlatformTransactionManager to find a method .getDataSource()"
                + " from where to get the DataSource. [" + platformTransactionManager + "]");

        DataSource dataSource = getDataSourceFromTransactionManager(platformTransactionManager);
        log.info(LOG_PREFIX + ".. found DataSource [" + dataSource + "].");

        return new JmsMatsTransactionManager_JmsAndSpringManagedSqlTx(platformTransactionManager, dataSource,
                getStandardTransactionDefinitionFunctionFor(platformTransactionManager.getClass()));
    }

    /**
     * Creates an instance of this class from a provided {@link PlatformTransactionManager} (of a type which manages a
     * DataSource), where the supplied instance is introspected to find a method <code>getDataSource()</code> from where
     * to get the underlying DataSource. <b>Do note that you should preferably have the {@link DataSource} within the
     * <code>{@link PlatformTransactionManager}</code> wrapped using the
     * {@link #wrapLazyConnectionDatasource(DataSource)} method.</b> If not wrapped as such, Mats will not be able to
     * know whether the stage or initiation actually performed data access.
     * <p />
     * Uses the supplied {@link TransactionDefinition} Function to define the transactions - consider
     * {@link #create(PlatformTransactionManager)} if you are OK with the standard.
     *
     * @param platformTransactionManager
     *            the {@link DataSourceTransactionManager} to use for transaction management.
     * @return a new {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx}.
     */
    public static JmsMatsTransactionManager_JmsAndSpringManagedSqlTx create(
            PlatformTransactionManager platformTransactionManager,
            Function<JmsMatsTxContextKey, DefaultTransactionDefinition> transactionDefinitionFunction) {
        log.info(LOG_PREFIX + "create(PlatformTransactionManager) [" + platformTransactionManager + "]");
        log.info(LOG_PREFIX + "Introspecting the supplied PlatformTransactionManager to find a method .getDataSource()"
                + " from where to get the DataSource. [" + platformTransactionManager + "]");

        DataSource dataSource = getDataSourceFromTransactionManager(platformTransactionManager);
        log.info(LOG_PREFIX + ".. found DataSource [" + dataSource + "].");

        return new JmsMatsTransactionManager_JmsAndSpringManagedSqlTx(platformTransactionManager, dataSource,
                transactionDefinitionFunction);
    }

    /**
     * Creates a {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx} from a provided
     * {@link PlatformTransactionManager} (of a type which manages a DataSource) - Do note that you should preferably
     * have the {@link DataSource} within the <code>{@link PlatformTransactionManager}</code> wrapped using the
     * {@link #wrapLazyConnectionDatasource(DataSource)} method. If not wrapped as such, Mats will not be able to know
     * whether the stage or initiation actually performed data access.
     * <p />
     * <b>Note: Only use this method if the variants NOT taking a DataSource fails to work.</b> It is imperative that
     * the DataSource and the PlatformTransactionManager provided "match up", meaning that the DataSource provided is
     * actually the instance which the PlatformTransactionManager handles.
     * <p />
     * Uses the standard {@link TransactionDefinition} Function, which sets the transaction name, sets Isolation Level
     * to {@link TransactionDefinition#ISOLATION_READ_COMMITTED} (unless HibernateTxMgr), and sets Propagation Behavior
     * to {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} - see
     * {@link #getStandardTransactionDefinitionFunctionFor(Class)}.
     *
     * @param platformTransactionManager
     *            the {@link DataSourceTransactionManager} to use for transaction management.
     * @param dataSource
     *            the {@link DataSource} which the supplied {@link PlatformTransactionManager} handles.
     * @return a new {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx}.
     */
    public static JmsMatsTransactionManager_JmsAndSpringManagedSqlTx create(
            PlatformTransactionManager platformTransactionManager, DataSource dataSource) {
        log.info(LOG_PREFIX + "create(PlatformTransactionManager, dataSource) [" + platformTransactionManager + "], ["
                + dataSource + "]");

        // Trying to find the DataSource from the PlatformTransactionManager nevertheless
        try {
            DataSource dataSourceFromTxMgr = getDataSourceFromTransactionManager(platformTransactionManager);
            log.warn(LOG_PREFIX + "NOTICE: I managed to get the DataSource from the PlatformTransactionManager you"
                    + " provided, and thus I suggest that you instead use the factory methods NOT taking a"
                    + " DataSource, to minimise the chances of supplying the wrong DataSource compared to"
                    + " what is managed by the PlatformTransactionManager.");
            if (dataSourceFromTxMgr != dataSource) {
                log.warn(LOG_PREFIX + "NOTICE VERY MUCH! The DataSource provided in the factory method is NOT the same"
                        + " instance that I got by introspecting the PlatformTransactionManager. This is MOST PROBABLY"
                        + " not what you want, and I was torn whether to throw an Exception here - but you might got"
                        + " your reasons?!");
            }
        }
        catch (IllegalArgumentException e) {
            /* no-op: Not being able to get the DataSource should be the reason why you employ this factory method! */
        }

        return new JmsMatsTransactionManager_JmsAndSpringManagedSqlTx(platformTransactionManager, dataSource,
                getStandardTransactionDefinitionFunctionFor(platformTransactionManager.getClass()));
    }

    /**
     * Creates a {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx} from a provided
     * {@link PlatformTransactionManager} (of a type which manages a DataSource) - Do note that you should preferably
     * have the {@link DataSource} within the <code>{@link PlatformTransactionManager}</code> wrapped using the
     * {@link #wrapLazyConnectionDatasource(DataSource)} method. If not wrapped as such, Mats will not be able to know
     * whether the stage or initiation actually performed data access.
     * <p />
     * <b>Note: Only use this method if the variants NOT taking a DataSource fails to work.</b> It is imperative that
     * the DataSource and the PlatformTransactionManager provided "match up", meaning that the DataSource provided is
     * actually the instance which the PlatformTransactionManager handles.
     * <p />
     * Uses the supplied {@link TransactionDefinition} Function to define the transactions - consider
     * {@link #create(PlatformTransactionManager, DataSource)} if you are OK with the standard.
     *
     * @param platformTransactionManager
     *            the {@link PlatformTransactionManager} to use for transaction management (must be one employing a
     *            DataSource).
     * @param dataSource
     *            the {@link DataSource} which the supplied {@link PlatformTransactionManager} handles.
     * @param transactionDefinitionFunction
     *            a {@link Function} which returns a {@link DefaultTransactionDefinition}, possibly based on the
     *            provided {@link JmsMatsTxContextKey} (e.g. different isolation level for a special endpoint).
     * @return a new {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx}.
     */
    public static JmsMatsTransactionManager_JmsAndSpringManagedSqlTx create(
            PlatformTransactionManager platformTransactionManager, DataSource dataSource,
            Function<JmsMatsTxContextKey, DefaultTransactionDefinition> transactionDefinitionFunction) {
        log.info(LOG_PREFIX + "create(PlatformTransactionManager, dataSource, transactionDefinitionFunction) ["
                + platformTransactionManager + "], [" + dataSource + "], [" + transactionDefinitionFunction + "]");

        // Trying to find the DataSource from the PlatformTransactionManager nevertheless
        try {
            DataSource dataSourceFromTxMgr = getDataSourceFromTransactionManager(platformTransactionManager);
            log.warn(LOG_PREFIX + "NOTICE: I managed to get the DataSource from the PlatformTransactionManager you"
                    + " provided, and thus I suggest that you instead use the factory methods NOT taking a"
                    + " DataSource, to minimise the chances of supplying the wrong DataSource compared to"
                    + " what is managed by the PlatformTransactionManager.");
            if (dataSourceFromTxMgr != dataSource) {
                log.warn(LOG_PREFIX + "NOTICE VERY MUCH! The DataSource provided in the factory method is NOT the same"
                        + " instance that I got by introspecting the PlatformTransactionManager. This is MOST PROBABLY"
                        + " not what you want, and I was torn whether to throw an Exception here - but you might got"
                        + " your reasons?!");
            }
        }
        catch (IllegalArgumentException e) {
            /* no-op: Not being able to get the DataSource should be the reason why you employ this factory method! */
        }

        return new JmsMatsTransactionManager_JmsAndSpringManagedSqlTx(platformTransactionManager, dataSource,
                transactionDefinitionFunction);
    }

    /**
     * Utility to get the DataSource from a PlatformTransactionManager, assuming that it has a
     * <code>getDataSource()</code> method.
     */
    private static DataSource getDataSourceFromTransactionManager(
            PlatformTransactionManager platformTransactionManager) {
        DataSource dataSource;
        try {
            Method getDataSource = platformTransactionManager.getClass().getMethod("getDataSource");
            getDataSource.setAccessible(true);
            dataSource = (DataSource) getDataSource.invoke(platformTransactionManager);
            if (dataSource == null) {
                throw new IllegalArgumentException("When invoking .getDataSource() on the PlatformTransactionManager,"
                        + " we got 'null' return [" + platformTransactionManager + "].");
            }
            return dataSource;
        }
        catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException("The supplied PlatformTransactionManager does not have a"
                    + " .getDataSource() method, or got problems invoking it.", e);
        }
    }

    /**
     * Returns the standard TransactionDefinition Function for the supplied PlatformTransactionManager. Sets Isolation
     * Level to {@link TransactionDefinition#ISOLATION_READ_COMMITTED ISOLATION_READ_COMMITTED} (unless
     * HibernateTransactionManager, which does not support setting Isolation Level) and Propagation Behavior to
     * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW PROPAGATION_REQUIRES_NEW} - and also sets the name of the
     * transaction to {@link JmsMatsTxContextKey}.toString().
     */
    public static Function<JmsMatsTxContextKey, DefaultTransactionDefinition> getStandardTransactionDefinitionFunctionFor(
            Class<? extends PlatformTransactionManager> platformTransactionManager) {
        log.info(LOG_PREFIX + "TransactionDefinition Function not provided, thus using default which sets the"
                + " transaction name, sets Isolation Level to ISOLATION_READ_COMMITTED, and sets Propagation Behavior"
                + " to PROPAGATION_REQUIRES_NEW (unless HibernateTransactionManager, where setting Isolation Level"
                + " evidently is not supported).");
        // ?: Is it HibernateTransactionManager?
        if (platformTransactionManager.getSimpleName().equals("HibernateTransactionManager")) {
            // -> Yes, Hibernate, which does not allow to set Isolation Level
            return (txContextKey) -> {
                DefaultTransactionDefinition transDef = new DefaultTransactionDefinition();
                transDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transDef.setName(txContextKey.toString());
                return transDef;
            };
        }
        // E-> normal mode
        return (txContextKey) -> {
            DefaultTransactionDefinition transDef = new DefaultTransactionDefinition();
            transDef.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transDef.setName(txContextKey.toString());
            return transDef;
        };
    }

    /**
     * Creates a proxy/wrapper that has lazy connection getting, and monitoring of whether the connection was actually
     * retrieved. This again enables SpringJmsMats implementation to see if the SQL Connection was actually
     * <i>employed</i> (i.e. a Statement was created) - not only whether we went into transactional demarcation, which a
     * Mats stage <i>always</i> does. This method is internally employed with the {@link #create(DataSource)
     * DataSource-taking} factories of this class which makes an internal {@link DataSourceTransactionManager}, but
     * should also be employed if you externally create another type of {@link PlatformTransactionManager}, e.g. the
     * HibernateTransactionManager, and provide that to the factories of this class taking a PlatformTransactionManager.
     * <p />
     * It returns an instance of {@link DeferredConnectionProxyDataSourceWrapper}, but as an extension that also
     * implements Spring's {@link InfrastructureProxy}.
     * <p />
     * We want this Lazy-and-Monitored DataSource which is returned here to "compare equals" with that of the DataSource
     * which is supplied to us - and which might be employed by other components "on the outside" of Mats - wrt. how
     * Spring's {@link DataSourceUtils} compare them in its ThreadLocal cache-hackery. Therefore, the proxy implement
     * {@link InfrastructureProxy} (read its JavaDoc!), which means that Spring can trawl its way down to the actual
     * DataSource when it needs to compare. Note: You will find this proxy-handling in the
     * {@link TransactionSynchronizationManager#getResource(Object)}, which invokes
     * <code>TransactionSynchronizationUtils.unwrapResourceIfNecessary(..)</code>, where the instanceof-check for
     * InfraStructureProxy resides.
     * <p />
     * <i>"The magnitude of this hack compares favorably with that of the US-of-A's national debt."</i>
     * <p />
     * Note: It is not a problem if the DataSource supplied is already wrapped in a
     * {@link LazyConnectionDataSourceProxy}, but it is completely unnecessary.
     * <p />
     * Tip: If you would want to check/understand how the LazyConnection stuff work, you may within a Mats stage do a
     * DataSourceUtil.getConnection(dataSource) - if the returned Connection's toString() looks like
     * <code>"DeferredConnectionProxy@3ec11999 WITHOUT actual Connection from DataSource@3406472c..."</code> then the
     * actual Connection is still not gotten. When it is gotten (e.g. after having done a SQL CRUD operation), the
     * toString() will look like <code>"DeferredConnectionProxy@3ec11999 WITH actual Connection@b5cc23a"</code>
     */
    public static DataSource wrapLazyConnectionDatasource(DataSource targetDataSource) {
        log.info(LOG_PREFIX + "Wrapping provided DataSource in a 'magic lazy proxy' which allows both"
                + " elision of JDBC transaction management if the Connection was never employed, AND allows Mats to"
                + " know whether an initiation or stage employed the Connection or not. [" + targetDataSource + "]");
        if (targetDataSource instanceof DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy) {
            log.warn(LOG_PREFIX + "  \\-> The DataSource you provided was already wrapped with the 'magic lazy proxy',"
                    + " so why are you trying to wrap it again? Ignoring by simply returning the already-wrapped"
                    + " DataSource you provided (thus not double-wrapping it). [" + targetDataSource + "]");
            return targetDataSource;
        }
        if (targetDataSource instanceof TransactionAwareDataSourceProxy) {
            throw new IllegalStateException("The provided DataSource should not be of type"
                    + " TransactionAwareDataSourceProxy (read its JavaDoc). Give me a 'cleaner' DataSource, preferably"
                    + " a pooled DataSource. [" + targetDataSource + "]");
        }
        if (targetDataSource instanceof LazyConnectionDataSourceProxy) {
            log.info(LOG_PREFIX + "  \\-> NOTICE: The provided DataSource is a LazyConnectionDataSourceProxy, which is"
                    + " unnecessary, but not really a problem.");
        }
        DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy deferredDataSource = new DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy(
                targetDataSource);
        log.info(LOG_PREFIX + "Wrapped the provided DataSource as [" + deferredDataSource + "].");

        return deferredDataSource;
    }

    /**
     * @return the employed Spring {@link PlatformTransactionManager}, which either was created by this instance if this
     *         instance was created using one of the DataSource-taking factory methods, or it was provided if this
     *         instance was created using one of the PlatformTransactionManager-taking methods.
     */
    public PlatformTransactionManager getPlatformTransactionManager() {
        return _platformTransactionManager;
    }

    /**
     * @return the employed {@link DataSource}, which either was provided when creating this instance (and thus
     *         wrapped), or was reflected out of the provided {@link PlatformTransactionManager}. It is hopefully
     *         wrapped using the {@link #wrapLazyConnectionDatasource(DataSource)}, which is done automatically if this
     *         instance was created using one of the DataSource-taking factory methods. However, if this instance was
     *         created using one of the PlatformTransactionManager-taking factory methods, it is up to the user to have
     *         wrapped it. Note that it works unwrapped too, but SpringJmsMats cannot then know whether the stages
     *         actually employ the SQL Connection, and must do full transaction demarcation around every stage.
     */
    public DataSource getDataSource() {
        return _dataSource;
    }

    /**
     * @return the unwrapped variant of {@link #getDataSource()} - note that it is only any {@link InfrastructureProxy}s
     *         that are unwrapped; Any wrapping done by a database pool is left intact.
     */
    public DataSource getDataSourceUnwrapped() {
        // ?: Is the DataSource wrapped? (hopefully is..)
        if (_dataSource instanceof InfrastructureProxy) {
            // -> Yes it is, so unwrap it.
            return (DataSource) ((InfrastructureProxy) _dataSource).getWrappedObject();
        }
        // E-> No, it isn't wrapped, so return directly.
        return _dataSource;
    }

    /**
     * Extension of {@link DeferredConnectionProxyDataSourceWrapper} which implements {@link InfrastructureProxy}.
     * <p />
     * Read JavaDoc for {@link #wrapLazyConnectionDatasource(DataSource)}.
     */
    protected static class DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy
            extends DeferredConnectionProxyDataSourceWrapper implements InfrastructureProxy {
        protected DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Object getWrappedObject() {
            DataSource targetDataSource = unwrap();
            if (targetDataSource instanceof InfrastructureProxy) {
                return ((InfrastructureProxy) targetDataSource).getWrappedObject();
            }
            return targetDataSource;
        }
    }

    @Override
    public TransactionContext getTransactionContext(JmsMatsTxContextKey txContextKey) {
        // Get the TransactionDefinition for this JmsMatsTxContextKey, which is a constant afterwards.
        DefaultTransactionDefinition defaultTransactionDefinition = _transactionDefinitionFunction.apply(txContextKey);
        return new TransactionalContext_JmsAndSpringDstm(txContextKey, _platformTransactionManager,
                defaultTransactionDefinition, _dataSource);
    }

    /**
     * The {@link TransactionContext}-implementation for {@link JmsMatsTransactionManager_JmsAndSpringManagedSqlTx}.
     */
    private static class TransactionalContext_JmsAndSpringDstm extends TransactionalContext_Jms {
        private final static String LOG_PREFIX = "#SPRINGJMATS# ";

        private final PlatformTransactionManager _platformTransactionManager;
        private final DefaultTransactionDefinition _transactionDefinitionForThisContext;
        private final DataSource _dataSource;

        public TransactionalContext_JmsAndSpringDstm(
                JmsMatsTxContextKey txContextKey,
                PlatformTransactionManager platformTransactionManager,
                DefaultTransactionDefinition transactionDefinitionForThisContext,
                DataSource dataSource) {
            super(txContextKey);
            _platformTransactionManager = platformTransactionManager;
            _transactionDefinitionForThisContext = transactionDefinitionForThisContext;
            _dataSource = dataSource;
        }

        @Override
        public void doTransaction(JmsMatsInternalExecutionContext internalExecutionContext,
                ProcessingLambda lambda)
                throws JmsMatsJmsException, MatsRefuseMessageException {

            try { // try-finally: Remove the HookBack from DataSource

                // :: We invoke the "outer" transaction, which is the JMS transaction.
                super.doTransaction(internalExecutionContext, () -> {
                    // ----- We're now *within* the JMS Transaction demarcation.

                    // :: Now go into the SQL Transaction demarcation
                    TransactionStatus transactionStatus = _platformTransactionManager.getTransaction(
                            _transactionDefinitionForThisContext);

                    // ----- We're now *within* the SQL Transaction demarcation.

                    /*
                     * IF we do NOT know whether the connection is gotten, because we were NOT given a magic-wrapped
                     * DataSource, then we cannot give a proper answer here. However, since the logic in the
                     * doTransaction(..) above always goes into SQL Transactional demarcation, we will always retrieve a
                     * Connection, and thus 'return true' is the most correct answer we can give in such situation.
                     * (Only time it would not have been correct, was if there actually was a Lazy proxy in the picture,
                     * but we can't know what, so we must assume yes.)
                     *
                     * NOTE: We COULD have checked if the TransactionSynchronizationManager.getResource(_dataSource) had
                     * a ConnectionHolder, which had a ConnectionHandle (which by logic above always is true), and then
                     * checked if the DataSourceUtil.getConnection(dataSource) by any chance was a
                     * LazyConnectionDataSourceProxy, and then introspect that for whether the Connection was gotten.
                     * But why could you not then instead use Mats' magic proxy which specifically handles this?!?
                     */
                    // Assume 'true' ("yes, it was employed"), unless we can do better
                    Supplier<Boolean> connectionEmployedState = () -> true;

                    // :? If we have a "magic" DataSource, enable the logic where we know whether Stage employed SQL.
                    if (_dataSource instanceof DeferredConnectionProxyDataSourceWrapper_InfrastructureProxy) {
                        // -> Yes, special DataSourceWrapper, so magic was-sql-Connection-employed tracking can ensue.
                        /*
                         * Fetch the transactional SQL Connection using DataSourceUtils. This should be of our own
                         * DeferredConnectionProxy (or a wrapped instance of it, which evidently can happen with certain
                         * setups of Hibernate), which we can utilize to query whether the user lambda code actually
                         * employed the underlying Connection.
                         *
                         * Note that just fetching the transactional SQL Connection instance from DataSourceUtils
                         * doesn't "employ" it (the actual SQL Connection is not yet fetched from the underlying
                         * DataSource).
                         */
                        Connection connectionFromDataSourceUtils = DataSourceUtils.getConnection(_dataSource);
                        // ?: Is it directly a DeferredConnectionProxy? (normal case)
                        if (connectionFromDataSourceUtils instanceof DeferredConnectionProxy) {
                            // -> Yes it is directly a DeferredConnectionProxy, so cast it
                            log.debug(LOG_PREFIX + "SQL Connection directly wrapped as DeferredConnectionProxy gotten"
                                    + " from DataSourceUtils: [" + connectionFromDataSourceUtils + "]");
                            DeferredConnectionProxy deferredConnectionProxy = (DeferredConnectionProxy) connectionFromDataSourceUtils;
                            connectionEmployedState = deferredConnectionProxy::isActualConnectionRetrieved;
                        }
                        else {
                            // -> No, it wasn't directly a DeferredConnectionProxy, so check if it isWrapperFor(..)
                            try {
                                if (connectionFromDataSourceUtils.isWrapperFor(DeferredConnectionProxy.class)) {
                                    // -> Yes, it isWrapperFor(..), so unwrap it.
                                    DeferredConnectionProxy deferredConnectionProxy = connectionFromDataSourceUtils
                                            .unwrap(DeferredConnectionProxy.class);
                                    log.debug(LOG_PREFIX + "SQL Connection gotten from DataSourceUtils"
                                            + " isWrapperFor(DeferredConnectionProxy), thus unwrapped to that:"
                                            + " [" + deferredConnectionProxy + "], original: ["
                                            + connectionFromDataSourceUtils + "]");
                                    connectionEmployedState = deferredConnectionProxy::isActualConnectionRetrieved;
                                }
                            }
                            catch (SQLException e) {
                                log.warn("Got SQLException when trying to invoke .isWrapperFor(DeferredConnectionProxy)"
                                        + " or .unwrap(DeferredConnectionProxy) on the SQL Connection retrieved by"
                                        + " DataSourceUtils.getConnection(_dataSource). This should really not happen."
                                        + " Ignoring (cannot do sql-employ-tracking now) -"
                                        + " please notify Mats maintainers about this situation.", e);
                            }
                        }
                    }

                    // :: Make the potential SQL Connection available
                    // Notice how we here use the DataSourceUtils class, so that we get the tx ThreadLocal Connection.
                    // Read more at both DataSourceUtils and DataSourceTransactionManager.
                    internalExecutionContext.setSqlTxConnectionSuppliers(
                            () -> DataSourceUtils.getConnection(_dataSource), connectionEmployedState);
                    // :: Also make it available for testing.
                    JmsMatsContextLocalCallback.bindResource(CONTEXT_LOCAL_KEY_CONNECTION_EMPLOYED_STATE_SUPPLIER,
                            connectionEmployedState);

                    try {
                        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "About to run ProcessingLambda for "
                                + stageOrInit(_txContextKey) + ", within Spring SQL Transactional demarcation.");
                        /*
                         * Invoking the provided ProcessingLambda, which typically will be the actual user code (albeit
                         * wrapped with some code from the JmsMatsStage to parse the MapMessage, deserialize the
                         * MatsTrace, and fetch the state etc.), which will now be inside both the inner (explicit) SQL
                         * Transaction demarcation, and the outer (implicit) JMS Transaction demarcation.
                         */
                        lambda.performWithinTransaction();
                    }
                    // Catch EVERYTHING that legally can come out of the try-block:
                    catch (JmsMatsJmsException | MatsRefuseMessageException | RuntimeException | Error e) {
                        // ----- The user code had some error occur, or want to reject this message.
                        // !!NOTE!!: The full Exception will be logged by outside JMS-trans class on JMS rollback
                        // handling.
                        log.error(LOG_PREFIX + "ROLLBACK SQL: " + e.getClass().getSimpleName() + " while processing "
                                + stageOrInit(_txContextKey) + " (should only be from user code)."
                                + " Rolling back the SQL Connection.", e);
                        internalExecutionContext.setUserLambdaExceptionLogged();
                        /*
                         * IFF the SQL Connection was fetched, we will now rollback (and close) it.
                         */
                        commitOrRollbackSqlTransaction(internalExecutionContext, connectionEmployedState,
                                SqlTxAction.ROLLBACK, transactionStatus, e);

                        // ----- We're *outside* the SQL Transaction demarcation (rolled back).

                        // We will now throw on the Exception, which will rollback the JMS Transaction.
                        throw e;
                    }
                    catch (Throwable t) {
                        // ----- This must have been a "sneaky throws"; Throwing an undeclared checked exception.
                        // !!NOTE!!: The full Exception will be logged by outside JMS-trans class on JMS rollback
                        // handling.
                        log.error(LOG_PREFIX + "ROLLBACK SQL: Got an undeclared checked exception " + t.getClass()
                                .getSimpleName() + " while processing " + stageOrInit(_txContextKey)
                                + " (should only be 'sneaky throws' of checked exception in user code)."
                                + " Rolling back the SQL Connection.", t);
                        internalExecutionContext.setUserLambdaExceptionLogged();
                        /*
                         * IFF the SQL Connection was fetched, we will now rollback (and close) it.
                         */
                        commitOrRollbackSqlTransaction(internalExecutionContext, connectionEmployedState,
                                SqlTxAction.ROLLBACK, transactionStatus, t);

                        // ----- We're *outside* the SQL Transaction demarcation (rolled back).

                        // Rethrow the Throwable as special RTE, which will rollback the JMS Transaction.
                        throw new JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException("Got a undeclared checked"
                                + " exception " + t.getClass().getSimpleName()
                                + " while processing " + stageOrInit(_txContextKey) + ".", t);
                    }

                    // ----- The ProcessingLambda went OK, no Exception was raised.

                    if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "COMMIT SQL: ProcessingLambda finished,"
                            + " committing SQL Connection.");

                    // Check whether Session/Connection is ok before committing DB (per contract with JmsSessionHolder).
                    try {
                        internalExecutionContext.getJmsSessionHolder().isSessionOk();
                    }
                    catch (JmsMatsJmsException e) {
                        // ----- Evidently the JMS Session is broken - so rollback SQL
                        log.error(LOG_PREFIX + "ROLLBACK SQL: " + e.getClass().getSimpleName()
                                + " when checking whether"
                                + " the JMS Session was OK. Rolling back the SQL Connection.", e);
                        /*
                         * IFF the SQL Connection was fetched, we will now rollback (and close) it.
                         */
                        commitOrRollbackSqlTransaction(internalExecutionContext, connectionEmployedState,
                                SqlTxAction.ROLLBACK, transactionStatus, e);

                        // ----- We're *outside* the SQL Transaction demarcation (rolled back).

                        // We will now throw on the Exception, which will rollback the JMS Transaction.
                        throw e;
                    }

                    /*
                     * IFF the SQL Connection was fetched, we will now commit (and close) it.
                     */
                    commitOrRollbackSqlTransaction(internalExecutionContext, connectionEmployedState,
                            SqlTxAction.COMMIT, transactionStatus, null);

                    // ----- We're now *outside* the SQL Transaction demarcation (committed).

                    // Return nicely, as the SQL Connection.commit() went OK.

                    // When exiting this lambda, the JMS transaction will be committed by the "outer" JMS tx impl.
                });
            }
            finally {
                @SuppressWarnings("unchecked")
                Supplier<Boolean> connectionEmployedState = (Supplier<Boolean>) JmsMatsContextLocalCallback.getResource(
                        CONTEXT_LOCAL_KEY_CONNECTION_EMPLOYED_STATE_SUPPLIER);
                if (connectionEmployedState != null) {
                    if (log.isDebugEnabled())
                        log.debug("About to exit the SQL Transactional Demarcation - SQL Connection "
                                + (connectionEmployedState.get() ? "WAS" : "was NOT") + " employed!");
                }
                // Unbind the ConnectionAcquiredStateSupplier from ContextLocal
                JmsMatsContextLocalCallback.unbindResource(CONTEXT_LOCAL_KEY_CONNECTION_EMPLOYED_STATE_SUPPLIER);
            }
        }

        private enum SqlTxAction {
            COMMIT, ROLLBACK
        }

        /**
         * Make note: The Spring DataSourceTransactionManager closes the SQL Connection after commit or rollback. Read
         * more e.g. <a href="https://stackoverflow.com/a/18207654/39334">here</a>, or look in
         * {@link DataSourceTransactionManager#doCleanupAfterCompletion(Object)}, which invokes
         * {@link DataSourceUtils#releaseConnection(Connection, DataSource)}, which invokes doCloseConnection(), which
         * eventually calls connection.close().
         */
        private void commitOrRollbackSqlTransaction(JmsMatsInternalExecutionContext internalExecutionContext,
                Supplier<Boolean> sqlConnectionEmployedSupplier, SqlTxAction sqlTxAction,
                TransactionStatus transactionStatus, Throwable exceptionThatHappened) {
            // NOTICE: THE FOLLOWING if-STATEMENT IS JUST FOR LOGGING!
            // ?: Was connection gotten by code in ProcessingLambda (user code)
            // NOTICE: We must commit or rollback the Spring TransactionManager nevertheless, to clean up
            if (!sqlConnectionEmployedSupplier.get()) {
                // -> No, Connection was not gotten
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "NOTICE: SQL Connection was NOT requested by stage"
                        + " or initiation (user code), so the following commit/rollback is no-op.");
                // NOTICE: We must commit or rollback the Spring TransactionManager nevertheless, to clean up.
                // NOTICE: NOT returning! The log line is just for informational purposes.
            }

            long nanosAsStart_DbCommit = System.nanoTime();

            // :: Commit or Rollback
            try {
                // ?: Commit or rollback?
                if (sqlTxAction == SqlTxAction.COMMIT) {
                    // -> Commit.
                    // ?: Check if we have gotten into "RollbackOnly" state, implying that the user has messed up.
                    if (transactionStatus.isRollbackOnly()) {
                        // -> Yes, we're in "RollbackOnly" - so rollback and throw out.
                        String msg = "When about to commit the SQL Transaction ["
                                + transactionStatus + "], we found that it was in a 'RollbackOnly' state. This implies"
                                + " that you have performed your own Spring transaction management within the Mats"
                                + " Stage/Initiation, which is not supported. Will now rollback the SQL, and throw"
                                + " out to rollback JMS.";
                        log.error(LOG_PREFIX + msg);
                        // If the rollback throws, it was a rollback (read the Exception-throwing at final catch).
                        sqlTxAction = SqlTxAction.ROLLBACK;
                        // Do rollback.
                        _platformTransactionManager.rollback(transactionStatus);
                        // Throw out, so that we /do not/ commit the JMS.
                        // (NOTE: There won't be an exceptionThatHappened in the "good case" of commit.)
                        throw new MatsSqlCommitWasRollbackOnlyException(msg);
                    }
                    // E-> No, we were NOT in "RollbackOnly" - so commit this stuff, and get out.
                    _platformTransactionManager.commit(transactionStatus);
                    if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Committed SQL Transaction ["
                            + transactionStatus + "].");
                }
                else {
                    // -> Rollback.
                    _platformTransactionManager.rollback(transactionStatus);
                    if (log.isDebugEnabled()) log.warn(LOG_PREFIX + "Rolled Back SQL Transaction ["
                            + transactionStatus + "].");
                }
            }
            catch (TransactionException e) {
                MatsSqlCommitOrRollbackFailedException failedException = new MatsSqlCommitOrRollbackFailedException(
                        "Could not [" + sqlTxAction + "] SQL Transaction [" + transactionStatus
                                + "] - for [" + _txContextKey + "].", e);
                // ?: If we had an Exception "on its way out", then we'll have to add this as suppressed.
                if (exceptionThatHappened != null) {
                    failedException.addSuppressed(exceptionThatHappened);
                }
                throw failedException;
            }
            finally {
                internalExecutionContext.setDbCommitNanos(System.nanoTime() - nanosAsStart_DbCommit);
            }
        }

        /**
         * Raised if we come into commit/rollback, and find that TransactionStatus is in "rollbackOnly mode".
         */
        static final class MatsSqlCommitWasRollbackOnlyException extends RuntimeException {
            public MatsSqlCommitWasRollbackOnlyException(String message) {
                super(message);
            }
        }

        /**
         * Raised if commit or rollback of the SQL Connection failed.
         */
        static final class MatsSqlCommitOrRollbackFailedException extends RuntimeException {
            MatsSqlCommitOrRollbackFailedException(String message, Throwable cause) {
                super(message, cause);
            }
        }
    }
}
