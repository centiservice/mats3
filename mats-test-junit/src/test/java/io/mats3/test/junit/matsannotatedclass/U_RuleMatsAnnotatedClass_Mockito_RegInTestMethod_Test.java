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
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsAnnotatedClass;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Rule_MatsAnnotatedClass} which tests Mockito Mocks in the test class.
 * <p>
 * To start Mockito, there's multiple ways.
 * <ol>
 * <li>Annotated the test class with {@code @RunWith(MockitoJUnitRunner.StrictStubs.class)}.</li>
 * <li>Use the Mockito rule {@code @Rule public MockitoRule _mockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)}.</li>
 * <li>Call {@code MockitoAnnotations.initMocks(this)} in a {@code @Before} method.</li>
 * <li>Manually create the mocks using {@code Mockito.mock(ServiceToMock.class)}, and manually inject them in the test
 * method.</li>
 * </ol>
 * Note that if you use "field init endpoint registration" of the {@link Rule_MatsAnnotatedClass}, you need to use the
 * rule variant due to initialization order, this is tested in {@link U_RuleMatsAnnotatedClass_Mockito_FieldInit_Test}.
 *
 * @author Endre Stølsvik 2025-01-26 23:24 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(MockitoJUnitRunner.StrictStubs.class) // Method #1
public class U_RuleMatsAnnotatedClass_Mockito_RegInTestMethod_Test {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass
            .create(MATS);

    // Mock out the dependency.
    @Mock
    private ServiceDependency _serviceDependency;

    @Test
    public void mockitoRunsWith_RegisterInTestMethod() throws ExecutionException, InterruptedException, TimeoutException {

        // :: Setup
        // Register the annotated class (not doing it in field init of the rule).
        _matsAnnotationRule.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

        String invokeServiceWith = "The String that the service will be invoked with";
        String serviceWillReply = "The String that the service will reply with";
        when(_serviceDependency.formatMessage(anyString())).thenReturn(serviceWillReply);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), invokeServiceWith);

        // :: Verify
        verify(_serviceDependency).formatMessage(invokeServiceWith);
        Assert.assertEquals(serviceWillReply, reply);
    }
}
