package io.mats3.api.intercept;

import java.util.List;
import java.util.Optional;

import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsInitiator.MatsInitiate;

/**
 * Specifies methods that an interceptable MatsFactory must provide.
 *
 * @author Endre Stølsvik - 2021-02-07 & 2021-02-19 13:18 - http://endre.stolsvik.com
 */
public interface MatsInterceptable {
    // ===== Initiation

    void addInitiationInterceptor(MatsInitiateInterceptor initiateInterceptor);

    List<MatsInitiateInterceptor> getInitiationInterceptors();

    <T extends MatsInitiateInterceptor> Optional<T> getInitiationInterceptor(Class<T> interceptorClass);

    void removeInitiationInterceptor(MatsInitiateInterceptor initiateInterceptor);

    // ===== Stage

    void addStageInterceptor(MatsStageInterceptor stageInterceptor);

    List<MatsStageInterceptor> getStageInterceptors();

    <T extends MatsStageInterceptor> Optional<T> getStageInterceptor(Class<T> interceptorClass);

    void removeStageInterceptor(MatsStageInterceptor stageInterceptor);

    /**
     * Marker interface to denote a logging interceptor. The MatsFactory will only allow one such singleton interceptor,
     * and remove any previously installed if subsequently installing another.
     */
    interface MatsLoggingInterceptor {
        /**
         * If this key is present on the Trace Properties, no ordinary log lines should be emitted while initiating or
         * stage processing the Mats Flow, <i>assuming</i> that the touched endpoints
         * {@link #SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY allows} for suppression. This can be used for frequent
         * system messages that otherwise would swamp the log, and should never be used for ordinary processing.
         * <p/>
         * This is the key of a {@link MatsInitiate#setTraceProperty(String, Object) TraceProperty}, and the value
         * should be a unique (and constant) String for the system subsystem in question.
         */
        String SUPPRESS_LOGGING_TRACE_PROPERTY_KEY = "mats.SuppressLogging";

        /**
         * {@link #SUPPRESS_LOGGING_TRACE_PROPERTY_KEY Suppression of loglines} will only be done if the affected
         * endpoints allows it. Allowance is done by setting a EndpointConfig
         * {@link EndpointConfig#setAttribute(String, Object) attribute} with this as key, and a {@link Boolean#TRUE} as
         * value.
         */
        String SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY = "mats.LoggingSuppressionAllowed";
    }

    /**
     * Marker interface to denote a metrics interceptor. The MatsFactory will only allow one such singleton interceptor,
     * and remove any previously installed if subsequently installing another.
     */
    interface MatsMetricsInterceptor {
    }
}
