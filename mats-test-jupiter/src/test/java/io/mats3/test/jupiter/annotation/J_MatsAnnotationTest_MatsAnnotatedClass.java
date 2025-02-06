package io.mats3.test.jupiter.annotation;


import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Tests the {@link MatsTest.AnnotatedClass} annotation for a field that is either instantiated by the extension, or
 * already instantiated by the test class.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
@MatsTest
public class J_MatsAnnotationTest_MatsAnnotatedClass {

    private final ServiceDependency
            _serviceDependency = new ServiceDependency();

    /**
     * Dummy example of a service dependency - taking a String, and prepend "Hello " to it.
     */
    public static class ServiceDependency {
        String formatMessage(String msg) {
            return "Hello " + msg;
        }
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

    @Nested
    class AnnotatedInstance {

        @MatsTest.AnnotatedClass
        private final AnnotatedMats3Endpoint _annotatedMats3Endpoint = new AnnotatedMats3Endpoint(_serviceDependency);


        @Test
        void testAnnotatedMatsClass(MatsFuturizer futurizer) throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint(futurizer, "World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
        }
    }

    @Nested
    class AnnotatedClass {

        @MatsTest.AnnotatedClass
        private AnnotatedMats3Endpoint _annotatedMats3Endpoint;

        @Test
        void testAnnotatedMatsClass(MatsFuturizer futurizer) throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint(futurizer, "World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
        }
    }


    static String callMatsAnnotatedEndpoint(MatsFuturizer futurizer, String request)
            throws InterruptedException, ExecutionException, TimeoutException {
        return futurizer.futurizeNonessential(
                        "invokeAnnotatedEndpoint",
                        "UnitTest",
                        ENDPOINT_ID,
                        String.class,
                        request)
                .thenApply(Reply::get)
                .get(10, TimeUnit.SECONDS);
    }
}
