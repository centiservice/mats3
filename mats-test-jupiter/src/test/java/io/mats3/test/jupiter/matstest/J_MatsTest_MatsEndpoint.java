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

package io.mats3.test.jupiter.matstest;

import static io.mats3.test.jupiter.matstest.J_MatsTest_MatsAnnotatedClass.callMatsEndpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.mats3.test.jupiter.Extension_MatsEndpoint;
import io.mats3.test.jupiter.MatsTest;
import io.mats3.test.jupiter.MatsTest.MatsTestEndpoint;
import io.mats3.util.MatsFuturizer;

/**
 * Test to demonstrate how to use the {@link MatsTestEndpoint} annotation to create a MatsEndpoint for testing.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
@MatsTest
class J_MatsTest_MatsEndpoint {

    private static final String ENDPOINT_ID = "TestEndpoint";
    private static final String NESTED_ENDPOINT_ID = "NestedTestEndpoint";

    @MatsTestEndpoint(endpointId = ENDPOINT_ID)
    private Extension_MatsEndpoint<String, String> _matsEndpoint;

    @Test
    void testMatsEndpointRegistered(MatsFuturizer matsFuturizer)
            throws ExecutionException, InterruptedException, TimeoutException {
        _matsEndpoint.setProcessLambda((ctx, msg) -> "Hello " + msg);

        String result = callMatsEndpoint(matsFuturizer, ENDPOINT_ID, "World");

        Assertions.assertEquals("Hello World", result);
    }

    @Nested
    class NestedEndpointRegistrationTest {

        @MatsTestEndpoint(endpointId = NESTED_ENDPOINT_ID)
        private Extension_MatsEndpoint<String, String> _nestedEndpoint;

        @Test
        void testMatsEndpointRegisteredOnTwoLevels(MatsFuturizer matsFuturizer)
                throws ExecutionException, InterruptedException, TimeoutException {
            _matsEndpoint.setProcessLambda((ctx, msg) -> "Outer message: " + msg);
            _nestedEndpoint.setProcessLambda((ctx, msg) -> "Nested message: " + msg);

            String resultOuter = callMatsEndpoint(matsFuturizer, ENDPOINT_ID, "Outer");
            String resultNested = callMatsEndpoint(matsFuturizer, NESTED_ENDPOINT_ID, "Inner");

            Assertions.assertEquals("Outer message: Outer", resultOuter);
            Assertions.assertEquals("Nested message: Inner", resultNested);
        }
    }
}
