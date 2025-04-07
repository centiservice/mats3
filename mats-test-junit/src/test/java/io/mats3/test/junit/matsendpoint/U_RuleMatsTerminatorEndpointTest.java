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

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.test.abstractunit.AbstractMatsTestEndpointBase.Result;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsTerminatorEndpoint;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Verifies behavior and illustrates usage of {@link Rule_MatsTerminatorEndpoint}.
 *
 * @author Kevin Mc Tiernan, 2025-04-01, kevin.mc.tiernan@storebrand.no
 */
public class U_RuleMatsTerminatorEndpointTest {

    @ClassRule
    public static Rule_Mats __mats = Rule_Mats.create();

    @Rule
    public Rule_MatsTerminatorEndpoint<String> _terminator = Rule_MatsTerminatorEndpoint.create(__mats,
            "Terminator", String.class);

    /**
     * Sends a message to the endpoint we want to test "AnEndpoint" and verifies the reply, as well as verifying that
     * the endpoint we're testing is sending out/starting the "side-effect" flow.
     */
    @Test
    public void verifyTerminator() throws ExecutionException, InterruptedException {
        // :: Setup
        __mats.getMatsFactory().single("AnEndpoint", String.class, String.class,
                (ctx, msg) -> {
                    // Spin off a new mats flow to the terminator
                    ctx.initiate(init -> init.to("Terminator").send(msg + " Terminator!"));
                    return msg + " World!";
                });
        // :: Act
        String reply = __mats.getMatsFuturizer().futurize("RequestHello", getClass().getSimpleName(),
                "AnEndpoint", String.class, "Hello", init -> { }).thenApply(Reply::get).get();

        Result<Void, String> result = _terminator.waitForMessage();
        // :: Assert
        Assert.assertEquals("Hello World!", reply);
        Assert.assertEquals("Hello Terminator!", result.getData());
    }
}
