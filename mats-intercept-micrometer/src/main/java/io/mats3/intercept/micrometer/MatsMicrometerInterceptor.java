package io.mats3.intercept.micrometer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.api.intercept.CommonCompletedContext;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInterceptable;
import io.mats3.api.intercept.MatsMetricsInterceptor;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsStageInterceptor;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Builder;

/**
 * An interceptor that instruments a MatsFactory with metrics using the (Spring) Micrometer framework. If you provide a
 * {@link MeterRegistry}, it will employ this to create the metrics on, otherwise it employs the
 * {@link Metrics#globalRegistry}.
 * <p />
 * <b>Note: This interceptor (Micrometer Metrics) has special support in <code>JmsMatsFactory</code>: If present on the
 * classpath, it is automatically installed using the {@link #install(MatsInterceptable)} install method.</b> This
 * implies that it employs the {@link Metrics#globalRegistry Micrometer 'globalRegistry'}. If you rather want to supply
 * a specific registry, then install a different instance using the {@link #install(MatsInterceptable, MeterRegistry)}
 * method - the <code>JmsMatsFactory</code> will then remove the automatically installed, since it implements the
 * special marker-interface {@link MatsMetricsInterceptor} of which there can only be one instance installed.
 *
 * @author Endre Stølsvik - 2021-02-07 12:45 - http://endre.stolsvik.com
 */
public class MatsMicrometerInterceptor
        implements MatsMetricsInterceptor, MatsInitiateInterceptor, MatsStageInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MatsMicrometerInterceptor.class);

    public static final String LOG_PREFIX = "#MATSMETRICS# ";

    // :: Execution, init or stage
    public static final String TIMER_EXEC_TOTAL_NAME = "mats.exec.total";
    public static final String TIMER_EXEC_TOTAL_DESC = "Total time taken to execute init or stage";

    public static final String TIMER_EXEC_USER_LAMBDA_NAME = "mats.exec.userlambda";
    public static final String TIMER_EXEC_USER_LAMBDA_DESC = "Part of total time taken for user lambda";

    public static final String TIMER_EXEC_DB_COMMIT_NAME = "mats.exec.db.commit";
    public static final String TIMER_EXEC_DB_COMMIT_DESC = "Part of total time taken to commit database";

    public static final String TIMER_EXEC_MSGSYS_SEND_NAME = "mats.exec.msgsys.send";
    public static final String TIMER_EXEC_MSGSYS_SEND_DESC = "Part of total time taken to produce and send all messages"
            + " to message system";

    public static final String TIMER_EXEC_MSGSYS_COMMIT_NAME = "mats.exec.msgsys.commit";
    public static final String TIMER_EXEC_MSGSYS_COMMIT_DESC = "Part of total time taken to commit all messages"
            + " to message system";

    public static final String COUNT_OUTGOING_MESSAGES_NAME = "mats.exec.msgs";
    public static final String COUNT_OUTGOING_MESSAGES_DESC = "Number of outgoing messages from execution";

    // :: Per message
    public static final String SIZE_MSG_ENVELOPE_NAME = "mats.msg.envelope";
    public static final String SIZE_MSG_ENVELOPE_DESC = "Outgoing mats envelope full size";

    public static final String SIZE_MSG_WIRE_NAME = "mats.msg.wire";
    public static final String SIZE_MSG_WIRE_DESC = "Outgoing mats envelope wire size";

    public static final String TIMER_MSG_TOTAL_NAME = "mats.msg.total";
    public static final String TIMER_MSG_TOTAL_DESC = "Total time taken to produce, serialize and compress envelope,"
            + " and produce and send message";

    public static final String TIMER_MSG_MSGSYS_SEND_NAME = "mats.msg.msgsys.send";
    public static final String TIMER_MSG_MSGSYS_SEND_DESC = "From total, time taken to produce and send message";

    /**
     * This is a cardinality-explosion-avoidance limit in case of wrongly used initiatorIds. These are not supposed to
     * be dynamic, but there is nothing hindering a user from creating a new initiatorId per initiation. Thus, if we go
     * above a certain number of such entries, we stop adding.
     * <p />
     * Value is 200.
     */
    public static final int MAX_NUMBER_OF_METRICS = 200;

    private final MeterRegistry _meterRegistry;

    private final LazyPopulatedStaticMap<ExecutionMetrics> _executionMetricsCache;
    private final LazyPopulatedStaticMap<MessageMetrics> _messageMetricsCache;

    private MatsMicrometerInterceptor(MeterRegistry meterRegistry) {
        _meterRegistry = meterRegistry;
        _executionMetricsCache = new LazyPopulatedStaticMap<>(args -> new ExecutionMetrics(_meterRegistry,
                args[0], args[1], args[2], args[3], args[4]));
        _messageMetricsCache = new LazyPopulatedStaticMap<>(args -> new MessageMetrics(_meterRegistry,
                args[0], args[1], args[2], args[3], args[4], args[5]));

    }

    /**
     * Creates a {@link MatsMicrometerInterceptor} employing the provided {@link MeterRegistry}, and installs it as a
     * singleton on the provided {@link MatsInterceptable} (which most probably is a {@link MatsFactory}).
     *
     * @param matsInterceptableMatsFactory
     *            the {@link MatsInterceptable} to install on (probably a {@link MatsFactory}.
     * @param meterRegistry
     *            the Micrometer {@link MeterRegistry} to create meters on.
     * @return the {@link MatsMicrometerInterceptor} instance which was installed as singleton.
     */
    public static MatsMicrometerInterceptor install(
            MatsInterceptable matsInterceptableMatsFactory,
            MeterRegistry meterRegistry) {
        MatsMicrometerInterceptor metrics = new MatsMicrometerInterceptor(meterRegistry);
        matsInterceptableMatsFactory.addInitiationInterceptor(metrics);
        matsInterceptableMatsFactory.addStageInterceptor(metrics);
        return metrics;
    }

    private static final MatsMicrometerInterceptor GLOBAL_REGISTRY_INSTANCE = new MatsMicrometerInterceptor(
            Metrics.globalRegistry);

    /**
     * Installs a singleton instance of {@link MatsMicrometerInterceptor} which employs the
     * {@link Metrics#globalRegistry}, on the provided {@link MatsInterceptable} (which most probably is a
     * {@link MatsFactory}).
     *
     * @param matsInterceptable
     *            the {@link MatsInterceptable} to install on (probably a {@link MatsFactory}.
     * @return the {@link MatsMicrometerInterceptor} instance which was installed as singleton.
     */
    public static MatsMicrometerInterceptor install(MatsInterceptable matsInterceptable) {
        matsInterceptable.addInitiationInterceptor(GLOBAL_REGISTRY_INSTANCE);
        matsInterceptable.addStageInterceptor(GLOBAL_REGISTRY_INSTANCE);
        return GLOBAL_REGISTRY_INSTANCE;
    }

    /**
     * Concurrent, lazy build-up, permanent cache. Using a plain HashMap, albeit with volatile reference. When a new
     * item needs to be added, the entire map is recreated before setting the new instance (so that any ongoing lookup
     * won't be affected by the put) - but this creation is done with "double-checked locking" inside a synchronized
     * block. Could have used a ConcurrentHashMap, but since I believe I am so clever, I'll make a stab at making a
     * faster variant. The thinking is that since this Map will have a volley of puts soon after startup, and then
     * forever be static, this might make sense: a volatile, non-contended read of the ref, and then a plain HashMap
     * lookup.
     */
    private static class LazyPopulatedStaticMap<V> {
        private volatile Map<List<String>, V> _cache = Collections.emptyMap();

        private final Function<String[], V> _creator;

        public LazyPopulatedStaticMap(Function<String[], V> creator) {
            _creator = creator;
        }

        private V getOrCreate(String... args) {
            List<String> key = Arrays.asList(args);
            V v = _cache.get(key);
            // ?: Did we find it from cache?
            if (v != null) {
                // -> Yes, so return it!
                return v;
            }
            // E-> value was null, now go for "double-checked locking" to create it.
            synchronized (this) {
                // ----- From now on, the reference to _cache won't change until we set it, because we must be within
                // the synchronized block to do the setting.
                Map<List<String>, V> oldCache = _cache;

                // First check the size - if too big, we stop making new ones
                if (oldCache.size() >= MAX_NUMBER_OF_METRICS) {
                    log.warn(LOG_PREFIX + "Cardinality explosion avoidance: When about to create metrics object for "
                            + key + ", we found that there already is [" + MAX_NUMBER_OF_METRICS + "] present,"
                            + " thus won't create it. You should find the offending code (probably using dynamic"
                            + " initiatorIds somewhere) and fix it.");
                    return null;
                }

                // ?: Still not in the currently present reference?
                V v2 = oldCache.get(key);
                if (v2 != null) {
                    return v2;
                }
                // E-> It was still not created, and now we "own" the '_cache' ref while we go about creating new.
                // The existing instance of the Map will never "move", so we can safely add all entries from it
                Map<List<String>, V> newCache = new HashMap<>(oldCache);
                // Create the new entry
                V newV = _creator.apply(args);
                // Add the new entry
                newCache.put(key, newV);
                // Overwrite the volatile reference to cache with this new cache.
                _cache = newCache;
                // Return the just created value
                return newV;
            }
        }
    }

    private static class ExecutionMetrics {
        private final Timer _timer_TotalTime;
        private final Timer _timer_UserLambda;
        private final Timer _timer_DbCommit;
        private final Timer _timer_MsgSend;
        private final Timer _timer_MsgCommit;

        DistributionSummary _count_Messages;

        private Timer addTagsAndRegister(Builder builder, MeterRegistry meterRegistry, String dispatchType,
                String initiatingAppName, String initiatorName, String initiatorId, String stageId) {
            return builder.tag("dispatch", dispatchType)
                    .tag("initiatingAppName", initiatingAppName)
                    .tag("initiatorName", initiatorName)
                    .tag("initiatorId", initiatorId)
                    .tag("stageId", stageId)
                    .register(meterRegistry);
        }

        ExecutionMetrics(MeterRegistry meterRegistry, String dispatchType,
                String initiatingAppName, String initiatorName, String initiatorId, String stageId) {
            _timer_TotalTime = addTagsAndRegister(Timer.builder(TIMER_EXEC_TOTAL_NAME)
                    .description(TIMER_EXEC_TOTAL_DESC), meterRegistry, dispatchType,
                    initiatingAppName, initiatorName, initiatorId, stageId);

            _timer_UserLambda = addTagsAndRegister(Timer.builder(TIMER_EXEC_USER_LAMBDA_NAME)
                    .description(TIMER_EXEC_USER_LAMBDA_DESC), meterRegistry, dispatchType,
                    initiatingAppName, initiatorName, initiatorId, stageId);

            _timer_DbCommit = addTagsAndRegister(Timer.builder(TIMER_EXEC_DB_COMMIT_NAME)
                    .description(TIMER_EXEC_DB_COMMIT_DESC), meterRegistry, dispatchType,
                    initiatingAppName, initiatorName, initiatorId, stageId);

            _timer_MsgSend = addTagsAndRegister(Timer.builder(TIMER_EXEC_MSGSYS_SEND_NAME)
                    .description(TIMER_EXEC_MSGSYS_SEND_DESC), meterRegistry, dispatchType,
                    initiatingAppName, initiatorName, initiatorId, stageId);

            _timer_MsgCommit = addTagsAndRegister(Timer.builder(TIMER_EXEC_MSGSYS_COMMIT_NAME)
                    .description(TIMER_EXEC_MSGSYS_COMMIT_DESC), meterRegistry, dispatchType,
                    initiatingAppName, initiatorName, initiatorId, stageId);

            _count_Messages = DistributionSummary.builder(COUNT_OUTGOING_MESSAGES_NAME)
                    .tag("dispatch", dispatchType)
                    .tag("initiatingAppName", initiatingAppName)
                    .tag("initiatorName", initiatorName)
                    .tag("initiatorId", initiatorId)
                    .tag("stageId", stageId)
                    .baseUnit("quantity")
                    .description(COUNT_OUTGOING_MESSAGES_DESC)
                    .register(meterRegistry);
        }

        void registerMeasurements(CommonCompletedContext ctx) {
            _timer_TotalTime.record(ctx.getTotalExecutionNanos(), TimeUnit.NANOSECONDS);
            _timer_UserLambda.record(ctx.getUserLambdaNanos(), TimeUnit.NANOSECONDS);
            _timer_DbCommit.record(ctx.getDbCommitNanos(), TimeUnit.NANOSECONDS);
            _timer_MsgSend.record(ctx.getSumMessageSystemProductionAndSendNanos(), TimeUnit.NANOSECONDS);
            _timer_MsgCommit.record(ctx.getMessageSystemCommitNanos(), TimeUnit.NANOSECONDS);
            _count_Messages.record(ctx.getOutgoingMessages().size());
        }
    }

    private static class MessageMetrics {
        private final DistributionSummary size_Envelope;
        private final DistributionSummary size_Wire;
        private final Timer time_Total;
        private final Timer time_MsgSys;

        MessageMetrics(MeterRegistry meterRegistry, String dispatchType,
                String initiatingAppName, String initiatorName, String msgInitiatorId, String from, String to) {
            size_Envelope = DistributionSummary.builder(SIZE_MSG_ENVELOPE_NAME)
                    .tag("dispatch", dispatchType)
                    .tag("initiatingAppName", initiatingAppName)
                    .tag("initiatorName", initiatorName)
                    .tag("initiatorId", msgInitiatorId)
                    .tag("from", from)
                    .tag("to", to)
                    .baseUnit("bytes")
                    .description(SIZE_MSG_ENVELOPE_DESC)
                    .register(meterRegistry);

            size_Wire = DistributionSummary.builder(SIZE_MSG_WIRE_NAME)
                    .tag("dispatch", dispatchType)
                    .tag("initiatingAppName", initiatingAppName)
                    .tag("initiatorName", initiatorName)
                    .tag("initiatorId", msgInitiatorId)
                    .tag("from", from)
                    .tag("to", to)
                    .baseUnit("bytes")
                    .description(SIZE_MSG_WIRE_DESC)
                    .register(meterRegistry);

            time_Total = Timer.builder(TIMER_MSG_TOTAL_NAME)
                    .tag("dispatch", dispatchType)
                    .tag("initiatingAppName", initiatingAppName)
                    .tag("initiatorName", initiatorName)
                    .tag("initiatorId", msgInitiatorId)
                    .tag("from", from)
                    .tag("to", to)
                    .description(TIMER_MSG_TOTAL_DESC)
                    .register(meterRegistry);

            time_MsgSys = Timer.builder(TIMER_MSG_MSGSYS_SEND_NAME)
                    .tag("dispatch", dispatchType)
                    .tag("initiatingAppName", initiatingAppName)
                    .tag("initiatorName", initiatorName)
                    .tag("initiatorId", msgInitiatorId)
                    .tag("from", from)
                    .tag("to", to)
                    .description(TIMER_MSG_MSGSYS_SEND_DESC)
                    .register(meterRegistry);
        }

        private void registerMeasurements(MatsSentOutgoingMessage msg) {
            size_Envelope.record(msg.getEnvelopeWireSize());
            size_Wire.record(msg.getEnvelopeWireSize());
            time_Total.record(msg.getEnvelopeProduceNanos()
                    + msg.getEnvelopeSerializationNanos()
                    + msg.getEnvelopeCompressionNanos()
                    + msg.getMessageSystemProduceAndSendNanos(), TimeUnit.NANOSECONDS);
            time_MsgSys.record(msg.getMessageSystemProduceAndSendNanos(), TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void initiateCompleted(InitiateCompletedContext ctx) {
        // :: INITIATION TIMINGS AND SIZES

        /*
         * NOTE: We're forced to use identical set of Tags (labels) across initiations and stages, due to this issue
         * https://github.com/micrometer-metrics/micrometer/issues/877. The underlying issue seems to be the official
         * Prometheus "client_java" which doesn't allow registering two distinct measures having the same name but
         * differing labels: https://github.com/prometheus/client_java/issues/696.
         *
         * Therefore, we add the "opposite" Tags with the empty string, bypassing the problem.
         */

        /*
         * The initiatorId is set by sending an actual message. If no message is sent from an initation lambda, there is
         * no initiatorId. But the lambda still executed, so we want to measure it. In case of multiple messages in one
         * initiation, each "initiatorId" (i.e. MatsInitiate.from(..)) might be different. Assuming that I have an idea
         * of how developers use the system, this should really not be a common situation. Therefore, we just pick the
         * first message's "from" (i.e. "initiatorId") to tag the timings with.
         */
        List<MatsSentOutgoingMessage> outgoingMessages = ctx.getOutgoingMessages();
        String commonInitiatorId = outgoingMessages.isEmpty()
                ? "_no_outgoing_messages_"
                : outgoingMessages.get(0).getInitiatorId();

        String initiatorName = ctx.getInitiator().getName();

        String initiatingAppName = ctx.getInitiator().getParentFactory().getFactoryConfig().getAppName();

        ExecutionMetrics executionMetrics = _executionMetricsCache.getOrCreate("init",
                initiatingAppName, initiatorName, commonInitiatorId, "");
        // ?: Did we get an ExecutionMetrics?
        if (executionMetrics != null) {
            // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
            executionMetrics.registerMeasurements(ctx);
        }

        // :: FOR-EACH-MESSAGE: RECORD TIMING AND SIZES
        // Note: here we use each message's "from" (i.e. "initiatorId").
        for (MatsSentOutgoingMessage msg : outgoingMessages) {

            MessageMetrics messageMetrics = _messageMetricsCache.getOrCreate("init",
                    initiatingAppName, initiatorName, msg.getInitiatorId(), "", msg.getTo());
            // ?: Did we get a MessageMetrics?
            if (messageMetrics != null) {
                // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
                messageMetrics.registerMeasurements(msg);
            }
        }
    }

    @Override
    public void stageReceived(StageReceivedContext ctx) {
        ProcessContext<Object> processContext = ctx.getProcessContext();

        long sumNanosPieces = ctx.getMessageSystemDeconstructNanos()
                + ctx.getEnvelopeDecompressionNanos()
                + ctx.getEnvelopeDeserializationNanos()
                + ctx.getMessageAndStateDeserializationNanos();

        // TODO: Interesting to metrics?
        // TODO: Time taken to deserialize etc.
        // TODO: Count received vs. completed. Will show if some stages often do retries.
    }

    @Override
    public void stageCompleted(StageCompletedContext ctx) {
        String initiatingAppName = ctx.getProcessContext().getInitiatingAppName();
        String initiatorId = ctx.getProcessContext().getInitiatorId();
        String stageId = ctx.getProcessContext().getStageId();

        ExecutionMetrics executionMetrics = _executionMetricsCache.getOrCreate("stage",
                initiatingAppName, "", initiatorId, stageId);
        // ?: Did we get an ExecutionMetrics?
        if (executionMetrics != null) {
            // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
            executionMetrics.registerMeasurements(ctx);
        }

        List<MatsSentOutgoingMessage> outgoingMessages = ctx.getOutgoingMessages();

        // :: FOR-EACH-MESSAGE: RECORD TIMING AND SIZES
        for (MatsSentOutgoingMessage msg : outgoingMessages) {

            /*
             * NOTICE: We use the current stageId as "from", not 'msg.getFrom()'. The reason here is both that a) in a
             * normal Mats flow situation (REQUEST/REPLY/NEXT/GOTO), it will be the same anyway, b) the latter can
             * potentially lead to cardinality explosion as it may be set by the user when initiating from a stage, but
             * c) most importantly that I believe it makes more sense in a metrics overview situation to see which
             * Endpoint/Stage that produces the message, instead of a somewhat arbitrary 'from' set inside the stage
             * lambda by the user. (I somewhat regret that it is possible to set the 'from' when initiating within a
             * stage).
             */
            MessageMetrics messageMetrics = _messageMetricsCache.getOrCreate("stage",
                    initiatingAppName, "", msg.getInitiatorId(), stageId, msg.getTo());
            // ?: Did we get a MessageMetrics?
            if (messageMetrics != null) {
                // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
                messageMetrics.registerMeasurements(msg);
            }
        }
    }
}
