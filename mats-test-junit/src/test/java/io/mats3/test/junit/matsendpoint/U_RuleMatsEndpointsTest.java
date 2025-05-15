package io.mats3.test.junit.matsendpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.test.MatsTestEndpoint;
import io.mats3.test.MatsTestEndpoint.Message;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsTestEndpoints;
import io.mats3.test.junit.Rule_MatsTestEndpoints.Endpoint;
import io.mats3.test.junit.Rule_MatsTestEndpoints.EndpointWithState;
import io.mats3.test.junit.Rule_MatsTestEndpoints.Terminator;
import io.mats3.test.junit.Rule_MatsTestEndpoints.TerminatorWithState;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Tests all of the Junit 4 variants of {@link MatsTestEndpoint} with some simple usage scenarios.
 *
 * @author Kevin Mc Tiernan, 2025-04-23, kevin.mc.tiernan@storebrand.no
 * @author Endre Stølsvik 2025-05-11 - http://stolsvik.com/, endre@stolsvik.com
 */
public class U_RuleMatsEndpointsTest {

    private static final String ENDPOINT = "HelloEndpoint";
    private static final String ENDPOINT_WITH_STATE = "HelloEndpointWithState";
    private static final String TERMINATOR = "Terminator";
    private static final String TERMINATOR_WITH_STATE = "TerminatorWithState";

    private static final String TERMINATOR_FOR_TEST = "TerminatorForTest";

    private static final String ENDPOINT_NPL = "HelloEndpoint_NPL";
    private static final String ENDPOINT_WITH_STATE_NPL = "HelloEndpointWithState_NPL";
    private static final String TERMINATOR_NPL = "Terminator_NPL";
    private static final String TERMINATOR_WITH_STATE_NPL = "TerminatorWithState_NPL";

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private MatsTestLatch _matsTestLatch = MATS.getMatsTestLatch();

    // ==================================================================================
    // :: SETUP: The four types of endpoints we're testing
    // ==================================================================================

    @Rule
    public Endpoint<ReplyDTO, RequestDTO> _endpoint = Rule_MatsTestEndpoints
            .createEndpoint(ENDPOINT, ReplyDTO.class, RequestDTO.class)
            .setMatsFactory(MATS.getMatsFactory()) // Set MatsFactory explicitly, just to show how that works.
            .setProcessLambda((ctx, msg) -> {
                _matsTestLatch.resolve(ctx, null, msg);
                return new ReplyDTO("Hello 1 " + msg.request);
            });
    @Rule
    public EndpointWithState<ReplyDTO, StateSTO, RequestDTO> _endpointWithState = Rule_MatsTestEndpoints
            .createEndpoint(MATS, ENDPOINT_WITH_STATE, ReplyDTO.class, StateSTO.class, RequestDTO.class);
    // Set ProcessLambda in test.

    @Rule
    public Terminator<RequestDTO> _terminator = Rule_MatsTestEndpoints
            .createTerminator(MATS, TERMINATOR, RequestDTO.class)
            .setProcessLambda((ctx, msg) -> _matsTestLatch.resolve(ctx, null, msg));
    @Rule
    public TerminatorWithState<StateSTO, RequestDTO> _terminatorWithState = Rule_MatsTestEndpoints
            .createTerminator(MATS, TERMINATOR_WITH_STATE, StateSTO.class, RequestDTO.class);
    // Set ProcessLambda in test.

    // ------------------------------------------------------------------------------------
    // :: Terminator for test - used to verify that endpoint '_helloEndpoint' replies!
    // (i.e. a bona fide use of this rule, not to test it!)
    @Rule
    public TerminatorWithState<StateSTO, ReplyDTO> _terminatorForTest = Rule_MatsTestEndpoints
            .createTerminator(MATS, TERMINATOR_FOR_TEST, StateSTO.class, ReplyDTO.class);

    // =======================================================================================
    // :: SETUP: Not having set Process Lambda will throw upon await, unless Terminators
    // =======================================================================================

    @Rule
    public Endpoint<ReplyDTO, RequestDTO> _endpoint_npl = Rule_MatsTestEndpoints
            .createEndpoint(MATS, ENDPOINT_NPL, ReplyDTO.class, RequestDTO.class);
    @Rule
    public EndpointWithState<ReplyDTO, StateSTO, RequestDTO> _endpointWithState_npl = Rule_MatsTestEndpoints
            .createEndpoint(MATS, ENDPOINT_WITH_STATE_NPL, ReplyDTO.class, StateSTO.class, RequestDTO.class);
    @Rule
    public Terminator<RequestDTO> _terminator_npl = Rule_MatsTestEndpoints
            .createTerminator(MATS, TERMINATOR_NPL, RequestDTO.class);
    @Rule
    public TerminatorWithState<StateSTO, RequestDTO> _terminatorWithState_npl = Rule_MatsTestEndpoints
            .createTerminator(MATS, TERMINATOR_WITH_STATE_NPL, StateSTO.class, RequestDTO.class);

    // ===============================================================================================================
    // :: TESTS: Ordinary functionality
    // ===============================================================================================================

    @Test
    public void endpointTest() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        RequestDTO worldRequest = new RequestDTO("World 1");

        // :: Act
        // Using Futurizer to send a message to the endpoint, and receive reply.
        ReplyDTO replyDto = MATS.getMatsFuturizer()
                .futurizeNonessential("Test", getClass().getSimpleName(), ENDPOINT, ReplyDTO.class, worldRequest)
                .thenApply(Reply::get)
                .get(30, TimeUnit.SECONDS);
        // :: Verify
        Assert.assertEquals("Hello 1 World 1", replyDto.reply);

        Message<Void, RequestDTO> epMessage = _endpoint.awaitInvocation();
        Assert.assertEquals(worldRequest.request, epMessage.getData().request);

        // Do the latch check after the Test endpoint has been verified - it should have been resolved.
        Result<Void, RequestDTO> latched = _matsTestLatch.waitForResult();
        Assert.assertEquals(worldRequest.request, latched.getData().request);
        Assert.assertNull(latched.getState());
        Assert.assertNotNull(latched.getContext());

        _endpointWithState.verifyNotInvoked();
        _terminator.verifyNotInvoked();
        _terminatorWithState.verifyNotInvoked();
    }

    @Test
    public void endpointWithStateTest() {
        // :: Setup
        RequestDTO worldRequest = new RequestDTO("World 2");
        StateSTO initialState = new StateSTO("State for 2");

        StateSTO terminatorState = new StateSTO("TerminatorState");

        // Set the process lambda to be used by the endpoint.
        _endpointWithState.setProcessLambda((ctx, state, msg) -> {
            _matsTestLatch.resolve(ctx, state, msg);
            return new ReplyDTO("Hello 2 " + msg.request);
        });

        // :: Act
        // Using ordinary request-replyTo to send a message to the endpoint, and receive reply.
        // This because the Futurizer cannot be used with initial state.
        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                .from(getClass().getSimpleName())
                .to(ENDPOINT_WITH_STATE)
                .replyTo(TERMINATOR_FOR_TEST, terminatorState)
                .request(worldRequest, initialState));

        // :: Verify
        Message<StateSTO, RequestDTO> epMessage = _endpointWithState.awaitInvocation();
        Assert.assertEquals(worldRequest.request, epMessage.getData().request);
        Assert.assertEquals(initialState.state, epMessage.getState().state);

        // Verify that the endpoint sent a reply to the terminator.
        Message<StateSTO, ReplyDTO> terminatorMessage = _terminatorForTest.awaitInvocation();
        Assert.assertEquals("Hello 2 World 2", terminatorMessage.getData().reply);
        Assert.assertEquals(terminatorState.state, terminatorMessage.getState().state);

        // Do the latch check after the Test endpoint has been verified - it should obviously have been resolved.
        Result<StateSTO, RequestDTO> latched = _matsTestLatch.waitForResult();
        Assert.assertEquals(worldRequest.request, latched.getData().request);
        Assert.assertEquals(initialState.state, latched.getState().state);
        Assert.assertNotNull(latched.getContext());

        _endpoint.verifyNotInvoked();
        _terminator.verifyNotInvoked();
        _terminatorWithState.verifyNotInvoked();
    }

    @Test
    public void terminatorTest() {
        // :: Setup
        RequestDTO worldRequest = new RequestDTO("World 3");

        // :: Act
        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                .from(getClass().getSimpleName())
                .to(TERMINATOR)
                .send(worldRequest));

        // :: Verify
        Message<Void, RequestDTO> epMessage = _terminator.awaitInvocation();
        Assert.assertEquals(worldRequest.request, epMessage.getData().request);

        // Do the latch check after the Test endpoint has been verified - it should have been resolved.
        Result<Void, RequestDTO> latched = _matsTestLatch.waitForResult();
        Assert.assertEquals(worldRequest.request, latched.getData().request);
        Assert.assertNull(latched.getState());
        Assert.assertNotNull(latched.getContext());

        _endpoint.verifyNotInvoked();
        _endpointWithState.verifyNotInvoked();
        _terminatorWithState.verifyNotInvoked();
    }

    @Test
    public void terminatorWithStateTest() {
        // :: Setup
        RequestDTO worldRequest = new RequestDTO("World 4");
        StateSTO initialState = new StateSTO("State for 4");

        // Set the process lambda to be used by the terminator.
        _terminatorWithState.setProcessLambda((ctx, state, msg) -> _matsTestLatch.resolve(ctx, state, msg));

        // :: Act
        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                .from(getClass().getSimpleName())
                .to(TERMINATOR_WITH_STATE)
                .send(worldRequest, initialState));

        // :: Verify
        Message<StateSTO, RequestDTO> epMessage = _terminatorWithState.awaitInvocation();
        Assert.assertEquals(worldRequest.request, epMessage.getData().request);
        Assert.assertEquals(initialState.state, epMessage.getState().state);

        // Do the latch check after the Test endpoint has been verified - it should have been resolved.
        Result<StateSTO, RequestDTO> latched = _matsTestLatch.waitForResult();
        Assert.assertEquals(worldRequest.request, latched.getData().request);
        Assert.assertEquals(initialState.state, latched.getState().state);
        Assert.assertNotNull(latched.getContext());

        _endpoint.verifyNotInvoked();
        _endpointWithState.verifyNotInvoked();
        _terminator.verifyNotInvoked();
    }

    // ===============================================================================================================
    // :: Tests for no process lambda - Endpoints should throw when awaiting invocation when missing process lambda.
    // ===============================================================================================================

    @Test(expected = IllegalStateException.class)
    public void endpointTest_no_process_lambda() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Verify
        _endpoint_npl.awaitInvocation();
    }

    @Test(expected = IllegalStateException.class)
    public void endpointWithStateTest_no_process_lambda() {
        // :: Verify
        _endpointWithState_npl.awaitInvocation();
    }

    @Test
    public void terminatorTest_no_process_lambda() {
        // :: Setup
        RequestDTO worldRequest = new RequestDTO("World 3 npl");

        // :: Act
        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                .from(getClass().getSimpleName())
                .to(TERMINATOR_NPL)
                .send(worldRequest));

        // :: Verify
        Message<Void, RequestDTO> epMessage = _terminator_npl.awaitInvocation();
        Assert.assertEquals(worldRequest.request, epMessage.getData().request);
        Assert.assertNull(epMessage.getState());
    }

    @Test
    public void terminatorWithStateTest_no_process_lambda() {
        // :: Setup
        RequestDTO worldRequest = new RequestDTO("World 4 npl");
        StateSTO initialState = new StateSTO("State for 4 npl");

        // :: Act
        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                .from(getClass().getSimpleName())
                .to(TERMINATOR_WITH_STATE_NPL)
                .send(worldRequest, initialState));

        // :: Verify
        Message<StateSTO, RequestDTO> epMessage = _terminatorWithState_npl.awaitInvocation();
        Assert.assertEquals(worldRequest.request, epMessage.getData().request);
        Assert.assertEquals(initialState.state, epMessage.getState().state);
    }

    // ===============================================================================================================
    // :: Tests for process lambda that throws
    // ===============================================================================================================

    @Test
    public void endpointTest_process_lambda_throws() {
        endpointTest_process_lambda_throws_generic(_endpoint, ENDPOINT, "throwing lambda 1");
    }

    @Test
    public void endpointWithStateTest_process_lambda_throws() {
        endpointTest_process_lambda_throws_generic(_endpointWithState, ENDPOINT_WITH_STATE, "throwing lambda 2");
    }

    @Test
    public void terminatorTest_process_lambda_throws() {
        endpointTest_process_lambda_throws_generic(_terminator, TERMINATOR, "throwing lambda 3");
    }

    @Test
    public void terminatorWithStateTest_process_lambda_throws() {
        endpointTest_process_lambda_throws_generic(_terminatorWithState, TERMINATOR_WITH_STATE, "throwing lambda 4");
    }

    public void endpointTest_process_lambda_throws_generic(MatsTestEndpoint<?, ?, ?> ep, String epid,
            String throwMessage) {
        // :: Setup
        // Set throwing lambda
        _endpoint.setProcessLambda((ctx, msg) -> {
            throw new RuntimeException(throwMessage);
        });

        // :: Act
        MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                .from(getClass().getSimpleName())
                .to(ENDPOINT)
                .send(new RequestDTO("...")));

        // :: Verify (should throw)
        try {
            _endpoint.awaitInvocation();
            Assert.fail("Expected AssertionError not thrown!");
        }
        catch (AssertionError e) {
            Assert.assertTrue("Expected lambda-thrown message to be in AssertionError",
                    e.getMessage().contains(throwMessage));
        }
    }

    // ===============================================================================================================
    // :: Tests for no message received (very rudimentary)
    // ===============================================================================================================

    @Test
    public void endpointTest_noMessage() {
        endpointTest_noMessage(_endpoint, ENDPOINT);
    }

    @Test
    public void endpointWithStateTest_noMessage() {
        endpointTest_noMessage(_endpointWithState, ENDPOINT_WITH_STATE);
    }

    @Test
    public void terminatorTest_noMessage() {
        endpointTest_noMessage(_terminator, TERMINATOR);
    }

    @Test
    public void terminatorWithStateTest_noMessage() {
        endpointTest_noMessage(_terminatorWithState, TERMINATOR_WITH_STATE);
    }

    public void endpointTest_noMessage(MatsTestEndpoint<?,?,?> ep, String epid) {
        // :: Setup
        // Set any process lambda, so we don't get an exception for that.
        _endpoint.setProcessLambda((ctx, msg) -> null);

        // :: Act & Verify
        _endpoint.verifyNotInvoked();
    }

    // ===============================================================================================================
    // :: Reply, State, and Request DTOs used in the tests.
    // ===============================================================================================================

    private static class ReplyDTO {
        String reply;

        ReplyDTO() {
            // No-args constructor for deserialization.
        }

        ReplyDTO(String reply) {
            this.reply = reply;
        }
    }

    private static class StateSTO {
        String state;

        StateSTO() {
            // No-args constructor for deserialization.
        }

        StateSTO(String state) {
            this.state = state;
        }
    }

    private static class RequestDTO {
        String request;

        RequestDTO() {
            // No-args constructor for deserialization.
        }

        RequestDTO(String request) {
            this.request = request;
        }
    }
}
