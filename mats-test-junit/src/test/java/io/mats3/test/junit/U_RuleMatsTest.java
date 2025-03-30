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
