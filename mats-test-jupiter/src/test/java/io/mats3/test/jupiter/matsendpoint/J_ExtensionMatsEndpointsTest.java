package io.mats3.test.jupiter.matsendpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.mats3.MatsInitiator;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.spring.test.SpringInjectRulesAndExtensions;
import io.mats3.test.MatsTestEndpoint;
import io.mats3.test.MatsTestEndpoint.Message;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsTestEndpoints;
import io.mats3.test.jupiter.Extension_MatsTestEndpoints.Endpoint;
import io.mats3.test.jupiter.Extension_MatsTestEndpoints.EndpointWithState;
import io.mats3.test.jupiter.Extension_MatsTestEndpoints.Terminator;
import io.mats3.test.jupiter.Extension_MatsTestEndpoints.TerminatorWithState;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Verifies the behavior of {@link Extension_MatsTestEndpoints}.
 *
 * @author Kevin Mc Tiernan, 2025-05-02, kevin.mc.tiernan@storebrand.no
 */
public class J_ExtensionMatsEndpointsTest {

    private static final String ENDPOINT = "HelloEndpoint";
    private static final String ENDPOINT_WITH_STATE = "HelloSeededWithState";
    private static final String TERMINATOR = "Terminator";
    private static final String TERMINATOR_WITH_STATE = "TerminatorWithState";

    private static final String TERMINATOR_FOR_TEST = "TerminatorForTest";

    private static final String ENDPOINT_NPL = "HelloEndpoint_NPL";
    private static final String ENDPOINT_WITH_STATE_NPL = "HelloSeededWithState_NPL";
    private static final String TERMINATOR_NPL = "Terminator_NPL";
    private static final String TERMINATOR_WITH_STATE_NPL = "TerminatorWithState_NPL";

    private static final String ENDPOINT_SPRING = "HelloEndpoint_Spring";
    private static final String ENDPOINT_WITH_STATE_SPRING = "HelloSeededWithState_Spring";
    private static final String TERMINATOR_SPRING = "Terminator_Spring";
    private static final String TERMINATOR_WITH_STATE_SPRING = "TerminatorWithState_Spring";

    @RegisterExtension
    public static final Extension_Mats MATS = Extension_Mats.create();

    @Nested
    class PureJavaTests {

        private MatsTestLatch _matsTestLatch = MATS.getMatsTestLatch();

        // ==================================================================================
        // :: SETUP: The four types of endpoints we're testing
        // ==================================================================================

        @RegisterExtension
        public Endpoint<ReplyDTO, RequestDTO> _endpoint = Extension_MatsTestEndpoints
                .createEndpoint(ENDPOINT, ReplyDTO.class, RequestDTO.class)
                .setMatsFactory(MATS.getMatsFactory()) // Set MatsFactory explicitly, just to show how that works.
                .setProcessLambda((ctx, msg) -> {
                    _matsTestLatch.resolve(ctx, null, msg);
                    return new ReplyDTO("Hello 1 " + msg.request);
                });
        @RegisterExtension
        public EndpointWithState<ReplyDTO, StateSTO, RequestDTO> _endpointWithState = Extension_MatsTestEndpoints
                .createEndpoint(MATS, ENDPOINT_WITH_STATE, ReplyDTO.class, StateSTO.class, RequestDTO.class);
        // Set ProcessLambda in test.

        @RegisterExtension
        public Terminator<RequestDTO> _terminator = Extension_MatsTestEndpoints
                .createTerminator(MATS, TERMINATOR, RequestDTO.class)
                .setProcessLambda((ctx, msg) -> _matsTestLatch.resolve(ctx, null, msg));
        @RegisterExtension
        public TerminatorWithState<StateSTO, RequestDTO> _terminatorWithState = Extension_MatsTestEndpoints
                .createTerminator(MATS, TERMINATOR_WITH_STATE, StateSTO.class, RequestDTO.class);
        // Set ProcessLambda in test.

        // ------------------------------------------------------------------------------------
        // :: Terminator for test - used to verify that endpoint '_helloEndpoint' replies!
        // (i.e. a bona fide use of this rule, not to test it!)
        @RegisterExtension
        public TerminatorWithState<StateSTO, ReplyDTO> _terminatorForTest = Extension_MatsTestEndpoints
                .createTerminator(MATS, TERMINATOR_FOR_TEST, StateSTO.class, ReplyDTO.class);

        // =======================================================================================
        // :: SETUP: Not having set Process Lambda will throw upon await, unless Terminators
        // =======================================================================================

        @RegisterExtension
        public Endpoint<ReplyDTO, RequestDTO> _helloEndpoint_npl = Extension_MatsTestEndpoints
                .createEndpoint(MATS, ENDPOINT_NPL, ReplyDTO.class, RequestDTO.class);
        @RegisterExtension
        public EndpointWithState<ReplyDTO, StateSTO, RequestDTO> _helloEndpointWithState_npl = Extension_MatsTestEndpoints
                .createEndpoint(MATS, ENDPOINT_WITH_STATE_NPL, ReplyDTO.class, StateSTO.class, RequestDTO.class);
        @RegisterExtension
        public Terminator<RequestDTO> _terminator_npl = Extension_MatsTestEndpoints
                .createTerminator(MATS, TERMINATOR_NPL, RequestDTO.class);
        @RegisterExtension
        public TerminatorWithState<StateSTO, RequestDTO> _terminatorWithState_npl = Extension_MatsTestEndpoints
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
            Assertions.assertEquals("Hello 1 World 1", replyDto.reply);

            Message<Void, RequestDTO> epMessage = _endpoint.awaitInvocation();
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);

            // Do the latch check after the Test endpoint has been verified - it should have been resolved.
            Result<Void, RequestDTO> latched = _matsTestLatch.waitForResult();
            Assertions.assertEquals(worldRequest.request, latched.getData().request);
            Assertions.assertNull(latched.getState());
            Assertions.assertNotNull(latched.getContext());

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
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);
            Assertions.assertEquals(initialState.state, epMessage.getState().state);

            // Verify that the endpoint sent a reply to the terminator.
            Message<StateSTO, ReplyDTO> terminatorMessage = _terminatorForTest.awaitInvocation();
            Assertions.assertEquals("Hello 2 World 2", terminatorMessage.getData().reply);
            Assertions.assertEquals(terminatorState.state, terminatorMessage.getState().state);

            // Do the latch check after the Test endpoint has been verified - it should obviously have been resolved.
            Result<StateSTO, RequestDTO> latched = _matsTestLatch.waitForResult();
            Assertions.assertEquals(worldRequest.request, latched.getData().request);
            Assertions.assertEquals(initialState.state, latched.getState().state);
            Assertions.assertNotNull(latched.getContext());

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
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);

            // Do the latch check after the Test endpoint has been verified - it should have been resolved.
            Result<Void, RequestDTO> latched = _matsTestLatch.waitForResult();
            Assertions.assertEquals(worldRequest.request, latched.getData().request);
            Assertions.assertNull(latched.getState());
            Assertions.assertNotNull(latched.getContext());

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
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);
            Assertions.assertEquals(initialState.state, epMessage.getState().state);

            // Do the latch check after the Test endpoint has been verified - it should have been resolved.
            Result<StateSTO, RequestDTO> latched = _matsTestLatch.waitForResult();
            Assertions.assertEquals(worldRequest.request, latched.getData().request);
            Assertions.assertEquals(initialState.state, latched.getState().state);
            Assertions.assertNotNull(latched.getContext());

            _endpoint.verifyNotInvoked();
            _endpointWithState.verifyNotInvoked();
            _terminator.verifyNotInvoked();
        }

        // ===============================================================================================================
        // :: Tests for no process lambda - Endpoints should throw when awaiting invocation when missing process lambda.
        // ===============================================================================================================

        @Test
        public void endpointTest_no_process_lambda() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Verify
            Assertions.assertThrows(IllegalStateException.class, () -> {
                _helloEndpoint_npl.awaitInvocation();
            });
        }

        @Test
        public void endpointWithStateTest_no_process_lambda() throws ExecutionException, InterruptedException,
                TimeoutException {
            // :: Verify
            Assertions.assertThrows(IllegalStateException.class, () -> {
                _helloEndpointWithState_npl.awaitInvocation();
            });
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
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);
            Assertions.assertNull(epMessage.getState());
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
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);
            Assertions.assertEquals(initialState.state, epMessage.getState().state);
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
            endpointTest_process_lambda_throws_generic(_terminatorWithState, TERMINATOR_WITH_STATE,
                    "throwing lambda 4");
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
                Assertions.fail("Expected AssertionError not thrown!");
            }
            catch (AssertionError e) {
                Assertions.assertTrue(e.getMessage().contains(throwMessage),
                        "Expected lambda-thrown message to be in AssertionError");
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

        public void endpointTest_noMessage(MatsTestEndpoint<?, ?, ?> ep, String epid) {
            // :: Setup
            // Set any process lambda, so we don't get an exception for that.
            _endpoint.setProcessLambda((ctx, msg) -> null);

            // :: Act & Verify
            _endpoint.verifyNotInvoked();
        }
    }

    /**
     * Verifies correct Spring wiring.
     */
    @Nested
    @SpringInjectRulesAndExtensions
    @ExtendWith(SpringExtension.class)
    @MatsTestContext
    class SpringTests {

        private MatsTestLatch _matsTestLatch = new MatsTestLatch();

        // Note: MatsFactory is now set by @SpringInjectRulesAndExtensions, so we don't need to set it explicitly.

        @RegisterExtension
        public Endpoint<ReplyDTO, RequestDTO> _helloEndpoint = Extension_MatsTestEndpoints
                .createEndpoint(ENDPOINT_SPRING, ReplyDTO.class, RequestDTO.class)
                .setProcessLambda((ctx, msg) -> {
                    _matsTestLatch.resolve(ctx, null, msg);
                    return new ReplyDTO("Hello 1 " + msg.request);
                });
        @RegisterExtension
        public EndpointWithState<ReplyDTO, StateSTO, RequestDTO> _helloEndpointWithState = Extension_MatsTestEndpoints
                .createEndpoint(ENDPOINT_WITH_STATE_SPRING, ReplyDTO.class, StateSTO.class, RequestDTO.class);
        // Set ProcessLambda in test.

        @RegisterExtension
        public Terminator<RequestDTO> _terminator = Extension_MatsTestEndpoints
                .createTerminator(TERMINATOR_SPRING, RequestDTO.class)
                .setProcessLambda((ctx, msg) -> _matsTestLatch.resolve(ctx, null, msg));
        @RegisterExtension
        public TerminatorWithState<StateSTO, RequestDTO> _terminatorWithState = Extension_MatsTestEndpoints
                .createTerminator(TERMINATOR_WITH_STATE_SPRING, StateSTO.class, RequestDTO.class);
        // Set ProcessLambda in test.

        // ------------------------------------------------------------------------------------
        // :: Terminator for test - used to verify that endpoint '_helloEndpoint' replies!
        // (i.e. a bona fide use of this rule, not to test it!)
        @RegisterExtension
        public TerminatorWithState<StateSTO, ReplyDTO> _terminatorForTest = Extension_MatsTestEndpoints
                .createTerminator(TERMINATOR_FOR_TEST, StateSTO.class, ReplyDTO.class);

        @Inject
        private MatsFuturizer _matsFuturizer;
        @Inject
        private MatsInitiator _matsInitiator;

        @Test
        public void endpointTest() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            RequestDTO worldRequest = new RequestDTO("World 1");

            // :: Act
            // Using Futurizer to send a message to the endpoint, and receive reply.
            ReplyDTO replyDto = _matsFuturizer
                    .futurizeNonessential("Test", getClass().getSimpleName(), ENDPOINT_SPRING, ReplyDTO.class,
                            worldRequest)
                    .thenApply(Reply::get)
                    .get(30, TimeUnit.SECONDS);
            // :: Verify
            Assertions.assertEquals("Hello 1 World 1", replyDto.reply);

            Message<Void, RequestDTO> epMessage = _helloEndpoint.awaitInvocation();
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);

            // Do the latch check after the Test endpoint has been verified - it should have been resolved.
            Result<Void, RequestDTO> latched = _matsTestLatch.waitForResult();
            Assertions.assertEquals(worldRequest.request, latched.getData().request);
            Assertions.assertNull(latched.getState());
            Assertions.assertNotNull(latched.getContext());

            _helloEndpointWithState.verifyNotInvoked();
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
            _helloEndpointWithState.setProcessLambda((ctx, state, msg) -> {
                _matsTestLatch.resolve(ctx, state, msg);
                return new ReplyDTO("Hello 2 " + msg.request);
            });

            // :: Act
            // Using ordinary request-replyTo to send a message to the endpoint, and receive reply.
            // This because the Futurizer cannot be used with initial state.
            _matsInitiator.initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(ENDPOINT_WITH_STATE_SPRING)
                    .replyTo(TERMINATOR_FOR_TEST, terminatorState)
                    .request(worldRequest, initialState));

            // :: Verify
            Message<StateSTO, RequestDTO> epMessage = _helloEndpointWithState.awaitInvocation();
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);
            Assertions.assertEquals(initialState.state, epMessage.getState().state);

            // Verify that the endpoint sent a reply to the terminator.
            Message<StateSTO, ReplyDTO> terminatorMessage = _terminatorForTest.awaitInvocation();
            Assertions.assertEquals("Hello 2 World 2", terminatorMessage.getData().reply);
            Assertions.assertEquals(terminatorState.state, terminatorMessage.getState().state);

            // Do the latch check after the Test endpoint has been verified - it should obviously have been resolved.
            Result<StateSTO, RequestDTO> latched = _matsTestLatch.waitForResult();
            Assertions.assertEquals(worldRequest.request, latched.getData().request);
            Assertions.assertEquals(initialState.state, latched.getState().state);
            Assertions.assertNotNull(latched.getContext());

            _helloEndpoint.verifyNotInvoked();
            _terminator.verifyNotInvoked();
            _terminatorWithState.verifyNotInvoked();
        }

        @Test
        public void terminatorTest() {
            // :: Setup
            RequestDTO worldRequest = new RequestDTO("World 3");

            // :: Act
            _matsInitiator.initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(TERMINATOR_SPRING)
                    .send(worldRequest));

            // :: Verify
            Message<Void, RequestDTO> epMessage = _terminator.awaitInvocation();
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);

            // Do the latch check after the Test endpoint has been verified - it should have been resolved.
            Result<Void, RequestDTO> latched = _matsTestLatch.waitForResult();
            Assertions.assertEquals(worldRequest.request, latched.getData().request);
            Assertions.assertNull(latched.getState());
            Assertions.assertNotNull(latched.getContext());

            _helloEndpoint.verifyNotInvoked();
            _helloEndpointWithState.verifyNotInvoked();
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
            _matsInitiator.initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(TERMINATOR_WITH_STATE_SPRING)
                    .send(worldRequest, initialState));

            // :: Verify
            Message<StateSTO, RequestDTO> epMessage = _terminatorWithState.awaitInvocation();
            Assertions.assertEquals(worldRequest.request, epMessage.getData().request);
            Assertions.assertEquals(initialState.state, epMessage.getState().state);

            // Do the latch check after the Test endpoint has been verified - it should have been resolved.
            Result<StateSTO, RequestDTO> latched = _matsTestLatch.waitForResult();
            Assertions.assertEquals(worldRequest.request, latched.getData().request);
            Assertions.assertEquals(initialState.state, latched.getState().state);
            Assertions.assertNotNull(latched.getContext());

            _helloEndpoint.verifyNotInvoked();
            _helloEndpointWithState.verifyNotInvoked();
            _terminator.verifyNotInvoked();
        }
    }

    // :: Reply, State, and Request DTOs used in the tests.

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
