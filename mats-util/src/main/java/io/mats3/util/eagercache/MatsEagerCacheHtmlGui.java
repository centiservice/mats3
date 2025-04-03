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

package io.mats3.util.eagercache;

import static io.mats3.util.eagercache.MatsEagerCacheHtmlGui.MatsEagerCacheClientHtmlGui.includeFile;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatHtmlBytes;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatHtmlTimestamp;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatMillis;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatTimestamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheClientInformation;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheUpdated;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientMock;
import io.mats3.util.eagercache.MatsEagerCacheHtmlGui.MatsEagerCacheClientHtmlGui.ReplyDto;
import io.mats3.util.eagercache.MatsEagerCacheHtmlGui.MatsEagerCacheClientHtmlGui.RequestDto;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheServerInformation;
import io.mats3.util.eagercache.MatsEagerCacheServer.ExceptionEntry;
import io.mats3.util.eagercache.MatsEagerCacheServer.LogEntry;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl.CacheMonitor;

/**
 * Embeddable HTML GUI for the {@link MatsEagerCacheClient} and {@link MatsEagerCacheServer}.
 */
public interface MatsEagerCacheHtmlGui {
    /**
     * Factory method for creating an instance of {@link MatsEagerCacheHtmlGui} for the {@link MatsEagerCacheServer}.
     *
     * @param server
     *            the {@link MatsEagerCacheServer} instance to create the GUI for.
     * @return the {@link MatsEagerCacheHtmlGui} instance.
     */
    static MatsEagerCacheHtmlGui create(MatsEagerCacheServer server) {
        return new MatsEagerCacheServerHtmlGui(server);
    }

    /**
     * Factory method for creating an instance of {@link MatsEagerCacheHtmlGui} for the {@link MatsEagerCacheClient}.
     *
     * @param client
     *            the {@link MatsEagerCacheClient} instance to create the GUI for.
     * @return the {@link MatsEagerCacheHtmlGui} instance.
     */
    static MatsEagerCacheHtmlGui create(MatsEagerCacheClient<?> client) {
        return new MatsEagerCacheClientHtmlGui(client);
    }

    /**
     * The maximum number of seconds to synchronously wait for a cache update to happen when requesting a refresh.
     */
    int MAX_WAIT_FOR_UPDATE_SECONDS = 20;

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

        default boolean acknowledgeException() {
            return false;
        }

        default boolean requestRefresh() {
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
            public boolean acknowledgeException() {
                return true;
            }

            @Override
            public boolean requestRefresh() {
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
        private static final Logger log = LoggerFactory.getLogger(MatsEagerCacheClientHtmlGui.class);

        private final MatsEagerCacheClient<?> _client;
        private final String _routingId;
        final static ObjectReader JSON_READ_REQUEST = FieldBasedJacksonMapper
                .getMats3DefaultJacksonObjectMapper().readerFor(RequestDto.class);
        final static ObjectWriter JSON_WRITE_REPLY = FieldBasedJacksonMapper
                .getMats3DefaultJacksonObjectMapper().writerFor(ReplyDto.class);

        private MatsEagerCacheClientHtmlGui(MatsEagerCacheClient<?> client) {
            _client = client;
            _routingId = "C-" + (client.getCacheClientInformation().getDataName()
                    + "-" + client.getCacheClientInformation().getNodename())
                    .replaceAll("[^a-zA-Z0-9_]", "-");
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
                    .append(info.getNodename()).append("'</h1>");

            if (ac.requestRefresh()) {
                out.append("<button class='matsec-button matsec-refresh-button' onclick='matsecRequestRefresh(this, \"")
                        .append(getRoutingId()).append("\", ").append(Integer.toString(MAX_WAIT_FOR_UPDATE_SECONDS))
                        .append(")'>Request Cache Refresh</button>\n");
            }

            out.append("<div class='matsec-updating-message'></div>\n");

            out.append("<div class='matsec-float-right'><i>").append(ac.username()).append("</i></div>\n");

            if (_client instanceof MatsEagerCacheClientMock) {
                out.append(
                        "<br><i><b>NOTICE! This is a MOCK of the MatsEagerCacheClient</b>, and much of the information"
                                + " below is mocked!</i><br><br>\n");
            }

            out.append("<div class='matsec-column-container'>\n");

            out.append("<div class='matsec-column'>\n");
            out.append("DataName: ").append("<b>").append(info.getDataName()).append("</b><br>\n");
            out.append("Nodename: ").append("<b>").append(info.getNodename()).append("</b><br>\n");
            out.append("LifeCycle: ").append("<b>").append(info.getCacheClientLifeCycle().toString()).append(
                    "</b><br>\n");
            out.append("CacheStarted: ")
                    .append(_formatHtmlTimestamp(info.getCacheStartedTimestamp())).append("<br>\n");
            out.append("BroadcastTopic: ").append("<b>").append(info.getBroadcastTopic()).append("</b><br>\n");
            out.append("InitialPopulationRequestSent: ")
                    .append(_formatHtmlTimestamp(info.getInitialPopulationRequestSentTimestamp())).append("<br>\n");

            boolean initialDone = info.isInitialPopulationDone();
            long millisBetween = info.getInitialPopulationTimestamp() - info.getInitialPopulationRequestSentTimestamp();
            out.append("InitialPopulationDone: ")
                    .append(initialDone
                            ? "<b>Done</b> @ " + _formatHtmlTimestamp(info.getInitialPopulationTimestamp())
                                    + " - " + _formatMillis(millisBetween) + " after request"
                            : "<i>Waiting</i>")
                    .append("</b><br>\n");
            out.append("LastFullUpdateReceived: ")
                    .append(_formatHtmlTimestamp(info.getLastFullUpdateReceivedTimestamp())).append("<br>\n");
            out.append("LastPartialUpdateReceived: ")
                    .append(_formatHtmlTimestamp(info.getLastPartialUpdateReceivedTimestamp())).append("<br>\n");
            out.append("<br>\n");

            out.append("NumberOfFullUpdatesReceived: ").append("<b>")
                    .append(Integer.toString(info.getNumberOfFullUpdatesReceived())).append("</b><br>\n");
            out.append("NumberOfPartialUpdatesReceived: ").append("<b>")
                    .append(Integer.toString(info.getNumberOfPartialUpdatesReceived())).append("</b><br>\n");
            out.append("NumberOfAccesses: ").append("<b>")
                    .append(Long.toString(info.getNumberOfAccesses())).append("</b><br>\n");
            out.append("<br>\n");

            double lastRoundTripTimeMillis = info.getLastRoundTripTimeMillis();
            out.append("LastRoundTripTime: <b>")
                    .append(lastRoundTripTimeMillis > 0
                            ? _formatMillis(info.getLastRoundTripTimeMillis())
                            : "N/A - <i>Click [Refresh]!</i>")
                    .append("</b>&nbsp;&nbsp;&nbsp;<span style='font-size:80%'><i>(Must chill ")
                    .append(Integer.toString(MatsEagerCacheServer.FAST_RESPONSE_LAST_RECV_THRESHOLD_SECONDS))
                    .append(" seconds between [Refresh] to get precise timing)</i></span><br>\n");
            out.append("</div>\n");

            out.append("<div class='matsec-column'>\n");
            // ?: Is the initial population done?
            if (!initialDone) {
                // -> No, initial population not done yet.
                out.append("&nbsp;&nbsp;<i>Initial population not done yet.</i><br><br>\n");
            }
            else {
                // -> Yes, initial population done - show info.
                out.append("<h3>Last Received Update</h3><br>\n");
                out.append("&nbsp;&nbsp;Received: ")
                        .append(_formatHtmlTimestamp(info.getLastAnyUpdateReceivedTimestamp())).append("<br>\n");
                out.append("&nbsp;&nbsp;Type: ").append("<b>")
                        .append(info.isLastUpdateFull() ? "Full" : "Partial").append("</b><br>\n");
                out.append("&nbsp;&nbsp;Mode: ").append("<b>")
                        .append(info.isLastUpdateLarge() ? "LARGE" : "Small").append("</b><br>\n");
                out.append("&nbsp;&nbsp;<i>Server:</i> ProduceAndCompressTotal: <b>")
                        .append(_formatMillis(info.getLastUpdateProduceAndCompressTotalMillis()))
                        .append("</b>")
                        .append(", of which compress: <b>")
                        .append(_formatMillis(info.getLastUpdateCompressMillis())).append("</b>")
                        .append("</b>, serialize: <b>")
                        .append(_formatMillis(info.getLastUpdateSerializeMillis())).append("</b><br>\n");
                out.append("&nbsp;&nbsp;<i>Client:</i> DecompressAndConsumeTotal: <b>")
                        .append(_formatMillis(info.getLastUpdateDecompressAndConsumeTotalMillis()))
                        .append("</b>, of which decompress: <b>")
                        .append(_formatMillis(info.getLastUpdateDecompressMillis())).append("</b>")
                        .append("</b>, deserialize: <b>")
                        .append(_formatMillis(info.getLastUpdateDeserializeMillis())).append("</b><br>\n");
                String meta = info.getLastUpdateMetadata();
                out.append("&nbsp;&nbsp;Metadata: ").append(meta != null ? "<b>" + meta + "</b>" : "<i>none</i>")
                        .append("<br>\n");
                out.append("&nbsp;&nbsp;CompressedSize: ")
                        .append(_formatHtmlBytes(info.getLastUpdateCompressedSize())).append("<br>\n");
                out.append("&nbsp;&nbsp;DecompressedSize: ")
                        .append(_formatHtmlBytes(info.getLastUpdateDecompressedSize())).append("<br>\n");
                out.append("&nbsp;&nbsp;DataCount: ").append("<b>")
                        .append(Integer.toString(info.getLastUpdateDataCount())).append("</b><br>\n");
                out.append("<div style='height: 7px'></div>\n");

                out.append("&nbsp;&nbsp;<span style='font-size:80%'><i>NOTE: If LARGE update, intentional lag is"
                        + " added between nulling existing dataset and producing new to aid GC:"
                        + " 100ms for Full, 25ms for Partial.</i></span><br>\n");
            }
            out.append("<br>\n");
            out.append("Cache Servers: ");
            Map<String, Set<String>> appsNodes = info.getServerAppNamesToNodenames();
            boolean first = true;
            for (Map.Entry<String, Set<String>> entry : appsNodes.entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append("<b>").append(entry.getKey()).append("</b>: ");
                out.append(entry.getValue().stream().collect(Collectors.joining(", ", "", ". ")));
            }

            out.append("</div>\n");
            out.append("</div>\n");

            _logsAndExceptions(out, ac, getRoutingId(), info.getExceptionEntries(), info.getLogEntries());

            out.append("</div>\n");
        }

        @Override
        public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody, AccessControl ac)
                throws IOException {
            RequestDto dto = JSON_READ_REQUEST.readValue(requestBody);
            switch (dto.op) {
                case "requestRefresh":
                    // # Access Check
                    if (!ac.requestRefresh()) {
                        throw new AccessDeniedException("User [" + ac.username() + "] not allowed to request refresh.");
                    }

                    long nanosAsStart_request = System.nanoTime();
                    Optional<CacheUpdated> cacheUpdated = _client.requestFullUpdate(MAX_WAIT_FOR_UPDATE_SECONDS * 1000);
                    if (cacheUpdated.isPresent()) {
                        double millisTaken_request = (System.nanoTime() - nanosAsStart_request) / 1_000_000d;
                        out.append(new ReplyDto(true, "Refresh requested, got update, took "
                                + _formatMillis(millisTaken_request) + ".").toJson());
                    }
                    else {
                        out.append(new ReplyDto(false, "Refresh requested, but timed out after waiting "
                                + MAX_WAIT_FOR_UPDATE_SECONDS + " seconds.").toJson());
                    }
                    break;
                case "acknowledgeSingle":
                    // # Access Check
                    if (!ac.acknowledgeException()) {
                        throw new AccessDeniedException("User [" + ac.username()
                                + "] not allowed to acknowledge exceptions.");
                    }

                    boolean result = _client.getCacheClientInformation().acknowledgeException(dto.id, ac.username());
                    out.append(result
                            ? new ReplyDto(true, "Acknowledged exception with id " + dto.id).toJson()
                            : new ReplyDto(false, "Exception with id '" + dto.id + "' not found or"
                                    + " already acknowledged.").toJson());
                    break;
                case "acknowledgeUpTo":
                    // # Access Check
                    if (!ac.acknowledgeException()) {
                        throw new AccessDeniedException("User [" + ac.username()
                                + "] not allowed to acknowledge exceptions.");
                    }

                    int numAcknowledged = _client.getCacheClientInformation()
                            .acknowledgeExceptionsUpTo(dto.timestamp, ac.username());
                    out.append(new ReplyDto(true, "Acknowledged " + numAcknowledged + " exceptions up to"
                            + " timestamp '" + _formatTimestamp(dto.timestamp) + "'").toJson());
                    break;
                default:
                    throw new AccessDeniedException("User [" + ac.username()
                            + "] tried to make an unknown operation [" + dto.op + "].");
            }
        }

        @Override
        public String getRoutingId() {
            return _routingId;
        }

        static void _logsAndExceptions(Appendable out, AccessControl ac, String routingId,
                List<ExceptionEntry> exceptionEntries,
                List<LogEntry> logEntries)
                throws IOException {
            out.append("<br>\n");

            // --------------------------------------------------------------
            // :: Print out exception entries, or "No exceptions" if none.
            if (exceptionEntries.isEmpty()) {
                out.append("<h2>Exception entries</h2><br>\n");
                out.append("<b><i>No exceptions!</i></b><br>\n");
            }
            else {
                out.append("<div class='matsec-log-table-container'>");
                out.append("<h2>Exception entries</h2>\n");
                out.append(logEntries.size() > 5
                        ? "<button class='matsec-button matsec-toggle-button'"
                                + " onclick='matsecToggleAllLess(this)'>Show All</button>\n"
                        : "");
                // Count unacknowledged messages
                long unacknowledged = exceptionEntries.stream()
                        .filter(e -> !e.isAcknowledged())
                        .count();
                out.append(Integer.toString(exceptionEntries.size()))
                        .append(exceptionEntries.size() != 1 ? " entries" : " entry")
                        .append(" out of max " + CacheMonitor.MAX_ENTRIES + ", most recent first, ");
                if (unacknowledged > 0) {
                    out.append("<b>").append(Long.toString(unacknowledged)).append(" unacknowledged!</b>\n");
                }
                else {
                    out.append("all acknowledged.\n");
                }
                if (ac.acknowledgeException()) {
                    out.append("  <button class='matsec-button matsec-float-right'"
                            + " onclick='matsecAcknowledgeUpTo(this, \"").append(routingId).append("\", ")
                            .append(Long.toString(exceptionEntries.get(exceptionEntries.size() - 1).getTimestamp()))
                            .append(")'>Acknowledge up to most recent displayed</button>\n");
                }
                out.append("<table class='matsec-log-table'><thead><tr>"
                        + "<th>Timestamp</th>"
                        + "<th>Category</th>"
                        + "<th>Message</th>"
                        + "<th>Acknowledged</th>"
                        + "</tr></thead>");
                out.append("<tbody>\n");
                int count = 0;
                for (int i = exceptionEntries.size() - 1; i >= 0; i--) {
                    ExceptionEntry entry = exceptionEntries.get(i);
                    String ack = entry.isAcknowledged()
                            ? "matsec-row-acknowledged"
                            : "matsec-row-unacknowledged";
                    out.append("<tr class='matsec-log-row")
                            .append(count >= 5 ? " matsec-hidden" : "")
                            .append("'>");
                    out.append("  <td class='").append(ack).append(" matsec-timestamp'>")
                            .append(_formatHtmlTimestamp(entry.getTimestamp())).append("</td>");
                    out.append("  <td class='").append(ack).append(" matsec-category'>")
                            .append(entry.getCategory().toString()).append("</td>");
                    out.append("  <td class='").append(ack).append(" matsec-message'>")
                            .append(entry.getMessage()).append("</td>");
                    out.append("  <td class='").append(ack).append(" matsec-acknowledged'>")
                            .append(entry.isAcknowledged()
                                    ? _formatHtmlTimestamp(entry.getAcknowledgedTimestamp().getAsLong())
                                    : ac.acknowledgeException()
                                            ? "<button class='matsec-button matsec-acknowledge-button'"
                                                    + " onclick='matsecAcknowledgeSingle(this, \"" + routingId
                                                    + "\", \"" + entry.getId() + "\")'>Acknowledge</button>"
                                            : "")
                            .append(entry.isAcknowledged()
                                    ? " by " + entry.getAcknowledgedByUser().orElseThrow()
                                    : "")
                            .append("</td>");
                    out.append("</tr>\n");
                    out.append("<tr class='matsec-log-row-throwable").append(count >= 5 ? " matsec-hidden'>" : "'>");
                    out.append("  <td colspan='4' class='matsec-throwable'><pre>").append(entry.getThrowableAsString())
                            .append("</pre></td>");
                    out.append("</tr>\n");
                    count++;
                }
                out.append("</tbody></table>\n");
                out.append("</div>\n");
            }

            // --------------------------------------------------------------
            // :: Print out log entries
            out.append("<br>\n");
            out.append("<div class='matsec-log-table-container'>");
            out.append("<h2>Log entries</h2>\n");
            out.append(logEntries.size() > 5
                    ? "<button class='matsec-button matsec-toggle-button'"
                            + " onclick='matsecToggleAllLess(this)'>Show All</button>\n"
                    : "");
            out.append(Integer.toString(logEntries.size())).append(logEntries.size() != 1 ? " entries" : " entry")
                    .append(" out of max " + CacheMonitor.MAX_ENTRIES + ", most recent first.<br>\n");
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
                out.append("<td class='matsec-message'>").append(entry.getMessage().replace(" ##", "</br>"))
                        .append("</td>");
                out.append("</tr>\n");
                count++;
            }
            out.append("</tbody></table>\n");
            out.append("</div>\n");
        }

        static class RequestDto {
            String op;
            String id;
            long timestamp;
        }

        static class ReplyDto {
            boolean ok;
            String message;

            public ReplyDto(boolean ok, String message) {
                this.ok = ok;
                this.message = message;
            }

            public String toJson() {
                try {
                    return MatsEagerCacheClientHtmlGui.JSON_WRITE_REPLY.writeValueAsString(this);
                }
                catch (JsonProcessingException e) {
                    throw new RuntimeException("Could not serialize to JSON", e);
                }
            }
        }
    }

    /**
     * Implementation of {@link MatsEagerCacheHtmlGui} for {@link MatsEagerCacheServer} - use the
     * {@link MatsEagerCacheHtmlGui#create(MatsEagerCacheServer)} factory method to get an instance.
     */
    class MatsEagerCacheServerHtmlGui implements MatsEagerCacheHtmlGui {
        private final MatsEagerCacheServer _server;
        private final String _routingId;

        private MatsEagerCacheServerHtmlGui(MatsEagerCacheServer server) {
            _server = server;
            _routingId = "S-" + (server.getCacheServerInformation().getDataName()
                    + "-" + server.getCacheServerInformation().getNodename())
                    .replaceAll("[^a-zA-Z0-9_]", "-");
        }

        @Override
        public void html(Appendable out, Map<String, String[]> requestParameters, AccessControl ac) throws IOException {
            CacheServerInformation info = _server.getCacheServerInformation();
            out.append("<div class='matsec-container'>\n");
            out.append("<h1>MatsEagerCacheServer '")
                    .append(info.getDataName()).append("' @ '").append(info.getNodename()).append("'</h1>");

            if (ac.requestRefresh()) {
                out.append("<button class='matsec-button matsec-refresh-button' onclick='matsecRequestRefresh(this, \"")
                        .append(getRoutingId()).append("\", ").append(Integer.toString(MAX_WAIT_FOR_UPDATE_SECONDS))
                        .append(")'>Initiate Cache Refresh</button>\n");
            }

            out.append("<div class='matsec-updating-message'></div>\n");

            out.append("<div class='matsec-float-right'><i>").append(ac.username()).append("</i></div>\n");

            out.append("<div class='matsec-column-container'>\n");

            out.append("<div class='matsec-column'>\n");
            out.append("DataName: ").append("<b>").append(info.getDataName()).append("</b><br>\n");
            out.append("Nodename: ").append("<b>").append(info.getNodename()).append("</b><br>\n");
            out.append("LifeCycle: ").append("<b>").append(info.getCacheServerLifeCycle().toString()).append(
                    "</b><br>\n");
            out.append("CacheStarted: ")
                    .append(_formatHtmlTimestamp(info.getCacheStartedTimestamp())).append("<br>\n");
            out.append("CacheRequestQueue: ").append("<b>").append(info.getCacheRequestQueue()).append("</b><br>\n");
            out.append("BroadcastTopic: ").append("<b>").append(info.getBroadcastTopic()).append("</b><br>\n");
            out.append("PeriodicFullUpdateIntervalMinutes: ").append("<b>")
                    .append(Double.toString(info.getPeriodicFullUpdateIntervalMinutes())).append("</b><br>\n");
            out.append("<br>\n");

            out.append("<h3>Last Cluster Full Update</h3><br>\n");
            out.append("&nbsp;&nbsp;RequestRegistered: ")
                    .append(_formatHtmlTimestamp(info.getLastFullUpdateRequestRegisteredTimestamp())).append("<br>\n");
            out.append("&nbsp;&nbsp;ProductionStarted: ")
                    .append(_formatHtmlTimestamp(info.getLastFullUpdateProductionStartedTimestamp()))
                    .append("</b><br>\n");
            out.append("&nbsp;&nbsp;UpdateReceived: ")
                    .append(_formatHtmlTimestamp(info.getLastFullUpdateReceivedTimestamp())).append("<br>\n");
            out.append("&nbsp;&nbsp;RegisterToUpdate: ")
                    .append(info.getLastFullUpdateRegisterToUpdateMillis() == 0
                            ? "<i>none</i>"
                            : "<b>" + _formatMillis(info.getLastFullUpdateRegisterToUpdateMillis()) + "</b>")
                    .append("<br>\n");
            out.append("</div>\n");

            out.append("<div class='matsec-column'>\n");
            long lastUpdateSent = info.getLastUpdateSentTimestamp();
            if (lastUpdateSent > 0) {
                out.append("<h3>Last Update Produced on this Node</h3><br>\n");
                out.append("&nbsp;&nbsp;Type: ").append("<b>")
                        .append(info.isLastUpdateFull() ? "Full" : "Partial").append("</b><br>\n");
                out.append("&nbsp;&nbsp;ProductionTotal: <b>")
                        .append(_formatMillis(info.getLastUpdateProduceTotalMillis()))
                        .append("</b> - source: <b>")
                        .append(_formatMillis(info.getLastUpdateSourceMillis()))
                        .append("</b>, serialize: <b>")
                        .append(_formatMillis(info.getLastUpdateSerializeMillis()))
                        .append("</b>, compress: <b>")
                        .append(_formatMillis(info.getLastUpdateCompressMillis()))
                        .append("</b><br>\n");
                String meta = info.getLastUpdateMetadata();
                out.append("&nbsp;&nbsp;Metadata: ").append(meta != null ? "<b>" + meta + "</b>" : "<i>none</i>")
                        .append("<br>\n");
                out.append("&nbsp;&nbsp;DataCount: ").append("<b>")
                        .append(Integer.toString(info.getLastUpdateDataCount())).append("</b><br>\n");
                out.append("&nbsp;&nbsp;UncompressedSize: ")
                        .append(_formatHtmlBytes(info.getLastUpdateUncompressedSize())).append("<br>\n");
                out.append("&nbsp;&nbsp;CompressedSize: ")
                        .append(_formatHtmlBytes(info.getLastUpdateCompressedSize())).append("<br>\n");
                out.append("&nbsp;&nbsp;Sent: ").append(_formatHtmlTimestamp(lastUpdateSent)).append("<br>\n");
                out.append("<br>\n");
            }
            else {
                out.append("<i>No update sent yet.</i><br><br>\n");
            }

            out.append("FullUpdates: <b>").append(Integer.toString(info.getNumberOfFullUpdatesSent()));
            out.append("</b> sent, out of <b>").append(Integer.toString(info.getNumberOfFullUpdatesReceived()))
                    .append("</b> received.<br>\n");
            out.append("PartialUpdates: <b>").append(Integer.toString(info.getNumberOfPartialUpdatesSent()));
            out.append("</b> sent, out of <b>").append(Integer.toString(info.getNumberOfPartialUpdatesReceived()))
                    .append("</b> received.<br>\n");
            out.append("<br>\n");

            out.append("LastPartialUpdateReceived: ")
                    .append(_formatHtmlTimestamp(info.getLastPartialUpdateReceivedTimestamp())).append("<br>\n");

            out.append("</div>\n");
            out.append("</div>\n");

            out.append("<br>\n");
            out.append("<div class='matsec-column-container'>\n");
            out.append("<div class='matsec-column'>\n");
            out.append("Cache Servers: ");
            Map<String, Set<String>> appsNodes = info.getServersAppNamesToNodenames();
            if (appsNodes.isEmpty()) {
                out.append("<i>We're not yet seeing any servers.</i><br>\n");
            }
            else {
                boolean first = true;
                for (Map.Entry<String, Set<String>> entry : appsNodes.entrySet()) {
                    if (!first) {
                        out.append(", ");
                    }
                    first = false;
                    int nodes = entry.getValue().size();
                    out.append("<b>").append(entry.getKey()).append("</b> (")
                            .append(Integer.toString(nodes)).append(nodes == 1 ? " node): " : " nodes): ");
                    out.append("<span style='font-size:80%'>").append(entry.getValue().toString()).append("</span>");
                }
            }

            out.append("<br>\n");
            out.append("Cache Clients: ");
            appsNodes = info.getClientsAppNamesToNodenames();
            if (appsNodes.isEmpty()) {
                out.append("<i>We're not (yet?) seeing any clients.</i><br>\n");
            }
            else {
                int totalNodes = appsNodes.values().stream().mapToInt(Set::size).sum();
                out.append("<b>").append(Integer.toString(appsNodes.size())).append(" apps, "
                        + totalNodes + " total nodes</b>:<br>\n");

                for (Map.Entry<String, Set<String>> entry : appsNodes.entrySet()) {
                    int nodes = entry.getValue().size();
                    out.append("&nbsp;&nbsp;&nbsp;&nbsp;<b>").append(entry.getKey()).append("</b> (")
                            .append(Integer.toString(nodes)).append(nodes == 1 ? " node): " : " nodes): ");
                    out.append("<span style='font-size:80%'>").append(entry.getValue().toString())
                            .append("</span><br>\n");
                }
                out.append("<span style='font-size:80%'><i>(Reset when Cache Server nodes start. Nodes advertise every"
                        + " ~1 hour, scavenge ~1.2 hrs.)</i></span><br>\n");
            }
            out.append("</div>\n");
            out.append("</div>\n");

            MatsEagerCacheClientHtmlGui._logsAndExceptions(out, ac, getRoutingId(), info.getExceptionEntries(), info
                    .getLogEntries());
            out.append("</div>\n");
        }

        @Override
        public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody, AccessControl ac)
                throws IOException {
            RequestDto dto = MatsEagerCacheClientHtmlGui.JSON_READ_REQUEST.readValue(requestBody);
            switch (dto.op) {
                case "requestRefresh":
                    // # Access Check
                    if (!ac.requestRefresh()) {
                        throw new AccessDeniedException("User [" + ac.username() + "] not allowed to request refresh.");
                    }

                    long nanosAsStart_request = System.nanoTime();
                    boolean finished = _server.initiateFullUpdate(
                            MAX_WAIT_FOR_UPDATE_SECONDS * 1000);
                    if (finished) {
                        double millisTaken_request = (System.nanoTime() - nanosAsStart_request) / 1_000_000d;
                        out.append(new ReplyDto(true, "Refresh requested, got update, took "
                                + _formatMillis(millisTaken_request) + ".").toJson());
                    }
                    else {
                        out.append(new ReplyDto(false, "Refresh requested, but timed out after waiting "
                                + MAX_WAIT_FOR_UPDATE_SECONDS + " seconds.").toJson());
                    }
                    break;
                case "acknowledgeSingle":
                    // # Access Check
                    if (!ac.acknowledgeException()) {
                        throw new AccessDeniedException("User [" + ac.username()
                                + "] not allowed to acknowledge exceptions.");
                    }

                    boolean result = _server.getCacheServerInformation().acknowledgeException(dto.id, ac.username());
                    out.append(result
                            ? new ReplyDto(true, "Acknowledged exception with id " + dto.id).toJson()
                            : new ReplyDto(false, "Exception with id '" + dto.id + "' not found or"
                                    + " already acknowledged.").toJson());
                    break;
                case "acknowledgeUpTo":
                    // # Access Check
                    if (!ac.acknowledgeException()) {
                        throw new AccessDeniedException("User [" + ac.username()
                                + "] not allowed to acknowledge exceptions.");
                    }

                    int numAcknowledged = _server.getCacheServerInformation()
                            .acknowledgeExceptionsUpTo(dto.timestamp, ac.username());
                    out.append(new ReplyDto(true, "Acknowledged " + numAcknowledged + " exceptions up to"
                            + " timestamp '" + _formatTimestamp(dto.timestamp) + "'").toJson());
                    break;
                default:
                    throw new AccessDeniedException("User [" + ac.username()
                            + "] tried to make an unknown operation [" + dto.op + "].");
            }
        }

        @Override
        public String getRoutingId() {
            return _routingId;
        }
    }
}
