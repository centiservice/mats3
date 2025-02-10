package io.mats3.test.jupiter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.mats3.MatsFactory;
import io.mats3.test.abstractunit.AbstractMatsAnnotatedClass;

/**
 * Helper class to test Mats3 Endpoints which are defined using the Mats3 SpringConfig annotations, but without using
 * the full Spring harness for testing. That is, you write tests in a "pure Java" style, but can still test your Mats3
 * SpringConfig annotation defined Mats endpoints. This can be terser and faster than using the full Spring test
 * harness.
 * <p>
 * By having at least one such test per SpringConfig defined Mats3 endpoints, you ensure an integration test of the
 * SpringConfig aspects of your Mats endpoints: The annotations are read, the endpoint is set up, and the STOs (state)
 * and DTOs (data) can be instantiated and serialized. For corner case unit testing where you want lots of tests, you
 * can instead invoke the individual methods directly on the instantiated endpoint class, thus getting the fastests
 * speeds.
 * <p>
 * The solution creates a Spring context internally to initialize the Mats endpoints, but this should be viewed as an
 * implementation detail, and the Spring context is not made available for the test - the specified SpringConfig
 * annotated endpoints just turns up in the MatsFactory so that you can test it using e.g. the MatsFuturizer. The Spring
 * context is created anew for each test, and is not shared between tests. Endpoints specified at the Extension's field
 * init will be registered anew on the provided {@link MatsFactory} for each test method. All Endpoints are deleted
 * after the test method has run. (The supplied MatsFactory and its underlying MQ Broker is shared between all tests.)
 * <p>
 * <b>"Field beans" dependency injection and dependency overriding: </b> Fields in the test class are read out, and
 * entered as beans into the Spring context, thus made available for injection into the Mats annotated classes when
 * using the {@link #withAnnotatedMatsClasses(Class...)} method. <b>Overriding:</b> When using Jupiter's @Nested
 * feature, you may override fields from the outer test class by declaring a field with the same type in the
 * inner @Nested test class. There is no support for qualifiers, so if your Mats-annotated endpoints require multiple
 * beans of the same type (made unique by qualifiers), you will have to use a full Spring setup for your tests.
 * <p>
 * <b>MatsFactory qualifiers</b>: If the Mats annotated endpoints has qualifiers for the MatsFactory, these will be
 * ignored. For the same reason, any duplicate endpointIds will be ignored, and only one instance will be registered:
 * The reason for using qualifiers are that you have multiple MatsFactories in the application, and some endpoints might
 * be registered on multiple MatsFactories (repeating the annotation with the same endpointId, but with different
 * qualifier). For the testing scenario using this class, we only have one MatsFactory, which will be used for all
 * endpoints, and duplicates will thus have to be ignored. If this is a problem, you will have to use a full Spring
 * setup for your tests.
 * <p>
 * Classes with SpringConfig Mats3 Endpoints can either be registered using the
 * {@link #withAnnotatedMatsClasses(Class...)} method, or by using the {@link #withAnnotatedMatsInstances(Object...)}
 * method. This can both be done at the field initialization of the Extension, or inside the test method.
 * <p>
 * <b>The classes-variant</b> is intended to be used on creation of the Extension, i.e. at the field initialization
 * point - but can also be used inside the test method. Technically, it will register the class in a Spring context, and
 * put all the fields of the test class as beans available for injection on that class - that is, dependencies in the
 * Mats3 annotated classes will be resolved using the fields of the test class. It is important that fields are
 * initialized before this extension runs, if there are dependencies in the test class needed by the Mats annotated
 * class. When using Mockito and the {@code @Mock} annotation, this is resolved automatically, since Mockito will
 * initialize the fields before the Extension is created. Mockito is started as normal, by annotating the test class
 * with {@code @ExtendWith(MockitoExtension.class)}.
 * <p>
 * <b>The instances-variant</b> registers the Mats annotated class as an Endpoint when called. You will then have to
 * initialize the class yourself, before registering it, probably using the constructor used when Spring otherwise would
 * constructor-inject the instance. This is relevant if you only have a few tests that need the endpoint, and possibly
 * other tests that are unit testing by calling directly on an instance of the class (i.e. calling directly on the
 * {@code @MatsMapping} or {@code @Stage} methods).
 * <p>
 * There are multiple examples in the test classes.
 *
 * @author Ståle Undheim, 2023.09.13 stale.undheim@storebrand.no
 * @author Endre Stølsvik 2025-02-05 16:32 - http://stolsvik.com/, endre@stolsvik.com
 */
public final class Extension_MatsAnnotatedClass extends AbstractMatsAnnotatedClass
        implements BeforeEachCallback, AfterEachCallback {

    private Extension_MatsAnnotatedClass() {
        super();
    }

    private Extension_MatsAnnotatedClass(MatsFactory matsFactory) {
        super(matsFactory);
    }

    /**
     * Create a new instance without MatsFactory - which will be found through the ExtensionContext if
     * {@link Extension_Mats} is also used.
     *
     * @return a new {@link Extension_MatsAnnotatedClass}
     */
    public static Extension_MatsAnnotatedClass create() {
        return new Extension_MatsAnnotatedClass();
    }

    /**
     * Create a new instance based on the supplied {@link MatsFactory} instance.
     *
     * @param matsFactory
     *            {@link MatsFactory} on which to register Mats endpoints for each test.
     * @return a new {@link Extension_MatsAnnotatedClass}
     */
    public static Extension_MatsAnnotatedClass create(MatsFactory matsFactory) {
        return new Extension_MatsAnnotatedClass(matsFactory);
    }

    /**
     * Create a new instance based on a {@link Extension_Mats} instance (from which the needed MatsFactory is gotten).
     *
     * @param extensionMats
     *            {@link Extension_Mats} to read the {@link MatsFactory} from, on which to register Mats endpoints for
     *            each test.
     * @return a new {@link Extension_MatsAnnotatedClass}
     */
    public static Extension_MatsAnnotatedClass create(Extension_Mats extensionMats) {
        return new Extension_MatsAnnotatedClass(extensionMats.getMatsFactory());
    }

    /**
     * Add classes to act as a source for annotations to register Mats endpoints for each test.
     */
    public Extension_MatsAnnotatedClass withAnnotatedMatsClasses(Class<?>... annotatedMatsClasses) {
        registerMatsAnnotatedClasses(annotatedMatsClasses);
        return this;
    }

    /**
     * Add instances of classes annotated with Mats annotations to register Mats endpoints for each test.
     */
    public Extension_MatsAnnotatedClass withAnnotatedMatsInstances(Object... annotatedMatsInstances) {
        super.registerMatsAnnotatedInstances(annotatedMatsInstances);
        return this;
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        // ?: Do we have the MatsFactory set yet?
        if (_matsFactory == null) {
            // -> No, so let's see if we can find it in the ExtensionContext (throws if not).
            Optional<Extension_Mats> matsFromContext = Extension_Mats.findFromContext(extensionContext);
            if (matsFromContext.isPresent()) {
                setMatsFactory(matsFromContext.get().getMatsFactory());
            }
            else {
                throw new IllegalStateException("MatsFactory is not set. Didn't find Extension_Mats in"
                        + " ExtensionContext, so couldn't get it from there either. Either set it explicitly"
                        + " using setMatsFactory(matsFactory), or use Extension_Mats (which adds itself to the"
                        + " ExtensionContext), and ensure that it is initialized before this"
                        + " Extension_MatsAnnotatedEndpoint.");
            }
        }

        // :: Find all the test instances: The instances of the test class, and the instances of the nested test classes
        // to the current test class running.
        List<Object> allInstances = new ArrayList<>(extensionContext.getRequiredTestInstances().getAllInstances());
        // By default, the instance order is from the root to the leaf. We instead want the leaf first, so that
        // fields there take precedence over fields higher up in the hierarchy.
        Collections.reverse(allInstances);
        beforeEach(allInstances);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        afterEach();
    }
}
