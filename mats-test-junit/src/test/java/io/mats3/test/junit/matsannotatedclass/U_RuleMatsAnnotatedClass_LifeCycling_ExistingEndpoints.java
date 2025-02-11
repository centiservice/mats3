package io.mats3.test.junit.matsannotatedclass;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsAnnotatedClass;
import io.mats3.test.junit.Rule_MatsEndpoint;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.ServiceDependency;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Rule_MatsAnnotatedClass}, where we also set up endpoints outside of the annotated class, both on a
 * "global basis" (before and after class), and on a "per test" basis (before and after each test) - the annotation
 * stuff should not interfere with these other endpoints. Also uses the {@link Rule_MatsEndpoint} to set up an
 * endpoint.
 */
public class U_RuleMatsAnnotatedClass_LifeCycling_ExistingEndpoints {
    private static final Logger log = LoggerFactory.getLogger(
            U_RuleMatsAnnotatedClass_LifeCycling_ExistingEndpoints.class);

    private static final String GLOBAL_ENDPOINT_ID = "GlobalEndpoint";
    private static final String ANNOTATION_ENDPOINT_ID = "AnnotatedEndpoint";
    private static final String RULE_MATS_ENDPOINT_ID = "ExtensionMatsEndpoint";
    private static final String PER_TEST_ENDPOINT_ID = "PerTestEndpoint";

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    // -- Using the ServiceDependency and AnnotatedMats3Endpoint from the U_RuleMatsAnnotatedClassBasicsTest.

    // needed as a "bean" to be injected into the AnnotatedMats3Endpoint
    private final ServiceDependency _serviceDependency = new ServiceDependency();

    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass
            .create(MATS)
            .withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);

    @Rule
    public final Rule_MatsEndpoint<String, String> _helloEndpoint = Rule_MatsEndpoint
            .create(MATS, RULE_MATS_ENDPOINT_ID, String.class, String.class)
            .setProcessLambda((context, msg) -> msg + " RuleMatsEndpoint");

    @BeforeClass
    public static void beforeClass() {
        log.info("=!=: @BeforeClass: Set up \"global\" endpoint.");

        // :: No endpoints should be here at start!
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(ANNOTATION_ENDPOINT_ID).isPresent());
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(RULE_MATS_ENDPOINT_ID).isPresent());
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Set up "global" endpoint
        MATS.getMatsFactory().single(GLOBAL_ENDPOINT_ID, String.class, String.class,
                (ctx, msg) -> msg + " Global");
    }

    @Before
    public void before() {
        log.info("=!=: @Before: Assert global, then set up \"per test\" endpoint.");

        // ASSERT:

        // The "Global" SHOULD be here
        Assert.assertTrue(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        // The annotated endpoint SHOULD be here
        Assert.assertTrue(MATS.getMatsFactory().getEndpoint(ANNOTATION_ENDPOINT_ID).isPresent());
        // The Rule_MatsEndpoint SHOULD be here
        Assert.assertTrue(MATS.getMatsFactory().getEndpoint(RULE_MATS_ENDPOINT_ID).isPresent());
        // The "per test" endpoint should NOT be here (because it is set up right below!)
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Set up "per test" endpoint
        MATS.getMatsFactory().single(PER_TEST_ENDPOINT_ID, String.class, String.class,
                (ctx, msg) -> msg + " PerTest");
    }

    @After
    public void after() {
        log.info("=!=: @After: Assert, then tear down \"per test\" endpoint.");

        // ASSERT:

        // :: All endpoints SHOULD still be here!
        Assert.assertTrue(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        Assert.assertTrue(MATS.getMatsFactory().getEndpoint(ANNOTATION_ENDPOINT_ID).isPresent());
        Assert.assertTrue(MATS.getMatsFactory().getEndpoint(RULE_MATS_ENDPOINT_ID).isPresent());
        // The "per test" endpoint SHOULD still be here! (because it is torn down right below!)
        Assert.assertTrue(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Tear down "per test" endpoint
        MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID)
                .ifPresent(ep -> ep.remove(30_000));

        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());
    }

    @AfterClass
    public static void afterClass() {
        log.info("=!=: @AfterClass: Assert, then tear down \"global\" endpoint.");

        // ASSERT:

        // The "Global" SHOULD still be here (because it is torn down right below!)
        Assert.assertTrue(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
        // :: The rest of the endpoints should NOT be here anymore!
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(ANNOTATION_ENDPOINT_ID).isPresent());
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(RULE_MATS_ENDPOINT_ID).isPresent());
        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(PER_TEST_ENDPOINT_ID).isPresent());

        // Tear down "global" endpoint
        MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID)
                .ifPresent(ep -> ep.remove(30_000));

        Assert.assertFalse(MATS.getMatsFactory().getEndpoint(GLOBAL_ENDPOINT_ID).isPresent());
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
        Assert.assertEquals("Hello World " + which, reply);

        String global = callEndpoint(GLOBAL_ENDPOINT_ID, "XYZ " + which);
        Assert.assertEquals("XYZ " + which + " Global", global);

        String extensionMats = callEndpoint(RULE_MATS_ENDPOINT_ID, "123 " + which);
        Assert.assertEquals("123 " + which + " RuleMatsEndpoint", extensionMats);

        String perTest = callEndpoint(PER_TEST_ENDPOINT_ID, "ABC " + which);
        Assert.assertEquals("ABC " + which + " PerTest", perTest);
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
