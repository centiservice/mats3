package io.mats3.impl.jms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsInitiator;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling.PoolingKeyInitiator;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling.PoolingKeyStageProcessor;
import io.mats3.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;

/**
 * Second round of pooling implementation of {@link JmsMatsJmsSessionHandler} which serializes the creation of JMS
 * Connections to avoid thundering herds (An improvement upon {@link JmsMatsJmsSessionHandler_Pooling}). Upon
 * {@link #create(ConnectionFactory, PoolingKeyInitiator, PoolingKeyStageProcessor) creation}, you decide how
 * Connections are shared for {@link JmsMatsStageProcessor}s and {@link MatsInitiator}s.
 *
 * @author Endre Stølsvik 2023-12-06 22:07 - http://stolsvik.com/, endre@stolsvik.com
 */
public class JmsMatsJmsSessionHandler_PoolingSerial implements JmsMatsJmsSessionHandler, JmsMatsStatics {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsJmsSessionHandler_PoolingSerial.class);

    protected final ConnectionFactory _jmsConnectionFactory;
    protected final PoolingKeyInitiator _poolingKeyInitiator;
    protected final PoolingKeyStageProcessor _poolingKeyStageProcessor;

    /**
     * Returns a JmsMatsJmsSessionHandler which uses the {@link PoolingKeyInitiator#INITIATOR INITIATOR} pooling key for
     * Intitiators (i.e. a JMS Connection per Initiator), and {@link PoolingKeyStageProcessor#FACTORY FACTORY} pooling
     * key for Endpoints (i.e. a JMS Connection per Endpoint).
     * <p/>
     * Note that is seems like ActiveMQ is not all that great at multiplexing multiple sessions and consumers over the
     * same connection. You get lower latency by using {@link PoolingKeyStageProcessor#STAGE STAGE} and even better with
     * {@link PoolingKeyStageProcessor#STAGE_PROCESSOR STAGE_PROCESSOR}, but when your set of services using Mats gets
     * large, this results in an awful lot of connections.
     *
     * @param jmsConnectionFactory
     *            the JMS {@link ConnectionFactory} to get JMS Connections from.
     * @return a JmsMatsJmsSessionHandler which employs a single JMS Connection for everything: Initiations, and all
     *         consumers.
     */
    public static JmsMatsJmsSessionHandler_PoolingSerial create(ConnectionFactory jmsConnectionFactory) {
        return new JmsMatsJmsSessionHandler_PoolingSerial(jmsConnectionFactory, PoolingKeyInitiator.INITIATOR,
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
    public static JmsMatsJmsSessionHandler_PoolingSerial create(ConnectionFactory jmsConnectionFactory,
            PoolingKeyInitiator poolingKeyInitiator, PoolingKeyStageProcessor poolingKeyStageProcessor) {
        return new JmsMatsJmsSessionHandler_PoolingSerial(jmsConnectionFactory, poolingKeyInitiator,
                poolingKeyStageProcessor);
    }

    protected JmsMatsJmsSessionHandler_PoolingSerial(ConnectionFactory jmsConnectionFactory,
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
                + " ConnectionWithPool from being cleaned fully, and thus hinders the pool's JMS Connection from being"
                + " closed.");

        return liveConnectionsWithPoolAfter;
    }

    // Synched by _connectionCreationSerializer
    private int _failedAttemptCounter = 0;

    private final ReentrantLock _connectionCreationSerializerLock = new ReentrantLock();

    protected JmsSessionHolder getSessionHolder_internal(JmsMatsTxContextKey txContextKey) throws JmsMatsJmsException {
        // :: First check whether we have a ConnectionWithSessionPool for this context key.

        // Derive the pooling key.
        Object poolingKey = JmsMatsJmsSessionHandler_Pooling.derivePoolingKey(txContextKey, _poolingKeyInitiator,
                _poolingKeyStageProcessor);

        // Get the ConnectionWithSessionPool for this pooling key, if any.
        ConnectionWithSessionPool connectionWithSessionPool_x;
        synchronized (this) {
            connectionWithSessionPool_x = _connectionWithSessionPools_live.get(poolingKey);
        }
        // ?: Did we get a ConnectionWithSessionPool for this pooling key?
        if (connectionWithSessionPool_x != null) {
            // -> Yes, we got a ConnectionWithSessionPool for this pooling key - return session from it.
            return connectionWithSessionPool_x.getOrCreateAndEmploySessionHolder(txContextKey);
        }

        // E-> No, there was no ConnectionWithSessionPool for this pooling key. We must create it.

        // :: Go into the "connection creation serializer" lock, to serialize creation of JMS Connections.

        /*
         * We want to serialize creation of JMS Connections, so that only one thread does it at a time - this to avoid
         * the "thundering herd" problem when the MQ has crashed, and all threads try to recreate the JMS Connection at
         * the same time. Additionally, we want a global backoff, so that if we have problems creating JMS Connections,
         * we do not have a bunch of threads trying to create it at the same time, and failing, and spamming the logs.
         */
        if (log.isTraceEnabled()) log.trace("Entering lock for '_connectionCreationSerializerLock',"
                + " context: [" + txContextKey + "], poolingKey: [" + poolingKey + "].");
        try {
            _connectionCreationSerializerLock.lockInterruptibly();
        }
        catch (InterruptedException e) {
            throw new JmsMatsJmsException("Got interrupted while waiting in line to create a JMS Connection for this "
                    + this.getClass().getSimpleName() + " instance with context [" + txContextKey + "], pooling key ["
                    + poolingKey + "].", e);
        }
        ConnectionWithSessionPool connectionWithSessionPool;
        try {
            if (log.isTraceEnabled()) log.trace("ENTERED lock for '_connectionCreationSerializerLock',"
                    + " context: [" + txContextKey + "], poolingKey: [" + poolingKey + "].");
            // :: Double-check again, now that we're inside the lock (previous thread exiting might have created it).
            synchronized (this) {
                connectionWithSessionPool_x = _connectionWithSessionPools_live.get(poolingKey);
            }
            // ?: Did we get a ConnectionWithSessionPool for this pooling key?
            if (connectionWithSessionPool_x != null) {
                // -> Yes, we got a ConnectionWithSessionPool for this pooling key - return session from it.
                return connectionWithSessionPool_x.getOrCreateAndEmploySessionHolder(txContextKey);
            }

            // E-> Not present, so we must create it.

            // :: Wait for backoff, and then create the JMS Connection
            // ?: If we're in a failed state, we wait exponentially longer and longer, but max 2 minutes.
            if (_failedAttemptCounter > 0) {
                // Wait exponentially, but max 2 minutes.
                long sleepTime = Math.min(2 * 60 * 1000, (long) (Math.pow(1.5, _failedAttemptCounter)
                        * 50));
                log.info(LOG_PREFIX + "Sleeping for [" + sleepTime + "] ms - failedAttempts: ["
                        + _failedAttemptCounter + "], context: [" + txContextKey + "], poolingKey: ["
                        + poolingKey + "].");
                try {
                    Thread.sleep(sleepTime);
                }
                catch (InterruptedException e) {
                    throw new JmsMatsJmsException("Got interrupted while waiting in line to create a JMS"
                            + " Connection for this " + this.getClass().getSimpleName() + " instance.", e);
                }
            }

            try {
                // Now create it.. THROWS if not possible to create JMS Connection!
                connectionWithSessionPool = new ConnectionWithSessionPool(poolingKey);
            }
            catch (Throwable t) {
                // Increasing fail-counter, so that next threads will wait longer and longer if this doesn't clear.
                _failedAttemptCounter++;
                if (log.isTraceEnabled()) log.trace("Creation of JMS Connection failed, context: ["
                        + txContextKey + "], poolingKey: [" + poolingKey + "], failedAttempts is now ["
                        + _failedAttemptCounter + "].", t);
                throw t;
            }

            log.info("Creation of JMS Connection succeeded" +
                    (_failedAttemptCounter > 0 ? " on [" + _failedAttemptCounter + "]th attempt" : "") +
                    ", context: [" + txContextKey + "], poolingKey: [" + poolingKey + "], connection:"
                    + " [" + connectionWithSessionPool._jmsConnection + "].");

            // Reset fail-counter to 0, letting next thread avoid wait.
            _failedAttemptCounter = 0;

            // :: Finish by "publishing" the ConnectionWithSessionPool, so that next thread will find it.
            // (It can now find it "shortcut" at top of method)
            synchronized (this) {
                _connectionWithSessionPools_live.put(poolingKey, connectionWithSessionPool);
            }
        }
        finally {
            // :: Release the "connection creation serializer" lock
            _connectionCreationSerializerLock.unlock();
        }

        // Get-or-create a new SessionHolder. NOTE: Synchronized internally
        return connectionWithSessionPool.getOrCreateAndEmploySessionHolder(txContextKey);
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

        final Connection _jmsConnection;

        ConnectionWithSessionPool(Object poolingKey) throws JmsMatsJmsException {
            _poolingKey = poolingKey;
            try {
                Connection jmsConnection = _jmsConnectionFactory.createConnection();
                // Starting it right away, as that could conceivably also give "connection establishment" JMSExceptions
                jmsConnection.start();
                _jmsConnection = jmsConnection;
            }
            catch (Throwable t) {
                // Got problems - set the Exception, so that any others that got waiting on connection can throw out.
                // Also, will remove the newly created ConnectionWithSessionPool. No-one can have made a Session, and
                // the next guy coming in should start anew.
                throw new JmsMatsJmsException("Got problems when trying to create & start a new JMS Connection.", t);
            }
        }

        JmsSessionHolderImpl getOrCreateAndEmploySessionHolder(JmsMatsTxContextKey txContextKey)
                throws JmsMatsJmsException {
            // crazy toString'ing, to get a nice log-line, but only if in debug to not waste cycles..
            String connectionWithSessionPool_ToStringBeforeDepool = null;
            if (log.isDebugEnabled()) {
                connectionWithSessionPool_ToStringBeforeDepool = toString();
            }
            // Get-or-create a new SessionHolder. NOTE: Synchronized internally
            JmsSessionHolderImpl jmsSessionHolder = getOrCreateAndEmploySessionHolder_internal(txContextKey);

            if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "getSessionHolder(...) for [" + txContextKey
                    + "], derived pool [" + connectionWithSessionPool_ToStringBeforeDepool
                    + "] -> returning created or depooled session [" + jmsSessionHolder + "], resulting in pool ["
                    + this.toString() + "].");
            return jmsSessionHolder;
        }

        private JmsSessionHolderImpl getOrCreateAndEmploySessionHolder_internal(JmsMatsTxContextKey txContextKey)
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

            // :: Create a new JMS Session and stick it into a SessionHolder, and employ it.
            try {
                // Create JMS Session from JMS Connection
                Session jmsSession = _jmsConnection.createSession(true, Session.SESSION_TRANSACTED);
                // Create the default MessageProducer
                MessageProducer messageProducer = jmsSession.createProducer(null);
                // Stick them into a SessionHolder
                JmsSessionHolderImpl jmsSessionHolder = new JmsSessionHolderImpl(this, jmsSession,
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
                        + " Session from JMS Connection [" + _jmsConnection + "].", t);
                // :: Crash this ConnectionWithSessionPool
                // Need a dummy JmsSessionHolderImpl (The JMS objects are not touched by the crashed() method).
                sessionCrashed(new JmsSessionHolderImpl(this, null, null), e);
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
        void closeSession(JmsSessionHolderImpl jmsSessionHolder) {
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
        void releaseSession(JmsSessionHolderImpl jmsSessionHolder) {
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
        void sessionCrashed(JmsSessionHolderImpl jmsSessionHolder, Throwable reasonException) {
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
            // Synch to access live and crashed maps
            synchronized (JmsMatsJmsSessionHandler_PoolingSerial.this) {
                // Synch to modify this ConnectionWithSessionPool
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
            // (Those should then come back either because they receive null, or check the isConnectionLive()).
            closeJmsConnection();
        }

        protected void assertBigToSmallLockOrder() {
            // If we at this point only have 'this' locked, and not "mother", then we're screwed.
            // Allowed: none locked, "mother" without this locked, and both locked.
            if (Thread.holdsLock(this) && (!Thread.holdsLock(JmsMatsJmsSessionHandler_PoolingSerial.this))) {
                throw new AssertionError("When locking both '"
                        + JmsMatsJmsSessionHandler_PoolingSerial.class.getSimpleName()
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
                    sessionCrashed(jmsSessionHolder, e);
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
            // Synch to access live and crashed maps
            synchronized (JmsMatsJmsSessionHandler_PoolingSerial.this) {
                // Synch to modify this ConnectionWithSessionPool
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
                    }
                    else {
                        // -> We did not remove the ConnectionWithSessionPool, so keep the JMS Connection open.
                        return false;
                    }
                }
            }
            log.info(LOG_PREFIX + "Pool was empty of Sessions, so removed it from the pool-sets [" + this + "].");
            return true;
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

        public JmsSessionHolderImpl(ConnectionWithSessionPool connectionWithSessionPool,
                Session jmsSession,
                MessageProducer messageProducer) {
            _connectionWithSessionPool = connectionWithSessionPool;
            _jmsSession = jmsSession;
            _messageProducer = messageProducer;
        }

        protected String _currentContext;

        protected void setCurrentContext(String currentContext) {
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
            _connectionWithSessionPool.closeSession(this);
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
            _connectionWithSessionPool.releaseSession(this);
        }

        @Override
        public void crashed(Throwable t) {
            boolean alreadyClosedOrCrashed = _closedOrCrashed.getAndSet(true);
            if (alreadyClosedOrCrashed) {
                log.info(LOG_PREFIX + "When about to crash [" + this + " from " + _connectionWithSessionPool
                        + "], it was already closed or crashed. Ignoring.");
                return;
            }
            _connectionWithSessionPool.sessionCrashed(this, t);
        }

        @Override
        public String toString() {
            return idThis() + ";ctx:" + _currentContext;
        }
    }
}
