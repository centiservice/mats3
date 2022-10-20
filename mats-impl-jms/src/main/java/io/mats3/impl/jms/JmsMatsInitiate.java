package io.mats3.impl.jms;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsInitiator.KeepTrace;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.api.intercept.CommonCompletedContext.MatsMeasurement;
import io.mats3.api.intercept.CommonCompletedContext.MatsTimingMeasurement;
import io.mats3.api.intercept.MatsOutgoingMessage.DispatchType;
import io.mats3.impl.jms.JmsMatsInitiator.MessageReferenceImpl;
import io.mats3.impl.jms.JmsMatsProcessContext.DoAfterCommitRunnableHolder;
import io.mats3.impl.jms.JmsMatsProcessContext.Measurement;
import io.mats3.impl.jms.JmsMatsProcessContext.TimingMeasurement;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsSerializer.DeserializedMatsTrace;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.Call;
import io.mats3.serial.MatsTrace.Call.MessagingModel;
import io.mats3.serial.MatsTrace.KeepMatsTrace;

/**
 * The JMS implementation of {@link MatsInitiate}.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 * @author Endre Stølsvik - 2020-01-17, extracted from {@link JmsMatsInitiator} - http://endre.stolsvik.com
 */
class JmsMatsInitiate<Z> implements MatsInitiate, JmsMatsStatics {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsInitiate.class);

    private final JmsMatsFactory<Z> _parentFactory;
    private final List<JmsMatsMessage<Z>> _messagesToSend;
    private final JmsMatsInternalExecutionContext _jmsMatsInternalExecutionContext;
    private final DoAfterCommitRunnableHolder _doAfterCommitRunnableHolder;

    // :: Only for "true initiation"
    private final String _existingMdcTraceId;

    // :: Only for "within Stage"
    private final MatsTrace<Z> _existingMatsTrace;

    static <Z> JmsMatsInitiate<Z> createForTrueInitiation(JmsMatsFactory<Z> parentFactory,
            List<JmsMatsMessage<Z>> messagesToSend, JmsMatsInternalExecutionContext jmsMatsInternalExecutionContext,
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder, String existingMdcTraceId) {
        return new JmsMatsInitiate<>(parentFactory, messagesToSend, jmsMatsInternalExecutionContext,
                doAfterCommitRunnableHolder,
                null, existingMdcTraceId);
    }

    static <Z> JmsMatsInitiate<Z> createForChildFlow(JmsMatsFactory<Z> parentFactory,
            List<JmsMatsMessage<Z>> messagesToSend, JmsMatsInternalExecutionContext jmsMatsInternalExecutionContext,
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder,
            MatsTrace<Z> existingMatsTrace) {
        return new JmsMatsInitiate<>(parentFactory, messagesToSend, jmsMatsInternalExecutionContext,
                doAfterCommitRunnableHolder,
                existingMatsTrace, null);
    }

    private JmsMatsInitiate(JmsMatsFactory<Z> parentFactory, List<JmsMatsMessage<Z>> messagesToSend,
            JmsMatsInternalExecutionContext jmsMatsInternalExecutionContext,
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder,
            MatsTrace<Z> existingMatsTrace, String existingMdcTraceId) {
        _parentFactory = parentFactory;
        _messagesToSend = messagesToSend;
        _jmsMatsInternalExecutionContext = jmsMatsInternalExecutionContext;
        _doAfterCommitRunnableHolder = doAfterCommitRunnableHolder;

        _existingMatsTrace = existingMatsTrace;
        _existingMdcTraceId = existingMdcTraceId;

        reset();
    }

    private String _traceId;
    private KeepMatsTrace _keepTrace;
    private boolean _nonPersistent;
    private boolean _interactive;
    private long _timeToLive;
    private boolean _noAudit;
    private String _from;
    private String _to;
    private String _replyTo;
    private boolean _replyToSubscription;
    private Object _replySto;
    private final LinkedHashMap<String, Object> _props = new LinkedHashMap<>();
    private final LinkedHashMap<String, byte[]> _binaries = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> _strings = new LinkedHashMap<>();

    /**
     * Invoked to (re)set situation at creation, AND after each of request(..), send(..) or publish(..).
     */
    private void reset() {
        // ?: Is this a initiation from within a Stage? (NOT via a MatsInitiator, i.e. NOT "from the outside")
        if (_existingMatsTrace != null) {
            // -> Yes, initiation within a Stage.
            // Set the initial traceId - any setting of TraceId is appended.
            _traceId = _existingMatsTrace.getTraceId();
            // Set the initial from (initiatorId), which is the current processing stage
            _from = _existingMatsTrace.getCurrentCall().getTo().getId();
        }
        else {
            // -> No, this is an initiation from MatsInitiator, i.e. "from the outside".
            _traceId = null;
            _from = null;
        }

        // :: Since the '_parentFactory.getInitiateTraceIdModifier()' might use the MDC to modify (prefix) the TraceId
        // with, after each sent message, we need to reset it to whatever it was at start.
        // ?: Did we have an existing MDC traceId? (only for "true initiations")
        if (_existingMdcTraceId != null) {
            // -> Yes, so restore it.
            MDC.put(MDC_TRACE_ID, _existingMdcTraceId);
        }
        else {
            // -> No, so clear it.
            MDC.remove(MDC_TRACE_ID);
        }

        // :: Set defaults
        // NOTE: _traceId is set above.
        // Using setter-method to set the default keepTrace, due to Impl vs. API difference in enums.
        keepTrace(_parentFactory.getDefaultKeepTrace());
        _nonPersistent = false;
        _interactive = false;
        _timeToLive = 0;
        // NOTE: _from is set above
        _to = null;
        _replyTo = null;
        _replyToSubscription = false;
        _replySto = null;
        _props.clear();
        _binaries.clear();
        _strings.clear();
    }

    @Override
    public MatsInitiate traceId(CharSequence traceId) {
        // :: Decide how to handle TraceId:
        // 1. If within Stage, prefix with existing message's traceId
        // 2. If outside Stage, modify with any configured function
        // 3. Use as is.

        // ?: Are we within a Stage?
        if (_existingMatsTrace != null) {
            // -> Yes, so use prefixing
            _traceId = _existingMatsTrace.getTraceId() + "|" + traceId;
        }
        // ?: Do we have modifier function?
        else if (_parentFactory.getInitiateTraceIdModifier() != null) {
            // -> Yes, so use this.
            _traceId = _parentFactory.getInitiateTraceIdModifier().apply(traceId.toString());
        }
        else {
            // -> No, neither within Stage, nor having modifier function, so use directly.
            _traceId = traceId.toString();
        }

        // Also set this on the MDC so that we have it on log lines if it crashes within the initiation lambda
        // NOTICE: The MDC will always be reset to the existing (init), or overwritten with new (stage proc), after
        // initiation lambda is finished, so this will not trash the traceId from an existing context.
        MDC.put(MDC_TRACE_ID, _traceId);
        return this;
    }

    @Override
    public MatsInitiate keepTrace(KeepTrace keepTrace) {
        if (keepTrace == KeepTrace.MINIMAL) {
            _keepTrace = KeepMatsTrace.MINIMAL;
        }
        else if (keepTrace == KeepTrace.COMPACT) {
            _keepTrace = KeepMatsTrace.COMPACT;
        }
        else if (keepTrace == KeepTrace.FULL) {
            _keepTrace = KeepMatsTrace.FULL;
        }
        else {
            throw new IllegalArgumentException("Unknown KeepTrace enum [" + keepTrace + "].");
        }
        return this;
    }

    @Override
    public MatsInitiate nonPersistent() {
        nonPersistent(0);
        return this;
    }

    @Override
    public MatsInitiate nonPersistent(long timeToLiveMillis) {
        if (timeToLiveMillis < 0) {
            throw new IllegalArgumentException("timeToLive must be > 0");
        }
        _nonPersistent = true;
        _timeToLive = timeToLiveMillis;
        return this;
    }

    @Override
    public MatsInitiate interactive() {
        _interactive = true;
        return this;
    }

    @Override
    @Deprecated
    public MatsInitiate timeToLive(long timeToLiveMillis) {
        if (timeToLiveMillis < 0) {
            throw new IllegalArgumentException("timeToLive must be > 0");
        }
        _timeToLive = timeToLiveMillis;
        return this;
    }

    @Override
    public MatsInitiate noAudit() {
        _noAudit = true;
        return this;
    }

    @Override
    public MatsInitiate from(String initiatorId) {
        _from = initiatorId;
        return this;
    }

    @Override
    public MatsInitiate to(String endpointId) {
        _to = endpointId;
        return this;
    }

    @Override
    public MatsInitiate replyTo(String endpointId, Object replySto) {
        _replyTo = endpointId;
        _replySto = replySto;
        _replyToSubscription = false;
        return this;
    }

    @Override
    public MatsInitiate replyToSubscription(String endpointId, Object replySto) {
        _replyTo = endpointId;
        _replySto = replySto;
        _replyToSubscription = true;
        return this;
    }

    @Override
    public MatsInitiate setTraceProperty(String propertyName, Object propertyValue) {
        _props.put(propertyName, propertyValue);
        return this;
    }

    @Override
    public MatsInitiate addBytes(String key, byte[] payload) {
        _binaries.put(key, payload);
        return this;
    }

    @Override
    public MatsInitiate addString(String key, String payload) {
        _strings.put(key, payload);
        return this;
    }

    private Set<String> _metricsIds = Collections.emptySet();
    private List<MatsMeasurement> _measurements = Collections.emptyList();
    private List<MatsTimingMeasurement> _timingMeasurements = Collections.emptyList();

    @Override
    public MatsInitiate logMeasurement(String metricId, String metricDescription, String baseUnit, double measure,
            String... labelKeyValue) {
        JmsMatsProcessContext.assertMetricArgs(metricId, metricDescription, baseUnit, labelKeyValue);
        assertMetricId(metricId);
        if (_measurements.isEmpty()) {
            _measurements = new ArrayList<>();
        }
        _measurements.add(new Measurement(metricId, metricDescription, baseUnit, measure, labelKeyValue));
        return this;
    }

    @Override
    public MatsInitiate logTimingMeasurement(String metricId, String metricDescription, long nanos,
            String... labelKeyValue) {
        JmsMatsProcessContext.assertMetricArgs(metricId, metricDescription, "dummy", labelKeyValue);
        assertMetricId(metricId);
        if (_timingMeasurements.isEmpty()) {
            _timingMeasurements = new ArrayList<>();
        }
        _timingMeasurements.add(new TimingMeasurement(metricId, metricDescription, nanos, labelKeyValue));
        return this;
    }

    private void assertMetricId(String metricId) {
        if (_metricsIds.contains(metricId)) {
            throw new IllegalArgumentException("The metricId [" + metricId + "] has been logged earlier.");
        }
        if (_metricsIds.isEmpty()) {
            _metricsIds = new HashSet<>();
        }
        _metricsIds.add(metricId);
    }

    List<MatsMeasurement> getMeasurements() {
        return _measurements;
    }

    List<MatsTimingMeasurement> getTimingMeasurements() {
        return _timingMeasurements;
    }

    @Override
    public MessageReference request(Object requestDto) {
        return request(requestDto, null);
    }

    @Override
    public MessageReference request(Object requestDto, Object initialTargetSto) {
        long nanosStartProducingOutgoingMessage = System.nanoTime();
        String msg = "All of 'traceId', 'from', 'to' and 'replyTo' must be set when request(..)";
        checkCommon(msg);
        if (_replyTo == null) {
            throw new NullPointerException(msg + ": Missing 'replyTo'.");
        }
        MatsSerializer<Z> ser = _parentFactory.getMatsSerializer();
        long now = System.currentTimeMillis();
        MatsTrace<Z> requestMatsTrace = createMatsTrace(ser, now)
                .addRequestCall(_from, _to, MessagingModel.QUEUE,
                        _replyTo, (_replyToSubscription ? MessagingModel.TOPIC : MessagingModel.QUEUE),
                        ser.serializeObject(requestDto),
                        ser.serializeObject(_replySto),
                        ser.serializeObject(initialTargetSto));
        produceMessage(requestDto, initialTargetSto, nanosStartProducingOutgoingMessage, requestMatsTrace);

        return new MessageReferenceImpl(requestMatsTrace.getCurrentCall().getMatsMessageId());
    }

    @Override
    public MessageReference send(Object messageDto) {
        return send(messageDto, null);
    }

    @Override
    public MessageReference send(Object messageDto, Object initialTargetSto) {
        long nanosStart = System.nanoTime();
        String msg = "All of 'traceId', 'from' and 'to' must be set, and 'replyTo' not set, when send(..)";
        checkCommon(msg);
        if (_replyTo != null) {
            throw new IllegalArgumentException(msg + ": 'replyTo' is set.");
        }

        MatsSerializer<Z> ser = _parentFactory.getMatsSerializer();
        long now = System.currentTimeMillis();
        MatsTrace<Z> sendMatsTrace = createMatsTrace(ser, now)
                .addSendCall(_from, _to, MessagingModel.QUEUE,
                        ser.serializeObject(messageDto), ser.serializeObject(initialTargetSto));
        produceMessage(messageDto, initialTargetSto, nanosStart, sendMatsTrace);

        return new MessageReferenceImpl(sendMatsTrace.getCurrentCall().getMatsMessageId());
    }

    @Override
    public MessageReference publish(Object messageDto) {
        return publish(messageDto, null);
    }

    @Override
    public MessageReference publish(Object messageDto, Object initialTargetSto) {
        long nanosStart = System.nanoTime();
        String msg = "All of 'traceId', 'from' and 'to' must be set, and 'replyTo' not set, when publish(..)";
        checkCommon(msg);
        if (_replyTo != null) {
            throw new IllegalArgumentException(msg + ": 'replyTo' is set.");
        }
        MatsSerializer<Z> ser = _parentFactory.getMatsSerializer();
        long now = System.currentTimeMillis();
        MatsTrace<Z> publishMatsTrace = createMatsTrace(ser, now)
                .addSendCall(_from, _to, MessagingModel.TOPIC,
                        ser.serializeObject(messageDto), ser.serializeObject(initialTargetSto));
        produceMessage(messageDto, initialTargetSto, nanosStart, publishMatsTrace);

        return new MessageReferenceImpl(publishMatsTrace.getCurrentCall().getMatsMessageId());
    }

    private void produceMessage(Object messageDto, Object initialTargetSto, long nanosStartProducingOutgoingMessage,
            MatsTrace<Z> outgoingMatsTrace) {
        // We do not need to set currentCall.setDebugInfo(..), as it is the same as on the initiation.
        // BUT, since the old versions <= 0.18.4 believe it is set, we set it still.
        // TODO: Remove once all are >= 0.18.5
        outgoingMatsTrace.getCurrentCall().setDebugInfo(_parentFactory.getFactoryConfig().getAppName(),
                _parentFactory.getFactoryConfig().getAppVersion(), _parentFactory.getFactoryConfig().getNodename(),
                "#init#");

        // ?: Do we have an existing MatsTrace (implying that we are being initiated within a Stage)
        if (_existingMatsTrace != null) {
            // -> Yes, so copy over existing Trace Properties
            for (String key : _existingMatsTrace.getTracePropertyKeys()) {
                outgoingMatsTrace.setTraceProperty(key, _existingMatsTrace.getTraceProperty(key));
            }
        }

        // Produce the new JmsMatsMessage to send
        JmsMatsMessage<Z> jmsMatsMessage = JmsMatsMessage.produceMessage(getProcessType(),
                nanosStartProducingOutgoingMessage, _parentFactory.getMatsSerializer(), outgoingMatsTrace,
                messageDto, initialTargetSto, _replySto, _props, _binaries, _strings);
        _messagesToSend.add(jmsMatsMessage);

        // Reset, in preparation for more messages
        // Note: Props, Binaries and Strings are cleared (but copied off by the produceMessage(..) call)
        reset();
    }

    private DispatchType getProcessType() {
        // If we have an existing MatsTrace, it is because we're initiating within a Stage, otherwise "outside".
        return _existingMatsTrace != null ? DispatchType.STAGE_INIT : DispatchType.INIT;
    }

    private MatsTrace<Z> createMatsTrace(MatsSerializer<Z> ser, long now) {
        String flowId = createFlowId(now);
        String debugInfo = _keepTrace != KeepMatsTrace.MINIMAL
                ? getInvocationPoint()
                : null;
        // If we got no info, replace with a tad more specific no info!
        debugInfo = (NO_INVOCATION_POINT.equals(debugInfo) ? "-no_info(init)-" : debugInfo);

        MatsTrace<Z> matsTrace = ser.createNewMatsTrace(_traceId, flowId, _keepTrace, _nonPersistent, _interactive,
                _timeToLive, _noAudit)
                .withDebugInfo(_parentFactory.getFactoryConfig().getAppName(),
                        _parentFactory.getFactoryConfig().getAppVersion(),
                        _parentFactory.getFactoryConfig().getNodename(), _from, debugInfo);
        // ?: Is this a child flow?
        if (_existingMatsTrace != null) {
            // -> Yes, so initialize it as such.
            matsTrace.withChildFlow(_existingMatsTrace.getCurrentCall().getMatsMessageId(),
                    _existingMatsTrace.getTotalCallNumber() + 1);
        }
        return matsTrace;
    }

    @Override
    public <R, S, I> void unstash(byte[] stash,
            Class<R> replyClass, Class<S> stateClass, Class<I> incomingClass,
            ProcessLambda<R, S, I> lambda) {

        long nanosStart = System.nanoTime();

        if (stash == null) {
            throw new NullPointerException("byte[] stash");
        }

        // :: Validate that this is a "MATSjmts" v.1 stash. ("jmts" -> "Jms MatsTrace Serializer")
        validateByte(stash, 0, 77);
        validateByte(stash, 1, 65);
        validateByte(stash, 2, 84);
        validateByte(stash, 3, 83);
        validateByte(stash, 4, 106);
        validateByte(stash, 5, 109);
        validateByte(stash, 6, 116);
        validateByte(stash, 7, 115);
        validateByte(stash, 8, 1);

        // ----- Validated ok. Could have thrown in a checksum, but if foot-shooting is your thing, then go ahead.

        // ::: Get the annoying metadata

        // How many such fields are there. The idea is that we can add more fields in later revisions, and
        // just have older versions "jump over" the ones it does not know.
        int howManyZeros = stash[9];

        // :: Find zeros (field delimiters) - UTF-8 does not have zeros: https://stackoverflow.com/a/6907327/39334
        int zstartEndpointId = findZero(stash, 10); // Should currently be right there, at pos#10.
        int zstartStageId = findZero(stash, zstartEndpointId + 1);
        int zstartNextStageId = findZero(stash, zstartStageId + 1);
        int zstartMatsTraceMeta = findZero(stash, zstartNextStageId + 1);
        int zstartSystemMessageId = findZero(stash, zstartMatsTraceMeta + 1);
        // :: Here we'll jump over fields that we do not know, to be able to add more metadata in later revisions.
        int zstartMatsTrace = zstartSystemMessageId;
        for (int i = 5; i < howManyZeros; i++) {
            zstartMatsTrace = findZero(stash, zstartMatsTrace + 1);
        }

        // :: Metadata
        // :EndpointId
        String endpointId = new String(stash, zstartEndpointId + 1, zstartStageId - zstartEndpointId - 1,
                StandardCharsets.UTF_8);
        // :StageId
        String stageId = new String(stash, zstartStageId + 1, zstartNextStageId - zstartStageId - 1,
                StandardCharsets.UTF_8);
        // :NextStageId
        // If nextStageId == the special "no next stage" string, then null. Else get it.
        String nextStageId = (zstartMatsTraceMeta - zstartNextStageId
                - 1) == JmsMatsProcessContext.NO_NEXT_STAGE.length &&
                stash[zstartNextStageId + 1] == JmsMatsProcessContext.NO_NEXT_STAGE[0]
                        ? null
                        : new String(stash, zstartNextStageId + 1,
                                zstartMatsTraceMeta - zstartNextStageId - 1, StandardCharsets.UTF_8);
        // :MatsTrace Meta
        String matsTraceMeta = new String(stash, zstartMatsTraceMeta + 1,
                zstartSystemMessageId - zstartMatsTraceMeta - 1, StandardCharsets.UTF_8);
        // :MessageId
        String messageId = new String(stash, zstartSystemMessageId + 1,
                zstartMatsTrace - zstartSystemMessageId - 1, StandardCharsets.UTF_8);

        // :Actual MatsTrace:
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        DeserializedMatsTrace<Z> deserializedMatsTrace = matsSerializer
                .deserializeMatsTrace(stash, zstartMatsTrace + 1,
                        stash.length - zstartMatsTrace - 1, matsTraceMeta);
        MatsTrace<Z> matsTrace = deserializedMatsTrace.getMatsTrace();

        // :: Current State: If null, make an empty object instead, unless Void, which is null.
        S currentSto = handleIncomingState(matsSerializer, stateClass, matsTrace.getCurrentState().orElse(null));

        // :: Current Call, incoming Message DTO
        Call<Z> currentCall = matsTrace.getCurrentCall();
        I incomingDto = handleIncomingMessageMatsObject(matsSerializer, incomingClass, currentCall.getData());

        double millisDeserializing = (System.nanoTime() - nanosStart) / 1_000_000d;

        log.info(LOG_PREFIX + "Unstashing message from [" + stash.length + " B] stash, R:[" + replyClass
                .getSimpleName() + "], S:[" + stateClass.getSimpleName() + "], I:[" + incomingClass.getSimpleName()
                + "]. From StageId:[" + matsTrace.getCurrentCall().getFrom() + "], This StageId:[" + stageId
                + "], NextStageId:[" + nextStageId + "] - deserializing took ["
                + millisDeserializing + " ms]");

        Supplier<MatsInitiate> initiateSupplier = () -> JmsMatsInitiate.createForChildFlow(_parentFactory,
                _messagesToSend, _jmsMatsInternalExecutionContext, _doAfterCommitRunnableHolder,
                matsTrace);

        _parentFactory.setCurrentMatsFactoryThreadLocal_ExistingMatsInitiate(initiateSupplier);

        // :: Invoke the process lambda (the actual user code).
        try {
            JmsMatsProcessContext<R, S, Z> processContext = new JmsMatsProcessContext<>(
                    _parentFactory,
                    endpointId,
                    stageId,
                    messageId,
                    nextStageId,
                    /* The stack should be user-based here, so getInvocationPoint() should be good. */
                    "-no_info(unstash)-",
                    matsTrace,
                    currentSto,
                    new LinkedHashMap<>(), new LinkedHashMap<>(),
                    _messagesToSend, _jmsMatsInternalExecutionContext,
                    _doAfterCommitRunnableHolder);

            JmsMatsContextLocalCallback.bindResource(ProcessContext.class, processContext);

            lambda.process(processContext, currentSto, incomingDto);
        }
        catch (MatsRefuseMessageException e) {
            throw new IllegalStateException("Cannot throw MatsRefuseMessageException when unstash()'ing!"
                    + " You should have done that when you first received the message, before"
                    + " stash()'ing it.", e);
        }
        finally {
            _parentFactory.clearCurrentMatsFactoryThreadLocal_ExistingMatsInitiate();
            JmsMatsContextLocalCallback.unbindResource(ProcessContext.class);
        }

        // No need to reset() here, as we've not touched the _from, _to, etc..
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getAttribute(Class<T> type, String... name) {
        // TODO: Way to stick in MatsFactory-configured attributes. Notice: both in ProcessContext and Initiate.
        // ?: Is this a query for SQL Connection, without any names?
        if ((type == Connection.class) && (name.length == 0)) {
            // -> Yes, then it is the default transactional SQL Connection.
            return (Optional<T>) _jmsMatsInternalExecutionContext.getSqlConnection();
        }
        return Optional.empty();
    }

    private static void validateByte(byte[] stash, int idx, int value) {
        if (stash[idx] != value) {
            throw new IllegalArgumentException("The stash bytes shall start with ASCII letters 'MATSjmts' and then"
                    + " a byte denoting the version. Index [" + idx + "] should be [" + value
                    + "], but was [" + stash[idx] + "].");
        }
    }

    private static int findZero(byte[] stash, int fromIndex) {
        try {
            int t = fromIndex;
            while (stash[t] != 0) {
                t++;
            }
            return t;
        }
        catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("The stash byte array does not contain the zeros I expected,"
                    + " starting from index [" + fromIndex + "]");
        }
    }

    private void checkCommon(String msg) {
        if ((_timeToLive > 0) && (!_nonPersistent)) {
            throw new IllegalStateException("TimeToLive is set [" + _timeToLive
                    + "], but message is not NonPersistent - illegal combination.");
        }
        if (_traceId == null) {
            throw new NullPointerException(msg + ": Missing 'traceId'.");
        }
        if (_from == null) {
            throw new NullPointerException(msg + ": Missing 'from'.");
        }
        if (_to == null) {
            throw new NullPointerException(msg + ": Missing 'to'.");
        }
    }

    @Override
    public String toString() {
        return idThis();
    }
}
