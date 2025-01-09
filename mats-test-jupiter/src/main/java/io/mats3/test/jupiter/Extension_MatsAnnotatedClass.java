package io.mats3.test.jupiter;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.mats3.MatsFactory;
import io.mats3.test.abstractunit.AbstractMatsAnnotatedClass;

/**
 * Helper class to test classes annotated with Mats annotations.
 * <p>
 * This class can be used to test classes annotated with Mats annotations, without having to create a full Spring
 * context in the test, and include Spring for testing. It still creates a Spring context internally to initialize the
 * Mats endpoints, but this is not exposed to the test.
 * <p>
 * Classes can either be registered using the #withClasses method, or by using the #addAnnotatedMatsBeans method.
 * #withClasses is intended to be used on creation of the Extension, and will register the class in a Spring context.
 * Dependencies will be read from the fields of the test class. It is important that fields are initialized before
 * this extension runs, if there are dependencies in the test class needed by the Mats annotated class. Usually this
 * will be resolved by using Mockito, and the @Mock annotation. Those fields will be initialized before this extension
 * runs, and will be available for the Spring context.
 * <p>
 * Please note that this will register the endpoints for each and every test.
 * <p>
 * The other option is to use the #addAnnotatedMatsBeans method, which will register the Mats annotated class when
 * called. This can be used when you want to initialize the annotated class by yourself, and only have a few tests
 * that need the endpoint, and other tests are unit tests calling directly on an instance of the class instead.
 * This will still allow for an integration test of the Mats endpoint, checking annotations, and that state and
 * dtos can be serialized if needed.
 *
 * @author Ståle Undheim, 2023.09.13 stale.undheim@storebrand.no
 */
public final class Extension_MatsAnnotatedClass extends AbstractMatsAnnotatedClass
        implements BeforeEachCallback, AfterEachCallback {


    private Extension_MatsAnnotatedClass(Extension_Mats extensionMats) {
        super(extensionMats);
    }

    /**
     * Create a new Extension_MatsSpring instance, register to Junit using
     * {@link org.junit.jupiter.api.extension.RegisterExtension}.
     * @param extensionMats {@link Extension_Mats} to read the {@link MatsFactory} from.
     * @return a new {@link Extension_MatsAnnotatedClass}
     */
    public static Extension_MatsAnnotatedClass create(Extension_Mats extensionMats) {
        return new Extension_MatsAnnotatedClass(extensionMats);
    }

    /**
     * Add classes to act as a source for annotations to register Mats endpoints for each test.
     */
    public Extension_MatsAnnotatedClass withClasses(Class<?>... annotatedMatsClasses) {
        addClasses(annotatedMatsClasses);
        return this;
    }

    @Override
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    // AvoidAccessibilityAlteration - need to reflect into test class to get fields.
    public void beforeEach(ExtensionContext extensionContext) {
        beforeEach(extensionContext.getTestInstance().orElse(null));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        afterEach();
    }

}
