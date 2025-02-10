package io.mats3.test.jupiter;

import static io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.callMatsAnnotatedEndpoint;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassBasicsTest.ServiceDependency;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Extension_MatsAnnotatedClass}, where we also set up endpoints outside of the annotated class, both on
 * a "global basis" (before and after class), and on a "per test" basis (before and after each test) - the annotation
 * stuff should not interfere with these other endpoints.
 */
public class J_ExtensionMatsAnnotatedClass_LifeCycling_ExistingEndpoints {
    private static final Logger log = LoggerFactory.getLogger(
            J_ExtensionMatsAnnotatedClass_LifeCycling_ExistingEndpoints.class);

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();

    // -- Using the ServiceDependency and AnnotatedMats3Endpoint from the U_RuleMatsAnnotatedClassBasicsTest.

    // needed as a "bean" to be injected into the AnnotatedMats3Endpoint
    private final ServiceDependency _serviceDependency = new ServiceDependency();

    @RegisterExtension
    public final Extension_MatsAnnotatedClass _matsAnnotationRule = Extension_MatsAnnotatedClass
            .create(MATS)
            .withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

    private static final String GLOBAL_ENDPOINT_ID = "GlobalEndpoint";
    private static final String ENDPOINT_ID = "AnnotatedEndpoint";
    private static final String PER_TEST_ENDPOINT_ID = "PerTestEndpoint";

    @BeforeAll
    public static void beforeAll() {
        log.info("=!=: @BeforeAll: Set up \"global\" endpoint.");

        // :: No endpoints should be here at start!
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Set up "global" endpoint
        MATS.getMatsFactory().single(GLOBAL_ENDPOINT_ID, String.class, String.class,
                (ctx, msg) -> msg + " Global");
    }

    @BeforeEach
    public void beforeEach() {
        log.info("=!=: @BeforeEach: Assert global, then set up \"per test\" endpoint.");

        // ASSERT:

        // The "Global" SHOULD be here
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        // The annotated endpoint SHOULD be here
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
        // The "per test" endpoint should NOT be here
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Set up "per test" endpoint
        MATS.getMatsFactory().single(PER_TEST_ENDPOINT_ID, String.class, String.class,
                (ctx, msg) -> msg + " PerTest");
    }

    @AfterEach
    public void afterEach() {
        log.info("=!=: @After: Assert, then tear down \"per test\" endpoint.");

        // ASSERT:

        // The "Global" SHOULD still be here
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        // The annotated endpoint SHOULD still be here
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
        // The "per test" endpoint SHOULD still be here
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Tear down "per test" endpoint
        MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID)
                .ifPresent(ep -> ep.remove(30_000));

    }

    @AfterAll
    public static void afterClass() {
        log.info("=!=: @AfterClass: Assert, then tear down \"global\" endpoint.");

        // The "Global" SHOULD still be here
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        // The annotated endpoint should NOT be here anymore
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(ENDPOINT_ID).isPresent());
        // The "per test" endpoint should NOT be here anymore
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Tear down "global" endpoint
        MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID)
                .ifPresent(ep -> ep.remove(30_000));
    }

    @Test
    public void test1() throws ExecutionException, InterruptedException, TimeoutException {
        test("1A");
        test("1B");
    }

    @Test
    public void test2() throws ExecutionException, InterruptedException, TimeoutException {
        test("2A");
        test("2B");
    }

    @Test
    public void test3() throws ExecutionException, InterruptedException, TimeoutException {
        test("3A");
        test("3B");
    }

    public void test(String which) throws ExecutionException, InterruptedException, TimeoutException {
        String reply = callMatsAnnotatedEndpoint(MATS.getMatsFuturizer(), "World " + which);
        Assertions.assertEquals("Hello World " + which, reply);

        String global = callEndpoint(GLOBAL_ENDPOINT_ID, "XYZ " + which);
        Assertions.assertEquals("XYZ " + which + " Global", global);

        String perTest = callEndpoint(PER_TEST_ENDPOINT_ID, "ABC " + which);
        Assertions.assertEquals("ABC " + which + " PerTest", perTest);
    }

    private static String callEndpoint(String endpointId, String message) {
        try {
            return MATS.getMatsFuturizer().futurizeNonessential(
                    "invokeAnnotatedEndpoint",
                    "UnitTest",
                    endpointId,
                    String.class,
                    message)
                    .thenApply(Reply::get)
                    .get(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Got exception when calling global endpoint.", e);
        }
    }
}
