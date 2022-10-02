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
 * Testing {@link ProcessContext#goTo(String, Object)} functionality.
 *
 * @author Endre Stølsvik 2022-10-02 15:50 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_Goto {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT1 = MatsTestHelp.endpointId("endpoint1");
    private static final String ENDPOINT2 = MatsTestHelp.endpointId("endpoint2");
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupEndpoints() {
        MatsEndpoint<DataTO, StateTO> ep1 = MATS.getMatsFactory().staged(ENDPOINT1, DataTO.class, StateTO.class);
        ep1.stage(DataTO.class, (ctx, state, msg) -> {
            // Utilize GOTO!
            // ?: Should we employ "with InitialState" or not?
            if (msg.number > 0) {
                // -> Positive number: Use InitialState
                ctx.goTo(ENDPOINT2, new DataTO(msg.number * 2, msg.string + ":From_Ep1Stage0"), new StateTO(37,
                        Math.PI));
            }
            else {
                // -> Negative number: Do not use InitialState
                ctx.goTo(ENDPOINT2, new DataTO(msg.number * 2, msg.string + ":From_Ep1Stage0"));
            }
        });
        ep1.finishSetup();

        MatsEndpoint<DataTO, StateTO> ep2 = MATS.getMatsFactory().staged(ENDPOINT2, DataTO.class, StateTO.class);
        ep2.stage(DataTO.class, (ctx, state, msg) -> {
            if (msg.number > 0) {
                Assert.assertEquals(37, state.number1);
                Assert.assertEquals(Math.PI, state.number2, 0d);
            }
            else {
                Assert.assertEquals(0, state.number1);
                Assert.assertEquals(0, state.number2, 0d);
            }
            ctx.next(new DataTO(msg.number * 3, msg.string + ":From_Ep2Stage0"
                    + (msg.number > 0 ? "_WithInitialState" : "_WithoutInitialState")));
        });
        ep2.lastStage(DataTO.class, (ctx, state, msg) -> {
            return new DataTO(msg.number * 5, msg.string + ":From_Ep2Stage1");
        });

        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            MATS.getMatsTestLatch().resolve(ctx, state, msg);
        });

    }

    @Test
    public void goToWithoutInitialState() {
        DataTO dto = new DataTO(-42, "TheQuestion");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT1)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish. NOTE: Such synchronous wait is not a typical Mats flow!
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 5, dto.string
                + ":From_Ep1Stage0:From_Ep2Stage0_WithoutInitialState:From_Ep2Stage1"), result.getData());
    }

    @Test
    public void goToWithInitialState() {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT1)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish. NOTE: Such synchronous wait is not a typical Mats flow!
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 5, dto.string
                + ":From_Ep1Stage0:From_Ep2Stage0_WithInitialState:From_Ep2Stage1"), result.getData());
    }

}
