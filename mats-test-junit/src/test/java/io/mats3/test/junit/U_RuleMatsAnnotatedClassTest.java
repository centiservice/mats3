package io.mats3.test.junit;

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
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Rule_MatsAnnotatedClass}.
 * <p>
 * <b>NOTICE: Note that more extensive testing of all the features are only done in the Extension-variant of this test,
 * at 'io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassTest'.</b>
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-01-09
 */
public class U_RuleMatsAnnotatedClassTest {

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();
    public static final String ENDPOINT_ID = "AnnotatedEndpoint";

    public static class ServiceDependency {
        String formatMessage(String msg) {
            return "Hello " + msg;
        }
    }

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
     * For the {@link Rule_MatsAnnotatedClass#withAnnotatedMatsClasses(Class[]) classes}-variant, this dependency will
     * be picked up by Rule_MatsAnnotatedClass, and result in injecting this instance into the Mats3 Endpoints. This
     * also works for Mockito {@code @Mock} mocks, example in the Extension-variant.
     */
    private final ServiceDependency _serviceDependency = new ServiceDependency();

    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass.create(MATS);

    /**
     * Example of adding a mats annotated class inside a test case, that will then be created and started.
     */
    @Test
    public void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        _matsAnnotationRule.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);
        String expectedReturn = "Hello World!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint("World!");

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
        String expectedReturn = "Hello World!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint("World!");

        // :: Verify
        Assert.assertEquals(expectedReturn, reply);
    }

    // Life cycle test

    @BeforeClass
    public static void beforeAll() {
        // Endpoint should not exist before all tests
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
    }

    @AfterClass
    public static void afterAll() {
        // Endpoint should have been removed after all tests
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
    }

    private static String callMatsAnnotatedEndpoint(String message)
            throws InterruptedException, ExecutionException, TimeoutException {
        return MATS.getMatsFuturizer().futurizeNonessential(
                "invokeAnnotatedEndpoint",
                U_RuleMatsAnnotatedClassTest.class.getSimpleName(),
                ENDPOINT_ID,
                String.class,
                message)
                .thenApply(Reply::getReply)
                .get(10, TimeUnit.SECONDS);
    }

}
