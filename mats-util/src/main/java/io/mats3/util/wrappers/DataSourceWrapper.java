package io.mats3.util.wrappers;

import io.mats3.MatsFactory.MatsWrapper;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * A base Wrapper for a JDBC {@link DataSource}, which simply implements DataSource, takes a DataSource instance and
 * forwards all calls to that. Meant to be extended to add extra functionality, e.g. Spring integration.
 *
 * @author Endre Stølsvik 2021-01-26 23:45 - http://stolsvik.com/, endre@stolsvik.com
 */
public class DataSourceWrapper implements DataSource, MatsWrapper<DataSource> {
    /**
     * This field is private - if you in extensions need the instance, invoke {@link #unwrap()}. If you want to take
     * control of the wrapped DataSource instance, then override {@link #unwrap()}.
     */
    private DataSource _targetDataSource;

    /**
     * Standard constructor, taking the wrapped {@link DataSource} instance.
     *
     * @param targetDataSource
     *            the {@link DataSource} instance which {@link #unwrap()} will return (and hence all forwarded methods
     *            will use).
     */
    public DataSourceWrapper(DataSource targetDataSource) {
        setWrappee(targetDataSource);
    }

    /**
     * No-args constructor, which implies that you either need to invoke {@link #setWrappee(DataSource)} before
     * publishing the instance (making it available for other threads), or override {@link #unwrap()} to provide the
     * desired {@link DataSource} instance. In these cases, make sure to honor memory visibility semantics - i.e.
     * establish a happens-before edge between the setting of the instance and any other threads getting it.
     */
    public DataSourceWrapper() {
        /* no-op */
    }

    /**
     * Sets the wrapped {@link DataSource}, e.g. in case you instantiated it with the no-args constructor. <b>Do note
     * that the field holding the wrapped instance is not volatile nor synchronized</b>. This means that if you want to
     * set it after it has been published to other threads, you will have to override both this method and
     * {@link #unwrap()} to provide for needed memory visibility semantics, i.e. establish a happens-before edge between
     * the setting of the instance and any other threads getting it.
     *
     * @param targetDataSource
     *            the {@link DataSource} which is returned by {@link #unwrap()}, unless that is overridden.
     */
    public void setWrappee(DataSource targetDataSource) {
        _targetDataSource = targetDataSource;
    }

    /**
     * @return the wrapped {@link DataSource}. All forwarding methods invokes this method to get the wrapped
     *         {@link DataSource}, thus if you want to get creative wrt. how and when the DataSource is decided, you can
     *         override this method.
     */
    public DataSource unwrap() {
        if (_targetDataSource == null) {
            throw new IllegalStateException("DataSourceWrapper.getTarget():"
                    + " The target DataSource is not set!");
        }
        return _targetDataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return unwrap().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return unwrap().getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return unwrap().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        unwrap().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        unwrap().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return unwrap().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return unwrap().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            @SuppressWarnings("unchecked")
            T t = (T) this;
            return t;
        }
        return unwrap().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || unwrap().isWrapperFor(iface);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + " for target DataSource [" + unwrap() + "]";
    }
}
