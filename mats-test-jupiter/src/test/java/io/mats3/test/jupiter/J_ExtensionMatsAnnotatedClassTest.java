package io.mats3.test.jupiter;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Extension_MatsAnnotatedClass}, with example of adding annotated classes when the extension is
 * created, or within each test method.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-01-09
 */
class J_ExtensionMatsAnnotatedClassTest {

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();
    public static final String ENDPOINT_ID = "AnnotatedEndpoint";

    public static class ServiceDependency {
        String formatMessage(String msg) {
            return "Hello " + msg;
        }
    }

    public static class MatsAnnotatedClass_Endpoint {

        private ServiceDependency _serviceDependency;

        public MatsAnnotatedClass_Endpoint() {

        }

        @Inject
        public MatsAnnotatedClass_Endpoint(ServiceDependency serviceDependency) {
            _serviceDependency = serviceDependency;
        }

        @MatsMapping(ENDPOINT_ID)
        public String matsEndpoint(@Dto String msg) {
            return _serviceDependency.formatMessage(msg);
        }

    }

    // This dependency will be picked up by Extension_MatsAnnotatedClass, and result in injecting this
    // instance into the service. This would also work if this was instead a Mockito mock.
    private final ServiceDependency _serviceDependency = new ServiceDependency();


    /**
     * Example for how to provide a class with annotated MatsEndpoints to test.
     */
    @Nested
    class TestAnnotatedWithProvidedClass {
        @RegisterExtension
        private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension = Extension_MatsAnnotatedClass
                .create(MATS)
                .withAnnotatedMatsClasses(MatsAnnotatedClass_Endpoint.class);

        @Test
        void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint("World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
        }
    }

    /**
     * Example for how to provide an instance of an annotated class with MatsEndpoints to test.
     */
    @Nested
    class TestAnnotatedWithProvidedInstance {

        @RegisterExtension
        private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension = Extension_MatsAnnotatedClass
                .create(MATS)
                .withAnnotatedMatsInstances(new MatsAnnotatedClass_Endpoint(_serviceDependency));

        @Test
        void testAnnotatedMatsInstance() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint("World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
        }
    }

    /**
     * These tests demonstrate that we can add new annotated classes within a test.
     */
    @Nested
    class TestAnnotatedWithDelayedConfiguration {

        @RegisterExtension
        private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension
                = Extension_MatsAnnotatedClass.create(MATS);

        @Test
        void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            _matsAnnotatedClassExtension.withAnnotatedMatsClasses(MatsAnnotatedClass_Endpoint.class);
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint("World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
        }


        @Test
        void testAnnotatedMatsInstance() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            MatsAnnotatedClass_Endpoint annotatedClassInstance =
                    new MatsAnnotatedClass_Endpoint(_serviceDependency);
            _matsAnnotatedClassExtension.withAnnotatedMatsInstances(annotatedClassInstance);
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint("World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
        }
    }

    private String callMatsAnnotatedEndpoint(String message)
            throws InterruptedException, ExecutionException, TimeoutException {
        return MATS.getMatsFuturizer().futurizeNonessential(
                        "invokeAnnotatedEndpoint",
                        getClass().getSimpleName(),
                        ENDPOINT_ID,
                        String.class,
                        message)
                .thenApply(Reply::getReply)
                .get(10, TimeUnit.SECONDS);
    }


}
