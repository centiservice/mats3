package io.mats3.test.junit;

import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory;
import io.mats3.test.abstractunit.AbstractMatsAnnotatedClass;

/**
 * Helper class to test Mats3 Endpoints which are defined using the Mats3 SpringConfig annotations, but without using
 * the full Spring harness for testing. That is, you write tests in a "pure Java" style, but can still test your
 * Spring-defined Mats endpoints. This can be terser and faster than using the full Spring test harness.
 * <p>
 * By having at least one such test, you ensure an "integration test" of the Mats endpoint by taking it up, checking the
 * annotations, and that state and DTOs can be instantiated and serialized.
 * <p>
 * The solution still creates a Spring context internally to initialize the Mats endpoints, but this should be viewed as
 * an implementation detail, and the Spring context is not made available for the test - the endpoints are just present
 * in the MatsFactory. The Spring context is created for each test, and is not shared between tests. The specified
 * Endpoints will be registered (anew) on the provided {@link MatsFactory} for each test method, and deleted after the
 * test method has run.
 * <p>
 * <b>MatsFactory qualifiers</b>: If the Mats annotated class has qualifiers for the MatsFactory, these will be ignored.
 * For the same reason, any duplicate endpointIds will be ignored, and only one instance will be registered: The reason
 * for using qualifiers are that you have multiple MatsFactories in the application, and some endpoints might be
 * registered on multiple MatsFactories (repeating the annotation with the same endpointId, but with different
 * qualifier). For the testing scenario using this class, we only have one MatsFactory, which will be used for all
 * endpoints, and duplicates will thus have to be ignored. If this is a problem, you should consider using the full
 * Spring test harness.
 * <p>
 * Classes with SpringConfig Mats3 Endpoints can either be registered using the
 * {@link #withAnnotatedMatsClasses(Class...)} method, or by using the {@link #withAnnotatedMatsInstances(Object...)}
 * method. This can both be done at the field initialization of the Rule, or inside the test method.
 * <p>
 * <b>The classes-variant</b> is intended to be used on creation of the Rule, i.e. at the field initialization point -
 * but can also be used inside the test method. Technically, it will register the class in a Spring context, and put all
 * the fields of the test class as beans available for injection on that class - that is, dependencies in the Mats3
 * annotated classes will be resolved using the fields of the test class. It is important that fields are initialized
 * before this extension runs, if there are dependencies in the test class needed by the Mats annotated class. You may
 * also use Mockito mocks: To start Mockito, there's multiple ways:
 * <ol>
 * <li>Annotate the test class with {@code @RunWith(MockitoJUnitRunner.StrictStubs.class)}.</li>
 * <li>Use the Mockito rule
 * {@code @Rule public MockitoRule _mockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)}.</li>
 * <li>Call {@code MockitoAnnotations.initMocks(this)} in a {@code @Before} method.</li>
 * <li>Manually create the mocks using {@code Mockito.mock(ServiceToMock.class)}, and manually inject them on the mats
 * annotated endpoint class's instance, inside the test method.</li>
 * </ol>
 * Note that if you use "field init endpoint registration" of the {@link Rule_MatsAnnotatedClass}, you need to use the
 * rule variant, or manually create the mocks, due to initialization order.
 * <p>
 * <b>The instances-variant</b> registers the Mats annotated class as an Endpoint when called. You will then have to
 * initialize the class yourself, before registering it, probably using the constructor used when Spring otherwise would
 * constructor-inject the instance. This is relevant if you only have a few tests that need the endpoint, and possibly
 * other tests that are unit testing by calling directly on an instance of the class (i.e. calling directly on the
 * {@code @MatsMapping} or {@code @Stage} methods).
 * <p>
 * There are multiple examples in the test classes.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2024-11-21
 * @author Endre Stølsvik 2025-02-05 16:32 - http://stolsvik.com/, endre@stolsvik.com
 */
// This is MethodRule instead of a TestRule, since we need the target test class instance to read the fields off of.
public class Rule_MatsAnnotatedClass extends AbstractMatsAnnotatedClass implements MethodRule {
    private static final Logger log = LoggerFactory.getLogger(Rule_MatsAnnotatedClass.class);

    private Rule_MatsAnnotatedClass(MatsFactory matsFactory) {
        super(matsFactory);
    }

    /**
     * Create a new Rule_MatsSpring instance based on the supplied {@link MatsFactory} instance, register to Junit using
     * {@link org.junit.Rule}.
     *
     * @param matsFactory
     *            the {@link MatsFactory} on which to register Mats endpoints for each test.
     * @return a new {@link Rule_MatsAnnotatedClass}
     */
    public static Rule_MatsAnnotatedClass create(MatsFactory matsFactory) {
        return new Rule_MatsAnnotatedClass(matsFactory);
    }

    /**
     * Create a new Rule_MatsSpring instance based on a {@link Rule_Mats} instance (from which the needed MatsFactory is
     * gotten), register to Junit using {@link org.junit.Rule}.
     *
     * @param ruleMats
     *            {@link Rule_Mats} to read the {@link MatsFactory} from, on which to register Mats endpoints for each
     *            test.
     * @return a new {@link Rule_MatsAnnotatedClass}
     */
    public static Rule_MatsAnnotatedClass create(Rule_Mats ruleMats) {
        return new Rule_MatsAnnotatedClass(ruleMats.getMatsFactory());
    }

    /**
     * Add classes to act as a source for annotations to register Mats endpoints for each test.
     *
     */
    public Rule_MatsAnnotatedClass withAnnotatedMatsClasses(Class<?>... annotatedMatsClasses) {
        super.registerMatsAnnotatedClasses(annotatedMatsClasses);
        return this;
    }

    /**
     * Add instances of classes annotated with Mats annotations to register Mats endpoints for each test.
     */
    public Rule_MatsAnnotatedClass withAnnotatedMatsInstances(Object... annotatedMatsInstances) {
        super.registerMatsAnnotatedInstances(annotatedMatsInstances);
        return this;
    }

    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        log.debug("Apply Rule_MatsAnnotatedClass to test method [" + method.getName() + "], "
                + "target [" + target + "], base [" + base + "]");
        return new Statement() {
            public void evaluate() throws Throwable {
                beforeEach(target);
                try {
                    base.evaluate();
                }
                finally {
                    afterEach();
                }
            }
        };
    }
}
