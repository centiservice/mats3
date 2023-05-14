package io.mats3.intercept.logging;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.mats3.MatsEndpoint.DetachedProcessContext;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsInitiator;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.api.intercept.CommonCompletedContext;
import io.mats3.api.intercept.CommonCompletedContext.MatsMeasurement;
import io.mats3.api.intercept.CommonCompletedContext.MatsTimingMeasurement;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInterceptable;
import io.mats3.api.intercept.MatsInterceptable.MatsLoggingInterceptor;
import io.mats3.api.intercept.MatsInterceptable.MatsMetricsInterceptor;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MessageType;
import io.mats3.api.intercept.MatsStageInterceptor;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext.ProcessResult;

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
 * <code><b>"mats.Stage"</b></code> (set fixed to 'true' for all Mats Stage processors). If initiation within a stage,
 * both are set.</li>
 * <li><code><b>"mats.CallNo"</b></code>: The "Call Number", which starts at 0 for the initiation, and then 1 for first
 * stage processing. (It refers to the "calls", i.e. passed messages.)</li>
 * <li>For Stage processors: <code><b>"mats.StageId"</b></code>: Set fixed to the stageId for all Mats Stage
 * processors.</li>
 * <li>For Stage processors: <code><b>"mats.StageIndex"</b></code>: Set fixed to the stage index for all Mats Stage
 * processors - '0' for the initial Stage, '1' for stage1 etc.</li>
 * </ul>
 *
 *
 * <h2>MDC Properties for Initiate Complete:</h2> Notice: Initiate Complete is rather similar to Stage Complete.
 * <ul>
 * <li><b>{@link #MDC_MATS_INITIATE_COMPLETED "mats.InitiateCompleted"}</b>: Present <i>on a single logline</i> per
 * completed initiation - <i>can be used to count initiations</i>. The value is same as
 * {@link #MDC_MATS_COMPLETE_TIME_TOTAL "mats.exec.Total.ms"}, see Metrics below. Assuming each initiation produces one
 * message, and hence one flow, this count should be identical to the count of {@link #MDC_MATS_FLOW_COMPLETED}.
 * However, an initiation can produce multiple messages (or zero), as described in
 * {@link #MDC_MATS_COMPLETE_QUANTITY_OUT}, thus if you sum the quantity value of lines that have this property set, the
 * result should actually be identical to flows completed.</li>
 * <li><b>{@link #MDC_INIT_OR_STAGE_ID "mats.InitOrStageId"}</b>: The <i>initiatorId</i> ("fromId") from the first
 * (typically sole) message in an initiation. It should be common that all messages in a single initiation have the same
 * initiatorId. If there are no sent messages, the value for an initiation will be
 * {@link MatsMetricsInterceptor#INITIATOR_ID_WHEN_NO_OUTGOING_MESSAGES "_no_outgoing_messages_"}. This property is also
 * set on the sent messages. For an initiation, this will be identical to {@link #MDC_MATS_INIT_ID "mats.init.Id"}, see
 * below.</li>
 * <li><b>{@link #MDC_TRACE_ID "traceId"}</b>: <i>(Flow prop)</i> Set for an initiation from when it is set in the user
 * code performing the initiation (reset to whatever it was upon exit of initiation lambda)</li>
 * <li><b>{@link #MDC_MATS_INIT_APP "mats.init.App"}</b>: <i>(Flow prop)</i> For an initiation, this is the current app,
 * so identical to <code>"mats.AppName"</code>.</li>
 * <li><b>{@link #MDC_MATS_INIT_ID "mats.init.Id"}</b>: <i>(Flow prop)</i> For an initiation, this will be identical to
 * {@link #MDC_INIT_OR_STAGE_ID "mats.InitOrStageId"}, see above.</li>
 * <li><b>{@link #MDC_MATS_INTERACTIVE "mats.Interactive"}</b>: <i>(Flow prop)</i> This will be the value of the
 * interactive flag of the first (typically sole) message of an initiation. It should be common that all messages in a
 * single initiation have the same value. If there are no sent messages, the value for an initiation will be
 * <code>false</code>.</li>
 * </ul>
 * <b>Metrics for the execution of the initiation</b> (very similar to the metrics for a stage processing):
 * <ul>
 * <li><b>{@link #MDC_MATS_VERSION "mats.Version"}</b>: Mats implementation version, as gotten by
 * {@link FactoryConfig#getMatsImplementationVersion()}.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_TOTAL "mats.exec.Total.ms"}</b>: Total time taken for the initiation to
 * complete - including both user code and all system code including commits. The same value is present on
 * {@link #MDC_MATS_INITIATE_COMPLETED "mats.InitiateCompleted"}, see above.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_USER_LAMBDA "mats.exec.UserLambda.ms"}</b>: Part of total time taken for the
 * actual user lambda, including e.g. any external IO like DB, but excluding all system code, in particular message
 * creation, and commits.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_OUT "mats.exec.Out.ms"}</b>: Part of total time taken for the creation and
 * serialization of Mats messages, and production <i>and sending</i> of "message system messages" (e.g. creating and
 * populating JMS Message plus <code>jmsProducer.send(..)</code> for the JMS implementation).</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_QUANTITY_OUT "mats.exec.Out.quantity"}</b>: Number of messages sent in this
 * initiation. Should most often be 1, but can be multiple, and also zero.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_SIZE_OUT_TOTAL_WIRE "mats.exec.Out.TotalWire.bytes"}</b>: The sum of
 * {@link #MDC_MATS_OUT_SIZE_TOTAL_WIRE} for all messages sent in this initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_DB_COMMIT "mats.exec.DbCommit.ms"}</b>: Part of total time taken for committing
 * DB.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_MSGSYS_COMMIT "mats.exec.MsgSysCommit.ms"}</b>: Part of total time taken for
 * committing the message system (e.g. <code>jmsSession.commit()</code> for the JMS implementation)</li>
 * </ul>
 * <b>User metrics:</b> Furthermore, any metrics (measurements and timings) set from within an initiation or stage will
 * be available as separate log lines, the metric being set on the MDC. The MDC-key for timings will be
 * <code>{@link #MDC_MATS_COMPLETE_OPS_TIMING_PREFIX "mats.exec.ops.time."}+{metricId}+".ms"</code> and for measurements
 * <code>{@link #MDC_MATS_COMPLETE_OPS_MEASURE_PREFIX "mats.exec.ops.measure."}+{metricId} + '.' + {baseUnit}</code>. If
 * labels/tags are set on a metric, the MDC-key will be <code>{metric MDC-key} + ".tag." + {labelKey}</code>. The name
 * of the constructed metric MDC-key is made available as value of the MDC-key {@link #MDC_MATS_COMPLETE_OPS_KEYNAME
 * "mats.exec.ops.key"}, this so that the metrics keys employed can be found more easily by searching for this static
 * key (Elastic have no easy way to find key names in an index using the query language itself). The description of the
 * metric is available under the MDC-key {@link #MDC_MATS_COMPLETE_OPS_DESCRIPTION "mats.exec.ops.description"}.<br/>
 * <br/>
 *
 * <h2>MDC Properties for Message Received:</h2>
 * <ul>
 * <li><b>{@link #MDC_MATS_MESSAGE_RECEIVED "mats.MessageReceived"}</b>: Present <i>on a single logline</i> per received
 * message - <i>can be used to count received messages</i>. This count should be identical to the count of
 * {@link #MDC_MATS_STAGE_COMPLETED}. The value is the same as {@link #MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL
 * "mats.in.TotalPreprocDeserial.ms"}, see Metrics below.</li>
 * <li><code><b>"mats.StageId"</b></code>: Always set on the Processor threads for a stage, so any logline output inside
 * a Mats stage will have this set.</li>
 * <li><b>{@link #MDC_TRACE_ID "traceId"}</b>: The Mats flow's traceId, set from the initiation.</li>
 * <li><code><b>"mats.in.MsgSysId"</b></code>: The messageId the messaging system assigned the message when it was
 * produced on the sender side (e.g JMSMessageID for the JMS implementation)</li>
 * <li><b>{@link #MDC_MATS_IN_MATS_MESSAGE_ID "mats.in.MatsMsgId"}</b>: The messageId the Mats system assigned the
 * message when it was produced on the sender side. Note that it consists of the Mats flow id + an individual part per
 * message in the flow.</li>
 * <li><b>{@link #MDC_MATS_IN_MESSAGE_TYPE "mats.in.MessageType"}</b>: The {@link MessageType MessageType} of the
 * incoming message, for example "SEND", "REQUEST" or "REPLY".</li>
 * <li><b>{@link #MDC_MATS_IN_FROM_APP_NAME "mats.in.from.App"}</b>: Which app this incoming message is from.</li>
 * <li><b>{@link #MDC_MATS_IN_FROM_ID "mats.in.from.Id"}</b>: Which initiatorId, endpointId or stageId this message is
 * from.</li>
 * <li><i>NOTICE: NOT using <code>"mats.in.to.app"</code>, as that is "this" App, and thus identical to
 * <code>"mats.AppName"</code>.</i></li>
 * <li><i>NOTICE: NOT using <code>"mats.in.to.id"</code>, as that is "this" StageId, and thus identical to
 * <code>"mats.StageId"</code>.</i></li>
 * </ul>
 * "Flow properties", i.e. common for all received and sent messages in a Mats flow (initiated, received on stage and
 * sent from stage):
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
 * <li><b>{@link #MDC_MATS_IN_TIME_SINCE_SENT "mats.in.SinceSent.ms"}</b>: Time taken from message was sent to it was
 * received. This metric gives the queue time, plus any other latency wrt. sending and committing on the sending side
 * and the transfer and reception on the receiving side.<b>This metric is susceptible to time skews between
 * nodes</b>.</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_SINCE_PRECEDING_ENDPOINT_STAGE "mats.in.PrecedEpStage.ms"}</b>: Time taken from the
 * sending of a message from the Stage immediately preceding this Stage <i>on the same Endpoint</i>, to the reception of
 * a message on this Stage, i.e. the time between stages of an Endpoint (but also between an Initiation REQUEST and the
 * replyTo-reception on the Terminator). This timing includes queue times and processing times of requested endpoints
 * happening in between the send and the receive, as well as any other latencies. For example, it is the time between
 * when EndpointA.Stage<b>2</b> performs a REQUEST to AnotherEndpointB, till the REPLY from that endpoint is received on
 * EndpointA.Stage<b>3</b> (There might be dozens of message passing and processings in between those two stages of the
 * same endpoint, as AnotherEndpointB might itself have a dozen stages, each performing some requests to yet other
 * endpoints). <b>This metric is susceptible to time skews between nodes</b>.</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL "mats.in.TotalPreprocDeserial.ms"}</b>: Total time taken
 * to preprocess and deserialize the incoming message. The same value is present on {@link #MDC_MATS_MESSAGE_RECEIVED
 * "mats.MessageReceived"}, see above.</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT "mats.in.MsgSysDeconstruct.ms"}</b>: Part of total time taken to
 * pick out the Mats pieces from the incoming message system message.</li>
 * <li><b>{@link #MDC_MATS_IN_SIZE_ENVELOPE_WIRE "mats.in.EnvelopeWire.bytes"}</b>: How big the incoming Mats envelope
 * ("MatsTrace") was in the incoming message system message, i.e. "on the wire".</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS "mats.in.EnvelopeDecompress.ms"}</b>: Part of total time taken to
 * decompress the Mats envelope (will be 0 if it was sent plain, and &gt; 0 if it was compressed).</li>
 * <li><b>{@link #MDC_MATS_IN_SIZE_ENVELOPE_SERIAL "mats.in.EnvelopeSerial.bytes"}</b>: How big the incoming Mats
 * envelope ("MatsTrace") is in its serialized form, after decompression.</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_ENVELOPE_DESERIAL "mats.in.EnvelopeDeserial.ms"}</b>: Part of total time taken to
 * deserialize the incoming serialized Mats envelope.</li>
 * <li><b>{@link #MDC_MATS_IN_TIME_DATA_AND_STATE_DESERIAL "mats.in.DataAndStateDeserial.ms"}</b>: Part of total time
 * taken to deserialize the data and state objects (DTO and STO) from the Mats envelope.</li>
 * <li><b>{@link #MDC_MATS_IN_SIZE_TOTAL_WIRE "mats.in.TotalWire.bytes"}</b>: Best approximation of the total message
 * system wire size (envelope + sideloads + any meta info set on the message). The overhead of the message system itself
 * will probably not be included.</li>
 * <li><b>{@link #MDC_MATS_IN_SIZE_STATE_SERIAL "mats.in.StateSerial.bytes"}</b>: The serialized size of the incoming
 * state object (STO). If there is no incoming state, <code>0</code> is returned - this is normal for initial stage of
 * an endpoint, unless an initiation sets initialState. If the serializer employs Strings, the returned value is
 * <code>String.length()</code>, which might not exactly be the number of bytes depending on the String contents.</li>
 * <li><b>{@link #MDC_MATS_IN_SIZE_DATA_SERIAL "mats.in.DataSerial.bytes"}</b>: The serialized size of the incoming data
 * object (DTO). Note that <code>null</code> might not return <code>0</code> bytes, depending on how the serializer
 * works. If the serializer employs Strings, the returned value is <code>String.length()</code>, which might not exactly
 * be the number of bytes depending on the String contents.</li>
 * </ul>
 *
 *
 * <h2>MDC Properties for Stage Complete:</h2> Notice: Stage Complete is rather similar to Initiate Complete.
 * <ul>
 * <li><b>{@link #MDC_MATS_STAGE_COMPLETED "mats.StageCompleted"}</b>: Present on a single logline per completed stage -
 * <i>can be used to count stage processings</i>. This count should be identical to the count of
 * {@link #MDC_MATS_MESSAGE_RECEIVED}. The value is same as {@link #MDC_MATS_COMPLETE_TIME_TOTAL "mats.exec.Total.ms"},
 * see Metrics below.</li>
 * <li><code><b>"mats.StageId"</b></code>: Always set on the Processor threads for a stage, so any logline output inside
 * a Mats stage will have this set.</li>
 * <li><b>{@link #MDC_MATS_PROCESS_RESULT "mats.ProcessResult"}</b>: the ProcessResult enum</li>
 * <li><b>{@link #MDC_INIT_OR_STAGE_ID "mats.InitOrStageId"}</b>: The StageId for the completed stage, thus identical to
 * "mats.StageId". The rationale for having this one more time is so that you can search for it across initiation
 * complete and stage complete, using a common key, as they are rather similar processes. Note that it is also set on
 * message sending.</li>
 * <li><b>{@link #MDC_TRACE_ID "traceId"}</b>: <i>(Flow prop)</i> The Mats flow's traceId, set from the initiation.</li>
 * <li><b>{@link #MDC_MATS_INIT_APP "mats.init.App"}</b>: <i>(Flow prop)</i> The application that initiated this Mats
 * Flow.</li>
 * <li><b>{@link #MDC_MATS_INIT_ID "mats.init.Id"}</b>: <i>(Flow prop)</i> The initiatorId for this Mats Flow, i.e. the
 * value of {@link MatsInitiate#from(String) matsInitiate.from(initiatorId)}.</li>
 * <li><b>{@link #MDC_MATS_INTERACTIVE "mats.Interactive"}</b>: <i>(Flow prop)</i> The value of the interactive flag for
 * this Mats Flow.</li>
 * </ul>
 * <b>Metrics for the processing of the stage</b> (very similar to the metrics for an initiation execution, but includes
 * metrics for the reception of a message):
 * <ul>
 * <li><b>{@link #MDC_MATS_VERSION "mats.Version"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_TOTAL "mats.exec.Total.ms"}</b>: Total time taken for the stage to complete -
 * including both user code and all system code including commits. The same value is present on
 * {@link #MDC_MATS_STAGE_COMPLETED "mats.StageCompleted"}, see above.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_TOTAL_PREPROC_AND_DESERIAL "mats.exec.TotalPreprocDeserial.ms"}</b>: Part of
 * the total time taken for the preprocessing and deserialization of the incoming message, same as the message received
 * logline's {@link #MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL "mats.in.TotalPreprocDeserial.ms"}, as that piece is
 * also part of the stage processing.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_USER_LAMBDA "mats.exec.UserLambda.ms"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_OUT "mats.exec.Out.ms"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_QUANTITY_OUT "mats.exec.Out.quantity"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_SIZE_OUT_TOTAL_WIRE "mats.exec.Out.TotalWire.bytes"}</b>: Same as for
 * initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_DB_COMMIT "mats.exec.DbCommit.ms"}</b>: Same as for initiation.</li>
 * <li><b>{@link #MDC_MATS_COMPLETE_TIME_MSGSYS_COMMIT "mats.exec.MsgSysCommit.ms"}</b>: Same as for initiation</li>
 * </ul>
 * <b>User metrics:</b> Furthermore, any metrics (measurements and timings) set from an initiation or stage will be
 * available as separate log lines - same as for initiations.<br/>
 *
 * <br/>
 * <h3>Extra Properties for Endpoint Complete:</h3> When any Stage of an Endpoint (typically the last) either REPLYs, or
 * stops the flow (neither sending a REQUEST, NEXT nor GOTO), the endpoint is completed.
 * <ul>
 * <li><b>{@link #MDC_MATS_ENDPOINT_COMPLETED "mats.EndpointCompleted"}</b>: Present on the same single logline as Stage
 * Completed if this also is the endpoint completion. Notice that the span from an initiation REQUEST to the
 * replyTo-reception on a Terminator is also counted as a EndpointCompleted. The value is the time taken from entry on
 * the initial stage of an endpoint, to stage completed on the final stage (or from Initiation REQUEST to
 * replyTo-reception on the Terminator). <b>This metric is susceptible to time skews between nodes</b>.</li>
 * </ul>
 *
 * <h3>Extra Properties for Flow Complete:</h3> When a Stage doesn't send any REPLY, REQUEST, NEXT or GOTO, it stops the
 * flow. This is the normal situation for a Terminator, but technically a flow may stop anywhere if the stage doesn't
 * send a flow message.
 * <ul>
 * <li><b>{@link #MDC_MATS_FLOW_COMPLETED "mats.FlowCompleted"}</b>: Present on the same single logline as Stage
 * Completed if this also is flow completion. <i>can be used to count mats flows</i>. This count has a very tight
 * relationship with {@link #MDC_MATS_INITIATE_COMPLETED}, read above. The value is the time taken from the flow was
 * initiated at a {@link MatsInitiator}, or from within a flow, to it ends. <b>This metric is susceptible to time skews
 * between nodes</b>.</li>
 * </ul>
 * <br/>
 *
 *
 * <h2>MDC Properties for Per created Message (both initiations and stage produced messages):</h2>
 * <ul>
 * <li><b>{@link #MDC_MATS_MESSAGE_SENT "mats.MessageSent"}</b>: Present on single logline per sent message - <i>can be
 * used to count sent messages.</i> The value is the same as {@link #MDC_MATS_OUT_TIME_TOTAL "mats.out.Total.ms"}, see
 * below.</li>
 * <li><b>{@link #MDC_MATS_DISPATCH_TYPE "mats.DispatchType"}</b>: The DispatchType enum; INIT, STAGE, STAGE_INIT</li>
 * <li><b>{@link #MDC_MATS_OUT_MATS_MESSAGE_ID "mats.out.MatsMsgId"}</b>: The messageId the Mats system gave the
 * message. Note that it consists of the Mats flow id + an individual part per message in the flow.</li>
 * <li><b>{@link #MDC_MATS_OUT_MESSAGE_SYSTEM_ID "mats.out.MsgSysId"}</b>: The messageId that the messaging system gave
 * the message - for the JMS Implementation, it is the JMSMessageId.</li>
 * <li><i>NOTICE: NOT using "mats.out.from.app", as that is 'this' App, and thus identical to:
 * <code>"mats.AppName"</code>.</i></li>
 * <li><i>NOTICE: NOT using "mats.out.from.Id", as that is identical to "mats.InitOrStageId".</i></li>
 * <li><b>{@link #MDC_INIT_OR_STAGE_ID "mats.InitOrStageId"}</b>: "this" StageId or InitiatorId: Who created this
 * message.</li>
 * <li><b>{@link #MDC_MATS_OUT_TO_ID "mats.out.to.Id"}</b>: target EndpointId/StageId</li>
 * <li><i>NOTICE: NOT using "mats.out.to.app", since we do not know which app will consume it.</i></li>
 * </ul>
 * "Flow properties", i.e. common for all received and sent messages in a Mats flow (initiated, received on stage and
 * sent from stage):
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
 * Mats envelope ("MatsTrace"), serializing, compressing and producing the message system message (sum of the below
 * parts). The same value is present on {@link #MDC_MATS_MESSAGE_SENT "mats.MessageSent"}, see above.</li>
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
 * <li><b>{@link #MDC_MATS_OUT_TIME_MSGSYS "mats.out.MsgSys.ms"}</b>: Part of the total time taken to produce and send
 * the message system message.</li>
 * <li><b>{@link #MDC_MATS_OUT_SIZE_TOTAL_WIRE "mats.out.TotalWire.bytes"}</b>: Best approximation of the total message
 * system wire size (envelope + sideloads + any meta info set on the message). The overhead of the message system itself
 * will probably not be included.</li>
 * <li><b>{@link #MDC_MATS_OUT_SIZE_DATA_SERIAL "mats.out.DataSerial.bytes"}</b>: The serialized size of the outgoing
 * data object (DTO). Note that <code>null</code> might not return <code>0</code> bytes, depending on how the serializer
 * works. If the serializer employs Strings, the returned value is <code>String.length()</code>, which might not exactly
 * be the number of bytes depending on the String contents.</li></li>
 * <li><i>NOTICE: NOT using <code>"mats.out.StateSerial.bytes"</code>, as it felt confusing what any outgoing state
 * relates to: It is not the target/receiving stage, as specified in {@link #MDC_MATS_OUT_TO_ID}. The outgoing state (if
 * any) is a part of the stack, and relates to the next stage of the current endpoint. However, this metric is available
 * on the incoming side, via {@link #MDC_MATS_IN_SIZE_STATE_SERIAL}.</i></li>
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
 * <p/>
 * <b>Note:</b> This logging interceptor honors the {@link MatsInitiate#setTraceProperty(String, Object) Trace Property}
 * {@link MatsLoggingInterceptor#SUPPRESS_LOGGING_TRACE_PROPERTY_KEY} and the Endpoint Attribute
 * {@link MatsLoggingInterceptor#SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY}. Number of suppressed log outputs per
 * matsFactoryName/initAppName/initiatorId/stageId will be logged every 30 minutes (stageId = "INIT" for initiations),
 * on the {@link #LOG_STAGE_NAME} logger. The first logging for each combo after logging the number of suppressions will
 * also be logged in full.
 * <p/>
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

    public static final String LOG_INIT_NAME = "io.mats3.log.init";
    public static final String LOG_STAGE_NAME = "io.mats3.log.stage";

    public static final int NUMBER_OF_MILLIS_BETWEEN_SUPPRESSION_LOG = 30 * 60 * 1000;

    protected static final Logger log_init = LoggerFactory.getLogger(LOG_INIT_NAME);
    protected static final Logger log_stage = LoggerFactory.getLogger(LOG_STAGE_NAME);

    protected static final String SUPPRESS_LOGGING_INTERCEPT_CONTEXT_ATTRIBUTE = "suppressStageLogging";

    public static final String LOG_PREFIX = "#MATSLOG# ";

    public static final String SUPPRESSION_MDC_KEY_PARTS_SEPARATOR = ",";

    // Not using "mats." prefix for "traceId", as it is hopefully generic yet specific
    // enough that it might be used in similar applications.
    public static final String MDC_TRACE_ID = "traceId";

    public static final MatsMetricsLoggingInterceptor INSTANCE = new MatsMetricsLoggingInterceptor();

    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // String MDC_MATS_APP_NAME = "mats.AppName";
    // String MDC_MATS_APP_VERSION = "mats.AppVersion";

    // ============================================================================================================
    // ===== COMMON for Initiate and Stage Completed, with timings:

    public static final String MDC_MATS_VERSION = "mats.Version";
    public static final String MDC_INIT_OR_STAGE_ID = "mats.InitOrStageId";

    // ... Metrics:
    public static final String MDC_MATS_COMPLETE_TIME_TOTAL = "mats.exec.Total.ms";
    public static final String MDC_MATS_COMPLETE_TIME_USER_LAMBDA = "mats.exec.UserLambda.ms";
    public static final String MDC_MATS_COMPLETE_TIME_OUT = "mats.exec.Out.ms";
    public static final String MDC_MATS_COMPLETE_QUANTITY_OUT = "mats.exec.Out.quantity";
    public static final String MDC_MATS_COMPLETE_SIZE_OUT_TOTAL_WIRE = "mats.exec.Out.TotalWire.bytes";
    public static final String MDC_MATS_COMPLETE_TIME_DB_COMMIT = "mats.exec.DbCommit.ms";
    public static final String MDC_MATS_COMPLETE_TIME_MSGSYS_COMMIT = "mats.exec.MsgSysCommit.ms";

    public static final String MDC_MATS_COMPLETE_OPS_TIMING_PREFIX = "mats.exec.ops.time.";
    public static final String MDC_MATS_COMPLETE_OPS_MEASURE_PREFIX = "mats.exec.ops.measure.";
    public static final String MDC_MATS_COMPLETE_OPS_KEYNAME = "mats.exec.ops.key";
    public static final String MDC_MATS_COMPLETE_OPS_DESCRIPTION = "mats.exec.ops.description";

    // ============================================================================================================
    // ===== For Initiate Completed (..in addition to "COMMON init/stage" above):
    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // MDC_MATS_INIT = "mats.Init"; // 'true' on any loglines involving Initialization (also within Stages)
    // MDC_MATS_CALL_NO = "mats.CallNo" // The Call Number, at 0 for initiations.
    // MDC_TRACE_ID = "traceId" // Set as soon as the user code sets it on the initialization.

    // 'true' on a single logline per completed initiation:
    public static final String MDC_MATS_INITIATE_COMPLETED = "mats.InitiateCompleted";

    // ============================================================================================================
    // ===== For Receiving a message
    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // MDC_MATS_STAGE = "mats.Stage"; // 'true' on Stage Processor threads (set fixed on the consumer thread)
    // MDC_MATS_STAGE_ID = "mats.StageId";
    // MDC_MATS_IN_MESSAGE_SYSTEM_ID = "mats.in.MsgSysId";
    // MDC_MATS_CALL_NO = "mats.CallNo" // The Call Number, first stage of flow is 1, then increases with calls.
    // MDC_TRACE_ID = "traceId"

    // 'true' on a single logline per received message:
    public static final String MDC_MATS_MESSAGE_RECEIVED = "mats.MessageReceived";

    public static final String MDC_MATS_IN_FROM_APP_NAME = "mats.in.from.App";
    public static final String MDC_MATS_IN_FROM_ID = "mats.in.from.Id";
    // NOTICE: NOT using MDC_MATS_IN_TO_APP, as that is identical to MDC_MATS_APP_NAME
    // NOTICE: NOT using MDC_MATS_IN_TO_ID, as that is identical to MDC_MATS_STAGE_ID
    public static final String MDC_MATS_IN_MATS_MESSAGE_ID = "mats.in.MatsMsgId";
    public static final String MDC_MATS_IN_MESSAGE_TYPE = "mats.in.MessageType";

    // ... Metrics:
    // Notice that metric this is susceptible to time skews between nodes.
    public static final String MDC_MATS_IN_TIME_SINCE_SENT = "mats.in.SinceSent.ms";
    // Notice that metric this is susceptible to time skews between nodes.
    public static final String MDC_MATS_IN_TIME_SINCE_PRECEDING_ENDPOINT_STAGE = "mats.in.PrecedEpStage.ms";

    public static final String MDC_MATS_IN_SIZE_TOTAL_WIRE = "mats.in.TotalWire.bytes";
    public static final String MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL = "mats.in.TotalPreprocDeserial.ms";
    public static final String MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT = "mats.in.MsgSysDeconstruct.ms";
    public static final String MDC_MATS_IN_SIZE_ENVELOPE_WIRE = "mats.in.EnvelopeWire.bytes";
    public static final String MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS = "mats.in.EnvelopeDecompress.ms";
    public static final String MDC_MATS_IN_SIZE_ENVELOPE_SERIAL = "mats.in.EnvelopeSerial.bytes";
    public static final String MDC_MATS_IN_TIME_ENVELOPE_DESERIAL = "mats.in.EnvelopeDeserial.ms";
    public static final String MDC_MATS_IN_TIME_DATA_AND_STATE_DESERIAL = "mats.in.DataAndStateDeserial.ms";
    public static final String MDC_MATS_IN_SIZE_STATE_SERIAL = "mats.in.StateSerial.bytes";
    public static final String MDC_MATS_IN_SIZE_DATA_SERIAL = "mats.in.DataSerial.bytes";

    // ============================================================================================================
    // ===== For Stage Completed (..in addition to "COMMON init/stage" above):
    // !!! Note that these MDCs are already set by JmsMats core: !!!
    // MDC_MATS_STAGE = "mats.Stage"; // 'true' on Stage Processor threads (set fixed on the consumer thread)
    // MDC_MATS_STAGE_ID = "mats.StageId";
    // MDC_MATS_IN_MESSAGE_SYSTEM_ID = "mats.in.MsgSysId";
    // MDC_MATS_CALL_NO = "mats.CallNo" // The Call Number, first stage of flow is 1, then increases with calls.
    // MDC_TRACE_ID = "traceId"

    // 'true' on a single logline per completed stage
    public static final String MDC_MATS_STAGE_COMPLETED = "mats.StageCompleted";
    // Set on a single logline per completed
    public static final String MDC_MATS_PROCESS_RESULT = "mats.ProcessResult";

    // ..... specific Stage complete metric - along with the other ".exec." from the COMMON Init/Stage Complete
    // Note that this is the same timing as the MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL
    public static final String MDC_MATS_COMPLETE_TIME_TOTAL_PREPROC_AND_DESERIAL = "mats.exec.TotalPreprocDeserial.ms";

    // ============================================================================================================
    // ===== For Endpoint Completed - i.e. a stage of ep that either REPLY or stop the flow (no REQ,NEXT,GOTO)
    // !! NOTE: These are /in addition/ to the Stage Completed above, e.g. MDC_MATS_COMPLETE_PROCESS_RESULT
    // !! NOTE: This will also be set on a Terminator, but that will only be for the Terminator endpoint itself, not
    // for initiator-to-terminator timings.

    // 'true' on a single logline per completed endpoint - it will be on the Stage Completed log lines.
    public static final String MDC_MATS_ENDPOINT_COMPLETED = "mats.EndpointCompleted";

    // ..... specific Endpoint Complete metric. Notice that metric this is susceptible to time skews between nodes.
    public static final String MDC_MATS_ENDPOINT_COMPLETE_TIME_TOTAL = "mats.endpoint.Total.ms";

    // ============================================================================================================
    // ===== For Flow Completed - i.e. a stage in a flow that does not send any outgoing flow msg (REPLY,REQ,NEXT,GOTO)
    // ===== (Typically at a Terminator, i.e. endpoint that have void as outgoing message)
    // !! NOTE: These are /in addition/ to the Stage Completed above, e.g. MDC_MATS_COMPLETE_PROCESS_RESULT

    // 'true' on a single logline per completed flow - it will be on the Stage Completed log lines.
    public static final String MDC_MATS_FLOW_COMPLETED = "mats.FlowCompleted";

    // ..... specific Flow Complete metric. Notice that this metric is susceptible to time skews between nodes.
    public static final String MDC_MATS_FLOW_COMPLETE_TIME_TOTAL = "mats.flow.Total.ms";

    // ============================================================================================================
    // ===== For Sending a single message (from init, or stage) - one line per message
    // Note the point about "combined" loglines for stage-complete + output-single-message, read JavaDoc.
    // 'true' on single logline per msg
    public static final String MDC_MATS_MESSAGE_SENT = "mats.MessageSent";
    // Set on single logline per msg: INIT, STAGE, STAGE_INIT
    public static final String MDC_MATS_DISPATCH_TYPE = "mats.DispatchType";

    public static final String MDC_MATS_OUT_MATS_MESSAGE_ID = "mats.out.MatsMsgId";
    public static final String MDC_MATS_OUT_MESSAGE_SYSTEM_ID = "mats.out.MsgSysId";

    // NOTICE: NOT using MDC_MATS_OUT_FROM_APP / "mats.out.from.App", as that is 'this' App: MDC_MATS_APP_NAME.
    // NOTICE: MDC_INIT_OR_STAGE_ID is also set when sending messages (as well as on init or stage complete).
    // NOTICE: Currently, MDC_INIT_OR_STAGE_ID == MDC_MATS_OUT_FROM_ID - will be removed
    // TODO: REMOVE once all are >=0.18.8, only leave "mats.InitOrStageId" instead.
    public static final String MDC_MATS_OUT_FROM_ID = "mats.out.from.Id"; // "this" StageId/InitiatorId.
    public static final String MDC_MATS_OUT_TO_ID = "mats.out.to.Id"; // target StageId.
    // NOTICE: NOT using MDC_MATS_OUT_TO_APP, since we do not know which app will consume it.

    // ... Metrics:
    public static final String MDC_MATS_OUT_TIME_TOTAL = "mats.out.Total.ms";
    public static final String MDC_MATS_OUT_SIZE_DATA_SERIAL = "mats.out.DataSerial.bytes";
    public static final String MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE = "mats.out.EnvelopeProduce.ms";
    public static final String MDC_MATS_OUT_TIME_ENVELOPE_SERIAL = "mats.out.EnvelopeSerial.ms";
    public static final String MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL = "mats.out.EnvelopeSerial.bytes";
    public static final String MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS = "mats.out.EnvelopeCompress.ms";
    public static final String MDC_MATS_OUT_SIZE_ENVELOPE_WIRE = "mats.out.EnvelopeWire.bytes";
    public static final String MDC_MATS_OUT_SIZE_TOTAL_WIRE = "mats.out.TotalWire.bytes";
    public static final String MDC_MATS_OUT_TIME_MSGSYS = "mats.out.MsgSys.ms";

    // ============================================================================================================
    // ===== For both Message Received and Message Send (note: part of MatsTrace itself: Common for all msgs in flow)
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

    protected final ConcurrentHashMap<String, AtomicInteger> _suppressions = new ConcurrentHashMap<>();

    protected MatsMetricsLoggingInterceptor() {
        Thread suppressionSummarizerThread = new Thread(this::suppressionSummarizerThreadRunnable,
                LOG_PREFIX + " Periodic Log Suppression summarizer");
        suppressionSummarizerThread.setDaemon(true); // Must die with JVM, we do not have stop-ability
        suppressionSummarizerThread.start();
    }

    protected void suppressionSummarizerThreadRunnable() {
        while (true) {
            try {
                Thread.sleep(NUMBER_OF_MILLIS_BETWEEN_SUPPRESSION_LOG);
            }
            catch (InterruptedException e) {
                log_stage.info(LOG_PREFIX + "Surprisingly, we were interrupted. Exiting.");
                break;
            }

            int suppressionSize = _suppressions.size();
            // ?: Is the number of suppressions very high?
            if (suppressionSize > 500) {
                // -> Yes, very high number of suppressions - this is most probably a bug which must be addressed.
                log_stage.error(LOG_PREFIX + "NOTE!! THERE ARE WAY TOO MANY [" + suppressionSize
                        + "] SUPPRESSION ENTRIES!"
                        + " This indicates that fromId (initiatorId) on initiations is set dynamically. You must fix"
                        + " this, or risk out of memory situations.");
            }

            // ?: Did we have any suppressions?
            if (suppressionSize == 0) {
                // -> No, so exit.
                continue;
            }

            // :: Copy out the Map, and clear it while doing so.
            Map<String, Integer> suppressionsCopy = new HashMap<>();
            Iterator<Entry<String, AtomicInteger>> it = _suppressions.entrySet().iterator();
            while (it.hasNext()) {
                Entry<String, AtomicInteger> entry = it.next();
                it.remove();
                suppressionsCopy.put(entry.getKey(), entry.getValue().get());
            }

            // :: Output single logline, with all the counts as separate MDC entries.
            try {
                int totalSuppressed = 0;
                for (Entry<String, Integer> entry : suppressionsCopy.entrySet()) {
                    int count = entry.getValue();
                    totalSuppressed += count;
                    MDC.put(entry.getKey(), Integer.toString(entry.getValue()));
                }
                log_stage.info(LOG_PREFIX + " Suppressed log entries! Total: [" + totalSuppressed + "], over ["
                        + suppressionsCopy.size() + "] groups. Group counts are in MDC.");
            }
            finally {
                MDC.clear();
            }
        }
    }

    private boolean shouldSuppressInitLogging(List<MatsSentOutgoingMessage> outgoingMessages) {
        // It is the message that carries the information about suppression (it is a trace property), thus we need to
        // inspect the initiated outgoing message(s). If there are none, we'll have to conclude that we shall log.

        // ?: No outgoing messages?
        if (outgoingMessages.isEmpty()) {
            // -> No outgoing messages, so we shall NOT suppress.
            return false;
        }

        // Might be multiple messages: All messages must say suppress to actually do suppression.
        for (MatsSentOutgoingMessage outgoingMessage : outgoingMessages) {
            Object suppressed = outgoingMessage.getTraceProperty(SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, Object.class);
            // ?: Does this message NOT want suppression?
            if (suppressed != Boolean.TRUE) {
                // -> This message DOES NOT want suppression, so we shall not suppress.
                return false;
            }
        }
        // All messages wanted suppression.
        return true;
    }

    @Override
    public void initiateCompleted(InitiateCompletedContext ctx) {
        List<MatsSentOutgoingMessage> outgoingMessages = ctx.getOutgoingMessages();

        // :: Handle log suppression
        // ?: Does this initiation want log suppression?
        FactoryConfig factoryConfig = ctx.getInitiator().getParentFactory().getFactoryConfig();
        if (shouldSuppressInitLogging(outgoingMessages)) {
            // -> Yes, this initiation want log suppression.
            // Find initiatorId, pick first message.
            // Suppression of init can only happen if there are outgoing messages, so there is at least one.
            MatsSentOutgoingMessage outgoingMessage = outgoingMessages.get(0);
            String suppressedId = "logsuppress:"
                    + factoryConfig.getName()
                    + SUPPRESSION_MDC_KEY_PARTS_SEPARATOR
                    + outgoingMessage.getInitiatingAppName()
                    + SUPPRESSION_MDC_KEY_PARTS_SEPARATOR
                    + outgoingMessage.getInitiatorId()
                    + SUPPRESSION_MDC_KEY_PARTS_SEPARATOR
                    + "INIT"; // This is an initiation.
            // :: The first suppression (after summarize-suppressions-and-clear) should not be suppressed!
            // Increase the counter, but start from -1
            int numberOfSuppressions = _suppressions.computeIfAbsent(suppressedId, s -> new AtomicInteger(-1))
                    .incrementAndGet();
            // ?: Was this the first logging after summarize-suppressions-and-clear.
            if (numberOfSuppressions > 0) {
                // -> No, not first logging, so do actual suppression - return.
                return;
            }
            // E-> Yes, this was first suppression, so do not suppress!
        }

        // :: First output the user measurements
        outputMeasurementsLoglines(log_init, ctx);

        // :: Then the "completed" logline, either combined with a single message - or multiple lines for multiple msgs
        Map<String, String> completedMDC = Collections.singletonMap(MDC_MATS_INITIATE_COMPLETED,
                msS(ctx.getTotalExecutionNanos()));
        String messageSenderName = factoryConfig.getName() + "|" + ctx.getInitiator().getName();
        String matsVersion = factoryConfig.getMatsImplementationVersion();

        /*
         * The initiatorId is set by sending an actual message. If no message is sent from an initiation lambda, there
         * is no initiatorId. But the lambda still executed, so we want to measure it - thus using a fictive initiatorId
         * in the no-outgoing-message case.
         *
         * In case of multiple messages in one initiation, each "initiatorId" (i.e. MatsInitiate.from(..)) might be
         * different. Assuming that I have an idea of how developers use the system, this should really not be a common
         * situation. Therefore, we just pick the first message's "from" (i.e. "initiatorId") to tag the timings with.
         *
         * The same holds for the interactive flag.
         */
        String firstMsgInitiatorId;
        boolean interactive;
        // ?: Do we have outgoing messages?
        if (outgoingMessages.isEmpty()) {
            // -> No outgoing messages, using dummy "initiatorId", and set interactive to false.
            firstMsgInitiatorId = MatsMetricsInterceptor.INITIATOR_ID_WHEN_NO_OUTGOING_MESSAGES;
            interactive = false;
        }
        else {
            // -> Yes, there are outgoing messages. Use first message's initiatorId and interactive flag.
            MatsSentOutgoingMessage firstMessage = outgoingMessages.get(0);
            firstMsgInitiatorId = firstMessage.getInitiatorId();
            interactive = firstMessage.isInteractive();
        }

        String initiatingAppName = factoryConfig.getAppName();

        commonStageAndInitiateCompleted(ctx, matsVersion, firstMsgInitiatorId, initiatingAppName, firstMsgInitiatorId,
                interactive, "", log_init, outgoingMessages, messageSenderName, "", 0L, completedMDC);
    }

    protected boolean shouldSuppressStageLogging(StageCommonContext ctx) {
        // ?: Does the Endpoint allow for log suppression?
        Object suppressionAllowed = ctx.getStage().getParentEndpoint().getEndpointConfig()
                .getAttribute(SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY);
        if (suppressionAllowed == Boolean.TRUE) {
            // -> Yes, endpoint allows for log suppression.
            // ?: Does this Mats Flow want log suppression?
            Object suppressed = ctx.getProcessContext()
                    .getTraceProperty(SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, Object.class);
            // Return whether this Stage wants log suppression
            return suppressed == Boolean.TRUE;
        }
        return false;
    }

    @Override
    public void stageReceived(StageReceivedContext ctx) {
        ProcessContext<Object> processContext = ctx.getProcessContext();

        // :: Handle log suppression
        // ?: Does this initialization want log suppression?
        if (shouldSuppressStageLogging(ctx)) {
            // -> Yes, this initiation want log suppression.
            String suppressedId = "logsuppress:"
                    + ctx.getStage().getParentEndpoint().getParentFactory().getFactoryConfig().getName()
                    + SUPPRESSION_MDC_KEY_PARTS_SEPARATOR
                    + processContext.getInitiatingAppName()
                    + SUPPRESSION_MDC_KEY_PARTS_SEPARATOR
                    + processContext.getInitiatorId()
                    + SUPPRESSION_MDC_KEY_PARTS_SEPARATOR
                    + ctx.getStage().getStageConfig().getStageId();
            // :: The first suppression (after summarize-suppressions-and-clear) should not be suppressed!
            // Increase the counter, but start from -1
            int numberOfSuppressions = _suppressions.computeIfAbsent(suppressedId, s -> new AtomicInteger(-1))
                    .incrementAndGet();
            // ?: Was this the first logging after summarize-suppressions-and-clear.
            if (numberOfSuppressions > 0) {
                // -> No, not first logging, so do actual suppression - return.
                // Store the value in the intercept context map
                ctx.putInterceptContextAttribute(SUPPRESS_LOGGING_INTERCEPT_CONTEXT_ATTRIBUTE, Boolean.TRUE);
                return;
            }
            // E-> Yes, this was first suppression, so do not suppress!
        }

        try {

            String totalPreprocessAndDeserializeMillisString = msS(ctx.getTotalPreprocessAndDeserializeNanos());

            MDC.put(MDC_MATS_MESSAGE_RECEIVED, totalPreprocessAndDeserializeMillisString);

            long sumNanosPieces = ctx.getMessageSystemDeconstructNanos()
                    + ctx.getEnvelopeDecompressionNanos()
                    + ctx.getEnvelopeDeserializationNanos()
                    + ctx.getDataAndStateDeserializationNanos();

            MDC.put(MDC_MATS_INIT_APP, processContext.getInitiatingAppName());
            MDC.put(MDC_MATS_INIT_ID, processContext.getInitiatorId());
            MDC.put(MDC_MATS_AUDIT, Boolean.toString(!processContext.isNoAudit()));
            MDC.put(MDC_MATS_PERSISTENT, Boolean.toString(!processContext.isNonPersistent()));
            MDC.put(MDC_MATS_INTERACTIVE, Boolean.toString(processContext.isInteractive()));

            MDC.put(MDC_MATS_IN_FROM_APP_NAME, processContext.getFromAppName());
            MDC.put(MDC_MATS_IN_FROM_ID, processContext.getFromStageId());
            MDC.put(MDC_MATS_IN_MATS_MESSAGE_ID, processContext.getMatsMessageId());
            MDC.put(MDC_MATS_IN_MESSAGE_TYPE, ctx.getIncomingMessageType().toString());

            // ::: Metrics

            long now = System.currentTimeMillis();

            // :: Time since the message was sent from the sender, "time on queue" between pieces in the flow.
            MDC.put(MDC_MATS_IN_TIME_SINCE_SENT,
                    Long.toString(now - processContext.getFromTimestamp().toEpochMilli()));

            // :: Time since previous stage (or, actually, initiation) on same stack height
            if ((ctx.getIncomingMessageType() == MessageType.REPLY)
                    || (ctx.getIncomingMessageType() == MessageType.NEXT)
                    || (ctx.getIncomingMessageType() == MessageType.GOTO)) {
                MDC.put(MDC_MATS_IN_TIME_SINCE_PRECEDING_ENDPOINT_STAGE,
                        Long.toString(now - ctx.getPrecedingSameStackHeightOutgoingTimestamp().toEpochMilli()));
            }

            // :: Preprocessing and Deserialization Total:
            MDC.put(MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL, totalPreprocessAndDeserializeMillisString);
            // .. and breakdown of Total:
            MDC.put(MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT, msS(ctx.getMessageSystemDeconstructNanos()));
            MDC.put(MDC_MATS_IN_SIZE_ENVELOPE_WIRE, Integer.toString(ctx.getEnvelopeWireSize()));
            MDC.put(MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS, msS(ctx.getEnvelopeDecompressionNanos()));
            MDC.put(MDC_MATS_IN_SIZE_ENVELOPE_SERIAL, Integer.toString(ctx.getEnvelopeSerializedSize()));
            MDC.put(MDC_MATS_IN_TIME_ENVELOPE_DESERIAL, msS(ctx.getEnvelopeDeserializationNanos()));
            MDC.put(MDC_MATS_IN_TIME_DATA_AND_STATE_DESERIAL, msS(ctx.getDataAndStateDeserializationNanos()));

            MDC.put(MDC_MATS_IN_SIZE_STATE_SERIAL, Integer.toString(ctx.getStateSerializedSize()));
            MDC.put(MDC_MATS_IN_SIZE_DATA_SERIAL, Integer.toString(ctx.getDataSerializedSize()));
            MDC.put(MDC_MATS_IN_SIZE_TOTAL_WIRE, Integer.toString(ctx.getMessageSystemTotalWireSize()));

            log_stage.info(LOG_PREFIX + "RECEIVED [" + ctx.getIncomingMessageType()
                    + "] message from [" + processContext.getFromStageId()
                    + "@" + processContext.getFromAppName() + ",v." + processContext.getFromAppVersion()
                    + "], totPreprocAndDeserial:[" + ms(ctx.getTotalPreprocessAndDeserializeNanos())
                    + "] || breakdown: msgSysDeconstruct:[" + ms(ctx.getMessageSystemDeconstructNanos())
                    + " ms]->envelopeWireSize:[" + ctx.getEnvelopeWireSize()
                    + " B]->decomp:[" + ms(ctx.getEnvelopeDecompressionNanos())
                    + " ms]->serialSize:[" + ctx.getEnvelopeSerializedSize()
                    + " B]->deserial:[" + ms(ctx.getEnvelopeDeserializationNanos())
                    + " ms]->(envelope)->dto&stoDeserial:[" + ms(ctx.getDataAndStateDeserializationNanos())
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
            MDC.remove(MDC_MATS_IN_MESSAGE_TYPE);

            MDC.remove(MDC_MATS_IN_TIME_SINCE_SENT);
            MDC.remove(MDC_MATS_IN_TIME_SINCE_PRECEDING_ENDPOINT_STAGE);

            MDC.remove(MDC_MATS_IN_TIME_TOTAL_PREPROC_AND_DESERIAL);

            MDC.remove(MDC_MATS_IN_TIME_MSGSYS_DECONSTRUCT);
            MDC.remove(MDC_MATS_IN_SIZE_ENVELOPE_WIRE);
            MDC.remove(MDC_MATS_IN_TIME_ENVELOPE_DECOMPRESS);
            MDC.remove(MDC_MATS_IN_SIZE_ENVELOPE_SERIAL);
            MDC.remove(MDC_MATS_IN_TIME_ENVELOPE_DESERIAL);
            MDC.remove(MDC_MATS_IN_TIME_DATA_AND_STATE_DESERIAL);

            MDC.remove(MDC_MATS_IN_SIZE_STATE_SERIAL);
            MDC.remove(MDC_MATS_IN_SIZE_DATA_SERIAL);
            MDC.remove(MDC_MATS_IN_SIZE_TOTAL_WIRE);
        }
    }

    @Override
    public void stageCompleted(StageCompletedContext ctx) {
        // :: Handle log suppression
        /*
         * NOTICE: Contrasted to initiation, we do not evaluate the outgoing messages, but follow the suppression from
         * the received evaluation. The reason is twofold: Semantically: The suppression refers to the whole Stage
         * Processing, and Mats Flow as a whole. Technically: The stage might not have any outgoing messages
         * (ProcessResult == NONE, i.e. terminate Mats Flow), and we would then have to assume that logging should
         * happen. Also, TraceProperties are inherited by both the outgoing flow message, and any stage initiated
         * messages, so they would typically all have the same status anyway.
         */
        // ?: Should we suppress - check intercept context attribute set in stageReceived(..)
        if (ctx.getInterceptContextAttribute(SUPPRESS_LOGGING_INTERCEPT_CONTEXT_ATTRIBUTE) == Boolean.TRUE) {
            // -> Yes, suppress - the "aggregate suppression stats" is already done in stageReceived(..).
            // HOWEVER, if this was a "bad result", then log anyway
            boolean badResult = ctx.getProcessResult() == ProcessResult.USER_EXCEPTION
                    || ctx.getProcessResult() == ProcessResult.SYSTEM_EXCEPTION;
            // ?: Was it not a bad result?
            if (!badResult) {
                // -> Yes, not bad result (i.e. good!) - so suppress.
                return;
            }
            // E-> No, this was a bad result, so let's log it anyway.
        }

        // :: First output the user measurements
        outputMeasurementsLoglines(log_stage, ctx);

        // :: Then the "completed" logline, either combined with a single message - or multiple lines for multiple msgs
        // :: NOTICE: Adding "stage completed" and "flow completed" extra MDCs if relevant.
        Map<String, String> completedMDC = new HashMap<>();

        completedMDC.put(MDC_MATS_STAGE_COMPLETED, msS(ctx.getTotalExecutionNanos()));
        List<MatsSentOutgoingMessage> outgoingMessages = ctx.getOutgoingMessages();

        // :: Specific metric for stage completed
        String totalPreprocessAndDeserializeNanosString = msS(ctx.getTotalPreprocessAndDeserializeNanos());
        String extraBreakdown = " totPreprocAndDeserial:[" + totalPreprocessAndDeserializeNanosString + " ms],";
        long extraNanosBreakdown = ctx.getTotalPreprocessAndDeserializeNanos();
        completedMDC.put(MDC_MATS_COMPLETE_TIME_TOTAL_PREPROC_AND_DESERIAL, totalPreprocessAndDeserializeNanosString);

        completedMDC.put(MDC_MATS_PROCESS_RESULT, ctx.getProcessResult().toString());

        // ?: Has the endpoint completed with this stage?
        if ((ctx.getProcessResult() == ProcessResult.REPLY) || (ctx.getProcessResult() == ProcessResult.NONE)) {
            // -> Yes, either it REPLYed, or it didn't send any outgoing message (as if a Terminator)
            // This means that the Mats Endpoint has completed.
            String totalEndpointTimeString = "0";
            long endpointEnteredTimestamp = ctx.getEndpointEnteredTimestamp().toEpochMilli();
            if (endpointEnteredTimestamp > 0) { // TODO: Why this check? Due to handling of older versions w/o data?
                long totalEndpointTime = System.currentTimeMillis() - endpointEnteredTimestamp;
                totalEndpointTimeString = Long.toString(totalEndpointTime);
            }
            completedMDC.put(MDC_MATS_ENDPOINT_COMPLETED, totalEndpointTimeString);
            completedMDC.put(MDC_MATS_ENDPOINT_COMPLETE_TIME_TOTAL, totalEndpointTimeString);
        }

        // ?: Has the flow completed with this stage?
        DetachedProcessContext processContext = ctx.getProcessContext();
        if (ctx.getProcessResult() == ProcessResult.NONE) {
            // -> Yes, there was no outgoing flow message (REQUEST,REPLY,NEXT,GOTO)
            // This means the Mats Flow has completed
            long initiationTimestamp = processContext.getInitiatingTimestamp().toEpochMilli();
            long totalFlowTime = System.currentTimeMillis() - initiationTimestamp;
            String totalFlowTimeString = Long.toString(totalFlowTime);
            completedMDC.put(MDC_MATS_FLOW_COMPLETED, totalFlowTimeString);
            completedMDC.put(MDC_MATS_FLOW_COMPLETE_TIME_TOTAL, totalFlowTimeString);
        }

        FactoryConfig factoryConfig = ctx.getStage().getParentEndpoint().getParentFactory().getFactoryConfig();
        // Current processing node
        String messageSenderName = factoryConfig.getName();
        String matsVersion = factoryConfig.getMatsImplementationVersion();
        String stageId = ctx.getStage().getStageConfig().getStageId(); // StageId.

        // Initiating node / Flow props.
        String initiatingAppName = processContext.getInitiatingAppName();
        String initiatorId = processContext.getInitiatorId();
        boolean interactive = processContext.isInteractive();

        commonStageAndInitiateCompleted(ctx, matsVersion, stageId, initiatingAppName, initiatorId, interactive,
                " with result " + ctx.getProcessResult(), log_stage, outgoingMessages, messageSenderName,
                extraBreakdown, extraNanosBreakdown, completedMDC);
    }

    protected void outputMeasurementsLoglines(Logger logger, CommonCompletedContext ctx) {
        // :: Timings
        for (MatsTimingMeasurement measurement : ctx.getTimingMeasurements()) {
            String metricId = measurement.getMetricId();
            String metricDescription = measurement.getMetricDescription();
            String[] labelKeyValue = measurement.getLabelKeyValue();

            String measure = msS(measurement.getNanos());
            String baseUnit = "ms";

            outputMeasurementLogline(logger, "TIMING ", MDC_MATS_COMPLETE_OPS_TIMING_PREFIX, metricId,
                    baseUnit, measure, metricDescription, labelKeyValue);
        }
        // :: Measurements
        for (MatsMeasurement measurement : ctx.getMeasurements()) {
            String metricId = measurement.getMetricId();
            String metricDescription = measurement.getMetricDescription();
            String[] labelKeyValue = measurement.getLabelKeyValue();

            String measure = Double.toString(measurement.getMeasure());
            String baseUnit = measurement.getBaseUnit();

            outputMeasurementLogline(logger, "MEASURE ", MDC_MATS_COMPLETE_OPS_MEASURE_PREFIX, metricId,
                    baseUnit, measure, metricDescription, labelKeyValue);
        }
    }

    protected void outputMeasurementLogline(Logger logger, String what, String mdcPrefix, String metricId,
            String baseUnit, String measure, String metricDescription, String[] labelKeyValue) {
        Collection<String> mdcTagKeysToClear = labelKeyValue.length > 0
                ? new ArrayList<>()
                : null;
        String mdcKey = mdcPrefix + metricId + "." + baseUnit;
        try {
            MDC.put(mdcKey, measure);
            MDC.put(MDC_MATS_COMPLETE_OPS_KEYNAME, mdcKey);
            MDC.put(MDC_MATS_COMPLETE_OPS_DESCRIPTION, metricDescription);

            StringBuilder buf = new StringBuilder(128);
            buf.append(LOG_PREFIX)
                    .append(what)
                    .append(metricId)
                    .append(":[")
                    .append(measure)
                    .append(' ')
                    .append(baseUnit)
                    .append(']');

            for (int i = 0; i < labelKeyValue.length; i += 2) {
                String labelKey = labelKeyValue[i];
                String labelValue = labelKeyValue[i + 1];
                String mdcLabelKey = mdcKey + ".tag." + labelKey;
                MDC.put(mdcLabelKey, labelValue);
                mdcTagKeysToClear.add(mdcLabelKey);
                buf.append(' ')
                        .append(labelKey)
                        .append(':')
                        .append(labelValue);
            }

            logger.info(buf.toString());
        }
        finally {
            MDC.remove(mdcKey);
            MDC.remove(MDC_MATS_COMPLETE_OPS_KEYNAME);
            MDC.remove(MDC_MATS_COMPLETE_OPS_DESCRIPTION);
            if (mdcTagKeysToClear != null) {
                for (String mdcLabelKey : mdcTagKeysToClear) {
                    MDC.remove(mdcLabelKey);
                }
            }
        }
    }

    protected enum Level {
        INFO, ERROR
    }

    protected void commonStageAndInitiateCompleted(CommonCompletedContext ctx, String matsVersion, String initOrStageId,
            String initiatingAppName, String initatorId, boolean interactive,
            String extraResult, Logger logger, List<MatsSentOutgoingMessage> outgoingMessages, String messageSenderName,
            String extraBreakdown, long extraNanosBreakdown, Map<String, String> completedMDC) {

        String what;
        if (ctx instanceof InitiateCompletedContext) {
            what = "INIT";
        }
        else if (completedMDC.containsKey(MDC_MATS_FLOW_COMPLETED)) {
            what = "STAGE/ENDPOINT/FLOW";
        }
        else if (completedMDC.containsKey(MDC_MATS_ENDPOINT_COMPLETED)) {
            what = "STAGE/ENDPOINT";
        }
        else {
            what = "STAGE";
        }

        // ?: Do we have a Throwable, indicating that processing (stage or init) failed?
        if (ctx.getThrowable().isPresent()) {
            // -> Yes, we have a Throwable
            // :: Output the 'completed' (now FAILED) line
            // NOTE: We do NOT output any messages, even if there are any (they can have been produced before the
            // error occurred), as they will NOT have been sent, and the log lines are supposed to represent
            // actual messages that have been put on the wire.
            Throwable t = ctx.getThrowable().get();
            commonStageAndInitiateCompleted_inner(ctx, matsVersion, initOrStageId,
                    initiatingAppName, initatorId, interactive,
                    LOG_PREFIX + what + " !!FAILED!!" + extraResult, extraBreakdown, extraNanosBreakdown,
                    Collections.emptyList(), Level.ERROR, logger, t, "", completedMDC);
        }
        else if (outgoingMessages.size() != 1) {
            // -> Yes, >1 or 0 messages

            // Note: One could argue that the messages should come before "complete", but in the case of concat of
            // complete and message when only 1 message, it feels better that the complete is the "main" line, and the
            // message is the "add-on". Thus, to keep consistency, we do the same when there are >1 messages.

            // :: Output the 'completed' line
            commonStageAndInitiateCompleted_inner(ctx, matsVersion, initOrStageId,
                    initiatingAppName, initatorId, interactive,
                    LOG_PREFIX + what + " completed" + extraResult, extraBreakdown, extraNanosBreakdown,
                    outgoingMessages, Level.INFO, logger, null, "", completedMDC);

            // :: Output the per-message logline (there might be >1, or 0, messages)
            for (MatsSentOutgoingMessage outgoingMessage : ctx.getOutgoingMessages()) {
                msgLog_AddMsgMdcs(outgoingMessage, () -> log_init.info(LOG_PREFIX
                        + msgLog_CreateMsgLogLine(messageSenderName, outgoingMessage)));
            }
        }
        else {
            // -> Only 1 message and no Throwable: Concat the two lines.
            msgLog_AddMsgMdcs(outgoingMessages.get(0), () -> {
                String msgLine = msgLog_CreateMsgLogLine(messageSenderName, outgoingMessages.get(0));
                commonStageAndInitiateCompleted_inner(ctx, matsVersion, initOrStageId,
                        initiatingAppName, initatorId, interactive,
                        LOG_PREFIX + what + " completed" + extraResult, extraBreakdown, extraNanosBreakdown,
                        outgoingMessages, Level.INFO, logger, null, "\n    " + LOG_PREFIX + msgLine, completedMDC);
            });
        }
    }

    protected void commonStageAndInitiateCompleted_inner(CommonCompletedContext ctx, String matsVersion,
            String initOrStageId, String initiatingAppName, String initiatorId, boolean interactive,
            String logPrefix, String extraBreakdown, long extraNanosBreakdown,
            List<MatsSentOutgoingMessage> outgoingMessages, Level level, Logger log, Throwable t, String logPostfix,
            Map<String, String> completedMDC) {

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

        // :: Subtract the DtoAndSto Serialization sum from the user lambda time.
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
            // Add the [init, stage, endpoint, flow]-completed specific MDCs
            completedMDC.forEach(MDC::put);

            int totalWireSize = 0;
            for (MatsSentOutgoingMessage outgoingMessage : outgoingMessages) {
                totalWireSize += outgoingMessage.getMessageSystemTotalWireSize();
            }

            MDC.put(MDC_MATS_VERSION, matsVersion);
            MDC.put(MDC_INIT_OR_STAGE_ID, initOrStageId); // Current stage (or initiation) Id.

            // MatsTrace elements:
            MDC.put(MDC_MATS_INIT_APP, initiatingAppName);
            MDC.put(MDC_MATS_INIT_ID, initiatorId);
            MDC.put(MDC_MATS_INTERACTIVE, Boolean.valueOf(interactive).toString());

            MDC.put(MDC_MATS_COMPLETE_TIME_TOTAL, msS(ctx.getTotalExecutionNanos()));
            MDC.put(MDC_MATS_COMPLETE_TIME_USER_LAMBDA, msS(nanosTaken_UserLambdaAlone));
            MDC.put(MDC_MATS_COMPLETE_TIME_OUT, msS(nanosTaken_SumMessageOutHandling));
            MDC.put(MDC_MATS_COMPLETE_QUANTITY_OUT, String.valueOf(outgoingMessages.size()));
            MDC.put(MDC_MATS_COMPLETE_SIZE_OUT_TOTAL_WIRE, String.valueOf(totalWireSize));
            MDC.put(MDC_MATS_COMPLETE_TIME_DB_COMMIT, msS(ctx.getDbCommitNanos()));
            MDC.put(MDC_MATS_COMPLETE_TIME_MSGSYS_COMMIT, msS(ctx.getMessageSystemCommitNanos()));

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
            completedMDC.keySet().forEach(MDC::remove);

            MDC.remove(MDC_MATS_VERSION);
            MDC.remove(MDC_INIT_OR_STAGE_ID);

            MDC.remove(MDC_MATS_INIT_APP);
            MDC.remove(MDC_MATS_INIT_ID);
            MDC.remove(MDC_MATS_INTERACTIVE);

            MDC.remove(MDC_MATS_COMPLETE_TIME_TOTAL);
            MDC.remove(MDC_MATS_COMPLETE_TIME_USER_LAMBDA);
            MDC.remove(MDC_MATS_COMPLETE_TIME_OUT);
            MDC.remove(MDC_MATS_COMPLETE_QUANTITY_OUT);
            MDC.remove(MDC_MATS_COMPLETE_SIZE_OUT_TOTAL_WIRE);
            MDC.remove(MDC_MATS_COMPLETE_TIME_DB_COMMIT);
            MDC.remove(MDC_MATS_COMPLETE_TIME_MSGSYS_COMMIT);
        }
    }

    protected void msgLog_AddMsgMdcs(MatsSentOutgoingMessage msg, Runnable runnable) {
        String existingTraceId = MDC.get(MDC_TRACE_ID);
        try {
            MDC.put(MDC_TRACE_ID, msg.getTraceId());

            // :: Calculate the total time taken
            long nanosTaken_Total = msg.getEnvelopeProduceNanos()
                    + msg.getEnvelopeSerializationNanos()
                    + msg.getEnvelopeCompressionNanos()
                    + msg.getMessageSystemProduceAndSendNanos();
            String millisTaken_Total_String = msS(nanosTaken_Total);

            MDC.put(MDC_MATS_MESSAGE_SENT, millisTaken_Total_String);
            MDC.put(MDC_MATS_DISPATCH_TYPE, msg.getDispatchType().toString());

            MDC.put(MDC_MATS_OUT_MATS_MESSAGE_ID, msg.getMatsMessageId());
            MDC.put(MDC_MATS_OUT_MESSAGE_SYSTEM_ID, msg.getSystemMessageId());

            MDC.put(MDC_MATS_INIT_APP, msg.getInitiatingAppName());
            MDC.put(MDC_MATS_INIT_ID, msg.getInitiatorId());
            MDC.put(MDC_MATS_OUT_FROM_ID, msg.getFrom()); // TODO: To be removed, INIT_OR_STAGE_ID is better.
            MDC.put(MDC_INIT_OR_STAGE_ID, msg.getFrom());
            MDC.put(MDC_MATS_OUT_TO_ID, msg.getTo());
            MDC.put(MDC_MATS_AUDIT, Boolean.toString(!msg.isNoAudit()));
            MDC.put(MDC_MATS_PERSISTENT, Boolean.toString(!msg.isNonPersistent()));
            MDC.put(MDC_MATS_INTERACTIVE, Boolean.toString(msg.isInteractive()));

            // Metrics:
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_PRODUCE, msS(msg.getEnvelopeProduceNanos()));
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_SERIAL, msS(msg.getEnvelopeSerializationNanos()));
            MDC.put(MDC_MATS_OUT_SIZE_ENVELOPE_SERIAL, Integer.toString(msg.getEnvelopeSerializedSize()));
            MDC.put(MDC_MATS_OUT_TIME_ENVELOPE_COMPRESS, msS(msg.getEnvelopeCompressionNanos()));
            MDC.put(MDC_MATS_OUT_SIZE_ENVELOPE_WIRE, Integer.toString(msg.getEnvelopeWireSize()));
            MDC.put(MDC_MATS_OUT_TIME_MSGSYS, msS(msg.getMessageSystemProduceAndSendNanos()));

            MDC.put(MDC_MATS_OUT_TIME_TOTAL, millisTaken_Total_String);

            MDC.put(MDC_MATS_OUT_SIZE_TOTAL_WIRE, Integer.toString(msg.getMessageSystemTotalWireSize()));
            MDC.put(MDC_MATS_OUT_SIZE_DATA_SERIAL, Integer.toString(msg.getDataSerializedSize()));

            // :: Actually log the line, i.e. execute the Runnable.
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
            MDC.remove(MDC_MATS_OUT_FROM_ID); // TODO: To be removed, INIT_OR_STAGE_ID is better..
            MDC.remove(MDC_INIT_OR_STAGE_ID);
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

            MDC.remove(MDC_MATS_OUT_SIZE_TOTAL_WIRE);
            MDC.remove(MDC_MATS_OUT_SIZE_DATA_SERIAL);
        }
    }

    protected String msgLog_CreateMsgLogLine(String messageSenderName, MatsSentOutgoingMessage msg) {
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

    protected static String msS(long nanosTake) {
        return Double.toString(ms(nanosTake));
    }

    /**
     * Converts nanos to millis with a sane number of significant digits ("3.5" significant digits), but assuming that
     * this is not used to measure things that take less than 0.001 milliseconds (in which case it will be "rounded" to
     * 0.0001, 1e-4, as a special value). Takes care of handling the difference between 0 and &gt; 0 nanoseconds when
     * rounding - in that 1 nanosecond will become 0.0001 (1e-4 ms, which if used to measure things that are really
     * short lived might be magnitudes wrong), while 0 will be 0.0 exactly. Note that printing of a double always
     * include the at least one decimal (unless scientific notation kicks in), which can lead your interpretation
     * slightly astray wrt. accuracy/significant digits when running this over e.g. nanos 555_555_555, which will print
     * as "556.0" (milliseconds), and 5_555_555_555 prints "5560.0".
     */
    protected static double ms(long nanosTaken) {
        if (nanosTaken == 0) {
            return 0.0;
        }
        // >=500_000 ms?
        if (nanosTaken >= 1_000_000L * 500_000) {
            // -> Yes, >500_000ms, thus chop into the integer part of the number (zeroing 3 digits)
            // (note: printing of a double always include ".0"), e.g. 612000.0
            return Math.round(nanosTaken / 1_000_000_000d) * 1_000d;
        }
        // >=50_000 ms?
        if (nanosTaken >= 1_000_000L * 50_000) {
            // -> Yes, >50_000ms, thus chop into the integer part of the number (zeroing 2 digits)
            // (note: printing of a double always include ".0"), e.g. 61200.0
            return Math.round(nanosTaken / 100_000_000d) * 100d;
        }
        // >=5_000 ms?
        if (nanosTaken >= 1_000_000L * 5_000) {
            // -> Yes, >5_000ms, thus chop into the integer part of the number (zeroing 1 digit)
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
