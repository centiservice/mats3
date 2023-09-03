package io.mats3.api.intercept;

import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsInitiator.MatsInitiate;

/**
 * Marker interface to denote a logging interceptor. The MatsFactory will only allow one such singleton interceptor, and
 * remove any previously installed if subsequently installing another.
 */
public interface MatsLoggingInterceptor {
    /**
     * If this key is present on the {@link MatsInitiate#setTraceProperty(String, Object) TraceProperties} of a Mats
     * Flow, with the value {@link Boolean#TRUE}, no ordinary log lines should be emitted while initiating or stage
     * processing the Mats Flow, <i>assuming</i> that the implicated endpoints
     * {@link #SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY allows} logging suppression. This is only to be used for
     * frequent system messages that otherwise would swamp the log, and should never be used for ordinary processing.
     * <p/>
     * Note wrt. suppression of initiation logging: An outgoing message starts a Mats Flow. It is the message that holds
     * the TraceProperty saying that logging should be suppressed. Three things: 1. Contrasted to endpoints, initiations
     * do not have an {@link #SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY "allow"} concept, as it makes no sense
     * security wise since the initiator could just change the allowance. 2. If multiple messages are initiated, then
     * they all must agree of suppression for the initiation log lines to be suppressed. 3. If you initiate, but do not
     * send any messages after all (inside the initiation you find that there is nothing to do), then it is impossible
     * to suppress logging. Thus, you should instead perform whatever checking to decide whether to send a message
     * before going into initiation.
     * <p/>
     * Note: The <code>MatsMetricsLoggingInterceptor</code> has logic whereby every X minutes, the number of suppressed
     * logs are output (if any), grouped into matsFactory/initApp/initiatorId/stageId, so that it is possible to assert
     * liveliness of the system, and also get an idea of how heavy usage it leads to.
     * <p/>
     * Note: Suppression of logging effectively removes these initiations and processing from any aggregate metrics
     * based on the log system, e.g. doing "number of message per second" in Kibana will lose out on these messages
     * since they aren't logged (except for the aggregate every X minutes mentioned above). Metrics in form of
     * <code>MatsMicrometerInterceptor</code> are not affected.
     *
     * @see #SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY
     */
    String SUPPRESS_LOGGING_TRACE_PROPERTY_KEY = "mats.SuppressLogging";

    /**
     * {@link #SUPPRESS_LOGGING_TRACE_PROPERTY_KEY Suppression of loglines} will only be done if the affected endpoints
     * allows it. Allowance is done by setting an
     * {@link EndpointConfig#setAttribute(String, Object) EndpointConfig attribute} for the allowing Endpoint with this
     * as key, with the value {@link Boolean#TRUE}.
     *
     * @see #SUPPRESS_LOGGING_TRACE_PROPERTY_KEY
     */
    String SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY = "mats.SuppressLoggingAllowed";
}
