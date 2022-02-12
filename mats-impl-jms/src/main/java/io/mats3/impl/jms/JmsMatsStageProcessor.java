package io.mats3.impl.jms;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
import io.mats3.MatsInitiator.MatsInitiate;
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
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext.ProcessResult;
import io.mats3.api.intercept.MatsStageInterceptor.StageInterceptContext;
import io.mats3.api.intercept.MatsStageInterceptor.StageInterceptOutgoingMessageContext;
import io.mats3.api.intercept.MatsStageInterceptor.StageInterceptUserLambdaContext;
import io.mats3.api.intercept.MatsStageInterceptor.StagePreprocessAndDeserializeErrorContext;
import io.mats3.api.intercept.MatsStageInterceptor.StagePreprocessAndDeserializeErrorContext.ReceiveDeconstructError;
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
    private final boolean _interactive;
    private final Thread _processorThread;
    private final TransactionContext _transactionContext;

    JmsMatsStageProcessor(JmsMatsStage<R, S, I, Z> jmsMatsStage, int processorNumber, boolean interactive) {
        _randomInstanceId = randomString(5) + "@" + jmsMatsStage.getParentFactory();
        _jmsMatsStage = jmsMatsStage;
        _processorNumber = processorNumber;
        _interactive = interactive;
        _transactionContext = jmsMatsStage.getParentFactory()
                .getJmsMatsTransactionManager().getTransactionContext(this);
        _processorThread = new Thread(this::runner, THREAD_PREFIX + ident());
    }

    private volatile boolean _runFlag = true; // Start off running.

    private volatile JmsSessionHolder _jmsSessionHolder;

    private String ident() {
        return _jmsMatsStage.getStageId() + '#'
                + _processorNumber
                + (_interactive ? "_pri" : "")
                + " {" + _randomInstanceId + '}';
    }

    @Override
    public JmsMatsStage<?, ?, ?, ?> getStage() {
        return _jmsMatsStage;
    }

    @Override
    public JmsMatsFactory<Z> getFactory() {
        return _jmsMatsStage.getParentEndpoint().getParentFactory();
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
            // :: Clean MDC and set the "static" MDC values, since we just MDC.clear()'ed
            clearAndSetStaticMdcValues();
            log.info(LOG_PREFIX + "Getting JMS Session, Destination and Consumer for stage ["
                    + _jmsMatsStage.getStageId() + "].");

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
                MessageConsumer jmsConsumer = _interactive
                        ? jmsSession.createConsumer(destination, "JMSPriority = 9")
                        : jmsSession.createConsumer(destination);

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
                    Message message;
                    try {
                        if (log.isDebugEnabled()) log.debug(LOG_PREFIX
                                + "Going into JMS consumer.receive() for [" + destination + "].");
                        _processorInReceive = true;
                        message = jmsConsumer.receive();
                    }
                    finally {
                        _processorInReceive = false;
                    }
                    long startedNanos = System.nanoTime();
                    Instant startedInstant = Instant.now();

                    // Need to check whether the JMS Message gotten is null, as that signals that the
                    // Consumer, Session or Connection was closed from another thread.
                    if (message == null) {
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

                    StageContextImpl stageContext = new StageContextImpl(_jmsMatsStage, startedNanos, startedInstant);

                    // Fetch relevant interceptors
                    List<MatsStageInterceptor> interceptorsForStage = _jmsMatsStage.getParentFactory()
                            .getInterceptorsForStage(stageContext);

                    List<JmsMatsMessage<Z>> messagesToSend = new ArrayList<>();

                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    StageCommonContextImpl<Z>[] stageCommonContext = new StageCommonContextImpl[1];

                    long millisAtStart_Received = System.currentTimeMillis();
                    long nanosAtStart_Received = System.nanoTime();
                    long[] nanosTaken_UserLambda = { 0L };
                    long[] nanosTaken_totalEnvelopeSerAndComp = { 0L };
                    long[] nanosTaken_totalMsgSysProdAndSend = { 0L };

                    Throwable throwableResult = null;
                    ProcessResult throwableProcessResult = null;

                    ReceiveDeconstructError[] receiveDeconstructError = new ReceiveDeconstructError[1];
                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    JmsMatsProcessContext<R, S, Z>[] processContext = new JmsMatsProcessContext[1];

                    try { // try-catch-finally: Catch processing Exceptions, handle cleanup in finally

                        // :: Going into Mats Transaction
                        _transactionContext.doTransaction(internalExecutionContext, () -> {
                            long nanosAtStart_DeconstructMessage = System.nanoTime();
                            // Assert that this is indeed a JMS MapMessage.
                            if (!(message instanceof MapMessage)) {
                                String msg = "Got some JMS Message that is not instanceof JMS MapMessage"
                                        + " - cannot be a MATS message! Refusing this message!";
                                log.error(LOG_PREFIX + msg + "\n" + message);
                                receiveDeconstructError[0] = ReceiveDeconstructError.WRONG_MESSAGE_TYPE;
                                throw new MatsRefuseMessageException(msg);
                            }

                            // ----- This is a MapMessage
                            MapMessage mapMessage = (MapMessage) message;

                            // :: Fetch Mats-specific message data from the JMS Message.
                            // ==========================================================

                            byte[] matsTraceBytes;
                            String matsTraceMeta;
                            String jmsMessageId;
                            try {
                                String matsTraceKey = getFactory().getFactoryConfig().getMatsTraceKey();
                                matsTraceBytes = mapMessage.getBytes(matsTraceKey);
                                matsTraceMeta = mapMessage.getString(matsTraceKey
                                        + MatsSerializer.META_KEY_POSTFIX);
                                jmsMessageId = mapMessage.getJMSMessageID();
                                // Setting this in the JMS Mats implementation instead of MatsMetricsLoggingInterceptor,
                                // so that if things fail before getting to the actual Mats part, we'll have it in
                                // the log lines.
                                MDC.put(MDC_MATS_IN_MESSAGE_SYSTEM_ID, jmsMessageId);

                                // Fetching the TraceId early from the JMS Message for MDC, so that can follow in logs.
                                String jmsTraceId = mapMessage.getStringProperty(JMS_MSG_PROP_TRACE_ID);
                                MDC.put(MDC_TRACE_ID, jmsTraceId);

                                // :: Assert that we got some values
                                if (matsTraceBytes == null) {
                                    String msg = "Got some JMS Message that is missing MatsTrace byte array on"
                                            + "JMS MapMessage key '" + matsTraceKey +
                                            "' - cannot be a MATS message! Refusing this message!";
                                    log.error(LOG_PREFIX + msg + "\n" + message);
                                    receiveDeconstructError[0] = ReceiveDeconstructError.MISSING_CONTENTS;
                                    throw new MatsRefuseMessageException(msg);
                                }

                                if (matsTraceMeta == null) {
                                    String msg = "Got some JMS Message that is missing MatsTraceMeta String on"
                                            + "JMS MapMessage key '" + MatsSerializer.META_KEY_POSTFIX
                                            + "' - cannot be a MATS message! Refusing this message!";
                                    log.error(LOG_PREFIX + msg + "\n" + message);
                                    receiveDeconstructError[0] = ReceiveDeconstructError.MISSING_CONTENTS;
                                    throw new MatsRefuseMessageException(msg);
                                }
                            }
                            catch (JMSException e) {
                                receiveDeconstructError[0] = ReceiveDeconstructError.DECONSTRUCT_ERROR;
                                throw new JmsMatsJmsException("Got JMSException when getting the MatsTrace"
                                        + " from the MapMessage by using mapMessage.get[Bytes|String](..)."
                                        + " Pretty crazy.", e);
                            }

                            // :: Getting the 'sideloads'; Byte-arrays and Strings from the MapMessage.
                            LinkedHashMap<String, byte[]> incomingBinaries = new LinkedHashMap<>();
                            LinkedHashMap<String, String> incomingStrings = new LinkedHashMap<>();
                            try {
                                @SuppressWarnings("unchecked")
                                Enumeration<String> mapNames = (Enumeration<String>) mapMessage.getMapNames();
                                while (mapNames.hasMoreElements()) {
                                    String name = mapNames.nextElement();
                                    Object object = mapMessage.getObject(name);
                                    if (object instanceof byte[]) {
                                        incomingBinaries.put(name, (byte[]) object);
                                    }
                                    else if (object instanceof String) {
                                        incomingStrings.put(name, (String) object);
                                    }
                                    else {
                                        log.warn("Got some object in the MapMessage to ["
                                                + _jmsMatsStage.getStageId()
                                                + "] which is neither byte[] nor String - which should not"
                                                + " happen - Ignoring.");
                                    }
                                }
                            }
                            catch (JMSException e) {
                                receiveDeconstructError[0] = ReceiveDeconstructError.DECONSTRUCT_ERROR;
                                throw new JmsMatsJmsException("Got JMSException when getting 'sideloads'"
                                        + " from the MapMessage by using mapMessage.get[Bytes|String](..)."
                                        + " Pretty crazy.", e);
                            }

                            long nanosTaken_DeconstructMessage = System.nanoTime() - nanosAtStart_DeconstructMessage;

                            // :: Deserialize the MatsTrace and DTO/STO from the message data.
                            // ================================================================

                            MatsSerializer<Z> matsSerializer = getFactory().getMatsSerializer();
                            DeserializedMatsTrace<Z> matsTraceDeserialized;
                            try {
                                matsTraceDeserialized = matsSerializer
                                        .deserializeMatsTrace(matsTraceBytes, matsTraceMeta);
                            }
                            catch (Exception e) {
                                String msg = "Got some JMS Message where we could not deserialize the MatsTrace.";
                                log.error(LOG_PREFIX + msg + "\n" + message);
                                receiveDeconstructError[0] = ReceiveDeconstructError.DECONSTRUCT_ERROR;
                                throw new MatsRefuseMessageException(msg, e);
                            }
                            MatsTrace<Z> matsTrace = matsTraceDeserialized.getMatsTrace();
                            // Update the MatsTrace with stage-incoming timestamp - handles the "endpoint entered" logic
                            matsTrace.setStageEnteredTimestamo(millisAtStart_Received);

                            // :: Overwriting the TraceId MDC, now from MatsTrace (in case missing from JMS Message)
                            MDC.put(MDC_TRACE_ID, matsTrace.getTraceId());

                            // :: Current Call
                            Call<Z> currentCall = matsTrace.getCurrentCall();
                            // Assert that this is indeed a JMS Message meant for this Stage
                            if (!_jmsMatsStage.getStageId().equals(currentCall.getTo().getId())) {
                                String msg = "The incoming MATS message is not to this Stage! this:["
                                        + _jmsMatsStage.getStageId() + "], msg:[" + currentCall.getTo()
                                        + "]. Refusing this message!";
                                log.error(LOG_PREFIX + msg + "\n" + mapMessage);
                                receiveDeconstructError[0] = ReceiveDeconstructError.WRONG_STAGE;
                                throw new MatsRefuseMessageException(msg);
                            }

                            long nanosAtStart_incomingMessageAndStateDeserializationNanos = System.nanoTime();

                            // :: Current State: If null, make an empty object instead, unless Void -> null.
                            S currentSto = handleIncomingState(matsSerializer, _jmsMatsStage.getStateClass(),
                                    matsTrace.getCurrentState().orElse(null));

                            // :: Incoming Message DTO
                            I incomingDto = handleIncomingMessageMatsObject(matsSerializer,
                                    _jmsMatsStage.getMessageClass(), currentCall.getData());

                            long nanosTaken_incomingMessageAndStateDeserializationNanos = System.nanoTime()
                                    - nanosAtStart_incomingMessageAndStateDeserializationNanos;

                            Supplier<MatsInitiate> initiateSupplier = () -> JmsMatsInitiate.createForChildFlow(
                                    getFactory(), messagesToSend, internalExecutionContext, doAfterCommitRunnableHolder,
                                    matsTrace);

                            // Set the nested initiation context supplier
                            _jmsMatsStage.getParentFactory().setCurrentMatsFactoryThreadLocal_MatsInitiate(
                                    initiateSupplier);
                            // Set the MatsTrace for nested initiations
                            _jmsMatsStage.getParentFactory().setCurrentMatsFactoryThreadLocal_WithinStageContext(
                                    matsTrace, jmsConsumer);

                            // :: Create contexts, invoke interceptors
                            // ==========================================================

                            // .. create the ProcessContext
                            processContext[0] = new JmsMatsProcessContext<>(
                                    getFactory(),
                                    _jmsMatsStage.getParentEndpoint().getEndpointId(),
                                    _jmsMatsStage.getStageId(),
                                    jmsMessageId,
                                    _jmsMatsStage.getNextStageId(),
                                    matsTraceBytes, 0, matsTraceBytes.length, matsTraceMeta,
                                    matsTrace,
                                    currentSto,
                                    initiateSupplier,
                                    incomingBinaries, incomingStrings,
                                    messagesToSend, internalExecutionContext,
                                    doAfterCommitRunnableHolder);

                            long nanosTaken_PreUserLambda = System.nanoTime() - nanosAtStart_Received;

                            // .. stick the ProcessContext into the ThreadLocal scope
                            JmsMatsContextLocalCallback.bindResource(ProcessContext.class, processContext[0]);

                            // Cast the ProcessContext unchecked, since we need it for the interceptors.
                            @SuppressWarnings("unchecked")
                            ProcessContext<Object> processContextCasted = (ProcessContext<Object>) processContext[0];

                            // Create the common part of the interceptor contexts
                            stageCommonContext[0] = new StageCommonContextImpl<>(
                                    _jmsMatsStage,
                                    startedNanos, startedInstant,
                                    matsTrace, incomingDto, currentSto,
                                    nanosTaken_DeconstructMessage,
                                    matsTraceBytes.length,
                                    matsTraceDeserialized.getNanosDecompression(),
                                    matsTraceDeserialized.getSizeDecompressed(),
                                    matsTraceDeserialized.getNanosDeserialization(),
                                    nanosTaken_incomingMessageAndStateDeserializationNanos,
                                    nanosTaken_PreUserLambda);

                            // === Invoke any interceptors, stage "Started"
                            StageReceivedContextImpl initiateStartedContext = new StageReceivedContextImpl(
                                    stageCommonContext[0], processContextCasted);

                            for (MatsStageInterceptor matsStageInterceptor : interceptorsForStage) {
                                try {
                                    matsStageInterceptor.stageReceived(initiateStartedContext);
                                }
                                catch (Throwable t) {
                                    log.error(LOG_PREFIX + "StageInterceptor raised exception on"
                                            + " 'started(..)', ignored.", t);
                                }
                            }

                            // === Invoke any interceptors, stage "Intercept"
                            // Create the InitiateInterceptContext instance (one for all interceptors)
                            StageInterceptUserLambdaContextImpl stageInterceptContext = new StageInterceptUserLambdaContextImpl(
                                    stageCommonContext[0], processContextCasted);
                            // :: Create a "lambda stack" of the interceptors
                            // This is the resulting lambda we will actually invoke
                            // .. if there are no interceptors, it will directly be the user lambda
                            @SuppressWarnings("unchecked")
                            ProcessLambda<Object, Object, Object> currentLambda = (ProcessLambda<Object, Object, Object>) _jmsMatsStage
                                    .getProcessLambda();
                            /*
                             * Create the lambda stack by moving "backwards" through the registered interceptors, as
                             * when we'll actually invoke the resulting lambda stack, the last stacked (at top), which
                             * is the first registered (due to iterating backwards), will be the first code to run.
                             */
                            for (int i = interceptorsForStage.size() - 1; i >= 0; i--) {
                                MatsStageInterceptor interceptor = interceptorsForStage.get(i);
                                if (!(interceptor instanceof MatsStageInterceptUserLambda)) {
                                    continue;
                                }
                                final MatsStageInterceptUserLambda interceptInterceptor = (MatsStageInterceptUserLambda) interceptor;
                                // The currentLambda is the one that the interceptor should invoke
                                final ProcessLambda<Object, Object, Object> lambdaThatInterceptorMustInvoke = currentLambda;
                                // .. and, wrap the current lambda with the interceptor.
                                // It may, or may not, wrap the provided init with its own implementation
                                currentLambda = (pc, state, incoming) -> interceptInterceptor.stageInterceptUserLambda(
                                        stageInterceptContext, lambdaThatInterceptorMustInvoke, pc, state, incoming);
                            }

                            // :: Invoke the process lambda (the actual user code), possibly wrapped by interceptors.
                            // =======================================================================================

                            // :: == ACTUALLY Invoke the lambda. The current lambda is the one we should invoke.
                            long nanosAtStart_UserLambda = System.nanoTime();
                            try {
                                currentLambda.process(processContextCasted, currentSto, incomingDto);
                            }
                            finally {
                                nanosTaken_UserLambda[0] = System.nanoTime() - nanosAtStart_UserLambda;
                            }

                            // === Invoke any interceptors, stage "Message"
                            invokeStageMessageInterceptors(interceptorsForStage, stageCommonContext[0],
                                    processContextCasted, messagesToSend);

                            // :: Send messages on target queues/topics
                            // =======================================================================================

                            /*
                             * Concatenate all TraceIds for the incoming messages and any initiations, to put on the
                             * MDC. - which is good for the logging, so that they are all present on the MDC for the
                             * send/commit log lines, and in particular if we get any Exceptions when committing. Notice
                             * that the typical situation is that there is just one traceId (incoming + request or
                             * reply, which have the same traceId), as stage-initiations are a more seldom situation.
                             */
                            concatAllTraceIds(matsTrace, messagesToSend);

                            // :: Send any outgoing Mats messages (replies, requests, new messages etc..)
                            // ?: Any messages produced?
                            if (!messagesToSend.isEmpty()) {
                                // -> Yes, there are messages, so send them.
                                // (commit is performed when it exits the transaction lambda)

                                long nanosAtStart_totalEnvelopeSerialization = System.nanoTime();
                                long nowMillis = System.currentTimeMillis();
                                for (JmsMatsMessage<Z> matsMessage : messagesToSend) {
                                    matsMessage.serializeAndCacheMatsTrace(nowMillis);
                                }
                                long nowNanos = System.nanoTime();
                                nanosTaken_totalEnvelopeSerAndComp[0] = nowNanos
                                        - nanosAtStart_totalEnvelopeSerialization;

                                long nanosAtStart_totalProduceAndSendMsgSysMessages = nowNanos;
                                produceAndSendMsgSysMessages(log, _jmsSessionHolder, getFactory(), messagesToSend);
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
                        throwableProcessResult = ProcessResult.SYSTEM_EXCEPTION;
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
                        throwableProcessResult = ProcessResult.SYSTEM_EXCEPTION;
                        // Throw on (the original exception) to crash the JMS Session
                        throw e;
                    }
                    catch (JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException e) {
                        // Just record this for interceptor - but take the cause, since that is the actual exception.
                        throwableResult = e.getCause();
                        throwableProcessResult = ProcessResult.USER_EXCEPTION;
                        // .. These are handled by transaction manager (rollback), so we should just continue.
                        continue;
                    }
                    // Handle most other Throwables that may come out, except Errors which are not AssertionError.
                    catch (MatsRefuseMessageException | RuntimeException | AssertionError e) {
                        // Just record this for interceptor..
                        throwableResult = e;
                        throwableProcessResult = ProcessResult.USER_EXCEPTION;
                        // .. These are handled by transaction manager (rollback), so we should just continue.
                        continue;
                    }

                    // ===== FINALLY: CLEAN UP, AND INVOKE INTERCEPTORS =====

                    finally {
                        JmsMatsContextLocalCallback.unbindResource(ProcessContext.class);
                        _jmsMatsStage.getParentFactory().clearCurrentMatsFactoryThreadLocal_MatsInitiate();
                        _jmsMatsStage.getParentFactory().clearCurrentMatsFactoryThreadLocal_WithinStageContext();

                        if (throwableProcessResult == ProcessResult.USER_EXCEPTION) {
                            log.info(LOG_PREFIX + "Got [" + throwableResult.getClass().getName()
                                    + "] inside transactional message processing, which most probably originated from"
                                    + " user code. The situation shall have been handled by the MATS TransactionManager"
                                    + " (rollback). Looping to fetch next message.");
                        }
                        else if (throwableProcessResult == ProcessResult.SYSTEM_EXCEPTION) {
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

                        long nanosTaken_TotalStartReceiveToFinished = System.nanoTime() - nanosAtStart_Received;

                        // === Invoke any interceptors, stage "ReceiveDeconstructError"

                        // ?: Was there a receive/deconstruct error?
                        if (receiveDeconstructError[0] != null) {
                            // -> Yes, so then the rest of the processing never happened.
                            StagePreprocessAndDeserializeErrorContextImpl context = new StagePreprocessAndDeserializeErrorContextImpl(
                                    _jmsMatsStage, startedNanos, startedInstant,
                                    receiveDeconstructError[0], throwableResult);
                            for (MatsStageInterceptor matsStageInterceptor : interceptorsForStage) {
                                try {
                                    matsStageInterceptor.stagePreprocessAndDeserializeError(context);
                                }
                                catch (Throwable t) {
                                    log.error(LOG_PREFIX + "StageInterceptor raised exception on"
                                            + " 'receiveDeconstructError(..)', ignored.", t);
                                }
                            }
                        }
                        else {
                            // -> No, no receiveDeconstructError. Normal processing.

                            // === Invoke any interceptors, stage "Completed"

                            // ?: Have we come to the actual place in the code where we're running the user lambda?
                            if ((stageCommonContext[0] != null) && (processContext[0] != null)) {

                                // :: Find any "result" message (REPLY, NEXT, GOTO)
                                MatsSentOutgoingMessage result = messagesToSend.stream()
                                        .filter(m -> (m.getMessageType() == MessageType.REPLY)
                                                || (m.getMessageType() == MessageType.NEXT)
                                                || (m.getMessageType() == MessageType.GOTO))
                                        .findFirst().orElse(null);
                                // :: Find any Request messages (note that Reqs can be produced both by stage and init)
                                List<MatsSentOutgoingMessage> requests = messagesToSend.stream()
                                        .filter(m -> (m.getMessageType() == MessageType.REQUEST)
                                                && (m.getDispatchType() == DispatchType.STAGE))
                                        .collect(Collectors.toList());
                                // :: Find any initiations performed within the stage (ProcessType.STAGE_INIT)
                                List<MatsSentOutgoingMessage> initiations = messagesToSend.stream()
                                        .filter(m -> m.getDispatchType() == DispatchType.STAGE_INIT)
                                        .collect(Collectors.toList());

                                // :: "Calculate" the ProcessingResult based on current situation
                                // Start out with "NONE", i.e. no outgoing messages.
                                ProcessResult processResult = ProcessResult.NONE;
                                // Throwable "overrides" any messages
                                // ?: Did we have a throwableProcessingResult?
                                if (throwableProcessResult != null) {
                                    // -> Yes, and then this is it.
                                    processResult = throwableProcessResult;
                                }
                                // E-> No throwable
                                // ?: Did we get a "result" message?
                                else if (result != null) {
                                    // -> Yes, result message
                                    // ?: Which type is it?
                                    switch (result.getMessageType()) {
                                        case REPLY:
                                            processResult = ProcessResult.REPLY;
                                            break;
                                        case NEXT:
                                            processResult = ProcessResult.NEXT;
                                            break;
                                        case GOTO:
                                            processResult = ProcessResult.GOTO;
                                            break;
                                    }
                                }
                                // E-> No result
                                // ?: Did we get any requests?
                                else if (!requests.isEmpty()) {
                                    // -> Yes, request(s)
                                    processResult = ProcessResult.REQUEST;
                                }

                                StageCompletedContextImpl stageCompletedContext = new StageCompletedContextImpl(
                                        stageCommonContext[0], processContext[0],
                                        processResult,
                                        nanosTaken_UserLambda[0],
                                        processContext[0].getMeasurements(),
                                        processContext[0].getTimingMeasurements(),
                                        nanosTaken_totalEnvelopeSerAndComp[0],
                                        internalExecutionContext.getDbCommitNanos(),
                                        nanosTaken_totalMsgSysProdAndSend[0],
                                        internalExecutionContext.getMessageSystemCommitNanos(),
                                        nanosTaken_TotalStartReceiveToFinished,
                                        throwableResult,
                                        Collections.unmodifiableList(messagesToSend),
                                        result,
                                        requests,
                                        initiations);

                                // Go through interceptors "backwards" for this exit-style stage
                                for (int i = interceptorsForStage.size() - 1; i >= 0; i--) {
                                    try {
                                        interceptorsForStage.get(i).stageCompleted(stageCompletedContext);
                                    }
                                    catch (Throwable t) {
                                        log.error(LOG_PREFIX + "StageInterceptor [" + interceptorsForStage.get(i)
                                                + "] raised a [" + t.getClass().getSimpleName() + "] when invoking"
                                                + " stageCompleted(..) - ignoring, but this is probably quite bad.", t);
                                    }
                                }
                            }
                        }
                    }

                    // MDC is cleared afterwards, at top of loop.
                } // End: INNER RECEIVE-LOOP
            }

            // catch-all-throwable, as we do not ever want the thread to die.
            // Will crash the jmsSession - or exit out if we've been told to.
            catch (Throwable t) { // .. amongst which is JmsMatsJmsException, JMSException, all Error except Assert.
                /*
                 * NOTE: We catch all Errors /except/ AssertionError here, assuming that AssertionError only is thrown
                 * by user code or JmsMats implementation code which DO NOT refer to bad JMS connectivity. Were this
                 * assumption to fail - i.e. that ActiveMQ code throws AssertionError at some point which effectively
                 * indicates that the JMS Connection is dead - then we could be screwed: That would be handled as a
                 * "good path" (aa message processing which raised some objection), and go back to the consume-loop.
                 * However, one would think that on the next consumer.receive() call, a JMSException would be thrown,
                 * sending us down here and thus crash the jmsSession and go back "booting up" a new Connection.
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

    private void invokeStageMessageInterceptors(List<MatsStageInterceptor> interceptorsForStage,
            StageCommonContextImpl<Z> stageCommonContext, ProcessContext<Object> processContextCasted,
            List<JmsMatsMessage<Z>> messagesToSend) {
        // :: Find the Message interceptors. Goddamn why is there no stream.filter[InstanceOf](Clazz.class)?
        List<MatsStageInterceptOutgoingMessages> messageInterceptors = interceptorsForStage.stream()
                .filter(MatsStageInterceptOutgoingMessages.class::isInstance)
                .map(MatsStageInterceptOutgoingMessages.class::cast)
                .collect(Collectors.toList());
        if (!messageInterceptors.isEmpty()) {
            Consumer<String> cancelOutgoingMessage = matsMsgId -> messagesToSend
                    .removeIf(next -> next.getMatsMessageId().equals(matsMsgId));
            // Making a copy for the 'messagesToSend', as it can be modified (add/remove) by the interceptor.
            ArrayList<JmsMatsMessage<Z>> copiedMessages = new ArrayList<>();
            List<MatsEditableOutgoingMessage> unmodifiableMessages = Collections.unmodifiableList(copiedMessages);
            StageInterceptOutgoingMessageContextImpl context = new StageInterceptOutgoingMessageContextImpl(
                    stageCommonContext, processContextCasted, unmodifiableMessages, cancelOutgoingMessage);
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

    private void concatAllTraceIds(MatsTrace<Z> matsTrace, List<JmsMatsMessage<Z>> messagesToSend) {
        // ?: Are there any outgoing messages? (There are none for e.g. Terminator)
        if (!messagesToSend.isEmpty()) {
            // -> Yes, there are outgoing messages.
            // Handle standard-case where there is only one outgoing (i.e. a Service which requested or replied)
            // ?: Only one message
            if (messagesToSend.size() == 1) {
                // ?: Is the traceId different from the one we are processing?
                // (This can happen if it is a Terminator, but which send a new message)
                if (!messagesToSend.get(0).getMatsTrace().getTraceId().equals(matsTrace
                        .getTraceId())) {
                    // -> Yes, different, so create a new MDC traceId value containing both.
                    String bothTraceIds = matsTrace.getTraceId()
                            + ';' + messagesToSend.get(0).getMatsTrace().getTraceId();
                    MDC.put(MDC_TRACE_ID, bothTraceIds);
                }
                else {
                    // -> They are the same. Should already be correct on MDC, but set anyway for good measure.
                    // (an interceptor could potentially have overwritten it)
                    MDC.put(MDC_TRACE_ID, matsTrace.getTraceId());
                }
            }
            else {
                // -> There are more than 1 outgoing message. Collect and concat.
                // Using TreeSet to both: 1) de-duplicate, 2) get sort.
                Set<String> allTraceIds = new TreeSet<>();
                // Add the TraceId for the message we are processing.
                allTraceIds.add(matsTrace.getTraceId());
                // :: Add TraceIds for all the outgoing messages
                for (JmsMatsMessage<Z> msg : messagesToSend) {
                    allTraceIds.add(msg.getMatsTrace().getTraceId());
                }
                // Set new concat'ed traceId (will probably still just be one..!)
                MDC.put(MDC_TRACE_ID, String.join(";", allTraceIds));
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
        MDC.put(MDC_MATS_APP_NAME, _jmsMatsStage.getParentFactory().getFactoryConfig().getAppName());
        MDC.put(MDC_MATS_APP_VERSION, _jmsMatsStage.getParentFactory().getFactoryConfig().getAppVersion());
    }

    private void chillWait(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            log.info(LOG_PREFIX + "Got InterruptedException when chill-waiting.");
        }
    }

    private void chillWait() {
        // About 5 seconds..
        chillWait(4500 + Math.round(Math.random() * 1000));
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
        log.info(LOG_PREFIX + "Created JMS " + (_jmsMatsStage.isQueue() ? "Queue" : "Topic") + ""
                + " to receive from: [" + destination + "].");
        return destination;
    }

    /**
     * Implementation of {@link StageInterceptContext}.
     */
    private static class StageContextImpl implements StageInterceptContext {

        private final MatsStage<?, ?, ?> _matsStage;
        private final long _startedNanos;
        private final Instant _startedInstant;

        public StageContextImpl(MatsStage<?, ?, ?> matsStage, long startedNanos, Instant startedInstant) {
            _matsStage = matsStage;
            _startedNanos = startedNanos;
            _startedInstant = startedInstant;
        }

        @Override
        public MatsStage<?, ?, ?> getStage() {
            return _matsStage;
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

    /**
     * Implementation of {@link StageCommonContext}, used as a container for the common fields for the subsequent
     * interface implementations.
     */
    private static class StageCommonContextImpl<Z> implements StageCommonContext {
        private final JmsMatsStage<?, ?, ?, Z> _stage;
        private final long _startedNanos;
        private final Instant _startedInstant;

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

        public StageCommonContextImpl(JmsMatsStage<?, ?, ?, Z> stage,
                long startedNanos, Instant startedInstant,
                MatsTrace<Z> matsTrace, Object incomingMessage, Object incomingState,
                long incomingEnvelopeDeconstructNanos,
                int incomingEnvelopeRawSize,
                long incomingEnvelopeDecompressionNanos,
                int incomingEnvelopeUncompressedSize,
                long incomingEnvelopeDeserializationNanos,
                long incomingMessageAndStateDeserializationNanos,
                long preUserLambdaNanos) {

            _stage = stage;
            _startedNanos = startedNanos;
            _startedInstant = startedInstant;

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
        public Object getIncomingMessage() {
            return _incomingMessage;
        }

        @Override
        public MessageType getIncomingMessageType() {
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
        public <T> Optional<T> getIncomingExtraState(String key, Class<T> type) {
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
        public long getMessageAndStateDeserializationNanos() {
            return _incomingMessageAndStateDeserializationNanos;
        }

        @Override
        public long getTotalPreprocessAndDeserializeNanos() {
            return _preUserLambdaNanos;
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
        public MessageType getIncomingMessageType() {
            return _stageCommonContext.getIncomingMessageType();
        }

        @Override
        public Object getIncomingMessage() {
            return _stageCommonContext.getIncomingMessage();
        }

        @Override
        public Optional<Object> getIncomingState() {
            return _stageCommonContext.getIncomingState();
        }

        @Override
        public <T> Optional<T> getIncomingExtraState(String key, Class<T> type) {
            return _stageCommonContext.getIncomingExtraState(key, type);
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
        public long getMessageAndStateDeserializationNanos() {
            return _stageCommonContext.getMessageAndStateDeserializationNanos();
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
        private final ProcessContext<Object> _processContext;

        public StageReceivedContextImpl(StageCommonContext stageCommonContext,
                ProcessContext<Object> processContext) {
            super(stageCommonContext);
            _processContext = processContext;
        }

        @Override
        public ProcessContext<Object> getProcessContext() {
            return _processContext;
        }
    }

    /**
     * Implementation of {@link StageReceivedContext}.
     */
    private static class StageInterceptUserLambdaContextImpl extends StageBaseContextImpl implements
            StageInterceptUserLambdaContext {
        private final ProcessContext<Object> _processContext;

        public StageInterceptUserLambdaContextImpl(StageCommonContext stageCommonContext,
                ProcessContext<Object> processContext) {
            super(stageCommonContext);
            _processContext = processContext;
        }

        @Override
        public ProcessContext<Object> getProcessContext() {
            return _processContext;
        }
    }

    /**
     * Implementation of {@link StageInterceptOutgoingMessageContext}.
     */
    private static class StageInterceptOutgoingMessageContextImpl extends StageBaseContextImpl implements
            StageInterceptOutgoingMessageContext {
        private final ProcessContext<Object> _processContext;

        private final List<MatsEditableOutgoingMessage> _matsMessages;
        private final Consumer<String> _cancelOutgoingMessage;

        public StageInterceptOutgoingMessageContextImpl(
                StageCommonContext stageCommonContext,
                ProcessContext<Object> processContext,
                List<MatsEditableOutgoingMessage> matsMessages,
                Consumer<String> cancelOutgoingMessage) {
            super(stageCommonContext);
            _processContext = processContext;
            _matsMessages = matsMessages;
            _cancelOutgoingMessage = cancelOutgoingMessage;
        }

        @Override
        public ProcessContext<Object> getProcessContext() {
            return _processContext;
        }

        @Override
        public List<MatsEditableOutgoingMessage> getOutgoingMessages() {
            return _matsMessages;
        }

        @Override
        public void initiate(InitiateLambda lambda) {
            _processContext.initiate(lambda);
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
        private final DetachedProcessContext _detachedProcessContext;
        private final ProcessResult _processResult;

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
                DetachedProcessContext detachedProcessContext,
                ProcessResult processResult,

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
            _detachedProcessContext = detachedProcessContext;
            _processResult = processResult;

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
        public DetachedProcessContext getProcessContext() {
            return _detachedProcessContext;
        }

        @Override
        public ProcessResult getProcessResult() {
            return _processResult;
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
        private final ReceiveDeconstructError _receiveDeconstructError;
        private final Throwable _throwable;

        public StagePreprocessAndDeserializeErrorContextImpl(MatsStage<?, ?, ?> stage,
                long startedNanos, Instant startedInstant,
                ReceiveDeconstructError receiveDeconstructError, Throwable throwable) {
            _stage = stage;
            _startedNanos = startedNanos;
            _startedInstant = startedInstant;
            _receiveDeconstructError = receiveDeconstructError;
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
        public ReceiveDeconstructError getReceiveDeconstructError() {
            return _receiveDeconstructError;
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
