package io.mats3.test.jupiter.matsannotatedclass;

import static io.mats3.test.jupiter.matsannotatedclass.J_ExtensionMatsAnnotatedClass_BasicsAndNestingTest.callMatsAnnotatedEndpoint;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsAnnotatedClass;
import io.mats3.test.jupiter.matsannotatedclass.J_ExtensionMatsAnnotatedClass_BasicsAndNestingTest.AnnotatedMats3Endpoint;
import io.mats3.test.jupiter.matsannotatedclass.J_ExtensionMatsAnnotatedClass_BasicsAndNestingTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Extension_MatsAnnotatedClass} which tests Mockito Mocks in the test class.
 * Specifically testing "field init endpoint registration" of the {@link Extension_MatsAnnotatedClass}. As opposed to
 * JUnit 4, we do not get lifecycle interaction issues with JUnit Jupiter, so we can use the MockitoExtension (in
 * JUnit 4, we need to use the Rule-variant of Mockito init).
 *
 * @author Endre Stølsvik 2025-01-27 01:19 - http://stolsvik.com/, endre@stolsvik.com
 */
@ExtendWith(MockitoExtension.class)
class J_ExtensionMatsAnnotatedClass_Mockito_FieldInit_Test {

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();

    @RegisterExtension
    private final Extension_MatsAnnotatedClass _matsAnnotatedExtension = Extension_MatsAnnotatedClass
            .create(MATS)
            .withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

    @Mock
    private ServiceDependency _serviceDependency;

    @Test
    public void mockitoExtendsWith_FieldInitRegistration() throws ExecutionException, InterruptedException,
            TimeoutException {
        // :: Arrange
        // NOTE: The annotated class is registered in field init of the rule.

        String invokeServiceWith = "The String that the service will be invoked with";
        String serviceWillReply = "The String that the service will reply with";
        when(_serviceDependency.formatMessage(anyString())).thenReturn(serviceWillReply);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), invokeServiceWith);

        // :: Assert
        Assertions.assertEquals(serviceWillReply, reply);
        verify(_serviceDependency).formatMessage(invokeServiceWith);
    }
}
