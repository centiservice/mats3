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

import java.math.BigDecimal;

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
import io.mats3.test.jupiter.Extension_MatsTerminatorStateEndpoint;
import io.mats3.test.jupiter.matsendpoint.J_ExtensionMatsEndpoint_SpringWiringTest.MatsSpringContext;

/**
 * Verifies behavior and illustrates usage of {@link Extension_MatsTerminatorStateEndpoint}.
 *
 * @author Kevin Mc Tiernan, 2025-04-07, kevin.mc.tiernan@storebrand.no
 */
class J_ExtensionMatsTerminatorStateEndpointTest {

    // TODO :: Should be moved inside the "UnitTests" class once library is moved to JDK 16+.
    @RegisterExtension
    static final Extension_Mats __mats = Extension_Mats.create();

    @Nested
    class UnitTests {
        private static final String MOCK_ENDPOINT_ID = "Mock.endpoint";
        private static final String ENDPOINT_ID = "Target.endpoint";
        @RegisterExtension
        public Extension_MatsTerminatorStateEndpoint<MockEndpointSto, TargetEndpointReplyDto> targetEndpoint =
                Extension_MatsTerminatorStateEndpoint.create(__mats, MOCK_ENDPOINT_ID, MockEndpointSto.class,
                        TargetEndpointReplyDto.class);

        /**
         * Verifies {@link Extension_MatsTerminatorStateEndpoint} ability to return both the incoming message and incoming state.
         */
        @Test
        void verify_result_using_state_terminator() {
            // :: Setup
            BigDecimal pi = new BigDecimal("3.14");
            BigDecimal r = new BigDecimal("50.00");

            __mats.getMatsFactory().single(ENDPOINT_ID, TargetEndpointReplyDto.class, TargetEpDto.class,
                    (ctx, msg) -> new TargetEndpointReplyDto(r));

            // :: Act
            __mats.getMatsInitiator().initiateUnchecked(init ->
                    init.from(getClass().getSimpleName())
                            .traceId("TestingStatePassing")
                            .to(ENDPOINT_ID)
                            // Reply to the mock endpoint with a defined state.
                            .replyTo(MOCK_ENDPOINT_ID, new MockEndpointSto(pi))
                            .request(new TargetEpDto(r)));

            // :: Assert
            Result<MockEndpointSto, TargetEndpointReplyDto> result = targetEndpoint.waitForMessage();

            Assertions.assertNotNull(result.getData());
            Assertions.assertNotNull(result.getState());

            Assertions.assertEquals(pi, result.getState().pi);
            Assertions.assertEquals(r, result.getData().r);
        }
    }

    @Nested
    @ExtendWith(SpringExtension.class)
    @SpringInjectRulesAndExtensions
    @ContextConfiguration(classes = MatsSpringContext.class)
    class SpringWiring {

        @RegisterExtension
        public Extension_MatsTerminatorStateEndpoint<String, String> _terminatorEndpoint =
                Extension_MatsTerminatorStateEndpoint.create("TerminatorStateWiring", String.class, String.class);
        @Inject
        private MatsInitiator _matsInitiator;

        @Test
        void testAutowiringOfTerminatorStateEndpoint() {
            // :: Act
            _matsInitiator.initiateUnchecked(init -> init
                    .traceId("Test_Terminator")
                    .from(getClass().getSimpleName())
                    .to("TerminatorStateWiring")
                    .send("Terminal", "TerminalState"));

            // var used as result conflicts with MatsLatch.Result
            var result = _terminatorEndpoint.waitForMessage();

            // :: Assert
            Assertions.assertEquals("Terminal", result.getData());
            Assertions.assertEquals("TerminalState", result.getState());
        }
    }

    // ======================================== Helper classes ========================================================

    public static class MockEndpointSto {
        BigDecimal pi;

        public MockEndpointSto() {
        }

        public MockEndpointSto(BigDecimal pi) {
            this.pi = pi;
        }
    }

    public static class TargetEndpointReplyDto {
        BigDecimal r;

        public TargetEndpointReplyDto() {
        }

        public TargetEndpointReplyDto(BigDecimal r) {
            this.r = r;
        }
    }

    public static class TargetEpDto {
        BigDecimal r;

        public TargetEpDto() {
        }

        public TargetEpDto(BigDecimal r) {
            this.r = r;
        }
    }
}
