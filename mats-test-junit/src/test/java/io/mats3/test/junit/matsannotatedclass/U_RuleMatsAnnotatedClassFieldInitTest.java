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
 * Scaled down version of the test of {@link Rule_MatsAnnotatedClass} which tests that pointing to the annotated class
 * in the static construction also works.
 *
 * @author Endre Stølsvik 2025-01-26 23:24 - http://stolsvik.com/, endre@stolsvik.com
 */
public class U_RuleMatsAnnotatedClassFieldInitTest {

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    // -- Using the ServiceDependency and AnnotatedMats3Endpoint from the U_RuleMatsAnnotatedClassBasicsTest.

    // needed as a "bean" to be injected into the AnnotatedMats3Endpoint
    private final ServiceDependency _serviceDependency = new ServiceDependency();

    /**
     * Here we're explicitly giving it the MatsFactory from the Rule_Mats.
     */
    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass
            .create(MATS.getMatsFactory())
            .withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

    /**
     * Expects the annotated class to be picked up from the static construction.
     */
    @Test
    public void fieldInitTest() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        String expectedReturn = "Hello World!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

        // :: Verify
        Assert.assertEquals(expectedReturn, reply);
    }
}
