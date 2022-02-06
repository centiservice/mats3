package io.mats3.serial.json;

import io.mats3.serial.MatsTrace;
import io.mats3.serial.impl.MatsTraceFieldImpl;

/**
 * Extension of {@link MatsTraceFieldImpl} which uses String for Z, meant to use JSON to serialize the DTO and STO
 * payloads. Employed by {@link MatsSerializerJson}.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public final class MatsTraceStringImpl extends MatsTraceFieldImpl<String> {

    /**
     * Creates a new {@link MatsTrace}. Must add a {@link Call} before sending.
     *
     * @param traceId
     *            the user-defined hopefully-unique id for this call flow.
     * @param flowId
     *            the system-defined pretty-much-(for <i>all</i> purposes)-guaranteed-unique id for this call flow.
     * @param keepMatsTrace
     *            the level of "trace keeping".
     * @param nonPersistent
     *            if the messages in this flow should be non-persistent
     * @param interactive
     *            if the messages in this flow is of "interactive" priority.
     * @param ttlMillis
     *            the number of milliseconds the message should live before being time out. 0 means "forever", and is
     *            the default.
     * @param noAudit
     *            hint to the underlying implementation, or to any monitoring/auditing tooling on the Message Broker,
     *            that it does not make much value in auditing this message flow, typically because it is just a
     *            "getter" of information to show to some user, or a health-check validating that some service is up and
     *            answers in a timely fashion.
     * @return the newly created {@link MatsTrace}.
     */
    @SuppressWarnings("unchecked")
    public static MatsTrace<String> createNew(String traceId, String flowId,
            KeepMatsTrace keepMatsTrace, boolean nonPersistent, boolean interactive, long ttlMillis, boolean noAudit) {
        return new MatsTraceStringImpl(traceId, flowId, keepMatsTrace, nonPersistent, interactive, ttlMillis, noAudit);
    }

    private MatsTraceStringImpl() {
        // Jackson JSON-lib needs a no-args constructor, but it can re-set finals.
        super();
    }

    private MatsTraceStringImpl(String traceId, String flowId, KeepMatsTrace keepMatsTrace, boolean nonPersistent,
            boolean interactive, long ttlMillis, boolean noAudit) {
        super(traceId, flowId, keepMatsTrace, nonPersistent, interactive, ttlMillis, noAudit);
    }
}
