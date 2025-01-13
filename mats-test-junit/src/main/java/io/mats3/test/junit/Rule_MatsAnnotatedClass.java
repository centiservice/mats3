package io.mats3.test.junit;

import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

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
 * in the MatsFactory. The Spring context is created for each test, and is not shared between tests. The Endpoints will
 * be registered (anew) on the provided {@link MatsFactory} for each test method, and deleted after the test method has
 * run.
 * <p>
 * Classes with SpringConfig Mats3 Endpoints can either be registered using the
 * {@link #withAnnotatedMatsClasses(Class...)} method, or by using the {@link #withAnnotatedMatsInstances(Object...)}
 * method.
 * <p>
 * The classes-variant is intended to be used on creation of the Extension, i.e. at the field initialization point.
 * Technically, it will register the class in a Spring context, and put all the fields of the test class as beans
 * available for injection on that class - that is, dependencies in the Mats3 annotated classes will be resolved using
 * the fields of the test class. It is important that fields are initialized before this extension runs, if there are
 * dependencies in the test class needed by the Mats annotated class. When using Mockito and the {@code @Mock}
 * annotation, this is resolved automatically, since Mockito will initialize the fields before the Extension is created.
 * <p>
 * The instances-variant registers the Mats annotated class when called. You will then have to initialize the class
 * yourself, before registering it, probably using the constructor used when Spring otherwise would constructor-inject
 * the instance. This is relevant if you only have a few tests that need the endpoint, and possibly other tests that are
 * unit testing by calling directly on an instance of the class (i.e. calling directly on the {@code @MatsMapping} or
 * {@code @Stage} methods).
 * <p>
 * There are examples of both in the test classes, check out the Extension-based test class
 * {@code 'io.mats3.test.jupiter.J_ExtensionMatsAnnotatedClassTest' } for the broadest set of examples.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2024-11-21
 */
public class Rule_MatsAnnotatedClass extends AbstractMatsAnnotatedClass implements MethodRule {

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
