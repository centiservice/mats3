package io.mats3.api.intercept;

/**
 * Marker interface to denote a metrics interceptor. The MatsFactory will only allow one such singleton interceptor, and
 * remove any previously installed if subsequently installing another.
 */
public interface MatsMetricsInterceptor {
    /**
     * When measuring "initiate complete", and the initiation ends up sending no messages, there is no
     * <code>InitiatorId</code> to include. This is a constant that can be used instead. Value is
     * <code>"_no_outgoing_messages_"</code>. (Note that this also holds for logging, but it came up first with
     * pure metrics).
     */
    String INITIATOR_ID_WHEN_NO_OUTGOING_MESSAGES = "_no_outgoing_messages_";
}
