package io.mats3.serial.impl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;

import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.Call.CallType;
import io.mats3.serial.MatsTrace.Call.Channel;
import io.mats3.serial.MatsTrace.Call.MessagingModel;

/**
 * An implementation of {@link MatsTrace} which uses fields to hold all state necessary for a Mats flow, including
 * "holders" for the serialization of DTOs and STOs, with type 'Z'. It is meant to be "field-serialized", thus the field
 * names are short. The most relevant types of Z are String and byte[], using e.g. JSON or Smile for serializing the
 * DTOs and STOs payloads, but it might be relevant to use a container/holder type too. You should create an extension
 * of this class to set the type. In 'mats-serial-json', a <code>MatsTraceStringImpl</code> variant exists.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class MatsTraceFieldImpl<Z> implements MatsTrace<Z>, Cloneable {

    private final String id; // "Flow Id", system-def Id for this call flow (as oppose to traceId, which is user def.)
    private final String tid; // TraceId, user-def Id for this call flow.

    private long ts; // Initialized @ TimeStamp (Java epoch). Not final due to legacy withDebugInfo(..)

    private final KeepMatsTrace kt; // KeepMatsTrace.
    private final Boolean np; // NonPersistent.
    private final Boolean ia; // Interactive.
    private final Long tl; // Time-To-Live, null if 0, where 0 means "forever".
    private final Boolean na; // NoAudit.

    private Long tidh; // For future OpenTracing support: 16-byte TraceId HIGH
    private Long tidl; // For future OpenTracing support: 16-byte TraceId LOW
    private Long sid; // For future OpenTracing support: Override SpanId for root
    private Long pid; // For future OpenTracing support: ParentId (note: "ChildOf" in spec)
    private Byte f; // For future OpenTracing support: Flags
    private long[] sids; // Open tracing SpanId stack. Not currently used, might be a better implementation.

    private int d; // For future Debug options, issue #79

    private String an; // Initializing AppName
    private String av; // Initializing AppVersion
    private String h; // Initializing Host/Node
    private String iid; // Initiator Id, "from" on initiation
    private String x; // Debug info (free-form..)

    private String auth; // For future Auth support: Initializing Authorization header, e.g. "Bearer: ....".

    private String sig; // For future Signature support: Signature of central pieces of information in the trace.
    // Note regarding signature: This is meant for the initial elements of the trace, kept in the trace.
    // The entire message is also signed, and the signature is kept in byte-sideloads.

    private int cn; // Call Number. Not final due to clone-impl.
    private int tcn; // For "StackOverflow" detector: "Total Call Number", does not reset when initiation within stage.
    private String pmid; // If initiated within a flow (stage): Parent MatsMessageId.

    private List<CallImpl<Z>> c = new ArrayList<>(); // Calls, "Call Flow". Not final due to clone-impl.
    private List<StackStateImpl<Z>> ss = new ArrayList<>(); // StackStates, "State Flow". Not final due to clone-impl.
    private Map<String, Z> tp = new LinkedHashMap<>(); // TraceProps. Not final due to clone-impl.

    /**
     * Creates a new {@link MatsTrace}. Must add a {@link Call} before sending.
     *
     * @param traceId
     *            the user-defined hopefully-unique id for this call flow.
     * @param flowId
     *            the system-defined pretty-much-(for <i>all</i> purposes)-guaranteed-unique id for this call flow.
     * @param keepMatsTrace
     *            the level of "trace keeping".
     * @param nonPersistent
     *            if the messages in this flow should be non-persistent
     * @param interactive
     *            if the messages in this flow is of "interactive" priority.
     * @param ttlMillis
     *            the number of milliseconds the message should live before being time out. 0 means "forever", and is
     *            the default.
     * @param noAudit
     *            hint to the underlying implementation, or to any monitoring/auditing tooling on the Message Broker,
     *            that it does not make much value in auditing this message flow, typically because it is just a
     *            "getter" of information to show to some user, or a health-check validating that some service is up and
     *            answers in a timely fashion.
     * @return the newly created {@link MatsTrace}.
     */
    public static <Z> MatsTrace<Z> createNew(String traceId, String flowId,
            KeepMatsTrace keepMatsTrace, boolean nonPersistent, boolean interactive, long ttlMillis, boolean noAudit) {
        return new MatsTraceFieldImpl<Z>(traceId, flowId, keepMatsTrace, nonPersistent, interactive, ttlMillis,
                noAudit);
    }

    @Override
    public MatsTrace<Z> withDebugInfo(String initializingAppName, String initializingAppVersion,
            String initializingHost, String initiatorId, String debugInfo) {
        an = initializingAppName;
        av = initializingAppVersion;
        h = initializingHost;
        iid = initiatorId;
        x = debugInfo;
        return this;
    }

    @Override
    public MatsTrace<Z> withChildFlow(String parentMatsMessageId, int totalCallNumber) {
        pmid = parentMatsMessageId;
        tcn = totalCallNumber;
        return this;
    }

    // TODO: POTENTIAL withOpenTracingTraceId() and withOpenTracingSpanId()..

    // Jackson JSON-lib needs a no-args constructor, but it can re-set finals.
    protected MatsTraceFieldImpl() {
        // REMEMBER: These will be set by the deserialization mechanism.
        tid = null;
        id = null;

        kt = null;
        np = null;
        ia = null;
        tl = null;
        na = null;
    }

    protected MatsTraceFieldImpl(String traceId, String flowId, KeepMatsTrace keepMatsTrace, boolean nonPersistent,
            boolean interactive, long ttlMillis, boolean noAudit) {
        this.tid = traceId;
        this.id = flowId;
        // This should really have been provided by user, when the initiation was /started/.
        overrideInitializationTimestamp(System.currentTimeMillis());
        this.kt = keepMatsTrace;
        this.np = nonPersistent ? Boolean.TRUE : null;
        this.ia = interactive ? Boolean.TRUE : null;
        this.tl = ttlMillis > 0 ? ttlMillis : null;
        this.na = noAudit ? Boolean.TRUE : null;
        this.cn = 0;
        this.tcn = 0;
    }

    /**
     * <b>NOTICE! This is NOT meant for public usage!</b>
     */
    public void overrideInitializationTimestamp(long timestamp) {
        this.ts = timestamp;
        // Set the initialization timestamp as "endpoint entered", so that if the init is a REQUEST, you can get
        // the "total endpoint time" on the terminator, as init-to-terminator.
        // The 'eets' (and 'ots') uses diff-from-initialization.
        this.eets = new long[] { 0 };
    }

    // == NOTICE == Serialization and deserialization is an implementation specific feature.

    @Override
    public String getTraceId() {
        return tid;
    }

    @Override
    public String getFlowId() {
        return id;
    }

    @Override
    public long getInitializedTimestamp() {
        return ts;
    }

    @Override
    public KeepMatsTrace getKeepTrace() {
        return kt;
    }

    @Override
    public boolean isNonPersistent() {
        return np == null ? Boolean.FALSE : np;
    }

    @Override
    public boolean isInteractive() {
        return ia == null ? Boolean.FALSE : ia;
    }

    @Override
    public long getTimeToLive() {
        return tl != null ? tl : 0;
    }

    @Override
    public boolean isNoAudit() {
        return na == null ? Boolean.FALSE : na;
    }

    @Override
    public String getInitializingAppName() {
        return an == null ? NULLED : an;
    }

    @Override
    public String getInitializingAppVersion() {
        return av == null ? NULLED : av;
    }

    @Override
    public String getInitializingHost() {
        return h == null ? NULLED : h;
    }

    @Override
    public String getInitiatorId() {
        return iid == null ? NULLED : iid;
    }

    @Override
    public String getDebugInfo() {
        return x;
    }

    @Override
    public int getCallNumber() {
        return cn;
    }

    @Override
    public int getTotalCallNumber() {
        return tcn;
    }

    @Override
    public String getParentMatsMessageId() {
        return pmid;
    }

    @Override
    public void setTraceProperty(String propertyName, Z propertyValue) {
        tp.put(propertyName, propertyValue);
    }

    @Override
    public Z getTraceProperty(String propertyName) {
        return tp.get(propertyName);
    }

    @Override
    public Set<String> getTracePropertyKeys() {
        return tp.keySet();
    }

    @Override
    public MatsTraceFieldImpl<Z> addRequestCall(String from,
            String to, MessagingModel toMessagingModel,
            String replyTo, MessagingModel replyToMessagingModel,
            Z data, Z replyState, Z initialState) {
        // Get copy of current stack. We're going to add a stack frame to it.
        List<ReplyChannel> newCallReplyStack = getCopyOfCurrentStackForNewCall();
        // Clone the current MatsTrace, which is the one we're going to modify and return.
        MatsTraceFieldImpl<Z> clone = cloneForNewCall();
        // :: Add the replyState - i.e. the state that is outgoing from the current stage, destined for the REPLY.
        // NOTE: This must be added BEFORE we add to the newCallReplyStack, since it is targeted to the stack frame
        // below this new Request stack frame!
        StackStateImpl<Z> newState = new StackStateImpl<Z>(newCallReplyStack.size(), replyState);
        // NOTE: Extra-state that was added from a previous message passing must be kept. We must thus copy that.
        // Get current StackStateImpl - before adding new to reply stack. CAN BE NULL, both if initial stage, or no
        // extra state added yet. Take into account if this the very first call.
        forwardExtraStateIfExist(newState);
        // Actually add the new state
        clone.ss.add(newState);

        // Add the stageId to replyTo to the stack
        newCallReplyStack.add(ReplyChannel.newWithRandomSpanId(replyTo, replyToMessagingModel));
        // Prune the data and stack from current call if KeepMatsTrace says so.
        clone.dropValuesOnCurrentCallIfAny();
        // Add the new Call
        clone.c.add(new CallImpl<Z>(CallType.REQUEST, getFlowId(), getInitializedTimestamp(), getCallNumber(), from,
                new ToChannel(to, toMessagingModel), data, newCallReplyStack));
        // Add any state meant for the initial stage ("stage0") of the "to" endpointId.
        if (initialState != null) {
            // The stack is now one height higher, since we added the "replyTo" to it.
            clone.ss.add(new StackStateImpl<Z>(newCallReplyStack.size(), initialState));
        }
        // Prune the StackStates if KeepMatsTrace says so
        clone.pruneUnnecessaryStackStates();
        return clone;
    }

    @Override
    public MatsTraceFieldImpl<Z> addSendCall(String from, String to, MessagingModel toMessagingModel,
            Z data, Z initialState) {
        // Get copy of current stack. NOTE: For a send/next call, the stack does not change.
        List<ReplyChannel> newCallReplyStack = getCopyOfCurrentStackForNewCall();
        // Clone the current MatsTrace, which is the one we're going to modify and return.
        MatsTraceFieldImpl<Z> clone = cloneForNewCall();
        // Prune the data and stack from current call if KeepMatsTrace says so.
        clone.dropValuesOnCurrentCallIfAny();
        // Add the new Call
        clone.c.add(new CallImpl<Z>(CallType.SEND, getFlowId(), getInitializedTimestamp(), getCallNumber(), from,
                new ToChannel(to, toMessagingModel), data, newCallReplyStack));
        // Add any state meant for the initial stage ("stage0") of the "to" endpointId.
        if (initialState != null) {
            clone.ss.add(new StackStateImpl<Z>(newCallReplyStack.size(), initialState));
        }
        // Prune the StackStates if KeepMatsTrace says so.
        clone.pruneUnnecessaryStackStates();
        return clone;
    }

    @Override
    public MatsTraceFieldImpl<Z> addNextCall(String from, String to, Z data, Z state) {
        if (state == null) {
            throw new IllegalStateException("When adding next-call, state-data string should not be null.");
        }
        // Get copy of current stack. NOTE: For a send/next call, the stack does not change.
        List<ReplyChannel> newCallReplyStack = getCopyOfCurrentStackForNewCall();
        // Clone the current MatsTrace, which is the one we're going to modify and return.
        MatsTraceFieldImpl<Z> clone = cloneForNewCall();
        // Prune the data and stack from current call if KeepMatsTrace says so.
        clone.dropValuesOnCurrentCallIfAny();
        // Add the new Call.
        clone.c.add(new CallImpl<Z>(CallType.NEXT, getFlowId(), getInitializedTimestamp(), getCallNumber(), from,
                new ToChannel(to, MessagingModel.QUEUE), data, newCallReplyStack));
        // Add the state meant for the next stage (Notice again that we do not change the reply stack here)
        StackStateImpl<Z> newState = new StackStateImpl<Z>(newCallReplyStack.size(), state);
        // NOTE: Extra-state that was added from a previous message passing must be kept. We must thus copy that.
        forwardExtraStateIfExist(newState);
        // Actually add the new state
        clone.ss.add(newState);
        // Prune the StackStates if KeepMatsTrace says so.
        clone.pruneUnnecessaryStackStates();
        return clone;
    }

    @Override
    public MatsTraceFieldImpl<Z> addReplyCall(String from, Z data) {
        // Get copy of current stack. We're going to pop a stack frame of it.
        List<ReplyChannel> newCallReplyStack = getCopyOfCurrentStackForNewCall();
        // ?: Do we actually have anything to pop?
        if (newCallReplyStack.size() == 0) {
            // -> No stack: Illegal - you shouldn't be making a REPLY call if there is nothing to reply to.
            throw new IllegalStateException("Trying to add Reply Call when there is no stack."
                    + " (Implementation note: You need to check the getCurrentCall().getStackHeight() before trying to"
                    + " do a reply - if it is zero, then just drop the reply instead.)");
        }
        // Clone the current MatsTrace, which is the one we're going to modify and return.
        MatsTraceFieldImpl<Z> clone = cloneForNewCall();
        // Prune the data and stack from current call if KeepMatsTrace says so.
        clone.dropValuesOnCurrentCallIfAny();
        // Pop the last element off the stack, since this is where we'll reply to, and the rest is the new stack.
        ReplyChannel to = newCallReplyStack.remove(newCallReplyStack.size() - 1);
        // Add the new Call, adding the ReplyForSpanId.
        CallImpl<Z> replyCall = new CallImpl<Z>(CallType.REPLY, getFlowId(), getInitializedTimestamp(), getCallNumber(),
                from, new ToChannel(to.i, to.m), data, newCallReplyStack).setReplyForSpanId(getCurrentSpanId());
        clone.c.add(replyCall);
        // Prune the StackStates if KeepMatsTrace says so.
        clone.pruneUnnecessaryStackStates();
        return clone;
    }

    private long[] ots; // "compressed" against the initiation time
    private long[] eets; // "compressed" against the initiation time

    @Override
    public void setOutgoingTimestamp(long timestamp) {
        // NOTE: SENDING SIDE: Invoked when a new message/call has been constructed, about to be sent.
        // NOTE: Shall be invoked AFTER having added a call for new outgoing message, as late as possible before send.

        // :: Set on the outgoing call
        CallImpl<Z> cc = getCurrentCall();
        cc.setCalledTimestamp(timestamp);

        // :: Handle the "outgoing timestamp stack"
        // NOTE: This enables the "time between stages" calculation: From send, to receive /on same stack height/.

        int stackHeight = cc.getReplyStackHeight();

        // ?: Is this a REPLY?
        if (cc.t == CallType.REPLY) {
            // -> Yes, REPLY: Then we should not set a new timestamp, but crop off the existing height
            // If we /were/ at stack height 2, then there /was/ 3 timestamps (height 0, 1, 2).
            // The current height (due to REPLY) is now 1, so now there should be 2 timestamps (height 0, 1)
            // Copy the current, cropping if necessary, extending if necessary. Must handle null due to old impls.
            this.ots = this.ots == null ? new long[stackHeight + 1] : Arrays.copyOf(this.ots, stackHeight + 1);
            return;
        }

        // Find difference between initiation and timestamp
        long diff = (timestamp - getInitializedTimestamp());

        // ?: Is this a REQUEST?
        if (cc.t == CallType.REQUEST) {
            // -> Yes, REQUEST. Then we should set our timestamp on the stackheight /below/ this call.
            // If we /were/ at stack height 1, then there /was/ 2 timestamps (height 0, 1).
            // The current height (due to REQUEST) is now 2, but we should leave our timestamp at height 1.
            // Copy the current, cropping if necessary, extending if necessary. Must handle null due to old impls.
            this.ots = this.ots == null ? new long[stackHeight] : Arrays.copyOf(this.ots, stackHeight);
            this.ots[stackHeight - 1] = diff;
            return;
        }

        // E-> This is a SEND or PUBLISH (initiations), or NEXT or GOTO (flows)

        // If we /were/ at stack height 1, then there /was/ 2 timestamps (height 0, 1).
        // The current height is still now 1, and there is still 2 timestamps (height 0, 1),
        // and we should leave our timestamp at height 1.
        // Copy the current, cropping if necessary, extending if necessary. Must handle null due to old impls.
        this.ots = this.ots == null ? new long[stackHeight + 1] : Arrays.copyOf(this.ots, stackHeight + 1);
        this.ots[stackHeight] = diff;
    }

    @Override
    public long getSameHeightOutgoingTimestamp() {
        // NOTE: RECEIVING SIDE: Invoked when a message has been received
        CallImpl<Z> cc = getCurrentCall();
        if (cc.t == CallType.REQUEST) {
            // Should really throw, as this makes no sense. Returning -1 instead, hope caller realizes his mistake.
            return -1;
        }
        // E-> Not REQUEST

        // For all types (outside of the REQUEST handled above), we should use the current stack height as the index of
        // where to find the "same height outgoing timestamp" that was set on the sender.

        int stackHeight = getCurrentCall().getReplyStackHeight();
        // Must handle null due to old impls.
        return this.ots == null ? 0 : this.ots[stackHeight] + getInitializedTimestamp();
    }

    @Override
    public void setStageEnteredTimestamp(long timestamp) {
        // NOTE: RECEIVING SIDE
        // NOTE: Shall be invoked RIGHT WHEN RECEIVING a message on a stage

        // :: Calculating EndpointEntered time, thus only when being received on the INITIAL stage

        CallImpl<Z> cc = getCurrentCall();

        // ?: Is this a NEXT?
        if (cc.t == CallType.NEXT) {
            // -> Yes NEXT. This is per definition not an *initial* stage.
            // The endpoint-entered time stays the same - thus not touching the endpoint-entered stack.
            return;
        }

        // ?: Is this a GOTO?
        if (cc.t == CallType.GOTO) {
            // -> Yes GOTO. This will be a new initial stage, but since it is a GOTO, it should not reset the
            // endpoint-entered timestamp - thus not touching the endpoint-entered stack.
            return;
        }

        int stackHeight = cc.getReplyStackHeight();

        // ?: Is this a REPLY?
        if (cc.t == CallType.REPLY) {
            // -> Yes, REPLY. This cannot per definition be an *initial* stage.
            // The endpoint-entered stack was cropped when the message was sent, so not touching it.
            return;
        }

        // E-> This is a SEND or PUBLISH (initiation), or a REQUEST (flow)

        // Find difference between initiation and timestamp
        long diff = (timestamp - getInitializedTimestamp());

        // If we are at stack height 1, then there are 2 timestamps in play (height 0, 1).
        // We're the one at stack height 1, so setting our timestamp there
        // Copy the current, cropping if necessary, extending if necessary. Must handle null due to old impls.
        this.eets = this.eets == null ? new long[stackHeight + 1] : Arrays.copyOf(this.eets, stackHeight + 1);
        this.eets[stackHeight] = diff;
    }

    @Override
    public long getSameHeightEndpointEnteredTimestamp() {
        // NOTE: RECEIVING/PROCESSING SIDE
        // NOTE: Shall be invoked on the stage about to either stop flow, or send REPLY.
        // NOTE: BEFORE adding a new call! (Obvious in case of "stopping" a flow, i.e. not sending a new message)

        // E-> This is a SEND or PUBLISH (initiation), or a REQUEST (flow)
        int stackHeight = getCurrentCall().getReplyStackHeight();

        // If we are at stack height 1, then there are 2 timestamps in play (height 0, 1).
        // We want the one at stack height 1.
        // Must handle null due to old impls.
        return this.eets == null ? 0 : this.eets[stackHeight] + getInitializedTimestamp();
    }

    private void forwardExtraStateIfExist(StackStateImpl<Z> newState) {
        // For REQUEST, it might be the very first call in a mats flow - in which case there obviously aren't any
        // current state and extra state yet.
        StackStateImpl<Z> currentState = c.isEmpty()
                ? null
                : getState(getCurrentCall().getReplyStackHeight());
        // ?: Do we have a current state, and does that have extra-state?
        if ((currentState != null) && (currentState.es != null)) {
            // -> Yes, there was extra state on the current stage
            // Copy it, and new StackState for the REPLY to the REQUEST we're currently adding.
            newState.es = new HashMap<>(currentState.es);
        }
    }

    /**
     * @return a COPY of the current stack.
     */
    private List<ReplyChannel> getCopyOfCurrentStackForNewCall() {
        if (c.isEmpty()) {
            return new ArrayList<>();
        }
        return getCurrentCall().getReplyStack_internal(); // This is a copy.
    }

    /**
     * Should be invoked just after adding a new Call, so if in non-FULL mode (COMPACT or MINIMAL), we can clean out any
     * stack states that either are higher than we're at now, or multiples for the same height (only the most recent for
     * each stack height is actually a part of the stack, the rest on the same level are for history).
     */
    private void pruneUnnecessaryStackStates() {
        // ?: Are we in MINIMAL or COMPACT modes?
        if ((kt == KeepMatsTrace.MINIMAL) || (kt == KeepMatsTrace.COMPACT)) {
            // -> Yes, so we'll drop the states we can.
            ss = getStateStack_internal();
        }
    }

    // TODO: POTENTIAL setSpanIdOnCurrentStack(..)

    @Override
    public long getCurrentSpanId() {
        // ?: Do we have a CurrentCall?
        CallImpl<Z> currentCall = getCurrentCall();
        if (currentCall == null) {
            // -> No, so then we derive the SpanId from the FlowId
            return getRootSpanId();
        }
        // E-> Yes, we have a CurrentCall
        List<ReplyChannel> stack = currentCall.s;
        // ?: Is there any stack?
        if (stack.isEmpty()) {
            // -> No, no stack, so we're at initiator/terminator level - again derive SpanId from FlowId
            return getRootSpanId();
        }
        // E-> Yes, we have a CurrentCall with a Stack > 0 elements.
        return stack.get(stack.size() - 1).getSpanId();
    }

    private long getRootSpanId() {
        return sid != null ? sid : fnv1a_64(getFlowId().getBytes(StandardCharsets.UTF_8));
    }

    private List<Long> getSpanIdStack() {
        ArrayList<Long> spanIds = new ArrayList<>();
        spanIds.add(getRootSpanId());
        CallImpl<Z> currentCall = getCurrentCall();
        // ?: Did we have a CurrentCall?
        if (currentCall != null) {
            // -> We have a CurrentCall, add the stack of SpanIds.
            for (ReplyChannel cws : currentCall.s) {
                spanIds.add(cws.sid);
            }
        }
        return spanIds;
    }

    private static final long FNV1A_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV1A_64_PRIME = 0x100000001b3L;

    /**
     * Fowler–Noll–Vo hash function, https://en.wikipedia.org/wiki/Fowler%E2%80%93Noll%E2%80%93Vo_hash_function
     */
    private static long fnv1a_64(final byte[] k) {
        long rv = FNV1A_64_OFFSET_BASIS;
        for (byte b : k) {
            rv ^= b;
            rv *= FNV1A_64_PRIME;
        }
        return rv;
    }

    /**
     * Should be invoked just before adding the new call to the cloneForNewCall()'ed MatsTrace, so as to clean out the
     * 'from' and Stack (and data if COMPACT) on the CurrentCall which after the add will become the <i>previous</i>
     * call.
     */
    private void dropValuesOnCurrentCallIfAny() {
        if (c.size() > 0) {
            getCurrentCall().dropFromAndStack();
            // ?: Are we on COMPACT mode? (Note that this is implicitly also done for MINIMAL - in cloneForNewCall() -
            // since all calls are dropped in MINIMAL!)
            if (kt == KeepMatsTrace.COMPACT) {
                // -> Yes, COMPACT, so drop data
                getCurrentCall().dropData();
            }
        }
    }

    @Override
    public CallImpl<Z> getCurrentCall() {
        // ?: No calls?
        if (c.size() == 0) {
            // -> No calls, so throw
            throw new IllegalStateException("No calls added - this is evidently a newly created MatsTrace,"
                    + " which isn't meaningful before an initial call is added");
        }
        // Return last element
        return c.get(c.size() - 1);
    }

    @Override
    public List<Call<Z>> getCallFlow() {
        return new ArrayList<>(c);
    }

    @Override
    public Optional<StackState<Z>> getCurrentState() {
        return Optional.ofNullable(getState(getCurrentCall().getReplyStackHeight()));
    }

    @Override
    public List<StackState<Z>> getStateFlow() {
        return new ArrayList<>(ss);
    }

    @Override
    public List<StackState<Z>> getStateStack() {
        // heavy-handed hack to get this to conform to the return type.
        @SuppressWarnings({ "unchecked", "rawtypes" })
        List<StackState<Z>> ret = (List<StackState<Z>>) (List) getStateStack_internal();
        return ret;
    }

    public List<StackStateImpl<Z>> getStateStack_internal() {
        if (ss.isEmpty()) {
            return new ArrayList<>();
        }
        // Current stack height - stack height is the /position/, not the /size()/.
        int currentCallStackHeight = getCurrentCall().getReplyStackHeight();
        // We want the lowest stack height between the currentCall's stack height, and what is at the last
        // element of the stack (there might not be a StateState for the /current/ stack height yet, only lower,
        // which will happen on every REQUEST that does not have "initial incoming state").
        int topOfStateStack = Math.min(currentCallStackHeight, ss.get(ss.size() - 1).getHeight());
        // Create the return StateStack.
        // Note: the stack height is the /position/, not the /size()/, thus +1 for capacity.
        ArrayList<StackStateImpl<Z>> newStateStack = new ArrayList<>(topOfStateStack + 1);
        // Ensure all positions exist, since we will be traversing backwards when adding
        for (int i = 0; i <= topOfStateStack; i++) {
            newStateStack.add(null);
        }
        // Traverse all the StackStates, keeping the /last/ State at each level.
        for (StackStateImpl<Z> stackState : ss) {
            // ?: Is this a State for a stack frame that is /higher/ than we current are on?
            // (Remember the "stack flow", and when we're "going back down" in the stack)
            if (stackState.getHeight() > topOfStateStack) {
                // -> Yes, so we'll not use that
                continue;
            }
            // NOTE: Overwrite! In a FULL stack flow, there might be multiple states for the same stack height.
            // We want the latest for each stack height.
            newStateStack.set(stackState.getHeight(), stackState);
        }
        return newStateStack;
    }

    /**
     * Searches in the stack-list from the back (most recent) for the first element that is of the specified stackDepth.
     * If a more shallow stackDepth than the specified is encountered, or the list is exhausted without the stackDepth
     * being found, the search is terminated with null.
     *
     * @param stackDepth
     *            the stack depth to find stack state for - it should be the size of the stack below you. For e.g. a
     *            Terminator, it is 0. The first request adds a stack level, so it resides at stackDepth 1. Etc.
     * @return the state StackStateImpl if found, <code>null</code> otherwise (as is typical when entering "stage0").
     */
    private StackStateImpl<Z> getState(int stackDepth) {
        for (int i = ss.size() - 1; i >= 0; i--) {
            StackStateImpl<Z> stackState = ss.get(i);
            // ?: Have we reached a lower depth than ourselves?
            if (stackDepth > stackState.getHeight()) {
                // -> Yes, we're at a lower depth: The rest can not possibly be meant for us.
                break;
            }
            if (stackDepth == stackState.getHeight()) {
                return stackState;
            }
        }
        // Did not find any stack state for us.
        return null;
    }

    /**
     * Takes into account the KeepMatsTrace value.
     */
    protected MatsTraceFieldImpl<Z> cloneForNewCall() {
        try {
            @SuppressWarnings("unchecked")
            MatsTraceFieldImpl<Z> cloned = (MatsTraceFieldImpl<Z>) super.clone();
            // Calls are not immutable (a Call's stack and data may be nulled due to KeepMatsTrace value)
            // ?: Are we using MINIMAL?
            if (kt == KeepMatsTrace.MINIMAL) {
                // -> Yes, MINIMAL, so we will literally just have the sole "NewCall" in the trace.
                cloned.c = new ArrayList<>(1);
            }
            else {
                // -> No, not MINIMAL (i.e. FULL or COMPACT), so clone up the Calls.
                cloned.c = new ArrayList<>(c.size());
                // Clone all the calls.
                for (CallImpl<Z> call : c) {
                    cloned.c.add(call.clone());
                }
            }
            // StackStates are mutable (the extra-state)
            cloned.ss = new ArrayList<>(ss.size());
            for (StackStateImpl<Z> stateState : ss) {
                cloned.ss.add(stateState.clone());
            }

            // TraceProps are immutable.
            cloned.tp = new LinkedHashMap<>(tp);

            // Increase CallNumber
            cloned.cn = this.cn + 1;
            // Increase TotalCallNumber, handling previous versions which didn't handle it.
            // TODO: Remove special handling once all are >=0.17.
            cloned.tcn = Math.max(cloned.cn, this.tcn + 1);
            return cloned;
        }
        catch (CloneNotSupportedException e) {
            throw new AssertionError("Implements Cloneable, so clone() should not throw.", e);
        }
    }

    /**
     * Represents an entry in the {@link MatsTrace}.
     */
    public static class CallImpl<Z> implements Call<Z>, Cloneable {
        private String an; // Calling AppName
        private String av; // Calling AppVersion
        private String h; // Calling Host
        private long ts; // Calling TimeStamp
        private String id; // MatsMessageId.

        private String x; // Debug Info (free-form)

        private final CallType t; // type.
        private String f; // from, may be nulled.
        private final ToChannel to; // to.
        private Z d; // data, may be nulled.
        private List<ReplyChannel> s; // stack of reply channels, may be nulled, in which case 'ss' is set.
        private Integer ss; // stack size if stack is nulled.

        private Long rid; // Reply-From-SpanId

        // Jackson JSON-lib needs a no-args constructor, but it can re-set finals.
        private CallImpl() {
            t = null;
            to = null;
        }

        CallImpl(CallType type, String flowId, long matsTraceCreationMillis, int callNo, String from, ToChannel to,
                Z data, List<ReplyChannel> stack) {
            this.t = type;
            this.f = from;
            this.to = to;
            this.d = data;
            this.s = stack;

            this.ts = System.currentTimeMillis();

            // Since we can have clock skews between servers, and we do not want a "-" in the messageId (due to the
            // double-clickableness mentioned below), we make -10 -> "n10".
            long millisSince = this.ts - matsTraceCreationMillis;
            String millisSinceString = millisSince >= 0 ? Long.toString(millisSince) : "n" + Math.abs(millisSince);
            // A MatsMessageId ends up looking like this: 'm_XBExAa1iioAGFVRk6nR5_Tjzswm4ys_t49_n22'
            // Or for negative millisSince: 'm_XBExAa1iioAGFVRk6nR5_Tjzswm4ys_tn49_n22'
            // NOTICE FEATURE: You can double-click anywhere inside that string, and get the entire id marked! w00t!
            this.id = flowId + "_t" + millisSinceString + "_n" + callNo;
        }

        @Override
        public CallImpl<Z> setDebugInfo(String callingAppName, String callingAppVersion, String callingHost,
                String debugInfo) {
            an = callingAppName;
            av = callingAppVersion;
            h = callingHost;
            x = debugInfo;
            return this;
        }

        @Override
        public CallImpl<Z> setCalledTimestamp(long calledTimestamp) {
            ts = calledTimestamp;
            return this;
        }

        public CallImpl<Z> setReplyForSpanId(long replyForSpanId) {
            rid = replyForSpanId;
            return this;
        }

        /**
         * Nulls the "from" and "stack" fields.
         */
        void dropFromAndStack() {
            f = null;
            ss = s.size();
            s = null;
        }

        /**
         * Nulls the "data" field.
         */
        void dropData() {
            d = null;
        }

        @Override
        public String getCallingAppName() {
            return an;
        }

        @Override
        public String getCallingAppVersion() {
            return av;
        }

        @Override
        public String getCallingHost() {
            return h;
        }

        @Override
        public long getCalledTimestamp() {
            return ts;
        }

        @Override
        public String getMatsMessageId() {
            return id;
        }

        @Override
        public String getDebugInfo() {
            return x;
        }

        @Override
        public CallType getCallType() {
            return t;
        }

        @Override
        public long getReplyFromSpanId() {
            if (getCallType() != CallType.REPLY) {
                throw new IllegalStateException("Type of this call is not REPLY, so you cannot ask for"
                        + " ReplyFromSpanId.");
            }
            // TODO: REMOVE THIS HACK ONCE EVERYONE IS >=0.16
            if (rid == null) {
                return 0;
            }
            return rid;
        }

        @Override
        public String getFrom() {
            if (f == null) {
                return NULLED;
            }
            return f;
        }

        @Override
        public Channel getTo() {
            return to;
        }

        @Override
        public Z getData() {
            return d;
        }

        /**
         * @return a COPY of the stack.
         */
        @Override
        public List<Channel> getReplyStack() {
            // heavy-handed hack to get this to conform to the return type.
            @SuppressWarnings({ "unchecked", "rawtypes" })
            List<Channel> ret = (List<Channel>) (List) getReplyStack_internal();
            return ret;
        }

        /**
         * @return a COPY of the stack.
         */
        List<ReplyChannel> getReplyStack_internal() {
            // ?: Has the stack been nulled (to conserve space) due to not being Current Call?
            if (s == null) {
                // -> Yes, nulled, so return a list of correct size where all elements are the string "-nulled-".
                return new ArrayList<>(Collections.nCopies(getReplyStackHeight(),
                        new ReplyChannel(NULLED, null, 0)));
            }
            // E-> No, not nulled (thus Current Call), so return the stack.
            return new ArrayList<>(s);
        }

        @Override
        public int getReplyStackHeight() {
            return (s != null ? s.size() : ss);
        }

        private String indent() {
            return new String(new char[getReplyStackHeight()]).replace("\0", ": ");
        }

        private String fromStackData(boolean printNullData) {
            return "#from:" + (an != null ? an : "") + (av != null ? "[" + av + "]" : "")
                    + (h != null ? "@" + h : "") + (id != null ? ':' + id : "")
                    + (x != null ? ", debug:" + x : "")
                    + (((d != null) || printNullData) ? ", #data:" + d : "");
        }

        @Override
        public String toString() {
            return indent()
                    + t
                    + (ts != 0 ? " " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                            ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), TimeZone.getDefault().toZoneId())) + " -"
                            : "")
                    + " #to:" + to
                    + ", " + fromStackData(false);
        }

        public String toStringFromMatsTrace(long startTimestamp, int maxStackSize, int maxToStageIdLength,
                boolean printNulLData) {
            String toType = (ts != 0 ? String.format("%4d", (ts - startTimestamp)) + "ms " : " - ") + indent() + t;
            int numMaxIncludingCallType = 14 + maxStackSize * 2;
            int numSpacesTo = Math.max(0, numMaxIncludingCallType - toType.length());
            String toTo = toType + spaces(numSpacesTo) + " #to:" + to;
            int numSpacesStack = Math.max(1, 7 + numMaxIncludingCallType + maxToStageIdLength - toTo.length());
            return toTo + spaces(numSpacesStack) + fromStackData(printNulLData);
        }

        protected CallImpl<Z> clone() {
            try {
                @SuppressWarnings("unchecked")
                CallImpl<Z> cloned = (CallImpl<Z>) super.clone();
                // Channels are immutable.
                cloned.s = (s == null ? null : new ArrayList<>(s));
                return cloned;
            }
            catch (CloneNotSupportedException e) {
                throw new AssertionError("Implements Cloneable, so clone() should not throw.", e);
            }
        }
    }

    private static String spaces(int length) {
        return new String(new char[length]).replace("\0", " ");
    }

    /**
     * The "standard" implementation of Channel, which is internally only used for the "To" aspect of Channel. For the
     * replyStack, the {@link ReplyChannel} is used.
     */
    private static class ToChannel implements Channel {
        private final String i;
        private final MessagingModel m;

        // Jackson JSON-lib needs a no-args constructor, but it can re-set finals.
        private ToChannel() {
            i = null;
            m = null;
        }

        public ToChannel(String i, MessagingModel m) {
            this.i = i;
            this.m = m;
        }

        @Override
        public String getId() {
            return i;
        }

        @Override
        public MessagingModel getMessagingModel() {
            return m;
        }

        @Override
        public String toString() {
            String model;
            switch (m) {
                case QUEUE:
                    model = "Q";
                    break;
                case TOPIC:
                    model = "T";
                    break;
                default:
                    model = m.toString();
            }
            return "[" + model + "]" + i;
        }
    }

    /**
     * Implementation of {@link Channel} used for the reply stack, extending the {@link ToChannel} by adding SpanId,
     * employed for the {@link CallImpl#getReplyStack_internal()}.
     * <p />
     * We're hitching the SpanIds onto the ReplyTo Stack, as they have the same stack semantics. However, do note that
     * the Channel-stack and the SpanId-stack are "offset" wrt. to what they refer to:
     * <ul>
     * <li>ReplyTo Stack: The topmost ChannelWithSpan in the stack is what this CurrentCall <i>shall reply to</i>, if it
     * so desires - i.e. it references the the frame <i>below</i> it in the stack, its <i>parent</i>.</li>
     * <li>SpanId Stack: The topmost ChannelWithSpan in the stack carries the SpanId that <i>this</i> Call processes
     * within - i.e. it refers to <i>this</i> frame in the stack.</li>
     * </ul>
     * However, when correlating with how OpenTracing and friends refer to SpanIds, these are always created by the
     * parent - which is also the case here: When a new REQUEST Call is made, this creates a new SpanId (which is kept
     * with the ReplyChannel that should be replied to) - and then the Call is being sent (inside the MatsTrace). Then,
     * when the REPLY Call is being created from the requested service, this SpanId is propagated back in the Call,
     * accessible via the {@link Call#getReplyFromSpanId()} method. Thus, the SpanId is both created, and then processed
     * again upon receiving the REPLY, by the parent stackframe - and viewed like this, the SpanId ('sid') thus actually
     * resides on the correct stackframe.
     */
    private static class ReplyChannel implements Channel {
        private final String i;
        private final MessagingModel m;
        private final long sid; // SpanId

        // Jackson JSON-lib needs a no-args constructor, but it can re-set finals.
        public ReplyChannel() {
            i = null;
            m = null;
            sid = 0;
        }

        public ReplyChannel(String i, MessagingModel m, long sid) {
            this.i = i;
            this.m = m;
            this.sid = sid;
        }

        public static ReplyChannel newWithRandomSpanId(String i, MessagingModel m) {
            return new ReplyChannel(i, m, ThreadLocalRandom.current().nextLong());
        }

        public long getSpanId() {
            return sid;
        }

        @Override
        public String getId() {
            return i;
        }

        @Override
        public MessagingModel getMessagingModel() {
            return m;
        }
    }

    private static class StackStateImpl<Z> implements StackState<Z>, Cloneable {
        private final int h; // depth.
        private final Z s; // state.

        private Map<String, Z> es; // extraState, map is null until first value present.

        // Jackson JSON-lib needs a no-args constructor, but it can re-set finals.
        private StackStateImpl() {
            h = 0;
            s = null;
        }

        public StackStateImpl(int height, Z state) {
            this.h = height;
            this.s = state;
        }

        public int getHeight() {
            return h;
        }

        public Z getState() {
            return s;
        }

        @Override
        public void setExtraState(String key, Z value) {
            if (es == null) {
                es = new HashMap<>();
            }
            es.put(key, value);
        }

        @Override
        public Z getExtraState(String key) {
            return es != null
                    ? es.get(key)
                    : null;
        }

        @Override
        public String toString() {
            return "height=" + h + ", state=" + s + (es != null ? ", extraState=" + es.toString() : "");
        }

        @Override
        protected StackStateImpl<Z> clone() {
            try {
                @SuppressWarnings("unchecked")
                StackStateImpl<Z> clone = (StackStateImpl<Z>) super.clone();
                if (es != null) {
                    clone.es = new HashMap<>(this.es);
                }
                return clone;
            }
            catch (CloneNotSupportedException e) {
                throw new AssertionError("Implements Cloneable, so shouldn't throw", e);
            }
        }
    }

    /**
     * MatsTraceStringImpl.toString().
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        CallImpl<Z> currentCall = getCurrentCall();

        if (currentCall == null) {
            return "MatsTrace w/o CurrentCall. TraceId:" + tid + ", FlowId:" + id + ".";
        }

        String callType = currentCall.getCallType().toString();
        callType = callType + spaces(8 - callType.length());

        // === HEADER ===
        buf.append("MatsTrace").append('\n')
                .append("  Call Flow / Initiation:").append('\n')
                .append("    Timestamp _________ : ").append(Instant.ofEpochMilli(
                        getInitializedTimestamp()).atZone(ZoneId.systemDefault()).toString()).append('\n')
                .append("    TraceId ___________ : ").append(getTraceId()).append('\n')
                .append("    FlowId ____________ : ").append(getFlowId()).append('\n')
                .append("    Initializing App __ : ").append(getInitializingAppName()).append(",v.").append(
                        getInitializingAppVersion()).append('\n')
                .append("    Initiator (from)___ : ").append(getInitiatorId()).append('\n')
                .append("    Init debug info ___ : ").append(getDebugInfo() != null
                        ? getDebugInfo()
                        : "-not present-").append('\n')
                .append("    Properties:").append('\n')
                .append("      KeepMatsTrace ___ : ").append(getKeepTrace()).append('\n')
                .append("      NonPersistent ___ : ").append(isNonPersistent()).append('\n')
                .append("      Interactive _____ : ").append(isInteractive()).append('\n')
                .append("      TimeToLive ______ : ").append(((tl == null) || (tl == 0)) ? "forever" : tl.toString())
                .append('\n')
                .append("      NoAudit _________ : ").append(isNoAudit()).append('\n')
                .append('\n');

        // === CURRENT CALL ===

        buf.append(" Current Call: ").append(currentCall.getCallType().toString()).append('\n')
                .append("    Timestamp _________ : ").append(Instant.ofEpochMilli(
                        getCurrentCall().getCalledTimestamp()).atZone(ZoneId.systemDefault())).append('\n')
                .append("    MatsMessageId _____ : ").append(getCurrentCall().getMatsMessageId()).append('\n')
                .append("    From App __________ : ").append(getCurrentCall().getCallingAppName()).append(",v.").append(
                        getCurrentCall().getCallingAppVersion()).append('\n')
                .append("    From ______________ : ").append(getCurrentCall().getFrom()).append('\n')
                .append("    To (this) _________ : ").append(getCurrentCall().getTo()).append('\n')
                .append("    Call debug info ___ : ").append(currentCall.getDebugInfo() != null
                        ? currentCall.getDebugInfo()
                        : "-not present-").append('\n')
                .append("    Flow call# ________ : ").append(getCallNumber()).append('\n')
                .append("    Incoming State ____ : ").append(getCurrentState().map(StackState::getState)
                        .map(Object::toString).orElse("-null-"))
                .append('\n')
                .append("    Incoming Msg ______ : ").append(currentCall.getData()).append('\n')
                .append("    Current SpanId ____ : ").append(Long.toString(getCurrentSpanId(), 36)).append('\n')
                .append("    ReplyFrom SpanId __ : ").append(currentCall.getCallType() == CallType.REPLY
                        ? Long.toString(currentCall.getReplyFromSpanId(), 36)
                        : "n/a (not REPLY)").append('\n');
        buf.append('\n');

        // === CALLS ===

        if (getKeepTrace() == KeepMatsTrace.MINIMAL) {
            // MINIMAL
            buf.append(" initiator:  (MINIMAL, so only have initiator and current call)\n");
            buf.append("     ");
            if (an != null) {
                buf.append(" @").append(an);
            }
            if (av != null) {
                buf.append('[').append(av).append(']');
            }
            if (h != null) {
                buf.append(" @").append(h);
            }
            if (ts != 0) {
                buf.append(" @");
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.formatTo(
                        ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), TimeZone.getDefault().toZoneId()), buf);
            }
            if (iid != null) {
                buf.append(" #initiatorId:").append(iid);
            }
            buf.append('\n');
            buf.append(" current call:  (stack height: ").append(currentCall.getReplyStackHeight()).append(")\n");
            buf.append("    ")
                    .append(((CallImpl) getCallFlow().get(0)).toStringFromMatsTrace(ts, 0, 0, false));
            buf.append('\n');
        }
        else {
            // FULL or COMPACT
            // --- Initiator "Call" ---
            buf.append(" call#:       call type\n");
            buf.append("    0    --- [Initiator]");
            if (an != null) {
                buf.append(" @").append(an);
            }
            if (av != null) {
                buf.append('[').append(av).append(']');
            }
            if (h != null) {
                buf.append(" @").append(h);
            }
            if (ts != 0) {
                buf.append(" @");
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.formatTo(
                        ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), TimeZone.getDefault().toZoneId()), buf);
            }
            if (iid != null) {
                buf.append(" #initiatorId:").append(iid);
            }
            buf.append('\n');
            int maxStackSize = c.stream().mapToInt(CallImpl::getReplyStackHeight).max().orElse(0);
            int maxToStageIdLength = c.stream()
                    .mapToInt(c -> c.getTo().toString().length())
                    .max().orElse(0);

            // --- Actual Calls

            List<Call<Z>> callFlow = getCallFlow();
            for (int i = 0; i < callFlow.size(); i++) {
                boolean printNullData = (kt == KeepMatsTrace.FULL) || (i == (callFlow.size() - 1));
                CallImpl<Z> call = (CallImpl<Z>) callFlow.get(i);
                buf.append(String.format("   %2d %s\n", i + 1,
                        call.toStringFromMatsTrace(ts, maxStackSize, maxToStageIdLength, printNullData)));
            }
        }

        buf.append('\n');

        // === STATES ===

        buf.append(" ").append("states: (").append(getKeepTrace()).append(", thus ")
                .append(getKeepTrace() == KeepMatsTrace.FULL ? "state flow" : "state stack")
                .append(" - includes state (if any) for this frame, and for all reply frames below us)")
                .append("\n");
        List<StackState<Z>> stateFlow = getStateFlow();
        if (stateFlow.isEmpty()) {
            buf.append("    <empty, no states>\n");
        }
        for (int i = 0; i < stateFlow.size(); i++) {
            buf.append(String.format("   %2d %s", i, stateFlow.get(i))).append('\n');
        }
        buf.append('\n');

        // === REPLY TO STACK ===

        buf.append(" current ReplyTo stack (frames below us): \n");
        List<Channel> stack = currentCall.getReplyStack();
        if (stack.isEmpty()) {
            buf.append("    <empty, cannot reply>\n");
        }
        else {
            List<StackState<Z>> stateStack = getStateStack();
            for (int i = 0; i < stack.size(); i++) {
                buf.append(String.format("   %2d %s", i, stack.get(i).toString()))
                        .append("  #state:").append(stateStack.get(i).getState())
                        .append('\n');
            }
        }
        buf.append('\n');

        // === SPAN ID STACK ===

        buf.append(" current SpanId stack: \n");
        List<Long> spanIdStack = getSpanIdStack();
        for (int i = 0; i < spanIdStack.size(); i++) {
            buf.append(String.format("   %2d %s", i,
                    Long.toString(spanIdStack.get(i), 36)));
            if (i == spanIdStack.size() - 1) {
                buf.append(" (SpanId which current ").append(currentCall.getCallType())
                        .append(" call is processing within)");
            }
            if (i == 0) {
                buf.append(" (Root SpanId for initiator/terminator level)");
            }
            buf.append('\n');
        }

        return buf.toString();
    }
}
