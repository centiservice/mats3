package io.mats3.test.junit.matsannotatedclass;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsAnnotatedClass;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Rule_MatsAnnotatedClass}.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-01-09
 */
public class U_RuleMatsAnnotatedClassBasicsTest {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass.create(MATS);

    /**
     * For the {@link Rule_MatsAnnotatedClass#withAnnotatedMatsClasses(Class[]) classes}-variant, this dependency will
     * be picked up by Rule_MatsAnnotatedClass, and result in injecting this instance into the Mats3 Endpoints. This
     * also works for Mockito {@code @Mock} mocks, see other tests.
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
     * Example of adding a mats annotated class inside a test case, that will then be created and started.
     */
    @Test
    public void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        _matsAnnotationRule.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);
        String expectedReturn = "Hello World!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

        // :: Verify
        Assert.assertEquals(expectedReturn, reply);
    }

    /**
     * Example of using an already instantiated Mats annotated class inside a test method.
     */
    @Test
    public void testAnnotatedMatsInstance() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        // We now create the instance ourselves, including dependency injection, and then register it.
        AnnotatedMats3Endpoint annotatedMatsInstance = new AnnotatedMats3Endpoint(_serviceDependency);
        _matsAnnotationRule.withAnnotatedMatsInstances(annotatedMatsInstance);
        String expectedReturn = "Hello World 2!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World 2!");

        // :: Verify
        Assert.assertEquals(expectedReturn, reply);
    }

    // Life cycle test

    @BeforeClass
    public static void beforeClass() {
        // Endpoint should not exist before all tests
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
    }

    @AfterClass
    public static void afterClass() {
        // Endpoint should have been removed after all tests
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
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
