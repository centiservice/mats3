package io.mats3.test.jupiter;

import static io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.callMatsAnnotatedEndpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Extension_MatsAnnotatedClass} which tests that it works with the factory
 * method taking a MatsFactory instead of a Rule_Mats.
 *
 * @author Endre Stølsvik 2025-01-26 23:24 - http://stolsvik.com/, endre@stolsvik.com
 */
class J_ExtensionMatsAnnotatedClassMatsFactoryTest {

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();

    @RegisterExtension
    private final Extension_MatsAnnotatedClass _matsAnnotatedExtension = Extension_MatsAnnotatedClass
            .create(MATS.getMatsFactory());


    private final ServiceDependency _serviceDependency = new ServiceDependency();


    /**
     * Same test as in U_RuleMatsAnnotatedClassTest.
     */
    @Test
    public void testAnnotatedMatsClass() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        _matsAnnotatedExtension.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);
        String expectedReturn = "Hello World!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World!");

        // :: Verify
        Assertions.assertEquals(expectedReturn, reply);
    }

    /**
     * Same test as in U_RuleMatsAnnotatedClassTest.
     */
    @Test
    public void testAnnotatedMatsInstance() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        // We now create the instance ourselves, including dependency injection, and then register it.
        AnnotatedMats3Endpoint annotatedMatsInstance = new AnnotatedMats3Endpoint(_serviceDependency);
        _matsAnnotatedExtension.withAnnotatedMatsInstances(annotatedMatsInstance);
        String expectedReturn = "Hello World 2!";

        // :: Act
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World 2!");

        // :: Verify
        Assertions.assertEquals(expectedReturn, reply);
    }
}
