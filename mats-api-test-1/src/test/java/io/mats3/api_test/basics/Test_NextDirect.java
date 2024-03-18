package io.mats3.api_test.basics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.DetachedProcessContext;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
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

    @Before
    public void setupEndpoints() {
        // :: The Terminator that resolves the tests
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

            // :: StageId should reflect "stage1", and obviously should EndpointId still be same.
            Assert.assertEquals(ENDPOINT, ctx.getEndpointId());
            Assert.assertEquals(ENDPOINT + ".stage1", ctx.getStageId());

            // :: From stage should be stage0 (i.e. endpoint itself)
            Assert.assertEquals(ENDPOINT, ctx.getFromStageId());

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

    private static final String ENDPOINT_LEAF1 = MatsTestHelp.endpoint("leaf1");
    private static final String ENDPOINT_LEAF2 = MatsTestHelp.endpoint("leaf2");
    private static final String TERMINATOR_FOR_INITS = MatsTestHelp.terminator("for_inits");

    @Test
    public void doubleNextDirects_interleaved_with_Requests_and_InitiationsInStages() throws InterruptedException {
        // :: ARRANGE
        // :: Some leaf services
        MatsFactory matsFactory = MATS.getMatsFactory();
        matsFactory.single(ENDPOINT_LEAF1, DataTO.class, DataTO.class, (
                ctx, msg) -> new DataTO(msg.number,
                        msg.string + "_leaf1"));
        matsFactory.single(ENDPOINT_LEAF2, DataTO.class, DataTO.class, (
                ctx, msg) -> new DataTO(msg.number,
                        msg.string + "_leaf2"));

        // :: Extra terminator for stage-inits
        CopyOnWriteArrayList<String> received_inits = new CopyOnWriteArrayList<>();
        CountDownLatch initsReceivedLatch = new CountDownLatch(18);
        matsFactory.terminator(TERMINATOR_FOR_INITS, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            received_inits.add(ctx.getFromStageId() + "#" + msg.string);
            initsReceivedLatch.countDown();
        });

        // :: The Endpoint which employs nextDirect(..)
        MatsEndpoint<DataTO, StateTO> ep = matsFactory.staged(ENDPOINT, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(0, state.number1);
            Assert.assertEquals(0, state.number2, 0d);
            state.number1 = 12345;
            state.number2 = Math.E;

            Assert.assertEquals(new DataTO(20d, "hjort"), ctx.getTraceProperty("init", DataTO.class));

            // Set TraceProps, bytes and strings

            ctx.setTraceProperty("stage0", new DataTO(10d, "elg"));
            ctx.addBytes("bytes_stage0", new byte[] { 1, 2, 3 });
            ctx.addString("string_stage0", "String from Stage0");

            ctx.initiate(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(0d, "zero")));
            matsFactory.getDefaultInitiator()
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(0d, "zero_DI")));
            matsFactory.getOrCreateInitiator("test")
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(0d, "zero_NI")));
            log.info("Invoking context.nextDirect() from Stage0");
            ctx.nextDirect(new DataTO(msg.number * Math.PI, msg.string + "_NextDirectFromStage0"));
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(12345, state.number1);
            Assert.assertEquals(Math.E, state.number2, 0d);
            state.number1 = 456;
            state.number2 = Math.PI;

            Assert.assertEquals(new DataTO(20d, "hjort"), ctx.getTraceProperty("init", DataTO.class));
            Assert.assertEquals(new DataTO(10d, "elg"), ctx.getTraceProperty("stage0", DataTO.class));

            // Previous nextDirect stage sideloads should be present
            Assert.assertArrayEquals(new byte[] { 1, 2, 3 }, ctx.getBytes("bytes_stage0"));
            Assert.assertEquals("String from Stage0", ctx.getString("string_stage0"));

            ctx.initiate(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(1d, "one")));
            matsFactory.getDefaultInitiator()
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(1d, "one_DI")));
            matsFactory.getOrCreateInitiator("test")
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(1d, "one_NI")));
            ctx.request(ENDPOINT_LEAF1, new DataTO(msg.number, msg.string + "_requestFromStage1"));
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(456, state.number1);
            Assert.assertEquals(Math.PI, state.number2, 0d);
            state.number1 = 789;
            state.number2 = 1d;

            Assert.assertEquals(new DataTO(20d, "hjort"), ctx.getTraceProperty("init", DataTO.class));
            Assert.assertEquals(new DataTO(10d, "elg"), ctx.getTraceProperty("stage0", DataTO.class));

            // :: Set more

            ctx.setTraceProperty("stage2", new DataTO(25d, "hund"));
            ctx.addBytes("bytes_stage2", new byte[] { 4, 5, 6 });
            ctx.addString("string_stage2", "String from Stage2");

            // The new TraceProperty is only present on outgoing messages, not the current context.
            Assert.assertNull(null, ctx.getTraceProperty("stage2", DataTO.class));

            ctx.initiate(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(2d, "two")));
            matsFactory.getDefaultInitiator()
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(2d, "two_DI")));
            matsFactory.getOrCreateInitiator("test")
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(2d, "two_NI")));
            log.info("Invoking context.nextDirect() from Stage2");
            ctx.nextDirect(new DataTO(msg.number * Math.E, msg.string + "_NextDirectFromStage2"));
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(789, state.number1);
            Assert.assertEquals(1d, state.number2, 0d);
            state.number1 = 7654;
            state.number2 = 2d;

            Assert.assertEquals(new DataTO(20d, "hjort"), ctx.getTraceProperty("init", DataTO.class));
            Assert.assertEquals(new DataTO(10d, "elg"), ctx.getTraceProperty("stage0", DataTO.class));
            Assert.assertEquals(new DataTO(25d, "hund"), ctx.getTraceProperty("stage2", DataTO.class));

            // Previous nextDirect stage sideloads should be present
            Assert.assertArrayEquals(new byte[] { 4, 5, 6 }, ctx.getBytes("bytes_stage2"));
            Assert.assertEquals("String from Stage2", ctx.getString("string_stage2"));

            // :: Set more

            ctx.setTraceProperty("stage3", new DataTO(30d, "hane"));
            ctx.addBytes("bytes_stage3", new byte[] { 3, 2, 1 });
            ctx.addString("string_stage3", "String from Stage3");

            // The new TraceProperty is only present on outgoing messages, not the current context.
            Assert.assertNull(null, ctx.getTraceProperty("stage3", DataTO.class));

            ctx.initiate(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(3d, "three")));
            matsFactory.getDefaultInitiator()
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(3d, "three_DI")));
            matsFactory.getOrCreateInitiator("test")
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(3d, "three_NI")));
            log.info("Invoking context.nextDirect() from Stage3");
            ctx.nextDirect(new DataTO(msg.number * 7, msg.string + "_NextDirectFromStage3"));
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(7654, state.number1);
            Assert.assertEquals(2d, state.number2, 0d);
            state.number1 = 1000;
            state.number2 = 1000d;

            Assert.assertEquals(new DataTO(20d, "hjort"), ctx.getTraceProperty("init", DataTO.class));
            Assert.assertEquals(new DataTO(10d, "elg"), ctx.getTraceProperty("stage0", DataTO.class));
            Assert.assertEquals(new DataTO(25d, "hund"), ctx.getTraceProperty("stage2", DataTO.class));
            Assert.assertEquals(new DataTO(30d, "hane"), ctx.getTraceProperty("stage3", DataTO.class));

            // From previous-previous nextDirect stage sideloads should be gone
            Assert.assertNull(ctx.getBytes("bytes_stage2"));
            Assert.assertNull(ctx.getString("string_stage2"));

            // .. while previous nextDirect stage sideloads should be present
            Assert.assertArrayEquals(new byte[] { 3, 2, 1 }, ctx.getBytes("bytes_stage3"));
            Assert.assertEquals("String from Stage3", ctx.getString("string_stage3"));

            ctx.initiate(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(4d, "four")));
            matsFactory.getDefaultInitiator()
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(4d, "four_DI")));
            matsFactory.getOrCreateInitiator("test")
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(4d, "four_NI")));
            ctx.request(ENDPOINT_LEAF2, new DataTO(msg.number, msg.string + "_requestFromStage4"));
        });
        ep.lastStage(DataTO.class, (ctx, state, msg) -> {
            Assert.assertEquals(1000, state.number1);
            Assert.assertEquals(1000d, state.number2, 0d);
            ctx.initiate(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(5d, "five")));
            matsFactory.getDefaultInitiator()
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(5d, "five_DI")));
            matsFactory.getOrCreateInitiator("test")
                    .initiateUnchecked(init -> init.to(TERMINATOR_FOR_INITS).send(new DataTO(5d, "five_NI")));
            return new DataTO(msg.number, msg.string + "_replyFromStage5");
        });

        // :: ACT

        DataTO dto = new DataTO(42, "answer");
        StateTO state = new StateTO(12, 34.56);

        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId(MatsTestHelp.traceId())
                .setTraceProperty("init", new DataTO(20, "hjort"))
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

        DetachedProcessContext ctx = result.getContext();
        Assert.assertEquals(new DataTO(20d, "hjort"), ctx.getTraceProperty("init", DataTO.class));
        Assert.assertEquals(new DataTO(10d, "elg"), ctx.getTraceProperty("stage0", DataTO.class));
        Assert.assertEquals(new DataTO(25d, "hund"), ctx.getTraceProperty("stage2", DataTO.class));
        Assert.assertEquals(new DataTO(30d, "hane"), ctx.getTraceProperty("stage3", DataTO.class));

        // Make sure all stage-inits have arrived
        boolean await = initsReceivedLatch.await(30, TimeUnit.SECONDS);
        Assert.assertTrue("Didn't get countdown", await);

        // Create expected-list
        ArrayList<String> expected_inits = new ArrayList<>();
        expected_inits.add("Test_NextDirect.Endpoint#zero");
        expected_inits.add("Test_NextDirect.Endpoint#zero_DI");
        expected_inits.add("Test_NextDirect.Endpoint#zero_NI");
        expected_inits.add("Test_NextDirect.Endpoint.stage1#one");
        expected_inits.add("Test_NextDirect.Endpoint.stage1#one_DI");
        expected_inits.add("Test_NextDirect.Endpoint.stage1#one_NI");
        expected_inits.add("Test_NextDirect.Endpoint.stage2#two");
        expected_inits.add("Test_NextDirect.Endpoint.stage2#two_DI");
        expected_inits.add("Test_NextDirect.Endpoint.stage2#two_NI");
        expected_inits.add("Test_NextDirect.Endpoint.stage3#three");
        expected_inits.add("Test_NextDirect.Endpoint.stage3#three_DI");
        expected_inits.add("Test_NextDirect.Endpoint.stage3#three_NI");
        expected_inits.add("Test_NextDirect.Endpoint.stage4#four");
        expected_inits.add("Test_NextDirect.Endpoint.stage4#four_DI");
        expected_inits.add("Test_NextDirect.Endpoint.stage4#four_NI");
        expected_inits.add("Test_NextDirect.Endpoint.stage5#five");
        expected_inits.add("Test_NextDirect.Endpoint.stage5#five_DI");
        expected_inits.add("Test_NextDirect.Endpoint.stage5#five_NI");

        // Sort received-list
        received_inits.sort(Comparator.naturalOrder());

        log.info("Expected initiations:");
        expected_inits.forEach(log::info);
        log.info("Received initiations:");
        received_inits.forEach(log::info);

        // Assert that we got all the initiations.
        Assert.assertEquals(expected_inits, received_inits);
    }
}
