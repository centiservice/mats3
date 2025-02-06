package io.mats3.test.junit;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import io.mats3.test.junit.U_RuleMatsAnnotatedClassBasicsTest.AnnotatedMats3Endpoint;
import io.mats3.test.junit.U_RuleMatsAnnotatedClassBasicsTest.ServiceDependency;

/**
 * Scaled down version of the test of {@link Rule_MatsAnnotatedClass} which tests that a missing field will crash.
 *
 */
public class U_RuleMatsAnnotatedClass_NullField_Test {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    // -- Using the ServiceDependency and AnnotatedMats3Endpoint from the U_RuleMatsAnnotatedClassBasicsTest.

    // This field is null, which should crash upon injection.
    private final ServiceDependency _serviceDependency = null;

    /**
     * Here we're explicitly giving it the MatsFactory from the Rule_Mats.
     */
    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass.create(MATS);

    /**
     * Expects the annotated class to be picked up from the static construction.
     */
    @Test
    public void fieldIsNull() {
        // This should crash.
        try {
            _matsAnnotationRule.withAnnotatedMatsClasses(AnnotatedMats3Endpoint.class);
            // Cannot throw AssertionError, as it is caught below.
            throw new IllegalStateException("The dependency is null, which should have crashed injection.");
        }
        catch (AssertionError e) {
            // Expected
        }
    }

}
