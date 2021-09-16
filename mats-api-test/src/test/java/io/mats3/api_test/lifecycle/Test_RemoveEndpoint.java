package io.mats3.api_test.lifecycle;

import java.util.Optional;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests the ability to remove and endpoint and then register a new one with the same endpointId, by first having one
 * single-stage endpoint, doing a test-round, and then replacing it with another, and doing another.
 *
 * @author Endre Stølsvik 2020-06-11 15:08 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_RemoveEndpoint {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> new DataTO(dto.number * 2, dto.string + ":FromService"));
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    @Test
    public void doTest() {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("first_run"))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        // NOTICE: The SERVICE endpoint multiplies by
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());

        // NOW, change the Endpoint!

        long nanos_StartRemove = System.nanoTime();

        // First find it
        Optional<MatsEndpoint<?, ?>> endpoint = MATS.getMatsFactory().getEndpoint(SERVICE);
        if (!endpoint.isPresent()) {
            throw new AssertionError("Didn't find endpoint!");
        }
        // .. remove it
        endpoint.get().remove(1000);

        double msRemoveTaken = (System.nanoTime() - nanos_StartRemove) / 1_000_000d;

        // .. register a new one:
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, msg) -> new DataTO(msg.number * 4, msg.string + ":FromService"));

        double msRemoveAndAddTaken = (System.nanoTime() - nanos_StartRemove) / 1_000_000d;

        log.info("######## ENDPOINT REMOVED AND ADDED, remove time taken: [" + msRemoveTaken
                + " ms], total:[" + msRemoveAndAddTaken + " ms].");

        // .. and run a new test through
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("second_run"))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 4, dto.string + ":FromService"), result.getData());
    }
}
