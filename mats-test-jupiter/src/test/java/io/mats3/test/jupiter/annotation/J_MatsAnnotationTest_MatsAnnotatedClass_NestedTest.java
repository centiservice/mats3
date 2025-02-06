package io.mats3.test.jupiter.annotation;


import static io.mats3.test.jupiter.annotation.J_MatsAnnotationTest_MatsAnnotatedClass.callMatsAnnotatedEndpoint;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.test.jupiter.annotation.J_MatsAnnotationTest_MatsAnnotatedClass_Mockito.AnnotatedMats3Endpoint;
import io.mats3.test.jupiter.annotation.J_MatsAnnotationTest_MatsAnnotatedClass_Mockito.ServiceDependency;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Tests the {@link MatsTest.AnnotatedClass} annotation for a field that is either instantiated by the extension, or
 * already instantiated by the test class.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
@ExtendWith(MockitoExtension.class)
class J_MatsAnnotationTest_MatsAnnotatedClass_NestedTest {

    @Mock
    private ServiceDependency _serviceDependency;

    @InjectMocks
    @MatsTest.AnnotatedClass
    private AnnotatedMats3Endpoint _annotatedMats3Endpoint;

    /**
     * Dummy example of a service dependency - taking a String, and prepend "Hello " to it.
     */
    public interface ServiceDependency {
        String formatMessage(String msg);
    }

    public static final String ENDPOINT_ID = "AnnotatedEndpoint";

    /**
     * Example of a class with annotated MatsEndpoints.
     */
    public static class AnnotatedMats3Endpoint {
        private ServiceDependency _serviceDependency;

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

    @Test
    void testAnnotatedClassDirectly() {
        // :: Setup
        String expectedReturn = "Hello World!";
        when(_serviceDependency.formatMessage("World!")).thenReturn(expectedReturn);

        // :: Act
        String reply = _annotatedMats3Endpoint.matsEndpoint("World!");

        // :: Verify
        Assertions.assertEquals(expectedReturn, reply);
        verify(_serviceDependency).formatMessage("World!");
    }

    @Nested
    @MatsTest
    class MatsIntegration {

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
    }

}
