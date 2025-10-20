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

package io.mats3.test.jupiter.matsannotatedclass;


import static io.mats3.test.jupiter.matsannotatedclass.J_ExtensionMatsAnnotatedClass_BasicsAndNestingTest.callMatsAnnotatedEndpoint;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.test.jupiter.MatsTest;
import io.mats3.test.jupiter.matsannotatedclass.J_MatsAnnotationTest_MatsAnnotatedClass_RegisterInAnnotation.AnnotatedMats3Endpoint;
import io.mats3.test.jupiter.matsannotatedclass.J_MatsAnnotationTest_MatsAnnotatedClass_RegisterInAnnotation.AnnotatedMats3Endpoint.ServiceDependency;
import io.mats3.util.MatsFuturizer;

/**
 * Tests classes registered via {@link MatsTest#matsAnnotatedClasses()}.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-11
 */
@ExtendWith(MockitoExtension.class)
@MatsTest(matsAnnotatedClasses = AnnotatedMats3Endpoint.class)
public class J_MatsAnnotationTest_MatsAnnotatedClass_RegisterInAnnotation {

    // Provide the Service dependency for AnnotatedMats3Endpoint
    @Mock
    private ServiceDependency _serviceDependency;

    @Test
    void testAnnotatedMatsClass(MatsFuturizer futurizer) throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        String expectedReturn = "Hello World!";
        when(_serviceDependency.formatMessage("World!")).thenReturn(expectedReturn);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(futurizer, "World!");

        // :: Verify
        Assertions.assertEquals(expectedReturn, reply);
        verify(_serviceDependency).formatMessage("World!");
    }

    // ---- Test classes, in normal code this would not be part of the test, but rather in your main code base ---------

    /**
     * Example of a class with annotated MatsEndpoints.
     */
    public static class AnnotatedMats3Endpoint {
        private ServiceDependency _serviceDependency;

        public static final String ENDPOINT_ID = "AnnotatedEndpoint";

        /**
         * Dummy example of a service dependency.
         */
        public interface ServiceDependency {
            String formatMessage(String msg);
        }

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

}
