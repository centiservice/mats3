package io.mats3.api_test.basics;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests the {@link ProcessContext#nextDirect(Object)} functionality.
 *
 * @author Endre Stølsvik 2022-10-23 19:49 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_NextDirect {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupEndpoints() {
        // :: The Terminator that resolves the test
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            MATS.getMatsTestLatch().resolve(ctx, state, msg);
        });

        // :: The Endpoint which employs nextDirect(..)
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(ENDPOINT, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(0, state.number1);
            Assert.assertEquals(0, state.number2, 0d);
            state.number1 = 12345;
            state.number2 = Math.E;
            log.info("Invoking context.nextDirect()");
            ctx.nextDirect(new DataTO(msg.number * Math.PI, msg.string + "_NextDirect"));
        });
        ep.lastStage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(12345, state.number1);
            Assert.assertEquals(Math.E, state.number2, 0d);
            return msg;
        });
    }

    @Test
    public void test() {

        DataTO dto = new DataTO(15, "fifteen");
        StateTO state = new StateTO(12, 34.56);

        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from())
                .to(ENDPOINT)
                .replyTo(TERMINATOR, state)
                .request(dto));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(state, result.getState());
        Assert.assertEquals(new DataTO(dto.number * Math.PI, dto.string + "_NextDirect"), result.getData());
    }
}
