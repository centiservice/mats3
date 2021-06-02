package io.mats3.impl.jms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;

public class JmsMatsJmsSessionHandler_Pooling implements JmsMatsJmsSessionHandler, JmsMatsStatics {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsJmsSessionHandler_Pooling.class);

    protected final ConnectionFactory _jmsConnectionFactory;
    protected final PoolingKeyInitiator _poolingKeyInitiator;
    protected final PoolingKeyStageProcessor _poolingKeyStageProcessor;

    /**
     * What kind of sharing of JMS Connections to employ for a {@link JmsMatsInitiator}.
     */
    public enum PoolingKeyInitiator {
        /**
         * All initiators share a common JMS Connection.
         */
        FACTORY,

        /**
         * Each initiator gets its own JSM Connection. Notice that due to the prevalent use of
         * {@link MatsFactory#getDefaultInitiator()}, this is often equivalent to {@link #FACTORY}. (However, each
         * instance of the utility <code>MatsFuturizer</code> creates its own initiator).
         */
        INITIATOR
    }

    /**
     * What kind of sharing of JMS Connections to employ for a {@link JmsMatsStageProcessor}.
     */
    public enum PoolingKeyStageProcessor {
        /**
         * All StageProcessors in all Stages in all Endpoints share a common JMS Connection - i.e. a single Connection
         * for all consumers in the {@link JmsMatsFactory}.
         */
        FACTORY,

        /**
         * All StageProcessors in all Stages for each Endpoint share a common JMS Connection - i.e. every Endpoint has
         * its own JMS Connection.
         */
        ENDPOINT,

        /**
         * All StageProcessors in each Stage share a common JMS Connection - i.e. every Stage has its own JMS
         * Connection.
         */
        STAGE,

        /**
         * Each StageProcessor has its own JMS Connection - i.e. no sharing.
         */
        STAGE_PROCESSOR
    }

    protected Object derivePoolingKey(JmsMatsTxContextKey txContextKey) {
        // ?: Is this an Initiator, or a StageProcessor?
        if (txContextKey instanceof JmsMatsInitiator) {
            // -> Initiator
            switch (_poolingKeyInitiator) {
                case FACTORY:
                    // Factory: One Connection is shared for all Initiators
                    return txContextKey.getFactory();
                case INITIATOR:
                    // The Initiator itself: Each Initiator gets a separate Connection
                    return txContextKey;
            }
        }
        // E-> StageProcessor
        switch (_poolingKeyStageProcessor) {
            case FACTORY:
                // Factory: Every StageProcessors in the entire Factory shares a Connection
                return txContextKey.getStage().getParentEndpoint().getParentFactory();
            case ENDPOINT:
                // Endpoint: The StageProcessors for all Stages in one Endpoint shares a Connection
                return txContextKey.getStage().getParentEndpoint();
            case STAGE:
                // Stage: The StageProcessors in one Stage shares a Connection
                return txContextKey.getStage();
            case STAGE_PROCESSOR:
                // StageProcessor (i.e. the key itself): Each StageProcessor gets a separate Connection.
                return txContextKey;
        }

        // Shall not happen!
        throw new AssertionError("Did not manage to derive pooling key from txContextKey [" + txContextKey
                + "] with PoolingKeyInitiator[" + _poolingKeyInitiator + "] and PoolingKeyStageProcessor["
                + _poolingKeyStageProcessor + "].");
    }

    /**
     * Returns a JmsMatsJmsSessionHandler which employs a single JMS Connection for everything: Initiations, and all
     * Stages (consumers and producers).
     *
     * @param jmsConnectionFactory
     *            the JMS {@link ConnectionFactory} to get JMS Connections from.
     * @return a JmsMatsJmsSessionHandler which employs a single JMS Connection for everything: Initiations, and all
     *         consumers.
     */
    public static JmsMatsJmsSessionHandler_Pooling create(ConnectionFactory jmsConnectionFactory) {
        return new JmsMatsJmsSessionHandler_Pooling(jmsConnectionFactory, PoolingKeyInitiator.FACTORY,
                PoolingKeyStageProcessor.FACTORY);
    }

    /**
     * Returns a JmsMatsJmsSessionHandler which have specific pooling derivation.
     *
     * @param jmsConnectionFactory
     *            the JMS {@link ConnectionFactory} to get JMS Connections from.
     * @param poolingKeyInitiator
     *            what kind of JMS Connection sharing to employ for Initiators.
     * @param poolingKeyStageProcessor
     *            what kind of JMS Connection sharing to employ for StageProcessors.
     * @return a JmsMatsJmsSessionHandler which has the specified pooling derivation.
     */
    public static JmsMatsJmsSessionHandler_Pooling create(ConnectionFactory jmsConnectionFactory,
            PoolingKeyInitiator poolingKeyInitiator, PoolingKeyStageProcessor poolingKeyStageProcessor) {
        return new JmsMatsJmsSessionHandler_Pooling(jmsConnectionFactory, poolingKeyInitiator,
                poolingKeyStageProcessor);
    }

    protected JmsMatsJmsSessionHandler_Pooling(ConnectionFactory jmsConnectionFactory,
            PoolingKeyInitiator poolingKeyInitiator, PoolingKeyStageProcessor poolingKeyStageProcessor) {
        _jmsConnectionFactory = jmsConnectionFactory;
        _poolingKeyInitiator = poolingKeyInitiator;
        _poolingKeyStageProcessor = poolingKeyStageProcessor;
    }

    @Override
    public JmsSessionHolder getSessionHolder(JmsMatsInitiator<?> initiator) throws JmsMatsJmsException {
        return getSessionHolder_internal(initiator);
    }

    @Override
    public JmsSessionHolder getSessionHolder(JmsMatsStageProcessor<?, ?, ?, ?> stageProcessor)
            throws JmsMatsJmsException {
        return getSessionHolder_internal(stageProcessor);
    }

    @Override
    public int closeAllAvailableSessions() {
        log.info(LOG_PREFIX + "Closing all available SessionHolders in all ConnectionWithPools,"
                + " thus hoping to close all JMS Connections (Note: Each Session pool has a single Connection).");
        int liveConnectionsWithPoolBefore;
        int availableSessionsNowClosed = 0;
        int liveConnectionsWithPoolAfter;
        int employedSessions = 0;
        synchronized (this) {
            liveConnectionsWithPoolBefore = _connectionWithSessionPools_live.size();
            // Copying over the liveConnections, since it hopefully will be modified.
            ArrayList<ConnectionWithSessionPool> connWithSessionPools = new ArrayList<>(_connectionWithSessionPools_live
                    .values());
            // :: Iterate over the pools, summing up employed, closing all available
            for (ConnectionWithSessionPool currentPool : connWithSessionPools) {
                // :: Sum up employed, for logging.
                employedSessions += currentPool._employedSessionHolders.size();
                // :: Close all available
                // Copying over the availableSessionHolders, since it hopefully will be modified.
                ArrayList<JmsSessionHolderImpl> availableSessionHolders = new ArrayList<>(
                        currentPool._availableSessionHolders);
                availableSessionsNowClosed += availableSessionHolders.size();
                // Iterate over the available SessionHolders in each pool, closing them.
                for (JmsSessionHolderImpl availableHolder : availableSessionHolders) {
                    currentPool.internalCloseSession(availableHolder);
                }
            }

            // ----- Closed all available JmsSessionHolders

            liveConnectionsWithPoolAfter = _connectionWithSessionPools_live.size();
        }
        log.info(LOG_PREFIX + " \\- Before closing: Live ConnectionWithPools:[" + liveConnectionsWithPoolBefore
                + "] with total Employed Sessions:[" + employedSessions + "], and total Available Sessions:["
                + availableSessionsNowClosed + "] -> After: All Available Sessions closed, resulting in Live"
                + " ConnectionWithPools:[" + liveConnectionsWithPoolAfter + "]. NOTE: Employed Sessions hinders their"
                + " ConnectionWithPool and thus the pool's JMS Connection from being closed.");

        return liveConnectionsWithPoolAfter;
    }

    protected JmsSessionHolder getSessionHolder_internal(JmsMatsTxContextKey txContextKey) throws JmsMatsJmsException {
        // Get the pooling key.
        Object poolingKey = derivePoolingKey(txContextKey);

        // :: Get-or-create ConnectionAndSession - record if we created it, as we then need to create the JMS Connection
        boolean weCreatedConnectionWithSessionPool = false;
        ConnectionWithSessionPool connectionWithSessionPool;
        synchronized (this) {
            // Get the ConnectionWithSessionPool for the pooling key
            connectionWithSessionPool = _connectionWithSessionPools_live.get(poolingKey);
            // ?: Was there a ConnectionWithSessionPool on this pooling key?
            if (connectionWithSessionPool == null) {
                // -> No, no ConnectionWithSessionPool - so we must make it.
                // *This thread* must initialize this ConnectionWithSessionPool
                weCreatedConnectionWithSessionPool = true;
                // Now create it..
                connectionWithSessionPool = new ConnectionWithSessionPool(poolingKey);
                // .. and put it into the map for this pooling key.
                _connectionWithSessionPools_live.put(poolingKey, connectionWithSessionPool);
            }
        }

        // ?: Was *this thread* the creator of this ConnectionWithSessionPool?
        if (weCreatedConnectionWithSessionPool) {
            // -> Yes, so we must create the JMS Connection (Notice: Outside the synchronization)
            connectionWithSessionPool.initializePoolByCreatingJmsConnection(txContextKey);
        }

        // ----- Either we got an existing JMS Connection (or the not-us fetcher got Exception) - or we just created it.

        // crazy toString'ing, to get a nice log-line, but only if in debug to not waste cycles..
        String connectionWithSessionPool_ToStringBeforeDepool = null;
        if (log.isDebugEnabled()) {
            connectionWithSessionPool_ToStringBeforeDepool = connectionWithSessionPool.toString();
        }
        // Get-or-create a new SessionHolder. NOTE: Synchronized internally
        JmsSessionHolderImpl jmsSessionHolder = connectionWithSessionPool
                .getOrCreateAndEmploySessionHolder(txContextKey);

        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "getSessionHolder(...) for [" + txContextKey
                + "], derived pool [" + connectionWithSessionPool_ToStringBeforeDepool
                + "] -> returning created or depooled session [" + jmsSessionHolder + "], resulting in pool ["
                + connectionWithSessionPool + "].");
        return jmsSessionHolder;
    }

    // Map<PoolingKey, Pool>
    // Synchronized by /this/ (i.e. the JmsMatsJmsSessionHandler_Pooling instance)
    protected IdentityHashMap<Object, ConnectionWithSessionPool> _connectionWithSessionPools_live = new IdentityHashMap<>();
    // Map<PoolingKey, Pool>
    // Synchronized by /this/ (i.e. the JmsMatsJmsSessionHandler_Pooling instance)
    protected IdentityHashMap<Object, ConnectionWithSessionPool> _connectionWithSessionPools_crashed = new IdentityHashMap<>();

    protected class ConnectionWithSessionPool implements JmsMatsStatics {
        final Object _poolingKey;

        // Synchronized by /this/ (i.e. the ConnectionWithSessionPool instance)
        final Deque<JmsSessionHolderImpl> _availableSessionHolders = new ArrayDeque<>();
        // Synchronized by /this/ (i.e. the ConnectionWithSessionPool instance)
        final Set<JmsSessionHolderImpl> _employedSessionHolders = new HashSet<>();

        final CountDownLatch _creatingConnectionCountDownLatch = new CountDownLatch(1);

        ConnectionWithSessionPool(Object poolingKey) {
            _poolingKey = poolingKey;
        }

        Connection _jmsConnection;
        Throwable _exceptionWhenCreatingConnection;

        void initializePoolByCreatingJmsConnection(JmsMatsTxContextKey txContextKey) throws JmsMatsJmsException {
            try {
                Connection jmsConnection = _jmsConnectionFactory.createConnection();
                // Starting it right away, as that could conceivably also give "connection establishment" JMSExceptions
                jmsConnection.start();
                setConnectionOrException_ReleaseWaiters(jmsConnection, null);
            }
            catch (Throwable t) {
                // Got problems - set the Exception, so that any others that got waiting on connection can throw out.
                // Also, will remove the newly created ConnectionWithSessionPool. No-one can have made a Session, and
                // the next guy coming in should start anew.
                setConnectionOrException_ReleaseWaiters(null, t);
                synchronized (JmsMatsJmsSessionHandler_Pooling.this) {
                    _connectionWithSessionPools_live.remove(_poolingKey);
                }
                throw new JmsMatsJmsException("Got problems when trying to create & start a new JMS Connection.", t);
            }
        }

        void setConnectionOrException_ReleaseWaiters(Connection jmsConnection, Throwable t) {
            _jmsConnection = jmsConnection;
            _exceptionWhenCreatingConnection = t;
            _creatingConnectionCountDownLatch.countDown();
        }

        protected Connection getOrWaitForPoolJmsConnection() throws JmsMatsJmsException {
            try {
                boolean ok = _creatingConnectionCountDownLatch.await(30, TimeUnit.SECONDS);
                if (!ok) {
                    throw new JmsMatsJmsException("Waited too long for a Connection to appear in the"
                            + " ConnectionWithSessionPool instance.");
                }
                if (_exceptionWhenCreatingConnection != null) {
                    throw new JmsMatsJmsException("Someone else got Exception when they tried to create Connection.",
                            _exceptionWhenCreatingConnection);
                }
                else {
                    return _jmsConnection;
                }
            }
            catch (InterruptedException e) {
                throw new JmsMatsJmsException("Got interrupted while waiting for a Connection to appear in the"
                        + " ConnectionAndSession instance.", e);
            }
        }

        JmsSessionHolderImpl getOrCreateAndEmploySessionHolder(JmsMatsTxContextKey txContextKey)
                throws JmsMatsJmsException {
            synchronized (this) {
                JmsSessionHolderImpl availableSessionHolder = _availableSessionHolders.pollFirst();
                if (availableSessionHolder != null) {
                    availableSessionHolder.setCurrentContext("depooled,employed_by:" + txContextKey);
                    _employedSessionHolders.add(availableSessionHolder);
                    return availableSessionHolder;
                }
            }
            // ----- No, there was no SessionHolder available, so we must make a new session

            // NOTE: This is async, so while we make the JMS Session, another might come in. No problem..

            // :: Get the Pool's JMS Connection (will wait if not already in place)
            // NOTE: Might throw if it was attempted created by someone else (concurrently), which threw.
            Connection jmsConnection = getOrWaitForPoolJmsConnection();

            // :: Create a new JMS Session and stick it into a SessionHolder, and employ it.
            try {
                // Create JMS Session from JMS Connection
                Session jmsSession = jmsConnection.createSession(true, Session.SESSION_TRANSACTED);
                // Create the default MessageProducer
                MessageProducer messageProducer = jmsSession.createProducer(null);
                // Stick them into a SessionHolder
                JmsSessionHolderImpl jmsSessionHolder = new JmsSessionHolderImpl(txContextKey, this, jmsSession,
                        messageProducer);
                // Set context
                jmsSessionHolder.setCurrentContext("create_new,employed_by:" + txContextKey);
                // Employ it.
                synchronized (this) {
                    _employedSessionHolders.add(jmsSessionHolder);
                }
                // Return it.
                return jmsSessionHolder;
            }
            catch (Throwable t) {
                // Bad stuff - create Exception for throwing, and crashing entire ConnectionWithSessionPool
                JmsMatsJmsException e = new JmsMatsJmsException("Got problems when trying to create a new JMS"
                        + " Session from JMS Connection [" + jmsConnection + "].", t);
                // :: Crash this ConnectionWithSessionPool
                // Need a dummy JmsSessionHolderImpl (The JMS objects are not touched by the crashed() method).
                crashed(new JmsSessionHolderImpl(txContextKey, this, null, null), e);
                // Throw it out.
                throw e;
            }
        }

        protected volatile Exception _poolIsCrashed_StackTrace;

        /**
         * Invoked by SessionHolders when their {@link JmsSessionHolderImpl#close()} is invoked.
         *
         * @param jmsSessionHolder
         *            the session holder to be closed (also physically).
         */
        void close(JmsSessionHolderImpl jmsSessionHolder) {
            if (_poolIsCrashed_StackTrace != null) {
                log.info(LOG_PREFIX + "close() invoked from [" + jmsSessionHolder + "] on [" + this + "],"
                        + " but evidently the pool is already crashed. Removing SessionHolder from pool, cleaning."
                        + " Underlying JMS Connection is [" + id(_jmsConnection) + ":" + _jmsConnection + "]");
                jmsSessionHolder.setCurrentContext("crashed+closed");
                // NOTICE! Since the ConnectionWithSessionPool is already crashed, the JMS Connection is already closed,
                // which again implies that the JMS Session is already closed.
                // Thus, only need to remove the SessionHolder; the JMS Session is already closed.
                removeSessionHolderFromPool_And_DitchPoolIfEmpty(jmsSessionHolder);
                return;
            }
            // E-> Not already crashed, so close it nicely.
            log.info(LOG_PREFIX + "close() invoked from [" + jmsSessionHolder + "] on pool [" + this + "] "
                    + " -> removing from pool and then physically closing JMS Session."
                    + " Underlying JMS Connection is [" + id(_jmsConnection) + ":" + _jmsConnection + "]");
            internalCloseSession(jmsSessionHolder);
        }

        /**
         * Invoked by SessionHolders when their {@link JmsSessionHolderImpl#release()} is invoked.
         *
         * @param jmsSessionHolder
         *            the session holder to be returned.
         */
        void release(JmsSessionHolderImpl jmsSessionHolder) {
            // ?: Is the pool already crashed?
            if (_poolIsCrashed_StackTrace != null) {
                jmsSessionHolder.setCurrentContext("crashed+released");
                log.info(LOG_PREFIX + "release() invoked from [" + jmsSessionHolder + "] on [" + this
                        + "] , but evidently the pool is already crashed. Cleaning it out.");
                // NOTICE! Since the ConnectionWithSessionPool is already crashed, the JMS Connection is already closed,
                // which again implies that the JMS Session is already closed.
                // Thus, only need to remove the SessionHolder; the JMS Session is already closed.
                removeSessionHolderFromPool_And_DitchPoolIfEmpty(jmsSessionHolder);
                return;
            }
            // E-> Not already crashed, so enpool it.
            // crazy toString'ing, to get a nice log-line, but only if in debug to not waste cycles..
            String jmsSessionHolder_ToStringBeforeEnPool = null;
            String pool_ToStringBeforeEnPool = null;
            if (log.isDebugEnabled()) {
                jmsSessionHolder_ToStringBeforeEnPool = jmsSessionHolder.toString();
                pool_ToStringBeforeEnPool = this.toString();
            }
            synchronized (this) {
                _employedSessionHolders.remove(jmsSessionHolder);
                _availableSessionHolders.addFirst(jmsSessionHolder);
            }
            jmsSessionHolder.setCurrentContext("available");
            if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "release() invoked from [" +
                    jmsSessionHolder_ToStringBeforeEnPool + "] on pool [" + pool_ToStringBeforeEnPool
                    + "] -> moving from 'employed' to 'available' set, resulting in pool [" + this + "].");
        }

        /**
         * Invoked by SessionHolders when their {@link JmsSessionHolderImpl#crashed(Throwable)} is invoked.
         *
         * @param jmsSessionHolder
         *            the session holder that crashed, which will be closed (also physically)
         * @param reasonException
         *            the Exception that was deemed as a JMS crash.
         */
        void crashed(JmsSessionHolderImpl jmsSessionHolder, Throwable reasonException) {
            jmsSessionHolder.setCurrentContext("crashed");

            // ?: Are we already crashed?
            if (_poolIsCrashed_StackTrace != null) {
                // -> Yes, so then everything should already have been taken care of.
                log.info(LOG_PREFIX + "crashed() invoked from [" + jmsSessionHolder + "] on [" + this + "], but pool"
                        + " was already crashed and JMS Connection closed."
                        + " Underlying JMS Connection is [" + id(_jmsConnection) + ":" + _jmsConnection + "]");
                // NOTICE! Since the ConnectionWithSessionPool is already crashed, the JMS Connection is already closed,
                // which again implies that the JMS Session is already closed.
                // Thus, only need to remove the SessionHolder, the JMS Session is already closed.
                removeSessionHolderFromPool_And_DitchPoolIfEmpty(jmsSessionHolder);
                return;
            }

            // E-> Not already crashed, must crash now
            if (log.isWarnEnabled()) log.warn(LOG_PREFIX + "crashed() invoked from [" + jmsSessionHolder + "] on ["
                    + this + "] -> crashing: Marking pool as crashed, clearing available SessionHolders. If pool"
                    + " is empty of SessionHolders (i.e. employed == 0), then close JMS Connection."
                    + " Underlying JMS Connection is [" + id(_jmsConnection) + ":" + _jmsConnection + "]");

            // Lock both the whole Handler, and this ConnectionWithSessionPool instance, to avoid having threads sneak
            // by and getting either an available Session, or the Connection.
            // Lock order: Bigger to smaller objects.
            log.info(LOG_PREFIX + "Marking pool as crashed, clearing available SessionHolders, moving us"
                    + " from live to dead ConnectionWithSessionPool. [" + this + "].");
            assertBigToSmallLockOrder();
            synchronized (JmsMatsJmsSessionHandler_Pooling.this) {
                synchronized (this) {
                    // Crash this pool
                    _poolIsCrashed_StackTrace = new Exception("This [" + this + "] was crashed.", reasonException);
                    // Clear *available* SessionHolders. (Employed list will empty out eventually)
                    // NOTE: Closing JMS Connection unconditionally, and thus Sessions, outside of synch..
                    _availableSessionHolders.clear();
                    // Removing this SessionHolder from employed
                    boolean lastSessionFromPool = removeSessionHolderFromPool_And_DitchPoolIfEmpty(
                            jmsSessionHolder);
                    // NOTE: If it was the last session, it is already removed from live-set.
                    // ?: Was this the last session?
                    if (!lastSessionFromPool) {
                        // -> No, it was not the last session, so move this pool to the crashed-set
                        // Remove us from the live connections set.
                        _connectionWithSessionPools_live.remove(_poolingKey);
                        // Add us to the crashed set
                        _connectionWithSessionPools_crashed.put(_poolingKey, this);
                    }
                    /*
                     * NOTE: Any other employed SessionHolders will invoke isConnectionLive(), and find that it is not
                     * still active by getting a JmsMatsJmsException, thus come back with crashed(). Otherwise, they
                     * will also come get a JMS Exception from other JMS actions, and come back with crashed(). It could
                     * potentially also get a null from .receive(), and thus come back with close().
                     */
                }
            }
            // :: Now close the JMS Connection, since this was a crash, and we want to get rid of it.
            // Closing JMS Connection will per JMS API close all Sessions, Consumers and Producers.
            closeJmsConnection();
        }

        protected void assertBigToSmallLockOrder() {
            // If we at this point only have 'this' locked, and not "mother", then we're screwed.
            // Both none locked, and both locked, is OK.
            if (Thread.holdsLock(this) && (!Thread.holdsLock(JmsMatsJmsSessionHandler_Pooling.this))) {
                throw new AssertionError("When locking both '"
                        + JmsMatsJmsSessionHandler_Pooling.class.getSimpleName()
                        + "' and '" + ConnectionWithSessionPool.class.getSimpleName() + "', one shall not"
                        + " start by having the pool locked, as that is the smaller, and the defined"
                        + " locking order is big to small.");
            }
        }

        protected void internalCloseSession(JmsSessionHolderImpl jmsSessionHolder) {
            jmsSessionHolder.setCurrentContext("closed");
            // Remove this SessionHolder from pool, and remove ConnectionWithSessionPool if empty (if so, returns true)
            boolean lastSessionSoCloseConnection = removeSessionHolderFromPool_And_DitchPoolIfEmpty(
                    jmsSessionHolder);
            // ?: Was this the last SessionHolder in use?
            if (lastSessionSoCloseConnection) {
                // -> Yes, last SessionHolder in this ConnectionWithSessionPool, so close the actual JMS Connection
                // (NOTICE! This will also close any JMS Sessions, specifically the one in the closing SessionHolder)
                // (NOTICE! The Connection will already have been removed from the pool in the above method invocation)
                closeJmsConnection();
            }
            else {
                // -> No, not last SessionHolder, so just close this SessionHolder's actual JMS Session
                try {
                    jmsSessionHolder._jmsSession.close();
                }
                catch (Throwable t) {
                    // Bad stuff - create Exception for throwing, and crashing entire ConnectionWithSessionPool
                    JmsMatsJmsException e = new JmsMatsJmsException("Got problems when trying to close JMS Session ["
                            + jmsSessionHolder._jmsSession + "] from [" + jmsSessionHolder + "].", t);
                    // Crash this ConnectionWithSessionPool
                    crashed(jmsSessionHolder, e);
                    // Not throwing on, per contract.
                }
            }
        }

        protected boolean removeSessionHolderFromPool_And_DitchPoolIfEmpty(
                JmsSessionHolderImpl jmsSessionHolder) {
            log.info(LOG_PREFIX + "Removing [" + jmsSessionHolder + "] from pool [" + this + "].");

            // Lock both the whole Handler, and this ConnectionWithSessionPool instance, to avoid having threads sneak
            // by and getting either an available Session, or the Connection.
            // Lock order: Bigger to smaller objects.
            assertBigToSmallLockOrder();
            boolean ret;
            synchronized (JmsMatsJmsSessionHandler_Pooling.this) {
                synchronized (this) {
                    // Remove from employed (this is the normal place a SessionHolder live)
                    _employedSessionHolders.remove(jmsSessionHolder);
                    // Remove from available (this is where a SessionHolder lives if the pool is shutting down)
                    _availableSessionHolders.remove(jmsSessionHolder);
                    // ?: Is the ConnectionWithSessionPool now empty?
                    if (_employedSessionHolders.isEmpty() && _availableSessionHolders.isEmpty()) {
                        // -> Yes, none in either employed nor available set.
                        // Remove us from live map, if this is where this ConnectionWithSessionPool resides
                        _connectionWithSessionPools_live.remove(_poolingKey);
                        // Remove us fom dead map, if this is where this ConnectionWithSessionPool resides
                        _connectionWithSessionPools_crashed.remove(_poolingKey);
                        // We removed the ConnectionWithSessionPool - so close the actual JMS Connection.
                        ret = true;
                    }
                    else {
                        // -> We did not remove the ConnectionWithSessionPool, so keep the JMS Connection open.
                        return false;
                    }
                }
            }
            if (ret) {
                log.info(LOG_PREFIX + "Pool was empty of Sessions, so removed it from the pool-sets [" + this + "].");
            }
            return ret;
        }

        protected void closeJmsConnection() {
            log.info(LOG_PREFIX + "Closing JMS Connection [" + _jmsConnection + "] for pool [" + this + "].");
            try {
                _jmsConnection.close();
            }
            catch (Throwable t) {
                log.info(LOG_PREFIX + "Got a [" + t.getClass().getSimpleName()
                        + "] when trying to close JMS Connection for pool [" + this + "]. Ignoring.", t);
            }
        }

        /**
         * Will be invoked by all SessionHolders at various times in {@link JmsMatsStageProcessor}.
         */
        void isConnectionLive(JmsSessionHolder jmsSessionHolder) throws JmsMatsJmsException {
            if (_poolIsCrashed_StackTrace != null) {
                throw new JmsMatsJmsException("When checking if [" + jmsSessionHolder + "] was live, we found that the"
                        + " pool's underlying JMS Connection had crashed with ["
                        + _poolIsCrashed_StackTrace.getCause().getClass().getSimpleName() + "]. JMS Connection: ["
                        + id(_jmsConnection) + ":" + _jmsConnection + "].");
            }
            JmsMatsMessageBrokerSpecifics.isConnectionLive(_jmsConnection);
        }

        @Override
        public String toString() {
            int available, employed;
            synchronized (this) {
                available = _availableSessionHolders.size();
                employed = _employedSessionHolders.size();
            }
            return idThis() + "{pool:" + (_poolIsCrashed_StackTrace == null ? "live" : "crashed") + "|sess avail:"
                    + available + ";empl:" + employed + "}";
        }
    }

    public static class JmsSessionHolderImpl implements JmsSessionHolder, JmsMatsStatics {
        protected final ConnectionWithSessionPool _connectionWithSessionPool;
        protected final Session _jmsSession;
        protected final MessageProducer _messageProducer;

        public JmsSessionHolderImpl(JmsMatsTxContextKey txContextKey,
                ConnectionWithSessionPool connectionWithSessionPool,
                Session jmsSession,
                MessageProducer messageProducer) {
            _currentContext = txContextKey;
            _connectionWithSessionPool = connectionWithSessionPool;
            _jmsSession = jmsSession;
            _messageProducer = messageProducer;
        }

        protected Object _currentContext;

        protected void setCurrentContext(Object currentContext) {
            _currentContext = currentContext;
        }

        @Override
        public void isSessionOk() throws JmsMatsJmsException {
            _connectionWithSessionPool.isConnectionLive(this);
        }

        @Override
        public Session getSession() {
            return _jmsSession;
        }

        @Override
        public MessageProducer getDefaultNoDestinationMessageProducer() {
            return _messageProducer;
        }

        protected AtomicBoolean _closedOrCrashed = new AtomicBoolean();

        @Override
        public void close() {
            boolean alreadyClosedOrCrashed = _closedOrCrashed.getAndSet(true);
            if (alreadyClosedOrCrashed) {
                log.info(LOG_PREFIX + "When about to close [" + this + " from " + _connectionWithSessionPool
                        + "], it was already closed or crashed. Ignoring.");
                return;
            }
            _connectionWithSessionPool.close(this);
        }

        @Override
        public void release() {
            /*
             * NOTE! The JmsSessionHolder is a shared object, which is the one that resides in the pool.
             */
            // :: Check whether it was already closed or crashed, in which case we must ignore the release call.
            // ?: Already closed or crashed?
            boolean alreadyClosedOrCrashed = _closedOrCrashed.get();
            if (alreadyClosedOrCrashed) {
                // -> Yes, already closed or crashed, so ignore the release.
                log.info(LOG_PREFIX + "When about to release [" + this + " from " + _connectionWithSessionPool
                        + "], it was already closed or crashed. Ignoring.");
                return;
            }
            // NOTE! NOT setting the "closed or crashed" boolean here, since the JmsSessionHolder is a shared object,
            // and not a "single use proxy" as e.g. a pooled SQL Connection typically is.
            _connectionWithSessionPool.release(this);
        }

        @Override
        public void crashed(Throwable t) {
            boolean alreadyClosedOrCrashed = _closedOrCrashed.getAndSet(true);
            if (alreadyClosedOrCrashed) {
                log.info(LOG_PREFIX + "When about to crash [" + this + " from " + _connectionWithSessionPool
                        + "], it was already closed or crashed. Ignoring.");
                return;
            }
            _connectionWithSessionPool.crashed(this, t);
        }

        @Override
        public String toString() {
            return idThis() + ";ctx:" + _currentContext;
        }
    }
}
