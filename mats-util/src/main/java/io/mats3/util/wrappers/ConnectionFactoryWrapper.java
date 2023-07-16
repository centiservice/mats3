package io.mats3.util.wrappers;

import io.mats3.MatsFactory.MatsWrapper;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;
import javax.jms.JMSException;

/**
 * A base Wrapper for a JMS {@link ConnectionFactory}, which simply implements ConnectionFactory, takes a
 * ConnectionFactory instance and forwards all calls to that. Meant to be extended to add extra functionality, e.g.
 * Spring integration.
 *
 * @author Endre Stølsvik 2019-06-10 11:43 - http://stolsvik.com/, endre@stolsvik.com
 */
public class ConnectionFactoryWrapper implements ConnectionFactory, MatsWrapper<ConnectionFactory> {

    /**
     * This field is private - if you in extensions need the instance, invoke {@link #unwrap()}. If you want to take
     * control of the wrapped ConnectionFactory instance, then override {@link #unwrap()}.
     */
    private ConnectionFactory _targetConnectionFactory;

    /**
     * Standard constructor, taking the wrapped {@link ConnectionFactory} instance.
     *
     * @param targetConnectionFactory
     *            the {@link ConnectionFactory} instance which {@link #unwrap()} will return (and hence all forwarded
     *            methods will use).
     */
    public ConnectionFactoryWrapper(ConnectionFactory targetConnectionFactory) {
        setWrappee(targetConnectionFactory);
    }

    /**
     * No-args constructor, which implies that you either need to invoke {@link #setWrappee(ConnectionFactory)} before
     * publishing the instance (making it available for other threads), or override {@link #unwrap()} to provide the
     * desired {@link ConnectionFactory} instance. In these cases, make sure to honor memory visibility semantics - i.e.
     * establish a happens-before edge between the setting of the instance and any other threads getting it.
     */
    public ConnectionFactoryWrapper() {
        /* no-op */
    }

    /**
     * Sets the wrapped {@link ConnectionFactory}, e.g. in case you instantiated it with the no-args constructor. <b>Do
     * note that the field holding the wrapped instance is not volatile nor synchronized</b>. This means that if you
     * want to set it after it has been published to other threads, you will have to override both this method and
     * {@link #unwrap()} to provide for needed memory visibility semantics, i.e. establish a happens-before edge between
     * the setting of the instance and any other threads getting it.
     *
     * @param targetConnectionFactory
     *            the {@link ConnectionFactory} which is returned by {@link #unwrap()}, unless that is overridden.
     */
    public void setWrappee(ConnectionFactory targetConnectionFactory) {
        _targetConnectionFactory = targetConnectionFactory;
    }

    /**
     * @return the wrapped {@link ConnectionFactory}. All forwarding methods invokes this method to get the wrapped
     *         {@link ConnectionFactory}, thus if you want to get creative wrt. how and when the ConnectionFactory is
     *         decided, you can override this method.
     */
    public ConnectionFactory unwrap() {
        if (_targetConnectionFactory == null) {
            throw new IllegalStateException("ConnectionFactoryWrapper.getTarget():"
                    + " The target ConnectionFactory is not set!");
        }
        return _targetConnectionFactory;
    }

    @Override
    public Connection createConnection() throws JMSException {
        return unwrap().createConnection();
    }

    @Override
    public Connection createConnection(String userName, String password) throws JMSException {
        return unwrap().createConnection(userName, password);
    }

    @Override
    public JMSContext createContext() {
        return unwrap().createContext();
    }

    @Override
    public JMSContext createContext(String userName, String password) {
        return unwrap().createContext(userName, password);
    }

    @Override
    public JMSContext createContext(String userName, String password, int sessionMode) {
        return unwrap().createContext(userName, password, sessionMode);
    }

    @Override
    public JMSContext createContext(int sessionMode) {
        return unwrap().createContext(sessionMode);
    }
}
