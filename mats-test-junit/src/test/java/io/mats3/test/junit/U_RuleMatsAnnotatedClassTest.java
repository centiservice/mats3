package io.mats3.test.junit;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Rule_MatsAnnotatedClass}
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

    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass.create(MATS);

    /**
     * Example of adding a mats annotated class inside a test case, that will then be created and started.
     */
    @Test
    public void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        _matsAnnotationRule.withAnnotatedMatsClasses(MatsAnnotatedClass_Endpoint.class);
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
        MatsAnnotatedClass_Endpoint annotatedMatsInstance = new MatsAnnotatedClass_Endpoint(_serviceDependency);
        _matsAnnotationRule.withAnnotatedMatsInstances(annotatedMatsInstance);
        String expectedReturn = "Hello World!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint("World!");

        // :: Verify
        Assert.assertEquals(expectedReturn, reply);
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
