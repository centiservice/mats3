package io.mats3.impl.jms;

import javax.jms.Session;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsInitiator;
import io.mats3.MatsStage;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;

/**
 * Transactional aspects of the JMS MATS implementation. (It is the duty of the MATS implementation to ensure that the
 * transactional principles of MATS are honored).
 * <p>
 * The reason for this being an interface, is that the transactional aspects can be implemented through different means.
 * Specifically, there is a direct implementation, and the intention is to also have a Spring-specific implementation.
 *
 * @author Endre Stølsvik - 2015-11-04 - http://endre.stolsvik.com
 */
public interface JmsMatsTransactionManager {

    /**
     * Provided to {@link #getTransactionContext(JmsMatsTxContextKey) getTransactionContext(...)} when a Mats-component
     * fetches the TransactionContext implementation.
     * <p>
     * This interface is implemented both by {@link JmsMatsStageProcessor StageProcessors} (in which case
     * {@link #getStage()} returns itself), and by {@link JmsMatsInitiator Initiators} (in which case
     * {@link #getStage()} returns {@code null}).
     */
    interface JmsMatsTxContextKey {
        /**
         * @return "this" if this is a StageProcessor, <code>null</code> if an Initiator.
         */
        JmsMatsStage<?, ?, ?, ?> getStage();

        /**
         * @return the {@link JmsMatsFactory} of the StageProcessor or Initiator (never <code>null</code>).
         */
        JmsMatsFactory<?> getFactory();
    }

    /**
     * Provides an implementation of {@link TransactionContext}. (JMS Connection and Session handling is done by
     * {@link JmsMatsJmsSessionHandler}).
     *
     * @param txContextKey
     *            for which {@link JmsMatsStage} or {@link JmsMatsInitiator} this request for {@link TransactionContext}
     *            is for.
     * @return a {@link TransactionContext} for the supplied txContextKey.
     */
    TransactionContext getTransactionContext(JmsMatsTxContextKey txContextKey);

    /**
     * Implementors shall do the transactional processing and handle any Throwable that comes out of the
     * {@link ProcessingLambda} by rolling back.
     */
    @FunctionalInterface
    interface TransactionContext {
        /**
         * Shall open relevant transactions (that are not already opened by means of JMS's "always in transaction" for
         * transactional Connections), perform the provided lambda, and then commit the transactions (including the JMS
         * {@link Session}).
         * <p>
         * If <i>any</i> Exception occurs when executing the provided lambda, then the transactions should be rolled
         * back - but if it is the declared special {@link MatsRefuseMessageException}, then the implementation should
         * also try to ensure that the underlying JMS Message is not redelivered (no more retries), but instead put on
         * the DLQ right away. <i>(Beware of "sneaky throws": The JVM bytecode doesn't care whether a method declares an
         * exception or not: It is possible to throw a checked exception form a method that doesn't declare it in
         * several different ways. Groovy is nasty here (as effectively all Exceptions are unchecked in the Groovy
         * world), and also google "sneakyThrows" for a way to do it using "pure java" that was invented with
         * Generics.)</i>
         *
         * @param jmsSessionMessageContext
         *            holds, amongst possibly other stuff, the {@link JmsSessionHolder} instance which contains the JMS
         *            Session upon which this transaction should run. Gotten from
         *            {@link JmsMatsJmsSessionHandler#getSessionHolder(JmsMatsStageProcessor)} or
         *            {@link JmsMatsJmsSessionHandler#getSessionHolder(JmsMatsInitiator)}.
         * @param lambda
         *            the stuff that shall be done within transaction, i.e. the {@link MatsStage} or the
         *            {@link MatsInitiator}.
         */
        void doTransaction(JmsMatsInternalExecutionContext jmsSessionMessageContext, ProcessingLambda lambda)
                throws JmsMatsJmsException, MatsRefuseMessageException;
    }

    /**
     * The lambda that is provided to the {@link JmsMatsTransactionManager} for it to provide transactional demarcation
     * around.
     */
    @FunctionalInterface
    interface ProcessingLambda {
        void performWithinTransaction() throws JmsMatsJmsException, MatsRefuseMessageException;
    }
}
