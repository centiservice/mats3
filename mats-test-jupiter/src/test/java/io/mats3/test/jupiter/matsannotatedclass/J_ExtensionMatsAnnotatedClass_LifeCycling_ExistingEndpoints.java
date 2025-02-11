package io.mats3.test.jupiter.matsannotatedclass;

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

import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsAnnotatedClass;
import io.mats3.test.jupiter.Extension_MatsEndpoint;
import io.mats3.test.jupiter.matsannotatedclass.J_ExtensionMatsAnnotatedClass_BasicsAndNestingTest.AnnotatedMats3Endpoint;
import io.mats3.test.jupiter.matsannotatedclass.J_ExtensionMatsAnnotatedClass_BasicsAndNestingTest.ServiceDependency;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Extension_MatsAnnotatedClass}, where we also set up endpoints outside of the annotated class, both on
 * a "global basis" (before and after class), and on a "per test" basis (before and after each test) - the annotation
 * stuff should not interfere with these other endpoints. Also uses the {@link Extension_MatsEndpoint} to set up an
 * endpoint.
 */
public class J_ExtensionMatsAnnotatedClass_LifeCycling_ExistingEndpoints {
    private static final Logger log = LoggerFactory.getLogger(
            J_ExtensionMatsAnnotatedClass_LifeCycling_ExistingEndpoints.class);

    private static final String GLOBAL_ENDPOINT_ID = "GlobalEndpoint";
    private static final String ANNOTATION_ENDPOINT_ID = "AnnotatedEndpoint";
    private static final String EXTENSION_MATS_ENDPOINT_ID = "ExtensionMatsEndpoint";
    private static final String PER_TEST_ENDPOINT_ID = "PerTestEndpoint";

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();

    // -- Using the ServiceDependency and AnnotatedMats3Endpoint from the J_ExtensionMatsAnnotatedClassBasicsTest.

    // needed as a "bean" to be injected into the AnnotatedMats3Endpoint
    private final ServiceDependency _serviceDependency = new ServiceDependency();

    @RegisterExtension
    // Notice how we're NOT setting the MatsFactory, because magic ensues through the ExtensionContext from MATS above.
    public final Extension_MatsAnnotatedClass _matsAnnotationRule = Extension_MatsAnnotatedClass
            .create()
            .withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

    @RegisterExtension
    // Notice how we're NOT setting the MatsFactory, because magic ensues through the ExtensionContext from MATS above.
    public Extension_MatsEndpoint<String, String> _helloEndpoint = Extension_MatsEndpoint.create(
            EXTENSION_MATS_ENDPOINT_ID, String.class, String.class)
            .setProcessLambda((context, msg) -> msg + " ExtensionMatsEndpoint");

    @BeforeAll
    public static void beforeAll() {
        log.info("=!=: @BeforeAll: Set up \"global\" endpoint.");

        // :: No endpoints should be here at start!
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(ANNOTATION_ENDPOINT_ID).isPresent());
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(EXTENSION_MATS_ENDPOINT_ID).isPresent());
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
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(ANNOTATION_ENDPOINT_ID).isPresent());
        // The Extension_MatsEndpoint SHOULD be here
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(EXTENSION_MATS_ENDPOINT_ID).isPresent());
        // The "per test" endpoint should NOT be here (because it is set up right below!)
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Set up "per test" endpoint
        MATS.getMatsFactory().single(PER_TEST_ENDPOINT_ID, String.class, String.class,
                (ctx, msg) -> msg + " PerTest");
    }

    @AfterEach
    public void afterEach() {
        log.info("=!=: @After: Assert, then tear down \"per test\" endpoint.");

        // ASSERT:

        // :: All endpoints SHOULD still be here!
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(ANNOTATION_ENDPOINT_ID).isPresent());
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(EXTENSION_MATS_ENDPOINT_ID).isPresent());
        // The "per test" endpoint SHOULD still be here! (because it is torn down right below!)
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Tear down "per test" endpoint
        MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID)
                .ifPresent(ep -> ep.remove(30_000));

        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());
    }

    @AfterAll
    public static void afterClass() {
        log.info("=!=: @AfterClass: Assert, then tear down \"global\" endpoint.");

        // The "Global" SHOULD still be here (because it is torn down right below!)
        Assertions.assertTrue(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        // :: The rest of the endpoints should NOT be here anymore!
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(ANNOTATION_ENDPOINT_ID).isPresent());
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(EXTENSION_MATS_ENDPOINT_ID).isPresent());
        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Tear down "global" endpoint
        MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID)
                .ifPresent(ep -> ep.remove(30_000));

        Assertions.assertFalse(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
    }

    @Test
    public void lifeCycle_Round1() throws ExecutionException, InterruptedException, TimeoutException {
        test("1A");
        test("1B");
    }

    @Test
    public void lifeCycle_Round2() throws ExecutionException, InterruptedException, TimeoutException {
        test("2A");
        test("2B");
    }

    @Test
    public void lifeCycle_Round3() throws ExecutionException, InterruptedException, TimeoutException {
        test("3A");
        test("3B");
    }

    public void test(String which) throws ExecutionException, InterruptedException, TimeoutException {
        String reply = callEndpoint(ANNOTATION_ENDPOINT_ID, "World " + which);
        Assertions.assertEquals("Hello World " + which, reply);

        String global = callEndpoint(GLOBAL_ENDPOINT_ID, "XYZ " + which);
        Assertions.assertEquals("XYZ " + which + " Global", global);

        String extensionMats = callEndpoint(EXTENSION_MATS_ENDPOINT_ID, "123 " + which);
        Assertions.assertEquals("123 " + which + " ExtensionMatsEndpoint", extensionMats);

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
