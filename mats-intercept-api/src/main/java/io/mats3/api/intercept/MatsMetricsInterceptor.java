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

package io.mats3.api.intercept;

import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsInitiator.MatsInitiate;

/**
 * Marker interface to denote a metrics interceptor. The MatsFactory will only allow one such singleton interceptor, and
 * remove any previously installed if subsequently installing another.
 */
public interface MatsMetricsInterceptor {
    /**
     * When measuring "initiate complete", and the initiation ends up sending no messages, there is no
     * <code>InitiatorId</code> to include. This is a constant that can be used instead. Value is
     * <code>"_no_outgoing_messages_"</code>. (Note that this also holds for logging, but it came up first with pure
     * metrics).
     */
    String INITIATOR_ID_WHEN_NO_OUTGOING_MESSAGES = "_no_outgoing_messages_";

    /**
     * If this key is present on the {@link MatsInitiate#setTraceProperty(String, Object) TraceProperties} of a Mats
     * Flow, with the value {@link Boolean#TRUE}, no metrics would be gathered while initiating or stage processing the
     * Mats Flow, <i>assuming</i> that the implicated endpoints {@link #SUPPRESS_METRICS_ENDPOINT_ALLOWS_ATTRIBUTE_KEY
     * allows} metrics suppression. This is only to be used for frequent system messages that otherwise would distort
     * metrics (typically overviews, i.e. "what microservice have most messages"), and should never be used for ordinary
     * processing.
     * <p>
     * Note wrt. suppression of initiation metrics: An outgoing message starts a Mats Flow. It is the message that holds
     * the TraceProperty saying that metrics should be suppressed. Three things: 1. Contrasted to endpoints, initiations
     * do not have an {@link #SUPPRESS_METRICS_ENDPOINT_ALLOWS_ATTRIBUTE_KEY "allow"} concept, as it makes no sense
     * security wise since the initiator could just change the allowance. 2. If multiple messages are initiated, then
     * they all must agree of suppression for the initiation metrics to be suppressed. 3. If you initiate, but do not
     * send any messages after all (inside the initiation you find that there is nothing to do), then it is impossible
     * to suppress metrics. Thus, you should instead perform whatever checking to decide whether to send a message
     * before going into initiation.
     * <p>
     * Note: Suppression of logging effectively removes these initiations and processing from any aggregate metrics,
     * e.g. doing "number of message per second" in Prometheus will lose out on these messages since they aren't metrics
     * recorded. Metrics in form of <code>MatsLoggingInterceptor</code> are not affected - unless those also are
     * {@link MatsLoggingInterceptor#SUPPRESS_LOGGING_TRACE_PROPERTY_KEY suppressed!}
     *
     * @see #SUPPRESS_METRICS_ENDPOINT_ALLOWS_ATTRIBUTE_KEY
     * @see MatsLoggingInterceptor#SUPPRESS_LOGGING_TRACE_PROPERTY_KEY
     */
    String SUPPRESS_METRICS_TRACE_PROPERTY_KEY = "mats.SuppressMetrics";

    /**
     * {@link #SUPPRESS_METRICS_TRACE_PROPERTY_KEY Suppression of metrics} will only be done if the affected endpoints
     * allows it. Allowance is done by setting an {@link EndpointConfig#setAttribute(String, Object) EndpointConfig
     * attribute} for the allowing Endpoint with this as key, with the value {@link Boolean#TRUE}.
     *
     * @see #SUPPRESS_METRICS_TRACE_PROPERTY_KEY
     */
    String SUPPRESS_METRICS_ENDPOINT_ALLOWS_ATTRIBUTE_KEY = "mats.SuppressMetricsAllowed";

}
