package io.mats3.intercept.logging;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.api.intercept.CommonCompletedContext;
import io.mats3.api.intercept.CommonCompletedContext.MatsMeasurement;
import io.mats3.api.intercept.CommonCompletedContext.MatsTimingMeasurement;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInterceptable;
import io.mats3.api.intercept.MatsLoggingInterceptor;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsStageInterceptor;

/**
 * A logging interceptor that writes loglines to two SLF4J loggers, including multiple pieces of information on the MDC
 * (initiatorId, endpointId and stageIds, and timings and sizes), so that it hopefully is easy to reason about and debug
 * all Mats flows, and to be able to use the logging system (e.g. Kibana over ElasticSearch) to create statistics.
 * <p />
 * Two loggers are used, which are {@link #log_init "io.mats3.log.init"} and {@link #log_stage "io.mats3.log.stage"}.
 * All loglines' message-part is prepended with <code>"#MATSLOG#"</code>.
 *
 * <h2>Log lines and their metadata</h2> There are 5 different type of log lines emitted by this logging interceptor -
 * but note that the "Per Message" log line can be combined with the "Initiate Complete" or "Stage Complete" loglines if
 * the initiation or stage only produce a single message - read more at end.
 * <ol>
 * <li>Initiate Complete</li>
 * <li>Message Received (on Stage)</li>
 * <li>Stage Complete</li>
 * <li>Per Created/Sent message (both for initiations and stage produced messages)</li>
 * <li>Metrics set from the user lambda during initiation and stages</li>
 * </ol>
 * Some MDC properties are set by the JMS implementation, and not by this interceptor. These are the ones that do not
 * have a JavaDoc-link in the below listings.
 * <p />
 * Note that all loglines that are emitted by any code, user or system, <i>which are within Mats init or processing</i>,
 * will have the follow properties set:
 * <ul>
 * <li><code><b>"mats.AppName"</b></code>: The app-name which the MatsFactory was created with</li>
 * <li><code><b>"mats.AppVersion"</b></code>: The app-version which the MatsFactory was created with</li>
 * <li>Either, or both, of <code><b>"mats.Init"</b></code> (set to 'true' on initiation enter, and cleared on exit) and
 * <code><b>"mats.Stage"</b></code> (set to constant 'true' for all Mats Stage processors). If initiation within a
 * stage, both are set.</li>
 * </ul>
 *
 * <h3>MDC Properties for Initiate Complete:</h3>
 * <ul>
 * <li><b>{@link #MDC_MATS_INITIATE_COMPLETED "mats.InitiateCompleted"}</b>: 'true' <i>on a single</i> logline per
 * completed initiation - <i>can be used to count initiations</i>.</li>
 * <li><b>{@link #MDC_TRACE_ID "traceId"}</b>: Set for an initiation from when it is set in the user code performing the
 * initiation (reset to whatever it was upon exit of initiation lambda)</li>
 * </ul>
 * <b>Metrics for the execution of the initiation</b> (very similar to the metrics for a stage processing):
 * <ul>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_TOTAL_EXECUTION "mats.exec.TotalExecution.ms"}</b>: Total time taken for the
 * initiation to complete - including both user code and all system code including commits.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_USER_LAMBDA "mats.exec.UserLambda.ms"}</b>: Part of total time taken for the
 * actual user lambda, including e.g. any external IO like DB, but excluding all system code, in particular message
 * creation, and commits.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_OUT "mats.exec.Out.ms"}</b>: Part of total time taken for the creation
 * and serialization of Mats messages, and production <i>and sending</i> of "message system messages" (e.g. creating and
 * populating JMS Message plus <code>jmsProducer.send(..)</code> for the JMS implementation)</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_QUANTITY_OUT "mats.exec.Out.quantity"}</b>: Number of messages sent</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_DB_COMMIT "mats.exec.DbCommit.ms"}</b>: Part of total time taken for committing
 * DB.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_MSG_SYS_COMMIT "mats.exec.MsgSysCommit.ms"}</b>: Part of total time taken for
 * committing the message system (e.g. <code>jmsSession.commit()</code> for the JMS implementation)</li>
 * </ul>
 * <b>User metrics:</b> Furthermore, any metrics (measurements and timings) set from an initiation or stage will be
 * available as separate log lines, the metric being set on the MDC. The MDC key for timings will be
 * {@link #MDC_MATS_COMPLETE_OPS_TIMING_PREFIX "mats.exec.ops.time."}+{metricId}+"ms" and for measurements
 * {@link #MDC_MATS_COMPLETE_OPS_MEASURE_PREFIX "mats.exec.ops.measure."}+{metricId} + {baseUnit}. If labels/tags are
 * set on a metric, the MDC-key will be {@link #MDC_MATS_COMPLETE_OPS_TIMING_PREFIX
 * "mats.exec.ops.time."}+{metricId}+".tag." + {labelKey} and for measurements
 * {@link #MDC_MATS_COMPLETE_OPS_MEASURE_PREFIX "mats.exec.ops.measure."}+{metricId} + ".tag." + {labelKey}.<br />
 * <br />
 * <h3>MDC Properties for Message Received:</h3>
 * <ul>
 * <li><b>{@link #MDC_MATS_MESSAGE_RECEIVED "mats.MessageReceived"}</b>: 'true' on a single logline per received message
 * - <i>can be used to count received messages</i>.</li>
 * <li><code><b>"mats.StageId"</b></code>: Always set on the Processor threads for a stage, so any logline output inside
 * a Mats stage will have this set.</li>
 * <li><b>{@link #MDC_TRACE_ID "traceId"}</b>: The Mats flow's traceId, set from the initiation.</li>
 * <li><code><b>"mats.in.MsgSysId"</b></code>: The messageId the messaging system assigned the incoming message upon
 * production on the sender side (e.g JMSMessageID for the JMS implementation)</li>
 * <li><b>{@link #MDC_MATS_IN_MATS_MESSAGE_ID "mats.in.MatsMsgId"}</b>: The messageId the Mats system assigned the
 * incoming message upon production on the sender side. Note that it consists of the Mats flow id + an individual part
 * per message in the flow.</li>
 * <li><b>{@link #MDC_MATS_IN_FROM_APP_NAME "mats.in.from.App"}</b>: Which app this incoming message is from.</li>
 * <li><b>{@link #MDC_MATS_IN_FROM_ID "mats.in.from.Id"}</b>: Which initiatorId, endpointId or stageId this message is
 * from.</li>
 * <li><i>NOTICE: NOT using <code>"mats.in.to.app"</code>, as that is "this" App, and thus identical to
 * <code>"mats.AppName"</code>.</i></li>
 * <li><i>NOTICE: NOT using <code>"mats.in.to.id"</code>, as that is "this" StageId, and thus identical to
 * <code>"mats.StageId"</code>.</i></li>
 * </ul>
 * Common for all received and sent messages in a Mats flow (initiated, received on stage and sent from stage):
 * <ul>
 * <li><b>{@link #MDC_MATS_INIT_APP "mats.init.App"}</b>: Which App initiated this Mats flow.</li>
 * <li><b>{@link #MDC_MATS_INIT_ID "mats.init.Id"}</b>: The initiatorId of this MatsFlow;
 * <code>matsInitiate.from(initiatorId)</code>.</li>
 * <li><b>{@link #MDC_MATS_AUDIT "mats.Audit"}</b>: Whether this Mats flow should be audited.</li>
 * <li><b>{@link #MDC_MATS_INTERACTIVE "mats.Interactive"}</b>: Whether this Mats flow should be treated as
 * "interactive", meaning that a human is actively waiting for its execution.</li>
 * <li><b>{@link #MDC_MATS_PERSISTENT "mats.Persistent"}</b>: Whether the messaging system should use persistent (as
 * opposed to non-persistent) message passing, i.e. store to disk to survive a crash.</li>
 * </ul>
 * <b>Metrics for message reception</b> (note how these compare to the production of a message, on the "Per Message"
 * loglines):
 * <ul>
 * <li><b>{@link #MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL "mats.in.TotalPreprocDeserial.ms"}</b>: Total time taken
 * to preprocess and deserialize the incoming message.</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT "mats.in.MsgSysDeconstruct.ms"}</b>: Part of total time taken to
 * pick out the Mats pieces from the incoming message system message.</li>
 * <li><b>{@link #MDC_MATS_IN_SIZE_ENVELOPE_WIRE "mats.in.EnvelopeWire.bytes"}</b>: How big the incoming Mats envelope
 * ("MatsTrace") was in the incoming message system message, i.e. "on the wire".</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS "mats.in.EnvelopeDecompress.ms"}</b>: Part of total time taken to
 * decompress the Mats envelope (will be 0 if it was sent plain, and >0 if it was compressed).</li>
 * <li><b>{@link #MDC_MATS_IN_SIZE_ENVELOPE_SERIAL "mats.in.EnvelopeSerial.bytes"}</b>: How big the incoming Mats
 * envelope ("MatsTrace") is in its serialized form, after decompression.</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_ENVELOPE_DESERIAL "mats.in.EnvelopeDeserial.ms"}</b>: Part of total time taken to
 * deserialize the incoming serialized Mats envelope.</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_MSG_AND_STATE_DESERIAL "mats.in.MsgAndStateDeserial.ms"}</b>: Part of total time
 * taken to deserialize the actual message and state objects from the Mats envelope.</li>
 * </ul>
 *
 * <h3>MDC Properties for Stage Complete:</h3>
 * <ul>
 * <li><b>{@link #MDC_MATS_STAGE_COMPLETED "mats.StageCompleted"}</b>: 'true' on a single logline per completed stage -
 * <i>can be used to count stage processings</i>.</li>
 * <li><code><b>"mats.StageId"</b></code>: Always set on the Processor threads for a stage, so any logline output inside
 * a Mats stage will have this set.</li>
 * <li><b>{@link #MDC_TRACE_ID "traceId"}</b>: The Mats flow's traceId, set from the initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_PROCESS_RESULT "mats.exec.ProcessResult"}</b>: the ProcessResult enum</li>
 * </ul>
 * <b>Metrics for the processing of the stage</b> (very similar to the metrics for an initiation execution, but includes
 * the reception of a message):
 * <ul>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_TOTAL_EXECUTION "mats.exec.TotalExecution.ms"}</b>: Total time taken for the
 * stage to complete - including both user code and all system code including commits.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_TOTAL_PREPROC_AND_DESERIAL "mats.exec.TotalPreprocDeserial.ms"}</b>: Part of
 * the total time taken for the preprocessing and deserialization of the incoming message, same as the message received
 * logline's {@link #MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL "mats.in.TotalPreprocDeserial.ms"}, as that piece is
 * also part of the stage processing.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_USER_LAMBDA "mats.exec.UserLambda.ms"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_OUT "mats.exec.Out.ms"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_QUANTITY_OUT "mats.exec.Out.quantity"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_DB_COMMIT "mats.exec.DbCommit.ms"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_MSG_SYS_COMMIT "mats.exec.MsgSysCommit.ms"}</b>: Same as for initiation</li>
 * </ul>
 * <b>User metrics:</b> Furthermore, any metrics (measurements and timings) set from an initiation or stage will be
 * available as separate log lines - same as for initiations.<br />
 * <br />
 * <h3>MDC Properties for Per created Message (both initiations and stage produced messages):</h3>
 * <ul>
 * <li><b>{@link #MDC_MATS_MESSAGE_SENT "mats.MessageSent"}</b>: 'true' on single logline per sent message - <i>can be
 * used to count sent messages.</i></li>
 * <li><b>{@link #MDC_MATS_DISPATCH_TYPE "mats.DispatchType"}</b>: The DispatchType enum; INIT, STAGE, STAGE_INIT</li>
 * <li><b>{@link #MDC_MATS_OUT_MATS_MESSAGE_ID "mats.out.MatsMsgId"}</b>: The messageId the Mats system gave the
 * message. Note that it consists of the Mats flow id + an individual part per message in the flow.</li>
 * <li><b>{@link #MDC_MATS_OUT_MESSAGE_SYSTEM_ID "mats.out.MsgSysId"}</b>: The messageId that the messaging system gave
 * the message - for the JMS Implementation, it is the JMSMessageId.</li>
 * <li><i>NOTICE: NOT using "mats.out.from.app", as that is 'this' App, and thus identical to:
 * <code>"mats.AppName"</code>.</i></li>
 * <li><b>{@link #MDC_MATS_OUT_FROM_ID "mats.out.from.Id"}</b>: "this" EndpointId/StageId/InitiatorId - <i>NOTICE:
 * <code>"mats.out.from.Id"</code> == <code>"mats.StageId"</code> for Stages - but there is no corresponding for
 * InitiatorId.</i></li>
 * <li><b>{@link #MDC_MATS_OUT_TO_ID "mats.out.to.Id"}</b>: target EndpointId/StageId</li>
 * <li><i>NOTICE: NOT using "mats.out.to.app", since we do not know which app will consume it.</i></li>
 * </ul>
 * Common for all sent and received messages in a Mats flow (initiated, received on stage and sent from stage):
 * <ul>
 * <li><b>{@link #MDC_MATS_INIT_APP "mats.init.App"}</b>: Which App initiated this Mats flow.</li>
 * <li><b>{@link #MDC_MATS_INIT_ID "mats.init.Id"}</b>: The initiatorId of this MatsFlow;
 * <code>matsInitiate.from(initiatorId)</code>.</li>
 * <li><b>{@link #MDC_MATS_AUDIT "mats.Audit"}</b>: Whether this Mats flow should be audited.</li>
 * <li><b>{@link #MDC_MATS_INTERACTIVE "mats.Interactive"}</b>: Whether this Mats flow should be treated as
 * "interactive", meaning that a human is actively waiting for its execution.</li>
 * <li><b>{@link #MDC_MATS_PERSISTENT "mats.Persistent"}</b>: Whether the messaging system should use persistent (as
 * opposed to non-persistent) message passing, i.e. store to disk to survive a crash.</li>
 * </ul>
 * <b>Metrics for message production</b> (note how these compare to the reception of a message, on the "Message
 * Received" loglines):
 * <ul>
 * <li><b>{@link #MDC_MATS_OUT_TIME_TOTAL "mats.out.Total.ms"}</b>: Total time taken to produce the message, all of the
 * Mats envelope ("MatsTrace"), serializing, compressing and producing the message system message.</li>
 * <li><b>{@link #MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE "mats.out.EnvelopeProduce.ms"}</b>: Part of the total time taken to
 * produce the Mats envelope ("MatsTrace"), including serialization of all constituents: DTO, STO and any Trace
 * Properties.</li>
 * <li><b>{@link #MDC_MATS_OUT_TIME_ENVELOPE_SERIAL "mats.out.EnvelopeSerial.ms"}</b>: Part of the total time taken to
 * serialize the Mats envelope.</li>
 * <li><b>{@link #MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL "mats.out.EnvelopeSerial.bytes"}</b>: Size of the serialized Mats
 * envelope.</li>
 * <li><b>{@link #MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS "mats.out.EnvelopeCompress.ms"}</b>: Part of the total time taken
 * to compress the serialized Mats envelope</li>
 * <li><b>{@link #MDC_MATS_OUT_SIZE_ENVELOPE_WIRE "mats.out.EnvelopeWire.bytes"}</b>: Size of the compressed serialized
 * Mats envelope.</li>
 * <li><b>{@link #MDC_MATS_OUT_TIME_MSGSYS "mats.out.MsgSys.ms"}</b>: Part of the total time taken to produce the
 * message system message.</li>
 * </ul>
 *
 * <b>Note:</b> Both Initiation and Stage completed can produce messages. For the very common case where this is just a
 * single message (a single initiated message starting a Mats flow, or a REQUEST or REPLY message from a Stage (in a
 * flow)), the "Per message" log line is combined with the Initiation or Stage Complete log line - on the message part,
 * the two lines are separated by a "/n", while each of the properties that come with the respective log line either are
 * common - i.e. they have the same name, and would have the same value - or they are differently named, so that the
 * combination does not imply any "overwrite" of a property name. If you search for a property that only occurs on "Per
 * message" log lines (you e.g. want to count, or select, all outgoing message), you will get hits for log lines that
 * are in the "combined" form (e.g. initiation producing a single message), and for log lines for individual messages
 * (i.e. where an initiation produced two messages, which results in three log lines: 1 for the initiation, and 2 for
 * the messages).
 * <p />
 * <b>Note: This interceptor (SLF4J Logger with Metrics on MDC) has special support in <code>JmsMatsFactory</code>: If
 * present on the classpath, it is automatically installed using the {@link #install(MatsInterceptable)} install
 * method.</b> This interceptor implements the special marker-interface {@link MatsLoggingInterceptor} of which there
 * can only be one instance installed in a <code>JmsMatsFactory</code> - implying that if you want a different type of
 * logging, you may implement a custom variant (either subclassing this, on your own risk, or start from scratch), and
 * simply install it, leading to this instance being removed (or just not have this variant on the classpath).
 *
 * @author Endre Stølsvik - 2021-02-07 12:45 - http://endre.stolsvik.com
 */
public class MatsMetricsLoggingInterceptor
        implements MatsLoggingInterceptor, MatsInitiateInterceptor, MatsStageInterceptor {

    public static final Logger log_init = LoggerFactory.getLogger("io.mats3.log.init");
    public static final Logger log_stage = LoggerFactory.getLogger("io.mats3.log.stage");

    public static final String LOG_PREFIX = "#MATSLOG# ";

    public static final MatsMetricsLoggingInterceptor INSTANCE = new MatsMetricsLoggingInterceptor();

    // Not using "mats." prefix for "traceId", as it is hopefully generic yet specific
    // enough that it might be used in similar applications.
    public static final String MDC_TRACE_ID = "traceId";

    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // String MDC_MATS_APP_NAME = "mats.AppName";
    // String MDC_MATS_APP_VERSION = "mats.AppVersion";

    // ============================================================================================================
    // ===== COMMON for Initiate and Stage Completed, with timings:
    // ... Metrics:
    public static final String MDC_MATS_COMPLETE_TIME_TOTAL_EXECUTION = "mats.exec.Total.ms";
    public static final String MDC_MATS_COMPLETE_TIME_USER_LAMBDA = "mats.exec.UserLambda.ms";
    public static final String MDC_MATS_COMPLETE_TIME_OUT = "mats.exec.Out.ms";
    public static final String MDC_MATS_COMPLETE_QUANTITY_OUT = "mats.exec.Out.quantity";
    public static final String MDC_MATS_COMPLETE_TIME_DB_COMMIT = "mats.exec.DbCommit.ms";
    public static final String MDC_MATS_COMPLETE_TIME_MSG_SYS_COMMIT = "mats.exec.MsgSysCommit.ms";
    public static final String MDC_MATS_COMPLETE_OPS_TIMING_PREFIX = "mats.exec.ops.time.";
    public static final String MDC_MATS_COMPLETE_OPS_MEASURE_PREFIX = "mats.exec.ops.measure.";

    // ============================================================================================================
    // ===== For Initiate Completed, with timings:
    // 'true' on a single logline per completed initiation:
    public static final String MDC_MATS_INITIATE_COMPLETED = "mats.InitiateCompleted";

    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // MDC_MATS_INIT = "mats.Init"; // 'true' on any loglines involving Initialization (also within Stages)

    // ============================================================================================================
    // ===== For Receiving a message
    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // MDC_MATS_STAGE = "mats.Stage"; // 'true' on Stage Processor threads (set fixed on the consumer thread)
    // MDC_MATS_STAGE_ID = "mats.StageId";
    // MDC_MATS_IN_MESSAGE_SYSTEM_ID = "mats.in.MsgSysId";
    // MDC_TRACE_ID = "traceId"

    // 'true' on a single logline per received message:
    public static final String MDC_MATS_MESSAGE_RECEIVED = "mats.MessageReceived";

    public static final String MDC_MATS_IN_FROM_APP_NAME = "mats.in.from.App";
    public static final String MDC_MATS_IN_FROM_ID = "mats.in.from.Id";
    // NOTICE: NOT using MDC_MATS_IN_TO_APP, as that is identical to MDC_MATS_APP_NAME
    // NOTICE: NOT using MDC_MATS_IN_TO_ID, as that is identical to MDC_MATS_STAGE_ID
    public static final String MDC_MATS_IN_MATS_MESSAGE_ID = "mats.in.MatsMsgId";

    // ... Metrics:
    public static final String MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL = "mats.in.TotalPreprocDeserial.ms";
    public static final String MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT = "mats.in.MsgSysDeconstruct.ms";
    public static final String MDC_MATS_IN_SIZE_ENVELOPE_WIRE = "mats.in.EnvelopeWire.bytes";
    public static final String MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS = "mats.in.EnvelopeDecompress.ms";
    public static final String MDC_MATS_IN_SIZE_ENVELOPE_SERIAL = "mats.in.EnvelopeSerial.bytes";
    public static final String MDC_MATS_IN_TIME_ENVELOPE_DESERIAL = "mats.in.EnvelopeDeserial.ms";
    public static final String MDC_MATS_IN_TIME_MSG_AND_STATE_DESERIAL = "mats.in.MsgAndStateDeserial.ms";

    // ============================================================================================================
    // ===== For Stage Completed
    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // MDC_MATS_STAGE_ID = "mats.StageId";
    // MDC_TRACE_ID = "traceId"
    // 'true' on a single logline per completed stage
    public static final String MDC_MATS_STAGE_COMPLETED = "mats.StageCompleted";
    // Set on a single logline per completed
    public static final String MDC_MATS_COMPLETE_PROCESS_RESULT = "mats.ProcessResult";

    // ..... specific Stage complete metric - along with the other ".exec." from the COMMON Init/Stage Complete
    // Note that this is the same timing as the MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL
    public static final String MDC_MATS_COMPLETE_TIME_TOTAL_PREPROC_AND_DESERIAL = "mats.exec.TotalPreprocDeserial.ms";

    // ============================================================================================================
    // ===== For Sending a single message (from init, or stage) - one line per message
    // 'true' on single logline per msg:
    public static final String MDC_MATS_MESSAGE_SENT = "mats.MessageSent";
    // Set on single logline per msg: INIT, STAGE, STAGE_INIT
    public static final String MDC_MATS_DISPATCH_TYPE = "mats.DispatchType";

    public static final String MDC_MATS_OUT_MATS_MESSAGE_ID = "mats.out.MatsMsgId";
    public static final String MDC_MATS_OUT_MESSAGE_SYSTEM_ID = "mats.out.MsgSysId";

    // NOTICE: NOT using MDC_MATS_OUT_FROM_APP / "mats.out.from.App", as that is 'this' App: MDC_MATS_APP_NAME.
    // NOTICE: MDC_MATS_OUT_FROM_ID == MDC_MATS_STAGE_ID for Stages - but there is no corresponding for InitiatorId.
    public static final String MDC_MATS_OUT_FROM_ID = "mats.out.from.Id"; // "this" EndpointId/StageId/InitiatorId.
    public static final String MDC_MATS_OUT_TO_ID = "mats.out.to.Id"; // target EndpointId/StageId.
    // NOTICE: NOT using MDC_MATS_OUT_TO_APP, since we do not know which app will consume it.

    // ... Metrics:
    public static final String MDC_MATS_OUT_TIME_TOTAL = "mats.out.Total.ms";
    public static final String MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE = "mats.out.EnvelopeProduce.ms";
    public static final String MDC_MATS_OUT_TIME_ENVELOPE_SERIAL = "mats.out.EnvelopeSerial.ms";
    public static final String MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL = "mats.out.EnvelopeSerial.bytes";
    public static final String MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS = "mats.out.EnvelopeCompress.ms";
    public static final String MDC_MATS_OUT_SIZE_ENVELOPE_WIRE = "mats.out.EnvelopeWire.bytes";
    public static final String MDC_MATS_OUT_TIME_MSGSYS = "mats.out.MsgSys.ms";

    // ============================================================================================================
    // ===== For both Message Received and Message Send (note: part of root MatsTrace, common for all msgs in flow)
    public static final String MDC_MATS_INIT_APP = "mats.init.App";
    public static final String MDC_MATS_INIT_ID = "mats.init.Id"; // matsInitiate.from(initiatorId).
    public static final String MDC_MATS_AUDIT = "mats.Audit";
    public static final String MDC_MATS_INTERACTIVE = "mats.Interactive";
    public static final String MDC_MATS_PERSISTENT = "mats.Persistent";

    /**
     * Adds the singleton {@link #INSTANCE} as both Initiation and Stage interceptors.
     *
     * @param matsInterceptableMatsFactory
     *            the {@link MatsInterceptable} MatsFactory to add it to.
     */
    public static void install(MatsInterceptable matsInterceptableMatsFactory) {
        matsInterceptableMatsFactory.addInitiationInterceptor(INSTANCE);
        matsInterceptableMatsFactory.addStageInterceptor(INSTANCE);
    }

    /**
     * Removes the singleton {@link #INSTANCE} as both Initiation and Stage interceptors.
     *
     * @param matsInterceptableMatsFactory
     *            the {@link MatsInterceptable} to remove it from.
     */
    public static void remove(MatsInterceptable matsInterceptableMatsFactory) {
        matsInterceptableMatsFactory.removeInitiationInterceptor(INSTANCE);
        matsInterceptableMatsFactory.removeStageInterceptor(INSTANCE);
    }

    @Override
    public void initiateCompleted(InitiateCompletedContext ctx) {
        // :: First output the user measurements
        outputMeasurementsLoglines(log_init, ctx);

        // :: Then the "completed" logline, either combined with a single message - or multiple lines for multiple msgs
        try {
            MDC.put(MDC_MATS_INITIATE_COMPLETED, "true");
            List<MatsSentOutgoingMessage> outgoingMessages = ctx.getOutgoingMessages();
            String messageSenderName = ctx.getInitiator().getParentFactory()
                    .getFactoryConfig().getName() + "|" + ctx.getInitiator().getName();

            commonStageAndInitiateCompleted(ctx, "", log_init, outgoingMessages, messageSenderName, "", 0L);
        }
        finally {
            MDC.remove(MDC_MATS_INITIATE_COMPLETED);
        }
    }

    @Override
    public void stageReceived(StageReceivedContext ctx) {
        try {
            MDC.put(MDC_MATS_MESSAGE_RECEIVED, "true");
            ProcessContext<Object> processContext = ctx.getProcessContext();

            long sumNanosPieces = ctx.getMessageSystemDeconstructNanos()
                    + ctx.getEnvelopeDecompressionNanos()
                    + ctx.getEnvelopeDeserializationNanos()
                    + ctx.getMessageAndStateDeserializationNanos();

            MDC.put(MDC_MATS_INIT_APP, processContext.getInitiatingAppName());
            MDC.put(MDC_MATS_INIT_ID, processContext.getInitiatorId());
            MDC.put(MDC_MATS_AUDIT, Boolean.toString(!processContext.isNoAudit()));
            MDC.put(MDC_MATS_PERSISTENT, Boolean.toString(!processContext.isNonPersistent()));
            MDC.put(MDC_MATS_INTERACTIVE, Boolean.toString(processContext.isInteractive()));

            MDC.put(MDC_MATS_IN_FROM_APP_NAME, processContext.getFromAppName());
            MDC.put(MDC_MATS_IN_FROM_ID, processContext.getFromStageId());
            MDC.put(MDC_MATS_IN_MATS_MESSAGE_ID, processContext.getMatsMessageId());

            // Total:
            MDC.put(MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL,
                    msS(ctx.getTotalPreprocessAndDeserializeNanos()));

            // Breakdown:
            MDC.put(MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT, msS(ctx.getMessageSystemDeconstructNanos()));
            MDC.put(MDC_MATS_IN_SIZE_ENVELOPE_WIRE, Long.toString(ctx.getEnvelopeWireSize()));
            MDC.put(MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS, msS(ctx.getEnvelopeDecompressionNanos()));
            MDC.put(MDC_MATS_IN_SIZE_ENVELOPE_SERIAL, Long.toString(ctx.getEnvelopeSerializedSize()));
            MDC.put(MDC_MATS_IN_TIME_ENVELOPE_DESERIAL, msS(ctx.getEnvelopeDeserializationNanos()));
            MDC.put(MDC_MATS_IN_TIME_MSG_AND_STATE_DESERIAL, msS(ctx.getMessageAndStateDeserializationNanos()));

            log_stage.info(LOG_PREFIX + "RECEIVED [" + ctx.getIncomingMessageType()
                    + "] message from [" + processContext.getFromStageId()
                    + "@" + processContext.getFromAppName() + ",v." + processContext.getFromAppVersion()
                    + "], totPreprocAndDeserial:[" + ms(ctx.getTotalPreprocessAndDeserializeNanos())
                    + "] || breakdown: msgSysDeconstruct:[" + ms(ctx.getMessageSystemDeconstructNanos())
                    + " ms]->envelopeWireSize:[" + ctx.getEnvelopeWireSize()
                    + " B]->decomp:[" + ms(ctx.getEnvelopeDecompressionNanos())
                    + " ms]->serialSize:[" + ctx.getEnvelopeSerializedSize()
                    + " B]->deserial:[" + ms(ctx.getEnvelopeDeserializationNanos())
                    + " ms]->(envelope)->dto&stoDeserial:[" + ms(ctx.getMessageAndStateDeserializationNanos())
                    + " ms] - sum pieces:[" + ms(sumNanosPieces)
                    + " ms], diff:[" + ms(ctx.getTotalPreprocessAndDeserializeNanos() - sumNanosPieces)
                    + " ms]");
        }
        finally {
            MDC.remove(MDC_MATS_MESSAGE_RECEIVED);

            MDC.remove(MDC_MATS_INIT_APP);
            MDC.remove(MDC_MATS_INIT_ID);
            MDC.remove(MDC_MATS_AUDIT);
            MDC.remove(MDC_MATS_PERSISTENT);
            MDC.remove(MDC_MATS_INTERACTIVE);

            MDC.remove(MDC_MATS_IN_FROM_APP_NAME);
            MDC.remove(MDC_MATS_IN_FROM_ID);
            MDC.remove(MDC_MATS_IN_MATS_MESSAGE_ID);

            MDC.remove(MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL);

            MDC.remove(MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT);
            MDC.remove(MDC_MATS_IN_SIZE_ENVELOPE_WIRE);
            MDC.remove(MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS);
            MDC.remove(MDC_MATS_IN_SIZE_ENVELOPE_SERIAL);
            MDC.remove(MDC_MATS_IN_TIME_ENVELOPE_DESERIAL);
            MDC.remove(MDC_MATS_IN_TIME_MSG_AND_STATE_DESERIAL);
        }
    }

    @Override
    public void stageCompleted(StageCompletedContext ctx) {
        // :: First output the user measurements
        outputMeasurementsLoglines(log_stage, ctx);

        // :: Then the "completed" logline, either combined with a single message - or multiple lines for multiple msgs
        try {
            MDC.put(MDC_MATS_STAGE_COMPLETED, "true");
            List<MatsSentOutgoingMessage> outgoingMessages = ctx
                    .getOutgoingMessages();

            String messageSenderName = ctx.getStage().getParentEndpoint().getParentFactory()
                    .getFactoryConfig().getName();

            // :: Specific metric for stage completed
            String extraBreakdown = " totPreprocAndDeserial:[" + ms(ctx.getTotalPreprocessAndDeserializeNanos())
                    + " ms],";
            long extraNanosBreakdown = ctx.getTotalPreprocessAndDeserializeNanos();
            MDC.put(MDC_MATS_COMPLETE_TIME_TOTAL_PREPROC_AND_DESERIAL, msS(ctx
                    .getTotalPreprocessAndDeserializeNanos()));

            MDC.put(MDC_MATS_COMPLETE_PROCESS_RESULT, ctx.getProcessResult().toString());

            commonStageAndInitiateCompleted(ctx, " with result " + ctx.getProcessResult(), log_stage,
                    outgoingMessages, messageSenderName, extraBreakdown, extraNanosBreakdown);
        }
        finally {
            MDC.remove(MDC_MATS_STAGE_COMPLETED);
            MDC.remove(MDC_MATS_COMPLETE_TIME_TOTAL_PREPROC_AND_DESERIAL);
            MDC.remove(MDC_MATS_COMPLETE_PROCESS_RESULT);
        }
    }

    private void outputMeasurementsLoglines(Logger logger, CommonCompletedContext ctx) {
        // :: Timings
        for (MatsTimingMeasurement measurement : ctx.getTimingMeasurements()) {
            String metricId = measurement.getMetricId();
            String metricDescription = measurement.getMetricDescription();
            String[] labelKeyValue = measurement.getLabelKeyValue();

            String measure = msS(measurement.getNanos());
            String baseUnit = "ms";

            outputMeasureLogline(logger, "TIMING ", MDC_MATS_COMPLETE_OPS_TIMING_PREFIX, metricId,
                    baseUnit, measure, metricDescription, labelKeyValue);
        }
        // :: Measurements
        for (MatsMeasurement measurement : ctx.getMeasurements()) {
            String metricId = measurement.getMetricId();
            String metricDescription = measurement.getMetricDescription();
            String[] labelKeyValue = measurement.getLabelKeyValue();

            String measure = Double.toString(measurement.getMeasure());
            String baseUnit = measurement.getBaseUnit();

            outputMeasureLogline(logger, "MEASURE ", MDC_MATS_COMPLETE_OPS_MEASURE_PREFIX, metricId,
                    baseUnit, measure, metricDescription, labelKeyValue);
        }
    }

    private void outputMeasureLogline(Logger logger, String what, String mdcPrefix, String metricId,
            String baseUnit, String measure, String metricDescription, String[] labelKeyValue) {
        Collection<String> mdcKeysToClear = labelKeyValue.length > 0
                ? new ArrayList<>()
                : null;
        String mdcKey = mdcPrefix + metricId + "." + baseUnit;
        try {
            MDC.put(mdcKey, measure);

            StringBuilder buf = new StringBuilder(128);
            buf.append(LOG_PREFIX)
                    .append(what)
                    .append(metricId)
                    .append(":[")
                    .append(measure)
                    .append(' ')
                    .append(baseUnit)
                    .append("] (\"")
                    .append(metricDescription)
                    .append("\")");

            for (int i = 0; i < labelKeyValue.length; i += 2) {
                String labelKey = labelKeyValue[i];
                String labelValue = labelKeyValue[i + 1];
                String mdcLabelKey = mdcPrefix + metricId + ".tag." + labelKey;
                MDC.put(mdcLabelKey, labelValue);
                mdcKeysToClear.add(mdcLabelKey);
                buf.append(' ')
                        .append(labelKey)
                        .append(':')
                        .append(labelValue);
            }

            logger.info(buf.toString());
        }
        finally {
            MDC.remove(mdcKey);
            if (mdcKeysToClear != null) {
                for (String mdcLabelKey : mdcKeysToClear) {
                    MDC.remove(mdcLabelKey);
                }
            }
        }
    }

    private void commonStageAndInitiateCompleted(CommonCompletedContext ctx, String extraResult, Logger logger,
            List<MatsSentOutgoingMessage> outgoingMessages, String messageSenderName,
            String extraBreakdown, long extraNanosBreakdown) {

        String what = extraBreakdown.isEmpty() ? "INIT" : "STAGE";

        // ?: Do we have a Throwable, indicating that processing (stage or init) failed?
        if (ctx.getThrowable().isPresent()) {
            // -> Yes, we have a Throwable
            // :: Output the 'completed' (now FAILED) line
            // NOTE: We do NOT output any messages, even if there are any (they can have been produced before the
            // error occurred), as they will NOT have been sent, and the log lines are supposed to represent
            // actual messages that have been put on the wire.
            Throwable t = ctx.getThrowable().get();
            completedLog(ctx, LOG_PREFIX + what + " !!FAILED!!" + extraResult, extraBreakdown, extraNanosBreakdown,
                    Collections.emptyList(), Level.ERROR, logger, t, "");
        }
        else if (outgoingMessages.size() != 1) {
            // -> Yes, >1 or 0 messages

            // :: Output the per-message logline (there might be >1, or 0, messages)
            for (MatsSentOutgoingMessage outgoingMessage : ctx.getOutgoingMessages()) {
                msgMdcLog(outgoingMessage, () -> log_init.info(LOG_PREFIX
                        + msgLogLine(messageSenderName, outgoingMessage)));
            }

            // :: Output the 'completed' line
            completedLog(ctx, LOG_PREFIX + what + " completed" + extraResult, extraBreakdown, extraNanosBreakdown,
                    outgoingMessages, Level.INFO, logger, null, "");
        }
        else {
            // -> Only 1 message and no Throwable: Concat the two lines.
            msgMdcLog(outgoingMessages.get(0), () -> {
                String msgLine = msgLogLine(messageSenderName, outgoingMessages.get(0));
                completedLog(ctx, LOG_PREFIX + what + " completed" + extraResult, extraBreakdown, extraNanosBreakdown,
                        outgoingMessages, Level.INFO, logger, null, "\n    " + LOG_PREFIX + msgLine);
            });
        }
    }

    private enum Level {
        INFO, ERROR
    }

    private void completedLog(CommonCompletedContext ctx, String logPrefix, String extraBreakdown,
            long extraNanosBreakdown, List<MatsSentOutgoingMessage> outgoingMessages, Level level, Logger log,
            Throwable t, String logPostfix) {

        /*
         * NOTE! The timings are a bit user-unfriendly wrt. "user lambda" timing, as this includes the production of
         * outgoing envelopes, including DTO, STO and TraceProps serialization, due to how it must be implemented. Thus,
         * we do some tricking here to get more relevant "split up" of the separate pieces.
         */
        // :: Find total DtoAndSto Serialization of outgoing messages
        long nanosTaken_SumDtoAndStoSerialNanos = 0;
        for (MatsSentOutgoingMessage msg : outgoingMessages) {
            nanosTaken_SumDtoAndStoSerialNanos += msg.getEnvelopeProduceNanos();
        }

        // :: Subtract the total DtoAndSto Serialization from the user lambda time.
        long nanosTaken_UserLambdaAlone = ctx.getUserLambdaNanos() - nanosTaken_SumDtoAndStoSerialNanos;

        // :: Sum up the total "message handling": Dto&Sto + envelope serial + msg.sys. handling.
        long nanosTaken_SumMessageOutHandling = nanosTaken_SumDtoAndStoSerialNanos
                + ctx.getSumEnvelopeSerializationAndCompressionNanos()
                + ctx.getSumMessageSystemProductionAndSendNanos();

        // Sum of all the pieces, to compare against the total execution time.
        long nanosTaken_SumPieces = extraNanosBreakdown
                + nanosTaken_UserLambdaAlone
                + nanosTaken_SumMessageOutHandling
                + ctx.getDbCommitNanos()
                + ctx.getMessageSystemCommitNanos();

        String numMessagesText;
        switch (outgoingMessages.size()) {
            case 0:
                numMessagesText = "no outgoing messages";
                break;
            case 1:
                numMessagesText = "single outgoing " + outgoingMessages.get(0).getMessageType() + " message";
                break;
            default:
                numMessagesText = "[" + outgoingMessages.size() + "] outgoing messages";
        }

        try {
            MDC.put(MDC_MATS_COMPLETE_TIME_TOTAL_EXECUTION, msS(ctx.getTotalExecutionNanos()));
            MDC.put(MDC_MATS_COMPLETE_TIME_USER_LAMBDA, msS(nanosTaken_UserLambdaAlone));
            MDC.put(MDC_MATS_COMPLETE_TIME_OUT, msS(nanosTaken_SumMessageOutHandling));
            MDC.put(MDC_MATS_COMPLETE_QUANTITY_OUT, String.valueOf(outgoingMessages.size()));
            MDC.put(MDC_MATS_COMPLETE_TIME_DB_COMMIT, msS(ctx.getDbCommitNanos()));
            MDC.put(MDC_MATS_COMPLETE_TIME_MSG_SYS_COMMIT, msS(ctx.getMessageSystemCommitNanos()));

            String msg = logPrefix
                    + ", " + numMessagesText
                    + ", total:[" + ms(ctx.getTotalExecutionNanos()) + " ms]"
                    + " || breakdown:"
                    + extraBreakdown
                    + " userLambda (excl. produceEnvelopes):[" + ms(nanosTaken_UserLambdaAlone)
                    + " ms], msgsOut:[" + ms(nanosTaken_SumMessageOutHandling)
                    + " ms], dbCommit:[" + ms(ctx.getDbCommitNanos())
                    + " ms], msgSysCommit:[" + ms(ctx.getMessageSystemCommitNanos())
                    + " ms] - sum pieces:[" + ms(nanosTaken_SumPieces)
                    + " ms], diff:[" + ms(ctx.getTotalExecutionNanos() - nanosTaken_SumPieces)
                    + " ms]"
                    + logPostfix;

            // ?: Log at what level?
            if (level == Level.INFO) {
                // -> INFO
                log.info(msg);
            }
            else {
                // -> ERROR
                log.error(msg, t);
            }
        }
        finally {
            MDC.remove(MDC_MATS_COMPLETE_TIME_TOTAL_EXECUTION);
            MDC.remove(MDC_MATS_COMPLETE_TIME_USER_LAMBDA);
            MDC.remove(MDC_MATS_COMPLETE_TIME_OUT);
            MDC.remove(MDC_MATS_COMPLETE_QUANTITY_OUT);
            MDC.remove(MDC_MATS_COMPLETE_TIME_DB_COMMIT);
            MDC.remove(MDC_MATS_COMPLETE_TIME_MSG_SYS_COMMIT);
        }
    }

    private void msgMdcLog(MatsSentOutgoingMessage msg, Runnable runnable) {
        String existingTraceId = MDC.get(MDC_TRACE_ID);
        try {
            MDC.put(MDC_TRACE_ID, msg.getTraceId());

            MDC.put(MDC_MATS_MESSAGE_SENT, "true");
            MDC.put(MDC_MATS_DISPATCH_TYPE, msg.getDispatchType().toString());

            MDC.put(MDC_MATS_OUT_MATS_MESSAGE_ID, msg.getMatsMessageId());
            MDC.put(MDC_MATS_OUT_MESSAGE_SYSTEM_ID, msg.getSystemMessageId());

            MDC.put(MDC_MATS_INIT_APP, msg.getInitiatingAppName());
            MDC.put(MDC_MATS_INIT_ID, msg.getInitiatorId());
            MDC.put(MDC_MATS_OUT_FROM_ID, msg.getFrom());
            MDC.put(MDC_MATS_OUT_TO_ID, msg.getTo());
            MDC.put(MDC_MATS_AUDIT, Boolean.toString(!msg.isNoAudit()));
            MDC.put(MDC_MATS_PERSISTENT, Boolean.toString(!msg.isNonPersistent()));
            MDC.put(MDC_MATS_INTERACTIVE, Boolean.toString(msg.isInteractive()));

            // Metrics:
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE, msS(msg.getEnvelopeProduceNanos()));
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_SERIAL, msS(msg.getEnvelopeSerializationNanos()));
            MDC.put(MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL, Long.toString(msg.getEnvelopeSerializedSize()));
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS, msS(msg.getEnvelopeCompressionNanos()));
            MDC.put(MDC_MATS_OUT_SIZE_ENVELOPE_WIRE, Long.toString(msg.getEnvelopeWireSize()));
            MDC.put(MDC_MATS_OUT_TIME_MSGSYS, msS(msg.getMessageSystemProduceAndSendNanos()));
            long nanosTaken_Total = msg.getEnvelopeProduceNanos()
                    + msg.getEnvelopeSerializationNanos()
                    + msg.getEnvelopeCompressionNanos()
                    + msg.getMessageSystemProduceAndSendNanos();
            MDC.put(MDC_MATS_OUT_TIME_TOTAL, msS(nanosTaken_Total));

            // :: Actually run the Runnable
            runnable.run();
        }
        finally {

            // :: Restore MDC
            // TraceId
            if (existingTraceId == null) {
                MDC.remove(MDC_TRACE_ID);
            }
            else {
                MDC.put(MDC_TRACE_ID, existingTraceId);
            }
            MDC.remove(MDC_MATS_MESSAGE_SENT);
            MDC.remove(MDC_MATS_DISPATCH_TYPE);

            MDC.remove(MDC_MATS_OUT_MATS_MESSAGE_ID);
            MDC.remove(MDC_MATS_OUT_MESSAGE_SYSTEM_ID);

            MDC.remove(MDC_MATS_INIT_APP);
            MDC.remove(MDC_MATS_INIT_ID);
            MDC.remove(MDC_MATS_OUT_FROM_ID);
            MDC.remove(MDC_MATS_OUT_TO_ID);
            MDC.remove(MDC_MATS_AUDIT);
            MDC.remove(MDC_MATS_PERSISTENT);
            MDC.remove(MDC_MATS_INTERACTIVE);

            MDC.remove(MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE);
            MDC.remove(MDC_MATS_OUT_TIME_ENVELOPE_SERIAL);
            MDC.remove(MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL);
            MDC.remove(MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS);
            MDC.remove(MDC_MATS_OUT_SIZE_ENVELOPE_WIRE);
            MDC.remove(MDC_MATS_OUT_TIME_MSGSYS);
            MDC.remove(MDC_MATS_OUT_TIME_TOTAL);
        }
    }

    private String msgLogLine(String messageSenderName, MatsSentOutgoingMessage msg) {
        long nanosTaken_Total = msg.getEnvelopeProduceNanos()
                + msg.getEnvelopeSerializationNanos()
                + msg.getEnvelopeCompressionNanos()
                + msg.getMessageSystemProduceAndSendNanos();

        return msg.getDispatchType() + " outgoing " + msg.getMessageType()
                + " message from [" + messageSenderName + "|" + msg.getFrom()
                + "] -> [" + msg.getTo()
                + "], total:[" + ms(nanosTaken_Total)
                + " ms] || breakdown: produce:[" + ms(msg.getEnvelopeProduceNanos())
                + " ms]->(envelope)->serial:[" + ms(msg.getEnvelopeSerializationNanos())
                + " ms]->serialSize:[" + msg.getEnvelopeSerializedSize()
                + " B]->comp:[" + ms(msg.getEnvelopeCompressionNanos())
                + " ms]->envelopeWireSize:[" + msg.getEnvelopeWireSize()
                + " B]->msgSysConstruct&Send:[" + ms(msg.getMessageSystemProduceAndSendNanos())
                + " ms]";
    }

    private static String msS(long nanosTake) {
        return Double.toString(ms(nanosTake));
    }

    /**
     * Converts nanos to millis with a sane number of significant digits ("3.5" significant digits), but assuming that
     * this is not used to measure things that take less than 0.001 milliseconds (in which case it will be "rounded" to
     * 0.0001, 1e-4, as a special value). Takes care of handling the difference between 0 and >0 nanoseconds when
     * rounding - in that 1 nanosecond will become 0.0001 (1e-4 ms, which if used to measure things that are really
     * short lived might be magnitudes wrong), while 0 will be 0.0 exactly. Note that printing of a double always
     * include the ".0" (unless scientific notation kicks in), which can lead your interpretation astray when running
     * this over e.g. the number 555_555_555, which will print as "556.0", and 5_555_555_555 prints "5560.0".
     */
    private static double ms(long nanosTaken) {
        if (nanosTaken == 0) {
            return 0.0;
        }
        // >=500_000 ms?
        if (nanosTaken >= 1_000_000L * 500_000) {
            // -> Yes, >500_000ms, thus chop into the integer part of the number (dropping 3 digits)
            // (note: printing of a double always include ".0"), e.g. 612000.0
            return Math.round(nanosTaken / 1_000_000_000d) * 1_000d;
        }
        // >=50_000 ms?
        if (nanosTaken >= 1_000_000L * 50_000) {
            // -> Yes, >50_000ms, thus chop into the integer part of the number (dropping 2 digits)
            // (note: printing of a double always include ".0"), e.g. 61200.0
            return Math.round(nanosTaken / 100_000_000d) * 100d;
        }
        // >=5_000 ms?
        if (nanosTaken >= 1_000_000L * 5_000) {
            // -> Yes, >5_000ms, thus chop into the integer part of the number (dropping 1 digit)
            // (note: printing of a double always include ".0"), e.g. 6120.0
            return Math.round(nanosTaken / 10_000_000d) * 10d;
        }
        // >=500 ms?
        if (nanosTaken >= 1_000_000L * 500) {
            // -> Yes, >500ms, so chop off fraction entirely
            // (note: printing of a double always include ".0"), e.g. 612.0
            return Math.round(nanosTaken / 1_000_000d);
        }
        // >=50 ms?
        if (nanosTaken >= 1_000_000L * 50) {
            // -> Yes, >50ms, so use 1 decimal, e.g. 61.2
            return Math.round(nanosTaken / 100_000d) / 10d;
        }
        // >=5 ms?
        if (nanosTaken >= 1_000_000L * 5) {
            // -> Yes, >5ms, so use 2 decimal, e.g. 6.12
            return Math.round(nanosTaken / 10_000d) / 100d;
        }
        // E-> <5 ms
        // Use 3 decimals, but at least '0.0001' if round to zero, so as to point out that it is NOT 0.0d
        // e.g. 0.612
        return Math.max(Math.round(nanosTaken / 1_000d) / 1_000d, 0.0001d);
    }
}
