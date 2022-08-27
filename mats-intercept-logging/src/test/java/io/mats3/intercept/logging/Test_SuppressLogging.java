package io.mats3.intercept.logging;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.api.intercept.MatsInterceptable.MatsLoggingInterceptor;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * "Manual visual test": The test cannot assert the suppression itself, you need to manually check the logs. But at
 * least, you get a setup where it can be easily checked. Also, the tests does check that the suppression doesn't crash
 * anything.
 * <p/>
 * Note: You must run the tests one by one to be able to understand the result. Read the full output, not only the log
 * lines that IntelliJ tries to delimit for the test. The asynchronous nature of Mats results in IntelliJ not being able
 * to correctly delimit the logging output: The resulting log lines seems jumbled.
 * <p/>
 * Read the JavaDoc of the tests.
 *
 * @author Endre Stølsvik - 2022-08-27 15:38 - http://endre.stolsvik.com
 */
public class Test_SuppressLogging {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE_DEFAULT = MatsTestHelp.endpointId("default");
    private static final String TERMINATOR_DEFAULT = MatsTestHelp.endpointId("defaultTerminator");

    private static final String SERVICE_SUPPRESS_ALLOWED = MatsTestHelp.endpointId("suppressionAllowed");
    private static final String TERMINATOR_SUPPRESS_ALLOWED = MatsTestHelp.endpointId("terminatorSuppressionAllowed");

    @BeforeClass
    public static void setupServiceAndTerminator_Default_SuppressionDisallowed() {
        MATS.getMatsFactory().single(SERVICE_DEFAULT, DataTO.class, DataTO.class,
                (context, dto) -> {
                    log.info("###TEST###: Service Stage Processing: Default_SuppressionDisallowed");
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });

        MATS.getMatsFactory().terminator(TERMINATOR_DEFAULT, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.info("###TEST###: TERMINATOR Stage Processing: Default_SuppressionDisallowed");
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    @BeforeClass
    public static void setupServiceAndTerminator_AcceptingLogSuppression() {
        MatsEndpoint<DataTO, Void> service = MATS.getMatsFactory().single(SERVICE_SUPPRESS_ALLOWED,
                DataTO.class, DataTO.class, (context, dto) -> {
                    log.info("###TEST###: Service Stage Processing: AcceptingLogSuppression");
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
        MatsEndpoint<Void, StateTO> terminator = MATS.getMatsFactory().terminator(TERMINATOR_SUPPRESS_ALLOWED,
                StateTO.class, DataTO.class, (context, sto, dto) -> {
                    log.info("###TEST###: TERMINATOR Stage Processing: AcceptingLogSuppression");
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

        // :: Allow log suppression for both service and terminator.
        service.getEndpointConfig()
                .setAttribute(MatsLoggingInterceptor.SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY, Boolean.TRUE);
        terminator.getEndpointConfig()
                .setAttribute(MatsLoggingInterceptor.SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY, Boolean.TRUE);
    }

    /**
     * NOT requesting log suppression, which ends up with normal logging, even though the service and terminator
     * involved allows log suppression.
     */
    @Test
    public void not_requesting_suppression_to_suppression_allowing_endpoints() {
        DataTO dto = new DataTO(1337, "Elite");
        StateTO sto = new StateTO(999, 333.333);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(SERVICE_SUPPRESS_ALLOWED)
                        .replyTo(TERMINATOR_SUPPRESS_ALLOWED, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }

    /**
     * Requesting log suppression, and the service and terminator allows suppression. This will be completely silent,
     * both the initiation (decided by the initiation!) will be suppressed, and service and terminator will also be
     * silent.
     */
    @Test
    public void requesting_suppression_to_suppression_allowing_endpoints() {
        DataTO dto = new DataTO(1337, "Elite");
        StateTO sto = new StateTO(999, 333.333);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(SERVICE_SUPPRESS_ALLOWED)
                        // Requesting suppression
                        .setTraceProperty(MatsLoggingInterceptor.SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, "SuppressionId")
                        .replyTo(TERMINATOR_SUPPRESS_ALLOWED, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }

    /**
     * Requesting log suppression, but the Mats Flow goes to an endpoint and terminator which hasn't enabled
     * suppression.
     * <p/>
     * <b>NOTICE: The initiation will be log suppressed, as that is decided by the sender - but the endpoint and
     * terminator will log normally.</b>
     */
    @Test
    public void requesting_suppression_to_non_suppression_allowing_endpoints() {
        DataTO dto = new DataTO(1337, "Elite");
        StateTO sto = new StateTO(999, 333.333);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(SERVICE_DEFAULT)
                        // Requesting suppression
                        .setTraceProperty(MatsLoggingInterceptor.SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, "SuppressionId")
                        .replyTo(TERMINATOR_DEFAULT, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }
}
