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

package io.mats3.intercept.logging;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.api.intercept.MatsLoggingInterceptor;
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

    private static final String ENDPOINT_DEFAULT = MatsTestHelp.endpoint("default");
    private static final String TERMINATOR_DEFAULT = MatsTestHelp.endpoint("defaultTerminator");

    private static final String ENDPOINT_SUPPRESS_ALLOWED = MatsTestHelp.endpoint("suppressionAllowed");
    private static final String TERMINATOR_SUPPRESS_ALLOWED = MatsTestHelp.endpoint("terminatorSuppressionAllowed");

    @BeforeClass
    public static void setupServiceAndTerminator_Default_SuppressionDisallowed() {
        MATS.getMatsFactory().single(ENDPOINT_DEFAULT, DataTO.class, DataTO.class,
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
        MatsEndpoint<DataTO, Void> service = MATS.getMatsFactory().single(ENDPOINT_SUPPRESS_ALLOWED,
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

    @AfterClass
    public static void delay() {
        // FOR VISUAL LOG INSPECTING WHEN DEVELOPING, set to true (and reduce the summary logging interval).
        boolean visualLogInspect = false;
        if (visualLogInspect) {
            try {
                Thread.sleep(60_000);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * NOT requesting log suppression, which ends up with normal logging, even though the service and terminator
     * involved allows log suppression.
     */
    @Test
    public void not_requesting_suppression_to_suppression_allowing_endpoints() {
        for (int i = 0; i < 10; i++) {
            DataTO dto = new DataTO(1337, "Elite");
            StateTO sto = new StateTO(999, 333.333);
            MATS.getMatsInitiator().initiateUnchecked(
                    (msg) -> msg.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("not_requesting"))
                            .to(ENDPOINT_SUPPRESS_ALLOWED)
                            .replyTo(TERMINATOR_SUPPRESS_ALLOWED, sto)
                            .request(dto));
            log.info("===TEST===: Inited 'not_requesting_suppression_to_suppression_allowing_endpoints'.");

            // Wait synchronously for terminator to finish.
            Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
            Assert.assertEquals(sto, result.getState());
            Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
        }
    }

    /**
     * Requesting log suppression, and the service and terminator allows suppression. This will be completely silent,
     * both the initiation (decided by the initiation!) will be suppressed, and service and terminator will also be
     * silent.
     */
    @Test
    public void requesting_suppression_to_suppression_allowing_endpoints() {
        for (int i = 0; i < 10; i++) {
            DataTO dto = new DataTO(1337, "Elite");
            StateTO sto = new StateTO(999, 333.333);
            MATS.getMatsInitiator().initiateUnchecked(
                    (msg) -> msg.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("request_allowed"))
                            .to(ENDPOINT_SUPPRESS_ALLOWED)
                            // Requesting suppression
                            .setTraceProperty(MatsLoggingInterceptor.SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, true)
                            .replyTo(TERMINATOR_SUPPRESS_ALLOWED, sto)
                            .request(dto));
            log.info("===TEST===: Inited 'requesting_suppression_to_suppression_allowing_endpoints'.");

            // Wait synchronously for terminator to finish.
            Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
            Assert.assertEquals(sto, result.getState());
            Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
        }
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
        for (int i = 0; i < 10; i++) {
            DataTO dto = new DataTO(1337, "Elite");
            StateTO sto = new StateTO(999, 333.333);
            MATS.getMatsInitiator().initiateUnchecked(
                    (msg) -> msg.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("request_non_allowed"))
                            .to(ENDPOINT_DEFAULT)
                            // Requesting suppression
                            .setTraceProperty(MatsLoggingInterceptor.SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, true)
                            .replyTo(TERMINATOR_DEFAULT, sto)
                            .request(dto));
            log.info("===TEST===: Inited 'requesting_suppression_to_non_suppression_allowing_endpoints'.");

            // Wait synchronously for terminator to finish.
            Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
            Assert.assertEquals(sto, result.getState());
            Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
        }
    }
}
