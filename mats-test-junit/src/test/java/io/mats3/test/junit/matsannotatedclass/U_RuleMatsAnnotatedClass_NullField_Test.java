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

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsAnnotatedClass;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Rule_MatsAnnotatedClass} which tests that a missing field will crash.
 *
 */
public class U_RuleMatsAnnotatedClass_NullField_Test {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    // -- Using the ServiceDependency and AnnotatedMats3Endpoint from the U_RuleMatsAnnotatedClassBasicsTest.

    // This field is null, which should crash upon injection.
    private final ServiceDependency _serviceDependency = null;

    /**
     * Here we're explicitly giving it the MatsFactory from the Rule_Mats.
     */
    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass.create(MATS);

    /**
     * Expects the annotated class to be picked up from the static construction.
     */
    @Test
    public void fieldIsNull() {
        // This should crash.
        try {
            _matsAnnotationRule.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);
            // Cannot throw AssertionError, as it is caught below.
            throw new IllegalStateException("The dependency is null, which should have crashed injection.");
        }
        catch (AssertionError e) {
            // Expected
        }
    }

}
