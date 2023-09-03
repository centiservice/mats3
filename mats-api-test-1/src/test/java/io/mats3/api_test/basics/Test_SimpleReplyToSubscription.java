package io.mats3.api_test.basics;

import io.mats3.MatsEndpoint;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests the simplest request functionality: A single-stage service is set up. A Terminator is set up. Then an initiator
 * does a request to the service, setting replyTo(Terminator).
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - request
 *     [Service] - reply
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_SimpleReplyToSubscription {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, dto) -> {
                    log.debug("MatsTrace at service:\n" + context.toString());
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
    }

    @BeforeClass
    public static void setupTerminator() {
        MatsEndpoint<Void, StateTO> terminator = MATS.getMatsFactory().subscriptionTerminator(TERMINATOR + "_Subscription", StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("SUBSCRIPTION TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
        // Ensure that this has started its receive-loop before starting tests.
        terminator.waitForReceiving(10_000);
    }

    @Test
    public void doTest() {
        DataTO dto = new DataTO(1337, "Elite");
        StateTO sto = new StateTO(999, 333.333);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyToSubscription(TERMINATOR + "_Subscription", sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }
}
