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

import java.math.BigDecimal;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.test.abstractunit.AbstractMatsTestEndpointBase.Result;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsTerminatorStateEndpoint;

/**
 * Verifies behavior of {@link Rule_MatsTerminatorStateEndpoint} and illustrates usage.
 *
 * @author Kevin Mc Tiernan, 2025-04-01, kevin.mc.tiernan@storebrand.no
 */
public class U_RuleMatsTerminatorStateEndpointTest {

    private static final String MOCK_ENDPOINT_ID = "Mock.endpoint";
    private static final String ENDPOINT_ID = "Target.endpoint";

    @ClassRule
    public static Rule_Mats __mats = Rule_Mats.create();

    @Rule
    public Rule_MatsTerminatorStateEndpoint<MockEndpointSto, TargetEndpointReplyDto> targetEndpoint =
            Rule_MatsTerminatorStateEndpoint.create(__mats, MOCK_ENDPOINT_ID, MockEndpointSto.class,
                    TargetEndpointReplyDto.class);

    /**
     * Verifies {@link Rule_MatsTerminatorStateEndpoint} ability to return both the incoming message and incoming state.
     */
    @Test
    public void verify_result_using_state_terminator() {
        // :: Setup
        BigDecimal pi = new BigDecimal("3.14");
        BigDecimal r = new BigDecimal("50.00");

        __mats.getMatsFactory().single(ENDPOINT_ID, TargetEndpointReplyDto.class, TargetEpDto.class,
                (ctx, msg) -> new TargetEndpointReplyDto(r));

        // :: Act
        __mats.getMatsInitiator().initiateUnchecked(init ->
                init.from(U_RuleMatsTerminatorStateEndpointTest.class.getSimpleName())
                        .traceId("TestingStatePassing")
                        .to(ENDPOINT_ID)
                        // Reply to the mock endpoint with a defined state.
                        .replyTo(MOCK_ENDPOINT_ID, new MockEndpointSto(pi))
                        .request(new TargetEpDto(r)));

        // :: Assert
        Result<MockEndpointSto, TargetEndpointReplyDto> result = targetEndpoint.waitForMessage();

        Assert.assertNotNull(result.getData());
        Assert.assertNotNull(result.getState());

        Assert.assertEquals(pi, result.getState().pi);
        Assert.assertEquals(r, result.getData().r);
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
