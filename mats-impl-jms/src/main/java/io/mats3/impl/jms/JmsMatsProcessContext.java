package io.mats3.impl.jms;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.MatsStage;
import io.mats3.api.intercept.CommonCompletedContext.MatsMeasurement;
import io.mats3.api.intercept.CommonCompletedContext.MatsTimingMeasurement;
import io.mats3.api.intercept.MatsOutgoingMessage.DispatchType;
import io.mats3.impl.jms.JmsMatsInitiator.MessageReferenceImpl;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsSerializer.SerializedMatsTrace;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.Call;
import io.mats3.serial.MatsTrace.Call.Channel;
import io.mats3.serial.MatsTrace.Call.MessagingModel;
import io.mats3.serial.MatsTrace.KeepMatsTrace;

/**
 * The JMS Mats implementation of {@link ProcessContext}. Instantiated for each incoming JMS message that is processed,
 * given to the {@link MatsStage}'s process lambda.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class JmsMatsProcessContext<R, S, Z> implements ProcessContext<R>, JmsMatsStatics {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsProcessContext.class);

    private final JmsMatsFactory<Z> _parentFactory;

    private final String _endpointId;
    private final String _stageId;
    private final String _systemMessageId;
    private final String _nextStageId;

    private final String _stageOrigin;

    private final MatsTrace<Z> _incomingMatsTrace;
    private final LinkedHashMap<String, byte[]> _incomingBinaries;
    private final LinkedHashMap<String, String> _incomingStrings;
    private final S _incomingAndOutgoingState;
    private final List<JmsMatsMessage<Z>> _messagesToSend;
    private final JmsMatsInternalExecutionContext _jmsMatsInternalExecutionContext;
    private final DoAfterCommitRunnableHolder _doAfterCommitRunnableHolder;

    // :: Overrides if NEXT_DIRECT:

    private boolean _isNextDirect;
    private String _nd_fromAppName;
    private String _nd_fromAppVersion;
    private String _nd_fromStageId;
    private Instant _nd_fromTimestamp;

    // :: Outgoing:

    // Set when outgoing flow message is created, to catch illegal call flows
    private DebugStackTraceException _requestsSent;
    private DebugStackTraceException _replyOrNextOrGotoSent;

    private final LinkedHashMap<String, Object> _outgoingProps = new LinkedHashMap<>();
    private final LinkedHashMap<String, byte[]> _outgoingBinaries = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> _outgoingStrings = new LinkedHashMap<>();

    // :: "Return values" if NEXT_DIRECT is invoked.
    private boolean _nextDirectInvoked; // Set 'true' of nextDirect is invoked
    private Object _nextDirectDto; // Value of nextDirect incoming message

    private JmsMatsProcessContext(JmsMatsFactory<Z> parentFactory,
            String endpointId,
            String stageId,
            String systemMessageId,
            String nextStageId,
            String stageOrigin,
            MatsTrace<Z> incomingMatsTrace, S incomingAndOutgoingState,
            LinkedHashMap<String, byte[]> incomingBinaries, LinkedHashMap<String, String> incomingStrings,
            List<JmsMatsMessage<Z>> out_messagesToSend,
            JmsMatsInternalExecutionContext jmsMatsInternalExecutionContext,
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder) {
        _parentFactory = parentFactory;

        _endpointId = endpointId;
        _stageId = stageId;
        _systemMessageId = systemMessageId;
        _nextStageId = nextStageId;
        _stageOrigin = stageOrigin;

        _incomingMatsTrace = incomingMatsTrace;
        _incomingBinaries = incomingBinaries;
        _incomingStrings = incomingStrings;
        _incomingAndOutgoingState = incomingAndOutgoingState;
        _messagesToSend = out_messagesToSend;
        _jmsMatsInternalExecutionContext = jmsMatsInternalExecutionContext;
        _doAfterCommitRunnableHolder = doAfterCommitRunnableHolder;
    }

    void override_FromProps_ForNextDirect(String nd_fromAppName, String nd_fromAppVersion,
                                          String nd_fromStageId, Instant nd_fromTimestamp) {
        _isNextDirect = true;
        _nd_fromAppName = nd_fromAppName;
        _nd_fromAppVersion = nd_fromAppVersion;
        _nd_fromStageId = nd_fromStageId;
        _nd_fromTimestamp = nd_fromTimestamp;
    }

    static <R, S, Z> JmsMatsProcessContext<R, S, Z> create(JmsMatsFactory<Z> parentFactory,
            String endpointId, String stageId, String systemMessageId, String nextStageId, String stageOrigin,
            MatsTrace<Z> incomingMatsTrace, S incomingAndOutgoingState,
            LinkedHashMap<String, byte[]> incomingBinaries, LinkedHashMap<String, String> incomingStrings,
            List<JmsMatsMessage<Z>> out_messagesToSend,
            JmsMatsInternalExecutionContext jmsMatsInternalExecutionContext,
            DoAfterCommitRunnableHolder doAfterCommitRunnableHolder) {
        return new JmsMatsProcessContext<>(parentFactory, endpointId, stageId, systemMessageId, nextStageId,
                stageOrigin, incomingMatsTrace, incomingAndOutgoingState, incomingBinaries,
                incomingStrings, out_messagesToSend, jmsMatsInternalExecutionContext, doAfterCommitRunnableHolder);
    }


    // :: Need access to the outgoing props, binaries and strings for nextDirect

    LinkedHashMap<String, Object> getOutgoingProps() {
        return _outgoingProps;
    }

    LinkedHashMap<String, byte[]> getOutgoingBinaries() {
        return _outgoingBinaries;
    }

    LinkedHashMap<String, String> getOutgoingStrings() {
        return _outgoingStrings;
    }

    /**
     * Holds any Runnable set by {@link #doAfterCommit(Runnable)}.
     */
    static class DoAfterCommitRunnableHolder {
        private Runnable _doAfterCommit;

        void setDoAfterCommit(Runnable runnable) {
            _doAfterCommit = runnable;
        }

        public void runDoAfterCommitIfAny() {
            if (_doAfterCommit != null) {
                _doAfterCommit.run();
            }
        }
    }

    @Override
    public String getStageId() {
        return _stageId;
    }

    @Override
    public String getFromAppName() {
        // ?: Is this a NEXT_DIRECT?
        if (_isNextDirect) {
            // -> Yes, NEXT_DIRECT
            return _nd_fromAppName;
        }
        // E-> No, not NEXT_DIRECT
        // If first call, then there is no CallingAppName on CurrentCall (to save some space), since it would be the
        // same as initializing.
        return _incomingMatsTrace.getCallNumber() == 1
                ? _incomingMatsTrace.getInitializingAppName()
                : _incomingMatsTrace.getCurrentCall().getCallingAppName();
    }

    @Override
    public String getFromAppVersion() {
        // ?: Is this a NEXT_DIRECT?
        if (_isNextDirect) {
            // -> Yes, NEXT_DIRECT
            return _nd_fromAppVersion;
        }
        // E-> No, not NEXT_DIRECT
        // If first call, then there is no CallingAppVersion on CurrentCall (to save some space), since it would be the
        // same as initializing.
        return _incomingMatsTrace.getCallNumber() == 1
                ? _incomingMatsTrace.getInitializingAppVersion()
                : _incomingMatsTrace.getCurrentCall().getCallingAppVersion();
    }

    @Override
    public String getFromStageId() {
        // ?: Is this a NEXT_DIRECT?
        if (_isNextDirect) {
            // -> Yes, NEXT_DIRECT
            return _nd_fromStageId;
        }
        // E-> No, not NEXT_DIRECT
        return _incomingMatsTrace.getCurrentCall().getFrom();
    }

    @Override
    public Instant getFromTimestamp() {
        // ?: Is this a NEXT_DIRECT?
        if (_isNextDirect) {
            // -> Yes, NEXT_DIRECT
            return _nd_fromTimestamp;
        }
        // E-> No, not NEXT_DIRECT
        return Instant.ofEpochMilli(_incomingMatsTrace.getCurrentCall().getCalledTimestamp());
    }

    @Override
    public String getInitiatingAppName() {
        return _incomingMatsTrace.getInitializingAppName();
    }

    @Override
    public String getInitiatingAppVersion() {
        return _incomingMatsTrace.getInitializingAppVersion();
    }

    @Override
    public String getInitiatorId() {
        return _incomingMatsTrace.getInitiatorId();
    }

    @Override
    public Instant getInitiatingTimestamp() {
        return Instant.ofEpochMilli(_incomingMatsTrace.getInitializedTimestamp());
    }

    @Override
    public String getMatsMessageId() {
        return _incomingMatsTrace.getCurrentCall().getMatsMessageId();
    }

    @Override
    public String getSystemMessageId() {
        return _systemMessageId;
    }

    @Override
    public boolean isNonPersistent() {
        return _incomingMatsTrace.isNonPersistent();
    }

    @Override
    public boolean isInteractive() {
        return _incomingMatsTrace.isInteractive();
    }

    @Override
    public boolean isNoAudit() {
        return _incomingMatsTrace.isNoAudit();
    }

    @Override
    public Set<String> getBytesKeys() {
        return _incomingBinaries.keySet();
    }

    @Override
    public String toString() {
        return _incomingMatsTrace.toString();
    }

    @Override
    public String getTraceId() {
        return _incomingMatsTrace.getTraceId();
    }

    @Override
    public String getEndpointId() {
        return _endpointId;
    }

    @Override
    public byte[] getBytes(String key) {
        return _incomingBinaries.get(key);
    }

    @Override
    public Set<String> getStringKeys() {
        return _incomingStrings.keySet();
    }

    @Override
    public String getString(String key) {
        return _incomingStrings.get(key);
    }

    @Override
    public void addBytes(String key, byte[] payload) {
        if (key == null) {
            throw new NullPointerException("addBytes(..): 'key' is null.");
        }
        if (payload == null) {
            throw new NullPointerException("addBytes(..): 'payload' is null");
        }
        _outgoingBinaries.put(key, payload);
    }

    @Override
    public void addString(String key, String payload) {
        if (key == null) {
            throw new NullPointerException("addString(..): 'key' is null.");
        }
        if (payload == null) {
            throw new NullPointerException("addString(..): 'payload' is null");
        }
        _outgoingStrings.put(key, payload);
    }

    @Override
    public void setTraceProperty(String propertyName, Object propertyValue) {
        _outgoingProps.put(propertyName, propertyValue);
    }

    static class Measurement implements MatsMeasurement {
        final String _metricId;
        final String _metricDescription;
        final String _baseUnit;
        final double _measure;
        final String[] _labelKeyValue;

        public Measurement(String metricId, String metricDescription, String baseUnit, double measure,
                String[] labelKeyValue) {
            _metricId = metricId;
            _metricDescription = metricDescription;
            _baseUnit = baseUnit;
            _measure = measure;
            _labelKeyValue = labelKeyValue;
        }

        @Override
        public String getMetricId() {
            return _metricId;
        }

        @Override
        public String getMetricDescription() {
            return _metricDescription;
        }

        @Override
        public String getBaseUnit() {
            return _baseUnit;
        }

        @Override
        public double getMeasure() {
            return _measure;
        }

        @Override
        public String[] getLabelKeyValue() {
            return _labelKeyValue;
        }
    }

    static class TimingMeasurement implements MatsTimingMeasurement {
        final String _metricId;
        final String _metricDescription;
        final long _nanos;
        final String[] _labelKeyValue;

        public TimingMeasurement(String metricId, String metricDescription, long nanos, String[] labelKeyValue) {
            _metricId = metricId;
            _metricDescription = metricDescription;
            _nanos = nanos;
            _labelKeyValue = labelKeyValue;
        }

        @Override
        public String getMetricId() {
            return _metricId;
        }

        @Override
        public String getMetricDescription() {
            return _metricDescription;
        }

        @Override
        public long getNanos() {
            return _nanos;
        }

        @Override
        public String[] getLabelKeyValue() {
            return _labelKeyValue;
        }
    }

    private Set<String> _metricsIds = Collections.emptySet();
    private List<MatsMeasurement> _measurements = Collections.emptyList();
    private List<MatsTimingMeasurement> _timingMeasurements = Collections.emptyList();

    @Override
    public void logMeasurement(String metricId, String metricDescription, String baseUnit, double measure,
            String... labelKeyValue) {
        assertMetricArgs(metricId, metricDescription, baseUnit, labelKeyValue);
        assertMetricId(metricId);
        if (_measurements.isEmpty()) {
            _measurements = new ArrayList<>();
        }
        _measurements.add(new Measurement(metricId, metricDescription, baseUnit, measure, labelKeyValue));
    }

    @Override
    public void logTimingMeasurement(String metricId, String metricDescription, long nanos, String... labelKeyValue) {
        assertMetricArgs(metricId, metricDescription, "dummy", labelKeyValue);
        assertMetricId(metricId);
        if (_timingMeasurements.isEmpty()) {
            _timingMeasurements = new ArrayList<>();
        }
        _timingMeasurements.add(new TimingMeasurement(metricId, metricDescription, nanos, labelKeyValue));
    }

    static void assertMetricArgs(String metricId, String metricDescription, String baseUnit, String... labelKeyValue) {
        if (metricId == null) {
            throw new NullPointerException("metricId");
        }
        if (metricId.isEmpty()) {
            throw new IllegalArgumentException("'metricId' is empty.");
        }
        if (metricDescription == null) {
            throw new NullPointerException("metricDescription");
        }
        if (baseUnit == null) {
            throw new NullPointerException("baseUnit");
        }
        for (int i = 0; i < labelKeyValue.length; i++) {
            if (labelKeyValue[i] == null) {
                throw new IllegalArgumentException("Value [" + i + "] of the labelKeyValue vararg array is null: "
                        + Arrays.toString(labelKeyValue));
            }
        }
        if ((labelKeyValue.length & 1) == 1) {
            throw new IllegalArgumentException("The labelKeyValue vararg array has an odd number of elements, which"
                    + " doesn't make sense since it should be alternate key-value pars: " + Arrays.toString(
                            labelKeyValue));
        }
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

    static final byte[] NO_NEXT_STAGE = "-".getBytes(StandardCharsets.UTF_8);

    @Override
    public byte[] stash() {
        long nanosStart = System.nanoTime();

        // Serialize the endpointId
        byte[] b_endpointId = _endpointId.getBytes(StandardCharsets.UTF_8);
        // .. stageId
        byte[] b_stageId = _stageId.getBytes(StandardCharsets.UTF_8);
        // .. nextStageId, handling that it might be null.
        byte[] b_nextStageId = _nextStageId == null ? NO_NEXT_STAGE : _nextStageId.getBytes(StandardCharsets.UTF_8);
        // .. serialized MatsTrace's meta info:
        SerializedMatsTrace serializedMatsTrace = _parentFactory.getMatsSerializer()
                .serializeMatsTrace(_incomingMatsTrace);
        byte[] serializedMTBytes = serializedMatsTrace.getMatsTraceBytes();
        String serializedMTMeta = serializedMatsTrace.getMeta();

        byte[] b_meta = serializedMTMeta.getBytes(StandardCharsets.UTF_8);
        // .. messageId
        byte[] b_systemMessageId = _systemMessageId.getBytes(StandardCharsets.UTF_8);

        // :: Create the byte array in one go

        // NOTICE: We use 0-delimiting, UTF-8 does not have zeros: https://stackoverflow.com/a/6907327/39334

        // Total length:
        // = 8 for the 2 x FourCC's "MATSjmts"
        int fullStashLength = 8
                // + 1 for the version, '1'
                + 1
                // + 1 for the number of zeros, currently 6.
                + 1
                // + 1 for the 0-delimiter // NOTICE: Can add more future data between n.o.Zeros and this
                // zero-delimiter.
                // + b_endpointId.length
                + 1 + b_endpointId.length
                // + 1 for the 0-delimiter
                // + b_stageId.length
                + 1 + b_stageId.length
                // + 1 for the 0-delimiter
                // + b_nextStageId.length
                + 1 + b_nextStageId.length
                // + 1 for the 0-delimiter
                // + b_meta.length
                + 1 + b_meta.length
                // + 1 for the 0-delimiter
                // + b_systemMessageId.length
                + 1 + b_systemMessageId.length
                // + 1 for the 0-delimiter
                // + length of incoming serialized MatsTrace, _mtSerLength
                + 1 + serializedMTBytes.length;
        byte[] b_fullStash = new byte[fullStashLength];

        // :: Fill the byte array with the stash

        // "MATSjmts":
        // * "MATS" as FourCC/"Magic Number", per spec.
        // * "jmts" for "Jms MatsTrace Serializer": This is the JMS impl of Mats, which employs MatsTraceSerializer.
        b_fullStash[0] = 77; // M
        b_fullStash[1] = 65; // A
        b_fullStash[2] = 84; // T
        b_fullStash[3] = 83; // S
        b_fullStash[4] = 106; // j
        b_fullStash[5] = 109; // m
        b_fullStash[6] = 116; // t
        b_fullStash[7] = 115; // s
        b_fullStash[8] = 1; // Version
        b_fullStash[9] = 6; // Number of zeros - to be able to add stuff later, and have older deserializers handle it.
        // -- NOTICE! There are room to add more stuff here before first 0-byte.

        // ZERO 1: All bytes in new initialized array is 0 already
        // EndpointId:
        int startPos_EndpointId = /* 4CC */ 8 + /* Version */ 1 + /* n.o.Zeros */ 1 + /* 0-delimiter */ 1;
        System.arraycopy(b_endpointId, 0, b_fullStash, startPos_EndpointId, b_endpointId.length);
        // ZERO 2: All bytes in new initialized array is 0 already
        // StageId start pos:
        int /* next field start */ startPos_StageId = /* last field start */ startPos_EndpointId
                + /* last field length */ b_endpointId.length
                + /* 0-delimiter */ 1;
        System.arraycopy(b_stageId, 0, b_fullStash, startPos_StageId, b_stageId.length);
        // ZERO 3: All bytes in new initialized array is 0 already
        // NextStageId start pos:
        int startPos_NextStageId = startPos_StageId + b_stageId.length + 1;
        System.arraycopy(b_nextStageId, 0, b_fullStash, startPos_NextStageId, b_nextStageId.length);
        // ZERO 4: All bytes in new initialized array is 0 already
        // MatsTrace Meta start pos:
        int startPos_Meta = startPos_NextStageId + b_nextStageId.length + 1;
        System.arraycopy(b_meta, 0, b_fullStash, startPos_Meta, b_meta.length);
        // ZERO 5: All bytes in new initialized array is 0 already
        // MessageId start pos:
        int startPos_MessageId = startPos_Meta + b_meta.length + 1;
        System.arraycopy(b_systemMessageId, 0, b_fullStash, startPos_MessageId, b_systemMessageId.length);
        // ZERO 6: All bytes in new initialized array is 0 already
        // Actual Serialized MatsTrace start pos:
        int startPos_MatsTrace = startPos_MessageId + b_systemMessageId.length + 1;
        System.arraycopy(serializedMTBytes, 0,
                b_fullStash, startPos_MatsTrace, serializedMTBytes.length);

        double millisSerializing = (System.nanoTime() - nanosStart) / 1_000_000d;

        log.info(LOG_PREFIX + "Stashed Mats flow, stash:[" + b_fullStash.length + " B], serializing took:["
                + millisSerializing + " ms].");

        return b_fullStash;
    }

    @Override
    public <T> T getTraceProperty(String propertyName, Class<T> clazz) {
        Z value = _incomingMatsTrace.getTraceProperty(propertyName);
        if (value == null) {
            return null;
        }
        return _parentFactory.getMatsSerializer().deserializeObject(value, clazz);
    }

    private static final String REPLY_TO_VOID = "REPLY_TO_VOID_NO_MESSAGE_SENT";

    private static class IllegalCallFlowsException extends IllegalStateException {
        public IllegalCallFlowsException(String message) {
            super(message);
        }
    }

    private static class DebugStackTraceException extends Exception {
        public DebugStackTraceException(String message) {
            super(message);
        }
    }

    @Override
    public MessageReference reply(Object replyDto) {
        long nanosStart = System.nanoTime();

        // ?: Have reply, next or goto already been invoked?
        if (_replyOrNextOrGotoSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Reply, Next or Goto has already been invoked! More than once is not allowed!";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REPLY/NEXT/GOTO DEBUG STACKTRACE:", _replyOrNextOrGotoSent);
            log.error(LOG_PREFIX + "   THIS REPLY DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS REPLY STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // ?: Have requests already been invoked?
        if (_requestsSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Request or Next has already been invoked! It is illegal to mix Reply with Request.";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REQUEST DEBUG STACKTRACE:", _requestsSent);
            log.error(LOG_PREFIX + "   THIS REPLY DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS REPLY STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // Reply is now performed.
        _replyOrNextOrGotoSent = new DebugStackTraceException("PREVIOUS REPLY STACKTRACE");

        // :: Short-circuit the reply (to no-op) if there is nothing on the stack to reply to.
        List<Channel> stack = _incomingMatsTrace.getCurrentCall().getReplyStack();
        if (stack.size() == 0) {
            // This is OK, it is just like a normal java call where you do not use the return value, e.g. map.put(k, v).
            // It happens if you use "send" (aka "fire-and-forget") to an endpoint which has reply-semantics, which
            // is legal.
            log.info(LOG_PREFIX + "Stage [" + _stageId + "] invoked context.reply(..), but there are no elements"
                    + " on the stack, hence no one to reply to, ignoring.");
            return new MessageReferenceImpl(REPLY_TO_VOID);
        }

        // :: Create next MatsTrace
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        // Note that serialization must be performed at invocation time, to preserve contract with API.
        Z replyZ = matsSerializer.serializeObject(replyDto);
        MatsTrace<Z> replyMatsTrace = _incomingMatsTrace.addReplyCall(_stageId, replyZ);

        String matsMessageId = produceMessage(replyDto, null, null, nanosStart, replyMatsTrace);

        return new MessageReferenceImpl(matsMessageId);
    }

    @Override
    public MessageReference request(String endpointId, Object requestDto) {
        long nanosStart = System.nanoTime();
        // :: Assert that we have a next-stage
        if (_nextStageId == null) {
            throw new IllegalStateException("Stage [" + _stageId
                    + "] invoked context.request(..), but there is no next stage to reply to."
                    + " Use context.initiate(..send()..) if you want to 'invoke' the endpoint w/o req/rep semantics.");
        }

        // ?: Have reply, next or goto already been invoked?
        if (_replyOrNextOrGotoSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Reply, Next or Goto has already been invoked! More than once is not allowed!";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REPLY/NEXT/GOTO DEBUG STACKTRACE:", _replyOrNextOrGotoSent);
            log.error(LOG_PREFIX + "   THIS REQUEST DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS REQUEST STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // NOTE! IT IS LEGAL TO SEND MULTIPLE REQUEST/NEXT MESSAGES!
        // Request is now performed.
        _requestsSent = new DebugStackTraceException("PREVIOUS REQUEST STACKTRACE");

        // :: Create next MatsTrace
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        // Note that serialization must be performed at invocation time, to preserve contract with API.
        Z requestZ = matsSerializer.serializeObject(requestDto);
        Z stateZ = matsSerializer.serializeObject(_incomingAndOutgoingState);
        MatsTrace<Z> requestMatsTrace = _incomingMatsTrace.addRequestCall(_stageId,
                endpointId, MessagingModel.QUEUE, _nextStageId, MessagingModel.QUEUE, requestZ, stateZ, null);

        String matsMessageId = produceMessage(requestDto, _incomingAndOutgoingState, null, nanosStart, requestMatsTrace);

        return new MessageReferenceImpl(matsMessageId);
    }

    @Override
    public MessageReference next(Object nextDto) {
        long nanosStart = System.nanoTime();
        // :: Assert that we have a next-stage
        if (_nextStageId == null) {
            throw new IllegalStateException("Stage [" + _stageId
                    + "] invoked context.next(..), but there is no next stage.");
        }

        // ?: Have reply, next or goto already been invoked?
        if (_replyOrNextOrGotoSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Reply, Next or Goto has already been invoked! More than once is not allowed!";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REPLY/NEXT/GOTO DEBUG STACKTRACE:", _replyOrNextOrGotoSent);
            log.error(LOG_PREFIX + "   THIS NEXT DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS NEXT STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // ?: Have requests already been invoked?
        if (_requestsSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Request already been invoked! It is illegal to mix Next with Request.";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REQUEST/NEXT DEBUG STACKTRACE:", _requestsSent);
            log.error(LOG_PREFIX + "   THIS NEXT DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS NEXT STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // Next is now performed.
        _replyOrNextOrGotoSent = new DebugStackTraceException("PREVIOUS NEXT STACKTRACE");

        // :: Create next (heh!) MatsTrace
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        // Note that serialization must be performed at invocation time, to preserve contract with API.
        Z nextZ = matsSerializer.serializeObject(nextDto);
        Z stateZ = matsSerializer.serializeObject(_incomingAndOutgoingState);
        MatsTrace<Z> nextMatsTrace = _incomingMatsTrace.addNextCall(_stageId, _nextStageId, nextZ, stateZ);

        String matsMessageId = produceMessage(nextDto, _incomingAndOutgoingState, null, nanosStart, nextMatsTrace);

        return new MessageReferenceImpl(matsMessageId);
    }

    @Override
    public void nextDirect(Object nextDirectDto) {
        // :: Assert that we have a next-stage
        if (_nextStageId == null) {
            throw new IllegalStateException("Stage [" + _stageId
                    + "] invoked context.nextDirect(..), but there is no next stage.");
        }

        // IT IS ILLEGAL TO SEND *ANY OTHER* FLOW MESSAGE ALONG WITH nextDirect(..)

        // ?: Have reply, next or goto already been invoked?
        if (_replyOrNextOrGotoSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Reply, Next or Goto has already been invoked! More than once is not allowed!";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REPLY/NEXT/GOTO DEBUG STACKTRACE:", _replyOrNextOrGotoSent);
            log.error(LOG_PREFIX + "   THIS NEXT_DIRECT DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS NEXT_DIRECT STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // ?: Have requests already been invoked?
        if (_requestsSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Request already been invoked! It is illegal to mix Next_direct with Request.";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REQUEST DEBUG STACKTRACE:", _requestsSent);
            log.error(LOG_PREFIX + "   THIS NEXT_DIRECT DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS NEXT_DIRECT STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // NextDirect is now performed.
        _replyOrNextOrGotoSent = new DebugStackTraceException("PREVIOUS NEXT_DIRECT STACKTRACE");

        // Set nextDirect, which will make the StageProcessor perform the operation.
        _nextDirectInvoked = true;
        // Value of nextDirect, which can be null.
        _nextDirectDto = nextDirectDto;
    }

    boolean isNextDirectInvoked() {
        return _nextDirectInvoked;
    }

    Object getNextDirectDto() {
        return _nextDirectDto;
    }

    @Override
    public MessageReference goTo(String endpointId, Object gotoDto) {
        return goTo(endpointId, gotoDto, null);
    }

    @Override
    public MessageReference goTo(String endpointId, Object gotoDto, Object initialTargetSto) {
        long nanosStart = System.nanoTime();
        if (endpointId == null) {
            throw new IllegalArgumentException("Target endpointId cannot be null.");
        }

        // ?: Have reply or goto already been invoked?
        if (_replyOrNextOrGotoSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Reply, Next or Goto has already been invoked! More than once is not allowed!";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REPLY/NEXT/GOTO DEBUG STACKTRACE:", _replyOrNextOrGotoSent);
            log.error(LOG_PREFIX + "   THIS GOTO DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS GOTO STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // ?: Have request or next already been invoked?
        if (_requestsSent != null) {
            // -> Yes, and this is not legal.
            String msg = "Request has already been invoked! It is illegal to mix Goto with Requests.";
            log.error(LOG_PREFIX + ILLEGAL_CALL_FLOWS + msg);
            log.error(LOG_PREFIX + "   PREVIOUS REQUEST DEBUG STACKTRACE:", _requestsSent);
            log.error(LOG_PREFIX + "   THIS GOTO DEBUG STACKTRACE:",
                    new DebugStackTraceException("THIS GOTO STACKTRACE"));
            throw new IllegalCallFlowsException(msg);
        }
        // Goto is now performed.
        _replyOrNextOrGotoSent = new DebugStackTraceException("PREVIOUS GOTO STACKTRACE");

        // :: Create next MatsTrace
        MatsSerializer<Z> matsSerializer = _parentFactory.getMatsSerializer();
        // Note that serialization must be performed at invocation time, to preserve contract with API.
        Z passZ = matsSerializer.serializeObject(gotoDto);
        Z initialTargetStateZ = matsSerializer.serializeObject(initialTargetSto);
        MatsTrace<Z> passMatsTrace = _incomingMatsTrace.addGotoCall(_stageId, endpointId, passZ, initialTargetStateZ);

        String matsMessageId = produceMessage(gotoDto, initialTargetSto, initialTargetSto, nanosStart, passMatsTrace);

        return new MessageReferenceImpl(matsMessageId);
    }

    private String produceMessage(Object incomingDto, Object sameStackHeightState, Object initialTargetSto,
                                  long nanosStart, MatsTrace<Z> outgoingMatsTrace) {
        String debugInfo;
        // ?: Is this MINIMAL MatsTrace
        if (outgoingMatsTrace.getKeepTrace() == KeepMatsTrace.MINIMAL) {
            // -> Yes, MINIMAL, so do not include rather verbose debugInfo
            debugInfo = null;
        }
        else {
            // -> No, not MINIMAL, so include debug info
            String invocationPoint = getInvocationPoint();
            // If we got no result from getInvocationPoint(), replace with info from '_stageOrigin'.
            debugInfo = (NO_INVOCATION_POINT.equals(invocationPoint)
                    ? "setup@" + _stageOrigin
                    : "invoked@" + invocationPoint);
        }

        Call<Z> currentCall = outgoingMatsTrace.getCurrentCall();
        currentCall.setDebugInfo(_parentFactory.getFactoryConfig().getAppName(),
                _parentFactory.getFactoryConfig().getAppVersion(),
                _parentFactory.getFactoryConfig().getNodename(), debugInfo);

        // Produce the JmsMatsMessage to send
        JmsMatsMessage<Z> msg = JmsMatsMessage.produceMessage(DispatchType.STAGE, nanosStart,
                _parentFactory.getMatsSerializer(), outgoingMatsTrace,
                incomingDto, initialTargetSto, sameStackHeightState,
                _outgoingProps, _outgoingBinaries, _outgoingStrings);
        _messagesToSend.add(msg);

        // Clear all outgoingProps, outgoingBinaries and outgoingStrings, for any new request(..) or send(..)
        // (Clearing, but copied off by the produceMessage(..) call)
        _outgoingProps.clear();
        _outgoingBinaries.clear();
        _outgoingStrings.clear();
        return currentCall.getMatsMessageId();
    }

    @Override
    public void initiate(InitiateLambda lambda) {
        // :: Store the existing TraceId, since it should hopefully be set (extended) in the initiate.
        String existingTraceId = MDC.get(MDC_TRACE_ID);

        try {
            // :: Do the actual initiation
            // 2022-10-20: Replaced actual "stage initiate" with getDefaultInitiator(), as that when directly called
            // is equivalent, but if nesting is in effect, defaultInitiator handles this.
            _parentFactory.getDefaultInitiator().initiateUnchecked(lambda);
        }
        finally {
            // :: Put back the previous TraceId.
            MDC.put(MDC_TRACE_ID, existingTraceId);
        }
    }

    @Override
    public void doAfterCommit(Runnable runnable) {
        _doAfterCommitRunnableHolder.setDoAfterCommit(runnable);
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
}
