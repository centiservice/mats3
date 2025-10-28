/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.localinspect;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsInitiator;
import io.mats3.MatsStage;
import io.mats3.api.intercept.MatsInitiateInterceptor.MatsInitiateInterceptOutgoingMessages;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsEditableOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MessageType;
import io.mats3.api.intercept.MatsStageInterceptor.MatsStageInterceptOutgoingMessages;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext.StageProcessResult;

/**
 * Interceptor that collects "local stats" for Initiators and Stages of Endpoints, which can be used in conjunction with
 * a MatsFactory report generator, {@link LocalHtmlInspectForMatsFactory}.
 * <p>
 * To install, invoke the {@link #install(MatsFactory)} method, supplying the MatsFactory. The report generator
 * will fetch the interceptor from the MatsFactory.
 * <p>
 * <b>Implementation note:</b> Mats allows Initiators and Endpoints to be defined "runtime" - there is no specific setup
 * time vs. run time. This implies that the set of Endpoints and Initiators in a MatsFactory can increase from one run
 * of a report generation to the next (Endpoint may even be deleted, but this is really only meant for testing).
 * Moreover, this stats collecting class only collects stats for Initiators and Endpoints that have had traffic, since
 * it relies on the interceptors API. This means that while the MatsFactory might know about an Initiator or an
 * Endpoint, this stats class might not yet have picked it up. This is why all methods return Optional. On the other
 * hand, the set of Stages of an endpoint cannot change after it has been {@link MatsEndpoint#finishSetup()
 * finishedSetup()} (and it cannot be {@link MatsEndpoint#start() start()}'ed before it has been
 * <code>finishedSetup()</code>) - thus if e.g. only the initial stage of an Endpoint has so far seen traffic, this
 * class has nevertheless created stats objects for all of the Endpoint's stages.
 *
 * @author Endre Stølsvik 2021-04-09 00:37 - http://stolsvik.com/, endre@stolsvik.com
 */
public class LocalStatsMatsInterceptor
        implements MatsInitiateInterceptOutgoingMessages, MatsStageInterceptOutgoingMessages {

    private static final Logger log = LoggerFactory.getLogger(LocalStatsMatsInterceptor.class);

    public static final int DEFAULT_NUM_SAMPLES = 1100;

    /**
     * This is an Out Of Memory avoidance in case of wrongly used initiatorIds. These are not supposed to be dynamic,
     * but there is nothing hindering a user from creating a new initiatorId per initiation. Thus, if we go above a
     * certain number of such entries, we stop adding.
     * <p>
     * Value is 500.
     */
    public static final int MAX_NUMBER_OF_DYNAMIC_ENTRIES = 500;

    /**
     * Creates an instance of this interceptor and installs it on the provided {@link MatsFactory}. Note that this
     * interceptor is stateful wrt. the MatsFactory, thus a new instance is needed per MatsFactory - which is fulfilled
     * using this method. It should only be invoked once per MatsFactory. You get the created interceptor in return,
     * but that is not needed when employed with {@link LocalHtmlInspectForMatsFactory}, as that will fetch the
     * instance from the MatsFactory using {@link FactoryConfig#getPlugins(Class)}.
     *
     * @param matsFactory
     *            the {@link MatsFactory MatsFactory} to add it to.
     */
    public static LocalStatsMatsInterceptor install(MatsFactory matsFactory) {
        LocalStatsMatsInterceptor interceptor = new LocalStatsMatsInterceptor(DEFAULT_NUM_SAMPLES);
        matsFactory.getFactoryConfig().installPlugin(interceptor);
        return interceptor;
    }

    private final int _numSamples;

    private LocalStatsMatsInterceptor(int numSamples) {
        _numSamples = numSamples;
    }

    private final ConcurrentHashMap<MatsInitiator, InitiatorStatsImpl> _initiators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MatsEndpoint<?, ?>, EndpointStatsImpl> _endpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MatsStage<?, ?, ?>, StageStatsImpl> _stages = new ConcurrentHashMap<>();

    // ======================================================================================================
    // ===== Exposed API for LocalStatsMatsInterceptor

    public Optional<InitiatorStats> getInitiatorStats(MatsInitiator matsInitiator) {
        return Optional.ofNullable(_initiators.get(matsInitiator));
    }

    public Optional<EndpointStats> getEndpointStats(MatsEndpoint<?, ?> matsEndpoint) {
        return Optional.ofNullable(_endpoints.get(matsEndpoint));
    }

    public Optional<StageStats> getStageStats(MatsStage<?, ?, ?> matsStage) {
        return Optional.ofNullable(_stages.get(matsStage));
    }

    public interface InitiatorStats {
        StatsSnapshot getTotalExecutionTimeNanos();

        NavigableMap<OutgoingMessageRepresentation, Long> getOutgoingMessageCounts();
    }

    public interface EndpointStats {
        List<StageStats> getStagesStats();

        StageStats getStageStats(MatsStage<?, ?, ?> stage);

        StatsSnapshot getTotalEndpointProcessingTimeNanos();

        /**
         * @return whether the reply DTO is <code>void</code>, in which case it is regarded as a Terminator endpoint.
         */
        boolean isTerminatorEndpoint();

        /**
         * <b>Only relevant for Endpoints that {@link #isTerminatorEndpoint()}.</b> Terminator endpoints have a special
         * set of timings: Time taken from the start of initiation to the terminator receives it. Note: Most initiations
         * specify a terminator in the same codebase as the initiation, but this is not a requirement. This timing is
         * special in that it uses the differences in initiation timestamp (System.currentTimeMillis()) vs. reception at
         * terminator (with millisecond precision) <i>until</i> it sees a reception that is on the same nodename as the
         * initiation. At that point it switches over to using only timings that go between initiation and reception on
         * the same node - this both removes the problem of time skews, and provide for more precise timings (since it
         * uses System.nanoTime()), at the expense of only sampling a subset of the available observations.
         */
        NavigableMap<IncomingMessageRepresentation, StatsSnapshot> getInitiatorToTerminatorTimeNanos();
    }

    public interface StageStats {
        int getIndex();

        boolean isInitial();

        /**
         * Note: Only has millisecond resolution, AND is susceptible to time skews between nodes (uses
         * <code>System.currentTimeMillis()</code> on the sending and receiving node).
         */
        StatsSnapshot getSpentQueueTimeNanos();

        /**
         * Note: Not present for the {@link #isInitial()} stage, as there is no "between" for the initial stage.
         * <p>
         * Note: Only recorded for messages that happens to have the two "between" stages executed on the same node, to
         * both eliminate time skews between nodes, and to get higher precision (nanoTime()).
         */
        Optional<StatsSnapshot> getBetweenStagesTimeNanos();

        /**
         * @return the stage's total execution time (from right after received, to right before going back to receive
         *         loop).
         */
        StatsSnapshot getStageTotalExecutionTimeNanos();

        NavigableMap<IncomingMessageRepresentation, Long> getIncomingMessageCounts();

        NavigableMap<StageProcessResult, Long> getProcessResultCounts();

        NavigableMap<OutgoingMessageRepresentation, Long> getOutgoingMessageCounts();
    }

    interface StatsSnapshot {
        /**
         * @return the current set of samples. While starting, when we still haven't had max-samples yet, the array size
         *         will be lower than max-samples, down to 0 elements.
         */
        long[] getSamples();

        /**
         * @param percentile
         *            the requested percentile, in the range [0, 1]. 0 means the lowest value (i.e. all samples are >=
         *            this value), while 1 means the highest sample (i.e. all samples are <= this value).
         * @return the value at the desired percentile
         */
        long getValueAtPercentile(double percentile);

        /**
         * @return the number of executions so far, which can be > max-samples.
         */
        long getNumObservations();

        // ===== Timings:

        default long getMin() {
            return getValueAtPercentile(0);
        };

        double getAverage();

        default long getMax() {
            return getValueAtPercentile(1);
        };

        double getStdDev();

        default double getMedian() {
            return getValueAtPercentile(0.5);
        }

        default double get75thPercentile() {
            return getValueAtPercentile(0.75);
        }

        default double get95thPercentile() {
            return getValueAtPercentile(0.95);
        }

        default double get98thPercentile() {
            return getValueAtPercentile(0.98);
        }

        default double get99thPercentile() {
            return getValueAtPercentile(0.99);
        }

        default double get999thPercentile() {
            return getValueAtPercentile(0.999);
        }
    }

    public interface MessageRepresentation {
        MessageType getMessageType();

        String getInitiatingAppName();

        String getInitiatorId();
    }

    public interface IncomingMessageRepresentation extends MessageRepresentation,
            Comparable<IncomingMessageRepresentation> {
        String getFromAppName();

        String getFromStageId();
    }

    public interface OutgoingMessageRepresentation extends MessageRepresentation,
            Comparable<OutgoingMessageRepresentation> {
        String getTo();

        Class<?> getMessageClass();
    }

    // ======================================================================================================
    // ===== INITIATION interceptor implementation

    public static final String EXTRA_STATE_REQUEST_NANOS = "mats.rts";
    public static final String EXTRA_STATE_REQUEST_NODENAME = "mats.rnn";

    public static final String EXTRA_STATE_ENDPOINT_ENTER_NANOS = "mats.eets";
    public static final String EXTRA_STATE_ENDPOINT_ENTER_NODENAME = "mats.eenn";

    public static final String EXTRA_STATE_OR_SIDELOAD_INITIATOR_NANOS = "mats.its";
    public static final String EXTRA_STATE_OR_SIDELOAD_INITIATOR_NODENAME = "mats.inn";

    @Override
    public void initiateInterceptOutgoingMessages(InitiateInterceptOutgoingMessagesContext context) {
        List<MatsEditableOutgoingMessage> outgoingMessages = context.getOutgoingMessages();

        // :: INITIATOR TO TERMINATOR TIMING:
        // Decorate outgoing messages with extra-state or sideloads, to get initiator to terminator timings
        for (MatsEditableOutgoingMessage msg : outgoingMessages) {
            // ?: Is this a REQUEST?
            if (msg.getMessageType() == MessageType.REQUEST) {
                // -> Yes, REQUEST.
                // Set nanoTime and nodename in extra-state, for the final REPLY to terminator.
                msg.setSameStackHeightExtraState(EXTRA_STATE_OR_SIDELOAD_INITIATOR_NANOS, context.getStartedNanoTime());
                msg.setSameStackHeightExtraState(EXTRA_STATE_OR_SIDELOAD_INITIATOR_NODENAME,
                        context.getInitiator().getParentFactory().getFactoryConfig().getNodename());
            }
            else {
                // -> No, so SEND or PUBLISH
                // Set nanoTime and nodename in sideload, for the receiving endpoint
                msg.addString(EXTRA_STATE_OR_SIDELOAD_INITIATOR_NANOS, Long.toString(context.getStartedNanoTime()));
                msg.addString(EXTRA_STATE_OR_SIDELOAD_INITIATOR_NODENAME,
                        context.getInitiator().getParentFactory().getFactoryConfig().getNodename());
            }
        }
    }

    @Override
    public void initiateCompleted(InitiateCompletedContext context) {
        MatsInitiator initiator = context.getInitiator();
        InitiatorStatsImpl initiatorStats = _initiators.computeIfAbsent(initiator, (v) -> new InitiatorStatsImpl(
                _numSamples));

        // :: TIMING
        long totalExecutionNanos = context.getTotalExecutionNanos();
        initiatorStats.recordTotalExecutionTimeNanos(totalExecutionNanos);

        // :: OUTGOING MESSAGES
        List<MatsSentOutgoingMessage> outgoingMessages = context.getOutgoingMessages();
        for (MatsSentOutgoingMessage msg : outgoingMessages) {
            initiatorStats.recordOutgoingMessage(msg.getMessageType(), msg.getTo(),
                    msg.getData() == null ? null : msg.getData().getClass(),
                    msg.getInitiatingAppName(), msg.getInitiatorId());
        }
    }

    // ======================================================================================================
    // ===== STAGE interceptor implementation

    @Override
    public void stageReceived(StageReceivedContext i_context) {
        MatsStage<?, ?, ?> stage = i_context.getStage();
        // Must get-or-create this first, since this is what creates the StageStats.
        EndpointStatsImpl endpointStats = getOrCreateEndpointStatsImpl(stage.getParentEndpoint());
        // Get the StageStats
        StageStatsImpl stageStats = _stages.get(stage);

        // Get the ProcessContext
        ProcessContext<Object> p_context = i_context.getProcessContext();

        MessageType incomingMessageType = i_context.getIncomingMessageType();

        IncomingMessageRepresentationImpl incomingMessageRepresentation = new IncomingMessageRepresentationImpl(
                incomingMessageType, p_context.getFromAppName(),
                p_context.getFromStageId(), p_context.getInitiatingAppName(), p_context.getInitiatorId());

        // :: COUNT INCOMING MESSAGES:
        stageStats.recordIncomingMessage(incomingMessageRepresentation);

        // :: TIME SPENT IN QUEUE
        Instant instantSent = p_context.getFromTimestamp();
        Instant instantReceived = Instant.now();
        Duration durationBetweenSentReceived = Duration.between(instantSent, instantReceived);
        stageStats.recordSpentQueueTimeNanos(durationBetweenSentReceived.toNanos());

        // :: TIME BETWEEN STAGES:
        // ?: Is this NOT the Initial stage, AND it is a REPLY/REPLY_SUBSCRIPTION or NEXT?
        if ((!stageStats.isInitial()) &&
                ((incomingMessageType == MessageType.REPLY) || (incomingMessageType == MessageType.REPLY_SUBSCRIPTION)
                        || (incomingMessageType == MessageType.NEXT))) {
            // -> Yes, so then we should have extra-state for the time the previous stage's request/send happened
            String requestNodename = i_context.getIncomingSameStackHeightExtraState(EXTRA_STATE_REQUEST_NODENAME, String.class)
                    .orElse(null);
            boolean sameNode = stage.getParentEndpoint().getParentFactory().getFactoryConfig().getNodename()
                    .equals(requestNodename);
            // ?: Is this the same node? (Only record if so, since we know that there must be such timings)
            if (sameNode) {
                long requestNanoTime = i_context.getIncomingSameStackHeightExtraState(EXTRA_STATE_REQUEST_NANOS, Long.class)
                        .orElse(0L);
                stageStats.recordBetweenStagesTimeNanos(System.nanoTime() - requestNanoTime);
            }
        }

        // :: TIME INITIATOR TO TERMINATOR:
        // ?: Is this the initial stage, AND is it a Terminator
        if (stageStats.isInitial() && endpointStats.isTerminatorEndpoint()) {
            // -> Yes, initial and Terminator.
            boolean initiatedOnSameApp = stage.getParentEndpoint().getParentFactory()
                    .getFactoryConfig().getAppName().equals(p_context.getInitiatingAppName());
            // ?: Is this a REPLY/REPLY_SUBSCRIPTION?
            if ((i_context.getIncomingMessageType() == MessageType.REPLY)
                    || (i_context.getIncomingMessageType() == MessageType.REPLY_SUBSCRIPTION)) {
                // -> Yes, this is a reply - thus extra-state is employed
                // ?: Is this from the same app?
                if (initiatedOnSameApp) {
                    // -> Yes, initiated and received on same app - then only use if same nodename
                    String initiatorNodename = i_context.getIncomingSameStackHeightExtraState(
                            EXTRA_STATE_OR_SIDELOAD_INITIATOR_NODENAME, String.class).orElse(null);
                    boolean sameNode = stage.getParentEndpoint().getParentFactory().getFactoryConfig()
                            .getNodename().equals(initiatorNodename);

                    // ?: Is this the same node? (Only record if so, since we know that there must be such timings)
                    if (sameNode) {
                        // -> Yes, same node, and should thus also have extra-state present
                        Long initiatedNanoTime = i_context.getIncomingSameStackHeightExtraState(
                                EXTRA_STATE_OR_SIDELOAD_INITIATOR_NANOS, Long.class).orElse(0L);
                        long nanosSinceInit = System.nanoTime() - initiatedNanoTime;
                        endpointStats.recordInitiatorToTerminatorTimeNanos(incomingMessageRepresentation,
                                nanosSinceInit, true);
                    }
                }
                else {
                    // -> No, not from same app, so use Mats' initiatedTimestamp (which is millis-since-epoch).
                    long millisSinceInit = System.currentTimeMillis() - p_context.getInitiatingTimestamp()
                            .toEpochMilli();
                    endpointStats.recordInitiatorToTerminatorTimeNanos(incomingMessageRepresentation,
                            millisSinceInit * 1_000_000L, false);
                }
            }
            else {
                // -> No, this is not a REPLY, thus it is SEND or PUBLISH - thus sideloads is employed
                if (initiatedOnSameApp) {
                    String initiatorNodename = p_context.getString(EXTRA_STATE_OR_SIDELOAD_INITIATOR_NODENAME);
                    boolean sameNode = stage.getParentEndpoint().getParentFactory().getFactoryConfig()
                            .getNodename().equals(initiatorNodename);

                    // ?: Is this the same node? (Only record if so, since we know that there must be such timings)
                    if (sameNode) {
                        // -> Yes, same node, and thus we should also have nano timing in sideload
                        long initiatedNanoTime = Long.parseLong(p_context.getString(
                                EXTRA_STATE_OR_SIDELOAD_INITIATOR_NANOS));
                        long nanosSinceInit = System.nanoTime() - initiatedNanoTime;
                        endpointStats.recordInitiatorToTerminatorTimeNanos(incomingMessageRepresentation,
                                nanosSinceInit, true);
                    }
                }
                else {
                    // -> No, not from same app, so use Mats' initiatedTimestamp (which is millis-since-epoch).
                    long millisSinceInit = System.currentTimeMillis() - p_context.getInitiatingTimestamp()
                            .toEpochMilli();
                    endpointStats.recordInitiatorToTerminatorTimeNanos(incomingMessageRepresentation,
                            millisSinceInit * 1_000_000L, false);
                }
            }
        }
    }

    @Override
    public void stageInterceptOutgoingMessages(StageInterceptOutgoingMessageContext context) {
        MatsStage<?, ?, ?> stage = context.getStage();
        // Must get-or-create this first, since this is what creates the StageStats.
        getOrCreateEndpointStatsImpl(stage.getParentEndpoint());

        // Get the StageStats
        StageStatsImpl stageStats = _stages.get(stage);

        // :: Add extra-state on outgoing messages for BETWEEN STAGES + ENDPOINT TOTAL PROCESSING TIME
        List<MatsEditableOutgoingMessage> outgoingMessages = context.getOutgoingMessages();
        for (MatsEditableOutgoingMessage msg : outgoingMessages) {
            // ?: Is this a REQUEST or a NEXT call? (those have a subsequent N+1 stage)
            MessageType messageType = msg.getMessageType();
            if ((messageType == MessageType.REQUEST) || (messageType == MessageType.NEXT)) {
                // :: TIME BETWEEN STAGES:
                // Need to record the REQUEST timestamp for the subsequent stage which gets the REPLY
                // NOTE: This is /overwriting/ the state between each stage, as it is only needed between each
                // stageN and stageN+1.
                msg.setSameStackHeightExtraState(EXTRA_STATE_REQUEST_NANOS, System.nanoTime());
                msg.setSameStackHeightExtraState(EXTRA_STATE_REQUEST_NODENAME,
                        stage.getParentEndpoint().getParentFactory().getFactoryConfig().getNodename());

                // :: TIME ENDPOINT TOTAL PROCESSING (Only for initial stage - to the finishing stage):
                // NOTICE: Storing nanos - but also the node name, so that we'll only use the timing if it is
                // same node exiting (or stopping, in case of terminator).
                // NOTE: This is only set on initial, and stays with the endpoint's stages - until finished.
                if (stageStats.isInitial()) {
                    msg.setSameStackHeightExtraState(EXTRA_STATE_ENDPOINT_ENTER_NANOS, context.getStartedNanoTime());
                    msg.setSameStackHeightExtraState(EXTRA_STATE_ENDPOINT_ENTER_NODENAME,
                            stage.getParentEndpoint().getParentFactory().getFactoryConfig().getNodename());
                }
            }
        }
    }

    @Override
    public void stageCompleted(StageCompletedContext context) {
        MatsStage<?, ?, ?> stage = context.getStage();
        // Must get-or-create this first, since this is what creates the StageStats.
        EndpointStatsImpl endpointStats = getOrCreateEndpointStatsImpl(stage.getParentEndpoint());

        // Get the StageStats
        StageStatsImpl stageStats = _stages.get(stage);

        // :: TIME STAGE TOTAL EXECUTION:
        stageStats.recordStageTotalExecutionTimeNanos(context.getTotalExecutionNanos());

        // :: COUNT PROCESS RESULTS:
        StageProcessResult stageProcessResult = context.getStageProcessResult();
        stageStats.recordProcessResult(stageProcessResult);

        // :: TIME ENDPOINT TOTAL PROCESSING:
        // ?: Is this a "finishing process result", i.e. either REPLY (service) or NONE (terminator/terminating flow)?
        if (stageProcessResult == StageProcessResult.REPLY
                || (stageProcessResult == StageProcessResult.REPLY_SUBSCRIPTION)
                || (stageProcessResult == StageProcessResult.NONE)) {
            // -> Yes, "exiting process result" - record endpoint total processing time
            // ?: Is this the initial stage?
            if (stageStats.isInitial()) {
                // -> Yes, initial - thus this is the only stage processed
                endpointStats.recordTotalEndpointProcessingTimeNanos(System.nanoTime() - context.getStartedNanoTime());
            }
            else {
                // -> No, not initial stage, been through one or more request/reply calls
                String enterNodename = context.getIncomingSameStackHeightExtraState(EXTRA_STATE_ENDPOINT_ENTER_NODENAME, String.class)
                        .orElse(null);
                // ?: Was the initial message on the same node as this processing?
                if (stage.getParentEndpoint().getParentFactory().getFactoryConfig().getNodename().equals(
                        enterNodename)) {
                    // -> Yes, same node - so then the System.nanoTime() is relevant to compare
                    Long enterNanoTime = context.getIncomingSameStackHeightExtraState(EXTRA_STATE_ENDPOINT_ENTER_NANOS, Long.class)
                            .orElse(0L);
                    endpointStats.recordTotalEndpointProcessingTimeNanos(System.nanoTime() - enterNanoTime);
                }
            }
        }

        // :: COUNT OUTGOING MESSAGES:
        List<MatsSentOutgoingMessage> outgoingMessages = context.getOutgoingMessages();
        for (MatsSentOutgoingMessage msg : outgoingMessages) {
            stageStats.recordOutgoingMessage(msg.getMessageType(), msg.getTo(),
                    msg.getData() == null ? null : msg.getData().getClass(),
                    msg.getInitiatingAppName(), msg.getInitiatorId());
        }
    }

    private EndpointStatsImpl getOrCreateEndpointStatsImpl(MatsEndpoint<?, ?> endpoint) {
        return _endpoints.computeIfAbsent(endpoint, (v) -> {
            EndpointStatsImpl epStats = new EndpointStatsImpl(endpoint, _numSamples);
            // Side-effect: Put all the stages into the stages-map, for direct access.
            _stages.putAll(epStats.getStagesMap());
            return epStats;
        });
    }

    // ======================================================================================================
    // ===== Impl: InitiatorStats, EndpointStats, StageStats

    static class InitiatorStatsImpl implements InitiatorStats {
        private final RingBuffer_Long _totalExecutionTimeNanos;

        private final AtomicInteger _numberOfAddedOutgoingMessageCounts = new AtomicInteger(0);
        private final ConcurrentHashMap<OutgoingMessageRepresentationImpl, AtomicLong> _outgoingMessageCounts = new ConcurrentHashMap<>();

        public InitiatorStatsImpl(int numSamples) {
            _totalExecutionTimeNanos = new RingBuffer_Long(numSamples);
        }

        public void recordTotalExecutionTimeNanos(long totalExecutionTimeNanos) {
            _totalExecutionTimeNanos.addEntry(totalExecutionTimeNanos);
        }

        private void recordOutgoingMessage(MessageType messageType, String to, Class<?> messageClass,
                String initiatingAppName, String initiatorId) {
            // ?: If we've added more than some Max of such entries, we stop adding.
            // NOTE: Known race condition: This is purely a "best effort" way to try to avoid adding unlimited number
            // of such measures, and if we overshoot, that is not a problem.
            if (_numberOfAddedOutgoingMessageCounts.get() >= MAX_NUMBER_OF_DYNAMIC_ENTRIES) {
                log.warn(createWarnMessageString("counts on outgoing messages from initiators",
                        "count per initiatorId/msgType/to per MatsInitiator",
                        _numberOfAddedOutgoingMessageCounts.get(),
                        initiatorId + "@" + initiatingAppName));
                return;
            }
            OutgoingMessageRepresentationImpl msg = new OutgoingMessageRepresentationImpl(messageType, to,
                    messageClass, initiatingAppName, initiatorId);
            AtomicLong count = _outgoingMessageCounts.computeIfAbsent(msg, x -> {
                // Increment the OOM-avoidance gadget.
                _numberOfAddedOutgoingMessageCounts.incrementAndGet();
                // Create the actual counter
                return new AtomicLong();
            });
            count.incrementAndGet();
        }

        @Override
        public StatsSnapshot getTotalExecutionTimeNanos() {
            return new StatsSnapshotImpl(_totalExecutionTimeNanos.getValuesCopy(),
                    _totalExecutionTimeNanos.getNumObservations());
        }

        @Override
        public NavigableMap<OutgoingMessageRepresentation, Long> getOutgoingMessageCounts() {
            NavigableMap<OutgoingMessageRepresentation, Long> ret = new TreeMap<>();
            _outgoingMessageCounts.forEach((k, v) -> ret.put(k, v.get()));
            return ret;
        }
    }

    public static class EndpointStatsImpl implements EndpointStats {
        private final boolean _isTerminator;
        private final Map<MatsStage<?, ?, ?>, StageStatsImpl> _stagesMap;
        private final List<StageStats> _stageStats_unmodifiable;

        private final RingBuffer_Long _totalEndpointProcessingTimeNanos;

        private final int _sampleReservoirSize;
        private final AtomicInteger _numberOfAddedInitiatorToTerminatorEntries = new AtomicInteger(0);
        private final ConcurrentHashMap<IncomingMessageRepresentation, InitiatorToTerminatorStatsHolder> _initiatorToTerminatorTimeNanos = new ConcurrentHashMap<>();

        private EndpointStatsImpl(MatsEndpoint<?, ?> endpoint, int sampleReservoirSize) {
            Class<?> replyClass = endpoint.getEndpointConfig().getReplyClass();
            // ?: Is the replyClass Void.TYPE/void.class (or Void.class for legacy reasons).
            _isTerminator = (replyClass == Void.TYPE) || (replyClass == Void.class);
            List<? extends MatsStage<?, ?, ?>> stages = endpoint.getStages();
            _stagesMap = new HashMap<>(stages.size());
            List<StageStats> stageStatsList = new ArrayList<>(stages.size());
            // :: Create StateStatsImpl for each Stage of the Endpoint.
            // Note: No use in adding "between stages time millis" sample reservoir for the first stage..
            // This is handled in the StageStatsImpl constructor
            for (int i = 0; i < stages.size(); i++) {
                MatsStage<?, ?, ?> stage = stages.get(i);
                StageStatsImpl stageStats = new StageStatsImpl(sampleReservoirSize, i);
                _stagesMap.put(stage, stageStats);
                stageStatsList.add(stageStats);
            }
            _stageStats_unmodifiable = Collections.unmodifiableList(stageStatsList);

            _sampleReservoirSize = sampleReservoirSize;
            _totalEndpointProcessingTimeNanos = new RingBuffer_Long(sampleReservoirSize);
        }

        public Map<MatsStage<?, ?, ?>, StageStatsImpl> getStagesMap() {
            return _stagesMap;
        }

        private static class InitiatorToTerminatorStatsHolder {
            private final RingBuffer_Long _ringBuffer;
            private final AtomicBoolean _sameNode = new AtomicBoolean(false);

            public InitiatorToTerminatorStatsHolder(RingBuffer_Long ringBuffer) {
                _ringBuffer = ringBuffer;
            }
        }

        private void recordInitiatorToTerminatorTimeNanos(IncomingMessageRepresentation incomingMessageRepresentation,
                long initiatorToTerminatorTimeNanos, boolean sameNode) {
            // ?: If we've added more than some Max of such entries, we stop adding.
            // NOTE: Known race condition: This is purely a "best effort" way to try to avoid adding unlimited number
            // of such measures, and if we overshoot, that is not a problem.
            if (_numberOfAddedInitiatorToTerminatorEntries.get() >= MAX_NUMBER_OF_DYNAMIC_ENTRIES) {
                log.warn(createWarnMessageString("statistics on timing between initiations and terminators",
                        "statistics-gatherer per initiatorId/terminatorId",
                        _numberOfAddedInitiatorToTerminatorEntries.get(),
                        incomingMessageRepresentation.getInitiatorId() + "@"
                                + incomingMessageRepresentation.getInitiatingAppName()));
                return;
            }

            InitiatorToTerminatorStatsHolder ringBufferHolder = _initiatorToTerminatorTimeNanos
                    .computeIfAbsent(incomingMessageRepresentation, s -> {
                        // Increment the OOM-avoidance gadget.
                        _numberOfAddedInitiatorToTerminatorEntries.incrementAndGet();
                        // Create the actual RingBuffer.
                        return new InitiatorToTerminatorStatsHolder(new RingBuffer_Long(_sampleReservoirSize));
                    });
            boolean alreadySeenSameNode = ringBufferHolder._sameNode.get();
            // ?: Have we already seen sameNodes?
            if (alreadySeenSameNode) {
                // -> Yes, already seen sameNode
                // ?: Is this a sameNode measurement?
                if (sameNode) {
                    // -> Yes, this is a sameNode measurement, so store it.
                    ringBufferHolder._ringBuffer.addEntry(initiatorToTerminatorTimeNanos);
                }
                else {
                    // -> No, this is NOT a sameNode measurement, so DROP it - we only want the good stuff!
                    return;
                }
            }
            else {
                // -> No, we haven't seen sameNode measurements before
                // We want to store the measurement at any rate (both non-sameNode, and good-stuff sameNode)
                ringBufferHolder._ringBuffer.addEntry(initiatorToTerminatorTimeNanos);
                // ?: Is this a sameNode measurement?
                if (sameNode) {
                    // Since we've now seen a sameNode measurement, we only want such good-stuff going forward
                    ringBufferHolder._sameNode.set(true);
                }
            }
        }

        private void recordTotalEndpointProcessingTimeNanos(long totalEndpointProcessingTimeNanos) {
            _totalEndpointProcessingTimeNanos.addEntry(totalEndpointProcessingTimeNanos);
        }

        @Override
        public boolean isTerminatorEndpoint() {
            return _isTerminator;
        }

        @Override
        public List<StageStats> getStagesStats() {
            return _stageStats_unmodifiable;
        }

        @Override
        public StageStats getStageStats(MatsStage<?, ?, ?> stage) {
            return _stagesMap.get(stage);
        }

        @Override
        public StatsSnapshot getTotalEndpointProcessingTimeNanos() {
            return new StatsSnapshotImpl(_totalEndpointProcessingTimeNanos.getValuesCopy(),
                    _totalEndpointProcessingTimeNanos.getNumObservations());
        }

        @Override
        public NavigableMap<IncomingMessageRepresentation, StatsSnapshot> getInitiatorToTerminatorTimeNanos() {
            NavigableMap<IncomingMessageRepresentation, StatsSnapshot> ret = new TreeMap<>();
            _initiatorToTerminatorTimeNanos.forEach((k, v) -> ret.put(k, new StatsSnapshotImpl(v._ringBuffer
                    .getValuesCopy(), v._ringBuffer.getNumObservations())));
            return ret;
        }
    }

    static class StageStatsImpl implements StageStats {
        private final RingBuffer_Long _spentQueueTimeNanos;
        private final RingBuffer_Long _betweenStagesTimeNanos;
        private final RingBuffer_Long _totalExecutionTimeNanos;
        private final int _index;
        private final boolean _initial;

        private final AtomicInteger _numberOfIncomingMessageCounts = new AtomicInteger(0);
        private final ConcurrentHashMap<IncomingMessageRepresentation, AtomicLong> _incomingMessageCounts = new ConcurrentHashMap<>();

        private final AtomicInteger _numberOfOutgoingMessageCounts = new AtomicInteger(0);
        private final ConcurrentHashMap<OutgoingMessageRepresentation, AtomicLong> _outgoingMessageCounts = new ConcurrentHashMap<>();

        private final ConcurrentHashMap<StageProcessResult, AtomicLong> _processResultCounts = new ConcurrentHashMap<>();

        public StageStatsImpl(int sampleReservoirSize, int index) {
            _spentQueueTimeNanos = new RingBuffer_Long(sampleReservoirSize);
            // Note: No use in adding "between stages time millis" sample reservoir for the first stage..
            _betweenStagesTimeNanos = (index == 0)
                    ? null
                    : new RingBuffer_Long(sampleReservoirSize);
            _totalExecutionTimeNanos = new RingBuffer_Long(sampleReservoirSize);
            _index = index;
            _initial = index == 0;
        }

        private void recordSpentQueueTimeNanos(long betweenStagesTimeNanos) {
            _spentQueueTimeNanos.addEntry(betweenStagesTimeNanos);
        }

        private void recordBetweenStagesTimeNanos(long betweenStagesTimeNanos) {
            _betweenStagesTimeNanos.addEntry(betweenStagesTimeNanos);
        }

        private void recordStageTotalExecutionTimeNanos(long totalExecutionTimeNanos) {
            _totalExecutionTimeNanos.addEntry(totalExecutionTimeNanos);
        }

        private void recordIncomingMessage(IncomingMessageRepresentation incomingMessageRepresentation) {
            // ?: If we've added more than some Max of such entries, we stop adding.
            // NOTE: Known race condition: This is purely a "best effort" way to try to avoid adding unlimited number
            // of such measures, and if we overshoot, that is not a problem.
            if (_numberOfIncomingMessageCounts.get() >= MAX_NUMBER_OF_DYNAMIC_ENTRIES) {
                log.warn(createWarnMessageString("counts on incoming messages",
                        "count per initiatorId/msgType/from per MatsStage",
                        _numberOfIncomingMessageCounts.get(),
                        incomingMessageRepresentation.getInitiatorId() + "@"
                                + incomingMessageRepresentation.getInitiatingAppName()));
                return;
            }
            AtomicLong count = _incomingMessageCounts.computeIfAbsent(incomingMessageRepresentation,
                    x -> {
                        // Increment the OOM-avoidance gadget.
                        _numberOfIncomingMessageCounts.incrementAndGet();
                        // Create the counter
                        return new AtomicLong();
                    });
            count.incrementAndGet();
        }

        private void recordProcessResult(StageProcessResult stageProcessResult) {
            AtomicLong count = _processResultCounts.computeIfAbsent(stageProcessResult, x -> new AtomicLong());
            count.incrementAndGet();
        }

        private void recordOutgoingMessage(MessageType messageType, String to, Class<?> messageClass,
                String initiatingAppName, String initiatorId) {
            // ?: If we've added more than some Max of such entries, we stop adding.
            // NOTE: Known race condition: This is purely a "best effort" way to try to avoid adding unlimited number
            // of such measures, and if we overshoot, that is not a problem.
            if (_numberOfOutgoingMessageCounts.get() >= MAX_NUMBER_OF_DYNAMIC_ENTRIES) {
                log.warn(createWarnMessageString("counts on outgoing messages",
                        "count per initiatorId/msgType/to per MatsStage",
                        _numberOfOutgoingMessageCounts.get(),
                        initiatorId + "@" + initiatingAppName));
                return;
            }
            OutgoingMessageRepresentationImpl msg = new OutgoingMessageRepresentationImpl(messageType, to,
                    messageClass, initiatingAppName, initiatorId);
            AtomicLong count = _outgoingMessageCounts.computeIfAbsent(msg, x -> {
                // Increment the OOM-avoidance gadget.
                _numberOfOutgoingMessageCounts.incrementAndGet();
                // Create the counter
                return new AtomicLong();
            });
            count.incrementAndGet();
        }

        @Override
        public int getIndex() {
            return _index;
        }

        @Override
        public boolean isInitial() {
            return _initial;
        }

        @Override
        public StatsSnapshot getSpentQueueTimeNanos() {
            return new StatsSnapshotImpl(_spentQueueTimeNanos.getValuesCopy(),
                    _spentQueueTimeNanos.getNumObservations());
        }

        @Override
        public Optional<StatsSnapshot> getBetweenStagesTimeNanos() {
            if (_betweenStagesTimeNanos == null) {
                return Optional.empty();
            }
            return Optional.of(new StatsSnapshotImpl(_betweenStagesTimeNanos.getValuesCopy(),
                    _betweenStagesTimeNanos.getNumObservations()));
        }

        @Override
        public StatsSnapshot getStageTotalExecutionTimeNanos() {
            return new StatsSnapshotImpl(_totalExecutionTimeNanos.getValuesCopy(),
                    _totalExecutionTimeNanos.getNumObservations());
        }

        @Override
        public NavigableMap<IncomingMessageRepresentation, Long> getIncomingMessageCounts() {
            NavigableMap<IncomingMessageRepresentation, Long> ret = new TreeMap<>();
            _incomingMessageCounts.forEach((k, v) -> ret.put(k, v.get()));
            return ret;
        }

        @Override
        public NavigableMap<StageProcessResult, Long> getProcessResultCounts() {
            NavigableMap<StageProcessResult, Long> ret = new TreeMap<>();
            _processResultCounts.forEach((k, v) -> ret.put(k, v.get()));
            return ret;
        }

        @Override
        public NavigableMap<OutgoingMessageRepresentation, Long> getOutgoingMessageCounts() {
            NavigableMap<OutgoingMessageRepresentation, Long> ret = new TreeMap<>();
            _outgoingMessageCounts.forEach((k, v) -> ret.put(k, v.get()));
            return ret;
        }
    }

    private static String createWarnMessageString(String what, String countDescription, int count, String initiatorId) {
        return "Too many measures: We try to do " + what + ". However, this requires us to keep a " + countDescription
                + ". Currently, we've added [" + count + "], and this is above the threshold of ["
                + MAX_NUMBER_OF_DYNAMIC_ENTRIES + "], so we've stopped adding more. InitiatorId of this dropped one: ["
                + initiatorId + "] - notice that the processing goes through just fine, we'll just not gather"
                + " statistics for it. NOTE: This typically means that you are wrongly creating a dynamic initiatorId,"
                + " e.g. adding some Id to the String on the init.from(<initiatorId>) call. Don't do that, such an id"
                + " should go into the traceId";
    }

    // ======================================================================================================
    // ===== Impl: IncomingMessageRepresentation & OutgoingMessageRepresentation

    public static class IncomingMessageRepresentationImpl implements IncomingMessageRepresentation {
        private final MessageType _messageType;
        private final String _fromAppName;
        private final String _fromStageId;
        private final String _initiatingAppName;
        private final String _initiatorId;

        public IncomingMessageRepresentationImpl(MessageType messageType, String fromAppName, String fromStageId,
                String initiatingAppName, String initiatorId) {
            _messageType = messageType;
            _fromAppName = fromAppName;
            _fromStageId = fromStageId;
            _initiatingAppName = initiatingAppName;
            _initiatorId = initiatorId;
        }

        public MessageType getMessageType() {
            return _messageType;
        }

        public String getFromAppName() {
            return _fromAppName;
        }

        public String getFromStageId() {
            return _fromStageId;
        }

        public String getInitiatingAppName() {
            return _initiatingAppName;
        }

        public String getInitiatorId() {
            return _initiatorId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IncomingMessageRepresentationImpl that = (IncomingMessageRepresentationImpl) o;
            return _messageType == that._messageType
                    && Objects.equals(_fromAppName, that._fromAppName)
                    && Objects.equals(_fromStageId, that._fromStageId)
                    && Objects.equals(_initiatingAppName, that._initiatingAppName)
                    && Objects.equals(_initiatorId, that._initiatorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(_messageType, _fromAppName, _fromStageId, _initiatingAppName, _initiatorId);
        }

        private static final Comparator<IncomingMessageRepresentation> COMPARATOR = Comparator
                .comparing(IncomingMessageRepresentation::getInitiatingAppName)
                .thenComparing(IncomingMessageRepresentation::getInitiatorId)
                .thenComparing(IncomingMessageRepresentation::getMessageType)
                .thenComparing(IncomingMessageRepresentation::getFromAppName)
                .thenComparing(IncomingMessageRepresentation::getFromStageId);

        @Override
        public int compareTo(IncomingMessageRepresentation o) {
            return COMPARATOR.compare(this, o);
        }
    }

    public static class OutgoingMessageRepresentationImpl implements OutgoingMessageRepresentation {
        private final MessageType _messageType;
        private final String _to;
        private final Class<?> _messageClass;
        private final String _initiatingAppName;
        private final String _initiatorId;

        public OutgoingMessageRepresentationImpl(MessageType messageType, String to, Class<?> messageClass,
                String initiatingAppName, String initiatorId) {
            _messageType = messageType;
            _to = to;
            _messageClass = messageClass;
            _initiatingAppName = initiatingAppName;
            _initiatorId = initiatorId;
        }

        @Override
        public MessageType getMessageType() {
            return _messageType;
        }

        @Override
        public String getTo() {
            return _to;
        }

        @Override
        public Class<?> getMessageClass() {
            return _messageClass;
        }

        @Override
        public String getInitiatingAppName() {
            return _initiatingAppName;
        }

        @Override
        public String getInitiatorId() {
            return _initiatorId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OutgoingMessageRepresentationImpl that = (OutgoingMessageRepresentationImpl) o;
            return _messageType == that._messageType
                    && Objects.equals(_to, that._to)
                    && Objects.equals(_messageClass, that._messageClass)
                    && Objects.equals(_initiatingAppName, that._initiatingAppName)
                    && Objects.equals(_initiatorId, that._initiatorId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(_messageType, _to, _messageClass, _initiatingAppName, _initiatorId);
        }

        private static final Comparator<OutgoingMessageRepresentation> COMPARATOR = Comparator
                .comparing(OutgoingMessageRepresentation::getInitiatingAppName)
                .thenComparing(OutgoingMessageRepresentation::getInitiatorId)
                .thenComparing(OutgoingMessageRepresentation::getMessageType)
                .thenComparing(OutgoingMessageRepresentation::getTo)
                .thenComparing(o -> o.getMessageClass() == null ? "null" : o.getMessageClass().getName());

        @Override
        public int compareTo(OutgoingMessageRepresentation o) {
            return COMPARATOR.compare(this, o);
        }
    }

    // ======================================================================================================
    // ===== Impl: StatsSnapshot, and RingBuffer_Long

    static class StatsSnapshotImpl implements StatsSnapshot {
        private final long[] _values;
        private final long _numObservations;

        /**
         * NOTE!! The values array shall be "owned" by this instance (i.e. copied out), and WILL be sorted here.
         */
        public StatsSnapshotImpl(long[] values, long numObservations) {
            _values = values;
            Arrays.sort(_values);
            _numObservations = numObservations;
        }

        @Override
        public long[] getSamples() {
            return _values;
        }

        @Override
        public long getValueAtPercentile(double percentile) {
            if (_values.length == 0) {
                return 0;
            }
            if (percentile == 0) {
                return _values[0];
            }
            if (percentile == 1) {
                return _values[_values.length - 1];
            }
            double index = percentile * (_values.length - 1);
            // ?: Is it a whole number?
            if (index == Math.rint(index)) {
                // -> Yes, whole number
                return _values[(int) index];
            }
            // E-> No, not whole number
            double firstIndex = Math.floor(index);
            double remainder = index - firstIndex;
            long first = _values[(int) firstIndex];
            long second = _values[((int) firstIndex) + 1];
            return (long) (first * (1 - remainder) + second * remainder);
        }

        @Override
        public long getNumObservations() {
            return _numObservations;
        }

        @Override
        public double getAverage() {
            // ?: Do we have no samples?
            if (_values.length == 0) {
                // -> Yes, no samples - return 0
                return 0;
            }
            long sum = 0;
            for (long value : _values) {
                sum += value;
            }
            return sum / (double) _values.length;
        }

        @Override
        public double getStdDev() {
            // ?: Do we have only 0 or 1 samples? ("sample mean" uses 'n - 1' as divisor)
            if (_values.length <= 1) {
                // -> Yes, no samples, or only 1 - return 0
                return 0;
            }
            double avg = getAverage();
            double sd = 0;
            for (long value : _values) {
                sd += Math.pow(((double) value) - avg, 2);
            }
            // Use /sample/ standard deviation (not population), i.e. "n - 1" as divisor.
            return Math.sqrt(sd / (double) (_values.length - 1));
        }
    }

    private static class RingBuffer_Long {
        private final long[] _values;
        private final int _sampleReservoirSize;
        private final LongAdder _numAdded;

        private int _bufferPos;

        private RingBuffer_Long(int sampleReservoirSize) {
            _values = new long[sampleReservoirSize];
            _sampleReservoirSize = sampleReservoirSize;
            _numAdded = new LongAdder();
            _bufferPos = 0;
        }

        void addEntry(long entry) {
            synchronized (this) {
                // First add at current pos
                _values[_bufferPos] = entry;
                // Now increase and wrap current pos
                _bufferPos = ++_bufferPos % _sampleReservoirSize;
            }
            // Keep tally of total number added
            _numAdded.increment();
        }

        long[] getValuesCopy() {
            // Note: There is a race here, but the error is always at most excluding one or a few samples - no problem.
            int numAdded = _numAdded.intValue();
            synchronized (this) {
                // ?: Already filled the ring?
                if (numAdded >= _sampleReservoirSize) {
                    // -> Yes, ring is filled.
                    // Use clone
                    return _values.clone();
                }
                // E-> no, ring is not filled, so we need to do partial copy
                long[] copy = new long[numAdded];
                System.arraycopy(_values, 0, copy, 0, numAdded);
                return copy;
            }
        }

        /**
         * @return the buffer size, i.e. max samples that will be kept, when older are discarded.
         */
        public int getSampleReservoirSize() {
            return _sampleReservoirSize;
        }

        /**
         * @return the number of samples in the buffer, which is capped by {@link #getSampleReservoirSize()}.
         */
        public int getSamplesInReservoir() {
            return Math.min(_numAdded.intValue(), _sampleReservoirSize);
        }

        /**
         * @return the total number of executions run - of which only the last {@link #getSampleReservoirSize()} is kept
         *         in the circular buffer.
         */
        public long getNumObservations() {
            return _numAdded.longValue();
        }
    }
}
