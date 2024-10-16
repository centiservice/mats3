package io.mats3.util.eagercache;

import static io.mats3.util.eagercache.MatsEagerCacheHtmlGui.MatsEagerCacheClientHtmlGui.includeFile;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatHtmlBytes;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatHtmlTimestamp;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatMillis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.mats3.util.eagercache.MatsEagerCacheClient.CacheClientInformation;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientMock;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheServerInformation;
import io.mats3.util.eagercache.MatsEagerCacheServer.ExceptionEntry;
import io.mats3.util.eagercache.MatsEagerCacheServer.LogEntry;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl.CacheMonitor;

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
     * Note: The output from this method is static - and common between Client and Server - and it can be written
     * directly to the HTML page in a script-tag, or included as a separate file (with hard caching). It shall only be
     * included once even if there are several GUIs on the same page.
     */
    static void styleSheet(Appendable out) throws IOException {
        includeFile(out, "matseagercache.css");
    }

    /**
     * Note: The output from this method is static - and common between Client and Server - and it can be written
     * directly to the HTML page in a style-tag, or included as a separate file (with hard caching). It shall only be
     * included once even if there are several GUIs on the same page.
     */
    static void javaScript(Appendable out) throws IOException {
        includeFile(out, "matseagercache.js");
    }

    /**
     * The embeddable HTML GUI - map this to GET, content type is <code>"text/html; charset=utf-8"</code>. This might
     * via the browser call back to {@link #json(Appendable, Map, String, AccessControl)} - which you also must mount at
     * (typically) the same URL (PUT, POST and DELETEs go there, GETs go here).
     */
    void html(Appendable out, Map<String, String[]> requestParameters, AccessControl ac) throws IOException;

    /**
     * The HTML GUI will invoke JSON-over-HTTP to the same URL it is located at - map this to PUT, POST and DELETE,
     * returned content type shall be <code>"application/json; charset=utf-8"</code>.
     * <p>
     * NOTICE: If you have several GUIs on the same path, you must route them to the correct instance. This is done by
     * the URL parameters 'routingId' which will always be supplied by the GUI when doing operations - compare with the
     * {@link #getRoutingId()} of the different instances, and route to the one matching.
     * <p>
     * NOTICE: If you want to change the JSON Path, i.e. the path which this GUI employs to do "active" operations, you
     * can do so by prepending a little JavaScript section which sets the global variable "matsec_json_path" to the HTML
     * output, thus overriding the default which is to use the current URL path (i.e. the same as the GUI is served on).
     * They may be on the same path since the HTML is served using GET, while the JSON uses PUT, POST and DELETE with
     * header "Content-Type: application/json".
     */
    void json(Appendable out, Map<String, String[]> requestParameters, String requestBody, AccessControl ac)
            throws IOException;

    /**
     * @return the value which shall be compared when incoming {@link #json(Appendable, Map, String, AccessControl)
     *         JSON-over-HTTP} requests must be routed to the correct instance.
     */
    String getRoutingId();

    /**
     * Interface which an instance of must be supplied to the {@link #html(Appendable, Map, AccessControl)} and
     * {@link #json(Appendable, Map, String, AccessControl)} methods, which allows the GUI log which user does some
     * operation, and to check if the user is allowed to do the operation.
     */
    interface AccessControl {
        default String username() {
            return "{unknown}";
        }

        default boolean clearExceptions() {
            return false;
        }
    }

    /**
     * Quick way to get an {@link AccessControl} instance which allow all operations.
     *
     * @param username
     *            the username to use in the {@link AccessControl#username()} method.
     * @return an {@link AccessControl} which allows all operations.
     */
    static AccessControl getAccessControlAllowAll(String username) {
        return new AccessControl() {
            @Override
            public String username() {
                return username;
            }

            @Override
            public boolean clearExceptions() {
                return true;
            }
        };
    }

    class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    /**
     * Implementation of {@link MatsEagerCacheHtmlGui} for {@link MatsEagerCacheClient} - use the
     * {@link MatsEagerCacheHtmlGui#create(MatsEagerCacheClient)} factory method to get an instance.
     */
    class MatsEagerCacheClientHtmlGui implements MatsEagerCacheHtmlGui {
        private final MatsEagerCacheClient<?> _client;
        private final String _routingId;

        private MatsEagerCacheClientHtmlGui(MatsEagerCacheClient<?> client) {
            _client = client;
            _routingId = "C-" + (client.getCacheClientInformation().getDataName()
                    + "-" + client.getCacheClientInformation().getNodename()).replaceAll("[^a-zA-Z0-9_]", "-");
        }

        static void includeFile(Appendable out, String file) throws IOException {
            String filename = MatsEagerCacheHtmlGui.class.getPackage().getName().replace('.', '/') + '/' + file;
            InputStream is = MatsEagerCacheHtmlGui.class.getClassLoader().getResourceAsStream(filename);
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
        public void html(Appendable out, Map<String, String[]> requestParameters, AccessControl ac) throws IOException {
            CacheClientInformation info = _client.getCacheClientInformation();
            out.append("<div class='matsec-container'>\n");
            out.append("<h1>MatsEagerCacheClient ")
                    .append(_client instanceof MatsEagerCacheClientMock ? "<b>MOCK</b>" : "")
                    .append(" '").append(info.getDataName()).append("' @ '")
                    .append(info.getNodename()).append("'</h1>\n");
            if (_client instanceof MatsEagerCacheClientMock) {
                out.append("<i><b>NOTICE! This is a MOCK of the MatsEagerCacheClient</b>, and some of the information"
                        + " below is mocked! (Notably all the LastUpdate* data)</i><br><br>\n");
            }

            out.append("<div class='matsec-column-container'>\n");

            out.append("<div class='matsec-column'>\n");
            out.append("DataName: ").append("<b>").append(info.getDataName()).append("</b><br>\n");
            out.append("Nodename: ").append("<b>").append(info.getNodename()).append("</b><br>\n");
            out.append("LifeCycle: ").append("<b>").append(info.getCacheClientLifeCycle().toString()).append(
                    "</b><br>\n");
            out.append("CacheStartedTimestamp: ")
                    .append(_formatHtmlTimestamp(info.getCacheStartedTimestamp())).append("<br>\n");
            out.append("BroadcastTopic: ").append("<b>").append(info.getBroadcastTopic()).append("</b><br>\n");
            out.append("InitialPopulationRequestSentTimestamp: ")
                    .append(_formatHtmlTimestamp(info.getInitialPopulationRequestSentTimestamp())).append("<br>\n");

            boolean initialDone = info.isInitialPopulationDone();
            long millisBetween = info.getInitialPopulationTimestamp() - info.getInitialPopulationRequestSentTimestamp();
            out.append("InitialPopulationDone: ")
                    .append(initialDone
                            ? "<b>Done</b> @ " + _formatHtmlTimestamp(info.getInitialPopulationTimestamp())
                                    + " - " + _formatMillis(millisBetween) + " after request"
                            : "<i>Waiting</i>")
                    .append("</b><br>\n");
            out.append("<br>\n");

            out.append("LastAnyUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(info.getAnyUpdateReceivedTimestamp())).append("<br>\n");
            out.append("LastFullUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(info.getLastFullUpdateReceivedTimestamp())).append("<br>\n");
            out.append("LastPartialUpdateReceivedTimestamp: ")
                    .append(_formatHtmlTimestamp(info.getLastPartialUpdateReceivedTimestamp())).append("<br>\n");
            out.append("</div>\n");

            out.append("<div class='matsec-column'>\n");
            // ?: Is the initial population done?
            if (!initialDone) {
                // -> No, initial population not done yet.
                out.append("<i>Initial population not done yet.</i><br><br>\n");
            }
            else {
                // -> Yes, initial population done - show info.
                out.append("LastUpdateType: ").append("<b>")
                        .append(info.isLastUpdateFull() ? "Full" : "Partial").append("</b><br>\n");
                out.append("LastUpdateMode: ").append("<b>")
                        .append(info.isLastUpdateLarge() ? "LARGE" : "Small").append("</b><br>\n");
                out.append("LastUpdateDurationMillis: <b>")
                        .append(_formatMillis(info.getLastUpdateDurationMillis())).append("</b><br>\n");
                String meta = info.getLastUpdateMetadata();
                out.append("LastUpdateMetadata: ").append(meta != null ? "<b>" + meta + "</b>" : "<i>none</i>")
                        .append("<br>\n");
                out.append("LastUpdateCompressedSize: ")
                        .append(_formatHtmlBytes(info.getLastUpdateCompressedSize())).append("<br>\n");
                out.append("LastUpdateDecompressedSize: ")
                        .append(_formatHtmlBytes(info.getLastUpdateDecompressedSize())).append("<br>\n");
                out.append("LastUpdateDataCount: ").append("<b>")
                        .append(Integer.toString(info.getLastUpdateDataCount())).append("</b><br>\n");
                out.append("<br>\n");
            }

            out.append("NumberOfFullUpdatesReceived: ").append("<b>")
                    .append(Integer.toString(info.getNumberOfFullUpdatesReceived())).append("</b><br>\n");
            out.append("NumberOfPartialUpdatesReceived: ").append("<b>")
                    .append(Integer.toString(info.getNumberOfPartialUpdatesReceived())).append("</b><br>\n");
            out.append("NumberOfAccesses: ").append("<b>")
                    .append(Long.toString(info.getNumberOfAccesses())).append("</b><br>\n");
            out.append("</div>\n");
            out.append("</div>\n");

            logsAndExceptions(out, info.getExceptionEntries(), info.getLogEntries());

            out.append("</div>\n");
        }

        static void logsAndExceptions(Appendable out, List<ExceptionEntry> exceptionEntries, List<LogEntry> logEntries)
                throws IOException {
            out.append("<br>\n");

            // :: Print out exception entries, or "No exceptions" if none.
            if (exceptionEntries.isEmpty()) {
                out.append("<h2>Exception entries</h2><br>\n");
                out.append("<b><i>No exceptions!</i></b><br>\n");
            }
            else {

                out.append("<div class='matsec-log-table-container'>");
                out.append("<h2>Exception entries</h2>\n");
                out.append(logEntries.size() > 5
                        ? "<button class='matsec-toggle-button' onclick='matsecToggleLogs(this)'>Show All</button>\n"
                        : "");
                out.append(Integer.toString(logEntries.size())).append(logEntries.size() != 1 ? " entries" : " entry")
                        .append(" out of max " + CacheMonitor.MAX_ENTRIES + ", most recent first.<br>\n");
                out.append("<table class='matsec-log-table'><thead><tr>"
                        + "<th>Timestamp</th>"
                        + "<th>Category</th>"
                        + "<th>Message</th>"
                        + "</tr></thead>");
                out.append("<tbody>\n");
                int count = 0;
                for (int i = exceptionEntries.size() - 1; i >= 0; i--) {
                    ExceptionEntry entry = exceptionEntries.get(i);
                    out.append("<tr class='matsec-log-row").append(count >= 5 ? " matsec-hidden'>" : "'>");
                    out.append("  <td class='matsec-timestamp'>").append(_formatHtmlTimestamp(entry.getTimestamp()))
                            .append("</td>");
                    out.append("  <td class='matsec-category'>").append(entry.getCategory().toString()).append("</td>");
                    out.append("  <td class='matsec-message'>").append(entry.getMessage()).append("</td>");
                    out.append("</tr>\n");
                    out.append("<tr class='matsec-log-row-throwable").append(count >= 5 ? " matsec-hidden'>" : "'>");
                    out.append("  <td colspan='3' class='matsec-throwable'><pre>").append(entry.getThrowableAsString())
                            .append("</pre></td>");
                    out.append("</tr>\n");
                    count++;
                }
                out.append("</tbody></table>\n");
                out.append("</div>\n");
            }

            // :: Print out log entries
            out.append("<br>\n");
            out.append("<div class='matsec-log-table-container'>");
            out.append("<h2>Log entries</h2>\n");
            out.append(logEntries.size() > 5
                    ? "<button class='matsec-toggle-button' onclick='matsecToggleLogs(this)'>Show All</button>\n"
                    : "");
            out.append(Integer.toString(logEntries.size())).append(logEntries.size() != 1 ? " entries" : " entry")
                    .append(" out of max " + CacheMonitor.MAX_ENTRIES + ", most recent first..<br>\n");
            out.append("<table class='matsec-log-table'><thead><tr>"
                    + "<th>Timestamp</th>"
                    + "<th>Level</th>"
                    + "<th>Category</th>"
                    + "<th>Message</th>"
                    + "</tr></thead>");
            out.append("<tbody>\n");
            int count = 0;
            for (int i = logEntries.size() - 1; i >= 0; i--) {
                LogEntry entry = logEntries.get(i);
                out.append("<tr class='matsec-log-row").append(count >= 5 ? " matsec-hidden'>" : "'>");
                out.append("<td class='matsec-timestamp'>").append(_formatHtmlTimestamp(entry.getTimestamp()))
                        .append("</td>");
                out.append("<td class='matsec-level'>").append(entry.getLevel().toString()).append("</td>");
                out.append("<td class='matsec-category'>").append(entry.getCategory().toString()).append("</td>");
                out.append("<td class='matsec-message'>").append(entry.getMessage()).append("</td>");
                out.append("</tr>\n");
                count++;
            }
            out.append("</tbody></table>\n");
            out.append("</div>\n");
        }

        @Override
        public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody, AccessControl ac)
                throws IOException {
            out.append("/* No JSON for MatsEagerCacheClient */");
        }

        @Override
        public String getRoutingId() {
            return _routingId;
        }
    }

    /**
     * Implementation of {@link MatsEagerCacheHtmlGui} for {@link MatsEagerCacheServer} - use the
     * {@link MatsEagerCacheHtmlGui#create(MatsEagerCacheServer)} factory method to get an instance.
     */
    class MatsEagerCacheServerHtmlGui implements MatsEagerCacheHtmlGui {
        private final CacheServerInformation _info;
        private final String _routingId;

        private MatsEagerCacheServerHtmlGui(MatsEagerCacheServer server) {
            _info = server.getCacheServerInformation();
            _routingId = "S-" + (server.getCacheServerInformation().getDataName()
                    + "-" + server.getCacheServerInformation().getNodename()).replaceAll("[^a-zA-Z0-9_]", "-");
        }

        @Override
        public void html(Appendable out, Map<String, String[]> requestParameters, AccessControl ac) throws IOException {
            out.append("<div class='matsec-container'>\n");
            out.append("<h1>MatsEagerCacheServer '" + _info.getDataName() + "' @ '" + _info.getNodename() + "'</h1>");

            out.append("<div class='matsec-column-container'>\n");

            out.append("<div class='matsec-column'>\n");
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
            out.append("</div>\n");

            out.append("<div class='matsec-column'>\n");
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
                out.append("LastUpdateDataCount: ").append("<b>")
                        .append(Integer.toString(_info.getLastUpdateDataCount())).append("</b><br>\n");
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
            out.append("</div>\n");
            out.append("</div>\n");

            MatsEagerCacheClientHtmlGui.logsAndExceptions(out, _info.getExceptionEntries(), _info
                    .getLogEntries());
            out.append("</div>\n");
        }

        @Override
        public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody, AccessControl ac)
                throws IOException {
            out.append("/* No JSON for MatsEagerCacheServer */");
        }

        @Override
        public String getRoutingId() {
            return _routingId;
        }
    }
}
