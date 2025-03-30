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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.jupiter.MatsTest;
import io.mats3.test.jupiter.MatsTest.MatsTestAnnotatedClass;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Tests the {@link MatsTestAnnotatedClass} annotation for a field that is either instantiated by the extension, or
 * already instantiated by the test class.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
@MatsTest
public class J_MatsTest_MatsAnnotatedClass {

    private final ServiceDependency _serviceDependency = new ServiceDependency();

    /**
     * Dummy example of a service dependency - taking a String, and prepend "Hello " to it.
     */
    public static class ServiceDependency {
        String formatMessage(String msg) {
            return "Hello " + msg;
        }
    }

    public static final String ENDPOINT_ID = "AnnotatedEndpoint";

    /**
     * Example of a class with annotated MatsEndpoints - would normally reside in the actual application code.
     */
    public static class AnnotatedMats3Endpoint {
        private ServiceDependency _serviceDependency;

        public AnnotatedMats3Endpoint() {
            /* No-args constructor for Jackson deserialization. */
        }

        @Inject
        public AnnotatedMats3Endpoint(ServiceDependency serviceDependency) {
            _serviceDependency = serviceDependency;
        }

        @MatsMapping(ENDPOINT_ID)
        public String matsEndpoint(@Dto String msg) {
            return _serviceDependency.formatMessage(msg);
        }
    }

    @Nested
    class AnnotatedClass {
        // :: Arrange
        @MatsTestAnnotatedClass
        private AnnotatedMats3Endpoint _annotatedMats3Endpoint;

        @Test
        void testAnnotatedMatsClass(MatsFuturizer futurizer) throws ExecutionException, InterruptedException,
                TimeoutException {
            // :: Act
            String reply = callMatsEndpoint(futurizer, ENDPOINT_ID, "Instance!");

            // :: Assert
            Assertions.assertEquals("Hello Instance!", reply);
        }
    }

    @Nested
    class AnnotatedInstance {
        // :: Arrange
        @MatsTestAnnotatedClass
        private final AnnotatedMats3Endpoint _annotatedMats3Endpoint = new AnnotatedMats3Endpoint(_serviceDependency);

        @Test
        void testAnnotatedMatsInstance(MatsFuturizer futurizer) {
            // :: Act
            String reply = callMatsEndpoint(futurizer, ENDPOINT_ID, "Class!");

            // :: Assert
            Assertions.assertEquals("Hello Class!", reply);
        }
    }

    static String callMatsEndpoint(MatsFuturizer futurizer, String endpointId, String request) {
        try {
            return futurizer.futurizeNonessential(
                    MatsTestHelp.traceId(),
                    MatsTestHelp.from("@MatsTest-tests"),
                    endpointId,
                    String.class,
                    request)
                    .thenApply(Reply::get)
                    .get(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Got exception when calling Mats endpoint", e);
        }
    }
}
