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

import java.sql.Connection;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;

import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;

/**
 * This is an internal context object, for execution of initiations and stage processing in JMS-Mats - one instance is
 * made per initiation and per message reception. This is used to communicate back and forth between JmsMats proper, the
 * {@link JmsMatsJmsSessionHandler} and {@link JmsMatsTransactionManager}.
 *
 * @author Endre Stølsvik 2019-08-11 23:04 - http://stolsvik.com/, endre@stolsvik.com
 */
public class JmsMatsInternalExecutionContext {

    private final JmsMatsFactory _jmsMatsFactory;

    private final JmsSessionHolder _jmsSessionHolder;

    // For initiation, the two below are null.

    private final JmsMatsStage<?, ?, ?> _jmsMatsStage;

    private final MessageConsumer _messageConsumer;

    static JmsMatsInternalExecutionContext forStage(JmsMatsFactory jmsMatsFactory,
            JmsSessionHolder jmsSessionHolder, JmsMatsStage<?, ?, ?> jmsMatsStage, MessageConsumer messageConsumer) {
        return new JmsMatsInternalExecutionContext(jmsMatsFactory, jmsSessionHolder, jmsMatsStage, messageConsumer);
    }

    static JmsMatsInternalExecutionContext forInitiation(JmsMatsFactory jmsMatsFactory,
            JmsSessionHolder jmsSessionHolder) {
        return new JmsMatsInternalExecutionContext(jmsMatsFactory, jmsSessionHolder, null, null);
    }

    private JmsMatsInternalExecutionContext(JmsMatsFactory jmsMatsFactory,
            JmsSessionHolder jmsSessionHolder, JmsMatsStage<?, ?, ?> jmsMatsStage, MessageConsumer messageConsumer) {
        _jmsMatsFactory = jmsMatsFactory;
        _jmsSessionHolder = jmsSessionHolder;
        _jmsMatsStage = jmsMatsStage;
        _messageConsumer = messageConsumer;
    }

    private Message _incomingJmsMessage;
    private int _incomingJmsMessageDeliveryCount;

    private Supplier<Connection> _sqlConnectionSupplier;
    private Supplier<Boolean> _sqlConnectionEmployedSupplier;
    private boolean _elideJmsCommit;

    private boolean _matsManagedDlqDivertEnabled = true; // Enabled until disabled.

    // For transaction manager - if a user lambda throws, the "innermost" tx should log it, and then "outer" should not.
    private boolean _userLambdaExceptionLogged;
    private long _dbCommitNanos;
    private long _messageSystemCommitNanos;

    public JmsMatsFactory getJmsMatsFactory() {
        return _jmsMatsFactory;
    }

    public JmsSessionHolder getJmsSessionHolder() {
        return _jmsSessionHolder;
    }

    /**
     * @return the {@link JmsMatsStage} in effect if this is within a {@link JmsMatsStageProcessor}, returns
     *         Optional.empty() if this is within a {@link JmsMatsInitiator}.
     */
    public Optional<JmsMatsStage<?, ?, ?>> getJmsMatsStage() {
        return Optional.ofNullable(_jmsMatsStage);
    }

    /**
     * @return the {@link MessageConsumer} in effect if this is within a {@link JmsMatsStageProcessor}, returns
     *         Optional.empty() if this is within a {@link JmsMatsInitiator}.
     */
    public Optional<MessageConsumer> getMessageConsumer() {
        return Optional.ofNullable(_messageConsumer);
    }

    void setStageIncomingJmsMessage(Message incomingJmsMessage, int incomingJmsMessageDeliveryCount) {
        _incomingJmsMessage = incomingJmsMessage;
        _incomingJmsMessageDeliveryCount = incomingJmsMessageDeliveryCount;
    }

    /**
     * @return the incoming JMS Message, if this is within a {@link JmsMatsStageProcessor}, returns Optional.empty() if
     *         this is within a {@link JmsMatsInitiator}.
     */
    Optional<Message> getIncomingJmsMessage() {
        return Optional.ofNullable(_incomingJmsMessage);
    }

    /**
     * @return the incoming JMS Message's delivery count (which starts at 1), if this is within a
     *         {@link JmsMatsStageProcessor}, returns 0 if this is within a {@link JmsMatsInitiator}, and return -1 if
     *         this is the delivery count is not available.
     */
    int getIncomingJmsMessageDeliveryCount() {
        return _incomingJmsMessageDeliveryCount;
    }

    /**
     * If the current {@link JmsMatsTransactionManager} is managing a SQL Connection, then it SHALL set a way to get the
     * current transactional SQL Connection, and a way to determine whether the SQL Connection was actually employed (if
     * this is not possible to determine, then return whether it was gotten).
     *
     * @param sqlConnectionSupplier
     *            a supplier for the current transactional SQL Connection
     * @param sqlConnectionEmployedSupplier
     *            a supplier for whether the SQL Connection was actually employed (if this is not possible to determine,
     *            then return whether it was gotten).
     */
    public void setSqlTxConnectionSuppliers(Supplier<Connection> sqlConnectionSupplier,
            Supplier<Boolean> sqlConnectionEmployedSupplier) {
        _sqlConnectionSupplier = sqlConnectionSupplier;
        _sqlConnectionEmployedSupplier = sqlConnectionEmployedSupplier;
    }

    /**
     * @return whether the {@link #setSqlTxConnectionSuppliers(Supplier, Supplier)} has been invoked.
     */
    boolean isUsingSqlHandlingTransactionManager() {
        return _sqlConnectionEmployedSupplier != null;
    }

    /**
     * Employed by {@link JmsMatsProcessContext#getAttribute(Class, String...)} and
     * {@link JmsMatsInitiate#getAttribute(Class, String...)} invocations to find SQL Connection.
     */
    Optional<Connection> getSqlConnection() {
        return _sqlConnectionSupplier == null
                ? Optional.empty()
                : Optional.of(_sqlConnectionSupplier.get());
    }

    /**
     * Used by JMS Mats to inform via logging whether the SQL Connection was actually employed (Notice that if this is
     * not possible to determine, it will return whether the SQL Connection was gotten). Read JavaDoc of
     * {@link #setSqlTxConnectionSuppliers(Supplier, Supplier)}.
     * <p/>
     * If the method {@link #setSqlTxConnectionSuppliers(Supplier, Supplier)} was not invoked, which is the case in a
     * non-SQL {@link JmsMatsTransactionManager}, like {@link JmsMatsTransactionManager_Jms}, this method will return
     * <code>false</code>.
     *
     * @return true if the SQL Connection was actually employed, or <code>false</code> if
     *         {@link #setSqlTxConnectionSuppliers(Supplier, Supplier)} was not invoked (i.e. in a non-SQL
     *         {@link JmsMatsTransactionManager} context.
     *
     * @see #setSqlTxConnectionSuppliers(Supplier, Supplier)
     */
    boolean wasSqlConnectionEmployed() {
        // The _sqlConnectionEmployedSupplier must both be set, AND return true, to return true.
        return _sqlConnectionEmployedSupplier != null && _sqlConnectionEmployedSupplier.get();
    }

    /**
     * Invoked by initiations if there is no point in committing JMS, since there was never produced any messages after
     * all.
     */
    void elideJmsCommitForInitiation() {
        _elideJmsCommit = true;
    }

    /**
     * @return whether {@link #elideJmsCommitForInitiation()} was invoked by an initiation, since no message was
     *         actually produced, thus nothing to commit.
     */
    boolean shouldElideJmsCommitForInitiation() {
        return _elideJmsCommit;
    }

    /**
     * In a stage processing, when we reach the point where we're about to send the outgoing messages, we set this flag.
     * This is used by the {@link JmsMatsTransactionManager} to determine whether to decide whether it can employ
     * MatsManagedDlqDivert (if reached the max delivery count), or whether it should just rollback the JMS Session. (If
     * 1 or more of the outgoing messages have been sent, then we cannot employ MatsManagedDlqDivert (which entails a
     * "manual" sending of the incoming message to the DLQ, and then commit), as that would mean that at least some of
     * the outgoing messages also would be committed along with the DLQing of the incoming message. (There's more
     * information in the comments of JmsMatsStageProcessor.checkForTooManyDeliveriesAndDivertToDlq(..))
     */
    void disableMatsManagedDlqDivert() {
        _matsManagedDlqDivertEnabled = false;
    }

    /**
     * @return whether it is still possible to do MatsManagedDlqDivert during processing? (Disabled when starting to
     * send JMS messages, as it after this point isn't possible to do Mats3-Managed DLQ Diverts).
     */
    boolean isMatsManagedDlqDivertStillEnabled() {
        return _matsManagedDlqDivertEnabled;
    }

    public void setUserLambdaExceptionLogged() {
        _userLambdaExceptionLogged = true;
    }

    public boolean isUserLambdaExceptionLogged() {
        return _userLambdaExceptionLogged;
    }

    // ===== Timings

    public void setDbCommitNanos(long dbCommitNanos) {
        _dbCommitNanos = dbCommitNanos;
    }

    public long getDbCommitNanos() {
        return _dbCommitNanos;
    }

    public void setMessageSystemCommitNanos(long messageSystemCommitNanos) {
        _messageSystemCommitNanos = messageSystemCommitNanos;
    }

    public long getMessageSystemCommitNanos() {
        return _messageSystemCommitNanos;
    }
}
