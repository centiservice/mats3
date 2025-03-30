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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsAnnotatedClass;
import io.mats3.test.jupiter.matsannotatedclass.J_ExtensionMatsAnnotatedClass_BasicsAndNestingTest.AnnotatedMats3Endpoint;
import io.mats3.test.jupiter.matsannotatedclass.J_ExtensionMatsAnnotatedClass_BasicsAndNestingTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Extension_MatsAnnotatedClass} which tests that it works with the factory
 * method taking a MatsFactory instead of a Rule_Mats.
 *
 * @author Endre Stølsvik 2025-01-26 23:24 - http://stolsvik.com/, endre@stolsvik.com
 */
class J_ExtensionMatsAnnotatedClass_ProvideMatsFactoryTest {

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();

    @RegisterExtension
    private final Extension_MatsAnnotatedClass _matsAnnotatedExtension = Extension_MatsAnnotatedClass
            .create(MATS.getMatsFactory());


    private final ServiceDependency _serviceDependency = new ServiceDependency();


    @Test
    public void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        _matsAnnotatedExtension.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);
        String expectedReturn = "Hello World!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

        // :: Verify
        Assertions.assertEquals(expectedReturn, reply);
    }

    @Test
    public void testAnnotatedMatsInstance() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        // We now create the instance ourselves, including dependency injection, and then register it.
        AnnotatedMats3Endpoint annotatedMatsInstance = new AnnotatedMats3Endpoint(_serviceDependency);
        _matsAnnotatedExtension.withAnnotatedMatsInstances(annotatedMatsInstance);
        String expectedReturn = "Hello World 2!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World 2!");

        // :: Verify
        Assertions.assertEquals(expectedReturn, reply);
    }
}
