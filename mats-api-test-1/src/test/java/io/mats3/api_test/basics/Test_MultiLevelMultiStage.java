package io.mats3.api_test.basics;

import io.mats3.MatsEndpoint;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

/**
 * Sets up a somewhat complex test scenario, testing the basic state keeping and request-reply passing.
 * <p>
 * Sets up these services:
 * <ul>
 * <li>Leaf service: Single stage: Replies directly.
 * <li>Mid service: Two stages: Requests "Leaf" service, then replies.
 * <li>Master service: Three stages: First requests "Mid" service, then requests "Leaf" service, then replies.
 * </ul>
 * A Terminator is also set up, and then the initiator sends a request to "Master", setting replyTo(Terminator).
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]              - init request
 *     [Master S0 - init]   - request
 *         [Mid S0 - init]  - request
 *             [Leaf]       - reply
 *         [Mid S1 - last]  - reply
 *     [Master S1]          - request
 *         [Leaf]           - reply
 *     [Master S2 - last]   - reply
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2015-07-31 - http://endre.stolsvik.com
 */
public class Test_MultiLevelMultiStage {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT_MAIN = MatsTestHelp.endpoint("MAIN");
    private static final String ENDPOINT_MID = MatsTestHelp.endpoint("MID");
    private static final String ENDPOINT_LEAF = MatsTestHelp.endpoint("LEAF");
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupLeafService() {
        // Create single-stage "Leaf" endpoint. Single stage, thus the processor is defined directly.
        MATS.getMatsFactory().single(ENDPOINT_LEAF, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Returns a Reply to the calling service with a alteration of incoming message
                    return new DataTO(dto.number * 2, dto.string + ":FromLeafService");
                });
    }

    @BeforeClass
    public static void setupMidMultiStagedService() {
        // Create two-stage "Mid" endpoint
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory()
                .staged(ENDPOINT_MID, DataTO.class, StateTO.class);

        // Initial stage, receives incoming message to this "Mid" service
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // State object is "empty" at initial stage.
            Assert.assertEquals(0, sto.number1);
            Assert.assertEquals(0, sto.number2, 0);
            // Setting state some variables.
            sto.number1 = 10;
            sto.number2 = Math.PI;
            // Perform request to "Leaf" Service...
            context.request(ENDPOINT_LEAF, dto);
        });

        // Next, and last, stage, receives replies from the "Leaf" service, and returns a Reply
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            // .. "continuing" after the "Leaf" Service has replied.
            // Assert that state variables set in previous stage are still with us.
            Assert.assertEquals(new StateTO(10, Math.PI), sto);
            // Returning Reply to calling service.
            return new DataTO(dto.number * 3, dto.string + ":FromMidService");
        });
    }

    @BeforeClass
    public static void setupMainMultiStagedService() {
        // Create three-stage "Main" endpoint
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory()
                .staged(ENDPOINT_MAIN, DataTO.class, StateTO.class);

        // Initial stage, receives incoming message to this "Main" service
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // State object is "empty" at initial stage.
            Assert.assertEquals(0, sto.number1);
            Assert.assertEquals(0, sto.number2, 0);
            // Setting state some variables.
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            // Perform request to "Mid" Service...
            context.request(ENDPOINT_MID, dto);
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // .. "continuing" after the "Mid" Service has replied.
            // Assert that state variables set in previous stage are still with us.
            Assert.assertEquals(Integer.MAX_VALUE, sto.number1);
            Assert.assertEquals(Math.E, sto.number2, 0);
            // Changing the state variables.
            sto.number1 = Integer.MIN_VALUE;
            sto.number2 = Math.E * 2;
            // Perform request to "Leaf" Service...
            context.request(ENDPOINT_LEAF, dto);
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            // .. "continuing" after the "Leaf" Service has replied.
            // Assert that state variables changed in previous stage are still with us.
            Assert.assertEquals(Integer.MIN_VALUE, sto.number1);
            Assert.assertEquals(Math.E * 2, sto.number2, 0);
            // Returning Reply to "caller"
            // (in this test it will be what the initiation specified as replyTo; "Terminator")
            return new DataTO(dto.number * 5, dto.string + ":FromMainService");
        });
    }

    @BeforeClass
    public static void setupTerminator() {
        // A "Terminator" is a service which does not reply, i.e. it "consumes" any incoming messages.
        // However, in this test, it resolves the test-latch, so that the main test thread can assert.
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    @Test
    public void doTest() {
        // :: Arrange
        // State object for "Terminator".
        StateTO sto = new StateTO((int) Math.round(123 * Math.random()), 321 * Math.random());
        // Request object to "Main" Service.
        DataTO dto = new DataTO(42 + Math.random(), "TheRequest:" + Math.random());

        // :: Act
        // Perform the Request to "Main", setting the replyTo to "Terminator".
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT_MAIN)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        // NOTE: Such synchronous wait is not a typical Mats flow!
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();

        // :: Assert
        // Assert that the State to the "Terminator" was what we wanted him to get
        Assert.assertEquals(sto, result.getState());
        // Assert that the Mats flow has gone through the stages, being modified as it went along
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 2 * 5,
                        dto.string + ":FromLeafService" + ":FromMidService"
                                + ":FromLeafService" + ":FromMainService"),
                result.getData());
    }
}