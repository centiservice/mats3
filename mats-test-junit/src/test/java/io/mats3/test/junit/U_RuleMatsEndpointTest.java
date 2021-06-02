package io.mats3.test.junit;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.util.MatsFuturizer.Reply;

/**
 * Illustrate some of the features of {@link Rule_MatsEndpoint}.
 * <p>
 * Also illustrates the usage of {@link Rule_MatsEndpoint} in combination with {@link Rule_Mats}.
 *
 * @author Kevin Mc Tiernan, 2020-11-09, kmctiernan@gmail.com
 */
public class U_RuleMatsEndpointTest {

    public static final String HELLO_ENDPOINT_ID = "HelloEndpoint";

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Rule
    public Rule_MatsEndpoint<String, String> _helloEndpoint = Rule_MatsEndpoint
            .create(MATS, HELLO_ENDPOINT_ID, String.class, String.class);

    /**
     * Shows that when no processor is defined, an endpoint will not produce a reply.
     */
    @Test
    public void noProcessorDefined() {
        Throwable throwExpected = null;

        // :: Send a message to the endpoint - This will timeout, thus wrap it in a try-catch.
        try {
            MATS.getMatsFuturizer().futurizeNonessential(getClass().getSimpleName() + "|noProcessorDefined",
                    getClass().getSimpleName(), HELLO_ENDPOINT_ID, String.class, "World")
                    .get(1, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            throwExpected = e;
        }
        catch (InterruptedException | ExecutionException e) {
            throw new AssertionError("Expected timeout exception, not this!", e);
        }

        // :: Assert that we got the TimeoutException we expected
        Assert.assertNotNull(throwExpected);
        Assert.assertEquals(TimeoutException.class, throwExpected.getClass());

        // ----- At this point the above block has timed out.

        // :: Assert that the endpoint actually got the message
        String incomingMsgToTheEndpoint = _helloEndpoint.waitForRequests(1).get(0);
        Assert.assertEquals("World", incomingMsgToTheEndpoint);
    }

    /**
     * Executes two calls to the same "mock" endpoint where the processor logic of the endpoint is changed mid test to
     * verify that this feature works as expected.
     */
    @Test
    public void changeProcessorMidTestTest() throws InterruptedException, ExecutionException, TimeoutException {
        String expectedReturn = "Hello World!";
        String secondExpectedReturn = "Hello Wonderful World!";

        // :: First Setup
        _helloEndpoint.setProcessLambda((ctx, msg) -> msg + " World!");

        // :: First Act
        String firstReply = MATS.getMatsFuturizer().futurizeNonessential(
                getClass().getSimpleName() + "_changeProcessorMidTestTest",
                        getClass().getSimpleName(),
                        HELLO_ENDPOINT_ID,
                        String.class,
                        "Hello")
                        .thenApply(Reply::getReply)
                        .get(10, TimeUnit.SECONDS);

        // :: First Verify
        Assert.assertEquals(expectedReturn, firstReply);

        // :: Second Setup
        _helloEndpoint.setProcessLambda((ctx, msg) -> msg + " Wonderful World!");

        // :: Second Act
        String secondReply = MATS.getMatsFuturizer().futurizeNonessential(
                getClass().getSimpleName() + "_changeProcessorMidTestTest",
                getClass().getSimpleName(),
                HELLO_ENDPOINT_ID,
                String.class,
                "Hello")
                .thenApply(Reply::getReply)
                .get(10, TimeUnit.SECONDS);

        // :: Final verify
        Assert.assertEquals(secondExpectedReturn, secondReply);
    }
}
