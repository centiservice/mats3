package io.mats3.impl.jms;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException;

/**
 * Implementation of {@link JmsMatsTransactionManager} that in addition to the JMS transaction also handles a JDBC SQL
 * {@link Connection} (using only pure java, i.e. no Spring) for which it keeps transaction demarcation along with the
 * JMS transaction, by means of <i>"Best Effort 1 Phase Commit"</i>:
 * <ol>
 * <li><b>JMS transaction is entered</b> (a transactional JMS Connection is always within a transaction)
 * <li>JMS Message is retrieved.
 * <li><b>SQL transaction is entered</b>
 * <li>Code is executed, including SQL statements.
 * <li><b>SQL transaction is committed - <font color="red">Any errors also rollbacks the JMS Transaction, so that none
 * of them have happened.</font></b>
 * <li><b>JMS transaction is committed.</b>
 * </ol>
 * Out of that order, one can see that if SQL transaction becomes committed, and then the JMS transaction fails, this
 * will be a pretty bad situation. However, of those two transactions, the SQL transaction is absolutely most likely to
 * fail, as this is where you can have business logic failures, concurrency problems (e.g. MS SQL's "Deadlock Victim"),
 * integrity constraints failing etc - that is, failures in both logic and timing. On the other hand, the JMS
 * transaction (which effectively boils down to <i>"yes, I received this message"</i>) is much harder to fail, where the
 * only situation where it can fail is due to infrastructure/hardware failures (exploding server / full disk on Message
 * Broker). This is called "Best Effort 1PC", and is nicely explained in <a href=
 * "http://www.javaworld.com/article/2077963/open-source-tools/distributed-transactions-in-spring--with-and-without-xa.html?page=2">
 * this article</a>. If this failure occurs, it will be caught and logged on ERROR level (by
 * {@link JmsMatsTransactionManager_Jms}) - and then the Message Broker will probably try to redeliver the message. Also
 * read the <a href="http://activemq.apache.org/should-i-use-xa.html">Should I use XA Transactions</a> from Apache
 * Active MQ.
 * <p>
 * Wise tip when working with <i>Message Oriented Middleware</i>: Code idempotent! Handle double-deliveries!
 * <p>
 * The transactionally demarcated SQL Connection can be retrieved from user code using
 * {@link ProcessContext#getAttribute(Class, String...) ProcessContext.getAttribute(Connection.class)}.
 * <p>
 * It requires a {@link DataSource} upon construction. The {@link DataSource} will be asked for a SQL Connection in any
 * MatsStage's StageProcessor that requires it: The fetching of the SQL Connection is lazy in that it won't be retrieved
 * (nor entered into transaction with), until it is actually requested by the user code by means of
 * <code>ProcessContext.getAttribute(Connection.class)</code>.
 * <p>
 * The SQL Connection will be {@link Connection#close() closed} after each stage processing (after each transaction,
 * either committed or rollbacked) - if it was requested during the user code.
 * <p>
 * This implementation will not perform any Connection reuse (caching/pooling). It is up to the supplier to implement
 * any pooling, or make use of a pooled DataSource, if so desired. (Which definitely should be desired, due to the heavy
 * use of <i>"get new - use - commit/rollback - close"</i>.)
 *
 * @author Endre Stølsvik - 2015-12-06 - http://endre.stolsvik.com
 */
public class JmsMatsTransactionManager_JmsAndJdbc extends JmsMatsTransactionManager_Jms {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsTransactionManager_JmsAndJdbc.class);

    private final DataSource _dataSource;

    public static JmsMatsTransactionManager_JmsAndJdbc create(DataSource dataSource) {
        return new JmsMatsTransactionManager_JmsAndJdbc(dataSource);
    }

    protected JmsMatsTransactionManager_JmsAndJdbc(DataSource dataSource) {
        super();
        _dataSource = dataSource;
    }

    @Override
    public TransactionContext getTransactionContext(JmsMatsTxContextKey txContextKey) {
        return new TransactionalContext_JmsAndJdbc(_dataSource, txContextKey);
    }

    @Override
    public String getSystemInformation() {
        return "JMS Mats TransactionManager with plain JDBC: "+idThis()
                + "\n  DataSource: " + _dataSource;
    }

    /**
     * The {@link TransactionContext}-implementation for {@link JmsMatsTransactionManager_JmsAndJdbc}.
     */
    public static class TransactionalContext_JmsAndJdbc extends TransactionalContext_Jms {
        private final DataSource _dataSource;

        public TransactionalContext_JmsAndJdbc(DataSource dataSource, JmsMatsTxContextKey txContextKey) {
            super(txContextKey);
            _dataSource = dataSource;
        }

        @Override
        public void doTransaction(JmsMatsInternalExecutionContext internalExecutionContext, ProcessingLambda lambda)
                throws JmsMatsJmsException, MatsRefuseMessageException {
            // :: First make the potential Connection available
            LazyJdbcConnectionSupplier lazyConSup = new LazyJdbcConnectionSupplier();
            internalExecutionContext.setSqlTxConnectionSuppliers(lazyConSup, lazyConSup::wasConnectionEmployed);

            // :: We invoke the "outer" transaction, which is the JMS transaction.
            super.doTransaction(internalExecutionContext, () -> {
                // ----- We're *within* the JMS Transaction demarcation.

                /*
                 * NOTICE: We will not get the SQL Connection and set AutoCommit to false /here/ (i.e. start the
                 * transaction), as that will be done implicitly by the user code IFF it actually fetches the SQL
                 * Connection.
                 *
                 * ----- Therefore, we're now IMPLICITLY *within* the SQL Transaction demarcation.
                 */

                try {
                    log.debug(LOG_PREFIX + "About to run ProcessingLambda for " + stageOrInit(_txContextKey)
                            + ", within JDBC SQL Transactional demarcation.");
                    /*
                     * Invoking the provided ProcessingLambda, which typically will be the actual user code (albeit
                     * wrapped with some minor code from the JmsMatsStage to parse the MapMessage, deserialize the
                     * MatsTrace, and fetch the state etc.), which will now be inside both the inner (implicit) SQL
                     * Transaction demarcation, and the outer JMS Transaction demarcation.
                     */
                    lambda.performWithinTransaction();
                }
                // Catch EVERYTHING that "legally" can come out of the try-block:
                catch (JmsMatsJmsException | MatsRefuseMessageException | RuntimeException | Error e) {
                    // ----- The user code had some error occur, or want to reject this message.
                    log.error(LOG_PREFIX + "ROLLBACK SQL: " + e.getClass().getSimpleName() + " while processing "
                            + stageOrInit(_txContextKey) + " Rolling back the SQL Connection.", e);
                    internalExecutionContext.setUserLambdaExceptionLogged();
                    /*
                     * IFF the SQL Connection was fetched, we will now rollback (and close) it.
                     */
                    finishTxThenCloseConnection(internalExecutionContext, lazyConSup, SqlTxAction.ROLLBACK);

                    // ----- We're *outside* the SQL Transaction demarcation (rolled back).

                    // We will now throw on the Exception, which will rollback the JMS Transaction.
                    throw e;
                }
                // Catch ANYTHING ELSE that can come out of the try-block (i.e. "sneaky throws"):
                catch (Throwable t) {
                    // ----- This must have been a "sneaky throws"; Throwing an undeclared checked exception.
                    log.error(LOG_PREFIX + "ROLLBACK SQL: Got an undeclared checked exception " + t.getClass()
                            .getSimpleName() + " while processing " + stageOrInit(_txContextKey)
                            + " (probably 'sneaky throws' of checked exception). Rolling back the SQL Connection.", t);
                    internalExecutionContext.setUserLambdaExceptionLogged();
                    /*
                     * IFF the SQL Connection was fetched, we will now rollback (and close) it.
                     */
                    finishTxThenCloseConnection(internalExecutionContext, lazyConSup, SqlTxAction.ROLLBACK);

                    // ----- We're *outside* the SQL Transaction demarcation (rolled back).

                    // Rethrow the Throwable as special RTE, which will rollback the JMS Transaction.
                    throw new JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException("Got a undeclared checked"
                            + " exception " + t.getClass().getSimpleName()
                            + " while processing " + stageOrInit(_txContextKey) + ".", t);
                }

                // ----- The ProcessingLambda went OK, no Exception was raised.

                // Check whether Session/Connection is ok before committing DB (per contract with JmsSessionHolder).
                try {
                    internalExecutionContext.getJmsSessionHolder().isSessionOk();
                }
                catch (JmsMatsJmsException e) {
                    // ----- Evidently the JMS Session is broken - so rollback SQL
                    log.error(LOG_PREFIX + "ROLLBACK SQL: " + e.getClass().getSimpleName() + " when checking whether"
                            + " the JMS Session was OK. Rolling back the SQL Connection.", e);
                    /*
                     * IFF the SQL Connection was fetched, we will now rollback (and close) it.
                     */
                    finishTxThenCloseConnection(internalExecutionContext, lazyConSup, SqlTxAction.ROLLBACK);

                    // ----- We're *outside* the SQL Transaction demarcation (rolled back).

                    // We will now throw on the Exception, which will rollback the JMS Transaction.
                    throw e;
                }

                // ----- The JMS Session is currently OK, so commit the DB.

                log.debug(LOG_PREFIX + "COMMIT SQL: ProcessingLambda finished, committing SQL Connection.");
                /*
                 * IFF the SQL Connection was fetched, we will now commit (and close) it.
                 */
                finishTxThenCloseConnection(internalExecutionContext, lazyConSup, SqlTxAction.COMMIT);

                // ----- We're now *outside* the SQL Transaction demarcation (committed).

                // Return nicely, as the SQL Connection.commit() and .close() went OK.

                // When exiting, the JMS transaction will be committed.
            });
        }

        private enum SqlTxAction {
            COMMIT, ROLLBACK
        }

        private void finishTxThenCloseConnection(JmsMatsInternalExecutionContext internalExecutionContext,
                LazyJdbcConnectionSupplier lazyConSup, SqlTxAction sqlTxAction) {
            // ?: Was connection gotten by code in ProcessingLambda (user code)
            if (!lazyConSup.wasConnectionEmployed()) {
                // -> No, Connection was not gotten
                log.debug(LOG_PREFIX + "SQL Connection was not requested by stage processing lambda (user code),"
                        + " nothing to perform " + (sqlTxAction == SqlTxAction.COMMIT ? "commit" : "rollback")
                        + " on!");
                return;
            }
            // E-> Yes, Connection was gotten by ProcessingLambda (user code)

            Connection _gottenConnection = lazyConSup.getGottenConnection();

            long nanosAsStart_DbCommit = System.nanoTime();

            // :: Commit or Rollback
            try {
                if (sqlTxAction == SqlTxAction.COMMIT) {
                    _gottenConnection.commit();
                    log.debug(LOG_PREFIX + "Committed SQL Connection [" + _gottenConnection + "].");
                }
                else {
                    _gottenConnection.rollback();
                    log.warn(LOG_PREFIX + "Rolled Back SQL Connection [" + _gottenConnection + "].");
                }
            }
            catch (SQLException e) {
                throw new MatsSqlCommitOrRollbackFailedException("Could not "
                        + (sqlTxAction == SqlTxAction.COMMIT ? "commit" : "rollback")
                        + " SQL Connection [" + _gottenConnection + "] - for stage [" + _txContextKey + "].", e);
            }
            finally {
                // :: Reset AutoCommit flag and Close
                lazyConSup.resetAutoCommitAndClose();
                // Commit timing.
                internalExecutionContext.setDbCommitNanos(System.nanoTime() - nanosAsStart_DbCommit);
            }
        }

        /**
         * Raised if commit or rollback of the SQL Connection failed.
         */
        public static final class MatsSqlCommitOrRollbackFailedException extends RuntimeException {
            public MatsSqlCommitOrRollbackFailedException(String message, Throwable cause) {
                super(message, cause);
            }
        }

        /**
         * Performs Lazy-getting (and setting AutoCommit false) of SQL Connection for the StageProcessor thread, by
         * means of being set on the {@link JmsMatsInternalExecutionContext}, which makes it available via
         * {@link JmsMatsProcessContext#getAttribute(Class, String...)}.
         */
        private class LazyJdbcConnectionSupplier implements Supplier<Connection> {
            private Connection _gottenConnection;
            private boolean _autoCommitModeBeforeFalse;

            @Override
            public Connection get() {
                // NOTE: This shall only be done on one thread, which is the StageProcessor thread.
                // .. The whole point is its ThreadLocal-ness - No concurrency.

                // ?: Have we already gotten the SQL Connection?
                if (_gottenConnection == null) {
                    // -> No, not gotten before - so get it now.
                    try {
                        _gottenConnection = _dataSource.getConnection();
                    }
                    catch (SQLException e) {
                        throw new MatsSqlConnectionCreationException("Could not get SQL Connection from ["
                                + _dataSource + "].", e);
                    }
                    try {
                        // Store the AutoCommit state before setting to false (to reset it)
                        _autoCommitModeBeforeFalse = _gottenConnection.getAutoCommit();

                        // Set it to false, since we want to run a transaction.
                        _gottenConnection.setAutoCommit(false);
                    }
                    catch (SQLException e) {
                        try {
                            _gottenConnection.close();
                        }
                        catch (SQLException closeE) {
                            log.warn("When trying to set AutoCommit to false, we got an SQLException. When trying"
                                    + " to close that Connection, we got a new SQLException. Ignoring.", closeE);
                        }
                        throw new MatsSqlConnectionCreationException("Could not set AutoCommit to false on the"
                                + " SQL Connection [" + _gottenConnection + "].", e);
                    }
                }

                // Return the gotten SQL Connection (either previously gotten, or just gotten)
                return _gottenConnection;
            }

            boolean wasConnectionEmployed() {
                return _gottenConnection != null;
            }

            Connection getGottenConnection() {
                return _gottenConnection;
            }

            void resetAutoCommitAndClose() {
                // :: Reset AutoCommit mode
                try {
                    _gottenConnection.setAutoCommit(_autoCommitModeBeforeFalse);
                    log.debug(LOG_PREFIX + "Reset AutoCommit mode to [" + _autoCommitModeBeforeFalse + "].");
                }
                catch (SQLException e) {
                    log.warn("After performing commit or rollback on SQL Connection ["
                            + _gottenConnection + "], we tried to reset AutoCommit mode to ["
                            + _autoCommitModeBeforeFalse + "] - for stage [" + _txContextKey
                            + "]. Will ignore this, since the operation should have gone through.", e);
                }

                // :: Close SQL Connection
                try {
                    _gottenConnection.close();
                    log.debug(LOG_PREFIX + "Closed SQL Connection [" + _gottenConnection + "].");
                }
                catch (SQLException e) {
                    log.warn("After performing commit or rollback on SQL Connection ["
                            + _gottenConnection + "], we tried to close it but that raised an exception - for stage ["
                            + _txContextKey + "]. Will ignore this, since the operation should have gone through.", e);
                }
            }
        }

        /**
         * A {@link RuntimeException} that should be raised by the {@literal Supplier<Connection>} if it can't get SQL
         * Connections.
         */
        static class MatsSqlConnectionCreationException extends RuntimeException {
            MatsSqlConnectionCreationException(String message, Throwable cause) {
                super(message, cause);
            }
        }
    }
}
