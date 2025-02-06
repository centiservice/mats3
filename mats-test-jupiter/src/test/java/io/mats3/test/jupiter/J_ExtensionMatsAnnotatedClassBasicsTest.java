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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.mats3.MatsEndpoint;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Extension_MatsAnnotatedClass}, with example of adding annotated classes when the extension is created,
 * or within each test method, and using Mockito to replace a dependency, and nested Jupiter tests.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-01-09
 */
class J_ExtensionMatsAnnotatedClassBasicsTest {

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();

    /**
     * For the {@link Extension_MatsAnnotatedClass#withAnnotatedMatsClasses(Class[]) classes}-variant, this dependency
     * will be picked up by Extension_MatsAnnotatedClass, and result in injecting this instance into the Mats3
     * Endpoints. This also works for Mockito {@code @Mock} mocks, example in nested test below, and other tests.
     */
    private final ServiceDependency _serviceDependency = new ServiceDependency();

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

    /**
     * Example for how to provide a <b>class</b> with annotated MatsEndpoints to test.
     */
    @Nested
    class TestAnnotatedWithProvidedClass {
        @RegisterExtension
        private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension = Extension_MatsAnnotatedClass
                .create(MATS)
                .withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

        // This is evidently needed for some runs on Java 21: It seems to sometimes elide the synthetic field
        // representing the outer/enclosing class instance when there are no references to it, which there ain't in
        // this test class.
        private ServiceDependency _serviceDependency = J_ExtensionMatsAnnotatedClassBasicsTest.this._serviceDependency;

        @Test
        void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
        }
    }

    /**
     * Example for how to provide an <b>instance</b> of an annotated class with MatsEndpoints to test.
     */
    @Nested
    class TestAnnotatedWithProvidedInstance {

        @RegisterExtension
        private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension = Extension_MatsAnnotatedClass
                .create(MATS)
                .withAnnotatedMatsInstances(new AnnotatedMats3Endpoint(_serviceDependency));

        @Test
        void testAnnotatedMatsInstance() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

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
        private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension = Extension_MatsAnnotatedClass
                .create(MATS);

        @Test
        void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            _matsAnnotatedClassExtension.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

            // :: Verify
            Assertions.assertEquals(expectedReturn, reply);
        }

        @Test
        void testAnnotatedMatsInstance() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
            AnnotatedMats3Endpoint annotatedClassInstance = new AnnotatedMats3Endpoint(_serviceDependency);
            _matsAnnotatedClassExtension.withAnnotatedMatsInstances(annotatedClassInstance);
            String expectedReturn = "Hello World!";

            // :: Act
            String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

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
                .withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

        // Note, since we declare this here, it will take precedence over the same bean declared in the
        // enclosing Extension_MatsAnnotatedClass. This is however based on the field name, so this needs to have the
        // same name as the same field in the enclosing class.
        @Mock
        private ServiceDependency _serviceDependency;

        @Test
        void testMockedService() throws ExecutionException, InterruptedException, TimeoutException {
            // :: Setup
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

    // :: Life cycle test
    // This cannot be performed in a nested class, as BeforeAll and AfterAll must be static

    /**
     * Register an instance of the Extension, but don't register any Mats3 annotated endpoints on it: Since the
     * MatsFactory is in global scope for the entire test class, included nested tests, if we register the Mats endpoint
     * annotated class here, this will fail in the nested classes, as the endpoint will already exist.
     */
    @RegisterExtension
    private final Extension_MatsAnnotatedClass _matsAnnotatedClassExtension = Extension_MatsAnnotatedClass.create(MATS);

    @BeforeAll
    static void beforeAll() {
        // Endpoint should not exist before all tests
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
    }

    @Test
    void testEndpointExistsAfterRegistration() {
        // :: Setup
        _matsAnnotatedClassExtension.withAnnotatedMatsInstances(new AnnotatedMats3Endpoint(_serviceDependency));

        // :: Act
        Optional<MatsEndpoint<?, ?>> endpoint = MATS.getMatsFactory().getEndpoint(ENDPOINT_ID);

        // :: Verify
        Assertions.assertTrue(endpoint.isPresent());
    }

    @Test
    void testDuplicateRegistration() {
        // :: Setup
        // First registration, OK
        AnnotatedMats3Endpoint annotatedMatsInstance = new AnnotatedMats3Endpoint(_serviceDependency);
        _matsAnnotatedClassExtension.withAnnotatedMatsInstances(annotatedMatsInstance);

        // :: Act
        // Second registration, should fail
        AssertionError assertionError = Assertions.assertThrows(AssertionError.class, () -> _matsAnnotatedClassExtension
                .withAnnotatedMatsInstances(annotatedMatsInstance));

        // :: Verify
        // This will of course change if this file changes. But just look at the line number in your editor for
        // the first call to withAnnotatedMatsInstances (the 2nd call will be present in the stacktrace)
        boolean exceptionAsExpected = assertionError.getMessage()
                .contains("J_ExtensionMatsAnnotatedClassBasicsTest.java:240");
        if (!exceptionAsExpected) {
            throw new AssertionError("The exception message did not contain the expected content!"
                    + " (including line number of expected double registration! Did you change the test class?"
                    + " Update the test with correct line number!).", assertionError);
        }
    }

    @AfterAll
    static void afterAll() {
        // Endpoint should have been removed after all tests
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
    }

    static String callMatsAnnotatedEndpoint(MatsFuturizer futurizer, String message)
            throws InterruptedException, ExecutionException, TimeoutException {
        return futurizer.futurizeNonessential(
                "invokeAnnotatedEndpoint",
                "UnitTest",
                ENDPOINT_ID,
                String.class,
                message)
                .thenApply(Reply::get)
                .get(10, TimeUnit.SECONDS);
    }
}
