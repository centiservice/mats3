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

package io.mats3.test.jupiter.matsendpoint;

import java.util.concurrent.ExecutionException;

import javax.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.mats3.MatsInitiator;
import io.mats3.spring.test.SpringInjectRulesAndExtensions;
import io.mats3.test.abstractunit.AbstractMatsTestEndpointBase.Result;
import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsTerminatorEndpoint;
import io.mats3.test.jupiter.matsendpoint.J_ExtensionMatsEndpoint_SpringWiringTest.MatsSpringContext;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Verifies behavior and illustrates usage of {@link Extension_MatsTerminatorEndpoint}.
 *
 * @author Kevin Mc Tiernan, 2025-04-07, kevin.mc.tiernan@storebrand.no
 */
class J_ExtensionMatsTerminatorEndpointTest {

    // TODO :: Should be moved inside the "UnitTests" class once library is moved to JDK 16+.
    @RegisterExtension
    static final Extension_Mats __mats = Extension_Mats.create();

    @Nested
    class UnitTests {
        @RegisterExtension
        public Extension_MatsTerminatorEndpoint<String> _terminator = Extension_MatsTerminatorEndpoint.create(__mats,
                "Terminator", String.class);

        @Test
        void verifyTerminator() throws ExecutionException, InterruptedException {
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
            Assertions.assertEquals("Hello World!", reply);
            Assertions.assertEquals("Hello Terminator!", result.getData());
        }
    }

    @Nested
    @ExtendWith(SpringExtension.class)
    @SpringInjectRulesAndExtensions
    @ContextConfiguration(classes = MatsSpringContext.class)
    class SpringWiring {

        @RegisterExtension
        public Extension_MatsTerminatorEndpoint<String> _terminatorEndpoint = Extension_MatsTerminatorEndpoint
                .create("TerminatorEndpoint", String.class);
        @Inject
        private MatsInitiator _matsInitiator;

        @Test
        void testAutowiringOfTerminatorEndpoint() {
            // :: Act
            _matsInitiator.initiateUnchecked(init -> init
                    .traceId("Test_Terminator")
                    .from(getClass().getSimpleName())
                    .to("TerminatorEndpoint")
                    .send("Terminal"));

            // var used as result conflicts with MatsLatch.Result
            var result = _terminatorEndpoint.waitForMessage();

            // :: Assert
            Assertions.assertEquals("Terminal", result.getData());
        }

    }
}
