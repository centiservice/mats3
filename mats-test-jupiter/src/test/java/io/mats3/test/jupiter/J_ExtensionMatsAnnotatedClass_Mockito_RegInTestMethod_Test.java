package io.mats3.test.jupiter;

import static io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.callMatsAnnotatedEndpoint;
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

import io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Extension_MatsAnnotatedClass} which tests Mockito Mocks in the test class.
 * Using the MockitoExtension annotation on the test class.
 *
 * @author Endre Stølsvik 2025-01-26 23:24 - http://stolsvik.com/, endre@stolsvik.com
 */
@ExtendWith(MockitoExtension.class)
class J_ExtensionMatsAnnotatedClass_Mockito_RegInTestMethod_Test {

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();

    @RegisterExtension
    private final Extension_MatsAnnotatedClass _matsAnnotatedExtension = Extension_MatsAnnotatedClass
            .create(MATS);

    @Mock
    private ServiceDependency _serviceDependency;

    @Test
    public void mockitoExtendsWith_RegisterInTestMethod() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        // Register the annotated class (not doing it in field init of the rule).
        _matsAnnotatedExtension.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

        String invokeServiceWith = "The String that the service will be invoked with";
        String serviceWillReply = "The String that the service will reply with";
        when(_serviceDependency.formatMessage(anyString())).thenReturn(serviceWillReply);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), invokeServiceWith);

        // :: Verify
        Assertions.assertEquals(serviceWillReply, reply);
        verify(_serviceDependency).formatMessage(invokeServiceWith);
    }
}
