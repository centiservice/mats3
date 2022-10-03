package io.mats3.impl.jms;

import java.sql.Connection;
import java.util.Optional;
import java.util.function.Supplier;

import javax.jms.MessageConsumer;

import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;

/**
 * This is an internal context object, for execution of initiations and stage processing in JMS-MATS - one instance is
 * made per initiation and per message reception. This is used to communicate back and forth between JmsMats proper, the
 * {@link JmsMatsJmsSessionHandler} and {@link JmsMatsTransactionManager}.
 *
 * @author Endre Stølsvik 2019-08-11 23:04 - http://stolsvik.com/, endre@stolsvik.com
 */
public class JmsMatsInternalExecutionContext {

    private final JmsSessionHolder _jmsSessionHolder;
    private final MessageConsumer _messageConsumer;

    static JmsMatsInternalExecutionContext forStage(JmsSessionHolder jmsSessionHolder,
            MessageConsumer messageConsumer) {
        return new JmsMatsInternalExecutionContext(jmsSessionHolder, messageConsumer);
    }

    static JmsMatsInternalExecutionContext forInitiation(JmsSessionHolder jmsSessionHolder) {
        return new JmsMatsInternalExecutionContext(jmsSessionHolder, null);
    }

    private JmsMatsInternalExecutionContext(JmsSessionHolder jmsSessionHolder, MessageConsumer messageConsumer) {
        _jmsSessionHolder = jmsSessionHolder;
        _messageConsumer = messageConsumer;
    }

    public JmsSessionHolder getJmsSessionHolder() {
        return _jmsSessionHolder;
    }

    /**
     * @return the {@link MessageConsumer} in effect if this is within a {@link JmsMatsStageProcessor}, returns
     *         Optional.empty() if this is within a {@link JmsMatsInitiator}.
     */
    public Optional<MessageConsumer> getMessageConsumer() {
        return Optional.ofNullable(_messageConsumer);
    }

    private Supplier<Connection> _sqlConnectionSupplier;
    private Supplier<Boolean> _sqlConnectionEmployedSupplier;

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

    private boolean _elideJmsCommit;

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

    // For transaction manager - if a user lambda throws, the "innermost" tx should log it, and then "outer" should not.
    private boolean _userLambdaExceptionLogged;

    public void setUserLambdaExceptionLogged() {
        _userLambdaExceptionLogged = true;
    }

    public boolean isUserLambdaExceptionLogged() {
        return _userLambdaExceptionLogged;
    }

    // ===== Timings

    private long _dbCommitNanos;
    private long _messageSystemCommitNanos;

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
