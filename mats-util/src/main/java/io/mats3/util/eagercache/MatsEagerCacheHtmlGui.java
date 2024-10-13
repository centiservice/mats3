package io.mats3.util.eagercache;

import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatHtmlBytes;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatHtmlTimestamp;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatMillis;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.mats3.util.eagercache.MatsEagerCacheClient.CacheClientInformation;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheServerInformation;
import io.mats3.util.eagercache.MatsEagerCacheServer.ExceptionEntry;
import io.mats3.util.eagercache.MatsEagerCacheServer.LogEntry;

/**
 * Embeddable HTML GUI for the {@link MatsEagerCacheClient} and {@link MatsEagerCacheServer}.
 */
public interface MatsEagerCacheHtmlGui {

    static MatsEagerCacheHtmlGui create(MatsEagerCacheServer client) {
        return new MatsEagerCacheServerHtmlGui(client);
    }

    static MatsEagerCacheHtmlGui create(MatsEagerCacheClient<?> server) {
        return new MatsEagerCacheClientHtmlGui(server);
    }

    /**
     * Note: The output from this method is static, it can be written directly to the HTML page in a script-tag, or
     * included as a separate file (with hard caching). It shall only be included once even if there are several GUIs on
     * the same page.
     */
    void outputStyleSheet(Appendable out) throws IOException;

    /**
     * Note: The output from this method is static, it can be written directly to the HTML page in a style-tag, or
     * included as a separate file (with hard caching). It shall only be included once even if there are several GUIs on
     * the same page.
     */
    void outputJavaScript(Appendable out) throws IOException;

    /**
     * The embeddable HTML GUI - map this to GET, content type is <code>"text/html; charset=utf-8"</code>. This might
     * via the browser call back to {@link #json(Appendable, Map, String)} - which you also must mount at (typically)
     * the same URL (PUT, POST and DELETEs go there, GETs go here).
     */
    void html(Appendable out, Map<String, String[]> requestParameters) throws IOException;

    /**
     * The HTML GUI will invoke JSON-over-HTTP to the same URL it is located at - map this to PUT, POST and DELETE,
     * returned content type shall be <code>"application/json; charset=utf-8"</code>.
     * <p>
     * NOTICE: If you have several GUIs on the same path, you must route them to the correct instance. This is done by
     * the URL parameters 'dataname' and 'nodename' which will always be supplied by the GUI when doing operations.
     * <p>
     * NOTICE: If you need to change the JSON Path, i.e. the path which this GUI employs to do "active" operations, you
     * can do so by setting the JS global variable "matsec_json_path" when outputting the HTML, overriding the default
     * which is to use the current URL path (i.e. the same as the GUI is served on). They may be on the same path since
     * the HTML is served using GET, while the JSON uses PUT, POST and DELETE with header "Content-Type:
     * application/json".
     */
    void json(Appendable out, Map<String, String[]> requestParameters, String requestBody) throws IOException;

    /**
     * Implementation of {@link MatsEagerCacheHtmlGui} for {@link MatsEagerCacheClient} - use the
     * {@link MatsEagerCacheHtmlGui#create(MatsEagerCacheClient)} factory method to get an instance.
     */
    class MatsEagerCacheClientHtmlGui implements MatsEagerCacheHtmlGui {
        private final CacheClientInformation _info;

        private MatsEagerCacheClientHtmlGui(MatsEagerCacheClient<?> client) {
            _info = client.getCacheClientInformation();
        }

        @Override
        public void outputStyleSheet(Appendable out) throws IOException {
            out.append("/* No CSS for MatsEagerCacheClient */");
        }

        @Override
        public void outputJavaScript(Appendable out) throws IOException {
            out.append("/* No JavaScript for MatsEagerCacheClient */");
        }

        @Override
        public void html(Appendable out, Map<String, String[]> requestParameters) throws IOException {
            out.append("<h2>MatsEagerCacheClient '").append(_info.getDataName()).append("' @ '")
                    .append(_info.getNodename()).append("'</h2>\n");
            out.append("DataName: ").append("<b>").append(_info.getDataName()).append("</b><br>\n");
            out.append("Nodename: ").append("<b>").append(_info.getNodename()).append("</b><br>\n");
            out.append("LifeCycle: ").append("<b>").append(_info.getCacheClientLifeCycle().toString()).append(
                    "</b><br>\n");
            out.append("CacheStartedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getCacheStartedTimestamp())).append("<br>\n");
            out.append("BroadcastTopic: ").append("<b>").append(_info.getBroadcastTopic()).append("</b><br>\n");
            out.append("InitialPopulationRequestSentTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getInitialPopulationRequestSentTimestamp())).append("<br>\n");
            boolean initial = _info.isInitialPopulationDone();
            long millisBetween = _info.getInitialPopulationTimestamp() -
                    _info.getInitialPopulationRequestSentTimestamp();
            out.append("InitialPopulationDone: ").append(initial
                    ? "<b>Done</b> @ " + _formatHtmlTimestamp(_info.getInitialPopulationTimestamp())
                            + " - " + _formatMillis(millisBetween) + " after request"
                    : "<i>Waiting</i>")
                    .append("</b><br>\n");
            out.append("<br>\n");

            out.append("LastAnyUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getAnyUpdateReceivedTimestamp())).append("<br>\n");
            out.append("LastFullUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getLastFullUpdateReceivedTimestamp())).append("<br>\n");
            out.append("LastPartialUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getLastPartialUpdateReceivedTimestamp())).append("<br>\n");
            out.append("<br>\n");

            if (initial) {
                out.append("LastUpdateType: ").append("<b>")
                        .append(_info.isLastUpdateFull() ? "Full" : "Partial").append("</b><br>\n");
                out.append("LastUpdateMode: ").append("<b>")
                        .append(_info.isLastUpdateLarge() ? "LARGE" : "Small").append("</b><br>\n");
                out.append("LastUpdateDurationMillis: <b>")
                        .append(_formatMillis(_info.getLastUpdateDurationMillis())).append("</b><br>\n");
                String meta = _info.getLastUpdateMetadata();
                out.append("LastUpdateMetadata: ").append(meta != null ? "<b>" + meta + "</b>" : "<i>none</i>")
                        .append("<br>\n");
                out.append("LastUpdateCompressedSize: ")
                        .append(_formatHtmlBytes(_info.getLastUpdateCompressedSize())).append("<br>\n");
                out.append("LastUpdateDecompressedSize: ")
                        .append(_formatHtmlBytes(_info.getLastUpdateDecompressedSize())).append("<br>\n");
                out.append("LastUpdateDataCount: ").append("<b>")
                        .append(Integer.toString(_info.getLastUpdateDataCount())).append("</b><br>\n");
                out.append("<br>\n");
            }
            else {
                out.append("<i>Initial population not done yet.</i><br><br>\n");
            }

            out.append("NumberOfFullUpdatesReceived: ").append("<b>")
                    .append(Integer.toString(_info.getNumberOfFullUpdatesReceived())).append("</b><br>\n");
            out.append("NumberOfPartialUpdatesReceived: ").append("<b>")
                    .append(Integer.toString(_info.getNumberOfPartialUpdatesReceived())).append("</b><br>\n");
            out.append("NumberOfAccesses: ").append("<b>")
                    .append(Long.toString(_info.getNumberOfAccesses())).append("</b><br>\n");

            logsAndExceptions(out, _info.getExceptionEntries(), _info.getLogEntries());
        }

        static void logsAndExceptions(Appendable out, List<ExceptionEntry> exceptionEntries, List<LogEntry> logEntries)
                throws IOException {
            out.append("<br>\n");

            // :: Print out exception entries, or "No exceptions" if none.
            if (exceptionEntries.isEmpty()) {
                out.append("<h3>Exception entries</h3>\n");
                out.append("<b><i>No exceptions!</i></b><br>\n");
            }
            else {
                out.append("<h3>Exception entries</h3>\n");
                for (ExceptionEntry entry : exceptionEntries) {
                    out.append(entry.toHtmlString()).append("<br>\n");
                }
            }

            // :: Print out log entries
            out.append("<br>\n");
            out.append("<h3>Log entries</h3>\n");
            for (LogEntry entry : logEntries) {
                out.append(entry.toHtmlString()).append("<br>\n");
            }
        }

        @Override
        public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody)
                throws IOException {
            out.append("/* No JSON for MatsEagerCacheClient */");
        }
    }

    /**
     * Implementation of {@link MatsEagerCacheHtmlGui} for {@link MatsEagerCacheServer} - use the
     * {@link MatsEagerCacheHtmlGui#create(MatsEagerCacheServer)} factory method to get an instance.
     */
    class MatsEagerCacheServerHtmlGui implements MatsEagerCacheHtmlGui {
        private final CacheServerInformation _info;

        private MatsEagerCacheServerHtmlGui(MatsEagerCacheServer server) {
            _info = server.getCacheServerInformation();
        }

        @Override
        public void outputStyleSheet(Appendable out) throws IOException {
            out.append("/* No CSS for MatsEagerCacheServer */");
        }

        @Override
        public void outputJavaScript(Appendable out) throws IOException {
            out.append("/* No JavaScript for MatsEagerCacheServer */");
        }

        @Override
        public void html(Appendable out, Map<String, String[]> requestParameters) throws IOException {
            out.append("<h2>MatsEagerCacheServer '" + _info.getDataName() + "' @ '" + _info.getNodename() + "'</h2>");
            out.append("DataName: ").append("<b>").append(_info.getDataName()).append("</b><br>\n");
            out.append("Nodename: ").append("<b>").append(_info.getNodename()).append("</b><br>\n");
            out.append("LifeCycle: ").append("<b>").append(_info.getCacheServerLifeCycle().toString()).append(
                    "</b><br>\n");
            out.append("CacheStartedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getCacheStartedTimestamp())).append("<br>\n");
            out.append("CacheRequestQueue: ").append("<b>").append(_info.getCacheRequestQueue()).append("</b><br>\n");
            out.append("BroadcastTopic: ").append("<b>").append(_info.getBroadcastTopic()).append("</b><br>\n");
            out.append("PeriodicFullUpdateIntervalMinutes: ").append("<b>")
                    .append(Double.toString(_info.getPeriodicFullUpdateIntervalMinutes())).append("</b><br>\n");
            out.append("<br>\n");

            out.append("LastFullUpdateRequestReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getLastFullUpdateRequestReceivedTimestamp())).append("<br>\n");
            out.append("LastFullUpdateProductionStartedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getLastFullUpdateProductionStartedTimestamp()))
                    .append(" - took <b>").append(_formatMillis(_info.getLastFullUpdateProduceTotalMillis()))
                    .append("</b><br>\n");
            out.append("LastFullUpdateSentTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getLastFullUpdateSentTimestamp())).append("<br>\n");
            out.append("LastFullUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getLastFullUpdateReceivedTimestamp())).append("<br>\n");
            out.append("LastPartialUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getLastPartialUpdateReceivedTimestamp())).append("<br>\n");
            out.append("LastAnyUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(_info.getLastAnyUpdateReceivedTimestamp())).append("<br>\n");
            out.append("<br>\n");

            long lastUpdateSent = _info.getLastUpdateSentTimestamp();
            if (lastUpdateSent > 0) {
                out.append("LastUpdateSentTimestamp: ").append("<b>")
                        .append(_formatHtmlTimestamp(lastUpdateSent)).append("</b><br>\n");
                out.append("LastUpdateType: ").append("<b>")
                        .append(_info.isLastUpdateFull() ? "Full" : "Partial").append("</b><br>\n");
                out.append("LastUpdateProductionTotalMillis: <b>")
                        .append(_formatMillis(_info.getLastUpdateProduceTotalMillis()))
                        .append("</b> - source: <b>")
                        .append(_formatMillis(_info.getLastUpdateSourceMillis()))
                        .append("</b>, serialize: <b>")
                        .append(_formatMillis(_info.getLastUpdateSerializeMillis()))
                        .append("</b>, compress: <b>")
                        .append(_formatMillis(_info.getLastUpdateCompressMillis()))
                        .append("</b><br>\n");
                String meta = _info.getLastUpdateMetadata();
                out.append("LastUpdateMetadata: ").append(meta != null ? "<b>" + meta + "</b>" : "<i>none</i>")
                        .append("<br>\n");
                out.append("LastUpdateCount: ").append("<b>")
                        .append(Integer.toString(_info.getLastUpdateCount())).append("</b><br>\n");
                out.append("LastUpdateUncompressedSize: ")
                        .append(_formatHtmlBytes(_info.getLastUpdateUncompressedSize())).append("<br>\n");
                out.append("LastUpdateCompressedSize: ")
                        .append(_formatHtmlBytes(_info.getLastUpdateCompressedSize())).append("<br>\n");
                out.append("<br>\n");
            }
            else {
                out.append("<i>No update sent yet.</i><br><br>\n");
            }

            out.append("FullUpdates: <b>").append(Integer.toString(_info.getNumberOfFullUpdatesSent()));
            out.append("</b> sent, out of <b>").append(Integer.toString(_info.getNumberOfFullUpdatesReceived()))
                    .append("</b> received.<br>\n");
            out.append("PartialUpdates: <b>").append(Integer.toString(_info.getNumberOfPartialUpdatesSent()));
            out.append("</b> sent, out of <b>").append(Integer.toString(_info.getNumberOfPartialUpdatesReceived()))
                    .append("</b> received.<br>\n");

            MatsEagerCacheClientHtmlGui.logsAndExceptions(out, _info.getExceptionEntries(), _info.getLogEntries());
        }

        @Override
        public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody)
                throws IOException {
            out.append("/* No JSON for MatsEagerCacheServer */");
        }
    }
}
