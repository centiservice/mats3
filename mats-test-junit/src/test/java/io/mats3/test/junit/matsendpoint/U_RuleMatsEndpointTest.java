/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.test.junit.matsendpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsEndpoint;
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

        // NOTE: Each @Test method is invoked in a new instance of the test class, thus the endpoint is reset.

        // :: Send a message to the endpoint - This will time out, thus wrap it in a try-catch.
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

        // ----- At this point the above block has timed out.

        // :: Assert that the endpoint actually got the message
        String incomingMsgToTheEndpoint = _helloEndpoint.waitForRequests(1).get(0);
        // Assert that the value of the message is the same as was sent.
        Assert.assertEquals("World", incomingMsgToTheEndpoint);

        // :: Assert that we got the TimeoutException we expected
        Assert.assertNotNull(throwExpected);
        Assert.assertEquals(TimeoutException.class, throwExpected.getClass());
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
                        .thenApply(Reply::get)
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
                .thenApply(Reply::get)
                .get(10, TimeUnit.SECONDS);

        // :: Final verify
        Assert.assertEquals(secondExpectedReturn, secondReply);
    }

    /**
     * Sends a null message to the endpoint to verify that it can handle null messages.
     */
    @Test
    public void sendNullMessage() throws InterruptedException, ExecutionException, TimeoutException {
        // :: Setup
        _helloEndpoint.setProcessLambda((ctx, msg) -> msg + " World!");

        // :: Act
        String reply = MATS.getMatsFuturizer().futurizeNonessential(
                        getClass().getSimpleName() + "_nullHandling",
                        getClass().getSimpleName(),
                        HELLO_ENDPOINT_ID,
                        String.class,
                        null)
                .thenApply(Reply::get)
                .get(10, TimeUnit.SECONDS);

        // :: Verify
        Assert.assertEquals("null World!", reply);
    }
}
