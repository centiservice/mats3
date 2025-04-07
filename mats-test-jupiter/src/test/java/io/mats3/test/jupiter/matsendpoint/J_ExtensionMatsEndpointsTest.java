package io.mats3.test.jupiter.matsendpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.mats3.MatsInitiator;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.spring.test.SpringInjectRulesAndExtensions;
import io.mats3.test.abstractunit.AbstractMatsTestEndpoint.Result;
import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsEndpoints;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Verifies the behavior of {@link Extension_MatsEndpoints}.
 *
 * @author Kevin Mc Tiernan, 2025-05-02, kevin.mc.tiernan@storebrand.no
 */
class J_ExtensionMatsEndpointsTest {

    private static final String HELLO_ENDPOINT_ID = "HelloEndpoint";
    private static final String HELLO_SEEDED_WITH_STATE = "HelloSeededWithState";
    private static final String TERMINATOR_ENDPOINT = "Terminator";
    private static final String TERMINATOR_WITH_STATE = "TerminatorWithState";

    @RegisterExtension
    public static final Extension_Mats MATS = Extension_Mats.create();

    @Nested
    class UnitTests {

        @RegisterExtension
        public Extension_MatsEndpoints.Endpoint<String, String> _helloEndpoint =
                Extension_MatsEndpoints.createEndpoint(HELLO_ENDPOINT_ID, String.class, String.class)
                        .setMatsFactory(MATS.getMatsFactory())
                        .setProcessLambda((ctx, msg) -> "Hello " + msg);
        @RegisterExtension
        public Extension_MatsEndpoints.EndpointWithState<String, String, String> _helloSeededWithState =
                Extension_MatsEndpoints.createEndpoint(HELLO_SEEDED_WITH_STATE, String.class, String.class, String.class)
                        .setMatsFactory(MATS.getMatsFactory())
                        .setProcessLambda((ctx, msg) -> "Hello " + msg);
        @RegisterExtension
        public Extension_MatsEndpoints.Terminator<String> _terminator =
                Extension_MatsEndpoints.createTerminator(TERMINATOR_ENDPOINT, String.class)
                        .setMatsFactory(MATS.getMatsFactory());
        @RegisterExtension
        public Extension_MatsEndpoints.TerminatorWithState<String, String> _terminatorWithState =
                Extension_MatsEndpoints.createTerminator(TERMINATOR_WITH_STATE, String.class, String.class)
                        .setMatsFactory(MATS.getMatsFactory());

        @Test
        public void endpointTest() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String world = "World";

            // :: Act
            String result = MATS.getMatsFuturizer()
                    .futurizeNonessential("Test", getClass().getSimpleName(), HELLO_ENDPOINT_ID, String.class, world)
                    .thenApply(Reply::get)
                    .get(1, TimeUnit.SECONDS);
            // :: Verify
            Assertions.assertEquals("Hello " + world, result);

            Result<Void, String> epResult = _helloEndpoint.waitForResult();

            Assertions.assertEquals(world, epResult.getData());

            _helloSeededWithState.verifyNotInvoked();
            _terminator.verifyNotInvoked();
            _terminatorWithState.verifyNotInvoked();
        }

        @Test
        public void endpointStateTest() {
            // :: Setup
            String world = "World";

            // :: Act
            MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(HELLO_SEEDED_WITH_STATE)
                    .send(world, "AStateOfSomeSort"));

            // :: Verify
            Result<String, String> epResult = _helloSeededWithState.waitForResult();
            Assertions.assertEquals(world, epResult.getData());
            Assertions.assertEquals("AStateOfSomeSort", epResult.getState());

            _helloEndpoint.verifyNotInvoked();
            _terminator.verifyNotInvoked();
            _terminatorWithState.verifyNotInvoked();
        }

        @Test
        public void terminatorTest() {
            // :: Setup
            String world = "World";

            // :: Act
            MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(TERMINATOR_ENDPOINT)
                    .send(world));

            // :: Verify
            Result<Void, String> epResult = _terminator.waitForResult();
            Assertions.assertEquals(world, epResult.getData());

            _helloEndpoint.verifyNotInvoked();
            _helloSeededWithState.verifyNotInvoked();
            _terminatorWithState.verifyNotInvoked();
        }

        @Test
        public void terminatorStateTest() {
            // :: Setup
            String world = "World";

            // :: Act
            MATS.getMatsInitiator().initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(TERMINATOR_WITH_STATE)
                    .send(world, "AStateOfSomeSort"));

            // :: Verify
            Result<String, String> epResult = _terminatorWithState.waitForResult();
            Assertions.assertEquals(world, epResult.getData());
            Assertions.assertEquals("AStateOfSomeSort", epResult.getState());

            _helloEndpoint.verifyNotInvoked();
            _helloSeededWithState.verifyNotInvoked();
            _terminator.verifyNotInvoked();
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

        @RegisterExtension
        public Extension_MatsEndpoints.Endpoint<String, String> _helloEndpoint =
                Extension_MatsEndpoints.createEndpoint(HELLO_ENDPOINT_ID, String.class, String.class)
                        .setProcessLambda((ctx, msg) -> "Hello " + msg);
        @RegisterExtension
        public Extension_MatsEndpoints.EndpointWithState<String, String, String> _helloSeededWithState =
                Extension_MatsEndpoints.createEndpoint(HELLO_SEEDED_WITH_STATE, String.class, String.class, String.class)
                        .setProcessLambda((ctx, msg) -> "Hello " + msg);
        @RegisterExtension
        public Extension_MatsEndpoints.Terminator<String> _terminator =
                Extension_MatsEndpoints.createTerminator(TERMINATOR_ENDPOINT, String.class);
        @RegisterExtension
        public Extension_MatsEndpoints.TerminatorWithState<String, String> _terminatorWithState =
                Extension_MatsEndpoints.createTerminator(TERMINATOR_WITH_STATE, String.class, String.class);

        @Inject
        private MatsFuturizer _matsFuturizer;
        @Inject
        private MatsInitiator _matsInitiator;

        @Test
        public void endpointTest() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String world = "World";

            // :: Act
            String result = _matsFuturizer
                    .futurizeNonessential("Test", getClass().getSimpleName(), HELLO_ENDPOINT_ID, String.class, world)
                    .thenApply(Reply::get)
                    .get(1, TimeUnit.SECONDS);
            // :: Verify
            Assertions.assertEquals("Hello " + world, result);

            Result<Void, String> epResult = _helloEndpoint.waitForResult();

            Assertions.assertEquals(world, epResult.getData());
        }

        @Test
        public void endpointStateTest() {
            // :: Setup
            String world = "World";

            // :: Act
            _matsInitiator.initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(HELLO_SEEDED_WITH_STATE)
                    .send(world, "AStateOfSomeSort"));

            // :: Verify
            Result<String, String> epResult = _helloSeededWithState.waitForResult();
            Assertions.assertEquals(world, epResult.getData());
            Assertions.assertEquals("AStateOfSomeSort", epResult.getState());
        }

        @Test
        public void terminatorTest() {
            // :: Setup
            String world = "World";

            // :: Act
            _matsInitiator.initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(TERMINATOR_ENDPOINT)
                    .send(world));

            // :: Verify
            Result<Void, String> epResult = _terminator.waitForResult();
            Assertions.assertEquals(world, epResult.getData());
        }

        @Test
        public void terminatorStateTest() {
            // :: Setup
            String world = "World";

            // :: Act
            _matsInitiator.initiateUnchecked(init -> init.traceId("Test")
                    .from(getClass().getSimpleName())
                    .to(TERMINATOR_WITH_STATE)
                    .send(world, "AStateOfSomeSort"));

            // :: Verify
            Result<String, String> epResult = _terminatorWithState.waitForResult();
            Assertions.assertEquals(world, epResult.getData());
            Assertions.assertEquals("AStateOfSomeSort", epResult.getState());
        }
    }
}
