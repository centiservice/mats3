/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.impl.jms;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;

/**
 * A dead simple implementation of {@link JmsMatsJmsSessionHandler} which does nothing of pooling nor connection
 * sharing. For StageProcessors (endpoints), this actually is one of the interesting options: Each StageProcessor has
 * its own Connection with a sole Session. But for Initiators, it is pretty bad: Each initiation constructs one
 * Connection (with a sole Session), and then closes the whole thing down after the initiation is done (message(s) is
 * sent).
 */
public class JmsMatsJmsSessionHandler_Simple implements JmsMatsJmsSessionHandler, JmsMatsStatics {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsJmsSessionHandler_Simple.class);

    protected final ConnectionFactory _jmsConnectionFactory;

    public static JmsMatsJmsSessionHandler_Simple create(ConnectionFactory connectionFactory) {
        return new JmsMatsJmsSessionHandler_Simple(connectionFactory);
    }

    protected JmsMatsJmsSessionHandler_Simple(ConnectionFactory jmsConnectionFactory) {
        _jmsConnectionFactory = jmsConnectionFactory;
    }

    @Override
    public JmsSessionHolder getSessionHolder(JmsMatsInitiator initiator) throws JmsMatsJmsException {
        JmsSessionHolder jmsSessionHolder = getSessionHolder_internal(initiator);
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "getSessionHolder(...) for Initiator [" + initiator
                + "], returning [" + jmsSessionHolder + "].");
        return jmsSessionHolder;
    }

    @Override
    public JmsSessionHolder getSessionHolder(JmsMatsStageProcessor<?, ?, ?> stageProcessor)
            throws JmsMatsJmsException {
        JmsSessionHolder jmsSessionHolder = getSessionHolder_internal(stageProcessor);
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "getSessionHolder(...) for StageProcessor [" + stageProcessor
                + "], returning [" + jmsSessionHolder + "].");
        return jmsSessionHolder;
    }

    protected AtomicInteger _numberOfOutstandingConnections = new AtomicInteger(0);

    @Override
    public int closeAllAvailableSessions() {
        /* nothing to do here, as each SessionHolder is an independent connection */
        // Directly return the number of outstanding connections.
        return _numberOfOutstandingConnections.get();
    }

    @Override
    public String getSystemInformation() {
        return "JMS Mats Simple Session Handler: " + this;
    }

    @Override
    public String toString() {
        return idThis();
    }

    protected JmsSessionHolder getSessionHolder_internal(JmsMatsTxContextKey txContextKey) throws JmsMatsJmsException {
        Connection jmsConnection;
        try {
            jmsConnection = _jmsConnectionFactory.createConnection();
        }
        catch (Throwable t) {
            throw new JmsMatsJmsException("Got problems when trying to create a new JMS Connection.", t);
        }
        // We now have an extra JMS Connection - "count it"
        _numberOfOutstandingConnections.incrementAndGet();

        // Starting it right away, as that could potentially also give "connection establishment" JMSExceptions
        try {
            jmsConnection.start();
        }
        catch (Throwable t) {
            try {
                _numberOfOutstandingConnections.decrementAndGet();
                jmsConnection.close();
            }
            catch (Throwable t2) {
                log.error(LOG_PREFIX + "Got " + t2.getClass().getSimpleName() + " when trying to close a JMS Connection"
                        + " after it failed to start. [" + jmsConnection + "]. Ignoring.", t);
            }
            throw new JmsMatsJmsException("Got problems when trying to start a new JMS Connection.", t);
        }

        // ----- The JMS Connection is gotten and started.

        // :: Create JMS Session and stick it in a Simple-holder
        Session jmsSession;
        try {
            jmsSession = jmsConnection.createSession(true, Session.SESSION_TRANSACTED);
        }
        catch (Throwable t) {
            try {
                _numberOfOutstandingConnections.decrementAndGet();
                jmsConnection.close();
            }
            catch (Throwable t2) {
                log.error(LOG_PREFIX + "Got " + t2.getClass().getSimpleName() + " when trying to close a JMS Connection"
                        + " after it failed to create a new Session. [" + jmsConnection + "]. Ignoring.", t2);
            }
            throw new JmsMatsJmsException(
                    "Got problems when trying to create a new JMS Session from a new JMS Connection ["
                            + jmsConnection + "].", t);
        }

        // :: Create The default MessageProducer, and then stick the Session and MessageProducer in a SessionHolder.
        try {
            MessageProducer messageProducer = jmsSession.createProducer(null);
            return new JmsSessionHolder_Simple(jmsConnection, jmsSession, messageProducer);
        }
        catch (Throwable t) {
            try {
                _numberOfOutstandingConnections.decrementAndGet();
                jmsConnection.close();
            }
            catch (Throwable t2) {
                log.error(LOG_PREFIX + "Got " + t2.getClass().getSimpleName() + " when trying to close a JMS Connection"
                        + " after it failed to create a new MessageProducer from a newly created JMS Session. ["
                        + jmsConnection + ", " + jmsSession + "]. Ignoring.", t2);
            }
            throw new JmsMatsJmsException("Got problems when trying to create a new MessageProducer from a new JMS"
                    + " Session [" + jmsSession + "] created from a new JMS Connection [" + jmsConnection + "].", t);
        }
    }

    private static final Logger log_holder = LoggerFactory.getLogger(JmsSessionHolder_Simple.class);

    public class JmsSessionHolder_Simple implements JmsSessionHolder {

        protected final Connection _jmsConnection;
        protected final Session _jmsSession;
        protected final MessageProducer _messageProducer;

        public JmsSessionHolder_Simple(Connection jmsConnection, Session jmsSession, MessageProducer messageProducer) {
            _jmsConnection = jmsConnection;
            _jmsSession = jmsSession;
            _messageProducer = messageProducer;
        }

        @Override
        public void isSessionOk() throws JmsMatsJmsException {
            if (_closedOrReleasedOrCrashed.get()) {
                throw new JmsMatsJmsException("SessionHolder is shut down.");
            }
            JmsMatsMessageBrokerSpecifics.isConnectionLive(_jmsConnection);
        }

        @Override
        public Session getSession() {
            if (log_holder.isDebugEnabled()) log_holder.debug(LOG_PREFIX + "getSession() on SessionHolder [" + this
                    + "], returning directly.");
            return _jmsSession;
        }

        @Override
        public MessageProducer getDefaultNoDestinationMessageProducer() {
            return _messageProducer;
        }

        protected AtomicBoolean _closedOrReleasedOrCrashed = new AtomicBoolean();

        @Override
        public void close() {
            boolean alreadyShutdown = _closedOrReleasedOrCrashed.getAndSet(true);
            if (alreadyShutdown) {
                log.info(LOG_PREFIX + "When trying to close [" + this + "], it was already closed,"
                        + " released or crashed.");
                return;
            }
            if (log_holder.isDebugEnabled()) log_holder.debug(LOG_PREFIX + "close() on SessionHolder [" + this
                    + "] - closing JMS Connection.");
            try {
                _numberOfOutstandingConnections.decrementAndGet();
                _jmsConnection.close();
            }
            catch (Throwable t) {
                log_holder.warn("Got problems when trying to close the JMS Connection due to .close() invoked.", t);
            }
        }

        @Override
        public void release() {
            boolean alreadyShutdown = _closedOrReleasedOrCrashed.getAndSet(true);
            if (alreadyShutdown) {
                log.info(LOG_PREFIX + "When trying to release [" + this + "], it was already closed,"
                        + " released or crashed.");
                return;
            }
            if (log_holder.isDebugEnabled()) log_holder.debug(LOG_PREFIX + "release() on SessionHolder [" + this
                    + "] - closing JMS Connection.");
            try {
                _numberOfOutstandingConnections.decrementAndGet();
                _jmsConnection.close();
            }
            catch (Throwable t) {
                log_holder.warn("Got problems when trying to close the JMS Connection due to .release() invoked.", t);
            }
        }

        @Override
        public void crashed(Throwable t) {
            boolean alreadyShutdown = _closedOrReleasedOrCrashed.getAndSet(true);
            if (alreadyShutdown) {
                log.info(LOG_PREFIX + "When trying to crash [" + this + "], it was already closed,"
                        + " released or crashed.");
                return;
            }
            if (log_holder.isDebugEnabled()) log_holder.debug(LOG_PREFIX + "crashed() on SessionHolder [" + this
                    + "] - closing JMS Connection.", t);
            try {
                _numberOfOutstandingConnections.decrementAndGet();
                _jmsConnection.close();
            }
            catch (Throwable t2) {
                log_holder.warn(LOG_PREFIX + "Got problems when trying to close the JMS Connection due to a"
                        + " \"JMS Crash\" (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ").", t2);
            }
        }
    }
}
