package io.mats3.test.junit.matsendpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.test.abstractunit.AbstractMatsTestEndpoint.Result;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsEndpoints;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Illustrate features {@link Rule_MatsEndpoints} and some simple usage scenarios.
 *
 * @author Kevin Mc Tiernan, 2025-04-23, kevin.mc.tiernan@storebrand.no
 */
public class U_RuleMatsEndpointsTest {

    private static final String HELLO_ENDPOINT_ID = "HelloEndpoint";
    private static final String HELLO_SEEDED_WITH_STATE = "HelloSeededWithState";
    private static final String TERMINATOR_ENDPOINT = "Terminator";
    private static final String TERMINATOR_WITH_STATE = "TerminatorWithState";

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Rule
    public Rule_MatsEndpoints.Endpoint<String, String> _helloEndpoint =
            Rule_MatsEndpoints.createEndpoint(HELLO_ENDPOINT_ID, String.class, String.class)
                    .setMatsFactory(MATS.getMatsFactory())
                    .setProcessLambda((ctx, msg) -> "Hello " + msg);
    @Rule
    public Rule_MatsEndpoints.EndpointWithState<String, String, String> _helloSeededWithState =
            Rule_MatsEndpoints.createEndpoint(HELLO_SEEDED_WITH_STATE, String.class, String.class, String.class)
                    .setMatsFactory(MATS.getMatsFactory())
                    .setProcessLambda((ctx, msg) -> "Hello " + msg);
    @Rule
    public Rule_MatsEndpoints.Terminator<String> _terminator =
            Rule_MatsEndpoints.createTerminator(TERMINATOR_ENDPOINT, String.class)
                    .setMatsFactory(MATS.getMatsFactory());
    @Rule
    public Rule_MatsEndpoints.TerminatorWithState<String, String> _terminatorWithState =
            Rule_MatsEndpoints.createTerminator(TERMINATOR_WITH_STATE, String.class, String.class)
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
        Assert.assertEquals("Hello " + world, result);

        Result<Void, String> epResult = _helloEndpoint.waitForResult();
        Assert.assertEquals(world, epResult.getData());

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
        Assert.assertEquals(world, epResult.getData());
        Assert.assertEquals("AStateOfSomeSort", epResult.getState());

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
        Assert.assertEquals(world, epResult.getData());

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
        Assert.assertEquals(world, epResult.getData());
        Assert.assertEquals("AStateOfSomeSort", epResult.getState());

        _helloEndpoint.verifyNotInvoked();
        _helloSeededWithState.verifyNotInvoked();
        _terminator.verifyNotInvoked();
    }
}
