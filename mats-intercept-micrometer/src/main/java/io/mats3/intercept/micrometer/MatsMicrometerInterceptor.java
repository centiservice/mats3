package io.mats3.intercept.micrometer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.DetachedProcessContext;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.api.intercept.CommonCompletedContext;
import io.mats3.api.intercept.CommonCompletedContext.MatsMeasurement;
import io.mats3.api.intercept.CommonCompletedContext.MatsTimingMeasurement;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInterceptable;
import io.mats3.api.intercept.MatsInterceptableMatsFactory;
import io.mats3.api.intercept.MatsMetricsInterceptor;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MessageType;
import io.mats3.api.intercept.MatsStageInterceptor;
import io.mats3.intercept.micrometer.MatsMicrometerInterceptor.ExecutionMetrics.ExecutionMetricsParams;
import io.mats3.intercept.micrometer.MatsMicrometerInterceptor.MessageMetrics.MessageMetricsParams;
import io.mats3.intercept.micrometer.MatsMicrometerInterceptor.ReceivedMetrics.ReceivedMetricsParams;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Builder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

/**
 * An interceptor that instruments a MatsFactory with metrics using the (Spring) Micrometer framework. If you provide a
 * {@link MeterRegistry}, it will employ this to create the metrics on, otherwise it employs the
 * {@link Metrics#globalRegistry}.
 * <p />
 * <b>Note: This interceptor (Micrometer Metrics) has special support in <code>JmsMatsFactory</code>: If present on the
 * classpath, it is automatically installed using the {@link #install(MatsInterceptableMatsFactory)} install method.</b>
 * This implies that it employs the {@link Metrics#globalRegistry Micrometer 'globalRegistry'} - and 'includeAllTags'
 * will be <code>false</code>. If you rather want to supply a specific registry, then install a different instance using
 * the {@link #install(MatsInterceptableMatsFactory, MeterRegistry, boolean)} method - the <code>JmsMatsFactory</code>
 * will then remove the automatically installed, since it implements the special marker-interface
 * {@link MatsMetricsInterceptor} of which there can only be one instance installed. <i>(In a Spring context where the
 * MatsFactory is created for you using an annotation, you should still be able to do this during early phases of Spring
 * initialization, i.e. inject a reference to the <code>MatsInterceptable</code> and just install the new instance. This
 * is possible since no Micrometer meters are created (and hence tags are set) until Mats initiations are performed or
 * Mats stages receive messages.)</i>
 * <p />
 * Note the argument 'includeAllTags' on the second install(..) method: If this is <code>true</code>, then tags will be
 * added to the meters which will give higher semantic resolution and more information, but which might result in a
 * quite substantial number of time series - if you have a popular Mats endpoint with many stages targeted by many other
 * services (thus getting many differing 'from', 'initiatingAppName' and 'initiatorId'), you may get a "cardinality
 * explosion", in particular if you also configure histograms. It is thus <code>false</code> by default, i.e. when using
 * the first install(..) method.<br />
 * Which extra tags are omitted in which situations when 'includeAllTags' is <code>false</code>:
 * <ul>
 * <li>Received (incoming message): "from" (from which stage) <i>for initial stage</i>, "initiatingAppName" and
 * "initiatorId".</li>
 * <li>Stage execution: "initiatingAppName" and "initiatorId".</li>
 * <li>Stage outgoing messages: "to" (to which stage) <i>for REPLY-messages</i> (as that will have same cardinality as
 * 'from'), "initiatingAppName" and "initiatorId".</li>
 * </ul>
 * You may set 'includeAllTags' to <code>true</code>, and then use a {@link MeterFilter} to tweak the tags with more
 * precision than this all-or-nothing approach, e.g. include "initiatingAppName" and "initiatorId" for specific
 * endpoints or stages.
 * <p />
 * <b>Notice the class {@link SuggestedTimingHistogramsMeterFilter}</b>, which configures the timing-specific metrics
 * with hopefully relevant histograms. You may apply this as such:<br />
 * <code>Metrics.globalRegistry.config().meterFilter(new SuggestedTimingHistogramsMeterFilter());</code><br />
 * .. or you may apply any other distribution or histogram configuration using the {@link MeterFilter} logic of
 * Micrometer.
 *
 * @see SuggestedTimingHistogramsMeterFilter
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
    public static final String TIMER_EXEC_USER_LAMBDA_DESC = " Part of total time taken for the actual user lambda"
            + " itself (from start to finish, which thus includes any external IO like e.g. DB or HTTP calls the user"
            + " code performs), minus any time taken to construct outbound Mats messages."
            + " (Use 'log[Timing]Measurement(..)' within user lambda to break out metrics for any potentially"
            + " expensive IO)";

    public static final String TIMER_EXEC_OUT_NAME = "mats.exec.out";
    public static final String TIMER_EXEC_OUT_DESC = "Part of total time taken to produce, serialize and compress"
            + " all envelopes, and produce and send all system messages";

    public static final String QUANTITY_EXEC_OUT_NAME = "mats.exec.out.quantity";
    public static final String QUANTITY_EXEC_OUT_DESC = "Number of outgoing messages from execution";

    public static final String TIMER_EXEC_DB_COMMIT_NAME = "mats.exec.db.commit";
    public static final String TIMER_EXEC_DB_COMMIT_DESC = "Part of total time taken to commit database";

    public static final String TIMER_EXEC_MSGSYS_COMMIT_NAME = "mats.exec.msgsys.commit";
    public static final String TIMER_EXEC_MSGSYS_COMMIT_DESC = "Part of total time taken to commit message system";

    // :: User provided metrics: timings and measurements
    public static final String TIMER_EXEC_OPS_PREFIX = "mats.exec.ops.time.";
    public static final String MEASURE_EXEC_OP_PREFIX = "mats.exec.ops.measure.";

    // :: Receive
    public static final String TIMER_IN_TOTAL_NAME = "mats.in.total";
    public static final String TIMER_IN_TOTAL_DESC = "Total time taken to preprocess and deserialize incoming message.";

    // :: Per message
    public static final String TIMER_OUT_TOTAL_NAME = "mats.out.total";
    public static final String TIMER_OUT_TOTAL_DESC = "Total time taken to produce, serialize and compress envelope,"
            + " and produce and send system message";

    public static final String TIMER_OUT_MSGSYS_SEND_NAME = "mats.out.msgsys";
    public static final String TIMER_OUT_MSGSYS_SEND_DESC = "From total, time taken to produce and send message system"
            + " message";

    public static final String SIZE_OUT_ENVELOPE_NAME = "mats.out.envelope";
    public static final String SIZE_OUT_ENVELOPE_DESC = "Outgoing mats envelope full size";

    public static final String SIZE_OUT_WIRE_NAME = "mats.out.wire";
    public static final String SIZE_OUT_WIRE_DESC = "Outgoing mats envelope wire size";

    private static final int NO_STAGE_INDEX = -1;

    /**
     * A {@link MeterFilter} that applies a hopefully reasonable histogram to all timing meters. The timings are split
     * up into two sets, "large" and "small" timings, based on what a reasonable span of timings should be for the
     * different meters. Large is 1.5 ms to 50 seconds, small is 0.15ms to 5 seconds. The buckets are spaced "circa 3x
     * exponentially", as such: [.. 5, 15, 50, 150, 500 ..]. Both sets have 10 buckets.
     */
    public static class SuggestedTimingHistogramsMeterFilter implements MeterFilter {
        public static double ms(double ms) {
            return ms * 1_000_000;
        }

        @Override
        public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
            String name = id.getName();

            // ?: Is it one of the "large timings", i.e. things that would be expected to take from millis to several
            // seconds?
            if (TIMER_EXEC_TOTAL_NAME.equals(name)
                    || TIMER_EXEC_USER_LAMBDA_NAME.equals(name)
                    || TIMER_EXEC_OUT_NAME.equals(name)
                    || name.startsWith(TIMER_EXEC_OPS_PREFIX) /* <- user timing, thus 'startsWith(..)' */) {
                return DistributionStatisticConfig.builder()
                        // Only use specific buckets, CIRCA 3x-logic
                        .serviceLevelObjectives(ms(1.5), ms(5), ms(15), ms(50), ms(150),
                                ms(500), ms(1_500), ms(5_000), ms(15_000), ms(50_000))
                        .build()
                        .merge(config);
            }
            // ?: Or is it one of the "smaller timings", i.e. things that should take a very short amount of time,
            // but could conceivably take a few seconds in bad cases.
            else if (TIMER_EXEC_DB_COMMIT_NAME.equals(name)
                    || TIMER_EXEC_MSGSYS_COMMIT_NAME.equals(name)
                    || TIMER_OUT_TOTAL_NAME.equals(name)
                    || TIMER_OUT_MSGSYS_SEND_NAME.equals(name)
                    || TIMER_IN_TOTAL_NAME.equals(name)) {
                return DistributionStatisticConfig.builder()
                        // Only use specific buckets, CIRCA 3x-logic
                        .serviceLevelObjectives(ms(0.15), ms(0.5), ms(1.5), ms(5), ms(15),
                                ms(50), ms(150), ms(500), ms(1_500), ms(5_000))
                        .build()
                        .merge(config);
            }
            // E-> No change, return config without change
            return config;
        }

    }

    /**
     * This is a cardinality-explosion-avoidance limit in case of wrongly used initiatorIds. These are not supposed to
     * be dynamic, but there is nothing hindering a user from creating a new initiatorId per initiation. Thus, if we go
     * above a certain number of such entries, we stop adding.
     * <p />
     * Value is 200.
     */
    public static final int MAX_NUMBER_OF_METRICS = 200;

    private final boolean _includeAllTags;

    private final LazyPopulatedCopyOnWriteMap<ExecutionMetricsParams, ExecutionMetrics> _executionMetricsCache;
    private final LazyPopulatedCopyOnWriteMap<UserMetricsParams, UserMeasurementMetrics> _userMeasurementMetrics;
    private final LazyPopulatedCopyOnWriteMap<UserMetricsParams, UserTimingMetrics> _userTimingMetrics;

    private final LazyPopulatedCopyOnWriteMap<ReceivedMetricsParams, ReceivedMetrics> _receivedMetricsCache;

    private final LazyPopulatedCopyOnWriteMap<MessageMetricsParams, MessageMetrics> _messageMetricsCache;

    private MatsMicrometerInterceptor(MeterRegistry meterRegistry, String appName, String appVersion,
            boolean includeAllTags) {
        _executionMetricsCache = new LazyPopulatedCopyOnWriteMap<>(params -> new ExecutionMetrics(meterRegistry,
                appName, appVersion, params));
        _userMeasurementMetrics = new LazyPopulatedCopyOnWriteMap<>(params -> new UserMeasurementMetrics(meterRegistry,
                appName, appVersion, params));
        _userTimingMetrics = new LazyPopulatedCopyOnWriteMap<>(params -> new UserTimingMetrics(meterRegistry,
                appName, appVersion, params));

        _receivedMetricsCache = new LazyPopulatedCopyOnWriteMap<>(params -> new ReceivedMetrics(meterRegistry,
                appName, appVersion, params));

        _messageMetricsCache = new LazyPopulatedCopyOnWriteMap<>(params -> new MessageMetrics(meterRegistry,
                appName, appVersion, params));

        _includeAllTags = includeAllTags;
    }

    /**
     * Creates a {@link MatsMicrometerInterceptor} employing the provided {@link MeterRegistry}, and installs it as a
     * singleton on the provided {@link MatsInterceptableMatsFactory}
     *
     * @param matsInterceptableMatsFactory
     *            the {@link MatsInterceptable} to install on (probably a {@link MatsFactory}.
     * @param meterRegistry
     *            the Micrometer {@link MeterRegistry} to create meters on.
     * @param includeAllTags
     *            whether all tags should be included (which may easily result in very many time series) or not.
     * @return the {@link MatsMicrometerInterceptor} instance which was installed as singleton.
     */
    public static MatsMicrometerInterceptor install(
            MatsInterceptableMatsFactory matsInterceptableMatsFactory,
            MeterRegistry meterRegistry, boolean includeAllTags) {
        FactoryConfig factoryConfig = matsInterceptableMatsFactory.getFactoryConfig();

        MatsMicrometerInterceptor metrics = new MatsMicrometerInterceptor(meterRegistry,
                factoryConfig.getAppName(), factoryConfig.getAppVersion(), includeAllTags);

        matsInterceptableMatsFactory.addInitiationInterceptor(metrics);
        matsInterceptableMatsFactory.addStageInterceptor(metrics);
        return metrics;
    }

    /**
     * Creates a {@link MatsMicrometerInterceptor} employing the provided {@link Metrics#globalRegistry globalRegistry},
     * and installs it as a singleton on the provided {@link MatsInterceptableMatsFactory}
     *
     * @param matsInterceptableMatsFactory
     *            the {@link MatsInterceptable} to install on (probably a {@link MatsFactory}.
     * @return the {@link MatsMicrometerInterceptor} instance which was installed as singleton.
     */
    public static MatsMicrometerInterceptor install(MatsInterceptableMatsFactory matsInterceptableMatsFactory) {
        return install(matsInterceptableMatsFactory, Metrics.globalRegistry, false);
    }

    /*
     * NOTE: We're forced to use identical set of Tags (labels) across initiations and stages, due to this issue
     * https://github.com/micrometer-metrics/micrometer/issues/877. The underlying issue seems to be the official
     * Prometheus "client_java" which doesn't allow registering two distinct measures having the same name but differing
     * labels: https://github.com/prometheus/client_java/issues/696.
     *
     * Therefore, we add all Tags both for init and stage, with the empty string for those irrelevant, bypassing the
     * problem.
     */

    @Override
    public void initiateCompleted(InitiateCompletedContext ctx) {
        // :: INITIATION TIMINGS AND SIZES

        String initiatingAppName = ctx.getInitiator().getParentFactory().getFactoryConfig().getAppName();
        String initiatorName = ctx.getInitiator().getName();
        /*
         * The initiatorId is set by sending an actual message. If no message is sent from an initation lambda, there is
         * no initiatorId. But the lambda still executed, so we want to measure it - thus using a fictive initiatorId in
         * the no-outgoing-message case.
         *
         * In case of multiple messages in one initiation, each "initiatorId" (i.e. MatsInitiate.from(..)) might be
         * different. Assuming that I have an idea of how developers use the system, this should really not be a common
         * situation. Therefore, we just pick the first message's "from" (i.e. "initiatorId") to tag the timings with.
         */
        List<MatsSentOutgoingMessage> outgoingMessages = ctx.getOutgoingMessages();
        String commonInitiatorId = outgoingMessages.isEmpty()
                ? "_no_outgoing_messages_"
                : outgoingMessages.get(0).getInitiatorId();

        ExecutionMetrics executionMetrics = _executionMetricsCache.getOrCreate(new ExecutionMetricsParams("init",
                initiatingAppName, initiatorName, commonInitiatorId, "", NO_STAGE_INDEX));
        // ?: Did we get an ExecutionMetrics?
        if (executionMetrics != null) {
            // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
            executionMetrics.registerMeasurements(ctx);
        }

        userTimingsAndMeasurements(ctx, "init", initiatingAppName, commonInitiatorId, "", NO_STAGE_INDEX);

        // :: FOR-EACH-MESSAGE: RECORD TIMING AND SIZES
        // Note: here we use each message's "from" (i.e. "initiatorId").
        for (MatsSentOutgoingMessage msg : outgoingMessages) {
            MessageMetrics messageMetrics = _messageMetricsCache.getOrCreate(new MessageMetricsParams("init",
                    msg.getMessageType().toString(), initiatingAppName, initiatorName, msg.getInitiatorId(),
                    "", NO_STAGE_INDEX, msg.getTo()));
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
        String initiatingAppName = _includeAllTags ? processContext.getInitiatingAppName() : "";
        String initiatorId = _includeAllTags ? processContext.getInitiatorId() : "";
        String stageId = processContext.getStageId();
        int stageIndex = ctx.getStage().getStageConfig().getStageIndex();

        String from = (!_includeAllTags) && (stageIndex == 0) ? "" : processContext.getFromStageId();

        ReceivedMetrics receivedMetrics = _receivedMetricsCache.getOrCreate(new ReceivedMetricsParams(
                ctx.getIncomingMessageType().toString(), initiatingAppName, initiatorId, from, stageId, stageIndex));
        // ?: Did we get an ReceivedMetrics?
        if (receivedMetrics != null) {
            // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
            receivedMetrics.registerMeasurements(ctx);
        }
    }

    @Override
    public void stageCompleted(StageCompletedContext ctx) {
        DetachedProcessContext processContext = ctx.getProcessContext();
        String initiatingAppName = _includeAllTags ? processContext.getInitiatingAppName() : "";
        String initiatorId = _includeAllTags ? processContext.getInitiatorId() : "";
        String stageId = processContext.getStageId();
        int stageIndex = ctx.getStage().getStageConfig().getStageIndex();

        ExecutionMetrics executionMetrics = _executionMetricsCache.getOrCreate(new ExecutionMetricsParams("stage",
                initiatingAppName, "", initiatorId, stageId, stageIndex));
        // ?: Did we get an ExecutionMetrics?
        if (executionMetrics != null) {
            // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
            executionMetrics.registerMeasurements(ctx);
        }

        userTimingsAndMeasurements(ctx, "stage", initiatingAppName, initiatorId, stageId, stageIndex);

        // :: FOR-EACH-MESSAGE: RECORD TIMING AND SIZES
        for (MatsSentOutgoingMessage msg : ctx.getOutgoingMessages()) {
            /*
             * If 'includeAllTags' == false, AND this is a REPLY, then DO NOT include the 'to'. The rationale here is
             * that a popular endpoint will potentially have many different incoming 'from' (which we exclude if
             * 'includeAllTags' is false), and therefore also many different outgoing 'REPLY'-to (which we then also
             * should exclude). For all other types, the 'to' will typically just be a single collaborator, i.e.
             * "SomeEndpoint.stage1" will always send a REQUEST to "SomeSpecificEndpoint" - the exception to this is
             * that if stage1 sometimes run NEXT or GOTO instead of REQUEST (thus different 'to') - but which won't
             * explode the cardinality to the same level (if it does REPLY, that is still excluded).
             */
            String to = (!_includeAllTags) && (msg.getMessageType() == MessageType.REPLY) ? "" : msg.getTo();
            /*
             * NOTICE: We use the current stageId as "from", not 'msg.getFrom()'. The reason here is both that a) in a
             * normal Mats flow situation (REQUEST/REPLY/NEXT/GOTO), it will be the same anyway, b) the latter can
             * potentially lead to cardinality explosion as it may be set by the user when initiating from a stage, but
             * c) most importantly that I believe it makes more sense in a metrics overview situation to see which
             * Endpoint/Stage that produces the message, instead of a somewhat arbitrary 'from' set inside the stage
             * lambda by the user. (I somewhat regret that it is possible to set the 'from' when initiating within a
             * stage).
             */
            MessageMetrics messageMetrics = _messageMetricsCache.getOrCreate(new MessageMetricsParams("stage",
                    msg.getMessageType().toString(), initiatingAppName, "", initiatorId, stageId, stageIndex, to));
            // ?: Did we get a MessageMetrics?
            if (messageMetrics != null) {
                // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
                messageMetrics.registerMeasurements(msg);
            }
        }
    }

    private void userTimingsAndMeasurements(CommonCompletedContext ctx, String executionType, String initiatingAppName,
            String initiatorId, String stageId, int stageIndex) {
        // :: User Timings
        List<MatsTimingMeasurement> timings = ctx.getTimingMeasurements();
        for (MatsTimingMeasurement timing : timings) {
            String metricId = timing.getMetricId();
            String metricDescription = timing.getMetricDescription();
            String[] labelKeyValue = timing.getLabelKeyValue();

            long nanos = timing.getNanos();

            UserTimingMetrics userTimingMetrics = _userTimingMetrics.getOrCreate(new UserMetricsParams(
                    executionType, initiatingAppName, initiatorId, stageId, stageIndex, metricId,
                    metricDescription, "", labelKeyValue));
            // ?: Did we get an UserTimingMetrics?
            if (userTimingMetrics != null) {
                // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
                userTimingMetrics.registerMeasurements(nanos);
            }
        }

        // :: User Measurements
        List<MatsMeasurement> measurements = ctx.getMeasurements();
        for (MatsMeasurement measurement : measurements) {
            String metricId = measurement.getMetricId();
            String metricDescription = measurement.getMetricDescription();
            String baseUnit = measurement.getBaseUnit();
            String[] labelKeyValue = measurement.getLabelKeyValue();

            double measure = measurement.getMeasure();

            UserMeasurementMetrics userMeasurementMetrics = _userMeasurementMetrics.getOrCreate(new UserMetricsParams(
                    executionType, initiatingAppName, initiatorId, stageId, stageIndex, metricId,
                    metricDescription, baseUnit, labelKeyValue));
            // ?: Did we get an UserMeasurementMetrics?
            if (userMeasurementMetrics != null) {
                // -> Yes, we got it, so cardinality-explosion-avoidance has NOT kicked in.
                userMeasurementMetrics.registerMeasurements(measure);
            }
        }
    }

    // =======================

    /**
     * Concurrent copy-on-write, lazy build-up, permanent cache. Using a plain HashMap, albeit with volatile reference.
     * When a new item needs to be added, the entire map is recreated before setting the new instance (so that any
     * ongoing lookup won't be affected by the put) - but this creation is done with "double-checked locking" inside a
     * synchronized block. Could have used a ConcurrentHashMap, but since I believe I am so clever, I'll make a stab at
     * making a faster, copy-on-write variant. The thinking is that since this Map will have a volley of puts soon after
     * startup, and then forever be static, the following might make sense: a volatile, non-contended read of the ref,
     * and then a plain HashMap lookup.
     */
    private static class LazyPopulatedCopyOnWriteMap<VP, V> {
        private volatile Map<VP, V> _cache = Collections.emptyMap();

        private final Function<VP, V> _creator;

        public LazyPopulatedCopyOnWriteMap(Function<VP, V> creator) {
            _creator = creator;
        }

        private V getOrCreate(VP params) {
            V v = _cache.get(params);
            // ?: Did we find it from cache?
            if (v != null) {
                // -> Yes, so return it!
                return v;
            }
            // E-> value was null, now go for "double-checked locking" to create it.
            synchronized (this) {
                // ----- From now on, the reference to _cache won't change until we set it, because we must be within
                // this synchronized block to do the setting.
                Map<VP, V> oldCache = _cache;

                // NOTE: We check again for the presence of the cached item, i.e. "double checked".

                // ?: Still not in the currently present reference?
                V v2 = oldCache.get(params);
                if (v2 != null) {
                    return v2;
                }

                // First check the size - if too big, we stop making new ones
                if (oldCache.size() >= MAX_NUMBER_OF_METRICS) {
                    log.warn(LOG_PREFIX + "Cardinality explosion avoidance: When about to create metrics object for "
                            + params + ", we found that there already is [" + MAX_NUMBER_OF_METRICS + "] present,"
                            + " thus won't create it. You should find the offending code (probably using dynamic"
                            + " initiatorIds somewhere) and fix it.");
                    return null;
                }

                // E-> It was still not created, and we're below the "cardinality explosion" limit - and now we "own"
                // the '_cache' ref while we go about creating new. The existing instance of the Map will not change
                // while within the sync block, so we can safely add all entries from it:
                Map<VP, V> newCache = new HashMap<>(oldCache);
                // Create the new entry
                log.info("Creating and caching new metric " + params);
                V newV = _creator.apply(params);
                // Add the new entry
                newCache.put(params, newV);
                // Overwrite the volatile reference to cache with this new cache.
                _cache = newCache;
                // Return the just created value
                return newV;
            }
        }
    }

    /**
     * Metrics for an Initiation and a Stage execution.
     */
    static class ExecutionMetrics {
        private final Timer _timer_TotalTime;
        private final Timer _timer_UserLambda;
        private final Timer _timer_DbCommit;
        private final Timer _timer_MsgOut;
        private final Timer _timer_MsgCommit;

        DistributionSummary _count_Messages;

        Timer timerAddTagsAndRegister(Builder builder, MeterRegistry meterRegistry,
                String appName, String appVersion, ExecutionMetricsParams params) {
            return builder
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", params._executionType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorName", params._initiatorName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", params._stageIndex == NO_STAGE_INDEX ? "" : Integer.toString(params._stageIndex))
                    .register(meterRegistry);
        }

        ExecutionMetrics(MeterRegistry meterRegistry, String appName, String appVersion,
                ExecutionMetricsParams params) {
            _timer_TotalTime = timerAddTagsAndRegister(Timer.builder(TIMER_EXEC_TOTAL_NAME)
                    .description(TIMER_EXEC_TOTAL_DESC), meterRegistry, appName, appVersion, params);

            _timer_UserLambda = timerAddTagsAndRegister(Timer.builder(TIMER_EXEC_USER_LAMBDA_NAME)
                    .description(TIMER_EXEC_USER_LAMBDA_DESC), meterRegistry, appName, appVersion, params);

            _timer_DbCommit = timerAddTagsAndRegister(Timer.builder(TIMER_EXEC_DB_COMMIT_NAME)
                    .description(TIMER_EXEC_DB_COMMIT_DESC), meterRegistry, appName, appVersion, params);

            _timer_MsgOut = timerAddTagsAndRegister(Timer.builder(TIMER_EXEC_OUT_NAME)
                    .description(TIMER_EXEC_OUT_DESC), meterRegistry, appName, appVersion, params);

            _timer_MsgCommit = timerAddTagsAndRegister(Timer.builder(TIMER_EXEC_MSGSYS_COMMIT_NAME)
                    .description(TIMER_EXEC_MSGSYS_COMMIT_DESC), meterRegistry, appName, appVersion, params);

            _count_Messages = DistributionSummary.builder(QUANTITY_EXEC_OUT_NAME)
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", params._executionType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorName", params._initiatorName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", params._stageIndex == NO_STAGE_INDEX ? "" : Integer.toString(params._stageIndex))
                    // NOTE! If we use baseUnit, we'll get crash on the meter name - instead embed directly in name.
                    // .baseUnit("quantity")
                    .description(QUANTITY_EXEC_OUT_DESC)
                    .register(meterRegistry);
        }

        void registerMeasurements(CommonCompletedContext ctx) {
            /*
             * NOTE! The timings are a bit user-unfriendly wrt. "user lambda" timing, as this includes the production of
             * outgoing envelopes, including DTO, STO and TraceProps serialization, due to how it must be implemented.
             * Thus, we do some tricking here to get more relevant "split up" of the separate pieces.
             */
            // :: Find total DtoAndSto Serialization of outgoing messages
            long nanosTaken_SumDtoAndStoSerialNanos = 0;
            for (MatsSentOutgoingMessage msg : ctx.getOutgoingMessages()) {
                nanosTaken_SumDtoAndStoSerialNanos += msg.getEnvelopeProduceNanos();
            }

            // :: Subtract the total DtoAndSto Serialization from the user lambda time.
            long nanosTaken_UserLambdaAlone = ctx.getUserLambdaNanos() - nanosTaken_SumDtoAndStoSerialNanos;

            // :: Sum up the total "message handling": Dto&Sto + envelope serial + msg.sys. handling.
            long nanosTaken_SumMessageOutHandling = nanosTaken_SumDtoAndStoSerialNanos
                    + ctx.getSumEnvelopeSerializationAndCompressionNanos()
                    + ctx.getSumMessageSystemProductionAndSendNanos();

            _timer_TotalTime.record(ctx.getTotalExecutionNanos(), TimeUnit.NANOSECONDS);
            _timer_UserLambda.record(nanosTaken_UserLambdaAlone, TimeUnit.NANOSECONDS);
            _timer_DbCommit.record(ctx.getDbCommitNanos(), TimeUnit.NANOSECONDS);
            _timer_MsgOut.record(nanosTaken_SumMessageOutHandling, TimeUnit.NANOSECONDS);
            _timer_MsgCommit.record(ctx.getMessageSystemCommitNanos(), TimeUnit.NANOSECONDS);
            _count_Messages.record(ctx.getOutgoingMessages().size());
        }

        static class ExecutionMetricsParams {
            final String _executionType;
            final String _initiatingAppName;
            final String _initiatorName;
            final String _initiatorId;
            final String _stageId;
            final int _stageIndex; // NO_STAGE_INDEX if initiation

            final int _hashCode;

            ExecutionMetricsParams(String executionType, String initiatingAppName, String initiatorName,
                    String initiatorId, String stageId, int stageIndex) {
                _executionType = executionType;
                _initiatingAppName = initiatingAppName;
                _initiatorName = initiatorName;
                _initiatorId = initiatorId;
                _stageId = stageId;
                _stageIndex = stageIndex;

                // Ignoring stageIndex, since it follows stageId, which we include.
                _hashCode = executionType.hashCode() + initiatingAppName.hashCode() + initiatorName.hashCode()
                        + initiatorId.hashCode() + stageId.hashCode();
            }

            @Override
            public boolean equals(Object o) {
                ExecutionMetricsParams that = (ExecutionMetricsParams) o;
                // Ignoring stageIndex, since it follows stageId, which we include.
                return Objects.equals(_executionType, that._executionType)
                        && Objects.equals(_initiatingAppName, that._initiatingAppName)
                        && Objects.equals(_initiatorName, that._initiatorName)
                        && Objects.equals(_initiatorId, that._initiatorId)
                        && Objects.equals(_stageId, that._stageId);
            }

            @Override
            public int hashCode() {
                return _hashCode;
            }

            @Override
            public String toString() {
                return "ExecutionMetricsParams{" +
                        "_executionType='" + _executionType + '\'' +
                        ", _initiatingAppName='" + _initiatingAppName + '\'' +
                        ", _initiatorName='" + _initiatorName + '\'' +
                        ", _initiatorId='" + _initiatorId + '\'' +
                        ", _stageId='" + _stageId + '\'' +
                        ", _stageIndex=" + _stageIndex +
                        '}';
            }
        }
    }

    /**
     * Measurement registered with {@link ProcessContext#logMeasurement(String, String, String, double, String...)}
     * (stage), or {@link MatsInitiate#logMeasurement(String, String, String, double, String...)} (init).
     */
    static class UserMeasurementMetrics {
        private final DistributionSummary _measure;

        UserMeasurementMetrics(MeterRegistry meterRegistry, String appName, String appVersion,
                UserMetricsParams params) {
            DistributionSummary.Builder builder = DistributionSummary
                    .builder(MEASURE_EXEC_OP_PREFIX + params._metricId)
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", params._executionType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", Integer.toString(params._stageIndex))
                    .baseUnit(params._baseUnit)
                    .description(params._metricDescription);

            // Add any user-set tags
            String[] labelKeyValue = params._labelKeyValue;
            for (int i = 0; i < labelKeyValue.length; i += 2) {
                String key = labelKeyValue[i];
                String value = labelKeyValue[i + 1];
                builder.tag(key, value);
            }

            _measure = builder.register(meterRegistry);
        }

        void registerMeasurements(double measurement) {
            _measure.record(measurement);
        }
    }

    /**
     * Timing measurement registered with {@link ProcessContext#logTimingMeasurement(String, String, long, String...)}
     * (stage), or {@link MatsInitiate#logTimingMeasurement(String, String, long, String...)} (init).
     */
    static class UserTimingMetrics {
        private final Timer _timer;

        UserTimingMetrics(MeterRegistry meterRegistry, String appName, String appVersion,
                UserMetricsParams params) {
            Builder builder = Timer
                    .builder(TIMER_EXEC_OPS_PREFIX + params._metricId)
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", params._executionType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", Integer.toString(params._stageIndex))
                    .description(params._metricDescription);

            // Add any user-set tags
            String[] labelKeyValue = params._labelKeyValue;
            for (int i = 0; i < labelKeyValue.length; i += 2) {
                String key = labelKeyValue[i];
                String value = labelKeyValue[i + 1];
                builder.tag(key, value);
            }

            _timer = builder.register(meterRegistry);
        }

        void registerMeasurements(long nanos) {
            _timer.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    static class UserMetricsParams {
        final String _executionType;
        final String _initiatingAppName;
        final String _initiatorId;
        final String _stageId;
        final int _stageIndex; // NO_STAGE_INDEX if initiation

        final String _metricId;
        final String _metricDescription;
        final String _baseUnit;
        final String[] _labelKeyValue;

        final int _hashCode;

        public UserMetricsParams(String executionType, String initiatingAppName, String initiatorId,
                String stageId, int stageIndex, String metricId, String metricDescription, String baseUnit,
                String[] labelKeyValue) {
            _executionType = executionType;
            _initiatingAppName = initiatingAppName;
            _initiatorId = initiatorId;
            _stageId = stageId;
            _stageIndex = stageIndex;

            _metricId = metricId;
            _metricDescription = metricDescription;
            _baseUnit = baseUnit;
            _labelKeyValue = labelKeyValue;

            // Ignoring stageIndex, since it follows stageId, which we include. (stageIndex might here be -1)
            // Ignoring metricDescription, since this is not part of the key for the meter.
            // (We'll effectively use the first we get, and ignore any other - which should be static anyway)
            // Not using Objects.hash(..) to avoid array creation.
            _hashCode = executionType.hashCode() + initiatingAppName.hashCode() + initiatorId.hashCode()
                    + stageId.hashCode()
                    + metricId.hashCode() + baseUnit.hashCode()
                    + Arrays.hashCode(_labelKeyValue);
        }

        @Override
        public boolean equals(Object o) {
            UserMetricsParams that = (UserMetricsParams) o;
            // Ignoring stageIndex, since it follows stageId, which we include.
            // Ignoring metricDescription, since this is not part of the key for the meter.
            return Objects.equals(_executionType, that._executionType)
                    && Objects.equals(_initiatingAppName, that._initiatingAppName)
                    && Objects.equals(_initiatorId, that._initiatorId)
                    && Objects.equals(_stageId, that._stageId)

                    && Objects.equals(_metricId, that._metricId)
                    && Objects.equals(_baseUnit, that._baseUnit)
                    && Arrays.equals(_labelKeyValue, that._labelKeyValue);
        }

        @Override
        public int hashCode() {
            return _hashCode;
        }

        @Override
        public String toString() {
            return "UserMetricsParams{" +
                    "_executionType='" + _executionType + '\'' +
                    ", _initiatingAppName='" + _initiatingAppName + '\'' +
                    ", _initiatorId='" + _initiatorId + '\'' +
                    ", _stageId='" + _stageId + '\'' +
                    ", _stageIndex=" + _stageIndex +
                    ", _metricId='" + _metricId + '\'' +
                    ", _metricDescription='" + _metricDescription + '\'' +
                    ", _baseUnit='" + _baseUnit + '\'' +
                    ", _labelKeyValue=" + Arrays.toString(_labelKeyValue) +
                    '}';
        }
    }

    /**
     * Metrics for reception of a message (on a Stage)
     */
    static class ReceivedMetrics {
        private final Timer _timer_Total;

        ReceivedMetrics(MeterRegistry meterRegistry, String appName, String appVersion, ReceivedMetricsParams params) {
            _timer_Total = Timer.builder(TIMER_IN_TOTAL_NAME)
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", "stage") // Just adding this to point out that it definitely refers to a stage.
                    .tag("type", params._messageType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("from", params._from)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", Integer.toString(params._stageIndex))
                    .description(TIMER_IN_TOTAL_DESC)
                    .register(meterRegistry);
        }

        void registerMeasurements(StageReceivedContext ctx) {
            _timer_Total.record(ctx.getTotalPreprocessAndDeserializeNanos(), TimeUnit.NANOSECONDS);
        }

        static class ReceivedMetricsParams {
            final String _messageType;
            final String _initiatingAppName;
            final String _initiatorId;
            final String _from;
            final String _stageId;
            final int _stageIndex;

            final int _hashCode;

            public ReceivedMetricsParams(String messageType, String initiatingAppName, String initiatorId,
                    String from, String stageId, int stageIndex) {
                _messageType = messageType;
                _initiatingAppName = initiatingAppName;
                _initiatorId = initiatorId;
                _from = from;
                _stageId = stageId;
                _stageIndex = stageIndex;

                // Ignoring stageIndex, since it follows stageId, which we include.
                // Not using Objects.hash(..) to avoid array creation, and effectively unroll.
                _hashCode = messageType.hashCode() + initiatingAppName.hashCode() + initiatorId.hashCode()
                        + from.hashCode() + stageId.hashCode();
            }

            @Override
            public boolean equals(Object o) {
                ReceivedMetricsParams that = (ReceivedMetricsParams) o;
                // Ignoring stageIndex, since it follows stageId, which we include.
                return Objects.equals(_messageType, that._messageType)
                        && Objects.equals(_initiatingAppName, that._initiatingAppName)
                        && Objects.equals(_initiatorId, that._initiatorId)
                        && Objects.equals(_from, that._from)
                        && Objects.equals(_stageId, that._stageId);
            }

            @Override
            public int hashCode() {
                return _hashCode;
            }

            @Override
            public String toString() {
                return "ReceivedMetricsParams{" +
                        "_messageType='" + _messageType + '\'' +
                        ", _initiatingAppName='" + _initiatingAppName + '\'' +
                        ", _initiatorId='" + _initiatorId + '\'' +
                        ", _from='" + _from + '\'' +
                        ", _stageId='" + _stageId + '\'' +
                        ", _stageIndex=" + _stageIndex +
                        '}';
            }
        }
    }

    /**
     * Metrics for a sent Message, both in Initiation and Stage.
     */
    static class MessageMetrics {
        private final DistributionSummary _size_Envelope;
        private final DistributionSummary _size_Wire;
        private final Timer _timer_Total;
        private final Timer _timer_MsgSys;

        MessageMetrics(MeterRegistry meterRegistry, String appName, String appVersion,
                MessageMetricsParams params) {
            String stageIndexValue = params._stageIndex == NO_STAGE_INDEX ? "" : Integer.toString(params._stageIndex);
            _size_Envelope = DistributionSummary.builder(SIZE_OUT_ENVELOPE_NAME)
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", params._executionType)
                    .tag("type", params._messageType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorName", params._initiatorName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", stageIndexValue)
                    .tag("to", params._to)
                    .baseUnit("bytes")
                    .description(SIZE_OUT_ENVELOPE_DESC)
                    .register(meterRegistry);

            _size_Wire = DistributionSummary.builder(SIZE_OUT_WIRE_NAME)
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", params._executionType)
                    .tag("type", params._messageType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorName", params._initiatorName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", stageIndexValue)
                    .tag("to", params._to)
                    .baseUnit("bytes")
                    .description(SIZE_OUT_WIRE_DESC)
                    .register(meterRegistry);

            _timer_Total = Timer.builder(TIMER_OUT_TOTAL_NAME)
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", params._executionType)
                    .tag("type", params._messageType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorName", params._initiatorName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", stageIndexValue)
                    .tag("to", params._to)
                    .description(TIMER_OUT_TOTAL_DESC)
                    .register(meterRegistry);

            _timer_MsgSys = Timer.builder(TIMER_OUT_MSGSYS_SEND_NAME)
                    .tag("appName", appName)
                    .tag("appVersion", appVersion)
                    .tag("exec", params._executionType)
                    .tag("type", params._messageType)
                    .tag("initiatingAppName", params._initiatingAppName)
                    .tag("initiatorName", params._initiatorName)
                    .tag("initiatorId", params._initiatorId)
                    .tag("stageId", params._stageId)
                    .tag("stageIndex", stageIndexValue)
                    .tag("to", params._to)

                    .description(TIMER_OUT_MSGSYS_SEND_DESC)
                    .register(meterRegistry);
        }

        void registerMeasurements(MatsSentOutgoingMessage msg) {
            _size_Envelope.record(msg.getEnvelopeWireSize());
            _size_Wire.record(msg.getEnvelopeWireSize());
            _timer_Total.record(msg.getEnvelopeProduceNanos()
                    + msg.getEnvelopeSerializationNanos()
                    + msg.getEnvelopeCompressionNanos()
                    + msg.getMessageSystemProduceAndSendNanos(), TimeUnit.NANOSECONDS);
            _timer_MsgSys.record(msg.getMessageSystemProduceAndSendNanos(), TimeUnit.NANOSECONDS);
        }

        static class MessageMetricsParams {
            final String _executionType;
            final String _messageType;
            final String _initiatingAppName;
            final String _initiatorName;
            final String _initiatorId;
            final String _stageId;
            final int _stageIndex; // NO_STAGE_INDEX if initiation
            final String _to;

            final int _hashCode;

            MessageMetricsParams(String executionType, String messageType,
                    String initiatingAppName, String initiatorName, String initiatorId,
                    String stageId, int stageIndex, String to) {
                _executionType = executionType;
                _messageType = messageType;
                _initiatingAppName = initiatingAppName;
                _initiatorName = initiatorName;
                _initiatorId = initiatorId;
                _stageId = stageId;
                _stageIndex = stageIndex;
                _to = to;

                // Ignoring stageIndex, since it follows stageId, which we include.
                // Not using Objects.hash(..) to avoid array creation, and effectively unroll.
                _hashCode = executionType.hashCode() + messageType.hashCode() + initiatingAppName.hashCode()
                        + initiatorName.hashCode() + initiatorId.hashCode() + stageId.hashCode() + to.hashCode();
            }

            @Override
            public boolean equals(Object o) {
                MessageMetricsParams that = (MessageMetricsParams) o;
                // Ignoring stageIndex, since it follows stageId, which we include.
                return Objects.equals(_executionType, that._executionType)
                        && Objects.equals(_messageType, that._messageType)
                        && Objects.equals(_initiatingAppName, that._initiatingAppName)
                        && Objects.equals(_initiatorName, that._initiatorName)
                        && Objects.equals(_initiatorId, that._initiatorId)
                        && Objects.equals(_stageId, that._stageId)
                        && Objects.equals(_to, that._to);
            }

            @Override
            public int hashCode() {
                return _hashCode;
            }

            @Override
            public String toString() {
                return "MessageMetricsParams{" +
                        "_executionType='" + _executionType + '\'' +
                        ", _messageType='" + _messageType + '\'' +
                        ", _initiatingAppName='" + _initiatingAppName + '\'' +
                        ", _initiatorName='" + _initiatorName + '\'' +
                        ", _initiatorId='" + _initiatorId + '\'' +
                        ", _stageId='" + _stageId + '\'' +
                        ", _stageIndex=" + _stageIndex +
                        ", _to='" + _to + '\'' +
                        '}';
            }
        }
    }
}
