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

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.test.jupiter.MatsTest;
import io.mats3.test.jupiter.matstest.J_MatsTest_MatsAnnotatedClass_AnnotationParameter.AnnotatedMats3Endpoint;
import io.mats3.util.MatsFuturizer;

/**
 * Tests the functionality of the {@link MatsTest} annotation where you can specify a class with annotated MatsEndpoints
 * directly on the annotation.
 *
 * @author Endre Stølsvik 2024-10-13 02:19 - http://stolsvik.com/, endre@stolsvik.com
 */
// :: Arrange
@MatsTest(matsAnnotatedClasses = AnnotatedMats3Endpoint.class)
public class J_MatsTest_MatsAnnotatedClass_AnnotationParameter {

    private final ServiceDependency _serviceDependency = new ServiceDependency();

    /**
     * Dummy example of a service dependency - taking a String, and prepend "Hello " to it.
     */
    public static class ServiceDependency {
        String formatMessage(String msg) {
            return "Hello " + msg;
        }
    }

    public static final String ENDPOINT_ID = "AnnotatedEndpoint_SpecifiedDirectlyOnMatsTestAnnotation";

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

    @Test
    void testAnnotatedMatsClass(MatsFuturizer futurizer) throws ExecutionException, InterruptedException,
            TimeoutException {
        // :: Act
        String reply = callMatsEndpoint(futurizer, ENDPOINT_ID, "Instance!");

        // :: Assert
        Assertions.assertEquals("Hello Instance!", reply);
    }
}
