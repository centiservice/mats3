package io.mats3.impl.jms;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.mats3.MatsEndpoint.DetachedProcessContext;
import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;
import io.mats3.MatsStage;
import io.mats3.MatsStage.StageConfig;
import io.mats3.api.intercept.MatsOutgoingMessage.DispatchType;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsEditableOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MessageType;
import io.mats3.api.intercept.MatsStageInterceptor;
import io.mats3.api.intercept.MatsStageInterceptor.MatsStageInterceptOutgoingMessages;
import io.mats3.api.intercept.MatsStageInterceptor.MatsStageInterceptUserLambda;
import io.mats3.api.intercept.MatsStageInterceptor.StageCommonContext;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext.StageProcessResult;
import io.mats3.api.intercept.MatsStageInterceptor.StageInterceptOutgoingMessageContext;
import io.mats3.api.intercept.MatsStageInterceptor.StageInterceptUserLambdaContext;
import io.mats3.api.intercept.MatsStageInterceptor.StagePreprocessAndDeserializeErrorContext;
import io.mats3.api.intercept.MatsStageInterceptor.StagePreprocessAndDeserializeErrorContext.StagePreprocessAndDeserializeError;
import io.mats3.api.intercept.MatsStageInterceptor.StageReceivedContext;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsMessageSendException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;
import io.mats3.impl.jms.JmsMatsProcessContext.DoAfterCommitRunnableHolder;
import io.mats3.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;
import io.mats3.impl.jms.JmsMatsTransactionManager.TransactionContext;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsSerializer.DeserializedMatsTrace;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.Call;
import io.mats3.serial.MatsTrace.Call.CallType;
import io.mats3.serial.MatsTrace.Call.MessagingModel;
import io.mats3.serial.MatsTrace.StackState;

/**
 * MessageConsumer-class for the {@link JmsMatsStage} which is instantiated {@link StageConfig#getConcurrency()} number
 * of times, carrying the run-thread.
 * <p>
 * Package access so that it can be referred to from JavaDoc.
 *
 * @author Endre Stølsvik 2019-08-24 00:11 - http://stolsvik.com/, endre@stolsvik.com
 */
class JmsMatsStageProcessor<R, S, I, Z> implements JmsMatsStatics, JmsMatsTxContextKey, JmsMatsStartStoppable {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsStageProcessor.class);

    private final String _randomInstanceId;
    private final JmsMatsStage<R, S, I, Z> _jmsMatsStage;
    private final int _processorNumber;
    private final PriorityFilter _priorityFilter;
    private final Thread _processorThread;
    private final TransactionContext _transactionContext;

    JmsMatsStageProcessor(JmsMatsStage<R, S, I, Z> jmsMatsStage, int processorNumber,
            PriorityFilter priorityFilter) {
        _randomInstanceId = randomString(5);
        _jmsMatsStage = jmsMatsStage;
        _processorNumber = processorNumber;
        _priorityFilter = priorityFilter;
        _transactionContext = jmsMatsStage.getParentFactory()
                .getJmsMatsTransactionManager().getTransactionContext(this);
        _processorThread = new Thread(this::runner, THREAD_PREFIX + ident());
    }

    public enum PriorityFilter {
        UNFILTERED("unfilt"),

        STANDARD_ONLY("std"),

        INTERACTIVE_ONLY("pri");

        public final String threadSuffix;

        PriorityFilter(String threadSuffix) {
            this.threadSuffix = threadSuffix;
        }
    }

    private volatile boolean _runFlag = true; // Start off running.

    private volatile JmsSessionHolder _jmsSessionHolder;

    private String ident() {
        return _jmsMatsStage.getStageId() + " ("
                + _priorityFilter.threadSuffix
                + "#"
                + _processorNumber
                + ") {" + _randomInstanceId + '}'
                + " @ " + _jmsMatsStage.getParentFactory().idThis();
    }

    @Override
    public JmsMatsStage<?, ?, ?, ?> getStage() {
        return _jmsMatsStage;
    }

    @Override
    public JmsMatsFactory<Z> getFactory() {
        return _jmsMatsStage.getParentFactory();
    }

    @Override
    public List<JmsMatsStartStoppable> getChildrenStartStoppable() {
        throw new AssertionError("This method should not have been called for [" + idThis() + "]");
    }

    private volatile boolean _processorInReceive;

    @Override
    public void start() {
        _processorThread.start();
    }

    @Override
    public void stopPhase0_SetRunFlagFalse() {
        // Start by setting the run-flag to false..
        _runFlag = false;
    }

    @Override
    public void stopPhase1_CloseSessionIfInReceive() {
        /*
         * Trying to make very graceful: If we're in consumer.receive(), then close the Session, which makes the
         * receive()-call return null, causing the thread to loop and check run-flag. If not, then assume that the
         * thread is out doing work, and it will see that the run-flag is false upon next loop.
         *
         * We won't put too much effort in making this race-proof, as if it fails, which should be seldom, the only
         * problems are a couple of ugly stack traces in the log: Transactionality will keep integrity.
         */

        // ?: Has processorThread already exited?
        if (!_processorThread.isAlive()) {
            // -> Yes, thread already exited, and it should thus have closed the JMS Session.
            // 1. JavaDoc isAlive(): "A thread is alive if it has been started and has not yet died."
            // 2. The Thread is started in the constructor.
            // 3. Thus, if it is not alive, there is NO possibility that it is starting, or about to be started.
            log.info(LOG_PREFIX + ident() + " has already exited, it should have closed JMS Session.");
            return;
        }

        // E-> ?: Is thread currently waiting in consumer.receive()?
        // First we do a repeated "pre-check", and wait a tad if it isn't in receive yet (this happens too often in
        // tests, where the system is being closed down before the run-loop has gotten back to consumer.receive())
        for (int i = 0; i < 50; i++) {
            // ?: Have we gotten to receive?
            if (_processorInReceive) {
                // -> Yes, gotten to receive, so break out of chill-loop.
                break;
            }
            chillWait(2);
            // ?: Is the thread dead?
            if (!_processorThread.isAlive()) {
                // -> Yes, thread is dead, so it has already exited.
                log.info(LOG_PREFIX + ident() + " has now exited, it should have closed JMS Session.");
                return;
            }
        }
        // ?: Is the thread in consumer.receive() now?
        if (_processorInReceive) {
            // -> Yes, waiting in receive(), so close session, thus making receive() return null.
            log.info(LOG_PREFIX + ident() + " is waiting in consumer.receive(), so we'll close the current"
                    + " JmsSessionHolder thereby making the receive() call return null, and the thread will exit.");
            closeCurrentSessionHolder();
        }
        else {
            // -> No, not in receive()
            log.info(LOG_PREFIX + ident() + " is NOT waiting in consumer.receive(), so we assume it is out"
                    + " doing work, and will come back and see the run-flag being false, thus exit.");
        }
    }

    @Override
    public void stopPhase2_GracefulWaitAfterRunflagFalse(int gracefulShutdownMillis) {
        if (_processorThread.isAlive()) {
            log.info(LOG_PREFIX + "Thread " + ident() + " is running, waiting for it to exit gracefully for ["
                    + gracefulShutdownMillis + " ms].");
            joinProcessorThread(gracefulShutdownMillis);
            // ?: Did the thread exit?
            if (!_processorThread.isAlive()) {
                // -> Yes, thread exited.
                log.info(LOG_PREFIX + ident() + " exited nicely, and either we closed the JMS session above, or"
                        + " the thread did it on its way out.");
            }
        }
    }

    @Override
    public void stopPhase3_InterruptIfStillAlive() {
        if (_processorThread.isAlive()) {
            // -> No, thread did not exit within graceful wait period.
            log.warn(LOG_PREFIX + ident() + " DID NOT exit after grace period, so interrupt it and wait some more.");
            // Interrupt the processor thread from whatever it is doing.
            _processorThread.interrupt();
        }
    }

    @Override
    public boolean stopPhase4_GracefulWaitAfterInterrupt() {
        if (_processorThread.isAlive()) {
            // Wait a small time more after the interrupt.
            joinProcessorThread(EXTRA_GRACE_MILLIS);
            // ?: Did the thread exit now?
            if (_processorThread.isAlive()) {
                // -> No, thread still not exited. Close the JMS session "in his face" to clean this up.
                log.warn(LOG_PREFIX + ident() + " DID NOT exit even after being interrupted."
                        + " Giving up, closing JMS Session to clean up. This isn't all that good, should be looked"
                        + " into why the thread is so stuck.");
                closeCurrentSessionHolder();
            }
            else {
                // -> Yes, thread exited.
                log.info(LOG_PREFIX + ident()
                        + " exited after being interrupted, it should have closed the JMS Session on its way out.");
            }
        }
        return !_processorThread.isAlive();
    }

    private void closeCurrentSessionHolder() {
        JmsSessionHolder currentJmsSessionHolder = _jmsSessionHolder;
        if (currentJmsSessionHolder != null) {
            currentJmsSessionHolder.close();
        }
        else {
            log.info(LOG_PREFIX + "There was no JMS Session in place...");
        }
    }

    private void joinProcessorThread(int gracefulWaitMillis) {
        try {
            _processorThread.join(gracefulWaitMillis);
        }
        catch (InterruptedException e) {
            log.warn(LOG_PREFIX + "Got InterruptedException when waiting for " + ident() + " to join."
                    + " Dropping out.");
        }
    }

    private void runner() {
        boolean nullFromReceiveThusSessionIsClosed = false;
        // :: OUTER RUN-LOOP, where we'll get a fresh JMS Session, Destination and MessageConsumer.
        OUTER: while (_runFlag) {
            // :: Putting "static values" into logger MDC (also clearing, if we've come here after failure)
            clearAndSetStaticMdcValues();
            log.info(LOG_PREFIX + "Initializing: Getting JMS Session, Destination("
                    + (_jmsMatsStage.isQueue() ? "Queue" : "Topic")
                    + ") and MessageConsumer for stage [" + _jmsMatsStage.getStageId() + "].");

            { // Local-scope the 'newJmsSessionHolder' variable.
                JmsSessionHolder newJmsSessionHolder;
                try {
                    newJmsSessionHolder = _jmsMatsStage.getParentFactory()
                            .getJmsMatsJmsSessionHandler().getSessionHolder(this);
                }
                catch (JmsMatsJmsException | RuntimeException t) {
                    log.warn(LOG_PREFIX + "Got " + t.getClass().getSimpleName() + " while trying to get new"
                            + " JmsSessionHolder. Chilling a bit, then looping to check run-flag.", t);
                    /*
                     * Doing a "chill-wait", so that if we're in a situation where this will tight-loop, we won't
                     * totally swamp both CPU and logs with meaninglessness.
                     */
                    chillWait();
                    continue;
                }
                // :: "Publish" the new JMS Session.
                synchronized (this) {
                    // ?: Check the run-flag one more time!
                    if (!_runFlag) {
                        // -> we're asked to exit.
                        // NOTICE! Since this JMS Session has not been "published" outside yet, we'll have to
                        // close it directly.
                        newJmsSessionHolder.close();
                        // Break out of run-loop.
                        break;
                    }
                    else {
                        // -> Yes, we're good! "Publish" the new JMS Session.
                        _jmsSessionHolder = newJmsSessionHolder;
                    }
                }
            }
            try { // catch-all-Throwable, as we do not ever want the thread to die - and handles jmsSession.crashed()
                Session jmsSession = _jmsSessionHolder.getSession();
                Destination destination = createJmsDestination(jmsSession, getFactory().getFactoryConfig());
                MessageConsumer jmsConsumer;
                switch (_priorityFilter) {
                    case STANDARD_ONLY:
                        // Only select the non-high-pri messages
                        // The only priorities Mats use are 4 (default) and 9 (highest)
                        // Using non-equal, just in case there ever were to come something outside of that.
                        jmsConsumer = jmsSession.createConsumer(destination, "JMSPriority <> 9");
                        break;
                    case INTERACTIVE_ONLY:
                        // Only select the high-pri messages
                        jmsConsumer = jmsSession.createConsumer(destination, "JMSPriority = 9");
                        break;
                    default:
                        // All else: Consume everything.
                        jmsConsumer = jmsSession.createConsumer(destination);
                }

                // We've established the consumer, and hence will start to receive messages and process them.
                // (Important for topics, where if we haven't established consumer, we won't get messages).
                // TODO: Handle ability to stop with subsequent re-start of endpoint.
                _jmsMatsStage.getAnyProcessorMadeConsumerLatch().countDown();

                // :: INNER RECEIVE-LOOP, where we'll use the JMS Session and MessageConsumer.receive().
                while (_runFlag) {
                    // :: Cleanup of MDC (for subsequent messages, also after Exceptions..)
                    clearAndSetStaticMdcValues();
                    // Check whether Session/Connection is ok (per contract with JmsSessionHolder)
                    _jmsSessionHolder.isSessionOk();
                    // :: GET NEW MESSAGE!! THIS IS THE MESSAGE PUMP!
                    // Using array so that we can null it out after fetching data from it, hoping to help GC.
                    Message[] messageA = new Message[1];
                    try {
                        if (log.isDebugEnabled()) log.debug(LOG_PREFIX
                                + "Going into JMS consumer.receive() for [" + destination + "].");
                        _processorInReceive = true;
                        messageA[0] = jmsConsumer.receive();
                    }
                    finally {
                        _processorInReceive = false;
                    }
                    long[] nanos_Received = { System.nanoTime() };
                    Instant[] instant_Received = { Instant.now() };

                    // Need to check whether the JMS Message gotten is null, as that signals that the
                    // Consumer, Session or Connection was closed from another thread.
                    if (messageA[0] == null) {
                        // ?: Are we shut down?
                        if (!_runFlag) {
                            // -> Yes, down
                            log.info(LOG_PREFIX + "Got null from JMS consumer.receive(), and run-flag is false."
                                    + " Breaking out of run-loop to exit.");
                            // Since this means that the session was closed "from the outside", we'll NOT close it
                            // from our side (here in the processor thread)
                            nullFromReceiveThusSessionIsClosed = true;
                            break OUTER;
                        }
                        else {
                            // -> No, not down: Something strange has happened.
                            log.warn(LOG_PREFIX + "!! Got null from JMS consumer.receive(), but run-flag is still"
                                    + " true. Closing current JmsSessionHolder to clean up. Looping to get new.");
                            closeCurrentSessionHolder();
                            continue OUTER;
                        }
                    }

                    // :: Perform the work inside the TransactionContext
                    DoAfterCommitRunnableHolder doAfterCommitRunnableHolder = new DoAfterCommitRunnableHolder();
                    JmsMatsInternalExecutionContext internalExecutionContext = JmsMatsInternalExecutionContext
                            .forStage(_jmsSessionHolder, jmsConsumer);

                    // The messages produced in a stage - will be reused (cleared) if nextDirect is invoked by stage
                    List<JmsMatsMessage<Z>> stageMessagesProduced = new ArrayList<>();
                    // Final list of messages to send (taking into account any nextDirect invocations)
                    List<JmsMatsMessage<Z>> allMessagesToSend = new ArrayList<>();

                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    StageCommonContextImpl<Z>[] stageCommonContext = new StageCommonContextImpl[1];

                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    List<MatsStageInterceptor>[] interceptorsForStage = new List[1];

                    long[] nanosTaken_UserLambda = { 0L };
                    long[] nanosTaken_totalEnvelopeSerAndComp = { 0L };
                    long[] nanosTaken_totalMsgSysProdAndSend = { 0L };

                    boolean preprocessOrDeserializeError = false;
                    Throwable throwableResult = null;
                    StageProcessResult throwableStageProcessResult = null;

                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    JmsMatsProcessContext<R, S, Z>[] processContext = new JmsMatsProcessContext[1];

                    try { // try-catch-finally: Catch processing Exceptions, handle cleanup in finally

                        // :: Going into Mats Transaction
                        _transactionContext.doTransaction(internalExecutionContext, () -> {

                            PreprocessAndDeserializeData<S, Z> data = preprocessAndDeserializeData(getFactory(),
                                    _jmsMatsStage, nanos_Received[0], instant_Received[0], messageA);

                            // Update the MatsTrace with stage-incoming timestamp - handles the "endpoint entered" logic
                            data.matsTrace.setStageEnteredTimestamp(instant_Received[0].toEpochMilli());
                            // Fetch endpoint entered (Not same as stage entered - only initial stage is "ep entered")
                            long endpointEnteredTimestamp = data.matsTrace.getSameHeightEndpointEnteredTimestamp();

                            // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
                            // +++++ START: nextDirect handling
                            // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

                            String threadName = Thread.currentThread().getName();

                            // Initially, The current stage is obviously /this/ stage on the first pass.
                            JmsMatsStage<R, S, ?, Z> currentStage = _jmsMatsStage;
                            long sameHeightOutgoingTimestamp = data.matsTrace.getSameHeightOutgoingTimestamp();
                            S incomingAndOutgoingSto = data.sto;
                            Object incomingDto = data.dto;

                            boolean nextDirectInvoked = false;
                            JmsMatsStage<R, S, ?, Z> previousStage = null;
                            do {
                                // :: Nested initiation handling
                                // For initiation within this transaction demarcation (ProcessContext or
                                // DefaultInitiator)
                                final String currentStageId = currentStage.getStageId();
                                getFactory().setCurrentMatsFactoryThreadLocal_ExistingMatsInitiate(
                                        () -> JmsMatsInitiate.createForChildFlow(getFactory(), stageMessagesProduced,
                                                internalExecutionContext, doAfterCommitRunnableHolder, data.matsTrace,
                                                currentStageId));
                                // This is within a stage processing, so store the context for any nested inits
                                // This is relevant both if within current transaction demarcation, but also if explicit
                                // going outside when using non-default initiator: matsFactory.getOrCreateInitiator.
                                getFactory().setCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing(
                                        data.matsTrace, currentStageId, jmsConsumer);

                                // :: Create contexts, invoke interceptors
                                // ==========================================================

                                // .. create the ProcessContext
                                JmsMatsProcessContext<R, S, Z> processContext_l = JmsMatsProcessContext.create(
                                        getFactory(),
                                        currentStage.getParentEndpoint().getEndpointId(),
                                        currentStage.getStageId(),
                                        data.jmsMessageId,
                                        currentStage.getNextStageId(),
                                        currentStage.getStageConfig().getOrigin(),
                                        data.matsTrace,
                                        incomingAndOutgoingSto,
                                        nextDirectInvoked
                                                ? processContext[0].getOutgoingBinaries() // fetch from previous
                                                : data.incomingBinaries,
                                        nextDirectInvoked
                                                ? processContext[0].getOutgoingStrings() // fetch from previous
                                                : data.incomingStrings,
                                        stageMessagesProduced,
                                        internalExecutionContext,
                                        doAfterCommitRunnableHolder);
                                if (nextDirectInvoked) {
                                    processContext_l.overrideForNextDirect(getFactory().getFactoryConfig().getAppName(),
                                            getFactory().getFactoryConfig().getAppVersion(),
                                            previousStage.getStageId(),
                                            instant_Received[0]);
                                }
                                // Now set it
                                processContext[0] = processContext_l;

                                // .. stick the ProcessContext into the ThreadLocal scope
                                JmsMatsContextLocalCallback.bindResource(ProcessContext.class, processContext_l);

                                // Cast the ProcessContext unchecked, since we need it for the interceptors.
                                @SuppressWarnings("unchecked")
                                ProcessContext<Object> processContextCasted = (ProcessContext<Object>) processContext_l;

                                // Create the common part of the interceptor contexts
                                long nanosTaken_PreUserLambda = System.nanoTime() - nanos_Received[0];
                                stageCommonContext[0] = nextDirectInvoked
                                        ? StageCommonContextImpl.forNextDirect(processContextCasted,
                                                currentStage, nanos_Received[0], instant_Received[0],
                                                endpointEnteredTimestamp, sameHeightOutgoingTimestamp,
                                                data.matsTrace, incomingDto, incomingAndOutgoingSto)
                                        : StageCommonContextImpl.ordinary(processContextCasted,
                                                currentStage, nanos_Received[0], instant_Received[0],
                                                endpointEnteredTimestamp, sameHeightOutgoingTimestamp,
                                                data.matsTrace, incomingDto, incomingAndOutgoingSto,
                                                data.nanosTaken_DeconstructMessage,
                                                data.deserializedMatsTrace.getSizeIncoming(),
                                                data.deserializedMatsTrace.getNanosDecompression(),
                                                data.deserializedMatsTrace.getSizeDecompressed(),
                                                data.deserializedMatsTrace.getNanosDeserialization(),
                                                data.nanosTaken_incomingMessageAndStateDeserializationNanos,
                                                nanosTaken_PreUserLambda);

                                // Fetch relevant interceptors
                                interceptorsForStage[0] = getFactory().getInterceptorsForStage(stageCommonContext[0]);

                                // === Invoke any interceptors, stage "Received"
                                invokeStageReceivedInterceptors(interceptorsForStage[0], stageCommonContext[0]);

                                // === Invoke any interceptors, stage "Intercept" - these will wrap the UserLambda.
                                ProcessLambda<Object, Object, Object> currentLambda = invokeWrapUserLambdaInterceptors(
                                        currentStage, interceptorsForStage[0], stageCommonContext[0]);

                                // == Invoke the UserLambda, possibly wrapped.
                                long nanosAtStart_UserLambda = System.nanoTime();
                                try {
                                    currentLambda.process(processContextCasted, incomingAndOutgoingSto, incomingDto);
                                }
                                finally {
                                    nanosTaken_UserLambda[0] = System.nanoTime() - nanosAtStart_UserLambda;
                                }

                                // === Invoke any interceptors, stage "Message"
                                invokeStageMessageInterceptors(interceptorsForStage[0], stageCommonContext[0],
                                        stageMessagesProduced);

                                // Copy the stageMessagesProduced over to allMessagesToSend, so that they'll be sent.
                                allMessagesToSend.addAll(stageMessagesProduced);

                                // :: Handle NEXT_DIRECT "calls".
                                nextDirectInvoked = processContext[0].isNextDirectInvoked();
                                if (nextDirectInvoked) {
                                    long nanosTaken_TotalStartReceiveToFinished = System.nanoTime() - nanos_Received[0];

                                    // :: Invoke StageCompletedInterceptors
                                    // NOTE: All stageMessagesProduces shall be STAGE_INIT, i.e. initiations.
                                    // (It is not possible to mix nextDirect with any other flow message).
                                    invokeStageCompletedInterceptors(internalExecutionContext, stageCommonContext[0],
                                            interceptorsForStage[0], nanosTaken_UserLambda[0],
                                            nanosTaken_totalEnvelopeSerAndComp[0], nanosTaken_totalMsgSysProdAndSend[0],
                                            null, processContext[0], nanosTaken_TotalStartReceiveToFinished,
                                            stageMessagesProduced, null, Collections.emptyList(),
                                            stageMessagesProduced, StageProcessResult.NEXT_DIRECT);

                                    // Change timestamps for "received" and sameHeightOutgoingTimestamp.
                                    nanos_Received[0] = System.nanoTime();
                                    Instant now = Instant.now();
                                    instant_Received[0] = now;
                                    sameHeightOutgoingTimestamp = now.toEpochMilli();

                                    // Blood-hack to add the TraceProperties to existing MatsTrace, both for keeping
                                    // on any later actual new message (with new MatsTrace), but also nextDirect stage.
                                    for (Entry<String, Object> entry : processContext_l.getOutgoingProps().entrySet()) {
                                        data.matsTrace.setTraceProperty(entry.getKey(),
                                                getFactory().getMatsSerializer().serializeObject(entry.getValue()));
                                    }

                                    // Clear the stageMessagesProduced, for nextDirect stage to have clear list.
                                    stageMessagesProduced.clear();

                                    // Current stage is now the next stage
                                    previousStage = currentStage;
                                    currentStage = currentStage.getNextStage();

                                    // Set the incoming DTO (may be null).
                                    incomingDto = processContext_l.getNextDirectDto();

                                    // Set new ThreadName
                                    Thread.currentThread().setName(THREAD_PREFIX + currentStage.getStageId()
                                            + " (nd) - " + ident());
                                }
                            } while (nextDirectInvoked);

                            // Reset name if NEXT_DIRECT has changed it.
                            Thread.currentThread().setName(threadName);

                            // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
                            // +++++ END: nextDirect handling
                            // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

                            // :: Send messages on target queues/topics
                            // =======================================================================================

                            /*
                             * Concatenate all TraceIds for the incoming message and any initiations, to put on the MDC.
                             * - which is good for the logging, so that they are all present on the MDC for the
                             * send/commit log lines, and in particular if we get any Exceptions when committing. Notice
                             * that the typical situation is that there is just one traceId (incoming + request or
                             * reply, which have the same traceId), as stage-initiations are a more seldom situation.
                             */
                            concatAllTraceIds(data.matsTrace.getTraceId(), allMessagesToSend);

                            // :: Send any outgoing Mats messages (replies, requests, new messages etc..)
                            // ?: Any messages produced?
                            if (!allMessagesToSend.isEmpty()) {
                                // -> Yes, there are messages, so send them.
                                // (commit is performed when it exits the transaction lambda)

                                long nanosAtStart_totalEnvelopeSerialization = System.nanoTime();
                                for (JmsMatsMessage<Z> matsMessage : allMessagesToSend) {
                                    matsMessage.serializeAndCacheMatsTrace();
                                }
                                long nowNanos = System.nanoTime();
                                nanosTaken_totalEnvelopeSerAndComp[0] = nowNanos
                                        - nanosAtStart_totalEnvelopeSerialization;

                                long nanosAtStart_totalProduceAndSendMsgSysMessages = nowNanos;
                                produceAndSendMsgSysMessages(log, _jmsSessionHolder, getFactory(), allMessagesToSend);
                                nanosTaken_totalMsgSysProdAndSend[0] = System.nanoTime() -
                                        nanosAtStart_totalProduceAndSendMsgSysMessages;
                            }
                        }); // End: Mats Transaction

                        // ----- Transaction is now committed (if exceptions were raised, we've been thrown out earlier)

                        // :: Handle the DoAfterCommit lambda.
                        try {
                            doAfterCommitRunnableHolder.runDoAfterCommitIfAny();
                        }
                        catch (RuntimeException | AssertionError e) {
                            // Message processing is per definition finished here, so no way to DLQ or otherwise
                            // notify world except logging an error.
                            log.error(LOG_PREFIX + "Got [" + e.getClass().getSimpleName()
                                    + "] when running the doAfterCommit Runnable. Ignoring.", e);
                        }
                    }

                    // ===== CATCH: HANDLE THE DIFFERENT ERROR SITUATIONS =====

                    catch (JmsMessageReadFailure_JmsMatsJmsException e) {
                        // Special case of JmsMatsJmsException indicating that reading from JMS Message failed.
                        // NOTE: This means we will NOT run the "completed" Interceptors.
                        preprocessOrDeserializeError = true;
                        // Throw on to crash the JMS Session
                        throw e;
                    }
                    catch (JmsMessageProblem_RefuseMessageException e) {
                        // Special case of MatsRefuseMessageException indicating that there are issues with the JMS
                        // message - handled by tx mgr, attempted insta-rollback (refuse).
                        // NOTE: This means we will NOT run the "completed" Interceptors.
                        preprocessOrDeserializeError = true;
                        // .. This is handled by transaction manager (rollback), so we should just continue.
                        continue;
                    }
                    catch (JmsMatsMessageSendException e) {
                        /*
                         * This is the special situation which is the "VERY BAD!" scenario, i.e. DB was committed, but
                         * JMS was not, which is .. very bad. JmsMatsMessageSendException is a JmsMatsJmsException, and
                         * that indicates that there was a problem with JMS - so we should "crash" the JmsSessionHolder
                         * to signal that the JMS Connection is probably broken.
                         */
                        // :: Create the API-level Exception for this situation, so that interceptor will get that.
                        MatsMessageSendException exceptionForInterceptor = new MatsMessageSendException("Evidently got"
                                + " problems sending out the JMS message after having run the process lambda and"
                                + " potentially committed other resources, typically database.", e);
                        // Record for interceptor
                        throwableResult = exceptionForInterceptor;
                        throwableStageProcessResult = StageProcessResult.SYSTEM_EXCEPTION;
                        // Throw on (the original exception) to crash the JMS Session
                        throw e;
                    }
                    catch (JmsMatsJmsException e) {
                        /*
                         * Catch any other JmsMatsJmsException, as that most probably indicates that there was some
                         * serious problem with JMS - so we should "crash" the JmsSessionHolder to signal that the JMS
                         * Connection is probably broken. This is a lesser evil than JmsMatsMessageSendException (aka
                         * "VERY BAD!"), as we've not committed neither DB nor JMS.
                         */
                        // Notice that we shall NOT have committed "external resources" at this point, meaning database.
                        // :: Create the API-level Exception for this situation, so that interceptor will get that.
                        MatsBackendException exceptionForInterceptor = new MatsBackendException("Evidently have"
                                + " problems talking with our backend, which is a JMS Broker.", e);
                        // Record for interceptor
                        throwableResult = exceptionForInterceptor;
                        throwableStageProcessResult = StageProcessResult.SYSTEM_EXCEPTION;
                        // Throw on (the original exception) to crash the JMS Session
                        throw e;
                    }
                    catch (JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException e) {
                        // Just record this for interceptor - but take the cause, since that is the actual exception.
                        throwableResult = e.getCause();
                        throwableStageProcessResult = StageProcessResult.USER_EXCEPTION;
                        // .. This is handled by transaction manager (rollback), so we should just continue.
                        continue;
                    }
                    // Handle most other Throwables that may come out, except Errors which are not AssertionError.
                    catch (MatsRefuseMessageException | RuntimeException | AssertionError e) {
                        // Just record this for interceptor..
                        throwableResult = e;
                        throwableStageProcessResult = StageProcessResult.USER_EXCEPTION;
                        // .. These are handled by transaction manager (rollback), so we should just continue.
                        continue;
                    }

                    // ===== FINALLY: CLEAN UP, AND INVOKE ERROR OR COMPLETE INTERCEPTORS =====

                    finally {
                        JmsMatsContextLocalCallback.unbindResource(ProcessContext.class);
                        getFactory().clearCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
                        getFactory().clearCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing();

                        if (preprocessOrDeserializeError) {
                            log.info(LOG_PREFIX + "Got a preprocess (deconstruct) or deserialize error - this is either"
                                    + " handled by the Mats TransactionManager, or it will \"crash\" the JMS Session"
                                    + " and recreate the connectivity - look at log message above.");
                        }
                        else if (throwableStageProcessResult == StageProcessResult.USER_EXCEPTION) {
                            log.info(LOG_PREFIX + "Got [" + throwableResult.getClass().getName()
                                    + "] inside transactional message processing, which most probably originated from"
                                    + " user code. The situation shall have been handled by the Mats TransactionManager"
                                    + " (rollback). Looping to fetch next message.");
                        }
                        else if (throwableStageProcessResult == StageProcessResult.SYSTEM_EXCEPTION) {
                            log.info(LOG_PREFIX + "Got [" + throwableResult.getClass().getName()
                                    + "] inside transactional message processing which seems to come from the messaging"
                                    + " system - this probably means that the JMS Connectivity have gone to bits,"
                                    + " and we will thus \"crash\" the JMS Session and recreate the connectivity.");
                        }
                        else {
                            // -> All good!
                            if (log.isDebugEnabled()) log.debug(LOG_PREFIX + " The Stage processing went without a"
                                    + " hitch.");
                        }

                        long nanosTaken_TotalStartReceiveToFinished = System.nanoTime() - nanos_Received[0];

                        // === Invoke any interceptors, stage "Completed"

                        // ?: Was there a preprocess/deserialization error? (In which case no "completed")
                        if (!preprocessOrDeserializeError) {
                            // -> No, not preprocess/deserialization error. Normal processing.
                            invokeFinalStageCompletedInterceptors(internalExecutionContext, stageMessagesProduced,
                                    stageCommonContext[0], interceptorsForStage[0], nanosTaken_UserLambda[0],
                                    nanosTaken_totalEnvelopeSerAndComp[0], nanosTaken_totalMsgSysProdAndSend[0],
                                    throwableResult, throwableStageProcessResult, processContext[0],
                                    nanosTaken_TotalStartReceiveToFinished);
                        }
                    }

                    // MDC is cleared afterwards, at top of loop.
                } // End: INNER RECEIVE-LOOP
            }

            // catch-all-throwable, as we do not ever want the thread to die.
            // Will crash the jmsSession - or exit out if we've been told to.
            catch (Throwable t) { // .. amongst which is JmsMatsJmsException, JMSException, all Error except Assert.
                /*
                 * NOTE: We catch all Errors /except/ AssertionError here (which is caught above in the inner receive
                 * loop), assuming that AssertionError only is thrown by user code or JmsMats implementation code which
                 * DO NOT refer to bad JMS connectivity. Were this assumption to fail - i.e. that ActiveMQ code throws
                 * AssertionError at some point which effectively indicates that the JMS Connection is dead - then we
                 * could be screwed: That would be handled as a "good path" (as a message processing which raised some
                 * objection), and go back to the consume-loop. However, one would think that on the next
                 * consumer.receive() call, a JMSException would be thrown, sending us down here and thus crash the
                 * jmsSession and go back "booting up" a new Connection.
                 */
                /*
                 * Annoying stuff of ActiveMQ that if you are "thrown out" due to interrupt from outside, it sets the
                 * interrupted status of the thread before the throw, therefore any new actions on any JMS object will
                 * insta-throw InterruptedException again. Therefore, we read (and clear) the interrupted flag here,
                 * since we do actually check whether we should act on anything that legitimately could have interrupted
                 * us: Interrupt for shutdown.
                 */
                boolean isThreadInterrupted = Thread.interrupted();
                /*
                 * First check the run flag before chilling, if the reason for Exception is closed Connection or Session
                 * due to shutdown. (ActiveMQ do not let you create Session if Connection is closed, and do not let you
                 * create a Consumer if Session is closed.)
                 */
                // ?: Should we still be running?
                if (!_runFlag) {
                    // -> No, not running anymore, so exit.
                    // ?: Decide between INFO and WARN-with-Exception - this is to have a nice exit w/o stacktraces.
                    if ((t.getCause() != null)
                            && t.getCause().getClass().isAssignableFrom(InterruptedException.class)) {
                        log.info(LOG_PREFIX + "Got [" + t.getClass().getSimpleName() + "]->cause:InterruptedException,"
                                + " inside the message processing loop, and the run-flag was false,"
                                + " so we shortcut to exit.");
                    }
                    else {
                        log.warn(LOG_PREFIX + "Got [" + t.getClass().getSimpleName() + "] inside the message processing"
                                + "loop, but the run-flag was false, so we shortcut to exit.", t);
                    }
                    // Shortcut to exit.
                    break;
                }
                // E-> Yes, we should still be running.
                // :: Log, "crash" JMS Session (thus ditching entire connection)
                log.warn(LOG_PREFIX + "Got [" + t.getClass().getSimpleName() + "] inside the message processing loop"
                        + (isThreadInterrupted ? " (NOTE: Interrupted status of Thread was 'true', now cleared)" : "")
                        + ", crashing JmsSessionHolder, chilling a bit, then looping.", t);
                _jmsSessionHolder.crashed(t);
                /*
                 * Doing a "chill-wait", so that if we're in a situation where this will tight-loop, we won't totally
                 * swamp both CPU and logs with meaninglessness.
                 */
                chillWait();
            }
        } // END: OUTER RUN-LOOP

        // If we exited out while processing, just clean up so that the exit line does not look like it came from msg.
        clearAndSetStaticMdcValues();
        // :: log "exit line".
        // ?: Should we close the current JMS SessionHolder?
        if (nullFromReceiveThusSessionIsClosed) {
            // -> No, the JMS SessionHolder has been closed from the outside (since we got 'null' from the receive),
            // so DO NOT do it here, thus avoiding loglines about double closes.
            log.info(LOG_PREFIX + ident() + " asked to exit, and that we do! NOT closing current JmsSessionHolder,"
                    + " as that was done from the outside.");
        }
        else {
            // -> Yes, evidently NOT closed from the outside, so DO it here (if double-close, the holder will catch it)
            log.info(LOG_PREFIX + ident() + " asked to exit, and that we do! Closing current JmsSessionHolder.");
            // Close current JMS SessionHolder.
            closeCurrentSessionHolder();
        }
        _jmsMatsStage.removeStageProcessorFromList(this);
    }

    private static class PreprocessAndDeserializeData<S, Z> {
        final MatsTrace<Z> matsTrace;
        final String jmsMessageId;
        final S sto;
        final Object dto;
        final LinkedHashMap<String, byte[]> incomingBinaries;
        final LinkedHashMap<String, String> incomingStrings;
        final long nanosTaken_DeconstructMessage;
        final DeserializedMatsTrace<Z> deserializedMatsTrace;
        final long nanosTaken_incomingMessageAndStateDeserializationNanos;

        public PreprocessAndDeserializeData(MatsTrace<Z> matsTrace, String jmsMessageId, S sto, Object dto,
                LinkedHashMap<String, byte[]> incomingBinaries,
                LinkedHashMap<String, String> incomingStrings, long nanosTaken_DeconstructMessage,
                DeserializedMatsTrace<Z> deserializedMatsTrace,
                long nanosTaken_incomingMessageAndStateDeserializationNanos) {
            this.matsTrace = matsTrace;
            this.jmsMessageId = jmsMessageId;
            this.sto = sto;
            this.dto = dto;
            this.incomingBinaries = incomingBinaries;
            this.incomingStrings = incomingStrings;
            this.nanosTaken_DeconstructMessage = nanosTaken_DeconstructMessage;
            this.deserializedMatsTrace = deserializedMatsTrace;
            this.nanosTaken_incomingMessageAndStateDeserializationNanos = nanosTaken_incomingMessageAndStateDeserializationNanos;
        }
    }

    /**
     * Special case of JmsMatsJmsException indicating that reading from JMS Message failed, i.e. caused a JMSException.
     * This shall "crash" the JmsSessionHolder
     *
     * NOTE: This means we will NOT run the "completed" Interceptors.
     */
    private static class JmsMessageReadFailure_JmsMatsJmsException extends JmsMatsJmsException {
        JmsMessageReadFailure_JmsMatsJmsException(String msg, JMSException cause) {
            super(msg, cause);
        }
    }

    /**
     * Special case of MatsRefuseMessageException indicating that there are issues with the contents of the JMS message
     * (not MapMessage, missing data, wrong stage etc): Handled by tx mgr, attempted insta-rollback (refuse). Note:
     * There was not a JMS problem here, but the contents of the message was wrong/unexpected.
     *
     * NOTE: This means we will NOT run the "completed" Interceptors.
     */
    private static class JmsMessageProblem_RefuseMessageException extends MatsRefuseMessageException {
        JmsMessageProblem_RefuseMessageException(String message) {
            super(message);
        }

        JmsMessageProblem_RefuseMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static <R, S, Z> PreprocessAndDeserializeData<S, Z> preprocessAndDeserializeData(
            JmsMatsFactory<Z> matsFactory, JmsMatsStage<R, S, ?, Z> jmsMatsStage,
            long startedNanos, Instant startedInstant, Message[] messageA)
            throws JmsMessageProblem_RefuseMessageException, JmsMessageReadFailure_JmsMatsJmsException {
        long nanosAtStart_DeconstructMessage = System.nanoTime();

        String jmsMessageId;
        try {
            // Setting this in the JMS Mats implementation instead of MatsMetricsLoggingInterceptor,
            // so that if things fail before getting to the actual Mats part, we'll have it in
            // the log lines.
            jmsMessageId = messageA[0].getJMSMessageID();
            MDC.put(MDC_MATS_IN_MESSAGE_SYSTEM_ID, jmsMessageId);
            // Fetching the TraceId early from the JMS Message for MDC, so that can follow in logs.
            String jmsTraceId = messageA[0].getStringProperty(JMS_MSG_PROP_TRACE_ID);
            MDC.put(MDC_TRACE_ID, jmsTraceId);
        }
        catch (JMSException e) {
            String reason = "Got JMSException when doing msg.getJMSMessageID() or msg.getStringProperty('traceId').";
            JmsMessageReadFailure_JmsMatsJmsException up = new JmsMessageReadFailure_JmsMatsJmsException(reason, e);
            invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                    startedInstant, StagePreprocessAndDeserializeError.DECONSTRUCT_ERROR, up, reason, "[unknown]");
            throw up;
        }

        // Assert that this is indeed a JMS MapMessage.
        if (!(messageA[0] instanceof MapMessage)) {
            String reason = "Got some JMS Message that is not instanceof JMS MapMessage"
                    + " - cannot be a Mats message! Refusing this message!";
            JmsMessageProblem_RefuseMessageException up = new JmsMessageProblem_RefuseMessageException(reason);
            invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                    startedInstant, StagePreprocessAndDeserializeError.WRONG_MESSAGE_TYPE, up, reason, jmsMessageId);
            throw up;
        }

        // ----- This is a MapMessage
        // Using array so that we can null it out after fetching data from it, hoping to help GC.
        MapMessage[] mapMessageA = { (MapMessage) messageA[0] };

        // :: Fetch Mats-specific message data from the JMS Message.
        // ==========================================================

        String matsTraceKey = matsFactory.getFactoryConfig().getMatsTraceKey();
        String matsTraceMetaKey = matsTraceKey + MatsSerializer.META_KEY_POSTFIX;

        byte[] matsTraceBytes;
        String matsTraceMeta;
        try {
            matsTraceBytes = mapMessageA[0].getBytes(matsTraceKey);
            matsTraceMeta = mapMessageA[0].getString(matsTraceMetaKey);

            // :: Assert that we got some values
            if ((matsTraceBytes == null) || (matsTraceMeta == null)) {
                String reason = "Got some JMS Message that is missing MatsTrace byte array"
                        + " or MatsTraceMeta String on JMS MapMessage key '" + matsTraceKey +
                        "' - cannot be a MATS message! Refusing this message!";
                JmsMessageProblem_RefuseMessageException up = new JmsMessageProblem_RefuseMessageException(reason);
                invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                        startedInstant, StagePreprocessAndDeserializeError.MISSING_CONTENTS, up, reason, jmsMessageId);
                throw up;
            }
        }
        catch (JMSException e) {
            String reason = "Got JMSException when getting the MatsTrace from the JMS MapMessage by using"
                    + " mapMessage.get[Bytes|String](..). Pretty crazy.";
            JmsMessageReadFailure_JmsMatsJmsException up = new JmsMessageReadFailure_JmsMatsJmsException(reason, e);
            invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                    startedInstant, StagePreprocessAndDeserializeError.DECONSTRUCT_ERROR, up, reason, jmsMessageId);
            throw up;
        }

        // :: Getting the 'sideloads'; Byte-arrays and Strings from the MapMessage.
        LinkedHashMap<String, byte[]> incomingBinaries = new LinkedHashMap<>();
        LinkedHashMap<String, String> incomingStrings = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Enumeration<String> mapNames = (Enumeration<String>) mapMessageA[0].getMapNames();
            while (mapNames.hasMoreElements()) {
                String name = mapNames.nextElement();
                // ?: Is this the MatsTrace itself?
                if (matsTraceKey.equals(name) || matsTraceMetaKey.equals(name)) {
                    // Yes, this is the MatsTrace: Do not expose this as binary or string sideload.
                    continue;
                }
                Object object = mapMessageA[0].getObject(name);
                if (object instanceof byte[]) {
                    incomingBinaries.put(name, (byte[]) object);
                }
                else if (object instanceof String) {
                    incomingStrings.put(name, (String) object);
                }
                else {
                    log.warn("Got some object in the MapMessage to ["
                            + jmsMatsStage.getStageId()
                            + "] which is neither byte[] nor String - which should not"
                            + " happen - Ignoring.");
                }
            }
        }
        catch (JMSException e) {
            String reason = "Got JMSException when getting 'sideloads' from the JMS MapMessage by using"
                    + " mapMessage.get[Bytes|String](..). Pretty crazy.";
            JmsMessageReadFailure_JmsMatsJmsException up = new JmsMessageReadFailure_JmsMatsJmsException(reason, e);
            invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                    startedInstant, StagePreprocessAndDeserializeError.DECONSTRUCT_ERROR, up, reason, jmsMessageId);
            throw up;
        }

        // ----- We don't need the JMS Message anymore, we've gotten all required info from it.
        // Null it out before starting deserialization, hopefully helping GC
        messageA[0] = null;
        mapMessageA[0] = null;

        long nanosTaken_DeconstructMessage = System.nanoTime() - nanosAtStart_DeconstructMessage;

        // :: Deserialize the MatsTrace and DTO/STO from the message data.
        // ================================================================

        MatsSerializer<Z> matsSerializer = matsFactory.getMatsSerializer();
        DeserializedMatsTrace<Z> matsTraceDeserialized;
        try {
            matsTraceDeserialized = matsSerializer.deserializeMatsTrace(matsTraceBytes, matsTraceMeta);
        }
        catch (Throwable t) {
            String reason = "Could not deserialize the MatsTrace from the JMS Message.";
            JmsMessageProblem_RefuseMessageException up = new JmsMessageProblem_RefuseMessageException(reason, t);
            invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                    startedInstant, StagePreprocessAndDeserializeError.DECONSTRUCT_ERROR, up, reason, jmsMessageId);
            throw up;
        }
        MatsTrace<Z> matsTrace = matsTraceDeserialized.getMatsTrace();

        // :: Overwriting the TraceId MDC, now from MatsTrace (in case missing from JMS Message)
        MDC.put(MDC_TRACE_ID, matsTrace.getTraceId());
        MDC.put(MDC_MATS_CALL_NUMBER, Integer.toString(matsTrace.getCallNumber()));

        // :: Current Call
        Call<Z> currentCall = matsTrace.getCurrentCall();
        // Assert that this is indeed a JMS Message meant for this Stage
        if (!jmsMatsStage.getStageId().equals(currentCall.getTo().getId())) {
            String reason = "The incoming MATS message is not to this Stage! this:["
                    + jmsMatsStage.getStageId() + "], msg:[" + currentCall.getTo() + "]. Refusing this message!";
            JmsMessageProblem_RefuseMessageException up = new JmsMessageProblem_RefuseMessageException(reason);
            invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                    startedInstant, StagePreprocessAndDeserializeError.WRONG_STAGE, up, reason, jmsMessageId);
            throw up;
        }

        long nanosAtStart_incomingMessageAndStateDeserializationNanos = System.nanoTime();

        // :: Current State: If null, make an empty object instead, unless Void -> null.
        S currentSto;
        try {
            currentSto = JmsMatsStatics.handleIncomingState(matsSerializer, jmsMatsStage.getStateClass(),
                    matsTrace.getCurrentState().orElse(null));
        }
        catch (Throwable t) {
            String reason = "Could not deserialize the current state (STO) from the MatsTrace from the JMS Message.";
            JmsMessageProblem_RefuseMessageException up = new JmsMessageProblem_RefuseMessageException(reason, t);
            invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                    startedInstant, StagePreprocessAndDeserializeError.DECONSTRUCT_ERROR, up, reason, jmsMessageId);
            throw up;
        }

        // :: Incoming Message DTO
        Object incomingDto;
        try {
            incomingDto = JmsMatsStatics.handleIncomingMessageMatsObject(matsSerializer,
                    jmsMatsStage.getMessageClass(), currentCall.getData());
        }
        catch (Throwable t) {
            String reason = "Could not deserialize the current state (STO) from the MatsTrace from the JMS Message.";
            JmsMessageProblem_RefuseMessageException up = new JmsMessageProblem_RefuseMessageException(reason, t);
            invokeStagePreprocessAndDeserializationErrorInterceptors(matsFactory, jmsMatsStage, startedNanos,
                    startedInstant, StagePreprocessAndDeserializeError.DECONSTRUCT_ERROR, up, reason, jmsMessageId);
            throw up;
        }

        long nanosTaken_incomingMessageAndStateDeserializationNanos = System.nanoTime()
                - nanosAtStart_incomingMessageAndStateDeserializationNanos;

        return new PreprocessAndDeserializeData<>(matsTrace, jmsMessageId, currentSto, incomingDto, incomingBinaries,
                incomingStrings, nanosTaken_DeconstructMessage, matsTraceDeserialized,
                nanosTaken_incomingMessageAndStateDeserializationNanos);
    }

    private static <R, S, Z> void invokeStagePreprocessAndDeserializationErrorInterceptors(
            JmsMatsFactory<Z> matsFactory, JmsMatsStage<R, S, ?, Z> jmsMatsStage,
            long startedNanos, Instant startedInstant, StagePreprocessAndDeserializeError error,
            Throwable throwable, String reason, String jmsMessageId) {

        log.error(LOG_PREFIX + reason + " - JMSMessageId:" + jmsMessageId);

        StagePreprocessAndDeserializeErrorContextImpl context = new StagePreprocessAndDeserializeErrorContextImpl(
                jmsMatsStage, startedNanos, startedInstant, error, throwable);

        // Fetch relevant interceptors.
        List<MatsStageInterceptor> interceptorsForStage_local = matsFactory.getInterceptorsForStage(context);

        for (MatsStageInterceptor matsStageInterceptor : interceptorsForStage_local) {
            try {
                matsStageInterceptor.stagePreprocessAndDeserializeError(context);
            }
            catch (Throwable t) {
                log.error(LOG_PREFIX + "StageInterceptor raised exception on"
                        + " 'stagePreprocessAndDeserializeError(..)', ignored.", t);
            }
        }
    }

    private static void invokeStageReceivedInterceptors(List<MatsStageInterceptor> interceptorsForStage,
            StageCommonContext stageCommonContext) {
        StageReceivedContextImpl initiateStartedContext = new StageReceivedContextImpl(stageCommonContext);

        for (MatsStageInterceptor matsStageInterceptor : interceptorsForStage) {
            try {
                matsStageInterceptor.stageReceived(initiateStartedContext);
            }
            catch (Throwable t) {
                log.error(LOG_PREFIX + "StageInterceptor raised exception on"
                        + " 'started(..)', ignored.", t);
            }
        }
    }

    private static <R, S, Z> ProcessLambda<Object, Object, Object> invokeWrapUserLambdaInterceptors(
            JmsMatsStage<R, S, ?, Z> currentStage,
            List<MatsStageInterceptor> interceptorsForStage,
            StageCommonContextImpl<Z> stageCommonContext) {
        // :: Create a "lambda stack" of the interceptors
        // This is the resulting lambda we will actually invoke
        // .. if there are no interceptors, it will directly be the user lambda
        @SuppressWarnings("unchecked")
        ProcessLambda<Object, Object, Object> currentLambda = (ProcessLambda<Object, Object, Object>) currentStage
                .getProcessLambda();
        /*
         * Create the lambda stack by moving "backwards" through the registered interceptors, as when we'll actually
         * invoke the resulting lambda stack, the last stacked (at top), which is the first registered (due to iterating
         * backwards), will be the first code to run.
         */
        // Create the InitiateInterceptContext instance (one for all interceptors)
        StageInterceptUserLambdaContextImpl stageInterceptUserLambdaContext = null;
        for (int i = interceptorsForStage.size() - 1; i >= 0; i--) {
            MatsStageInterceptor interceptor = interceptorsForStage.get(i);
            if (!(interceptor instanceof MatsStageInterceptUserLambda)) {
                continue;
            }
            // Lazy init of StageInterceptUserLambdaContext (thus only if needed)
            if (stageInterceptUserLambdaContext == null) {
                stageInterceptUserLambdaContext = new StageInterceptUserLambdaContextImpl(
                        stageCommonContext);
            }
            final StageInterceptUserLambdaContext stageInterceptUserLambdaContextFinal = stageInterceptUserLambdaContext;
            final MatsStageInterceptUserLambda userLambdaInterceptor = (MatsStageInterceptUserLambda) interceptor;
            // The currentLambda is the one that the interceptor should invoke
            final ProcessLambda<Object, Object, Object> previousCurrentLambda = currentLambda;
            // .. and, wrap the current lambda with the interceptor.
            // It may, or may not, wrap the provided init with its own implementation
            currentLambda = (pc, state, incoming) -> userLambdaInterceptor.stageInterceptUserLambda(
                    stageInterceptUserLambdaContextFinal, previousCurrentLambda, pc,
                    state, incoming);
        }
        return currentLambda;
    }

    private static <Z> void invokeStageMessageInterceptors(List<MatsStageInterceptor> interceptorsForStage,
            StageCommonContextImpl<Z> stageCommonContext, List<JmsMatsMessage<Z>> messagesToSend) {
        // :: Find the Message interceptors. Goddamn why is there no stream.filter[InstanceOf](Clazz.class)?
        List<MatsStageInterceptOutgoingMessages> messageInterceptors = interceptorsForStage.stream()
                .filter(MatsStageInterceptOutgoingMessages.class::isInstance)
                .map(MatsStageInterceptOutgoingMessages.class::cast)
                .collect(Collectors.toList());

        if (!messageInterceptors.isEmpty()) {
            // Make a cancel message-lambda, which removes the message from the current set of messagesToSend.
            Consumer<String> cancelOutgoingMessage = matsMsgId -> messagesToSend
                    .removeIf(next -> next.getMatsMessageId().equals(matsMsgId));

            // Making a copy for the 'messagesToSend', as it can be modified (add/remove) by the interceptor.
            // (This is repeatedly filled with the current set of messagesToSend)
            ArrayList<JmsMatsMessage<Z>> copiedMessages = new ArrayList<>();

            // Wrap the copy in an unmodifiableList, so that the intercept cannot directly affect it
            // (must instead use the cancel message-lambda)
            List<MatsEditableOutgoingMessage> unmodifiableMessages = Collections.unmodifiableList(copiedMessages);

            // Make the context, giving both the current
            StageInterceptOutgoingMessageContextImpl context = new StageInterceptOutgoingMessageContextImpl(
                    stageCommonContext, unmodifiableMessages, cancelOutgoingMessage);

            // Iterate through the interceptors, "showing" the matsMessages.
            for (MatsStageInterceptOutgoingMessages messageInterceptor : messageInterceptors) {
                // Filling with the /current/ set of messagesToSend.
                copiedMessages.clear();
                copiedMessages.addAll(messagesToSend);
                // :: Invoke the interceptor
                // NOTICE: If the interceptor cancels a message, or initiates a new matsMessage, this WILL show up for
                // the next invoked interceptor.
                messageInterceptor.stageInterceptOutgoingMessages(context);
            }
        }
    }

    private void invokeFinalStageCompletedInterceptors(JmsMatsInternalExecutionContext internalExecutionContext,
            List<JmsMatsMessage<Z>> stageMessagesProduced, StageCommonContextImpl<Z> stageCommonContext,
            List<MatsStageInterceptor> interceptorsForStage, long nanosTaken_UserLambda,
            long nanosTaken_totalEnvelopeSerAndComp, long nanosTaken_totalMsgSysProdAndSend,
            Throwable throwableResult, StageProcessResult throwableStageProcessResult,
            JmsMatsProcessContext<R, S, Z> processContext, long nanosTaken_TotalStartReceiveToFinished) {
        // ?: Assert that we've come to the actual place in the code where we're running the user
        // lambda
        if ((stageCommonContext == null)
                || (processContext == null)
                || (interceptorsForStage == null)) {
            // -> No, we have evidently not all expected info in place
            // This is unexpected: Log this so that we can find it and fix it!
            log.error(LOG_PREFIX + "Unexpected situation: Missing StageCommonContext,"
                    + " ProcessContext or Interceptors for Stage - won't be able to run"
                    + " \"finishing\" interceptors.");
            return;
        }
        // -> We have expected pieces in place

        // ::: "Calculate" the ProcessResult based on current throwable/message situation

        // :: Find any "result" message (REPLY, NEXT, GOTO)
        // NOTE! This cannot be NEXT_DIRECT, as that would already have been handled in the processing code above.
        MatsSentOutgoingMessage resultMessage = stageMessagesProduced.stream()
                .filter(m -> (m.getMessageType() == MessageType.REPLY)
                        || (m.getMessageType() == MessageType.NEXT)
                        || (m.getMessageType() == MessageType.GOTO))
                .findFirst().orElse(null);
        // :: Find any Flow Request messages (note that Requests can be produced both by stage and init, and we only
        // want the actual stage Requests (i.e. Flow) - not STAGE_INIT)
        List<MatsSentOutgoingMessage> flowRequests = stageMessagesProduced.stream()
                .filter(m -> (m.getMessageType() == MessageType.REQUEST)
                        && (m.getDispatchType() == DispatchType.STAGE))
                .collect(Collectors.toList());
        // :: Find any initiations performed within the stage (ProcessType.STAGE_INIT - these can be REQUEST, SEND and
        // PUBLISH)
        List<MatsSentOutgoingMessage> initiations = stageMessagesProduced.stream()
                .filter(m -> m.getDispatchType() == DispatchType.STAGE_INIT)
                .collect(Collectors.toList());

        StageProcessResult stageProcessResult;
        // Throwable "overrides" any messages (they haven't been sent)
        // ?: Did we have a throwableProcessingResult?
        if (throwableStageProcessResult != null) {
            // -> Yes, and then this is it.
            stageProcessResult = throwableStageProcessResult;
        }
        // ?: No throwable, did we get a "result message"?
        else if (resultMessage != null) {
            // -> Yes, result message
            // ?: Which type is it?
            switch (resultMessage.getMessageType()) {
                case REPLY:
                    stageProcessResult = StageProcessResult.REPLY;
                    break;
                case NEXT:
                    stageProcessResult = StageProcessResult.NEXT;
                    break;
                case GOTO:
                    stageProcessResult = StageProcessResult.GOTO;
                    break;
                default:
                    // NOTE: NEXT_DIRECT is handled directly in the processing code, read comment at start!
                    // This shalln't happen, see code above where we only pick out those three.
                    throw new AssertionError("Unknown result message type [" + resultMessage
                            .getMessageType() + "].");
            }
        }
        // ?: No "result message", did we get any requests?
        else if (!flowRequests.isEmpty()) {
            // -> Yes, request(s)
            stageProcessResult = StageProcessResult.REQUEST;
        }
        else {
            // -> There was neither throwable, "result message", nor requests - thus there
            // was no process result. This is thus a Mats Flow stop.
            stageProcessResult = StageProcessResult.NONE;
        }

        invokeStageCompletedInterceptors(internalExecutionContext, stageCommonContext,
                interceptorsForStage, nanosTaken_UserLambda,
                nanosTaken_totalEnvelopeSerAndComp, nanosTaken_totalMsgSysProdAndSend,
                throwableResult, processContext, nanosTaken_TotalStartReceiveToFinished,
                stageMessagesProduced, resultMessage, flowRequests, initiations, stageProcessResult);
    }

    /**
     * Both for NEXT_DIRECT, and when exiting processor.
     */
    private static <R, S, Z> void invokeStageCompletedInterceptors(
            JmsMatsInternalExecutionContext internalExecutionContext,
            StageCommonContextImpl<Z> stageCommonContext, List<MatsStageInterceptor> interceptorsForStage,
            long nanosTaken_UserLambda, long nanosTaken_totalEnvelopeSerAndComp,
            long nanosTaken_totalMsgSysProdAndSend, Throwable throwableResult,
            JmsMatsProcessContext<R, S, Z> processContext, long nanosTaken_TotalStartReceiveToFinished,
            List<? extends MatsSentOutgoingMessage> messagesToSend, MatsSentOutgoingMessage resultMessage,
            List<? extends MatsSentOutgoingMessage> requests, List<? extends MatsSentOutgoingMessage> initiations,
            StageProcessResult stageProcessResult) {
        StageCompletedContextImpl stageCompletedContext = new StageCompletedContextImpl(
                stageCommonContext,
                stageProcessResult,
                nanosTaken_UserLambda,
                processContext.getMeasurements(),
                processContext.getTimingMeasurements(),
                nanosTaken_totalEnvelopeSerAndComp,
                internalExecutionContext.getDbCommitNanos(),
                nanosTaken_totalMsgSysProdAndSend,
                internalExecutionContext.getMessageSystemCommitNanos(),
                nanosTaken_TotalStartReceiveToFinished,
                throwableResult,
                Collections.unmodifiableList(messagesToSend),
                resultMessage,
                Collections.unmodifiableList(requests),
                Collections.unmodifiableList(initiations));

        // Go through interceptors backwards for this exit-style intercept stage
        for (int i = interceptorsForStage.size() - 1; i >= 0; i--) {
            try {
                // ?: Choose between NEXT_DIRECT stage completed, and ordinary (final) stage completed.
                if (stageProcessResult == StageProcessResult.NEXT_DIRECT) {
                    interceptorsForStage.get(i).stageCompletedNextDirect(stageCompletedContext);
                }
                else {
                    interceptorsForStage.get(i).stageCompleted(stageCompletedContext);
                }
            }
            catch (Throwable t) {
                log.error(LOG_PREFIX + "StageInterceptor [" + interceptorsForStage.get(i)
                        + "] raised a [" + t.getClass().getSimpleName() + "] when invoking"
                        + " stageCompleted(..) for stage [" + stageCompletedContext.getStage()
                        + "] - ignoring, but this is probably quite bad.", t);
            }
        }
    }

    private static <Z> void concatAllTraceIds(String incomingTraceId, List<JmsMatsMessage<Z>> messagesToSend) {
        // ?: Are there any outgoing messages? (There are none for e.g. Terminator)
        if (!messagesToSend.isEmpty()) {
            // -> Yes, there are outgoing messages.
            // Handle standard-case where there is only one outgoing (i.e. a Service which requested or replied)
            // ?: Fast-path for only one message
            if (messagesToSend.size() == 1) {
                // ?: Is the traceId different from the one we are processing?
                // (This can happen if it is a Terminator, but which send a new message)
                if (!messagesToSend.get(0).getMatsTrace().getTraceId().equals(incomingTraceId)) {
                    // -> Yes, different, so create a new MDC traceId value containing both.
                    String bothTraceIds = incomingTraceId
                            + ';' + messagesToSend.get(0).getMatsTrace().getTraceId();
                    MDC.put(MDC_TRACE_ID, bothTraceIds);
                }
                else {
                    // -> They are the same. Should already be correct on MDC, but set anyway for good measure.
                    // (an interceptor could potentially have overwritten it)
                    MDC.put(MDC_TRACE_ID, incomingTraceId);
                }
            }
            else {
                // -> There are more than 1 outgoing message. Collect and concat.
                // Using TreeSet to both: 1) de-duplicate, 2) get sort.
                Set<String> allTraceIds = new TreeSet<>();
                // Add the TraceId for the message we are processing.
                allTraceIds.add(incomingTraceId);
                // :: Add TraceIds for all the outgoing messages
                for (JmsMatsMessage<?> msg : messagesToSend) {
                    allTraceIds.add(msg.getMatsTrace().getTraceId());
                }
                String collectedTraceIds;
                int tooMany = 15;
                // ?: Are there too many?
                if (allTraceIds.size() <= tooMany) {
                    // -> No, not too many
                    collectedTraceIds = String.join(";", allTraceIds);
                }
                else {
                    // -> Yes, too many - creating a "traceId" reflecting cropping.
                    collectedTraceIds = "<cropped,numTraceIds:" + allTraceIds.size() + ">;"
                            + allTraceIds.stream().limit(tooMany).collect(Collectors.joining(";"))
                            + ";...";
                }
                // Set new concat'ed traceId (will probably still just be one..!)
                MDC.put(MDC_TRACE_ID, collectedTraceIds);
            }
        }
    }

    private void clearAndSetStaticMdcValues() {
        // NOTE: The StageProcessor /owns/ this thread, so we do not need to bother about cleanliness of MDC handling.
        // Just clear the MDC.
        MDC.clear();

        // This is Stage
        MDC.put(MDC_MATS_STAGE, "true");
        // Set the "static" values again
        MDC.put(MDC_MATS_STAGE_ID, _jmsMatsStage.getStageId());
        MDC.put(MDC_MATS_STAGE_INDEX, Integer.toString(_jmsMatsStage.getStageConfig().getStageIndex()));
        MDC.put(MDC_MATS_APP_NAME, getFactory().getFactoryConfig().getAppName());
        MDC.put(MDC_MATS_APP_VERSION, getFactory().getFactoryConfig().getAppVersion());
    }

    private static void chillWait(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            log.info(LOG_PREFIX + "Got InterruptedException when chill-waiting.");
        }
    }

    private static void chillWait() {
        // About 30 seconds..
        // TODO: Make exponential backoff.
        // (This will kick inn if you /do not/ use a "failover:" protocol, and the broker goes down.)
        chillWait(29_500 + Math.round(Math.random() * 1000));
    }

    private Destination createJmsDestination(Session jmsSession, FactoryConfig factoryConfig) throws JMSException {
        Destination destination;
        String destinationName = factoryConfig.getMatsDestinationPrefix() + _jmsMatsStage.getStageId();
        if (_jmsMatsStage.isQueue()) {
            destination = jmsSession.createQueue(destinationName);
        }
        else {
            destination = jmsSession.createTopic(destinationName);
        }
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Created JMS " + (_jmsMatsStage.isQueue() ? "Queue" : "Topic")
                + " to receive from: [" + destination + "].");
        return destination;
    }

    /**
     * Implementation of {@link StageCommonContext}, used as a container for the common fields for the subsequent
     * interface implementations.
     */
    private static class StageCommonContextImpl<Z> implements StageCommonContext {
        private final ProcessContext<Object> _processContext;

        private final JmsMatsStage<?, ?, ?, Z> _stage;
        private final long _startedNanos;
        private final Instant _startedInstant;
        private final long _endpointEnteredTimestampMillis;
        private final long _precedingSameStackHeightOutgoingTimestamp;

        private final MatsTrace<Z> _matsTrace;
        private final Object _incomingMessage;
        private final Object _incomingState;

        private final long _incomingEnvelopeDeconstructNanos;
        private final int _incomingEnvelopeRawSize;
        private final long _incomingEnvelopeDecompressionNanos;
        private final int _incomingEnvelopeUncompressedSize;
        private final long _incomingEnvelopeDeserializationNanos;
        private final long _incomingMessageAndStateDeserializationNanos;
        private final long _preUserLambdaNanos;

        private final boolean _isNextDirect;

        private Map<String, Object> _utilityMap;

        private StageCommonContextImpl(ProcessContext<Object> processContext,
                JmsMatsStage<?, ?, ?, Z> stage,
                long startedNanos, Instant startedInstant, long endpointEnteredTimestampMillis,
                long precedingSameStackHeightOutgoingTimestamp, MatsTrace<Z> matsTrace, Object incomingMessage,
                Object incomingState,
                long incomingEnvelopeDeconstructNanos,
                int incomingEnvelopeRawSize,
                long incomingEnvelopeDecompressionNanos,
                int incomingEnvelopeUncompressedSize,
                long incomingEnvelopeDeserializationNanos,
                long incomingMessageAndStateDeserializationNanos,
                long preUserLambdaNanos,
                boolean isNextDirect) {
            _processContext = processContext;

            _stage = stage;
            _startedNanos = startedNanos;
            _startedInstant = startedInstant;
            _endpointEnteredTimestampMillis = endpointEnteredTimestampMillis;
            _precedingSameStackHeightOutgoingTimestamp = precedingSameStackHeightOutgoingTimestamp;

            _matsTrace = matsTrace;
            _incomingMessage = incomingMessage;
            _incomingState = incomingState;

            _incomingEnvelopeDeconstructNanos = incomingEnvelopeDeconstructNanos;
            _incomingEnvelopeRawSize = incomingEnvelopeRawSize;
            _incomingEnvelopeDecompressionNanos = incomingEnvelopeDecompressionNanos;
            _incomingEnvelopeUncompressedSize = incomingEnvelopeUncompressedSize;
            _incomingEnvelopeDeserializationNanos = incomingEnvelopeDeserializationNanos;
            _incomingMessageAndStateDeserializationNanos = incomingMessageAndStateDeserializationNanos;
            _preUserLambdaNanos = preUserLambdaNanos;

            _isNextDirect = isNextDirect;
        }

        static <Z> StageCommonContextImpl<Z> ordinary(ProcessContext<Object> processContext,
                JmsMatsStage<?, ?, ?, Z> stage,
                long startedNanos, Instant startedInstant, long endpointEnteredTimestampMillis,
                long precedingSameStackHeightOutgoingTimestamp, MatsTrace<Z> matsTrace, Object incomingMessage,
                Object incomingState,
                long incomingEnvelopeDeconstructNanos,
                int incomingEnvelopeRawSize,
                long incomingEnvelopeDecompressionNanos,
                int incomingEnvelopeUncompressedSize,
                long incomingEnvelopeDeserializationNanos,
                long incomingMessageAndStateDeserializationNanos,
                long preUserLambdaNanos) {
            return new StageCommonContextImpl<>(processContext, stage,
                    startedNanos, startedInstant, endpointEnteredTimestampMillis,
                    precedingSameStackHeightOutgoingTimestamp, matsTrace, incomingMessage,
                    incomingState, incomingEnvelopeDeconstructNanos, incomingEnvelopeRawSize,
                    incomingEnvelopeDecompressionNanos, incomingEnvelopeUncompressedSize,
                    incomingEnvelopeDeserializationNanos, incomingMessageAndStateDeserializationNanos,
                    preUserLambdaNanos, false);
        }

        static <Z> StageCommonContextImpl<Z> forNextDirect(ProcessContext<Object> processContext,
                JmsMatsStage<?, ?, ?, Z> stage,
                long startedNanos, Instant startedInstant, long endpointEnteredTimestampMillis,
                long precedingSameStackHeightOutgoingTimestamp, MatsTrace<Z> matsTrace, Object incomingMessage,
                Object incomingState) {
            return new StageCommonContextImpl<>(processContext, stage,
                    startedNanos, startedInstant, endpointEnteredTimestampMillis,
                    precedingSameStackHeightOutgoingTimestamp, matsTrace, incomingMessage,
                    incomingState, 0, 0,
                    0, 0,
                    0, 0,
                    0, true);
        }

        @Override
        public MatsStage<?, ?, ?> getStage() {
            return _stage;
        }

        @Override
        public Instant getStartedInstant() {
            return _startedInstant;
        }

        @Override
        public long getStartedNanoTime() {
            return _startedNanos;
        }

        @Override
        public Instant getEndpointEnteredTimestamp() {
            return Instant.ofEpochMilli(_endpointEnteredTimestampMillis);
        }

        @Override
        public Instant getPrecedingSameStackHeightOutgoingTimestamp() {
            return Instant.ofEpochMilli(_precedingSameStackHeightOutgoingTimestamp);
        }

        @Override
        public Object getIncomingData() {
            return _incomingMessage;
        }

        @Override
        public int getDataSerializedSize() {
            Z currentData = _matsTrace.getCurrentCall().getData();
            return _stage.getParentFactory().getMatsSerializer().sizeOfSerialized(currentData);
        }

        @Override
        public MessageType getIncomingMessageType() {
            // ?: Is this a NEXT_DIRECT?
            if (_isNextDirect) {
                return MessageType.NEXT_DIRECT;
            }
            // E-> Not NEXT_DIRECT

            CallType callType = _matsTrace.getCurrentCall().getCallType();
            if (callType == CallType.REQUEST) {
                return MessageType.REQUEST;
            }
            else if (callType == CallType.REPLY) {
                return MessageType.REPLY;
            }
            else if (callType == CallType.NEXT) {
                return MessageType.NEXT;
            }
            else if (callType == CallType.GOTO) {
                return MessageType.GOTO;
            }
            else if (callType == CallType.SEND) {
                // -> SEND, so must evaluate SEND or PUBLISH
                if (_matsTrace.getCurrentCall().getTo().getMessagingModel() == MessagingModel.QUEUE) {
                    return MessageType.SEND;
                }
                else {
                    return MessageType.PUBLISH;
                }
            }
            throw new AssertionError("Unknown CallType of matsTrace.currentCall: " + callType);
        }

        @Override
        public Optional<Object> getIncomingState() {
            return Optional.ofNullable(_incomingState);
        }

        @Override
        public int getStateSerializedSize() {
            Optional<StackState<Z>> currentStackStateO = _matsTrace.getCurrentState();
            if (!currentStackStateO.isPresent()) {
                return 0;
            }
            Z currentState = currentStackStateO.get().getState();
            return _stage.getParentFactory().getMatsSerializer().sizeOfSerialized(currentState);
        }

        @Override
        public <T> Optional<T> getIncomingSameStackHeightExtraState(String key, Class<T> type) {
            MatsSerializer<Z> matsSerializer = _stage.getParentFactory().getMatsSerializer();
            return _matsTrace.getCurrentState().map(s -> matsSerializer.deserializeObject(s.getExtraState(key), type));
        }

        @Override
        public long getMessageSystemDeconstructNanos() {
            return _incomingEnvelopeDeconstructNanos;
        }

        @Override
        public int getEnvelopeWireSize() {
            return _incomingEnvelopeRawSize;
        }

        @Override
        public long getEnvelopeDecompressionNanos() {
            return _incomingEnvelopeDecompressionNanos;
        }

        @Override
        public int getEnvelopeSerializedSize() {
            return _incomingEnvelopeUncompressedSize;
        }

        @Override
        public long getEnvelopeDeserializationNanos() {
            return _incomingEnvelopeDeserializationNanos;
        }

        @Override
        public long getDataAndStateDeserializationNanos() {
            return _incomingMessageAndStateDeserializationNanos;
        }

        @Override
        public void putInterceptContextAttribute(String key, Object value) {
            // No need to store nulls, due to getter semantics.
            if (value == null) {
                return;
            }
            // ?: Have we created the Map yet?
            if (_utilityMap == null) {
                // -> No, not created, so make it now.
                _utilityMap = new HashMap<>();
            }
            _utilityMap.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getInterceptContextAttribute(String key) {
            // ?: Have we created the map?
            if (_utilityMap == null) {
                // -> No, map not created, so obviously no value.
                return null;
            }
            return (T) _utilityMap.get(key);
        }

        @Override
        public DetachedProcessContext getProcessContext() {
            return _processContext;
        }

        @Override
        public long getTotalPreprocessAndDeserializeNanos() {
            return _preUserLambdaNanos;
        }

        @Override
        public int getMessageSystemTotalWireSize() {
            // ?: Is this NEXT_DIRECT?
            if (_isNextDirect) {
                // -> Yes, NEXT_DIRECT, so message system wire size is 0 (there was none!)
                return 0;
            }
            // E-> Not NEXT_DIRECT, so calculate
            DetachedProcessContext processContext = getProcessContext();

            // Calculate extra sizes we know as implementation
            // (Sizes of all the JMS properties we stick on the messages, and their values).
            int jmsImplSizes = TOTAL_JMS_MSG_PROPS_SIZE
                    + processContext.getTraceId().length()
                    + processContext.getMatsMessageId().length()
                    // missing DISPATCH_TYPE
                    + getIncomingMessageType().toString().length()
                    + processContext.getFromStageId().length()
                    + processContext.getInitiatingAppName().length()
                    + processContext.getInitiatorId().length()
                    + processContext.getStageId().length() // To
                    + Boolean.valueOf(!processContext.isNoAudit()).toString().length();

            return StageCommonContext.super.getMessageSystemTotalWireSize() + jmsImplSizes;
        }
    }

    /**
     * Base implementation which all the other intercept context interfaces can inherit from, taking a
     * {@link StageCommonContext} to call through to.
     */
    private static class StageBaseContextImpl implements StageCommonContext {
        private StageCommonContext _stageCommonContext;

        protected StageBaseContextImpl(StageCommonContext stageCommonContext) {
            _stageCommonContext = stageCommonContext;
        }

        @Override
        public MatsStage<?, ?, ?> getStage() {
            return _stageCommonContext.getStage();
        }

        @Override
        public Instant getStartedInstant() {
            return _stageCommonContext.getStartedInstant();
        }

        @Override
        public long getStartedNanoTime() {
            return _stageCommonContext.getStartedNanoTime();
        }

        @Override
        public Instant getEndpointEnteredTimestamp() {
            return _stageCommonContext.getEndpointEnteredTimestamp();
        }

        @Override
        public Instant getPrecedingSameStackHeightOutgoingTimestamp() {
            return _stageCommonContext.getPrecedingSameStackHeightOutgoingTimestamp();
        }

        @Override
        public MessageType getIncomingMessageType() {
            return _stageCommonContext.getIncomingMessageType();
        }

        @Override
        public Object getIncomingData() {
            return _stageCommonContext.getIncomingData();
        }

        @Override
        public int getDataSerializedSize() {
            return _stageCommonContext.getDataSerializedSize();
        }

        @Override
        public Optional<Object> getIncomingState() {
            return _stageCommonContext.getIncomingState();
        }

        @Override
        public int getStateSerializedSize() {
            return _stageCommonContext.getStateSerializedSize();
        }

        @Override
        public <T> Optional<T> getIncomingSameStackHeightExtraState(String key, Class<T> type) {
            return _stageCommonContext.getIncomingSameStackHeightExtraState(key, type);
        }

        @Override
        public long getMessageSystemDeconstructNanos() {
            return _stageCommonContext.getMessageSystemDeconstructNanos();
        }

        @Override
        public int getEnvelopeWireSize() {
            return _stageCommonContext.getEnvelopeWireSize();
        }

        @Override
        public long getEnvelopeDecompressionNanos() {
            return _stageCommonContext.getEnvelopeDecompressionNanos();
        }

        @Override
        public int getEnvelopeSerializedSize() {
            return _stageCommonContext.getEnvelopeSerializedSize();
        }

        @Override
        public long getEnvelopeDeserializationNanos() {
            return _stageCommonContext.getEnvelopeDeserializationNanos();
        }

        @Override
        public long getDataAndStateDeserializationNanos() {
            return _stageCommonContext.getDataAndStateDeserializationNanos();
        }

        @Override
        public void putInterceptContextAttribute(String key, Object value) {
            _stageCommonContext.putInterceptContextAttribute(key, value);
        }

        @Override
        public <T> T getInterceptContextAttribute(String key) {
            return _stageCommonContext.getInterceptContextAttribute(key);
        }

        @Override
        public DetachedProcessContext getProcessContext() {
            return _stageCommonContext.getProcessContext();
        }

        @Override
        public long getTotalPreprocessAndDeserializeNanos() {
            return _stageCommonContext.getTotalPreprocessAndDeserializeNanos();
        }
    }

    /**
     * Implementation of {@link StageReceivedContext}.
     */
    private static class StageReceivedContextImpl extends StageBaseContextImpl implements StageReceivedContext {
        public StageReceivedContextImpl(StageCommonContext stageCommonContext) {
            super(stageCommonContext);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ProcessContext<Object> getProcessContext() {
            return (ProcessContext<Object>) super.getProcessContext();
        }
    }

    /**
     * Implementation of {@link StageReceivedContext}.
     */
    private static class StageInterceptUserLambdaContextImpl extends StageBaseContextImpl implements
            StageInterceptUserLambdaContext {
        public StageInterceptUserLambdaContextImpl(StageCommonContext stageCommonContext) {
            super(stageCommonContext);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ProcessContext<Object> getProcessContext() {
            return (ProcessContext<Object>) super.getProcessContext();
        }
    }

    /**
     * Implementation of {@link StageInterceptOutgoingMessageContext}.
     */
    private static class StageInterceptOutgoingMessageContextImpl extends StageBaseContextImpl implements
            StageInterceptOutgoingMessageContext {
        private final List<MatsEditableOutgoingMessage> _matsMessages;
        private final Consumer<String> _cancelOutgoingMessage;

        public StageInterceptOutgoingMessageContextImpl(
                StageCommonContext stageCommonContext,
                List<MatsEditableOutgoingMessage> matsMessages,
                Consumer<String> cancelOutgoingMessage) {
            super(stageCommonContext);
            _matsMessages = matsMessages;
            _cancelOutgoingMessage = cancelOutgoingMessage;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ProcessContext<Object> getProcessContext() {
            return (ProcessContext<Object>) super.getProcessContext();
        }

        @Override
        public List<MatsEditableOutgoingMessage> getOutgoingMessages() {
            return _matsMessages;
        }

        @Override
        public void initiate(InitiateLambda lambda) {
            getProcessContext().initiate(lambda);
        }

        @Override
        public void cancelOutgoingMessage(String matsMessageId) {
            _cancelOutgoingMessage.accept(matsMessageId);
        }
    }

    /**
     * Implementation of {@link StageCompletedContext}.
     */
    private static class StageCompletedContextImpl extends StageBaseContextImpl implements StageCompletedContext {
        private final StageProcessResult _stageProcessResult;

        private final long _userLambdaNanos;
        private final List<MatsMeasurement> _measurements;
        private final List<MatsTimingMeasurement> _timingMeasurements;
        private final long _envelopeSerializationNanos;
        private final long _dbCommitNanos;
        private final long _messageSystemMessageProductionAndSendNanos;
        private final long _messageSystemCommitNanos;
        private final long _totalProcessingNanos;

        private final Throwable _throwable;
        private final List<MatsSentOutgoingMessage> _messages;
        private final MatsSentOutgoingMessage _reply;
        private final List<MatsSentOutgoingMessage> _requests;
        private final List<MatsSentOutgoingMessage> _initiations;

        public StageCompletedContextImpl(
                StageCommonContext stageCommonContext,
                StageProcessResult stageProcessResult,

                long userLambdaNanos,
                List<MatsMeasurement> measurements,
                List<MatsTimingMeasurement> timingMeasurements,
                long envelopeSerializationNanos,
                long dbCommitNanos,
                long messageSystemMessageProductionAndSendNanos,
                long messageSystemCommitNanos,
                long totalProcessingNanos,

                Throwable throwable,
                List<MatsSentOutgoingMessage> messages,
                MatsSentOutgoingMessage reply,
                List<MatsSentOutgoingMessage> requests,
                List<MatsSentOutgoingMessage> initiations) {
            super(stageCommonContext);
            _stageProcessResult = stageProcessResult;

            _userLambdaNanos = userLambdaNanos;
            _measurements = measurements;
            _timingMeasurements = timingMeasurements;
            _envelopeSerializationNanos = envelopeSerializationNanos;
            _dbCommitNanos = dbCommitNanos;
            _messageSystemMessageProductionAndSendNanos = messageSystemMessageProductionAndSendNanos;
            _messageSystemCommitNanos = messageSystemCommitNanos;
            _totalProcessingNanos = totalProcessingNanos;

            _throwable = throwable;
            _messages = messages;
            _reply = reply;
            _requests = requests;
            _initiations = initiations;
        }

        @Override
        public StageProcessResult getStageProcessResult() {
            return _stageProcessResult;
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

        @Override
        public Optional<MatsSentOutgoingMessage> getStageResultMessage() {
            return Optional.ofNullable(_reply);
        }

        @Override
        public List<MatsSentOutgoingMessage> getStageRequestMessages() {
            return _requests;
        }

        @Override
        public List<MatsSentOutgoingMessage> getStageInitiatedMessages() {
            return _initiations;
        }
    }

    /**
     * Implementation of {@link StagePreprocessAndDeserializeErrorContext}
     */
    private static class StagePreprocessAndDeserializeErrorContextImpl
            implements StagePreprocessAndDeserializeErrorContext {
        private final MatsStage<?, ?, ?> _stage;
        private final long _startedNanos;
        private final Instant _startedInstant;
        private final StagePreprocessAndDeserializeError _stagePreprocessAndDeserializeError;
        private final Throwable _throwable;

        public StagePreprocessAndDeserializeErrorContextImpl(MatsStage<?, ?, ?> stage,
                long startedNanos, Instant startedInstant,
                StagePreprocessAndDeserializeError stagePreprocessAndDeserializeError, Throwable throwable) {
            _stage = stage;
            _startedNanos = startedNanos;
            _startedInstant = startedInstant;
            _stagePreprocessAndDeserializeError = stagePreprocessAndDeserializeError;
            _throwable = throwable;
        }

        @Override
        public MatsStage<?, ?, ?> getStage() {
            return _stage;
        }

        @Override
        public Instant getStartedInstant() {
            return _startedInstant;
        }

        @Override
        public long getStartedNanoTime() {
            return _startedNanos;
        }

        @Override
        public StagePreprocessAndDeserializeError getStagePreprocessAndDeserializeError() {
            return _stagePreprocessAndDeserializeError;
        }

        @Override
        public Optional<Throwable> getThrowable() {
            return Optional.ofNullable(_throwable);
        }
    }

    @Override
    public String toString() {
        return idThis();
    }
}
