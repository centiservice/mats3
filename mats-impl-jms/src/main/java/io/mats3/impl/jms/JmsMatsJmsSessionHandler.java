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

import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;

/**
 * Interface for implementing JMS Connection and JMS Session handling. This can implement both different connection
 * sharing mechanisms, and should implement some kind of Session pooling for initiators. It can also implement logic for
 * "is this connection up?" mechanisms, to minimize the gap whereby a JMS Connection is in a bad state but the
 * application has not noticed this yet.
 * <p>
 * The reason for session pooling for initiators is that a JMS Session can only be used for one thread, and since
 * initiators are shared throughout the code base, one initiator might be used by several threads at the same time.
 * <p>
 * For reference wrt. cardinality between Sessions and Connections: In the JCA spec, it is specified that one JMS
 * Connection is used per one JMS Session (and in the JMS spec v2.0, this is "formalized" in that in the "simplified JMS
 * API" one have a new class JMSContext, which combines a Connection and Session in one). This will here mean that for
 * each {@link JmsMatsTxContextKey}, there shall be a unique JMS Connection (i.e. each StageProcessor has its own
 * Connection). It does makes some sense, though, that JMS Connections at least are shared for all StageProcessors for a
 * Stage - or even for all StageProcessors for all Stages of an Endpoint. Otherwise, a large system built on Mats will
 * use a pretty massive amount of Connections. However, sharing one Connection for the entire application/service (i.e.
 * for all endpoints in the JVM) might be a bit too heavy burden for a single JMS Connection.
 */
public interface JmsMatsJmsSessionHandler {
    /**
     * Will be invoked every time an Initiator wants to send a message - it will be returned after the message(s) is
     * sent.
     *
     * @param initiator
     *            the initiator in question.
     * @return a {@link JmsSessionHolder} instance - which is not the same as any other SessionHolders concurrently in
     *         use (but it may be pooled, so after a {@link JmsSessionHolder#release()}, it may be returned to another
     *         invocation again).
     * @throws JmsMatsJmsException
     *             if there was a problem getting a Connection. Problems getting a Sessions (e.g. the current Connection
     *             is broken) should be internally handled (i.e. try to get a new Connection), except if it can be
     *             determined that the problem getting a Session is of a fundamental nature (i.e. the credentials can
     *             get a Connection, but cannot get a Session - which would be pretty absurd, but hey).
     */
    JmsSessionHolder getSessionHolder(JmsMatsInitiator initiator) throws JmsMatsJmsException;

    /**
     * Will be invoked before the StageProcessor goes into its consumer loop - it will be closed once the Stage is
     * stopped, or if the Session "crashes", i.e. a method on Session or some downstream API throws an Exception.
     *
     * @param processor
     *            the StageProcessor in question.
     * @return a {@link JmsSessionHolder} instance - which is unique for each call.
     * @throws JmsMatsJmsException
     *             if there was a problem getting a Connection. Problems getting a Sessions (e.g. the current Connection
     *             is broken) should be internally handled (i.e. try to get a new Connection), except if it can be
     *             determined that the problem getting a Session is of a fundamental nature (i.e. the credentials can
     *             get a Connection, but cannot get a Session - which would be pretty absurd, but hey).
     */
    JmsSessionHolder getSessionHolder(JmsMatsStageProcessor<?, ?, ?> processor) throws JmsMatsJmsException;

    /**
     * Closes all <i>Available</i> Sessions, does not touch <i>Employed</i>. Net result is that Connections that do not
     * have any employed Sessions will be closed. The use case for this is to "clear out" the
     * {@link JmsMatsJmsSessionHandler} upon shutdown - the returned value is a count of how many Connections are still
     * alive after the operation, which should be 0.
     *
     * @return the number of Connections still alive after the operation (in the assumed use case, this should be zero).
     */
    int closeAllAvailableSessions();

    /**
     * Returns a plain text textual description of the Session Handler setup, meant for human consumption, for simple
     * introspection and monitoring. It will be a multi-line string, and should contain information about the setup.
     * It may many lines.
     *
     * @return a plain text textual description of the Session Handler setup, meant for human consumption, for simple
     *         introspection and monitoring.
     */
    String getSystemInformation();

    /**
     * A "sidecar object" for the JMS Session, so that additional stuff can be bound to it.
     */
    interface JmsSessionHolder {
        /**
         * Shall be invoked at these points, with the action to perform if it raises {@link JmsMatsJmsException}.
         * <ol>
         * <li>(For StageProcessors) Before going into MessageConsumer.receive() - if {@link JmsMatsJmsException} is
         * raised, {@link #close()} or {@link #crashed(Throwable)} shall be invoked, and then a new JmsSessionHolder
         * shall be fetched. [This is to be able to signal to the StageProcessor that the underlying Connection might
         * have become unstable - start afresh]</li>
         *
         * <li>(For StageProcessors and Initiators) Before committing any resources other than the JMS Session - if
         * {@link JmsMatsJmsException} is raised, rollback shall be performed, {@link #crashed(Throwable)} shall be
         * invoked, and then a new JmsSessionHolder shall be fetched. [This is to tighten the gap between typically the
         * DB commit and the JMS commit: Just before the DB is committed, an invocation to this method is performed. If
         * this goes OK, then the DB is committed and then the JMS Session is committed.]</li>
         * </ol>
         */
        void isSessionOk() throws JmsMatsJmsException;

        /**
         * @return the JMS Session. It will be the same instance every time.
         */
        Session getSession();

        /**
         * @return the default non-specific {@link MessageProducer} that goes along with {@link #getSession() the JMS
         *         Session}.
         */
        MessageProducer getDefaultNoDestinationMessageProducer();

        /**
         * For StageProcessors: This physically closes the JMS Session, and removes it from the pool-Connection, and
         * when all Sessions for a given pool-Connection is closed, the pool-Connection is closed.
         */
        void close();

        /**
         * For Initiators: This returns the JmsSessionHolder to the Session Pool for the underlying Connection.
         * <p />
         * <b>Note: It is allowed to call this in a finally block after use, so it must guard against already having
         * {@link #crashed(Throwable) crashed} when inside the {@link JmsMatsTransactionManager}</b>, in which case it
         * should probably effectively act as a no-op.
         */
        void release();

        /**
         * Notifies that a Session (or "downstream" consumer or producer) raised some exception - probably due to some
         * connectivity issues experienced as a JMSException while interacting with the JMS API, or because the
         * {@link JmsSessionHolder#isSessionOk()} returned {@code false}.
         * <p>
         * This should close and ditch the Session, then the SessionHandler should (semantically) mark the underlying
         * Connection as broken, and then then get all other "leasers" to come back with their sessions (close or
         * crash), so that the Connection can be closed. The leasers should then get a new session by
         * {@link JmsMatsJmsSessionHandler#getSessionHolder(JmsMatsStageProcessor)}, which will be based on a fresh
         * Connection.
         * <p>
         * NOTE: If a session comes back with "crashed", but it has already been "revoked" by the SessionHandler due to
         * another crash, this invocation should be equivalent to {@link #close()}, i.e. "come home as agreed upon,
         * whatever the state you are in".
         */
        void crashed(Throwable t);
    }
}
