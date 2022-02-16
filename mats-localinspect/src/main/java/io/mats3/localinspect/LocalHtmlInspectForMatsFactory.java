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
import io.mats3.api.intercept.MatsStageInterceptor;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext.ProcessResult;
import io.mats3.localinspect.LocalStatsMatsInterceptor.EndpointStats;
import io.mats3.localinspect.LocalStatsMatsInterceptor.IncomingMessageRepresentation;
import io.mats3.localinspect.LocalStatsMatsInterceptor.InitiatorStats;
import io.mats3.localinspect.LocalStatsMatsInterceptor.MessageRepresentation;
import io.mats3.localinspect.LocalStatsMatsInterceptor.OutgoingMessageRepresentation;
import io.mats3.localinspect.LocalStatsMatsInterceptor.StageStats;
import io.mats3.localinspect.LocalStatsMatsInterceptor.StatsSnapshot;

/**
 * Will produce an "embeddable" HTML interface - notice that there are CSS ({@link #getStyleSheet(Appendable)}),
 * JavaScript ({@link #getJavaScript(Appendable)}) and HTML
 * ({@link #createFactoryReport(Appendable, boolean, boolean, boolean) createFactoryReport(Appendable,..)}) to include.
 * If the {@link LocalStatsMatsInterceptor} is installed on the {@link MatsFactory} implementing
 * {@link MatsInterceptable}, it will include pretty nice "local statistics" for all initiators, endpoints and stages.
 * <p />
 * Note: You are expected to {@link #create(MatsFactory) create} one instance of this class per MatsFactory, and keep
 * these around for the lifetime of the MatsFactories (i.e. for the JVM) - as in multiple singletons. Do not create one
 * per HTML request. The reason for this is that at a later point, this class might be extended with "active" features,
 * like stopping and starting endpoints, change the concurrency etc - at which point it might itself need active state,
 * e.g. for a feature like "stop this endpoint for 30 minutes".
 *
 * @author Endre Stølsvik 2021-03-25 - http://stolsvik.com/, endre@stolsvik.com
 */
public class LocalHtmlInspectForMatsFactory {

    public static LocalHtmlInspectForMatsFactory create(MatsFactory matsFactory) {
        return new LocalHtmlInspectForMatsFactory(matsFactory);
    }

    final MatsFactory _matsFactory;
    final MatsInterceptable _matsInterceptable;

    LocalHtmlInspectForMatsFactory(MatsFactory matsFactory) {
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
    public void getStyleSheet(Appendable out) throws IOException {
        includeFile(out, "localhtmlinspect.css");
    }

    /**
     * Note: The return from this method is static, and should only be included once per HTML page, no matter how many
     * MatsFactories you display.
     */
    public void getJavaScript(Appendable out) throws IOException {
        out.append("");
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

    public void createFactoryReport(Appendable out, boolean includeInitiators,
            boolean includeEndpoints, boolean includeStages) throws IOException {
        // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
        LocalStatsMatsInterceptor localStats = null;
        if (_matsInterceptable != null) {
            localStats = _matsInterceptable
                    .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
        }

        FactoryConfig config = _matsFactory.getFactoryConfig();
        out.append("<div class='mats_report mats_factory'>\n");
        out.append("<div class='mats_heading'>MatsFactory <h2>" + config.getName() + "</h2>\n");
        out.append(" - <b>Known number of CPUs:</b> " + config.getNumberOfCpus());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config));
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append("</div>\n");
        out.append("<hr />\n");

        out.append("<div class='mats_info'>");
        out.append("config: <b>Name:</b> " + config.getName());
        out.append(" - <b>App:</b> " + config.getAppName() + " v." + config.getAppVersion());
        out.append(" - <b>Nodename:</b> " + config.getNodename());
        out.append(" - <b>Destination prefix:</b> '" + config.getMatsDestinationPrefix() + "'");
        out.append(" - <b>Trace key:</b> '" + config.getMatsTraceKey() + "'<br/>\n");

        out.append("<br/>\n");
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
        if (includeInitiators) {
            for (MatsInitiator initiator : _matsFactory.getInitiators()) {
                createInitiatorReport(out, initiator);
            }
        }

        if (includeEndpoints) {
            for (MatsEndpoint<?, ?> endpoint : _matsFactory.getEndpoints()) {
                createEndpointReport(out, endpoint, includeStages);
            }
        }
        out.append("</div>\n");
    }

    public void createFactorySummary(Appendable out, boolean includeInitiators, boolean includeEndpoints) throws IOException {
        if (includeInitiators || includeEndpoints) {
            // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
            LocalStatsMatsInterceptor localStats = null;
            if (_matsInterceptable != null) {
                localStats = _matsInterceptable
                        .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
            }
            out.append("<table class='mats_table_summary'>");
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

                    out.append("<tr class='mats_summary_initiator_row'>");
                    out.append("<td>").append("&nbsp;&nbsp;<a href='#matsInitiator_")
                            .append(_matsFactory.getFactoryConfig().getName())
                            .append("_")
                            .append(matsInitiator.getName())
                            .append("'>")
                            .append(matsInitiator.getName()).append("</a><br/>\n")
                            .append("</td>");
                    out.append("<td class='mats_right'>Initiator</td>");
                    if ((localStats != null) && localStats.getInitiatorStats(matsInitiator).isPresent()) {
                        InitiatorStats stats = localStats.getInitiatorStats(matsInitiator).get();
                        long sumOutMsgs = stats.getOutgoingMessageCounts().values()
                                .stream().mapToLong(Long::longValue).sum();
                        out.append("<td class='mats_right'>").append(Long.toString(sumOutMsgs)).append("</td>");

                        StatsSnapshot execSnapshot = stats.getTotalExecutionTimeNanos();
                        out.append("<td class='mats_right'>")
                                .append(Integer.toString(execSnapshot.getSamples().length)).append("</td>");
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
                        out.append("<tr class='mats_summary_lastline'>");
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
                    out.append("<td class='mats_right'>")
                            .append(subscription ? "<i>" : "")
                            .append(deduceEndpointType(matsEndpoint))
                            .append(subscription ? "</i>" : "")
                            .append("</td>");

                    if ((localStats != null) && localStats.getEndpointStats(matsEndpoint).isPresent()) {
                        EndpointStats endpointStats = localStats.getEndpointStats(matsEndpoint).get();
                        long sumOutMsgs = endpointStats.getStagesStats().get(0).getIncomingMessageCounts().values()
                                .stream().mapToLong(Long::longValue).sum();
                        out.append("<td class='mats_right'>").append(Long.toString(sumOutMsgs)).append("</td>");

                        StatsSnapshot execSnapshot = endpointStats.getTotalEndpointProcessingTimeNanos();
                        out.append("<td class='mats_right'>")
                                .append(Integer.toString(execSnapshot.getSamples().length)).append("</td>");
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
                        out.append("<div class='mats_stage_summary_box'>");
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
                out.append("<tr><td colspan=100 class='mats_right'>");
                out.append("Legend: <div class='mats_stage_summary_box'>");
                out.append("<div class='mats_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>{queue time}</div>");
                out.append("S:1");
                out.append("<div class='mats_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>{process time}</div>");
                out.append("</div>");
                out.append("<div class='mats_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>{time between}</div>");
                out.append("<div class='mats_stage_summary_box'>");
                out.append("<div class='mats_summary_time' style='background: " + colorForMs(150).toCss()
                        + "'>...</div>");
                out.append("S:2");
                out.append("<div class='mats_summary_time' style='background: " + colorForMs(150).toCss()
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
                out.append("</td></tr>");
            }
            out.append("</table>");
        }
    }

    void summaryStageTime(Appendable out, StatsSnapshot stats) throws IOException {
        out.append("<div class='mats_tooltip'>"
                + "<div class='mats_summary_time' style='background: " + colorForNanos(stats
                        .get95thPercentile()).toCss() + "'>" + formatNanos0(stats.get95thPercentile()) + "</div>"
                + "<div class='mats_tooltiptext'>" + formatStats(stats) + "</div>"
                + "</div>");

    }

    void timingCell(Appendable out, double nanos) throws IOException {
        out.append("<td class='mats_right' style='background:")
                .append(colorForNanos(nanos).toCss()).append("'>")
                .append(formatNanos1(nanos)).append("</td>");
    }

    void timingCellForAverage(Appendable out, StatsSnapshot snapshot) throws IOException {
        out.append("<td class='mats_right' style='background:")
                .append(colorForNanos(snapshot.getAverage()).toCss()).append("'>")
                .append("<div class='mats_tooltip'>")
                .append(formatNanos1(snapshot.getAverage()))
                .append("<div class='mats_tooltiptext'>")
                .append(formatStats(snapshot)).append("</div></div>")
                .append("</td>");
    }

    void legendTimingPatch(Appendable out, double ms) throws IOException {
        out.append("<span style='background: " + colorForMs(ms).toCss() + "'>&nbsp;" + Math.round(ms) + "&nbsp;</span> ");
    }

    public void createInitiatorReport(Appendable out, MatsInitiator matsInitiator)
            throws IOException {
        // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
        LocalStatsMatsInterceptor localStats = null;
        if (_matsInterceptable != null) {
            localStats = _matsInterceptable
                    .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
        }

        out.append("<div class='mats_report mats_initiator' id='matsInitiator_")
                .append(matsInitiator.getParentFactory().getFactoryConfig().getName())
                .append("_")
                .append(matsInitiator.getName()).append("'>\n");
        out.append("<div class='mats_heading'>Initiator <h3>" + matsInitiator.getName() + "</h3>\n");
        out.append("</div>\n");
        out.append("<div class='mats_info'>\n");
        if (localStats != null) {
            Optional<InitiatorStats> initiatorStats_ = localStats.getInitiatorStats(matsInitiator);
            if (initiatorStats_.isPresent()) {
                InitiatorStats initiatorStats = initiatorStats_.get();
                StatsSnapshot stats = initiatorStats.getTotalExecutionTimeNanos();

                out.append("<b>Total initiation time:</b> " + formatStats(stats) + "<br/>\n");
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
        }
        out.append("</div>\n");
        out.append("</div>\n");
    }

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
                ? " mats_hot"
                : "";

        String type = deduceEndpointType(matsEndpoint);
        out.append("<div class='mats_report mats_endpoint" + hot + "' id='matsEndpoint_")
                .append(matsEndpoint.getParentFactory().getFactoryConfig().getName())
                .append("_")
                .append(config.getEndpointId()).append("'>\n");
        out.append("<div class='mats_heading'>" + type + " <h3>" + config.getEndpointId() + "</h3>");
        out.append(" - " + formatIoClass("Incoming", config.getIncomingClass()));
        out.append(" - " + formatIoClass("Reply", config.getReplyClass()));
        out.append(" - " + formatIoClass("State", config.getStateClass()));
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config) + "\n");
        out.append("<br/>");
        out.append("<div class='mats_creation_info'>")
                .append(matsEndpoint.getEndpointConfig().getCreationInfo().replace(";", " - \n"))
                .append("</div>");
        out.append("</div>\n");

        out.append("<div class='mats_info'>\n");
        // out.append()("Worst stage duty cycle: ### <br/>\n");
        if (totExecSnapshot != null) {
            out.append("<b>Total endpoint time:</b> " + formatStats(totExecSnapshot) + "<br/>"
                    + "<span style='font-size: 75%'><i>(Note: From entry on Initial Stage to REPLY or NONE."
                    + " <b>Does not include queue time for Initial Stage!</b>)</i><br/></span>\n");
        }

        if (endpointStats != null) {
            NavigableMap<IncomingMessageRepresentation, StatsSnapshot> initiatorToTerminatorTimeNanos = endpointStats
                    .getInitiatorToTerminatorTimeNanos();

            if (!initiatorToTerminatorTimeNanos.isEmpty()) {
                out.append("<b>From Initiator to Terminator times:</b><br/>\n");
                boolean first = true;
                for (Entry<IncomingMessageRepresentation, StatsSnapshot> entry : initiatorToTerminatorTimeNanos
                        .entrySet()) {
                    IncomingMessageRepresentation msg = entry.getKey();
                    StatsSnapshot stats = entry.getValue();

                    if (first) {
                        first = false;
                    }
                    else {
                        out.append("<br/>\n");
                    }
                    out.append("&nbsp;&nbsp; From initiatorId " + formatIid(msg.getInitiatorId())
                            + " @ " + formatAppName(msg.getInitiatingAppName())
                            + " &mdash; (" + msg.getMessageType() + "s from: " + formatEpid(msg.getFromStageId())
                            + " @ " + formatAppName(msg.getFromAppName()) + ")<br/>\n");
                    out.append("&nbsp;&nbsp;&nbsp;&nbsp;" + formatStats(stats));
                    out.append("<br/>\n");
                }
            }
        }
        out.append("</div>\n");

        if (includeStages) {
            for (MatsStage<?, ?, ?> stage : matsEndpoint.getStages()) {

                // :: Time between stages
                if (localStats != null) {
                    Optional<StageStats> stageStats_ = localStats.getStageStats(stage);
                    if (stageStats_.isPresent()) {
                        StageStats stageStats = stageStats_.get();
                        Optional<StatsSnapshot> stats = stageStats.getBetweenStagesTimeNanos();
                        // ?: Do we have Between-stats? (Do not have for initial stage).
                        if (stats.isPresent()) {
                            out.append("<div class='mats_info'><b>Time between:</b> "
                                    + formatStats(stats.get()) + "</div>\n");
                        }
                    }
                }

                createStageReport(out, stage);
            }
        }
        out.append("</div>\n");
    }

    public void createStageReport(Appendable out, MatsStage<?, ?, ?> matsStage) throws IOException {
        // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
        LocalStatsMatsInterceptor localStats = null;
        if (_matsInterceptable != null) {
            localStats = _matsInterceptable
                    .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
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

        StageConfig<?, ?, ?> config = matsStage.getStageConfig();

        // If we have snapshot, and the 99.5% percentile is too high, add the "mats hot" class.
        String hot = (totExecSnapshot != null) && (totExecSnapshot.get999thPercentile() > 500_000_000d)
                ? " mats_hot"
                : "";
        out.append("<div class='mats_report mats_stage" + hot + "' id='matsStage_")
                .append(matsStage.getParentEndpoint().getParentFactory().getFactoryConfig().getName())
                .append("_")
                .append(config.getStageId()).append("'>\n");
        out.append("<div class='mats_heading'>Stage <h4>" + config.getStageId() + "</h4>\n");
        out.append(" - <b>Incoming:</b> <code>" + config.getIncomingClass().getSimpleName() + "</code>\n");
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config) + "\n");
        out.append(" - <b>Running stage processors:</b> " + config.getRunningStageProcessors() + "\n");
        out.append("<br/>");
        out.append("<div class='mats_creation_info'>")
                .append(matsStage.getStageConfig().getCreationInfo().replace(";", " - \n"))
                .append("</div>");
        out.append("</div>");

        out.append("<div class='mats_info'>\n");
        // out.append()("Duty cycle: ### <br/>\n");
        // out.append()("Oldest reported 'check-in' for stage procs: ### seconds ago."
        // + " <b>Stuck stage procs: ###</b><br/>\n");
        if ((stageStats != null) && (totExecSnapshot != null)) {

            out.append("<b>Queue time</b>: " + formatStats(stageStats.getSpentQueueTimeNanos())
                    + " (susceptible to time skews between nodes)<br/>\n");

            Map<IncomingMessageRepresentation, Long> incomingMessageCounts = stageStats.getIncomingMessageCounts();
            if (incomingMessageCounts.isEmpty()) {
                out.append("<b>NO incoming messages!</b>\n");
            }
            else if (incomingMessageCounts.size() == 1) {
                out.append("<b>Incoming messages:</b> ");
            }
            else {
                out.append("<b>Incoming messages (" + formatInt(totExecSnapshot.getNumObservations())
                        + "):</b><br/>\n");
            }
            for (Entry<IncomingMessageRepresentation, Long> entry : incomingMessageCounts.entrySet()) {
                IncomingMessageRepresentation msg = entry.getKey();
                out.append("&nbsp;&nbsp;" + formatInt(entry.getValue()) + " x " + msg.getMessageType()
                        + " from " + formatEpid(msg.getFromStageId()) + " <b>@</b> "
                        + formatAppName(msg.getFromAppName())
                        + formatInit(msg)
                        + "<br/>");
            }

            out.append("<b>Total stage time:</b> " + formatStats(totExecSnapshot) + "<br/>\n");

            // :: ProcessingResults
            SortedMap<ProcessResult, Long> processResultCounts = stageStats.getProcessResultCounts();
            if (processResultCounts.isEmpty()) {
                out.append("<b>NO processing results!</b><br/>\n");
            }
            else if (processResultCounts.size() == 1) {
                out.append("<b>Processing results:</b>\n");
            }
            else {
                out.append("<b>Processing results:</b><br/>\n");
            }
            for (Entry<ProcessResult, Long> entry : processResultCounts.entrySet()) {
                ProcessResult processResult = entry.getKey();
                out.append(formatInt(entry.getValue()) + " x " + processResult + "<br/>\n");
            }

            // :: Outgoing messages
            SortedMap<OutgoingMessageRepresentation, Long> outgoingMessageCounts = stageStats
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
                out.append("&nbsp;&nbsp;" + formatInt(entry.getValue())
                        + " x " + formatClass(msg.getMessageClass())
                        + " " + msg.getMessageType() + " to " + formatEpid(msg.getTo())
                        + formatInit(msg)
                        + "<br/>");
            }
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

    /**
     * <pre>
     * out.append("<tr><td colspan=100>")
     *         .append("<div style='background: rgba(0, 255, 0, 1)'>0ms</div>")
     *         .append("<div style='background: rgba(0, 255, 255, 1)'>100ms</div>")
     *         .append("<div style='background: rgba(0, 0, 255, 1)'>250ms</div>")
     *         .append("<div style='background: rgba(255, 0, 255, 1)'>500ms</div>")
     *         .append("<div style='background: rgba(255, 0, 0, 1)'>1000ms</div>")
     *         .append("</td></tr>");
     *
     * </pre>
     */

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
        return "<span class='mats_iid'>" + iid + "</span>";
    }

    String formatEpid(String epid) {
        return "<span class='mats_epid'>" + epid + "</span>";
    }

    String formatAppName(String appName) {
        return "<span class='mats_appname'>" + appName + "</span>";
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

    String formatConcurrency(MatsConfig config) {
        return config.getConcurrency() + (config.isConcurrencyDefault() ? " <i>(inherited)</i>"
                : " <i><b>(explicitly set)</b></i>");
    }

    String formatStats(StatsSnapshot snapshot) {
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
                + ", <b><span class='mats_max'>max:</sup></b>" + colorAndFormatNanos(snapshot.getMax())
                + " - <b><span class='mats_min'>min:</span></b>" + formatNanos(snapshot.getMin())
                + " &mdash; <i>number of samples: " + formatInt(snapshot.getSamples().length)
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
