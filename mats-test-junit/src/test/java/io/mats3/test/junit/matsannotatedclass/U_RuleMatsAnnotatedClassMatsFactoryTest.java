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

package io.mats3.test.junit.matsannotatedclass;

import static io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.callMatsAnnotatedEndpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsAnnotatedClass;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Rule_MatsAnnotatedClass} which tests that it works with the factory method
 * taking a MatsFactory instead of a Rule_Mats.
 *
 * @author Endre Stølsvik 2025-01-27 21:30 - http://stolsvik.com/, endre@stolsvik.com
 */
public class U_RuleMatsAnnotatedClassMatsFactoryTest {

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    // -- Using the ServiceDependency and AnnotatedMats3Endpoint from the U_RuleMatsAnnotatedClassTest.

    // Needed as a "bean" to be injected into the AnnotatedMats3Endpoint, and also used directly in the "Instance" test.
    private final ServiceDependency _serviceDependency = new ServiceDependency();

    /**
     * Here we're explicitly giving it the MatsFactory from the Rule_Mats.
     */
    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass
            .create(MATS.getMatsFactory());

    @Test
    public void initWithClassInsideMethod() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        _matsAnnotationRule.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);
        String expectedReturn = "Hello World!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

        // :: Verify
        Assert.assertEquals(expectedReturn, reply);
    }

    /**
     * Same test as in U_RuleMatsAnnotatedClassTest.
     */
    @Test
    public void initWithInstanceInsideMethod() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        // We now create the instance ourselves, including dependency injection, and then register it.
        AnnotatedMats3Endpoint annotatedMatsInstance = new AnnotatedMats3Endpoint(_serviceDependency);
        _matsAnnotationRule.withAnnotatedMatsInstances(annotatedMatsInstance);
        String expectedReturn = "Hello World 2!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World 2!");

        // :: Verify
        Assert.assertEquals(expectedReturn, reply);
    }
}
