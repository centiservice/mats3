package io.mats3.api_test.basics;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
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
    private static final String ENDPOINT_LEAF1 = MatsTestHelp.endpoint("leaf1");
    private static final String ENDPOINT_LEAF2 = MatsTestHelp.endpoint("leaf2");
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @Before
    public void setupEndpoints() {
        // :: The Terminator that resolves the test
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            MATS.getMatsTestLatch().resolve(ctx, state, msg);
        });

    }

    @After
    public void deleteEndpoints() {
        MATS.cleanMatsFactories();
    }

    @Test
    public void simplest() {
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

    @Test
    public void withRequests() {
        // :: ARRANGE
        MatsEndpoint<DataTO, Void> leaf1 = MATS.getMatsFactory().single(ENDPOINT_LEAF1, DataTO.class, DataTO.class, (
                ctx, msg) -> new DataTO(msg.number,
                        msg.string + "_leaf1"));
        MatsEndpoint<DataTO, Void> leaf2 = MATS.getMatsFactory().single(ENDPOINT_LEAF2, DataTO.class, DataTO.class, (
                ctx, msg) -> new DataTO(msg.number,
                        msg.string + "_leaf2"));

        // :: The Endpoint which employs nextDirect(..)
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(ENDPOINT, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(0, state.number1);
            Assert.assertEquals(0, state.number2, 0d);
            state.number1 = 12345;
            state.number2 = Math.E;
            log.info("Invoking context.nextDirect() from Stage0");
            ctx.nextDirect(new DataTO(msg.number * Math.PI, msg.string + "_NextDirectFromStage0"));
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(12345, state.number1);
            Assert.assertEquals(Math.E, state.number2, 0d);
            state.number1 = 456;
            state.number2 = Math.PI;
            ctx.request(ENDPOINT_LEAF1, new DataTO(msg.number, msg.string + "_requestFromStage1"));
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(456, state.number1);
            Assert.assertEquals(Math.PI, state.number2, 0d);
            state.number1 = 789;
            state.number2 = 1d;
            log.info("Invoking context.nextDirect() from Stage2");
            ctx.nextDirect(new DataTO(msg.number * Math.E, msg.string + "_NextDirectFromStage2"));
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(789, state.number1);
            Assert.assertEquals(1d, state.number2, 0d);
            state.number1 = 7654;
            state.number2 = 2d;
            log.info("Invoking context.nextDirect() from Stage2");
            ctx.nextDirect(new DataTO(msg.number * 7, msg.string + "_NextDirectFromStage3"));
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(7654, state.number1);
            Assert.assertEquals(2d, state.number2, 0d);
            state.number1 = 1000;
            state.number2 = 1000d;
            ctx.request(ENDPOINT_LEAF2, new DataTO(msg.number, msg.string + "_requestFromStage4"));
        });
        ep.lastStage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(1000, state.number1);
            Assert.assertEquals(1000d, state.number2, 0d);
            return new DataTO(msg.number, msg.string + "_replyFromStage5");
        });

        // :: ACT

        DataTO dto = new DataTO(42, "answer");
        StateTO state = new StateTO(12, 34.56);

        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from())
                .to(ENDPOINT)
                .replyTo(TERMINATOR, state)
                .request(dto));

        // :: ASSERT

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(state, result.getState());
        Assert.assertEquals(new DataTO(dto.number * Math.PI * Math.E * 7, dto.string
                + "_NextDirectFromStage0"
                + "_requestFromStage1_leaf1"
                + "_NextDirectFromStage2_NextDirectFromStage3"
                + "_requestFromStage4_leaf2"
                + "_replyFromStage5"), result.getData());
    }
}
