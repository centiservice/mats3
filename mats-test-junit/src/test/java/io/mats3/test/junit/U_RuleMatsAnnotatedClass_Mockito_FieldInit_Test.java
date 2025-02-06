package io.mats3.test.junit;

import static io.mats3.test.junit.U_RuleMatsAnnotatedClassBasicsTest.callMatsAnnotatedEndpoint;
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

import io.mats3.test.junit.U_RuleMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.junit.U_RuleMatsAnnotatedClassBasicsTest.ServiceDependency;

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
