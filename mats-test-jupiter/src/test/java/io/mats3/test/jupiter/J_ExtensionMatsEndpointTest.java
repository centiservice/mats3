package io.mats3.test.jupiter;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.mats3.util.MatsFuturizer.Reply;

/**
 * Illustrate some of the features of {@link Extension_MatsEndpoint}.
 * <p>
 * Also illustrates the usage of {@link Extension_MatsEndpoint} in combination with {@link Extension_Mats}.
 *
 * @author Kevin Mc Tiernan, 2020-10-20, kmctiernan@gmail.com
 */
public class J_ExtensionMatsEndpointTest {

    public static final String HELLO_ENDPOINT_ID = "HelloEndpoint";

    @RegisterExtension
    public static final Extension_Mats MATS = Extension_Mats.create();

    @RegisterExtension
    public final Extension_MatsEndpoint<String, String> _helloEndpoint = Extension_MatsEndpoint
            .create(HELLO_ENDPOINT_ID, String.class, String.class)
            .setMatsFactory(MATS.getMatsFactory());

    /**
     * Shows that when no processor is defined, an endpoint will not produce a reply.
     */
    @Test
    public void noProcessorDefined() {
        Throwable throwExpected = null;

        // :: Send a message to the endpoint - This will timeout, thus wrap it in a try-catch.
        try {
            MATS.getMatsFuturizer().futurizeNonessential(getClass().getSimpleName() + "|noProcessorDefined",
                    getClass().getSimpleName(),
                    HELLO_ENDPOINT_ID,
                    String.class,
                    "World")
                    .thenApply(Reply::getReply)
                    .get(1, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            throwExpected = e;
        }
        catch (InterruptedException | ExecutionException e) {
            throw new AssertionError("Expected timeout exception, not this!", e);
        }

        // ----- At this point the above block has timed out. Now we need to verify that the endpoint actually got the
        // message and that the exception throw above was indeed a TimeoutException.
        String incomingMsgToTheEndpoint = _helloEndpoint.waitForRequests(1).get(0);

        Assertions.assertNotNull(throwExpected);
        Assertions.assertEquals(TimeoutException.class, throwExpected.getClass());

        Assertions.assertEquals("World", incomingMsgToTheEndpoint);
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
        Assertions.assertEquals(expectedReturn, firstReply);

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
        Assertions.assertEquals(secondExpectedReturn, secondReply);
    }
}
