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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsAnnotatedClass;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Rule_MatsAnnotatedClass} which tests Mockito Mocks in the test class.
 * Specifically testing "field init endpoint registration" of the {@link Rule_MatsAnnotatedClass}, where we need to use
 * the rule variant of Mockito init due to lifecycle interaction issues.
 *
 * @author Endre Stølsvik 2025-01-26 23:50 - http://stolsvik.com/, endre@stolsvik.com
 */
public class U_RuleMatsAnnotatedClass_Mockito_FieldInit_Test {

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    // Using the MockitoRule to ensure that the Mockito Mocks are properly initialized, this way is necessary when using
    // "field init endpoint registration" of the Rule_MatsAnnotatedClass, as below. The test-class annotation
    // @RunWith(MockitoJUnitRunner.StrictStubs.class) does not work in this case, due to lifecycle interaction issues.
    @Rule
    public MockitoRule _mockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);

    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass
            .create(MATS)
            .withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

    // Mock out the dependency.
    @Mock
    private ServiceDependency _serviceDependency;

    @Test
    public void mockitoRule_FieldInitRegistration() throws ExecutionException, InterruptedException,
            TimeoutException {

        // :: Arrange
        // NOTE: The annotated class is registered in field init of the rule.

        String invokeServiceWith = "The String that the service will be invoked with";
        String serviceWillReply = "The String that the service will reply with";
        when(_serviceDependency.formatMessage(anyString())).thenReturn(serviceWillReply);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), invokeServiceWith);

        // :: Assert
        verify(_serviceDependency).formatMessage(invokeServiceWith);
        Assert.assertEquals(serviceWillReply, reply);
    }
}
