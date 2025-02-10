package io.mats3.test.junit;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;

/**
 * Simplest possible test case utilizing only {@link Rule_Mats} and {@link Rule_MatsEndpoint}
 * <p>
 * Instead of using a {@link io.mats3.util.MatsFuturizer} this test will utilize a {@link Rule_MatsEndpoint} as
 * a terminator and "receive" the reply on this endpoint.
 *
 * @author Kevin Mc Tiernan, 2020-11-10, kmctiernan@gmail.com
 */
public class U_RuleMatsTest {

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Rule
    public final Rule_MatsEndpoint<String, String> _helloEndpoint = Rule_MatsEndpoint
            .create(MATS, "HelloEndpoint", String.class, String.class)
            .setProcessLambda((ctx, msg) -> "Hello " + msg);

    @Rule
    public final Rule_MatsEndpoint<Void, String> _terminator = Rule_MatsEndpoint
            .create(MATS, "Terminator", void.class, String.class);

    @Test
    public void getReplyFromEndpoint() throws MatsMessageSendException, MatsBackendException {
        // :: Send a message to hello endpoint with a specified reply as the specified reply endpoint.
        MATS.getMatsInitiator().initiate(msg -> msg
                .traceId(getClass().getSimpleName() + "[replyFromEndpointTest]")
                .from(getClass().getSimpleName())
                .to("HelloEndpoint")
                .replyTo("Terminator", null)
                .request("World!"));

        // :: Wait for the message to reach our Terminator
        String request = _terminator.waitForRequest();

        // :: Verify
        Assert.assertEquals("Hello World!", request);
    }
}
