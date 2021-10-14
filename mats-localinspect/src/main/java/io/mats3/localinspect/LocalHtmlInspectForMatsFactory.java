package io.mats3.localinspect;

import java.io.IOException;
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
 * Will produce an "embeddable" HTML interface - notice that there are CSS ({@link #getStyleSheet(Appendable)}), JavaScript
 * ({@link #getJavaScript(Appendable)}) and HTML ({@link #createFactoryReport(Appendable, boolean, boolean, boolean)
 * createFactoryReport(Appendable,..)}) to include. If the {@link LocalStatsMatsInterceptor} is installed on the
 * {@link MatsFactory} implementing {@link MatsInterceptable}, it will include pretty nice "local statistics" for all
 * initiators, endpoints and stages.
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
        // Regular fonts: Using the "Native font stack" of Bootstrap 5
        String font_regular = ""
                // Cross-platform generic font family (default user interface font)
                // + "system-ui,"
                // Safari for macOS and iOS (San Francisco)
                + " -apple-system,"
                // Chrome < 56 for macOS (San Francisco)
                + " BlinkMacSystemFont,"
                // Windows
                + " \"Segoe UI\","
                // Android
                + " Roboto,"
                // Basic web fallback
                + " \"Helvetica Neue\", Arial,"
                // Linux
                + " \"Noto Sans\","
                + " \"Liberation Sans\","
                // Sans serif fallback
                + " sans-serif,"
                // Emoji fonts
                + " \"Apple Color Emoji\", \"Segoe UI Emoji\", \"Segoe UI Symbol\", \"Noto Color Emoji\"";

        // Monospaced fonts: Using the "Native font stack" of Bootstrap 5
        String font_mono = "SFMono-Regular,Menlo,Monaco,Consolas,\"Liberation Mono\",\"Courier New\",monospace;";

        out.append(".mats_report {\n"
                + "  font-family: " + font_regular + ";\n"
                + "  font-weight: 400;\n"
                + "  font-size: 95%;\n"
                + "  line-height: 1.35;\n"
                + "  color: #212529;\n"
                + "}\n");

        out.append(".mats_report hr {\n"
                + "  border: 1px dashed #aaa;\n"
                + "  margin: 0.2em 0 0.8em 0;\n"
                + "  color: inherit;\n"
                + "  background-color: currentColor;\n"
                + "  opacity: 0.25;\n"
                + "}\n");

        // :: Fonts and headings
        out.append(".mats_report h2, .mats_report h3, .mats_report h4 {\n"
                // Have to re-set font here, otherwise Bootstrap 3 takes over.
                + "  font-family: " + font_regular + ";\n"
                + "  display: inline;\n"
                + "  line-height: 1.2;\n"
                + "}\n");
        out.append(".mats_report h2 {\n"
                + "  font-size: 1.5em;\n"
                + "  font-weight: 400;\n"
                + "}\n");
        out.append(".mats_report h3 {\n"
                + "  font-size: 1.4em;\n"
                + "  font-weight: 400;\n"
                + "}\n");
        out.append(".mats_report h4 {\n"
                + "  font-size: 1.3em;\n"
                + "  font-weight: 400;\n"
                + "}\n");
        out.append(".mats_heading {\n"
                + "  display: block;\n"
                + "  margin: 0em 0em 0.5em 0em;\n"
                + "}\n");
        out.append(".mats_report code {\n"
                + "  font-family: " + font_mono + ";\n"
                + "  font-size: .875em;\n"
                + "  color: #d63384;\n"
                + "  background-color: rgba(0, 0, 0, 0.07);\n"
                + "  padding: 2px 4px 1px 4px;\n"
                + "  border-radius: 3px;\n"
                + "}\n");
        // .. min and max (timings)
        out.append(".mats_min {\n"
                + "  top: +0.15em;\n"
                + "  position: relative;\n"
                + "  font-size: 0.75em;\n"
                + "}\n");
        out.append(".mats_max {\n"
                + "  top: -0.45em;\n"
                + "  position: relative;\n"
                + "  font-size: 0.75em;\n"
                + "}\n");

        // .. InitiatorIds and EndpointIds
        out.append(".mats_iid {\n"
                + "  font-family: " + font_mono + ";\n"
                + "  font-size: .875em;\n"
                + "  color: #d63384;\n"
                + "  background-color: rgba(0, 255, 0, 0.07);\n"
                + "  padding: 2px 4px 1px 4px;\n"
                + "  border-radius: 3px;\n"
                + "}\n");
        out.append(".mats_epid {\n"
                + "  font-family: " + font_mono + ";\n"
                + "  font-size: .875em;\n"
                + "  color: #d63384;\n"
                + "  background-color: rgba(0, 0, 255, 0.07);\n"
                + "  padding: 2px 4px 1px 4px;\n"
                + "  border-radius: 3px;\n"
                + "}\n");
        out.append(".mats_appname {\n"
                // + " color: #d63384;\n"
                + "  background-color: rgba(0, 255, 255, 0.07);\n"
                + "  padding: 2px 4px 1px 4px;\n"
                + "  border-radius: 3px;\n"
                + "}\n");

        // .. integers in timings (i.e. ms >= 500)
        // NOTE! The point of this class is NOT denote "high timings", but to denote that there are no
        // decimals, to visually make it easier to compare a number '1 235' with '1.235'.
        out.append(".mats_integer {\n"
                + "  color: #b02a37;\n"
                + "}\n");

        // :: The different parts of the report
        out.append(".mats_info {\n"
                + "  margin: 0em 0em 0em 0.5em;\n"
                + "}\n");

        out.append(".mats_factory {\n"
                + "  background: #f0f0f0;\n"
                + "}\n");
        out.append(".mats_initiator {\n"
                + "  background: #e0f0e0;\n"
                + "}\n");
        out.append(".mats_endpoint {\n"
                + "  background: #e0e0f0;\n"
                + "}\n");
        out.append(".mats_stage {\n"
                + "  background: #f0f0f0;\n"
                + "}\n");
        // Boxes:
        out.append(".mats_factory, .mats_initiator, .mats_endpoint, .mats_stage {\n"
                + "  border-radius: 3px;\n"
                + "  box-shadow: 2px 2px 2px 0px rgba(0,0,0,0.37);\n"
                + "  border: thin solid #a0a0a0;\n"
                + "  margin: 0.5em 0.5em 0.7em 0.5em;\n"
                + "  padding: 0.1em 0.5em 0.5em 0.5em;\n"
                + "}\n");

        out.append(".mats_initiator, .mats_endpoint {\n"
                + "  margin: 0.5em 0.5em 2em 0.5em;\n"
                + "}\n");

        out.append(".mats_hot {\n"
                + "  box-shadow: #FFF 0 -1px 4px, #ff0 0 -2px 10px, #ff8000 0 -10px 20px, red 0 -18px 40px, 5px 5px 15px 5px rgba(0,0,0,0);\n"
                + "  border: 0.2em solid red;\n"
                + "  background: #ECEFCF;\n"
                + "}\n");
    }

    /**
     * Note: The return from this method is static, and should only be included once per HTML page, no matter how many
     * MatsFactories you display.
     */
    public void getJavaScript(Appendable out) throws IOException {
        out.append("");
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
        out.append("<div class=\"mats_report mats_factory\">\n");
        out.append("<div class=\"mats_heading\">MatsFactory <h2>" + config.getName() + "</h2>\n");
        out.append(" - <b>Known number of CPUs:</b> " + config.getNumberOfCpus());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config));
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append("</div>\n");
        out.append("<hr />\n");

        out.append("<div class=\"mats_info\">");
        out.append("config: <b>Name:</b> " + config.getName());
        out.append(" - <b>App:</b> " + config.getAppName() + " v." + config.getAppVersion());
        out.append(" - <b>Nodename:</b> " + config.getNodename());
        out.append(" - <b>Destination prefix:</b> \"" + config.getMatsDestinationPrefix() + "\"");
        out.append(" - <b>Trace key:</b> \"" + config.getMatsTraceKey() + "\"<br />\n");
        out.append((localStats != null
                ? "<b>Local Statistics collector present in MatsFactory!</b>"
                        + " (<code>" + LocalStatsMatsInterceptor.class.getSimpleName() + "</code> installed)"
                : "<b>Missing Local Statistics collector in MatsFactory - <code>"
                        + LocalStatsMatsInterceptor.class.getSimpleName()
                        + "</code> is not installed!</b>") + "</b><br />");

        if (_matsInterceptable != null) {
            out.append("<b>Installed InitiationInterceptors:</b><br />\n");
            List<MatsInitiateInterceptor> initiationInterceptors = _matsInterceptable.getInitiationInterceptors();
            for (MatsInitiateInterceptor initiationInterceptor : initiationInterceptors) {
                out.append("&nbsp;&nbsp;<code>" + initiationInterceptor.getClass().getName() + "</code>: "
                        + initiationInterceptor + "<br />\n");
            }
            out.append("<b>Installed StageInterceptors:</b><br />\n");
            List<MatsStageInterceptor> stageInterceptors = _matsInterceptable.getStageInterceptors();
            for (MatsStageInterceptor stageInterceptor : stageInterceptors) {
                out.append("&nbsp;&nbsp;<code>" + stageInterceptor.getClass().getName() + "</code>: "
                        + stageInterceptor + "<br />\n");
            }
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

    public void createInitiatorReport(Appendable out, MatsInitiator matsInitiator)
            throws IOException {
        // We do this dynamically, so as to handle late registration of the LocalStatsMatsInterceptor.
        LocalStatsMatsInterceptor localStats = null;
        if (_matsInterceptable != null) {
            localStats = _matsInterceptable
                    .getInitiationInterceptor(LocalStatsMatsInterceptor.class).orElse(null);
        }

        out.append("<div class=\"mats_report mats_initiator\">\n");
        out.append("<div class=\"mats_heading\">Initiator <h3>" + matsInitiator.getName() + "</h3>\n");
        out.append("</div>\n");
        out.append("<hr />\n");
        out.append("<div class=\"mats_info\">\n");
        if (localStats != null) {
            Optional<InitiatorStats> initiatorStats_ = localStats.getInitiatorStats(matsInitiator);
            if (initiatorStats_.isPresent()) {
                InitiatorStats initiatorStats = initiatorStats_.get();
                StatsSnapshot stats = initiatorStats.getTotalExecutionTimeNanos();

                out.append("<b>Total initiation time:</b> " + formatStats(stats) + "<br />\n");
                SortedMap<OutgoingMessageRepresentation, Long> outgoingMessageCounts = initiatorStats
                        .getOutgoingMessageCounts();
                long sumOutMsgs = outgoingMessageCounts.values().stream().mapToLong(Long::longValue).sum();
                if (outgoingMessageCounts.isEmpty()) {
                    out.append("<b>NO outgoing messages!</b><br />\n");
                }
                else if (outgoingMessageCounts.size() == 1) {
                    out.append("<b>Outgoing messages:</b> \n");

                }
                else {
                    out.append("<b>Outgoing messages (" + formatInt(sumOutMsgs) + "):</b><br />\n");
                }

                for (Entry<OutgoingMessageRepresentation, Long> entry : outgoingMessageCounts.entrySet()) {
                    OutgoingMessageRepresentation msg = entry.getKey();
                    out.append("&nbsp;&nbsp;" + formatInt(entry.getValue()) + " x " + formatClass(msg.getMessageClass())
                            + " " + msg.getMessageType() + " from initiatorId " + formatIid(msg.getInitiatorId())
                            + " to " + formatEpid(msg.getTo()) + "<br />");
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

        // Deduce type
        String type = config.getReplyClass() == void.class ? "Terminator" : "Endpoint";
        if ((matsEndpoint.getStages().size() == 1) && (config.getReplyClass() != void.class)) {
            type = "Single " + type;
        }
        if (matsEndpoint.getStages().size() > 1) {
            type = "MultiStage " + type;
        }
        if (matsEndpoint.getEndpointConfig().isSubscription()) {
            type = "Subscription " + type;
        }

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

        out.append("<div class=\"mats_report mats_endpoint" + hot + "\">\n");
        out.append("<div class=\"mats_heading\">" + type + " <h3>" + config.getEndpointId() + "</h3>");
        out.append(" - " + formatIoClass("Incoming", config.getIncomingClass()));
        out.append(" - " + formatIoClass("Reply", config.getReplyClass()));
        out.append(" - " + formatIoClass("State", config.getStateClass()));
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config) + "\n");
        out.append("</div>\n");
        out.append("<hr />\n");

        out.append("<div class=\"mats_info\">\n");
        // out.append()("Creation debug info: ### <br />\n");
        // out.append()("Worst stage duty cycle: ### <br />\n");
        if (totExecSnapshot != null) {
            out.append("<b>Total endpoint time:</b> " + formatStats(totExecSnapshot) + "<br /><br />\n");
        }

        if (endpointStats != null) {
            NavigableMap<IncomingMessageRepresentation, StatsSnapshot> initiatorToTerminatorTimeNanos = endpointStats
                    .getInitiatorToTerminatorTimeNanos();

            if (!initiatorToTerminatorTimeNanos.isEmpty()) {
                out.append("<b>From Initiator to Terminator times:</b><br />\n");
                boolean first = true;
                for (Entry<IncomingMessageRepresentation, StatsSnapshot> entry : initiatorToTerminatorTimeNanos
                        .entrySet()) {
                    IncomingMessageRepresentation msg = entry.getKey();
                    StatsSnapshot stats = entry.getValue();

                    if (first) {
                        first = false;
                    }
                    else {
                        out.append("<br />\n");
                    }
                    out.append("&nbsp;&nbsp; From initiatorId " + formatIid(msg.getInitiatorId())
                            + " @ " + formatAppName(msg.getInitiatingAppName())
                            + " &mdash; (" + msg.getMessageType() + "s from: " + formatEpid(msg.getFromStageId())
                            + " @ " + formatAppName(msg.getFromAppName()) + ")<br />\n");
                    out.append("&nbsp;&nbsp;&nbsp;&nbsp;" + formatStats(stats));
                    out.append("<br />\n");
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
                            out.append("<div class=\"mats_info\"><b>Time between:</b> "
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
        out.append("<div class=\"mats_report mats_stage" + hot + "\">\n");
        out.append("<div class=\"mats_heading\">Stage <h4>" + config.getStageId() + "</h4>\n");
        out.append(" - <b>Incoming:</b> <code>" + config.getIncomingClass().getSimpleName() + "</code>\n");
        out.append(" - <b>Running:</b> " + config.isRunning());
        out.append(" - <b>Concurrency:</b> " + formatConcurrency(config) + "\n");
        out.append(" - <b>Running stage processors:</b> " + config.getRunningStageProcessors() + "\n");
        out.append("</div>\n");
        out.append("<hr />\n");

        out.append("<div class=\"mats_info\">\n");
        // out.append()("Creation debug info: ### <br />\n");
        // out.append()("Duty cycle: ### <br />\n");
        // out.append()("Oldest reported \"check-in\" for stage procs: ### seconds ago."
        // + " <b>Stuck stage procs: ###</b><br />\n");
        // out.append()("<b>For terminators: - total times per outgoing initiation to difference services"
        // + " (use extra-state), should make things better for MatsFuturizer</b><br />\n");
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
                        + "):</b><br />\n");
            }
            for (Entry<IncomingMessageRepresentation, Long> entry : incomingMessageCounts.entrySet()) {
                IncomingMessageRepresentation msg = entry.getKey();
                out.append("&nbsp;&nbsp;" + formatInt(entry.getValue()) + " x " + msg.getMessageType()
                        + " from " + formatEpid(msg.getFromStageId()) + " <b>@</b> "
                        + formatAppName(msg.getFromAppName())
                        + formatInit(msg)
                        + "<br />");
            }

            out.append("<b>Total stage time:</b> " + formatStats(totExecSnapshot) + "<br />\n");

            // :: ProcessingResults
            SortedMap<ProcessResult, Long> processResultCounts = stageStats.getProcessResultCounts();
            if (processResultCounts.isEmpty()) {
                out.append("<b>NO processing results!</b><br />\n");
            }
            else if (processResultCounts.size() == 1) {
                ProcessResult processResult = processResultCounts.firstKey();
                Long count = processResultCounts.get(processResult);
                out.append("<b>Processing results:</b>\n");
            }
            else {
                out.append("<b>Processing results:</b><br />\n");
            }
            for (Entry<ProcessResult, Long> entry : processResultCounts.entrySet()) {
                ProcessResult processResult = entry.getKey();
                out.append(formatInt(entry.getValue()) + " x " + processResult + "<br />\n");
            }

            // :: Outgoing messages
            SortedMap<OutgoingMessageRepresentation, Long> outgoingMessageCounts = stageStats
                    .getOutgoingMessageCounts();
            long sumOutMsgs = outgoingMessageCounts.values().stream().mapToLong(Long::longValue).sum();
            if (outgoingMessageCounts.isEmpty()) {
                out.append("<b>NO outgoing messages!</b><br />\n");
            }
            else if (outgoingMessageCounts.size() == 1) {
                out.append("<b>Outgoing messages:</b> \n");

            }
            else {
                out.append("<b>Outgoing messages (" + formatInt(sumOutMsgs) + "):</b><br />\n");
            }

            for (Entry<OutgoingMessageRepresentation, Long> entry : outgoingMessageCounts.entrySet()) {
                OutgoingMessageRepresentation msg = entry.getKey();
                out.append("&nbsp;&nbsp;" + formatInt(entry.getValue())
                        + " x " + formatClass(msg.getMessageClass())
                        + " " + msg.getMessageType() + " to " + formatEpid(msg.getTo())
                        + formatInit(msg)
                        + "<br />");
            }
        }
        out.append("</div>\n");
        out.append("</div>\n");
    }

    // Could be static, but aren't, in case anyone wants to override them.
    // NOTE: These are NOT part of any "stable API" promises!

    String formatIid(String iid) {
        return "<span class=\"mats_iid\">" + iid + "</span>";
    }

    String formatEpid(String epid) {
        return "<span class=\"mats_epid\">" + epid + "</span>";
    }

    String formatAppName(String appName) {
        return "<span class=\"mats_appname\">" + appName + "</span>";
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
        return "<b>x\u0304:</b>" + formatNanos(avg)
                + " <b><i>s</i>:</b>" + formatNanos(sd)
                + " <b><i>2s</i>:</b>[" + formatNanos(avg - 2 * sd) + ", " + formatNanos(avg + 2 * sd) + "]"
                + " - <b><span class=\"mats_min\">min:</span></b>" + formatNanos(snapshot.getMin())
                + " <b><span class=\"mats_max\">max:</sup></b>" + formatNanos(snapshot.getMax())
                + " &mdash; percentiles <b>50%:</b>" + formatNanos(snapshot.getMedian())
                + ", <b>75%:</b>" + formatNanos(snapshot.get75thPercentile())
                + ", <b>95%:</b>" + formatNanos(snapshot.get95thPercentile())
                + ", <b>98%:</b>" + formatNanos(snapshot.get98thPercentile())
                + ", <b>99%:</b>" + formatNanos(snapshot.get99thPercentile())
                + ", <b>99.9%:</b>" + formatNanos(snapshot.get999thPercentile())
                + " &mdash; <i>number of samples: " + formatInt(snapshot.getSamples().length)
                + ", out of observations:" + formatInt(snapshot.getNumObservations()) + "</i>";
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

        // NOTE! The point of this class is NOT denote "high timings", but to denote that there are no
        // decimals, to visually make it easier to compare a number '1 235' with '1.235'.
        NF_0_DECIMALS = new DecimalFormat("<span class=\"mats_integer\">#,##0</span>");
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
