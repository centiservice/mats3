package io.mats3.util.wrappers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory.MatsWrapperDefault;

/**
 * DataSource wrapper which returns thin Connection proxies (currently employing Java's "dynamic proxy" functionality)
 * which do not actually fetch a Connection until it is needed. It defers as long as possible, "holding back" calls to
 * {@link Connection#setAutoCommit(boolean) setAutoCommit(..)}, {@link Connection#setTransactionIsolation(int)
 * setTransactionIsolation(..)}, {@link Connection#setReadOnly(boolean) setReadOnly(..)}, and ignores any
 * {@link Connection#commit() commit()} and {@link Connection#rollback() rollback()}, as well as handling some
 * surrounding methods like toString(), equals(), hashCode() etc, until the user code e.g. creates a Statement or
 * PreparedStatement - i.e. not until the user code actually needs to talk to the database. This deferring results in a
 * situation where if the user code opens a transaction, but does <i>not</i> need to talk to the database after all, and
 * then commits the transaction, the entire operation is elided - saving several round-trips over the wire.
 * <p />
 * The use of Java Dynamic Proxies is a simple way to ensure that the implementation is future proof, in that any method
 * that appears on the Connection in any Java version will be handled by triggering actual Connection fetching and
 * subsequent call-through to the actual method. Only the methods that are used in standard transaction management are
 * specially handled to defer the getting of the actual Connection.
 * <p />
 * Note that it is assumed that the AutoCommit, TransactionIsolation and ReadOnly values are identical for all fetched
 * Connections (that is, the pool resets them to some default when {@link Connection#close() connection.close()} is
 * invoked). The default values for these properties are retrieved from the very first Connection that is actually
 * fetched. The deferring works as such: If you set one of these value on the returned proxied Connection, this will be
 * recorded, and will subsequently be set on the actual Connection once it is fetched because it is needed (e.g. when a
 * PreparedStatement is created). However, as a further performance optimization, when about to set the value, it is
 * compared against those default values gotten from the first Connection, and if they are equal, the actual
 * setting-invocation is elided.
 * <p />
 * Inspired by Spring's LazyConnectionDataSourceProxy, but with the additional feature that you can query the Connection
 * proxy for whether the underlying Connection was actually gotten, since it is a {@link DeferredConnectionProxy
 * DeferredConnectionProxy} (extends {@link Connection}). It also has the method
 * {@link #actualConnectionWasRetrieved(DeferredConnectionProxy, Connection) actualConnectionWasRetrieved(proxy,
 * actualConnection)} which can be interesting for extensions. It also has the method
 * {@link #methodInvoked(boolean, DeferredConnectionProxy, Connection, Method, Object[], Object) methodInvoked(..)},
 * which is invoked after every method invoked on the returned {@link DeferredConnectionProxy DeferredConnectionProxy}s,
 * which primarily is interesting for testing, but might have other uses.
 * <p />
 * If in a Spring environment, the returned instance furthermore implements
 * <code>org.springframework.jdbc.datasource.ConnectionProxy</code>.
 * 
 * @author Endre Stølsvik 2021-01-26 23:45 - http://stolsvik.com/, endre@stolsvik.com
 */
public class DeferredConnectionProxyDataSourceWrapper extends DataSourceWrapper {

    // TODO: Evaluate what impact dynaproxies gives on performance, and whether rather use ByteBuddy or similar.

    private static final Logger log = LoggerFactory.getLogger(DeferredConnectionProxyDataSourceWrapper.class);

    private static final String SPRING_CONNECTION_PROXY_CLASSNAME = "org.springframework.jdbc.datasource.ConnectionProxy";

    private static final Class<?>[] INTERFACES_TO_IMPLEMENT;

    private static final Map<Integer, String> INT_TO_TRANSACTION_ISOLATION = new HashMap<>();
    static {
        INT_TO_TRANSACTION_ISOLATION.put(Connection.TRANSACTION_NONE, "TRANSACTION_NONE"); // 0
        INT_TO_TRANSACTION_ISOLATION.put(Connection.TRANSACTION_READ_UNCOMMITTED, "TRANSACTION_READ_UNCOMMITTED"); // 1
        INT_TO_TRANSACTION_ISOLATION.put(Connection.TRANSACTION_READ_COMMITTED, "TRANSACTION_READ_COMMITTED"); // 2
        INT_TO_TRANSACTION_ISOLATION.put(Connection.TRANSACTION_REPEATABLE_READ, "TRANSACTION_REPEATABLE_READ"); // 4
        INT_TO_TRANSACTION_ISOLATION.put(Connection.TRANSACTION_SERIALIZABLE, "TRANSACTION_SERIALIZABLE"); // 8

        Class<?> springConnectionProxyInterface = null;
        try {
            springConnectionProxyInterface = Class.forName(SPRING_CONNECTION_PROXY_CLASSNAME);
        }
        catch (Exception e) {
            /* Not found: no-op */
        }
        INTERFACES_TO_IMPLEMENT = springConnectionProxyInterface != null
                ? new Class<?>[] { DeferredConnectionProxy.class, springConnectionProxyInterface }
                : new Class<?>[] { DeferredConnectionProxy.class };
    }

    /**
     * @param dataSource
     *            the DataSource to wrap.
     * @return an instance of this class wrapping the supplied DataSource.
     */
    public static DeferredConnectionProxyDataSourceWrapper wrap(DataSource dataSource) {
        return new DeferredConnectionProxyDataSourceWrapper(dataSource);
    }

    /**
     * @return a "thin Proxy", implemented as Java's "dynamic proxy", NOT YET proxying an actual Connection.
     * @throws SQLException
     *             can actually never throw SQLException, since it just gives you a proxy.
     */
    @Override
    public DeferredConnectionProxy getConnection() throws SQLException {
        DeferredFetchInvocationHandler invocationHandler = new DeferredFetchInvocationHandler();
        DeferredConnectionProxy connectionProxy = (DeferredConnectionProxy) Proxy.newProxyInstance(
                DeferredConnectionProxyDataSourceWrapper.class.getClassLoader(),
                INTERFACES_TO_IMPLEMENT, invocationHandler);
        invocationHandler.setConnectionProxy(connectionProxy);
        return connectionProxy;
    }

    /**
     * @param username
     *            username to to forward to underlying DataSource.getConnection(username, password) when Connection
     *            needs to be gotten.
     * @param password
     *            password to to forward to underlying DataSource.getConnection(username, password) when Connection
     *            needs to be gotten.
     * @return a "thin Proxy", implemented as Java's "dynamic proxy", NOT YET proxying an actual Connection.
     * @throws SQLException
     *             can actually never throw SQLException, since it just gives you a proxy.
     */
    @Override
    public DeferredConnectionProxy getConnection(String username, String password) throws SQLException {
        DeferredFetchInvocationHandler invocationHandler = new DeferredFetchInvocationHandler(username, password);
        DeferredConnectionProxy connectionProxy = (DeferredConnectionProxy) Proxy.newProxyInstance(
                DeferredConnectionProxyDataSourceWrapper.class.getClassLoader(),
                INTERFACES_TO_IMPLEMENT, invocationHandler);
        invocationHandler.setConnectionProxy(connectionProxy);
        return connectionProxy;
    }

    /**
     * Provides a method to query whether the Connection actually was gotten.
     */
    public interface DeferredConnectionProxy extends MatsWrapperDefault<Connection>, Connection {
        boolean isActualConnectionRetrieved();
    }

    /**
     * Override if you want to know when the actual Connection was retrieved. Default implementation logs to debug.
     */
    protected void actualConnectionWasRetrieved(DeferredConnectionProxy connectionProxy, Connection actualConnection) {
        if (log.isDebugEnabled()) log.debug("Actual Connection retrieved from DataSource@"
                + Integer.toHexString(System.identityHashCode(unwrap())) + " [" + unwrap()
                + "] -> Connection@" + Integer.toHexString(System.identityHashCode(actualConnection))
                + " [" + actualConnection
                + "], for DeferredConnectionProxy@" + Integer.toHexString(System.identityHashCode(connectionProxy)));
    }

    /**
     * Override if you want to know about every method invoked on the {@link DeferredConnectionProxy}, its arguments
     * and its return value, and whether it was the proxy or the actual Connection that answered. Default implementation
     * does nothing.
     */
    protected void methodInvoked(boolean answeredByProxy, DeferredConnectionProxy connectionProxy,
            Connection actualConnection, Method method, Object[] args, Object result) {
    }

    protected DeferredConnectionProxyDataSourceWrapper() {
        /* no-op; must set DataSource using setWrappee(..) */
    }

    protected DeferredConnectionProxyDataSourceWrapper(DataSource targetDataSource) {
        super(targetDataSource);
    }

    // :: Write guarded by synchronization on /this/.
    protected volatile Boolean _autoCommitFromWrappedDataSource;
    protected volatile Integer _transactionIsolationFromWrappedDataSource;
    protected volatile Boolean _readOnlyFromWrappedDataSource;

    /**
     * @return <code>null</code> until the first underlying Connection has actually been gotten, after that what this
     *         first underlying Connection replied to invocation of {@link Connection#getAutoCommit()}.
     */
    public Boolean getAutoCommitFromWrappedDataSource() {
        return _autoCommitFromWrappedDataSource;
    }

    /**
     * @return <code>null</code> until the first underlying Connection has actually been gotten, after that what this
     *         first underlying Connection replied to invocation of {@link Connection#getTransactionIsolation()}.
     */
    public Integer getTransactionIsolationFromWrappedDataSource() {
        return _transactionIsolationFromWrappedDataSource;
    }

    /**
     * @return <code>null</code> until the first underlying Connection has actually been gotten, after that what this
     *         first underlying Connection replied to invocation of {@link Connection#isReadOnly()}.
     */
    public Boolean getReadOnlyFromWrappedDataSource() {
        return _readOnlyFromWrappedDataSource;
    }

    protected void retrieveDefaultAutoCommitAndTransactionIsolationAndReadOnlyValues(Connection con)
            throws SQLException {
        // Note: Double-checked-locking, using volatile and synchronized
        if ((_autoCommitFromWrappedDataSource == null) || (_transactionIsolationFromWrappedDataSource == null)
                || _readOnlyFromWrappedDataSource == null) {
            boolean firstDepooledConnectionAutoCommitStatus = con.getAutoCommit();
            int firstDepooledConnectionTransactionIsolationStatus = con.getTransactionIsolation();
            boolean firstDepooledConnectionReadOnlyStatus = con.isReadOnly();

            log.info("Retrieved default values for transactional properties from first actual Connection:"
                    + " AutoCommit:[" + firstDepooledConnectionAutoCommitStatus + "],"
                    + " TransactionIsolation:[" + firstDepooledConnectionTransactionIsolationStatus + ":"
                    + INT_TO_TRANSACTION_ISOLATION.get(firstDepooledConnectionTransactionIsolationStatus) + "],"
                    + " ReadOnly:[" + firstDepooledConnectionReadOnlyStatus + "].");
            synchronized (this) {
                // ?: Checking double..
                if ((_autoCommitFromWrappedDataSource == null) || (_transactionIsolationFromWrappedDataSource == null)
                        || _readOnlyFromWrappedDataSource == null) {
                    // -> Not set, setting:
                    _autoCommitFromWrappedDataSource = firstDepooledConnectionAutoCommitStatus;
                    _transactionIsolationFromWrappedDataSource = firstDepooledConnectionTransactionIsolationStatus;
                    _readOnlyFromWrappedDataSource = firstDepooledConnectionReadOnlyStatus;
                }
            }
        }
    }

    /**
     * Implementation of Java's "dynamic proxy" {@link InvocationHandler} for deferring fetching of actual Connection
     * until necessary.
     */
    protected class DeferredFetchInvocationHandler implements InvocationHandler {
        private final String _username;
        private final String _password;

        private Connection _actualConnection;

        private Boolean _desiredAutoCommit;
        private Integer _desiredTransactionIsolation;
        private Boolean _desiredReadOnly;

        private boolean _closedWithoutGettingActualConnection;

        private DeferredConnectionProxy _connectionProxy;

        public DeferredFetchInvocationHandler() {
            _username = null;
            _password = null;
        }

        public DeferredFetchInvocationHandler(String username, String password) {
            _username = username;
            _password = password;
        }

        protected void setConnectionProxy(DeferredConnectionProxy connectionProxy) {
            _connectionProxy = connectionProxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // :: First handle methods we can or must answer with only the proxy

            switch (method.getName()) {
                case "isActualConnectionRetrieved":
                    methodInvoked(true, _connectionProxy, _actualConnection, method, args, _actualConnection != null);
                    return _actualConnection != null;

                case "getTargetConnection":
                    // -> Spring's ConnectionProxy.getTargetConnection() ("magically" implemented if on Classpath)
                    // Actually get the Connection
                    retrieveActualConnection(method);
                    // Return it
                    methodInvoked(true, _connectionProxy, _actualConnection, method, args, _actualConnection);
                    return _actualConnection;

                case "unwrap":
                    // ?: Is this MatsWrapper's unwrap?
                    if (args == null) { // no-args method get null, not zero-length array.
                        // -> Yes, MatsWrapper.unwrap()
                        // Actually get the Connection
                        retrieveActualConnection(method);
                        // Return it
                        methodInvoked(true, _connectionProxy, _actualConnection, method, args, _actualConnection);
                        return _actualConnection;
                    }
                    // E-> No, it is the JDBC 4.0 Connection.unwrap(interface)
                    // ?: Is the question whether it is one of the interfaces the proxy implement?
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        // -> Yes, so return the proxy
                        methodInvoked(true, _connectionProxy, _actualConnection, method, args, proxy);
                        return proxy;
                    }
                    // E-> No, it is not - so fallthrough to retrieve and query the actual connection.
                    break;

                case "isWrapperFor":
                    // ?: Is the question whether it is one of the interfaces the proxy implement?
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        methodInvoked(true, _connectionProxy, _actualConnection, method, args, true);
                        return true;
                    }
                    // E-> No, it is not - so fallthrough to retrieve and query the actual connection.
                    break;

                case "equals":
                    // To avoid getting Connection for simple equals, we perform identity equality
                    boolean equals = proxy == args[0];
                    methodInvoked(true, _connectionProxy, _actualConnection, method, args, equals);
                    return equals;

                case "hashCode":
                    // To avoid getting Connection for simple hashcode, we perform identity hashcode.
                    // HashCode cannot change when we've gotten the actual Connection, so do this always.
                    int hashCode = System.identityHashCode(proxy);
                    methodInvoked(true, _connectionProxy, _actualConnection, method, args, hashCode);
                    return hashCode;

                case "toString":
                    // -> Yes, toString, reply with String explaining that we're wrapped.
                    // ?: Do we have actual Connection?
                    String toString = _actualConnection != null
                            ? "DeferredConnectionProxy@" + Integer.toHexString(System.identityHashCode(
                                    _connectionProxy)) + " WITH actual Connection@"
                                    + Integer.toHexString(System.identityHashCode(_actualConnection))
                                    + " [" + _actualConnection + "]"
                            : "DeferredConnectionProxy@" + Integer.toHexString(System.identityHashCode(
                                    _connectionProxy)) + " WITHOUT actual Connection from DataSource@"
                                    + Integer.toHexString(System.identityHashCode(unwrap()))
                                    + " [" + unwrap() + "]";
                    methodInvoked(true, _connectionProxy, _actualConnection, method, args, toString);
                    return toString;

            }

            // :: Now, handle methods which we will answer with only the proxy, UNLESS we've already have gotten
            // the actual Connection

            // ?: Do we have the actual Connection?
            if (_actualConnection == null) {
                // -> No, we do not have the actual Connection yet.
                // Let's hope we can handle it without getting it!

                // :: Fist handle methods we can do even if user have closed the Connection.

                switch (method.getName()) {
                    case "close":
                        // -> Yes, close(), and we haven't fetched Connection - set state to closed for proxy.
                        _closedWithoutGettingActualConnection = true;
                        // Void method, return null.
                        methodInvoked(true, _connectionProxy, null, method, args, null);
                        return null;

                    case "isClosed":
                        methodInvoked(true, _connectionProxy, null, method, args,
                                _closedWithoutGettingActualConnection);
                        return _closedWithoutGettingActualConnection;
                }

                // :: Now, check if the user have already closed the proxy (without getting the actual Connection)
                if (_closedWithoutGettingActualConnection) {
                    throw new SQLException("The Connection is already closed. (This is a"
                            + " DeferredConnectionProxy, and it was closed without fetching the actual Connection)");
                }

                // :: Now, do all the operations allowed when not having yet closed the connection proxy

                switch (method.getName()) {
                    case "setAutoCommit":
                        // -> Yes, setAutoCommit(): Record user's desire.
                        _desiredAutoCommit = (Boolean) args[0];
                        // Void method, return null.
                        methodInvoked(true, _connectionProxy, null, method, args, null);
                        return null;

                    case "getAutoCommit":
                        // -> Yes, getAutoCommit()
                        // ?: Have the user set his desired AutoCommit?
                        if (_desiredAutoCommit != null) {
                            // -> Yes, set - return his desire.
                            methodInvoked(true, _connectionProxy, null, method, args, _desiredAutoCommit);
                            return _desiredAutoCommit;
                        }
                        // E-> No, user hasn't set desired yet.
                        // ?: Have we gotten the default from the pool yet?
                        if (_autoCommitFromWrappedDataSource != null) {
                            // -> Yes, default from pool gotten - return it.
                            methodInvoked(true, _connectionProxy, null, method, args, _autoCommitFromWrappedDataSource);
                            return _autoCommitFromWrappedDataSource;
                        }
                        // E-> Desired nor default AutoCommit not yet set, so fall-through to getting the actual
                        // Connection. (This will also initialize the defaults.)
                        break;

                    case "setTransactionIsolation":
                        // -> Yes, setTransactionIsolation(): Record user's desire.
                        _desiredTransactionIsolation = (Integer) args[0];
                        // Void method, return null.
                        methodInvoked(true, _connectionProxy, null, method, args, null);
                        return null;

                    case "getTransactionIsolation":
                        // -> Yes, getTransactionIsolation()
                        // ?: Have the user set his desired TransactionIsolation?
                        if (_desiredTransactionIsolation != null) {
                            // -> Yes, set - return his desire.
                            methodInvoked(true, _connectionProxy, null, method, args, _desiredTransactionIsolation);
                            return _desiredTransactionIsolation;
                        }
                        // E-> No, user hasn't set desired yet.
                        // ?: Have we gotten the default from the pool yet?
                        if (_transactionIsolationFromWrappedDataSource != null) {
                            // -> Yes, default from pool gotten - return it.
                            methodInvoked(true, _connectionProxy, null, method, args,
                                    _transactionIsolationFromWrappedDataSource);
                            return _transactionIsolationFromWrappedDataSource;
                        }
                        // E-> Desired nor default TransactionIsolation not yet set, so fall-through to get the actual
                        // Connection. (This will also initialize the defaults.)
                        break;

                    case "setReadOnly":
                        // -> Yes, setReadOnly(): Record user's desire.
                        _desiredReadOnly = (Boolean) args[0];
                        // Void method, return null.
                        methodInvoked(true, _connectionProxy, null, method, args, null);
                        return null;

                    case "isReadOnly":
                        // -> Yes, isReadOnly()
                        // ?: Have the user set his desired ReadOnly?
                        if (_desiredReadOnly != null) {
                            // -> Yes, set - return his desire.
                            methodInvoked(true, _connectionProxy, null, method, args, _desiredReadOnly);
                            return _desiredReadOnly;
                        }
                        // E-> No, user hasn't set desired yet.
                        // ?: Have we gotten the default from the pool yet?
                        if (_readOnlyFromWrappedDataSource != null) {
                            // -> Yes, default from pool gotten - return it.
                            methodInvoked(true, _connectionProxy, null, method, args, _readOnlyFromWrappedDataSource);
                            return _readOnlyFromWrappedDataSource;
                        }
                        // E-> Desired nor default ReadOnly not yet set, so fall-through to get the actual Connection.
                        // (This will also initialize the defaults.)
                        break;

                    case "commit":
                        // -> Yes, commit(): Actual Connection not yet fetched, so nothing to commit, thus IGNORE.
                        // Fall-through: Void method, return null.

                    case "rollback":
                        // -> Yes, rollback(): Actual Connection not yet fetched, so nothing to rollback, thus IGNORE.
                        // Fall-through: Void method, return null.

                    case "getWarnings":
                        // -> Yes, getWarnings(): Actual Connection not yet fetched, so no Warnings (return null)
                        // Fall-through: Return null because no warnings

                    case "clearWarnings":
                        // -> Yes, clearWarnings(): Actual Connection not yet fetched, so nothing to clear.
                        // Void method, return null.
                        methodInvoked(true, _connectionProxy, null, method, args, null);
                        return null;
                }
            }

            // :: NOW, actual Connection is already fetched, OR we cannot handle the method invoked without getting it.

            // Retrieve; No-op if already retrieved. (Will also initialize the default trans props if not yet gotten.)
            retrieveActualConnection(method);

            // NOTICE: We assume that the pool resets the different values for AutoCommit, TransactionIsolation and
            // ReadOnly upon close().
            // E.g. for C3P0, the reset logic is in C3P0PoolecConnection.reset():391-426, where AutoCommit,
            // TransactionIsolation and ReadOnly; as well as Catalog, Holdability and TypeMap is reset.

            // Invoke the method on the actual Connection.
            try {
                Object result = method.invoke(_actualConnection, args);
                methodInvoked(false, _connectionProxy, _actualConnection, method, args, result);
                return result;
            }
            catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }

        /**
         * Fetches the Connection if necessary.
         */
        private void retrieveActualConnection(Method method) throws SQLException {
            // ?: Is it already fetched?
            if (_actualConnection != null) {
                // -> Yes, already fetched, so nothing to do.
                return;
            }

            if (log.isDebugEnabled())
                log.debug("Fetching actual Connection for method [" + method.getName() + "()].");

            // :: Fetch actual Connection

            // ?: Is username set?
            if (_username != null) {
                // -> Yes, set, so use the getConnection(u/p)
                _actualConnection = unwrap().getConnection(_username, _password);
            }
            else {
                // -> No, not set, so use the getConnection()
                _actualConnection = unwrap().getConnection();
            }

            retrieveDefaultAutoCommitAndTransactionIsolationAndReadOnlyValues(_actualConnection);

            // :: Apply the desired transaction settings

            // ?: Has user set desired AutoCommit?
            if (_desiredAutoCommit != null) {
                // -> Yes, user has set desired AutoCommit
                // ?: Is it different from the default (gotten from the first Connection)?
                if (!_desiredAutoCommit.equals(_autoCommitFromWrappedDataSource)) {
                    // -> Yes, it is different, so set it
                    _actualConnection.setAutoCommit(_desiredAutoCommit);
                }
            }

            // ?: Has user set desired TransactionIsolation?
            if (_desiredTransactionIsolation != null) {
                // -> Yes, user has set desired TransactionIsolation
                // ?: Is it different from the default (gotten from the first Connection)?
                if (!_desiredTransactionIsolation.equals(_transactionIsolationFromWrappedDataSource)) {
                    // -> Yes, it is different, so set it
                    _actualConnection.setTransactionIsolation(_desiredTransactionIsolation);
                }
            }

            // ?: Has user set desired ReadOnly?
            if (_desiredReadOnly != null) {
                // -> Yes, user has set desired TransactionIsolation
                // ?: Is it different from the default (gotten from the first Connection)?
                if (!_desiredReadOnly.equals(_readOnlyFromWrappedDataSource)) {
                    // -> Yes, it is different, so set it
                    try {
                        _actualConnection.setReadOnly(_desiredReadOnly);
                    }
                    catch (Exception e) {
                        // Read-only Connection is not supported, but this is just a hint, so we ignore it.
                        log.debug("Connection.setReadOnly(true) raised Exception, but ignoring since it is just a hint."
                                + " Connection [" + _actualConnection + "]", e);
                    }
                }
            }

            // :: Notify the DeferredConnectionProxyDataSourceWrapper about this actual retrieval.
            actualConnectionWasRetrieved(_connectionProxy, _actualConnection);
        }
    }
}
