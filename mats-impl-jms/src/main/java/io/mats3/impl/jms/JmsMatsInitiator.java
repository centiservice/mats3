package io.mats3.impl.jms;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateCompletedContext;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateCompletedContext.InitiateProcessResult;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateInterceptContext;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateInterceptOutgoingMessagesContext;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateInterceptUserLambdaContext;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateStartedContext;
import io.mats3.api.intercept.MatsInitiateInterceptor.MatsInitiateInterceptOutgoingMessages;
import io.mats3.api.intercept.MatsInitiateInterceptor.MatsInitiateInterceptUserLambda;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsEditableOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsMessageSendException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException;
import io.mats3.impl.jms.JmsMatsFactory.NestingWithinStageProcessing;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;
import io.mats3.impl.jms.JmsMatsProcessContext.DoAfterCommitRunnableHolder;
import io.mats3.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;
import io.mats3.impl.jms.JmsMatsTransactionManager.TransactionContext;

/**
 * The JMS implementation of {@link MatsInitiator}.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
class JmsMatsInitiator<Z> implements MatsInitiator, JmsMatsTxContextKey, JmsMatsStatics {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsInitiator.class);

    private final String _name;
    private final JmsMatsFactory<Z> _parentFactory;
    private final JmsMatsJmsSessionHandler _jmsMatsJmsSessionHandler;
    private final TransactionContext _transactionContext;

    public JmsMatsInitiator(String name, JmsMatsFactory<Z> parentFactory,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler,
            JmsMatsTransactionManager jmsMatsTransactionManager) {
        // NOTICE! Due to multi-threading, whereby one Initiator might be used "globally" for e.g. a Servlet Container
        // having 200 threads, we cannot fetch a sole Session for the Initiator to be used for all initiations (as
        // it might be used concurrently by all the 200 Servlet Container threads). Thus, each initiation needs to
        // get hold of its own Session. However, the Sessions should be pooled.

        _name = name;
        _parentFactory = parentFactory;
        _jmsMatsJmsSessionHandler = jmsMatsJmsSessionHandler;
        _transactionContext = jmsMatsTransactionManager.getTransactionContext(this);
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public JmsMatsFactory<Z> getParentFactory() {
        return _parentFactory;
    }

    @Override
    public void initiate(InitiateLambda lambda) throws MatsBackendException, MatsMessageSendException {
        // NOTICE! Due to multi-threading, whereby one Initiator might be used "globally" for e.g. a Servlet Container
        // having 200 threads, we cannot fetch a sole Session for the Initiator to be used for all initiations (as
        // it might be used concurrently by all the 200 Servlet Container threads). Thus, each initiation needs to
        // get hold of its own Session. However, the Sessions should be pooled.

        Instant startedInstant = Instant.now();
        long nanosAtStart_Init = System.nanoTime();

        String existingTraceId = MDC.get(MDC_TRACE_ID);
        String existingMatsInit = MDC.get(MDC_MATS_INIT);
        String existingMatsAppName = MDC.get(MDC_MATS_APP_NAME);
        String existingMatsAppVersion = MDC.get(MDC_MATS_APP_VERSION);
        String existingMatsCallNumber = MDC.get(MDC_MATS_CALL_NUMBER);
        String existingMatsCallNumberTotal = MDC.get(MDC_MATS_TOTAL_CALL_NUMBER);

        // :: For Intercepting, base intercept context.
        InitiateInterceptContextImpl interceptContext = new InitiateInterceptContextImpl(this, startedInstant,
                nanosAtStart_Init);
        List<MatsInitiateInterceptor> interceptorsForInitiation = _parentFactory.getInterceptorsForInitiation(
                interceptContext);

        try { // :: try-finally: Clear up the MDC.
            MDC.put(MDC_MATS_INIT, "true");
            MDC.put(MDC_MATS_CALL_NUMBER, "0");

            List<JmsMatsMessage<Z>> messagesToSend = new ArrayList<>();

            long[] nanosTaken_UserLambda = { 0L };
            long[] nanosTaken_totalEnvelopeSerialization = { 0L };
            long[] nanosTaken_totalProduceAndSendMsgSysMessages = { 0L };

            Throwable throwableResult = null;
            InitiateProcessResult throwableInitiateProcessResult = null;

            JmsSessionHolder jmsSessionHolder;
            try {
                jmsSessionHolder = _jmsMatsJmsSessionHandler.getSessionHolder(this);
            }
            catch (JmsMatsJmsException e) {
                // Could not get hold of JMS *Connection* - Read the JavaDoc of
                // JmsMatsJmsSessionHandler.getSessionHolder()
                throw new MatsBackendException("Could not get hold of JMS Connection.", e);
            }
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder = new DoAfterCommitRunnableHolder();

            Optional<NestingWithinStageProcessing<Z>> withinStageContext = _parentFactory
                    .getCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing();

            JmsMatsInternalExecutionContext internalExecutionContext = withinStageContext
                    .map(within -> JmsMatsInternalExecutionContext.forStage(
                            jmsSessionHolder, within.getMessageConsumer()))
                    .orElseGet(() -> JmsMatsInternalExecutionContext.forInitiation(jmsSessionHolder));

            JmsMatsInitiate<Z> init = withinStageContext
                    .map(within -> JmsMatsInitiate.createForChildFlow(_parentFactory, messagesToSend,
                            internalExecutionContext, doAfterCommitRunnableHolder,
                            within.getMatsTrace(), within.getCurrentStageId()))
                    .orElseGet(() -> JmsMatsInitiate.createForTrueInitiation(_parentFactory, messagesToSend,
                            internalExecutionContext, doAfterCommitRunnableHolder, existingTraceId));

            MDC.put(MDC_MATS_TOTAL_CALL_NUMBER, String.valueOf(init.getInitialTotalCallNumber()));

            try {
                // ===== Going into Transactional Demarcation
                _transactionContext.doTransaction(internalExecutionContext, () -> {

                    JmsMatsContextLocalCallback.bindResource(MatsInitiate.class, init);
                    _parentFactory.setCurrentMatsFactoryThreadLocal_ExistingMatsInitiate(() -> init);

                    // === Invoke any interceptors, stage "Started"
                    InitiateStartedContextImpl initiateStartedContext = new InitiateStartedContextImpl(interceptContext,
                            init);
                    interceptorsForInitiation.forEach(interceptor -> interceptor.initiateStarted(
                            initiateStartedContext));

                    // === Invoke any interceptors, stage "Intercept"
                    // Create the InitiateInterceptContext instance (one for all interceptors)
                    InitiateInterceptUserLambdaContextImpl initiateInterceptContext = new InitiateInterceptUserLambdaContextImpl(
                            interceptContext, init);
                    // :: Create a "lambda stack" of the interceptors
                    // This is the resulting lambda we will actually invoke
                    // .. if there are no interceptors, it will directly be the user lambda
                    InitiateLambda currentLambda = lambda;
                    /*
                     * Create the lambda stack by moving "backwards" through the registered interceptors, as when we'll
                     * actually invoke the resulting lambda stack, the last stacked (at top), which is the first
                     * registered (due to iterating backwards), will be the first code to run.
                     */
                    for (int i = interceptorsForInitiation.size() - 1; i >= 0; i--) {
                        MatsInitiateInterceptor interceptor = interceptorsForInitiation.get(i);
                        if (!(interceptor instanceof MatsInitiateInterceptUserLambda)) {
                            continue;
                        }
                        final MatsInitiateInterceptUserLambda interceptInterceptor = (MatsInitiateInterceptUserLambda) interceptor;
                        // The currentLambda is the one that the interceptor should invoke
                        final InitiateLambda lambdaThatInterceptorMustInvoke = currentLambda;
                        // .. and, wrap the current lambda with the interceptor.
                        // It may, or may not, wrap the provided init with its own implementation
                        currentLambda = initForInterceptor -> interceptInterceptor.initiateInterceptUserLambda(
                                initiateInterceptContext, lambdaThatInterceptorMustInvoke, initForInterceptor);
                    }

                    // :: == ACTUALLY Invoke the lambda. The current lambda is the one we should invoke.
                    long nanosAtStart_UserLambda = System.nanoTime();
                    try {
                        currentLambda.initiate(init);
                    }
                    finally {
                        nanosTaken_UserLambda[0] = System.nanoTime() - nanosAtStart_UserLambda;
                    }

                    // === Invoke any interceptors, stage "Message"
                    invokeInitiateMessageInterceptors(interceptorsForInitiation, interceptContext, init,
                            messagesToSend);

                    /*
                     * Concatenate all TraceIds for the outgoing messages, to put on the MDC. - which is good for the
                     * logging, so that they are all present on the MDC for the send/commit log lines, and in particular
                     * if we get any Exceptions when committing. Notice that the typical situation is that there is just
                     * one message.
                     */
                    concatAllTraceIds(messagesToSend);

                    // :: Check whether we can elide the entire committing of JMS, due to no messages to send
                    // ?: Any messages produced?
                    if (messagesToSend.isEmpty()) {
                        // -> No, no messages produced
                        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Mark the internal execution context to elide"
                                + " JMS Commit, since there was no outgoing messages during initiation.");
                        // Mark this execution context as "please elide JMS commit".
                        internalExecutionContext.elideJmsCommitForInitiation();
                    }
                    else {
                        // -> Yes, there are messages, so serialize the envelopes and send them
                        // (commit is performed when it exits the transaction lambda)
                        // :: Serialize
                        long nanosAtStart_totalEnvelopeSerialization = System.nanoTime();
                        for (JmsMatsMessage<Z> matsMessage : messagesToSend) {
                            matsMessage.serializeAndCacheMatsTrace();
                        }
                        long nowNanos = System.nanoTime();
                        nanosTaken_totalEnvelopeSerialization[0] = nowNanos - nanosAtStart_totalEnvelopeSerialization;

                        long nanosAtStart_totalProduceAndSendMsgSysMessages = nowNanos;

                        // :: ACTUALLY SEND THE MESSAGES
                        produceAndSendMsgSysMessages(log, jmsSessionHolder, _parentFactory, messagesToSend);
                        nanosTaken_totalProduceAndSendMsgSysMessages[0] = System.nanoTime() -
                                nanosAtStart_totalProduceAndSendMsgSysMessages;
                    }
                }); // End: Mats Transaction

                // ----- Transaction is now committed (if exceptions were raised, we've been thrown out earlier)

                // :: Handle the context.doAfterCommit(Runnable) lambda.
                try {
                    doAfterCommitRunnableHolder.runDoAfterCommitIfAny();
                }
                catch (RuntimeException re) {
                    log.error(LOG_PREFIX + "Got RuntimeException when running the doAfterCommit Runnable."
                            + " Ignoring.", re);
                }
            }
            catch (JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException e) {
                // Just record this for interceptor (but the original undeclared Exception, which is the
                // cause)..
                throwableResult = e.getCause();
                throwableInitiateProcessResult = InitiateProcessResult.USER_EXCEPTION;
                // .. and throw on out
                throw new JmsMatsInitiationRaisedUndeclaredCheckedException("Undeclared checked Exception"
                        + " [" + e.getCause().getClass().getName() + "] from initiation lambda.", e);
            }
            catch (MatsRefuseMessageException e) {
                // NOTICE! MatsRefuseMessageException IS NOT DECLARED FOR INITIATION LAMBDAS (only for stage lambdas),
                // i.e. "it cannot happen", i.e. sneaky throws. However, since the transaction manager handles both
                // stages and inits, _it_ will let it pass through (and not wrap it as with the undeclared above).
                // Therefore, we handle it as with undeclared exception above.
                //
                // NOTICE! The special JmsMatsOverflowRuntimeException is NOT relevant to catch here, and it should
                // rather percolate out, as that can only happen when an initiation is performed within a Stage - there
                // is no way to increase the "total call number" beyond 1 when doing "true initiations" "from the
                // outside", let alone 100 (when it is raised). It should percolate out, as it IS caught in the JMS
                // transaction manager, being handled exactly as MatsRefuseMessageException - which is relevant if an
                // initiation is performed within a Stage, and the "total call number" then overflows.

                // Store the original Exception (as we do with JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException)
                throwableResult = e;
                throwableInitiateProcessResult = InitiateProcessResult.USER_EXCEPTION;
                // .. and throw on as a JmsMatsInitiationRaisedUndeclaredCheckedException
                throw new JmsMatsInitiationRaisedUndeclaredCheckedException("Undeclared checked Exception"
                        + " [" + e.getClass().getSimpleName() + "] from initiation lambda.", e);
            }
            catch (JmsMatsMessageSendException e) {
                /*
                 * This is the special situation which is the "VERY BAD!" scenario, i.e. DB was committed, but JMS was
                 * not, which is .. very bad. JmsMatsMessageSendException is a JmsMatsJmsException, and that indicates
                 * that there was a problem with JMS - so we should "crash" the JmsSessionHolder to signal that the JMS
                 * Connection is probably broken.
                 */
                // :: Create the API-level Exception for this situation, so that interceptor will get that.
                MatsMessageSendException rethrow = new MatsMessageSendException("Evidently got problems sending out"
                        + " the JMS message after having run the process lambda and potentially committed other"
                        + " resources, typically database.", e);
                // Record for interceptor
                throwableResult = rethrow;
                throwableInitiateProcessResult = InitiateProcessResult.SYSTEM_EXCEPTION;
                // Crash the JMS Session
                jmsSessionHolder.crashed(e);
                // TODO: Do retries if it fails!
                // .. and throw on out
                throw rethrow;
            }
            catch (JmsMatsException e) {
                /*
                 * Catch any other JmsMatsException, as that most probably indicates that there was some serious problem
                 * with JMS - so we should "crash" the JmsSessionHolder to signal that the JMS Connection is probably
                 * broken. This is a lesser evil than JmsMatsMessageSendException (aka "VERY BAD!"), as we've not
                 * committed neither DB nor JMS.
                 */
                // :: Create the API-level Exception for this situation, so that interceptor will get that.
                MatsBackendException rethrow = new MatsBackendException("Evidently have problems talking with our"
                        + " backend, which is a JMS Broker.", e);
                // Record for interceptor
                throwableResult = rethrow;
                throwableInitiateProcessResult = InitiateProcessResult.SYSTEM_EXCEPTION;
                // Crash the JMS Session
                jmsSessionHolder.crashed(e);
                // .. and throw on out
                throw rethrow;
            }
            catch (RuntimeException | Error e) {
                // Just record this for interceptor..
                throwableResult = e;
                throwableInitiateProcessResult = InitiateProcessResult.USER_EXCEPTION;
                // .. and throw on out
                throw e;
            }
            finally {
                jmsSessionHolder.release(); // <- handles if has already been crashed()
                JmsMatsContextLocalCallback.unbindResource(MatsInitiate.class);
                _parentFactory.clearCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();

                long nanosTaken_TotalStartInitToFinished = System.nanoTime() - nanosAtStart_Init;

                // === Invoke any interceptors, stage "Completed"

                // ::: "Calculate" the ProcessResult based on current throwable/message situation

                InitiateProcessResult initiateProcessResult;
                // ?: Do we have a caught throwable?
                if (throwableInitiateProcessResult != null) {
                    // -> Yes, so use that result
                    initiateProcessResult = throwableInitiateProcessResult;
                }
                // ?: Do we have zero messages?
                else if (messagesToSend.isEmpty()) {
                    // -> 'None' messages
                    initiateProcessResult = InitiateProcessResult.NONE;
                }
                // ?: Do we have multiple messages?
                else if (messagesToSend.size() > 1) {
                    // -> Multiple messages
                    initiateProcessResult = InitiateProcessResult.MULTIPLE;
                }
                else {
                    // -> Only a single message
                    // Fetch the message
                    MatsSentOutgoingMessage singleMessage = messagesToSend.get(0);
                    // :: Switch-case on the type of the single message
                    switch (singleMessage.getMessageType()) {
                        case REQUEST:
                            initiateProcessResult = InitiateProcessResult.REQUEST;
                            break;
                        case SEND:
                            initiateProcessResult = InitiateProcessResult.SEND;
                            break;
                        case PUBLISH:
                            initiateProcessResult = InitiateProcessResult.PUBLISH;
                            break;
                        default:
                            log.error(LOG_PREFIX + "UNEXPECTED MESSAGE TYPE IN INITIATION! ["
                                    + singleMessage.getMessageType() + "] - REPORT BUG! - resolving to MULTIPLE!");
                            initiateProcessResult = InitiateProcessResult.MULTIPLE;
                    }
                }

                InitiateCompletedContextImpl initiateCompletedContext = new InitiateCompletedContextImpl(
                        initiateProcessResult,
                        interceptContext,
                        nanosTaken_UserLambda[0],
                        init.getMeasurements(),
                        init.getTimingMeasurements(),
                        nanosTaken_totalEnvelopeSerialization[0],
                        internalExecutionContext.getDbCommitNanos(),
                        nanosTaken_totalProduceAndSendMsgSysMessages[0],
                        internalExecutionContext.getMessageSystemCommitNanos(),
                        nanosTaken_TotalStartInitToFinished,
                        throwableResult,
                        Collections.unmodifiableList(messagesToSend));

                // Go through interceptors backwards for this exit-style intercept stage
                for (int i = interceptorsForInitiation.size() - 1; i >= 0; i--) {
                    try {
                        interceptorsForInitiation.get(i).initiateCompleted(initiateCompletedContext);
                    }
                    catch (Throwable t) {
                        log.error("InitiateInterceptor [" + interceptorsForInitiation.get(i) + "] raised a ["
                                + t.getClass().getSimpleName() + "] when invoking initiateCompleted(..)"
                                + " - ignoring, but this is probably quite bad.", t);
                    }
                }
            }
        }
        finally {
            // :: Restore MDC
            if (existingMatsInit != null) {
                MDC.put(MDC_MATS_INIT, existingMatsInit);
            }
            else {
                MDC.remove(MDC_MATS_INIT);
            }

            if (existingMatsAppName != null) {
                MDC.put(MDC_MATS_APP_NAME, existingMatsAppName);
            }
            else {
                MDC.remove(MDC_MATS_APP_NAME);
            }

            if (existingMatsAppVersion != null) {
                MDC.put(MDC_MATS_APP_VERSION, existingMatsAppVersion);
            }
            else {
                MDC.remove(MDC_MATS_APP_VERSION);
            }

            if (existingMatsCallNumber != null) {
                MDC.put(MDC_MATS_CALL_NUMBER, existingMatsCallNumber);
            }
            else {
                MDC.remove(MDC_MATS_CALL_NUMBER);
            }

            if (existingMatsCallNumberTotal != null) {
                MDC.put(MDC_MATS_TOTAL_CALL_NUMBER, existingMatsCallNumberTotal);
            }
            else {
                MDC.remove(MDC_MATS_TOTAL_CALL_NUMBER);
            }

            if (existingTraceId != null) {
                MDC.put(MDC_TRACE_ID, existingTraceId);
            }
            else {
                MDC.remove(MDC_TRACE_ID);
            }
        }
    }

    private void concatAllTraceIds(List<JmsMatsMessage<Z>> messagesToSend) {
        // :: Fast-path if 0 or 1
        String collectedTraceIds;
        if (messagesToSend.isEmpty()) {
            collectedTraceIds = "";
        }
        else if (messagesToSend.size() == 1) {
            collectedTraceIds = messagesToSend.get(0).getTraceId();
        }
        else {
            Stream<String> allTraceIdsStream = messagesToSend.stream()
                    .map(m -> m.getMatsTrace().getTraceId())
                    .distinct()
                    .sorted();
            int tooMany = 15;
            // ?: Are there too many?
            if (messagesToSend.size() <= tooMany) {
                // -> No, not too many
                collectedTraceIds = allTraceIdsStream.collect(Collectors.joining(";"));
            }
            else {
                // -> Yes, too many - creating a "traceId" reflecting cropping.
                collectedTraceIds = "<cropped,numTraceIds:" + messagesToSend.size() + ">;"
                        + allTraceIdsStream.limit(tooMany).collect(Collectors.joining(";"))
                        + ";...";
            }
        }
        MDC.put(MDC_TRACE_ID, collectedTraceIds);
    }

    private static class JmsMatsInitiationRaisedUndeclaredCheckedException extends RuntimeException {
        public JmsMatsInitiationRaisedUndeclaredCheckedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void invokeInitiateMessageInterceptors(List<MatsInitiateInterceptor> interceptorsForInitiation,
            InitiateInterceptContextImpl interceptContext,
            JmsMatsInitiate<Z> initiate, List<JmsMatsMessage<Z>> messagesToSend) {
        // :: Find the Message interceptors. Goddamn why is there no stream.filter[InstanceOf](Clazz.class)?
        List<MatsInitiateInterceptOutgoingMessages> messageInterceptors = interceptorsForInitiation.stream()
                .filter(MatsInitiateInterceptOutgoingMessages.class::isInstance)
                .map(MatsInitiateInterceptOutgoingMessages.class::cast)
                .collect(Collectors.toList());
        if (!messageInterceptors.isEmpty()) {
            Consumer<String> cancelOutgoingMessage = matsMsgId -> messagesToSend
                    .removeIf(next -> next.getMatsMessageId().equals(matsMsgId));
            // Making a copy for the 'messagesToSend', as it can be modified (add/remove) by the interceptor.
            ArrayList<JmsMatsMessage<Z>> copiedMessages = new ArrayList<>();
            List<MatsEditableOutgoingMessage> unmodifiableMessages = Collections.unmodifiableList(copiedMessages);
            InitiateInterceptOutgoingMessagesContextImpl context = new InitiateInterceptOutgoingMessagesContextImpl(
                    interceptContext, initiate, unmodifiableMessages, cancelOutgoingMessage);
            // Iterate through the interceptors, "showing" the matsMessages.
            for (MatsInitiateInterceptOutgoingMessages messageInterceptor : messageInterceptors) {
                // Filling with the /current/ set of messagesToSend.
                copiedMessages.clear();
                copiedMessages.addAll(messagesToSend);
                // :: Invoke the interceptor
                // NOTICE: If the interceptor cancels a message, or initiates a new matsMessage, this WILL show up for
                // the next invoked interceptor.
                messageInterceptor.initiateInterceptOutgoingMessages(context);
            }
        }
    }

    @Override
    public void initiateUnchecked(InitiateLambda lambda) throws MatsBackendRuntimeException,
            MatsMessageSendRuntimeException {
        try {
            initiate(lambda);
        }
        catch (MatsMessageSendException e) {
            throw new MatsMessageSendRuntimeException("Wrapping the MatsMessageSendException in a unchecked variant",
                    e);
        }
        catch (MatsBackendException e) {
            throw new MatsBackendRuntimeException("Wrapping the MatsBackendException in a unchecked variant", e);
        }
    }

    @Override
    public void close() {
        /*
         * Nothing to do in JMS Mats implementation, as we only "loan" JMS Sessions from the JmsMatsJmsSessionHandler,
         * which is the one that closes everything on shutdown.
         */
    }

    /**
     * Implementation of {@link JmsMatsTxContextKey}.
     */
    @Override
    public JmsMatsStage<?, ?, ?, ?> getStage() {
        // There is no stage, and contract is to return null.
        return null;
    }

    @Override
    public JmsMatsFactory<Z> getFactory() {
        return _parentFactory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JmsMatsInitiator<?> that = (JmsMatsInitiator<?>) o;
        return _parentFactory.equals(that._parentFactory) && _name.equals(that._name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_parentFactory, _name);
    }

    @Override
    public String idThis() {
        return id("JmsMatsInitiator{" + _name + "}", this) + "@" + _parentFactory;
    }

    @Override
    public String toString() {
        return idThis();
    }

    /**
     * Implementation of {@link InitiateInterceptContext}.
     */
    static class InitiateInterceptContextImpl implements InitiateInterceptContext {
        private final MatsInitiator _matsInitiator;
        private final Instant _startedInstant;
        private final long _startedNanos;

        public InitiateInterceptContextImpl(MatsInitiator matsInitiator, Instant startedInstant, long startedNanos) {
            _matsInitiator = matsInitiator;
            _startedInstant = startedInstant;
            _startedNanos = startedNanos;
        }

        @Override
        public MatsInitiator getInitiator() {
            return _matsInitiator;
        }

        @Override
        public Instant getStartedInstant() {
            return _startedInstant;
        }

        @Override
        public long getStartedNanoTime() {
            return _startedNanos;
        }
    }

    static class InitiateStartedContextImpl implements InitiateStartedContext {
        private final InitiateInterceptContextImpl _initiationInterceptContext;
        private final MatsInitiate _matsInitiate;

        public InitiateStartedContextImpl(
                InitiateInterceptContextImpl initiationInterceptContext, MatsInitiate matsInitiate) {
            _initiationInterceptContext = initiationInterceptContext;
            _matsInitiate = matsInitiate;
        }

        @Override
        public MatsInitiator getInitiator() {
            return _initiationInterceptContext.getInitiator();
        }

        @Override
        public Instant getStartedInstant() {
            return _initiationInterceptContext.getStartedInstant();
        }

        @Override
        public long getStartedNanoTime() {
            return _initiationInterceptContext.getStartedNanoTime();
        }

        @Override
        public void initiate(InitiateLambda lambda) {
            lambda.initiate(_matsInitiate);
        }
    }

    static class InitiateInterceptUserLambdaContextImpl implements InitiateInterceptUserLambdaContext {
        private final InitiateInterceptContextImpl _initiationInterceptContext;
        private final MatsInitiate _matsInitiate;

        public InitiateInterceptUserLambdaContextImpl(
                InitiateInterceptContextImpl initiationInterceptContext, MatsInitiate matsInitiate) {
            _initiationInterceptContext = initiationInterceptContext;
            _matsInitiate = matsInitiate;
        }

        @Override
        public MatsInitiator getInitiator() {
            return _initiationInterceptContext.getInitiator();
        }

        @Override
        public Instant getStartedInstant() {
            return _initiationInterceptContext.getStartedInstant();
        }

        @Override
        public long getStartedNanoTime() {
            return _initiationInterceptContext.getStartedNanoTime();
        }

        @Override
        public void initiate(InitiateLambda lambda) {
            lambda.initiate(_matsInitiate);
        }
    }

    static class InitiateInterceptOutgoingMessagesContextImpl implements InitiateInterceptOutgoingMessagesContext {
        private final InitiateInterceptContextImpl _initiateInterceptContext;
        private final MatsInitiate _matsInitiate;

        private final List<MatsEditableOutgoingMessage> _matsMessages;
        private final Consumer<String> _cancelOutgoingMessage;

        public InitiateInterceptOutgoingMessagesContextImpl(
                InitiateInterceptContextImpl initiateInterceptContext, MatsInitiate matsInitiate,
                List<MatsEditableOutgoingMessage> matsMessages,
                Consumer<String> cancelOutgoingMessage) {
            _initiateInterceptContext = initiateInterceptContext;
            _matsInitiate = matsInitiate;
            _matsMessages = matsMessages;
            _cancelOutgoingMessage = cancelOutgoingMessage;
        }

        @Override
        public MatsInitiator getInitiator() {
            return _initiateInterceptContext.getInitiator();
        }

        @Override
        public Instant getStartedInstant() {
            return _initiateInterceptContext.getStartedInstant();
        }

        @Override
        public long getStartedNanoTime() {
            return _initiateInterceptContext.getStartedNanoTime();
        }

        @Override
        public List<MatsEditableOutgoingMessage> getOutgoingMessages() {
            return _matsMessages;
        }

        @Override
        public void initiate(InitiateLambda lambda) {
            lambda.initiate(_matsInitiate);
        }

        @Override
        public void cancelOutgoingMessage(String matsMessageId) {
            _cancelOutgoingMessage.accept(matsMessageId);
        }
    }

    static class InitiateCompletedContextImpl implements InitiateCompletedContext {
        private final InitiateProcessResult _initiateProcessResult;

        private final InitiateInterceptContextImpl _initiateInterceptContext;

        private final long _userLambdaNanos;
        private final List<MatsMeasurement> _measurements;
        private final List<MatsTimingMeasurement> _timingMeasurements;
        private final long _envelopeSerializationNanos;
        private final long _messageSystemMessageProductionAndSendNanos;
        private final long _dbCommitNanos;
        private final long _messageSystemCommitNanos;
        private final long _totalProcessingNanos;
        private final Throwable _throwable;
        private final List<MatsSentOutgoingMessage> _messages;

        public InitiateCompletedContextImpl(
                InitiateProcessResult initiateProcessResult,
                InitiateInterceptContextImpl initiateInterceptContext,

                long userLambdaNanos,
                List<MatsMeasurement> measurements,
                List<MatsTimingMeasurement> timingMeasurements,
                long envelopeSerializationNanos,
                long dbCommitNanos,
                long messageSystemMessageProductionAndSendNanos,
                long messageSystemCommitNanos,
                long totalProcessingNanos,

                Throwable throwable,
                List<MatsSentOutgoingMessage> messages) {
            _initiateProcessResult = initiateProcessResult;

            _initiateInterceptContext = initiateInterceptContext;

            _userLambdaNanos = userLambdaNanos;
            _measurements = measurements;
            _timingMeasurements = timingMeasurements;
            _envelopeSerializationNanos = envelopeSerializationNanos;
            _messageSystemMessageProductionAndSendNanos = messageSystemMessageProductionAndSendNanos;
            _dbCommitNanos = dbCommitNanos;
            _messageSystemCommitNanos = messageSystemCommitNanos;
            _totalProcessingNanos = totalProcessingNanos;

            _throwable = throwable;
            _messages = messages;
        }

        @Override
        public InitiateProcessResult getInitiateProcessResult() {
            return _initiateProcessResult;
        }

        @Override
        public MatsInitiator getInitiator() {
            return _initiateInterceptContext.getInitiator();
        }

        @Override
        public Instant getStartedInstant() {
            return _initiateInterceptContext.getStartedInstant();
        }

        @Override
        public long getStartedNanoTime() {
            return _initiateInterceptContext.getStartedNanoTime();
        }

        @Override
        public long getUserLambdaNanos() {
            return _userLambdaNanos;
        }

        @Override
        public List<MatsMeasurement> getMeasurements() {
            return _measurements;
        }

        @Override
        public List<MatsTimingMeasurement> getTimingMeasurements() {
            return _timingMeasurements;
        }

        @Override
        public long getSumEnvelopeSerializationAndCompressionNanos() {
            return _envelopeSerializationNanos;
        }

        @Override
        public long getDbCommitNanos() {
            return _dbCommitNanos;
        }

        @Override
        public long getSumMessageSystemProductionAndSendNanos() {
            return _messageSystemMessageProductionAndSendNanos;
        }

        @Override
        public long getMessageSystemCommitNanos() {
            return _messageSystemCommitNanos;
        }

        @Override
        public long getTotalExecutionNanos() {
            return _totalProcessingNanos;
        }

        @Override
        public Optional<Throwable> getThrowable() {
            return Optional.ofNullable(_throwable);
        }

        @Override
        public List<MatsSentOutgoingMessage> getOutgoingMessages() {
            return _messages;
        }
    }

    static class MessageReferenceImpl implements MessageReference {
        private final String _matsMessageId;

        public MessageReferenceImpl(String matsMessageId) {
            _matsMessageId = matsMessageId;
        }

        @Override
        public String getMatsMessageId() {
            return _matsMessageId;
        }

        @Override
        public String toString() {
            return "matsMessageId=" + _matsMessageId;
        }
    }

    /**
     * This wrapping MatsInitiator effectively works like <code>propagation=REQUIRED</code>, in that if there is an
     * ongoing stage or initiation, any initiation done with it is "hoisted" up to the ongoing process. Otherwise, it
     * acts as normal - which is to create a transaction.
     */
    static class MatsInitiator_DefaultInitiator_TxRequired<Z> implements MatsInitiator {
        private final JmsMatsFactory<Z> _matsFactory;
        private final MatsInitiator _matsInitiator;

        public MatsInitiator_DefaultInitiator_TxRequired(JmsMatsFactory<Z> matsFactory,
                MatsInitiator matsInitiator) {
            _matsFactory = matsFactory;
            _matsInitiator = matsInitiator;
        }

        @Override
        public String getName() {
            return _matsInitiator.getName();
        }

        @Override
        public MatsFactory getParentFactory() {
            return _matsFactory;
        }

        @Override
        public void initiate(InitiateLambda lambda) throws MatsMessageSendException, MatsBackendException {
            Optional<Supplier<MatsInitiate>> matsInitiateForNesting = _matsFactory
                    .getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            // ?: Are we within an existing Mats demarcation?
            if (matsInitiateForNesting.isPresent()) {
                // -> Evidently within an existing Mats demarcation, so use the ThreadLocal MatsInitiate.
                lambda.initiate(matsInitiateForNesting.get().get());
            }
            else {
                // -> No, not within a Mats demarcation, so just forward the call directly to the MatsInitiator
                _matsInitiator.initiate(lambda);
            }
        }

        @Override
        public void initiateUnchecked(InitiateLambda lambda) throws MatsBackendRuntimeException,
                MatsMessageSendRuntimeException {
            Optional<Supplier<MatsInitiate>> matsInitiateForNesting = _matsFactory
                    .getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            // ?: Are we within an existing Mats demarcation?
            if (matsInitiateForNesting.isPresent()) {
                // -> Evidently within an existing Mats demarcation, so use the ThreadLocal MatsInitiate.
                lambda.initiate(matsInitiateForNesting.get().get());
            }
            else {
                // -> No, not within a Mats demarcation, so just forward the call directly to the MatsInitiator
                _matsInitiator.initiateUnchecked(lambda);
            }
        }

        @Override
        public void close() {
            Optional<Supplier<MatsInitiate>> matsInitiateForNesting = _matsFactory
                    .getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            // ?: Are we within an existing Mats demarcation?
            if (matsInitiateForNesting.isPresent()) {
                // -> Evidently within an existing Mats demarcation, so point this out pretty harshly.
                throw new IllegalStateException("This is the MatsFactory.getDefaultInitiator(), but it was gotten"
                        + " within a nested Mats demarcation, and is thus when within a Stage, a wrapper"
                        + " around the MatsInitiate from ProcessContext.initiate(..), or when within an initiation, "
                        + " a wrapper around the same MatsInitiate that you already have. It as such makes"
                        + " absolutely NO SENSE that you would want to close it: You've gotten the default "
                        + " MatsInitiator, *you are within a Mats processing context*, and then you invoke "
                        + " '.close()' on it?!");
            }
            else {
                // -> No, not within a Mats demarcation, so forward close call.
                _matsInitiator.close();
            }
        }

        @Override
        public String toString() {
            Optional<Supplier<MatsInitiate>> matsInitiateForNesting = _matsFactory
                    .getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            return matsInitiateForNesting.isPresent()
                    ? "[nested-process wrapper of MatsFactory.getDefaultInitiator()]@" + Integer
                            .toHexString(System.identityHashCode(this))
                    : _matsInitiator.toString();
        }
    }

    /**
     * This wrapping MatsInitiator effectively works like <code>propagation=REQUIRES_NEW</code>, in that if there is an
     * ongoing stage or initiation, any initiation done with it is given a new transactional context (currently by brute
     * force: create a new Thread and run it there!). Otherwise, it acts as normal - which is to create a transaction.
     */
    static class MatsInitiator_NamedInitiator_TxRequiresNew<Z> implements MatsInitiator {
        private final JmsMatsFactory<Z> _matsFactory;
        private final MatsInitiator _matsInitiator;

        public MatsInitiator_NamedInitiator_TxRequiresNew(JmsMatsFactory<Z> matsFactory,
                MatsInitiator matsInitiator) {
            _matsFactory = matsFactory;
            _matsInitiator = matsInitiator;
        }

        @Override
        public String getName() {
            return _matsInitiator.getName();
        }

        @Override
        public MatsFactory getParentFactory() {
            return _matsFactory;
        }

        @Override
        public void initiate(InitiateLambda lambda) throws MatsMessageSendException, MatsBackendException {
            Optional<Supplier<MatsInitiate>> matsInitiateForNesting = _matsFactory
                    .getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            // ?: Are we within an existing Mats demarcation?
            if (matsInitiateForNesting.isPresent()) {
                // -> Evidently within an existing Mats demarcation, so fire up a new subInitiateThread to do the
                // initiation.
                Throwable[] throwableResult = new Throwable[1];
                String threadName = Thread.currentThread().getName() + " StageNested {"
                        + _matsFactory.randomString(5) + '}';
                Map<String, String> copyOfContextMap = MDC.getCopyOfContextMap();
                Optional<NestingWithinStageProcessing<Z>> existingMatsTrace = _matsFactory
                        .getCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing();
                Thread subInitiateThread = new Thread(() -> {
                    MDC.setContextMap(copyOfContextMap);
                    existingMatsTrace.ifPresent(
                            _matsFactory::setCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing);
                    try {
                        _matsInitiator.initiate(lambda);
                    }
                    catch (Throwable t) {
                        throwableResult[0] = t;
                    }
                    finally {
                        // Not really necessary, since the thread currently exits and dies, but just to point out that
                        // I handle my resources correctly..! ;-)
                        _matsFactory.clearCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing();
                    }
                }, threadName);

                subInitiateThread.start();

                try {
                    subInitiateThread.join();
                }
                catch (InterruptedException e) {
                    // Pass on the interrupt to the sub-initiate Thread.
                    subInitiateThread.interrupt();
                    // Throw out.
                    throw new InterruptedRuntimeException("Got interrupted while waiting for sub-initiate"
                            + " Thread [" + subInitiateThread.getName() + "] to complete."
                            + " (Passed on the interrupt to that Thread).", e);
                }
                if (throwableResult[0] != null) {
                    Throwable t = throwableResult[0];
                    t.addSuppressed(new RuntimeException("Stacktrace representing actual sub-initiation point."));
                    if (t instanceof RuntimeException) {
                        throw (RuntimeException) t;
                    }
                    if (t instanceof MatsBackendException) {
                        throw (MatsBackendException) t;
                    }
                    if (t instanceof MatsMessageSendException) {
                        throw (MatsMessageSendException) t;
                    }
                    if (t instanceof Error) {
                        throw (Error) t;
                    }
                    throw new RuntimeException("Got undeclared checked Exception [" + t.getClass().getSimpleName()
                            + "]!", t);
                }
            }
            else {
                // -> No, not within a Mats demarcation, so just forward the call directly to the MatsInitiator
                _matsInitiator.initiate(lambda);
            }
        }

        @Override
        public void initiateUnchecked(InitiateLambda lambda) throws MatsBackendRuntimeException,
                MatsMessageSendRuntimeException {
            Optional<Supplier<MatsInitiate>> matsInitiateForNesting = _matsFactory
                    .getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            // ?: Are we within an existing Mats demarcation?
            if (matsInitiateForNesting.isPresent()) {
                // -> Evidently within an existing Mats demarcation, so fire up a new subInitiateThread to do the
                // initiation, thus ensuring "new context".
                Throwable[] throwableResult = new Throwable[1];
                String threadName = Thread.currentThread().getName() + " StageNested {"
                        + _matsFactory.randomString(5) + '}';
                Map<String, String> copyOfContextMap = MDC.getCopyOfContextMap();
                Optional<NestingWithinStageProcessing<Z>> existingMatsTrace = _matsFactory
                        .getCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing();
                Thread subInitiateThread = new Thread(() -> {
                    MDC.setContextMap(copyOfContextMap);
                    existingMatsTrace.ifPresent(
                            _matsFactory::setCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing);
                    MDC.setContextMap(copyOfContextMap);
                    try {
                        _matsInitiator.initiateUnchecked(lambda);
                    }
                    catch (Throwable t) {
                        throwableResult[0] = t;
                    }
                    finally {
                        // Not really necessary, since the thread currently exits and dies, but just to point out that
                        // I handle my resources correctly..! ;-)
                        _matsFactory.clearCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing();
                    }
                }, threadName);

                subInitiateThread.start();

                try {
                    subInitiateThread.join();
                }
                catch (InterruptedException e) {
                    // Pass on the interrupt to the sub-initiate Thread.
                    subInitiateThread.interrupt();
                    // Throw out.
                    throw new InterruptedRuntimeException("Got interrupted while waiting for sub-initiate"
                            + " Thread [" + subInitiateThread.getName() + "] to complete."
                            + " (Passed on the interrupt to that Thread).", e);
                }
                if (throwableResult[0] != null) {
                    Throwable t = throwableResult[0];
                    t.addSuppressed(new RuntimeException("Stacktrace representing actual sub-initiation point."));
                    // Also handles the two MatsBackendRuntimeException and MatsMessageSendRuntimeException
                    if (t instanceof RuntimeException) {
                        throw (RuntimeException) t;
                    }
                    if (t instanceof Error) {
                        throw (Error) t;
                    }
                    throw new RuntimeException("Got undeclared checked Exception [" + t.getClass().getSimpleName()
                            + "]!", t);
                }
            }
            else {
                // -> No, not within a Mats demarcation, so just forward the call directly to the MatsInitiator
                _matsInitiator.initiateUnchecked(lambda);
            }
        }

        static class InterruptedRuntimeException extends RuntimeException {
            public InterruptedRuntimeException(String message, Throwable cause) {
                super(message, cause);
            }
        }

        @Override
        public void close() {
            Optional<Supplier<MatsInitiate>> matsInitiateForNesting = _matsFactory
                    .getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            // ?: Are we within an existing Mats demarcation?
            if (matsInitiateForNesting.isPresent()) {
                // -> Evidently within an existing Mats demarcation, so point this out pretty harshly.
                throw new IllegalStateException("You are evidently within a Mats processing (stage or init),"
                        + " so it makes absolutely no sense to close the MatsInitiator now.");
            }
            else {
                // -> No, not within a Mats demarcation, so forward close call.
                _matsInitiator.close();
            }
        }

        @Override
        public String toString() {
            Optional<Supplier<MatsInitiate>> matsInitiateForNesting = _matsFactory
                    .getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            return matsInitiateForNesting.isPresent()
                    ? "[nested-process wrapper of MatsFactory.getOrCreateInitiator(" + getName() + ")]@"
                            + Integer.toHexString(System.identityHashCode(this))
                    : _matsInitiator.toString();
        }
    }
}
