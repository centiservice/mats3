package io.mats3.localinspect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import io.mats3.MatsConfig;
import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsFactory;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsInitiator;
import io.mats3.MatsStage;
import io.mats3.MatsStage.StageConfig;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInterceptable;
import io.mats3.api.intercept.MatsOutgoingMessage.MessageType;
import io.mats3.api.intercept.MatsStageInterceptor;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext.StageProcessResult;
import io.mats3.localinspect.LocalStatsMatsInterceptor.EndpointStats;
import io.mats3.localinspect.LocalStatsMatsInterceptor.IncomingMessageRepresentation;
import io.mats3.localinspect.LocalStatsMatsInterceptor.InitiatorStats;
import io.mats3.localinspect.LocalStatsMatsInterceptor.MessageRepresentation;
import io.mats3.localinspect.LocalStatsMatsInterceptor.OutgoingMessageRepresentation;
import io.mats3.localinspect.LocalStatsMatsInterceptor.StageStats;
import io.mats3.localinspect.LocalStatsMatsInterceptor.StatsSnapshot;

/**
 * Implementation of {@link LocalHtmlInspectForMatsFactory}.
 *
 * @author Endre Stølsvik 2021-03-25 - http://stolsvik.com/, endre@stolsvik.com
 */
public class LocalHtmlInspectForMatsFactoryImpl implements LocalHtmlInspectForMatsFactory {

    public static LocalHtmlInspectForMatsFactoryImpl create(MatsFactory matsFactory) {
        return new LocalHtmlInspectForMatsFactoryImpl(matsFactory);
    }

    final MatsFactory _matsFactory;
    final MatsInterceptable _matsInterceptable;

    LocalHtmlInspectForMatsFactoryImpl(MatsFactory matsFactory) {
        _matsFactory = matsFactory;
        MatsInterceptable matsInterceptable = null;

        // ?: Is the provided MatsFactory a MatsInterceptable?
        if (matsFactory instanceof MatsInterceptable) {
            // -> Yes, so hold on to it.
            matsInterceptable = (MatsInterceptable) matsFactory;
        }
        // ?: Okay, is the fully unwrapped MatsFactory a MatsInterceptable then?
        else if (matsFactory.unwrapFully() instanceof MatsInterceptable) {
            // -> Yes, when we unwrapFully'ed, the resulting MatsFactory was MatsInterceptable
            // Hold on to it
            matsInterceptable = (MatsInterceptable) matsFactory.unwrapFully();
        }

        _matsInterceptable = matsInterceptable;
    }

    /**
     * Note: The return from this method is static, and should only be included once per HTML page, no matter how many
     * MatsFactories you display.
     */
    @Override
    public void getStyleSheet(Appendable out) throws IOException {
        includeFile(out, "localhtmlinspect.css");
    }

    /**
     * Note: The return from this method is static, and should only be included once per HTML page, no matter how many
     * MatsFactories you display.
     */
    @Override
    public void getJavaScript(Appendable out) throws IOException {
        includeFile(out, "localhtmlinspect.js");
    }

    private static void includeFile(Appendable out, String file) throws IOException {
        String filename = LocalHtmlInspectForMatsFactory.class.getPackage().getName().replace('.', '/') + '/' + file;
        InputStream is = LocalHtmlInspectForMatsFactory.class.getClassLoader().getResourceAsStream(filename);
        if (is == null) {
            throw new IllegalStateException("Missing '" + file + "' from ClassLoader.");
        }
        InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        while (true) {
            String line = br.readLine();
            if (line == null) {
                break;
            }
            out.append(line).append('\n');
        }
    }

    @Override
    public void createFactoryReport(Appendable out, boolean includeInitiators,
            boolean includeEndpoints, boolean includeStages) throws IOException {
        // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
        LocalStatsMatsInterceptor localStats = null;
        if (_matsInterceptable != null) {
            localStats = _matsInterceptable
                    .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
        }

        FactoryConfig config = _matsFactory.getFactoryConfig();
        out.append("<div class='matsli_report matsli_factory'>\n");
        out.append("<div class='matsli_heading'>MatsFactory <h2>" + config.getName() + "</h2>\n");
        out.append(" - <b>Known number of CPUs:</b> " + config.getNumberOfCpus());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config));
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append("</div>\n");

        out.append("<div class='matsli_info'>");
        out.append("<span style='font-size: 90%; vertical-align: 0.5em'>");
        out.append("<b>Name:</b> " + config.getName());
        out.append(" - <b>App:</b> " + config.getAppName() + " v." + config.getAppVersion());
        out.append(" - <b>Nodename:</b> " + config.getNodename());
        out.append(" - <b>Mats<sup>3</sup>:</b> " + config.getMatsImplementationName()
                + ", v."+config.getMatsImplementationVersion());
        out.append(" - <b>Destination prefix:</b> '" + config.getMatsDestinationPrefix() + "'");
        out.append(" - <b>Trace key:</b> '" + config.getMatsTraceKey() + "'<br/>\n");
        out.append("</span>\n");

        out.append("<h4>Factory Summary</h4>");
        createFactorySummary(out, includeInitiators, includeEndpoints);

        out.append("<br>\n");
        out.append((localStats != null
                ? "<b>Local Statistics collector present in MatsFactory!</b>"
                        + " (<code>" + LocalStatsMatsInterceptor.class.getSimpleName() + "</code> installed)"
                : "<b>Missing Local Statistics collector in MatsFactory - <code>"
                        + LocalStatsMatsInterceptor.class.getSimpleName()
                        + "</code> is not installed!</b>") + "</b><br/>");

        if (_matsInterceptable != null) {
            out.append("<b>Installed InitiationInterceptors:</b><br/>\n");
            List<MatsInitiateInterceptor> initiationInterceptors = _matsInterceptable.getInitiationInterceptors();
            for (MatsInitiateInterceptor initiationInterceptor : initiationInterceptors) {
                out.append("&nbsp;&nbsp;<code>" + initiationInterceptor.getClass().getName() + "</code>: "
                        + initiationInterceptor + "<br/>\n");
            }
            out.append("<b>Installed StageInterceptors:</b><br/>\n");
            List<MatsStageInterceptor> stageInterceptors = _matsInterceptable.getStageInterceptors();
            for (MatsStageInterceptor stageInterceptor : stageInterceptors) {
                out.append("&nbsp;&nbsp;<code>" + stageInterceptor.getClass().getName() + "</code>: "
                        + stageInterceptor + "<br/>\n");
            }
            out.append("<br/>\n");
        }
        out.append("</div>");

        // :: Initiators

        boolean first = true;
        if (includeInitiators) {
            for (MatsInitiator initiator : _matsFactory.getInitiators()) {
                out.append(first ? "" : "<hr/>");
                first = false;
                createInitiatorReport(out, initiator);
            }
        }

        // :: Endpoints

        if (includeEndpoints) {
            for (MatsEndpoint<?, ?> endpoint : _matsFactory.getEndpoints()) {
                out.append(first ? "" : "<hr/>");
                first = false;
                createEndpointReport(out, endpoint, includeStages);
            }
        }
        out.append("</div>\n");
    }

    @Override
    public void createFactorySummary(Appendable out, boolean includeInitiators, boolean includeEndpoints)
            throws IOException {
        if (includeInitiators || includeEndpoints) {
            // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
            LocalStatsMatsInterceptor localStats = null;
            if (_matsInterceptable != null) {
                localStats = _matsInterceptable
                        .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
            }
            out.append("<table class='matsli_table_summary matsli_report'>");
            out.append("<thead><tr>");
            out.append("<th>Initiator Name / Endpoint Id</th>");
            out.append("<th>type</th>");
            out.append("<th>msgs</th>");
            out.append("<th>samples</th>");
            out.append("<th>avg</th>");
            out.append("<th>median</th>");
            out.append("<th>75%</th>");
            out.append("<th>95%</th>");
            out.append("<th>99.9%</th>");
            out.append("<th>Stages</th>");
            out.append("</tr></thead>");
            if (includeInitiators) {
                for (MatsInitiator matsInitiator : _matsFactory.getInitiators()) {

                    out.append("<tr class='matsli_summary_initiator_row'>");
                    out.append("<td>").append("&nbsp;&nbsp;<a href='#matsInitiator_")
                            .append(_matsFactory.getFactoryConfig().getName())
                            .append("_")
                            .append(matsInitiator.getName())
                            .append("'>")
                            .append(matsInitiator.getName()).append("</a><br/>\n")
                            .append("</td>");
                    out.append("<td class='matsli_right'>Initiator</td>");
                    if ((localStats != null) && localStats.getInitiatorStats(matsInitiator).isPresent()) {
                        InitiatorStats stats = localStats.getInitiatorStats(matsInitiator).get();
                        long sumOutMsgs = stats.getOutgoingMessageCounts().values()
                                .stream().mapToLong(Long::longValue).sum();
                        out.append("<td class='matsli_right'>").append(formatInt(sumOutMsgs)).append("</td>");

                        StatsSnapshot execSnapshot = stats.getTotalExecutionTimeNanos();
                        out.append("<td class='matsli_right'>")
                                .append(formatInt(execSnapshot.getSamples().length)).append("</td>");
                        timingCellForAverage(out, execSnapshot);
                        timingCell(out, execSnapshot.getMedian());
                        timingCell(out, execSnapshot.get75thPercentile());
                        timingCell(out, execSnapshot.get95thPercentile());
                        timingCell(out, execSnapshot.get999thPercentile());
                    }
                    else {
                        out.append("<td colspan=7></td>");
                    }
                    out.append("<td></td>"); // No stages for Initiator.
                    out.append("</tr>");
                }
            }
            if (includeEndpoints) {
                List<MatsEndpoint<?, ?>> endpoints = _matsFactory.getEndpoints();
                for (int i = 0; i < endpoints.size(); i++) {
                    MatsEndpoint<?, ?> matsEndpoint = endpoints.get(i);

                    if (i == (endpoints.size() - 1)) {
                        out.append("<tr class='matsli_summary_lastline'>");
                    }
                    else {
                        out.append("<tr>");
                    }
                    boolean subscription = matsEndpoint.getEndpointConfig().isSubscription();
                    out.append("<td>").append("&nbsp;&nbsp;")
                            .append(subscription ? "<i>" : "")
                            .append("<a href='#matsEndpoint_")
                            .append(_matsFactory.getFactoryConfig().getName())
                            .append("_")
                            .append(matsEndpoint.getEndpointConfig().getEndpointId())
                            .append("'>")
                            .append(matsEndpoint.getEndpointConfig().getEndpointId()).append("</a><br/>\n")
                            .append(subscription ? "</i>" : "")
                            .append("</td>");
                    out.append("<td class='matsli_right'>")
                            .append(subscription ? "<i>" : "")
                            .append(deduceEndpointType(matsEndpoint))
                            .append(subscription ? "</i>" : "")
                            .append("</td>");

                    if ((localStats != null) && localStats.getEndpointStats(matsEndpoint).isPresent()) {
                        EndpointStats endpointStats = localStats.getEndpointStats(matsEndpoint).get();
                        long sumOutMsgs = endpointStats.getStagesStats().get(0).getIncomingMessageCounts().values()
                                .stream().mapToLong(Long::longValue).sum();
                        out.append("<td class='matsli_right'>").append(formatInt(sumOutMsgs)).append("</td>");

                        StatsSnapshot execSnapshot = endpointStats.getTotalEndpointProcessingTimeNanos();
                        out.append("<td class='matsli_right'>")
                                .append(formatInt(execSnapshot.getSamples().length)).append("</td>");
                        timingCellForAverage(out, execSnapshot);
                        timingCell(out, execSnapshot.getMedian());
                        timingCell(out, execSnapshot.get75thPercentile());
                        timingCell(out, execSnapshot.get95thPercentile());
                        timingCell(out, execSnapshot.get999thPercentile());
                    }
                    else {
                        out.append("<td colspan=7></td>");
                    }

                    // :: STAGES
                    out.append("<td>");
                    for (MatsStage<?, ?, ?> matsStage : matsEndpoint.getStages()) {

                        // :: Time between stages (in front of stage)
                        if ((localStats != null) && localStats.getStageStats(matsStage).isPresent()) {
                            StageStats stageStats = localStats.getStageStats(matsStage).get();
                            Optional<StatsSnapshot> betweenSnapshot = stageStats
                                    .getBetweenStagesTimeNanos();
                            // ?: Do we have Between-stats? (Do not have for initial stage).
                            if (betweenSnapshot.isPresent()) {
                                summaryStageTime(out, betweenSnapshot.get());
                            }
                        }
                        out.append("<div class='matsli_stage_summary_box'>");
                        // Queue time:
                        if ((localStats != null) && localStats.getStageStats(matsStage).isPresent()) {
                            StageStats stageStats = localStats.getStageStats(matsStage).get();
                            StatsSnapshot queueSnapshot = stageStats.getSpentQueueTimeNanos();
                            summaryStageTime(out, queueSnapshot);
                        }

                        out.append("<a href='#");
                        if (matsStage.getStageConfig().getStageIndex() == 0) {
                            out.append("matsEndpoint_")
                                    .append(_matsFactory.getFactoryConfig().getName())
                                    .append("_")
                                    .append(matsEndpoint.getEndpointConfig().getEndpointId())
                                    .append("'>");
                        }
                        else {
                            out.append("matsStage_")
                                    .append(_matsFactory.getFactoryConfig().getName())
                                    .append("_")
                                    .append(matsStage.getStageConfig().getStageId())
                                    .append("'>");
                        }
                        out.append("S:").append(Integer.toString(matsStage.getStageConfig().getStageIndex()))
                                .append("</a>");

                        // Processing time:
                        if ((localStats != null) && localStats.getStageStats(matsStage).isPresent()) {
                            StageStats stageStats = localStats.getStageStats(matsStage).get();
                            StatsSnapshot execSnapshot = stageStats.getStageTotalExecutionTimeNanos();
                            summaryStageTime(out, execSnapshot);
                        }

                        out.append("</div>");
                    }
                    out.append("</td>");

                    out.append("</tr>");
                }
                out.append("<tr><td colspan=100 class='matsli_right'>");
                out.append("Legend: <div class='matsli_stage_summary_box'>");
                out.append("<div class='matsli_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>{queue time}</div>");
                out.append("S:1");
                out.append("<div class='matsli_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>{process time}</div>");
                out.append("</div>");
                out.append("<div class='matsli_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>{time between}</div>");
                out.append("<div class='matsli_stage_summary_box'>");
                out.append("<div class='matsli_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>...</div>");
                out.append("S:2");
                out.append("<div class='matsli_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>...</div>");
                out.append("</div>... (95th pctl)");
                out.append("<div style='width: 3em; display:inline-block'></div>");
                out.append("Timings: ");
                legendTimingPatch(out, 0);
                legendTimingPatch(out, 25);
                legendTimingPatch(out, 50);
                legendTimingPatch(out, 75);
                legendTimingPatch(out, 100);
                legendTimingPatch(out, 150);
                legendTimingPatch(out, 200);
                legendTimingPatch(out, 250);
                legendTimingPatch(out, 300);
                legendTimingPatch(out, 400);
                legendTimingPatch(out, 500);
                legendTimingPatch(out, 750);
                legendTimingPatch(out, 1000);
                legendTimingPatch(out, 1250);
                legendTimingPatch(out, 1500);
                legendTimingPatch(out, 1750);
                legendTimingPatch(out, 2000);
                out.append("<br>\n");
                out.append("<span style='vertical-align: -0.5em; font-size: 95%'>Notice:"
                        + " <b>#1</b> Pay attention to the {queue time} of the initial stage: It is <i>not</i> included"
                        + " in the Endpoint total times."
                        + " <b>#2</b> The {time between} will in practice include the {queue time} of the following"
                        + " stage."
                        + " <b>#3</b> The {queue time} is susceptible to time skews between nodes.</span>\n");
                out.append("</td></tr>");
            }
            out.append("</table>");
        }
    }

    void summaryStageTime(Appendable out, StatsSnapshot stats) throws IOException {
        out.append("<div class='matsli_tooltip'>"
                + "<div class='matsli_summary_time' style='background: " + colorForNanos(stats
                        .get95thPercentile()).toCss() + "'>" + formatNanos0(stats.get95thPercentile()) + "</div>"
                + "<div class='matsli_tooltiptext'>" + formatStats(stats, true) + "</div>"
                + "</div>");

    }

    void timingCell(Appendable out, double nanos) throws IOException {
        out.append("<td class='matsli_right' style='background:")
                .append(colorForNanos(nanos).toCss()).append("'>")
                .append(formatNanos1(nanos)).append("</td>");
    }

    void timingCellForAverage(Appendable out, StatsSnapshot snapshot) throws IOException {
        out.append("<td class='matsli_right' style='background:")
                .append(colorForNanos(snapshot.getAverage()).toCss()).append("'>")
                .append("<div class='matsli_tooltip'>")
                .append(formatNanos1(snapshot.getAverage()))
                .append("<div class='matsli_tooltiptext'>")
                .append(formatStats(snapshot, true)).append("</div></div>")
                .append("</td>");
    }

    void legendTimingPatch(Appendable out, double ms) throws IOException {
        out.append("<span style='background: " + colorForMs(ms).toCss() + "'>&nbsp;" + Math.round(ms)
                + "&nbsp;</span> ");
    }

    @Override
    public void createInitiatorReport(Appendable out, MatsInitiator matsInitiator)
            throws IOException {
        // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
        LocalStatsMatsInterceptor localStats = null;
        if (_matsInterceptable != null) {
            localStats = _matsInterceptable
                    .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
        }

        out.append("<div class='matsli_report matsli_initiator' id='matsInitiator_")
                .append(matsInitiator.getParentFactory().getFactoryConfig().getName())
                .append("_")
                .append(matsInitiator.getName()).append("'>\n");
        out.append("<div class='matsli_heading'>Initiator <h3>" + matsInitiator.getName() + "</h3>\n");
        out.append("</div>\n");
        out.append("<div class='matsli_info'>\n");
        if (localStats != null) {
            Optional<InitiatorStats> initiatorStats_ = localStats.getInitiatorStats(matsInitiator);
            if (initiatorStats_.isPresent()) {
                InitiatorStats initiatorStats = initiatorStats_.get();
                StatsSnapshot stats = initiatorStats.getTotalExecutionTimeNanos();

                out.append("<b>Total initiation time:</b> " + formatStats(stats, false) + "<br/>\n");
                SortedMap<OutgoingMessageRepresentation, Long> outgoingMessageCounts = initiatorStats
                        .getOutgoingMessageCounts();
                long sumOutMsgs = outgoingMessageCounts.values().stream().mapToLong(Long::longValue).sum();
                if (outgoingMessageCounts.isEmpty()) {
                    out.append("<b>NO outgoing messages!</b><br/>\n");
                }
                else if (outgoingMessageCounts.size() == 1) {
                    out.append("<b>Outgoing messages:</b> \n");

                }
                else {
                    out.append("<b>Outgoing messages (" + formatInt(sumOutMsgs) + "):</b><br/>\n");
                }

                for (Entry<OutgoingMessageRepresentation, Long> entry : outgoingMessageCounts.entrySet()) {
                    OutgoingMessageRepresentation msg = entry.getKey();
                    out.append("&nbsp;&nbsp;" + formatInt(entry.getValue()) + " x " + formatClass(msg.getMessageClass())
                            + " " + msg.getMessageType() + " from initiatorId " + formatIid(msg.getInitiatorId())
                            + " to " + formatEpid(msg.getTo()) + "<br/>");
                }
            }
            else {
                out.append("<i>&mdash; No statistics gathered &mdash;</i>\n");
            }
        }
        out.append("</div>\n");
        out.append("</div>\n");
    }

    @Override
    public void createEndpointReport(Appendable out, MatsEndpoint<?, ?> matsEndpoint, boolean includeStages)
            throws IOException {
        // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
        LocalStatsMatsInterceptor localStats = null;
        if (_matsInterceptable != null) {
            localStats = _matsInterceptable
                    .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
        }

        EndpointConfig<?, ?> config = matsEndpoint.getEndpointConfig();

        StatsSnapshot totExecSnapshot = null;
        EndpointStats endpointStats = null;
        if (localStats != null) {
            Optional<EndpointStats> endpointStats_ = localStats.getEndpointStats(matsEndpoint);
            if (endpointStats_.isPresent()) {
                endpointStats = endpointStats_.get();
                totExecSnapshot = endpointStats.getTotalEndpointProcessingTimeNanos();
            }
        }

        // If we have snapshot, and the 99.5% percentile is too high, add the "mats hot" class.
        String hot = (totExecSnapshot != null) && (totExecSnapshot.get999thPercentile() > 1000_000_000d)
                ? " matsli_hot"
                : "";

        String type = deduceEndpointType(matsEndpoint);
        out.append("<div class='matsli_report matsli_endpoint" + hot + "' id='matsEndpoint_")
                .append(matsEndpoint.getParentFactory().getFactoryConfig().getName())
                .append("_")
                .append(config.getEndpointId()).append("'>\n");
        out.append("<div class='matsli_heading'>" + type + " <h3>" + config.getEndpointId() + "</h3>");
        out.append(" - " + formatIoClass("Incoming", config.getIncomingClass()));
        out.append(" - " + formatIoClass("Reply", config.getReplyClass()));
        out.append(" - " + formatIoClass("State", config.getStateClass()));
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config) + "\n");
        out.append("<br/>");
        out.append("<div class='matsli_creation_info'>")
                .append(matsEndpoint.getEndpointConfig().getOrigin().replace(";", " - \n"))
                .append("</div>");
        out.append("</div>\n");

        out.append("<div class='matsli_info'>\n");
        // out.append()("Worst stage duty cycle: ### <br/>\n");

        if (endpointStats != null) {
            NavigableMap<IncomingMessageRepresentation, StatsSnapshot> initiatorToTerminatorTimeNanos = endpointStats
                    .getInitiatorToTerminatorTimeNanos();

            if (!initiatorToTerminatorTimeNanos.isEmpty()) {
                out.append("<b>From Initiator to Terminator times:</b> <i>(From start of MatsInitiator.initiate(..),"
                        + " to reception on initial stage of terminator. Susceptible to time skews if initiated"
                        + " on different app.)</i><br/>\n");

                out.append("<table class='matsli_table_init_to_term'>");
                out.append("<thead><tr>");
                out.append("<th>Initiated from</th>");
                out.append("<th>from</th>");
                out.append("<th>observ</th>");
                out.append("<th>samples</th>");
                out.append("<th>avg</th>");
                out.append("<th>median</th>");
                out.append("<th>75%</th>");
                out.append("<th>95%</th>");
                out.append("<th>99.9%</th>");
                out.append("</tr></thead>");
                out.append("<tbody>");
                for (Entry<IncomingMessageRepresentation, StatsSnapshot> entry : initiatorToTerminatorTimeNanos
                        .entrySet()) {
                    IncomingMessageRepresentation msg = entry.getKey();
                    StatsSnapshot snapshot = entry.getValue();

                    out.append("<tr>");
                    out.append("<td>" + formatIid(msg.getInitiatorId())
                            + " @ " + formatAppName(msg.getInitiatingAppName()) + "</td>");
                    out.append("<td>" + formatMsgType(msg.getMessageType()) + " from "
                            + formatEpid(msg.getFromStageId())
                            + " @ " + formatAppName(msg.getFromAppName()) + "</td>");
                    out.append("<td class='matsli_right'>")
                            .append(formatInt(snapshot.getNumObservations())).append("</td>");
                    out.append("<td class='matsli_right'>")
                            .append(formatInt(snapshot.getSamples().length)).append("</td>");
                    timingCellForAverage(out, snapshot);
                    timingCell(out, snapshot.getMedian());
                    timingCell(out, snapshot.get75thPercentile());
                    timingCell(out, snapshot.get95thPercentile());
                    timingCell(out, snapshot.get999thPercentile());
                    out.append("</tr>");
                }
                out.append("</tbody></table>");
                out.append("<br/>\n");
            }
        }

        if (totExecSnapshot != null) {
            out.append("<b>Total endpoint time:</b> " + formatStats(totExecSnapshot, false) + "<br/>"
                    + "<span style='font-size: 75%; vertical-align: 0.5em'><i>(Note: From entry on Initial Stage to REPLY or NONE."
                    + " <b>Does not include queue time for Initial Stage!</b>)</i><br/></span>\n");
        }

        out.append("</div>\n");

        if (includeStages) {
            for (MatsStage<?, ?, ?> stage : matsEndpoint.getStages()) {
                createStageReport(out, stage);
            }
        }
        out.append("</div>\n");
    }

    @Override
    public void createStageReport(Appendable out, MatsStage<?, ?, ?> matsStage) throws IOException {
        // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
        LocalStatsMatsInterceptor localStats = null;
        if (_matsInterceptable != null) {
            localStats = _matsInterceptable
                    .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
        }

        StageConfig<?, ?, ?> config = matsStage.getStageConfig();

        String anchorId = "matsStage_" + matsStage.getParentEndpoint().getParentFactory().getFactoryConfig().getName()
                + "_" + config.getStageId();

        // :: Time between stages
        boolean anchroIdPrinted = false;
        if (localStats != null) {
            Optional<StageStats> stageStats_ = localStats.getStageStats(matsStage);
            if (stageStats_.isPresent()) {
                StageStats stageStats = stageStats_.get();
                Optional<StatsSnapshot> stats = stageStats.getBetweenStagesTimeNanos();
                // ?: Do we have Between-stats? (Do not have for initial stage).
                if (stats.isPresent()) {
                    out.append("<div class='matsli_info' id='").append(anchorId).append("'><b>Time between:</b> ")
                            .append(formatStats(stats.get(), false)).append("</div>\n");
                    anchroIdPrinted = true;
                }
            }
        }

        StatsSnapshot totExecSnapshot = null;
        StageStats stageStats = null;
        if (localStats != null) {
            Optional<StageStats> stageStats_ = localStats.getStageStats(matsStage);
            if (stageStats_.isPresent()) {
                stageStats = stageStats_.get();
                totExecSnapshot = stageStats.getStageTotalExecutionTimeNanos();
            }
        }

        // If we have snapshot, and the 99.5% percentile is too high, add the "mats hot" class.
        String hot = (totExecSnapshot != null) && (totExecSnapshot.get999thPercentile() > 500_000_000d)
                ? " matsli_hot"
                : "";
        out.append("<div class='matsli_report matsli_stage" + hot + "'" +
                (anchroIdPrinted ? "" : " id='" + anchorId + "'") + ">\n");
        out.append("<div class='matsli_heading'>Stage <h4>" + config.getStageId() + "</h4>\n");
        out.append(" - <b>Incoming:</b> <code>" + config.getIncomingClass().getSimpleName() + "</code>\n");
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config) + "\n");
        out.append(" - <b>Running stage processors:</b> " + config.getRunningStageProcessors() + "\n");
        out.append("<br/>");
        out.append("<div class='matsli_creation_info'>")
                .append(matsStage.getStageConfig().getOrigin().replace(";", " - \n"))
                .append("</div>");
        out.append("</div>");

        out.append("<div class='matsli_info'>\n");
        // out.append()("Duty cycle: ### <br/>\n");
        // out.append()("Oldest reported 'check-in' for stage procs: ### seconds ago."
        // + " <b>Stuck stage procs: ###</b><br/>\n");
        if ((stageStats != null) && (totExecSnapshot != null)) {

            boolean initialStage = config.getStageIndex() == 0;

            out.append("<b>Queue time</b>: " + formatStats(stageStats.getSpentQueueTimeNanos(), false)
                    + " (susceptible to time skews between nodes)<br/>\n");

            Map<IncomingMessageRepresentation, Long> incomingMessageCounts = stageStats.getIncomingMessageCounts();
            if (incomingMessageCounts.isEmpty()) {
                out.append("<b>NO incoming messages!</b>\n");
            }
            else if (incomingMessageCounts.size() == 1) {
                out.append("<b>Incoming messages:</b> ");
                Entry<IncomingMessageRepresentation, Long> entry = incomingMessageCounts.entrySet().iterator().next();
                IncomingMessageRepresentation msg = entry.getKey();
                out.append(formatInt(entry.getValue()) + " x " + formatMsgType(msg.getMessageType())
                        + " from " + formatEpid(msg.getFromStageId()) + " <b>@</b> "
                        + formatAppName(msg.getFromAppName()) + formatInit(msg) + "<br/>");
            }
            else {
                out.append("<span>"); // to have the buttons and summary+details in same container
                out.append("<b>Incoming messages (" + formatInt(totExecSnapshot.getNumObservations()) + "):</b>"
                        + " <div class='matsli_msgs_summary_btn matsli_msgs_summary_or_details_btn_active'"
                        + " onclick='matsli_messages_summary(event)'>Details - <i>click for summary</i></div>"
                        + " <div class='matsli_msgs_details_btn'"
                        + " onclick='matsli_messages_details(event)'>Summary - <i>click for details ("
                        + incomingMessageCounts.size() + ")</i></div>"
                        + "<br/>\n");

                out.append("<div class='matsli_msgs_summary'>");
                Map<String, AtomicLong> summer = new TreeMap<>();
                for (Entry<IncomingMessageRepresentation, Long> entry : incomingMessageCounts.entrySet()) {
                    IncomingMessageRepresentation msg = entry.getKey();
                    // Different handling whether InitialStage or any other stage.
                    String key = msg.getMessageType() + "#"
                            + (initialStage ? msg.getInitiatingAppName() : msg.getFromStageId());
                    AtomicLong count = summer.computeIfAbsent(key, s -> new AtomicLong());
                    count.addAndGet(entry.getValue());
                }
                for (Entry<String, AtomicLong> entry : summer.entrySet()) {
                    int hash = entry.getKey().indexOf('#');
                    String messageType = entry.getKey().substring(0, hash);
                    String fromOrInitiatingApp = entry.getKey().substring(hash + 1);
                    out.append(formatInt(entry.getValue().get())).append(" x ").append(formatMsgType(messageType));
                    // Different handling whether InitialStage or any other stage.
                    if (initialStage) {
                        out.append(", flows initiated by ").append(formatAppName(fromOrInitiatingApp));
                    }
                    else {
                        out.append(" from stageId ").append(formatEpid(fromOrInitiatingApp));
                    }
                    out.append("<br/>\n");
                }
                out.append("</div>\n");

                out.append("<div class='matsli_msgs_details matsli_noshow'>");
                for (Entry<IncomingMessageRepresentation, Long> entry : incomingMessageCounts.entrySet()) {
                    IncomingMessageRepresentation msg = entry.getKey();
                    out.append(formatInt(entry.getValue()) + " x " + formatMsgType(msg.getMessageType())
                            + " from " + formatEpid(msg.getFromStageId()) + " <b>@</b> "
                            + formatAppName(msg.getFromAppName()) + formatInit(msg) + "<br/>");
                }
                out.append("</div></span>\n");
            }
            out.append("<b>Total stage time:</b> " + formatStats(totExecSnapshot, false) + "<br/>\n");

            // :: ProcessingResults
            SortedMap<StageProcessResult, Long> processResultCounts = stageStats.getProcessResultCounts();
            if (processResultCounts.isEmpty()) {
                out.append("<b>NO processing results!</b><br/>\n");
            }
            else {
                out.append("<b>Processing results:</b> \n");
                boolean first = true;
                for (Entry<StageProcessResult, Long> entry : processResultCounts.entrySet()) {
                    out.append(first ? "" : ", ");
                    first = false;
                    StageProcessResult stageProcessResult = entry.getKey();
                    out.append(formatInt(entry.getValue()) + " x " + formatMsgType(stageProcessResult));
                }
                out.append("<br/>\n");
            }

            // :: Outgoing messages
            SortedMap<OutgoingMessageRepresentation, Long> outgoingMessageCounts = stageStats
                    .getOutgoingMessageCounts();
            long sumOutMsgs = outgoingMessageCounts.values().stream().mapToLong(Long::longValue).sum();
            if (outgoingMessageCounts.isEmpty()) {
                out.append("<b>NO outgoing messages!</b><br/>\n");
            }
            else if (outgoingMessageCounts.size() == 1) {
                out.append("<b>Outgoing messages:</b> ");
                Entry<OutgoingMessageRepresentation, Long> entry = outgoingMessageCounts.entrySet().iterator().next();
                OutgoingMessageRepresentation msg = entry.getKey();
                out.append(formatInt(entry.getValue())
                        + " x " + formatClass(msg.getMessageClass())
                        + " " + formatMsgType(msg.getMessageType()) + " to " + formatEpid(msg.getTo())
                        + formatInit(msg) + "<br/>");
            }
            else {
                out.append("<span>"); // to have the buttons and summary+details in same container
                out.append("<b>Outgoing messages (" + formatInt(sumOutMsgs) + "):</b>"
                        + " <div class='matsli_msgs_summary_btn matsli_msgs_summary_or_details_btn_active'"
                        + " onclick='matsli_messages_summary(event)'>Details - <i>click for summary</i></div>"
                        + " <div class='matsli_msgs_details_btn'"
                        + " onclick='matsli_messages_details(event)'>Summary - <i>click for details ("
                        + outgoingMessageCounts.size() + ")</i></div>"
                        + "<br/>\n");

                out.append("<div class='matsli_msgs_summary'>");
                Map<String, AtomicLong> summer = new TreeMap<>();
                // :: Outgoing Messages Summary: Calculate ..
                for (Entry<OutgoingMessageRepresentation, Long> entry : outgoingMessageCounts.entrySet()) {
                    OutgoingMessageRepresentation msg = entry.getKey();
                    // Different handling whether REPLY or any other
                    boolean replyMsg = msg.getMessageType() == MessageType.REPLY;
                    String messageClassName = msg.getMessageClass() == null
                            ? "null"
                            : msg.getMessageClass().getSimpleName();
                    String key = msg.getMessageType() + "#" + messageClassName + "#" +
                            (replyMsg ? msg.getInitiatingAppName() : msg.getTo());
                    AtomicLong count = summer.computeIfAbsent(key, s -> new AtomicLong());
                    count.addAndGet(entry.getValue());
                }
                // .. then output summary
                for (Entry<String, AtomicLong> entry : summer.entrySet()) {
                    int hashIdx1 = entry.getKey().indexOf('#');
                    int hashIdx2 = entry.getKey().indexOf('#', hashIdx1 + 1);
                    String messageType = entry.getKey().substring(0, hashIdx1);
                    boolean isReplyMsg = "REPLY".equals(messageType);
                    String messageClass = entry.getKey().substring(hashIdx1 + 1, hashIdx2);
                    String toOrInitiatingApp = entry.getKey().substring(hashIdx2 + 1);
                    // Different handling whether REPLY or any other
                    out.append(formatInt(entry.getValue().get())).append(" x ").append(formatClass(messageClass))
                            .append(' ').append(formatMsgType(messageType));
                    if (isReplyMsg) {
                        out.append(", flows initiated by ").append(formatAppName(toOrInitiatingApp));
                    }
                    else {
                        out.append(" to ").append(formatEpid(toOrInitiatingApp));
                    }
                    out.append("<br/>\n");
                }
                out.append("</div>");

                // :: Outgoing Messages Details
                out.append("<div class='matsli_msgs_details matsli_noshow'>");
                for (Entry<OutgoingMessageRepresentation, Long> entry : outgoingMessageCounts.entrySet()) {
                    OutgoingMessageRepresentation msg = entry.getKey();
                    out.append(formatInt(entry.getValue())
                            + " x " + formatClass(msg.getMessageClass())
                            + " " + formatMsgType(msg.getMessageType()) + " to " + formatEpid(msg.getTo())
                            + formatInit(msg) + "<br/>");
                }
                out.append("</div></span>\n");
            }
        }
        else {
            out.append("<i>&mdash; No statistics gathered &mdash;</i>\n");
        }
        out.append("</div>\n");
        out.append("</div>\n");
    }

    // Could be static, but aren't, in case anyone wants to override them.
    // NOTE: These are NOT part of any "stable API" promises!

    static class RgbaColor {
        private final int r;
        private final int g;
        private final int b;
        private final double a;

        public RgbaColor(int r, int g, int b, double a) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }

        RgbaColor interpolate(RgbaColor to, double blendTo) {
            if ((blendTo > 1.0) || (blendTo < 0.0)) {
                throw new IllegalArgumentException("Blend must be [0, 1], not '" + blendTo + "'.");
            }
            double inverseBlend = 1 - blendTo;
            final int newR = (int) Math.round((this.r * inverseBlend) + (to.r * blendTo));
            final int newG = (int) Math.round((this.g * inverseBlend) + (to.g * blendTo));
            final int newB = (int) Math.round((this.b * inverseBlend) + (to.b * blendTo));

            final double newA = this.a * inverseBlend + to.a * blendTo;

            return new RgbaColor(newR, newG, newB, newA);
        }

        RgbaColor interpolate(RgbaColor to, double rangeFrom, double rangeTo, double value) {
            if ((value > rangeTo) || (value < rangeFrom)) {
                throw new IllegalArgumentException("value must be in range [" + rangeFrom + "," + rangeTo + "], not '"
                        + value + "'");
            }
            double rangeSpan = rangeTo - rangeFrom;
            double valueInRange = value - rangeFrom;
            double blend = valueInRange / rangeSpan;
            return interpolate(to, blend);
        }

        String toCss() {
            return "rgba(" + r + "," + g + "," + b + "," + (Math.round(a * 1000) / 1000d);
        }
    }

    RgbaColor ms0 = new RgbaColor(128, 255, 128, 1);
    // 100 ms is about the threshold for perception of instantaneous.
    // E.g. https://www.pubnub.com/blog/how-fast-is-realtime-human-perception-and-technology/
    // As pointed out, in a continuous information setting, e.g. video, this is reduced to 13ms.
    RgbaColor ms100 = new RgbaColor(0, 192, 0, 1);
    RgbaColor ms250 = new RgbaColor(0, 128, 192, 1);
    RgbaColor ms500 = new RgbaColor(0, 64, 255, 1);
    RgbaColor ms1000 = new RgbaColor(255, 0, 192, 1);
    RgbaColor ms2000 = new RgbaColor(255, 0, 0, 1);

    RgbaColor colorForMs(double ms) {
        RgbaColor color;
        if (ms < 0) {
            // -> Handle negative timings, which must be due to time skews, or 2xstd.dev. calculations.
            color = ms0;
        }
        else if (ms < 100) {
            color = ms0.interpolate(ms100, 0, 100, ms);
        }
        else if (ms < 250) {
            color = ms100.interpolate(ms250, 100, 250, ms);
        }
        else if (ms < 500) {
            color = ms250.interpolate(ms500, 250, 500, ms);
        }
        else if (ms < 1000) {
            color = ms500.interpolate(ms1000, 500, 1000, ms);
        }
        else if (ms < 2000) {
            color = ms1000.interpolate(ms2000, 1000, 2000, ms);
        }
        else {
            color = ms2000;
        }

        return color.interpolate(new RgbaColor(255, 255, 255, 1), 0.5d);
    }

    RgbaColor colorForNanos(double nanos) {
        return colorForMs(nanos / 1_000_000d);
    }

    String deduceEndpointType(MatsEndpoint<?, ?> matsEndpoint) {
        EndpointConfig<?, ?> config = matsEndpoint.getEndpointConfig();
        String type = config.getReplyClass() == void.class ? "Terminator" : "Endpoint";
        if ((matsEndpoint.getStages().size() == 1) && (config.getReplyClass() != void.class)) {
            type = "Single " + type;
        }
        if (matsEndpoint.getStages().size() > 1) {
            type = matsEndpoint.getStages().size() + "-Stage " + type;
        }
        if (config.isSubscription()) {
            type = "Subscription " + type;
        }
        return type;
    }

    String formatIid(String iid) {
        return "<span class='matsli_iid'>" + iid + "</span>";
    }

    String formatEpid(String epid) {
        return "<span class='matsli_epid'>" + epid + "</span>";
    }

    String formatAppName(String appName) {
        return "<span class='matsli_appname'>" + appName + "</span>";
    }

    String formatMsgType(Object messageType) {
        return "<span class='matsli_msgtype'>" + messageType.toString() + "</span>";
    }

    String formatInit(MessageRepresentation msg) {
        return " &mdash; <i>init:" + formatIid(msg.getInitiatorId()) + " <b>@</b> "
                + formatAppName(msg.getInitiatingAppName()) + "</i>";
    }

    String formatIoClass(String what, Class<?> type) throws IOException {
        boolean isVoid = type == Void.TYPE;
        return (isVoid ? "<s>" : "")
                + "<b>" + what + ":</b> " + formatClass(type)
                + (isVoid ? "</s>" : "") + "\n";
    }

    String formatClass(Class<?> type) {
        if (type == null) {
            return "<code><i>null</i></code>";
        }
        return "<code>" + type.getSimpleName() + "</code>";
    }

    String formatClass(String type) {
        if ((type == null) || ("null".equals(type))) {
            return "<code><i>null</i></code>";
        }
        return "<code>" + type + "</code>";
    }

    String formatConcurrency(MatsConfig config) {
        return config.getConcurrency() + (config.isConcurrencyDefault() ? " <i>(inherited)</i>"
                : " <i><b>(explicitly set)</b></i>");
    }

    String formatStats(StatsSnapshot snapshot, boolean tooltipStyle) {
        double sd = snapshot.getStdDev();
        double avg = snapshot.getAverage();
        return "<b>avg:</b>" + colorAndFormatNanos(avg)
                + " <b><i>sd</i>:</b>" + formatNanos(sd)
                + " &mdash; <b>50%:</b>" + colorAndFormatNanos(snapshot.getMedian())
                + ", <b>75%:</b>" + colorAndFormatNanos(snapshot.get75thPercentile())
                + ", <b>95%:</b>" + colorAndFormatNanos(snapshot.get95thPercentile())
                + ", <b>98%:</b>" + colorAndFormatNanos(snapshot.get98thPercentile())
                + ", <b>99%:</b>" + colorAndFormatNanos(snapshot.get99thPercentile())
                + ", <b>99.9%:</b>" + colorAndFormatNanos(snapshot.get999thPercentile())
                + ", <b><span class='matsli_max'>max:</sup></b>" + colorAndFormatNanos(snapshot.getMax())
                + " - <b><span class='matsli_min'>min:</span></b>" + formatNanos(snapshot.getMin())
                + (tooltipStyle ? "<br/>\n" : " &mdash; ")
                + "<i>number of samples: " + formatInt(snapshot.getSamples().length)
                + ", out of observations:" + formatInt(snapshot.getNumObservations()) + "</i>";
    }

    String colorAndFormatNanos(double nanos) {
        return "<span style='background: " + colorForNanos(nanos).toCss() + "'>" + formatNanos(nanos) + "</span>";
    }

    static final DecimalFormatSymbols NF_SYMBOLS;
    static final DecimalFormat NF_INTEGER;

    static final DecimalFormat NF_0_DECIMALS;
    static final DecimalFormat NF_1_DECIMALS;
    static final DecimalFormat NF_2_DECIMALS;
    static final DecimalFormat NF_3_DECIMALS;
    static {
        NF_SYMBOLS = new DecimalFormatSymbols(Locale.US);
        NF_SYMBOLS.setDecimalSeparator('.');
        NF_SYMBOLS.setGroupingSeparator('\u202f');

        NF_INTEGER = new DecimalFormat("#,##0");
        NF_INTEGER.setMaximumFractionDigits(0);
        NF_INTEGER.setDecimalFormatSymbols(NF_SYMBOLS);

        NF_0_DECIMALS = new DecimalFormat("#,##0");
        NF_0_DECIMALS.setMaximumFractionDigits(0);
        NF_0_DECIMALS.setDecimalFormatSymbols(NF_SYMBOLS);

        NF_1_DECIMALS = new DecimalFormat("#,##0.0");
        NF_1_DECIMALS.setMaximumFractionDigits(1);
        NF_1_DECIMALS.setDecimalFormatSymbols(NF_SYMBOLS);

        NF_2_DECIMALS = new DecimalFormat("#,##0.00");
        NF_2_DECIMALS.setMaximumFractionDigits(2);
        NF_2_DECIMALS.setDecimalFormatSymbols(NF_SYMBOLS);

        NF_3_DECIMALS = new DecimalFormat("#,##0.000");
        NF_3_DECIMALS.setMaximumFractionDigits(3);
        NF_3_DECIMALS.setDecimalFormatSymbols(NF_SYMBOLS);
    }

    String formatInt(long number) {
        return NF_INTEGER.format(number);
    }

    String formatNanos0(double nanos) {
        if (Double.isNaN(nanos)) {
            return "NaN";
        }
        return NF_0_DECIMALS.format(Math.round(nanos / 1_000_000d));
    }

    String formatNanos1(double nanos) {
        if (Double.isNaN(nanos)) {
            return "NaN";
        }
        if (nanos == 0d) {
            return "0";
        }
        return NF_1_DECIMALS.format(Math.round(nanos / 100_000d) / 10d);
    }

    String formatNanos(double nanos) {
        if (Double.isNaN(nanos)) {
            return "NaN";
        }
        if (nanos == 0d) {
            return "0";
        }
        // >=500 ms?
        if (nanos >= 1_000_000L * 500) {
            // -> Yes, >500ms, so chop off fraction entirely, e.g. 612
            return NF_0_DECIMALS.format(Math.round(nanos / 1_000_000d));
        }
        // >=50 ms?
        if (nanos >= 1_000_000L * 50) {
            // -> Yes, >50ms, so use 1 decimal, e.g. 61.2
            return NF_1_DECIMALS.format(Math.round(nanos / 100_000d) / 10d);
        }
        // >=5 ms?
        if (nanos >= 1_000_000L * 5) {
            // -> Yes, >5ms, so use 2 decimal, e.g. 6.12
            return NF_2_DECIMALS.format(Math.round(nanos / 10_000d) / 100d);
        }
        // Negative? (Can happen when we to 'avg - 2 x std.dev', the result becomes negative)
        if (nanos < 0) {
            // -> Negative, so use three digits
            return NF_3_DECIMALS.format(Math.round(nanos / 1_000d) / 1_000d);
        }
        // E-> <5 ms
        // Use 3 decimals, e.g. 0.612
        double round = Math.round(nanos / 1_000d) / 1_000d;
        // ?: However, did we round to zero?
        if (round == 0) {
            // -> Yes, round to zero, so show special case
            return "~>0";
        }
        return NF_3_DECIMALS.format(round);
    }
}
