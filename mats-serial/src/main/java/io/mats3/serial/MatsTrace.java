package io.mats3.serial;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.mats3.serial.MatsTrace.Call.MessagingModel;

/**
 * Together with the {@link MatsSerializer}, this interface describes one way to implement a wire-protocol for how Mats
 * communicates. It is up to the implementation of the <code>MatsFactory</code> to implement a protocol of how the Mats
 * API is transferred over the wire. This is one such implementation that can be used, which is employed by the default
 * JMS implementation of Mats.
 * <p>
 * The MatsTrace is designed to contain all previous {@link Call}s in a processing, thus helping the debugging for any
 * particular stage immensely: All earlier calls with data and stack frames for this processing is kept in the trace,
 * thus enabling immediate understanding of what lead up to the particular situation.
 * <p>
 * However, for any particular invocation (invoke, request or reply), only the current (last) {@link Call} - along with
 * the stack frames for the same and lower stack depths than the current call - is needed to execute the stage. This
 * makes it possible to use a condensed variant of MatsTrace that only includes the single current {@link Call}, along
 * with the relevant stack frames. This is defined by the {@link KeepMatsTrace} enum.
 * <p>
 * One envisions that for development and the production stabilization phase of the system, the long form is used, while
 * when the system have performed flawless for a while, one can change it to use the condensed form, thereby shaving
 * some cycles for the serialization and deserialization, but more importantly potentially quite a bit of bandwidth and
 * message processing compared to transfer of the full trace.
 *
 * @param <Z>
 *            The type which STOs and DTOs are serialized into. When employing JSON for the "outer" serialization of
 *            MatsTrace, it does not make that much sense to use a binary (Z=byte[]) "inner" representation of the DTOs
 *            and STOs, because JSON is terrible at serializing byte arrays.
 *
 * @author Endre Stølsvik - 2018-03-17 23:37, factored out from original from 2015 - http://endre.stolsvik.com
 */
public interface MatsTrace<Z> {

    /**
     * String employed as return value for "debug only" fields which may as well be null - by setting them to null, we
     * conserve time and space in the serialization (For JSON, even the field itself is not serialized if the value is
     * null). If they are null, the corresponding getter returns this value.
     */
    String NULLED = "-nulled-";

    /**
     * @deprecated TODO: delete once all > v0.18.4
     */
    @Deprecated
    default MatsTrace<Z> withDebugInfo(String initializingAppName, String initializingAppVersion,
            String initializingHost,
            String initiatorId, long initializedTimestamp, String debugInfo) {
        return withDebugInfo(initializingAppName, initializingAppVersion, initializingHost, initiatorId, debugInfo);
    }

    /**
     * Can only be set once..
     *
     * @param initiatorId
     *            the id set using <code>matsInitiate.from(initiatorId)</code> - it is not the name of the initiator.
     * @return <code>this</code>, for chaining. Note that this is opposed to the add[Request|Send|Next|Reply]Call(..)
     *         methods, which return a new, independent instance.
     */
    MatsTrace<Z> withDebugInfo(String initializingAppName, String initializingAppVersion, String initializingHost,
            String initiatorId, String debugInfo);

    /**
     * If this newly created MatsTrace is a child-flow (initiated within a Stage) of an existing flow, then this method
     * should be invoked to set the parent MatsMessageId, and the "total call number" which is a "Call Overflow
     * protection" mechanism.
     * <p />
     * <b>Parent Mats Message Id:</b> The MatsMessageId of the message whose processing spawned this new flow.
     * <p />
     * <b>Total Call Number:</b> Initializes the {@link #getTotalCallNumber()}. If this message is initiated within an
     * existing call flow, set to the current call's {@link #getTotalCallNumber()} + 1. This number will increase for
     * every subsequent call this flow is going through, just as with {@link #getCallNumber()} - the difference being
     * that it should <i>continue</i> increasing if a new message is initiated within a flow. Thereby it is possible to
     * stop an out-of-control initiate-send/request recursion, by checking that the {@link #getTotalCallNumber()}
     * doesn't ever go above a fixed number, e.g. 100.
     *
     * @param parentMatsMessageId
     *            the MatsMessageId of the message whose processing spawned this new flow.
     * @param totalCallNumber
     *            the {@link #getTotalCallNumber()} to initialize this MatsTrace with.
     * @return <code>this</code>, for chaining. Note that this is opposed to the add[Request|Send|Next|Reply]Call(..)
     *         methods, which return a new, independent instance.
     */
    MatsTrace<Z> withChildFlow(String parentMatsMessageId, int totalCallNumber);

    /**
     * @return the TraceId that this {@link MatsTrace} was initiated with - this is set once, at initiation time, and
     *         follows the processing till it terminates. (All log lines will have the traceId set on the MDC.)
     */
    String getTraceId();

    /**
     * @return the "FlowId", which is a system-specified, guaranteed-globally-unique TraceId - and shall be the prefix
     *         of each {@link Call#getMatsMessageId()}, separated by a "_".
     */
    String getFlowId();

    long getInitializedTimestamp();

    /**
     * @return to which extent the Call history (with State) should be kept. The default is
     *         {@link KeepMatsTrace#COMPACT}.
     */
    KeepMatsTrace getKeepTrace();

    /**
     * Specifies how the MatsTrace will handle historic values that are present just for debugging. Notice the annoyance
     * that this is effectively specified twice, once in the MATS API, and once here. That is better, IMHO, than this
     * package depending on the MATS API only for that enum.
     */
    enum KeepMatsTrace {
        /**
         * Keeps all Data and State history for the entire trace. (Still the {@link Call#getFrom() from} and
         * {@link Call#getReplyStack() stack} will be nulled, as it provide zero value that cannot be deduced from the
         * previous calls).
         */
        FULL,

        /**
         * <b>Default</b>: Nulls out Data for other than current call while still keeping the meta-info for the call
         * history, and condenses State to a pure stack.
         */
        COMPACT,

        /**
         * Only keep the {@link MatsTrace#getCurrentCall() current call}, and condenses State to a pure stack.
         */
        MINIMAL
    }

    /**
     * @return whether the message should be JMS-style "non-persistent", default is <code>false</code> (i.e. persistent,
     *         reliable).
     */
    boolean isNonPersistent();

    /**
     * @return whether the message should be prioritized in that a human is actively waiting for the reply, default is
     *         <code>false</code>.
     */
    boolean isInteractive();

    /**
     * @return the number of milliseconds the message should live before being time out. 0 means "forever", and is the
     *         default.
     */
    long getTimeToLive();

    /**
     * @return a hint to the underlying implementation, or to any monitoring/auditing tooling on the Message Broker,
     *         that it does not make much value in auditing this message flow, typically because it is just a "getter"
     *         of information to show to some user, or a health-check validating that some service is up and answers in
     *         a timely fashion.
     */
    boolean isNoAudit();

    // --- Stuff set with the 'withDebugInfo(...)' method.

    String getInitializingAppName();

    String getInitializingAppVersion();

    String getInitializingHost();

    /**
     * @return a fictive "endpointId" of the initiator, see <code>MatsInitiator.MatsInitiate.from(String)</code>.
     */
    String getInitiatorId();

    String getDebugInfo();

    /**
     * @return the number of calls that this MatsTrace have been through, i.e. how many times
     *         {@link MatsTrace#addRequestCall(String, String, MessagingModel, String, MessagingModel, Object, Object, Object)
     *         MatsTrace.add[Request|Next|Reply..](..)} has been invoked on this MatsTrace. This means that right after
     *         a new MatsTrace has been created, before a call has been added, 0 is returned. With KeepMatsTrace at
     *         {@link KeepMatsTrace#FULL FULL} or {@link KeepMatsTrace#COMPACT COMPACT}, the returned number will be the
     *         same as {@link #getCallFlow()}.size(), but with {@link KeepMatsTrace#MINIMAL MINIMAL}, that number of
     *         always 1, but this number will still return the number of calls that has been added through the flow.
     *
     * @see #getTotalCallNumber()
     */
    int getCallNumber();

    /**
     * "Stack overflow protection" mechanism.
     *
     * @return the total call number, which is the same as {@link #getCallNumber()} <i>unless</i> this flow was
     *         initiated within a stage, in which case the totalCallNumber starts at the current call number at that
     *         stage (as set with {@link #withChildFlow(String, int)}). This ensures that if we end up with e.g. a mats
     *         flow initiating a new mats flow to itself, thus creating a loop, this number will continuously increase,
     *         and we can thus break out at some obviously-too-large value.
     */
    int getTotalCallNumber();

    /**
     * If this is a {@link #withChildFlow(String, int) child flow} of an existing flow, this should return the
     * MatsMessageId of the message whose processing spawned this new flow.
     *
     * @return the MatsMessageId of the message whose processing spawned this new flow.
     */
    String getParentMatsMessageId();

    /**
     * Sets a trace property, refer to <code>ProcessContext.setTraceProperty(String, Object)</code>. Notice that on the
     * MatsTrace-side, the value must be of type {@code Z}.
     *
     * @param propertyName
     *            the name of the property.
     * @param propertyValue
     *            the value of the property.
     *
     * @see #getTracePropertyKeys()
     * @see #getTraceProperty(String)
     */
    void setTraceProperty(String propertyName, Z propertyValue);

    /**
     * Retrieves a property value set by {@link #setTraceProperty(String, Z)}, refer to
     * <code>ProcessContext.getTraceProperty(String, Class)</code>. Notice that on the MatsTrace-side, the value is of
     * type {@code Z}.
     *
     * @param propertyName
     *            the name of the property to retrieve.
     * @return the value of the property.
     *
     * @see #setTraceProperty(String, Object)
     * @see #getTracePropertyKeys()
     */
    Z getTraceProperty(String propertyName);

    /**
     * @return the set of keys containing {@link #getTraceProperty(String) trace properties}.
     *
     * @see #getTraceProperty(String)
     * @see #setTraceProperty(String, Object)
     */
    Set<String> getTracePropertyKeys();

    /**
     * Adds a {@link Call.CallType#REQUEST REQUEST} Call, which is an invocation of a service where one expects a Reply
     * from this service to go to a specified endpoint, typically the next stage in a multi-stage endpoint: Envision a
     * normal invocation of some method that returns a value.
     *
     * @param from
     *            which stageId this request is for. This is solely meant for monitoring and debugging - the protocol
     *            does not need the from specifier, as this is not where any replies go to.
     * @param to
     *            which endpoint that should get the request.
     * @param toMessagingModel
     *            the {@link MessagingModel} of 'to'.
     * @param replyTo
     *            which endpoint that should get the reply from the requested endpoint.
     * @param replyToMessagingModel
     *            the {@link MessagingModel} of 'replyTo'.
     * @param data
     *            the request data, most often a JSON representing the Request Data Transfer Object that the requesting
     *            service expects to get.
     * @param replyState
     *            the state data for the stageId that gets the reply to this request, that is, the state for the stageId
     *            that is at the first element of the replyStack. Most often a JSON representing the State Transfer
     *            Object for the multi-stage endpoint.
     * @param initialState
     *            an optional feature, whereby the state can be set for the initial stage of the requested endpoint.
     *            Same stuff as replyState.
     */
    MatsTrace<Z> addRequestCall(String from,
            String to, MessagingModel toMessagingModel,
            String replyTo, MessagingModel replyToMessagingModel,
            Z data, Z replyState, Z initialState);

    /**
     * Adds a {@link Call.CallType#SEND SEND} Call, meaning a "request" which do not expect a Reply: Envision an
     * invocation of a void-method. Or an invocation of some method that returns the value, but where you invoke it as a
     * void-method (i.e. not storing the result, e.g. the method <code>map.remove("test")</code> returns the removed
     * value, but is often invoked without storing this.).
     *
     * @param from
     *            which stageId this request is for. This is solely meant for monitoring and debugging - the protocol
     *            does not need the from specifier, as this is not where any replies go to.
     * @param to
     *            which endpoint that should get the message.
     * @param toMessagingModel
     *            the {@link MessagingModel} of 'to'.
     * @param data
     *            the request data, most often a JSON representing the Request Data Transfer Object that the receiving
     *            service expects to get.
     * @param initialState
     *            an optional feature, whereby the state can be set for the initial stage of the requested endpoint.
     */
    MatsTrace<Z> addSendCall(String from,
            String to, MessagingModel toMessagingModel,
            Z data, Z initialState);

    /**
     * Adds a {@link Call.CallType#NEXT NEXT} Call, which is a "skip call" to the next stage in a multistage service, as
     * opposed to the normal request out to a service expecting a reply. The functionality is functionally identical to
     * {@link #addSendCall(String, String, MessagingModel, Z, Z) addSendCall(...)}, but has its own {@link Call.CallType
     * CallType} enum value {@link Call.CallType#NEXT NEXT}.
     * <p />
     * Note: Cannot specify {@link MessagingModel} here, as one cannot fathom where that would make sense: It must be
     * {@link MessagingModel#QUEUE QUEUE}.
     *
     * @param from
     *            which stageId this request is for. This is solely meant for monitoring and debugging - the protocol
     *            does not need the from specifier, as this is not where any replies go to.
     * @param to
     *            which endpoint that should get the message - the next stage in a multi-stage service.
     * @param data
     *            the request data, most often a JSON representing the Request Data Transfer Object that the next stage
     *            expects to get.
     * @param state
     *            the state data for the next stage.
     */
    MatsTrace<Z> addNextCall(String from, String to, Z data, Z state);

    /**
     * Adds a {@link Call.CallType#REPLY REPLY} Call, which happens when a requested service is finished with its
     * processing and have some Reply to return. This method pops the stack (takes the last element) from the (previous)
     * current call, sets this as the "to" parameter, and uses the rest of the list as the stack for the next Call.
     *
     * @param from
     *            which stageId this request is for. This is solely meant for monitoring and debugging - the protocol
     *            does not need the from specifier, as this is not where any replies go to.
     * @param data
     *            the request data, most often a JSON representing the Request Data Transfer Object that the requesting
     *            service expects to get.
     */
    MatsTrace<Z> addReplyCall(String from, Z data);

    /**
     * Shall be invoked after adding the outgoing call, immediately before serializing the outgoing MatsTrace.
     * <ul>
     * <li>Sets the outgoing Call's {@link Call#getCalledTimestamp()} to be more closely aligned to the exact sending
     * time. (For example, the message may have been constructed, then a massive SQL query was performed, and then a new
     * message is constructed, and then the messages are actually turned into JMS messages and committed on the wire.
     * This means that the first message will have a much earlier timestamp than the second.) Using this method, all
     * outgoing messages can have the Called Timestamp set <i>right</i> before it is serialized and JMS-constructed and
     * committed.</li>
     * <li>If the {@link #getCurrentCall()} is a REQUEST, SEND, GOTO or PUBLISH (anything else than REPLY), it also sets
     * the same-height-called-timestamp, recorded on the stackframe below it, or on the MatsTrace itself if there is no
     * stackframe below (initial SEND). This is to be able to calculate the "time between stages" for the e.g. time
     * between stage1 and stage2 of a multi-stage endpoint, noting that this might entail multiple levels of Request and
     * Replies (thus it must reside on the stack).</li>
     * </ul>
     */
    void setOutgoingTimestamp(long timestamp);

    /**
     * @return the timestamp set by {@link #setOutgoingTimestamp(long)} for the preceding call on the same stack height.
     *         Used to calculate the "time between stages" for the different stages on an endpoint. It does not make
     *         sense to get this for the initial stage of an Endpoint if the incoming is a REQUEST, and the return
     *         value will then be <code>-1</code>.
     */
    long getSameHeightOutgoingTimestamp();

    /**
     * Invoke this as early as possible on the reception of a message. Used to calculate the "total endpoint time",
     * through all stages (including intermediate request/replies): Time from entry on the Initial Stage, to when the
     * endpoint Replies, or stops (no outgoing message).
     */
    void setStageEnteredTimestamp(long timestamp);

    /**
     * @return the value of {@link #setStageEnteredTimestamp(long)} for the stages of the same endpoint. Used to
     *         calculate the "total endpoint time", through all stages, when the endpoint Replies, or stops (no outgoing
     *         message).
     */
    long getSameHeightEndpointEnteredTimestamp();

    /**
     * @return this MatsTrace's SpanId. If it is still on the initiator side, before having had a call added to it, or
     *         on the terminator side, when the stack again is empty, the SpanId is derived from the {@link #getFlowId()
     *         FlowId}. Otherwise, it is the topmost element of an internal stack, in the same way as
     *         {@link #getCurrentCall()}.{@link Call#getReplyStack()}.
     */
    long getCurrentSpanId();

    /**
     * @return the {@link Call Call} which should be processed by the stage receiving this {@link MatsTrace} (which
     *         should be the stageId specified in getCurrentCall().{@link Call#getTo() getTo()}). Returns
     *         <code>null</code> if not call has yet been added to the trace.
     */
    Call<Z> getCurrentCall();

    /**
     * Returns the {@link StackState} for the {@link #getCurrentCall()}, if present.
     * <p />
     * Searches in the {@link #getStateFlow() 'State Flow'} from the back (most recent) for the first element that is at
     * the current stack height, as defined by {@link #getCurrentCall()}.{@link Call#getReplyStackHeight()}. If a more
     * shallow stackDepth than the specified is encountered, or the list is exhausted without the Stack Height being
     * found, the search is terminated with null. This happens for the initial stage for an endpoint, unless the
     * 'initialState' was set on the SEND or REQUEST.
     * <p />
     * The point of the 'State Flow' is the same as for the Call list: Monitoring and debugging, by keeping a history of
     * all calls in the processing, along with the states that was present at each call point.
     * <p />
     * If "condensing" is on ({@link KeepMatsTrace#COMPACT COMPACT} or {@link KeepMatsTrace#MINIMAL MINIMAL}), the
     * stack-state-list is - by the condensing algorithm - turned in to a pure stack (as available via
     * {@link #getStateStack()}), with the StackState for the earliest stack element at position 0, while the latest
     * (current) at end of list. The above-specified search algorithm still works, as it now will either find the
     * element with the correct stack depth at the end of the list, or it is not there.
     * <p />
     * NOTE: The StateStack (mostly) includes a frame for the <i>current</i> call, as opposed to the
     * {@link Call#getReplyStack()} (reply stack), which only includes frames below us. Note that as a matter of
     * avoiding space use, on a REQUEST call, the StackState is not added for the actual REQUEST message's state stack,
     * unless the "initial incoming state" is supplied (which is uncommon - a service invocation typically starts with
     * an empty state). However, on REPLY messages, it will always be present, and hence the state stack is typically
     * one level higher (includes current frame) than the reply stack (only includes frames below).
     * <p />
     * NOTE: As further info on how the state stack relates to the reply stack height: When a REPLY comes to a
     * terminator, there are 0 more frames below. However, the terminator needs its state, which is at state stack
     * height 0.
     *
     * @return the {@link StackState} for the {@link #getCurrentCall()} if it exists, <code>null</code> otherwise, as is
     *         typical when entering initial stage of an endpoint.
     */
    Optional<StackState<Z>> getCurrentState();

    /**
     * @return the stack of the states for the current stack: getCurrentCall().getStack().
     * @see #getCurrentState() for more information on how the "State Flow" works.
     */
    List<StackState<Z>> getStateStack();

    /**
     * @return the flow of calls, from the first REQUEST (or SEND), to the {@link #getCurrentCall() current call} -
     *         unless {@link #getKeepTrace() KeepTrace} is MINIMAL, in which case only the current call is present in
     *         the list.
     */
    List<Call<Z>> getCallFlow();

    /**
     * @return the entire list of states as they have changed throughout the call flow. If {@link KeepMatsTrace} is
     *         COMPACT or MINIMAL, then it will be a pure stack (as returned with {@link #getStateStack()}, with the
     *         last element being the most recent stack frame. NOTICE: The index position in this list has little to do
     *         with which stack level the state refers to. This must be gotten from {@link StackState#getHeight()}.
     * @see #getCurrentState() for more information on how the "State Flow" works.
     */
    List<StackState<Z>> getStateFlow();

    /**
     * Represents an entry in the {@link MatsTrace}.
     */
    interface Call<Z> {
        long getCalledTimestamp();

        /**
         * @return the Mats Message Id, a guaranteed-globally-unique id for this particular message - it SHALL be
         *         constructed as follows: {@link MatsTrace#getFlowId()} + "_" + flow-unique messageId.
         */
        String getMatsMessageId();

        /**
         * @deprecated TODO: Remove once all are >= 0.18.4
         */
        @Deprecated
        default Call<Z> setDebugInfo(String callingAppName, String callingAppVersion, String callingHost,
                long calledTimestamp, String matsMessageId, String debugInfo) {
            return setDebugInfo(callingAppName, callingAppVersion, callingHost, debugInfo);
        }

        /**
         * Can only be set once.
         */
        Call<Z> setDebugInfo(String callingAppName, String callingAppVersion, String callingHost, String debugInfo);

        String getCallingAppName();

        String getCallingAppVersion();

        String getCallingHost();

        String getDebugInfo();

        CallType getCallType();

        /**
         * @return when {@link #getCallType()} is {@link CallType#REPLY REPLY}, the value of the REQUEST's SpanId is
         *         returned, otherwise an {@link IllegalStateException} is thrown.
         */
        long getReplyFromSpanId();

        /**
         * Which type of Call this is.
         */
        enum CallType {
            REQUEST,

            SEND,

            NEXT,

            REPLY,

            /** Not yet used. Ref issue #69 */
            GOTO
        }

        /**
         * @return the stageId that sent this call - will most probably be the string {@link #NULLED "-nulled-"} for any
         *         other Call than the {@link MatsTrace#getCurrentCall()}, to conserve space in the MatsTrace. The
         *         rationale for this, is that if those Calls are available, they are there for debug purposes only, and
         *         then you can use the order of the Calls to see who is the caller: The previous Call's {@link #getTo()
         *         "to"} is the {@link #getFrom() "from"} of this Call.
         */
        String getFrom();

        /**
         * @return the endpointId/stageId this Call concerns, wrapped in a {@link Channel} to also specify the
         *         {@link MessagingModel} in use.
         */
        Channel getTo();

        /**
         * An encapsulation of the stageId/endpointId along with the {@link MessagingModel} the message should be
         * delivered over.
         */
        interface Channel {
            String getId();

            MessagingModel getMessagingModel();
        }

        /**
         * Specifies what type of Messaging Model a 'to' and 'replyTo' is to go over: Queue or Topic. Queue is the
         * obvious choice for most traffic, but sometimes talking to all nodes in a cluster is of interest.
         */
        enum MessagingModel {
            QUEUE,

            TOPIC
        }

        Z getData();

        /**
         * @return the stack height of this Call - which is the number of elements <i>below</i> this call. I.e. for a
         *         {@link CallType#REPLY REPLY} to a Terminator, the stack is of size 0 (there are no more elements to
         *         REPLY to), while for the first {@link CallType#REQUEST REQUEST} from an initiator, the stack is of
         *         size 1 (the endpointId for the Terminator is the one element below this Call).
         */
        int getReplyStackHeight();

        /**
         * @return a COPY of the replyTo stack of Channels (if you just need the height, which is the common case, use
         *         {@link #getReplyStackHeight()}) - NOTICE: This will most probably be a List with
         *         {@link #getReplyStackHeight()} elements containing "-nulled-" for any other Call than the
         *         {@link MatsTrace#getCurrentCall()}, to conserve space in the MatsTrace. The LAST (i.e. position
         *         'size()-1') element is the most recent, meaning that the next REPLY will go here, while the FIRST
         *         (i.e. position 0) element is the earliest in the stack, i.e. the stageId where the Terminator
         *         endpointId typically will reside (unless the initial call was a {@link CallType#SEND SEND}, which
         *         means that you don't want a reply).
         */
        List<Channel> getReplyStack();
    }

    /**
     * The State instances (of type Z), along with the height of the stack the state relates to.
     */
    interface StackState<Z> {
        /**
         * @return which stack height this {@link #getState()} belongs to.
         */
        int getHeight();

        /**
         * @return the state at stack height {@link #getHeight()}.
         */
        Z getState();

        /**
         * Sets "extra state" on this StackState.
         */
        void setExtraState(String key, Z value);

        /**
         * Retrieves "extra state" on this StackState.
         */
        Z getExtraState(String key);
    }
}
