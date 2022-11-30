package io.mats3.impl.jms;

import java.util.Optional;

import javax.jms.MessageConsumer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsMessageSendException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsOverflowRuntimeException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;

/**
 * Implementation of {@link JmsMatsTransactionManager} handling only JMS (getting Connections, and creating Sessions),
 * doing all transactional handling "native", i.e. using only the JMS API (as opposed to e.g. using Spring and its
 * transaction managers).
 * <p />
 * The JMS Connection and Session handling is performed in calling code, where the resulting {@link JmsSessionHolder} is
 * provided to the {@link TransactionalContext_Jms#doTransaction(JmsMatsInternalExecutionContext, ProcessingLambda)}.
 * <p />
 * Note: Musing about the JmsMats transactional handling 6 years later (2021-02-03), I do find it a tad bit obscure.
 * This entire Mats implementation is a JMS implementation, and all JMS Session handling is handled by the "core", but
 * just exactly commit and rollback is handled in this class. The extensions handling SQL are much more "independent" as
 * such. However, the big point is the {@link JmsMatsStageProcessor}, which has a JMS Consumer-pump/loop, and that
 * Consumer's JMS Session is intimately intertwined with the transaction aspects handled here. Had it only been the
 * {@link JmsMatsInitiator} and as such initiations of messages that should be handled, much more of the JMS transaction
 * management could have been handled here. (Actually, the only transactional aspect at that point would have been to
 * put a transaction around the actual sending, so that if more than one message was sent, they were all either sent or
 * not.)
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class JmsMatsTransactionManager_Jms implements JmsMatsTransactionManager, JmsMatsStatics {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsTransactionManager_Jms.class);

    public static JmsMatsTransactionManager create() {
        return new JmsMatsTransactionManager_Jms();
    }

    protected JmsMatsTransactionManager_Jms() {
        /* hide; use factory method */
    }

    @Override
    public TransactionContext getTransactionContext(JmsMatsTxContextKey txContextKey) {
        return new TransactionalContext_Jms(txContextKey);
    }

    /**
     * The {@link JmsMatsTransactionManager.TransactionContext} implementation for
     * {@link JmsMatsTransactionManager_Jms}.
     */
    public static class TransactionalContext_Jms implements TransactionContext, JmsMatsStatics {

        protected final JmsMatsTxContextKey _txContextKey;

        public TransactionalContext_Jms(JmsMatsTxContextKey txContextKey) {
            _txContextKey = txContextKey;
        }

        @Override
        public void doTransaction(JmsMatsInternalExecutionContext internalExecutionContext, ProcessingLambda lambda)
                throws JmsMatsJmsException, MatsRefuseMessageException {
            /*
             * We're always within a JMS transaction (as that is the nature of the JMS API when in transactional mode).
             *
             * ----- Therefore, we're now *within* the JMS Transaction demarcation.
             */

            Session jmsSession = internalExecutionContext.getJmsSessionHolder().getSession();

            try {
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "About to run ProcessingLambda for " +
                        stageOrInit(_txContextKey) + ", within JMS Transactional demarcation.");
                /*
                 * Invoking the provided ProcessingLambda, which typically will be SQL Transaction demarcation - which
                 * then again invokes the user-lambda (but which will be wrapped again by some JmsMatsStage processing).
                 */
                lambda.performWithinTransaction();
            }
            /*
             * Catch EVERYTHING that can come out of the try-block, with some cases handled specially.
             */
            catch (MatsRefuseMessageException | JmsMatsOverflowRuntimeException e) {
                /*
                 * Special exception allowed from the Mats API from the MatsStage lambda, denoting that one wants
                 * immediate refusal of the message. (This is just a hint/wish, as e.g. the JMS specification does
                 * not provide such a mechanism).
                 */
                String msg = LOG_PREFIX + "ROLLBACK JMS: Got a MatsRefuseMessageException while transacting "
                        + stageOrInit(_txContextKey) + " (most probably from the user code)."
                        + " Rolling back the JMS transaction - trying to ensure that it goes directly to DLQ.";
                if (internalExecutionContext.isUserLambdaExceptionLogged()) {
                    log.error(msg);
                }
                else {
                    log.error(msg, e);
                }
                // Fetch the MessageConsumer used for this MatsStage, so that we can insta-DLQ.
                Optional<MessageConsumer> messageConsumer = internalExecutionContext.getMessageConsumer();
                // ?: Assert that it is present
                if (!messageConsumer.isPresent()) {
                    // -> It is not - and MatsRefuseMessageException is only declared to be thrown from stage process
                    log.error(e.getClass().getName() + " was raised in a wrong context where no JMS MessageConsumer is"
                            + " present (i.e. initiation). This shall not be possible - 'sneaky throws' in play?.", e);
                    rollback(jmsSession, e);
                }
                else {
                    // -> It is present - so give the job of insta-DLQ'ing to the BrokerSpecifics.
                    JmsMatsMessageBrokerSpecifics.instaDlqWithRollbackLambda(messageConsumer.get(),
                            () -> rollback(jmsSession, e));
                }
                // Rethrow
                throw e;
            }
            catch (JmsMatsJmsException e) {
                /*
                 * This denotes that the JmsMatsProcessContext (the JMS Mats implementation - i.e. us) has had problems
                 * doing JMS stuff. This shall currently only happen in the JmsMatsStage when accessing the contents of
                 * the received [Map]Message, and for both Stage and Init when sending out new messages. Sending this on
                 * to the outside catch block, as this means that we have an unstable JMS context.
                 */
                String msg = LOG_PREFIX + "ROLLBACK JMS: Got a " + JmsMatsJmsException.class.getSimpleName()
                        + " while transacting " + stageOrInit(_txContextKey)
                        + ", indicating that the Mats JMS implementation had problems performing"
                        + " some operation. Rolling back JMS Session, throwing on to get new JMS Connection.";
                if (internalExecutionContext.isUserLambdaExceptionLogged()) {
                    log.error(msg);
                }
                else {
                    log.error(msg, e);
                }

                rollback(jmsSession, e);
                // Throwing out, since the JMS Connection most probably is unstable.
                throw e;
            }
            catch (RuntimeException | Error e) {
                /*
                 * Should only be user code, as errors from "ourselves" (the JMS Mats impl) should throw
                 * JmsMatsJmsException, and are caught earlier (see above).
                 */
                log.error(LOG_PREFIX + "ROLLBACK JMS: Got a " + e.getClass().getSimpleName() + " while transacting "
                        + stageOrInit(_txContextKey) + " Rolling back the JMS session.", e);
                rollback(jmsSession, e);
                // Throw on, so that if this is in an initiate-call, it will percolate all the way out.
                // (NOTE! Inside JmsMatsStageProcessor, RuntimeExceptions won't recreate the JMS Connection..)
                throw e;
            }
            catch (Throwable t) {
                /*
                 * This must have been a "sneaky throws"; Throwing of an undeclared checked exception.
                 */
                String msg = LOG_PREFIX + "ROLLBACK JMS: " + t.getClass().getSimpleName() + " while transacting "
                        + stageOrInit(_txContextKey) + " (probably 'sneaky throws' of checked exception)."
                        + " Rolling back the JMS session.";
                if (internalExecutionContext.isUserLambdaExceptionLogged()) {
                    log.error(msg);
                }
                else {
                    log.error(msg, t);
                }
                rollback(jmsSession, t);
                // Rethrow the Throwable as special RTE, which if Initiate will percolate all the way out.
                throw new JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException("Got a undeclared checked exception "
                        + t.getClass().getSimpleName() + " while transacting " + stageOrInit(_txContextKey) + ".", t);
            }

            // ----- The ProcessingLambda went OK, no Exception was raised.

            // == Handling JMS Commit elision
            // ?: Should we elide JMS Commit?
            if (internalExecutionContext.shouldElideJmsCommitForInitiation()) {
                // -> Yes, we should elide JMS Commit - i.e. NOT commit it, since there was no messages sent.
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "COMMIT JMS: Asked to elide JMS Commit, so that we do!"
                        + " Transaction finished.");
                internalExecutionContext.setMessageSystemCommitNanos(0L);
                return;
            }

            // E-> No, NOT eliding JMS Commit - i.e. we SHOULD commit it, since messages has been produced.

            if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "COMMIT JMS: ProcessingLambda finished,"
                    + " committing JMS Session.");
            try {
                long nanosAsStart_MessageSystemCommit = System.nanoTime();
                // Actual JMS commit
                jmsSession.commit();
                long nanosTaken_MessageSystemCommit = System.nanoTime() - nanosAsStart_MessageSystemCommit;
                internalExecutionContext.setMessageSystemCommitNanos(nanosTaken_MessageSystemCommit);
            }
            catch (Throwable t) {
                /*
                 * WARNING WARNING! COULD NOT COMMIT JMS! Besides indicating that we have a JMS problem, we also have a
                 * potential bad situation with potentially committed external state changes, but where the JMS Message
                 * Broker cannot record our consumption of the message, and will probably have to (wrongly) redeliver
                 * it, or throw out if this was an initiation.
                 */
                String sqlEmployed = internalExecutionContext.isUsingSqlHandlingTransactionManager()
                        ? "(NOTICE: SQL Connection " + (internalExecutionContext.wasSqlConnectionEmployed()
                                ? "WAS"
                                : "was NOT") + " gotten/employed!)"
                        : "(Not using a SQL-handling JmsMatsTransactionManager)";
                log.error(LOG_PREFIX
                        + "VERY BAD! " + stageOrInit(_txContextKey) + " " + sqlEmployed
                        + " After processing finished correctly, and"
                        + " any external, potentially state changing operations have committed OK, we could not"
                        + " commit the JMS Session! If this happened within a Mats message initiation, the state"
                        + " changing operations (e.g. database insert/update) have been committed, while the message"
                        + " was not sent. If this is not caught by the initiation code ('manually' rolling back the"
                        + " state change), the global state is probably out of sync (i.e. the order-row is marked"
                        + " 'processing started', while the corresponding process-order message was not sent). However,"
                        + " if this happened within a Mats Stage (inside an endpoint), this will most probably "
                        + " lead to a redelivery (as in 'double delivery'), which should be handled by your endpoint's"
                        + " idempotent handling of incoming messages. Do note that this might be a problem if the stage"
                        + " also sends an outgoing message in the normal flow: If you just check your database at the"
                        + " next redelivery, and realize that the changes have been committed, and hence do not send an"
                        + " outgoing message, you will effectively have stopped the Mats flow: Since it wasn't sent"
                        + " this time (that is, due to this 'VERY BAD'), and you do not send it the next time (since"
                        + " you realize that it was a double delivery, and the database had changes applied, and you"
                        + " thus erroneously do not send the outgoing message), you won't ever send it, which probably"
                        + " is not what you want.", t);
                /*
                 * This certainly calls for reestablishing the JMS Session, so we need to throw out a
                 * JmsMatsJmsException. However, in addition, this is the specific type of error ("VERY BAD!") that
                 * MatsInitiator.MatsMessageSendException is created for.
                 */
                throw new JmsMatsMessageSendException("VERY BAD! After " + stageOrInit(_txContextKey) + " finished"
                        + " processing correctly, and any external, potentially state changing operations have"
                        + " committed OK, we could not commit the JMS Session! " + sqlEmployed, t);
            }

            // -> The JMS Session nicely committed.
            if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "JMS Session committed for " + stageOrInit(_txContextKey)
                    + "! Transaction finished.");
        }
    }

    static void rollback(Session jmsSession, Throwable t) throws JmsMatsJmsException {
        try {
            jmsSession.rollback();
            // -> The JMS Session rolled nicely back.
            log.warn(LOG_PREFIX + "JMS Session rolled back.");
        }
        catch (Throwable rollbackT) {
            /*
             * Could not roll back. This certainly indicates that we have some problem with the JMS Session, and we'll
             * throw it out so that we start a new JMS Session.
             *
             * However, it is not that bad, as the JMS Message Broker probably will redeliver anyway.
             */
            throw new JmsMatsJmsException("When trying to rollback JMS Session due to a "
                    + t.getClass().getSimpleName() + ", we got some Exception."
                    + " The JMS Session certainly seems unstable.", rollbackT);
        }
    }
}
