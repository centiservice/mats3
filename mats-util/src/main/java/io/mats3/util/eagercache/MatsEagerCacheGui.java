package io.mats3.util.eagercache;

import static io.mats3.util.eagercache.MatsEagerCacheServer._formatMillis;
import static io.mats3.util.eagercache.MatsEagerCacheServer._formatTimestamp;

import java.io.IOException;
import java.util.Map;

import io.mats3.util.eagercache.MatsEagerCacheClient.CacheClientInformation;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheServerInformation;

public interface MatsEagerCacheGui {

    static MatsEagerCacheGui create(CacheClientInformation info) {
        return new MatsEagerCacheClientGui(info);
    }

    static MatsEagerCacheGui create(CacheServerInformation info) {
        return new MatsEagerCacheServerGui(info);
    }

    /**
     * Note: The output from this method is static, it can be written directly to the HTML page in a script-tag, or
     * included as a separate file (with hard caching).
     */
    void outputStyleSheet(Appendable out) throws IOException;

    /**
     * Note: The output from this method is static, it can be written directly to the HTML page in a style-tag, or
     * included as a separate file (with hard caching).
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
     * Implementation of {@link MatsEagerCacheGui} for {@link MatsEagerCacheClient} - use the
     * {@link MatsEagerCacheGui#create(CacheClientInformation)} factory method to get an instance.
     */
    class MatsEagerCacheClientGui implements MatsEagerCacheGui {
        private final CacheClientInformation _info;

        private MatsEagerCacheClientGui(CacheClientInformation info) {
            _info = info;
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
            out.append("<h2>MatsEagerCacheClient '" + _info.getDataName() + "' @ '" + _info.getNodename() + "'</h2>");
            out.append("<pre>");
            out.append("DataName: ").append(_info.getDataName()).append("\n");
            out.append("Nodename: ").append(_info.getNodename()).append("\n");
            out.append("Running: ").append(Boolean.toString(_info.isRunning())).append("\n");
            out.append("InitialPopulationDone: ")
                    .append(Boolean.toString(_info.isInitialPopulationDone())).append("\n");
            out.append("CacheStartedTimestamp: ")
                    .append(_formatTimestamp(_info.getCacheStartedTimestamp())).append("\n");
            out.append("InitialPopulationRequestSentTimestamp: ")
                    .append(_formatTimestamp(_info.getInitialPopulationRequestSentTimestamp())).append("\n");
            out.append("InitialPopulationTimestamp: ")
                    .append(_formatTimestamp(_info.getInitialPopulationTimestamp())).append("\n");
            out.append("LastUpdateTimestamp: ")
                    .append(_formatTimestamp(_info.getLastUpdateTimestamp())).append("\n");
            out.append("LastUpdateDurationMillis: ")
                    .append(_formatMillis(_info.getLastUpdateDurationMillis())).append("\n");
            out.append("NumberOfFullUpdatesReceived: ")
                    .append(Integer.toString(_info.getNumberOfFullUpdatesReceived())).append("\n");
            out.append("NumberOfPartialUpdatesReceived: ")
                    .append(Integer.toString(_info.getNumberOfPartialUpdatesReceived())).append("\n");
            out.append("NumberOfAccesses: ")
                    .append(Long.toString(_info.getNumberOfAccesses())).append("\n");
            out.append("</pre>");
        }

        @Override
        public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody)
                throws IOException {
            out.append("/* No JSON for MatsEagerCacheClient */");
        }
    }

    /**
     * Implementation of {@link MatsEagerCacheGui} for {@link MatsEagerCacheServer} - use the
     * {@link MatsEagerCacheGui#create(CacheServerInformation)} factory method to get an instance.
     */
    class MatsEagerCacheServerGui implements MatsEagerCacheGui {
        private final CacheServerInformation _info;

        private MatsEagerCacheServerGui(CacheServerInformation info) {
            _info = info;
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
            out.append("<pre>");
            out.append("DataName: ").append(_info.getDataName()).append("\n");
            out.append("Nodename: ").append(_info.getNodename()).append("\n");
            out.append("CacheRequestQueue: ").append(_info.getCacheRequestQueue()).append("\n");
            out.append("BroadcastTopic: ").append(_info.getBroadcastTopic()).append("\n");
            out.append("CacheServerLifeCycle: ").append(_info.getCacheServerLifeCycle().toString()).append("\n");
            out.append("CacheStartedTimestamp: ")
                    .append(_formatTimestamp(_info.getCacheStartedTimestamp())).append("\n");
            out.append("LastFullUpdateRequestReceivedTimestamp: ")
                    .append(_formatTimestamp(_info.getLastFullUpdateRequestReceivedTimestamp())).append("\n");
            out.append("LastFullUpdateProductionStartedTimestamp: ")
                    .append(_formatTimestamp(_info.getLastFullUpdateProductionStartedTimestamp())).append("\n");
            out.append("LastFullUpdateReceivedTimestamp: ")
                    .append(_formatTimestamp(_info.getLastFullUpdateReceivedTimestamp())).append("\n");
            out.append("LastPartialUpdateReceivedTimestamp: ")
                    .append(_formatTimestamp(_info.getLastPartialUpdateReceivedTimestamp())).append("\n");
            out.append("LastAnyUpdateReceivedTimestamp: ")
                    .append(_formatTimestamp(_info.getLastAnyUpdateReceivedTimestamp())).append("\n");
            out.append("PeriodicFullUpdateIntervalMinutes: ")
                    .append(Double.toString(_info.getPeriodicFullUpdateIntervalMinutes())).append("\n");
            out.append("NumberOfFullUpdatesSent: ")
                    .append(Integer.toString(_info.getNumberOfFullUpdatesSent())).append("\n");
            out.append("NumberOfPartialUpdatesSent: ")
                    .append(Integer.toString(_info.getNumberOfPartialUpdatesSent())).append("\n");
            out.append("</pre>");
        }

        @Override
        public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody)
                throws IOException {
            out.append("/* No JSON for MatsEagerCacheServer */");
        }
    }
}
