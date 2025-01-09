package io.mats3.test.jupiter;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.mats3.MatsEndpoint;
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

    /**
     * Replace the dependency with mockito
     */
    @Nested
    @ExtendWith(MockitoExtension.class)
    class TestAnnotatedWithMockito {

        @RegisterExtension
        private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension = Extension_MatsAnnotatedClass
                .create(MATS)
                .withAnnotatedMatsClasses(MatsAnnotatedClass_Endpoint.class);

        // Note, since we declare this here, it will take precedence over the same bean declared in the
        // enclosing Extension_MatsAnnotatedClass. This is however based on the field name, so this needs to have the
        // same name as the same field in the enclosing class.
        @Mock
        private ServiceDependency _serviceDependency;

        @Test
        void testMockedService() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String expectedReturn = "Hello World!";
            when(_serviceDependency.formatMessage(anyString())).thenReturn(expectedReturn);

            // :: Act
            String reply = callMatsAnnotatedEndpoint("World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
            verify(_serviceDependency).formatMessage("World!");
        }

    }

    // :: Life cycle test
    // This cannot be performed in a nested class, as BeforeAll and AfterAll must be static

    // Since the MatsFactory is in global scope for the entire test, if we register the annotated class here, this
    // will fail in the nested classes, as the endpoint will already exist.
    @RegisterExtension
    private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension = Extension_MatsAnnotatedClass.create(MATS);

    @BeforeAll
    static void beforeAll() {
        // Endpoint should not exists before all tests
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
    }

    @Test
    void testEndpointExistsAfterRegistration() {
        // :: Setup
        _matsAnnotatedClassExtension.withAnnotatedMatsInstances(new MatsAnnotatedClass_Endpoint(_serviceDependency));

        // :: Act
        Optional<MatsEndpoint<?, ?>> endpoint = MATS.getMatsFactory().getEndpoint(ENDPOINT_ID);

        // :: Verify
        Assertions.assertTrue(endpoint.isPresent());
    }

    @Test
    void testDuplicateRegistration() {
        // :: Setup
        MatsAnnotatedClass_Endpoint annotatedMatsInstance = new MatsAnnotatedClass_Endpoint(_serviceDependency);
        // First registration, OK
        _matsAnnotatedClassExtension.withAnnotatedMatsInstances(annotatedMatsInstance);

        // :: Act
        AssertionError assertionError = Assertions.assertThrows(AssertionError.class, () ->
                // Second registration, should fail
                _matsAnnotatedClassExtension.withAnnotatedMatsInstances(annotatedMatsInstance));

        // :: Verify
        // This will of course change if this file changes. But just look at the line number in your editor for
        // the first call to withAnnotatedMatsInstances (the 2nd call will be present in the stacktrace)
        assertionError.printStackTrace();
        Assertions.assertTrue(assertionError.getMessage().contains("J_ExtensionMatsAnnotatedClassTest.java:225"));
    }

    @AfterAll
    static void afterAll() {
        // Endpoint should have been removed after all tests
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
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
