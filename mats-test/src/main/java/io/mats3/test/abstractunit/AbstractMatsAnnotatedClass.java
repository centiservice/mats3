/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.test.abstractunit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.util.ReflectionUtils;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.spring.MatsSpringAnnotationRegistration;

/**
 * Base class used for Rule_MatsAnnotatedClass and Extension_MatsAnnotatedClass to support testing of classes annotated
 * with Mats annotations.
 * <p>
 * Worth remembering that this class is instantiated for each method in the test class (along with the actual test class
 * instance!), and thus the Spring context is recreated for each test method even though it is in the constructor.
 * However, the MatsFactory is expected to be a class singleton (typically from the ClassRule static
 * Rule/Extension_Mats), and thus the MatsFactory is reused across all tests in the test class.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-01-09
 * @author Endre Stølsvik - 2025-02-05 - http://endre.stolsvik.com
 */
public abstract class AbstractMatsAnnotatedClass {
    private static final Logger log = LoggerFactory.getLogger(AbstractMatsAnnotatedClass.class);

    public static final String LOG_PREFIX = "#MATSTEST:MAC# ";

    // List of endpoints that we have created. Since this util can be used in conjunction with other tools to create
    // endpoints, we need to know which endpoint we created for a test, so that we can remove them after the test, but
    // not remove unrelated endpoints. One of these is the MatsFuturizer of the test class's Rule|Extension_Mats.
    private final List<MatsEndpoint<?, ?>> _registeredEndpoints = new ArrayList<>();

    private final List<String> _uninitializedBeans = new ArrayList<>();

    // Used to enable the overriding of the field beans for Jupiter, where fields from the inner, @Nested test class
    // should take precedence over the fields from the outer, "parent" test class.
    private final Set<Class<?>> _registeredFieldTypes = new HashSet<>();

    // To detect duplicate registrations of the same class, we keep track of the registration locations.
    private final Map<String, StackTraceElement> _registrationLocations = new HashMap<>();

    private final AnnotationConfigApplicationContext _applicationContext;

    protected AbstractMatsAnnotatedClass() {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Instantiating AbstractMatsAnnotatedClass (" + this.getClass()
                .getSimpleName() + "), creating Spring Context - on this: " + idThis());

        // Create Spring Context
        _applicationContext = new AnnotationConfigApplicationContext();
        // Register the BeanPostProcessor 'MatsSpringAnnotationRegistration' in its special testing mode - this is
        // responsible for reading Mats annotations on the beans we later register, and then registering the endpoints
        // with the MatsFactory. It will ignore any MatsFactory qualifiers, and if it encounters a double registration
        // (i.e. the same endpoint registered twice), it will ignore the second registration - the rationale being that
        // using qualifiers, you may have the same endpoint registered multiple times, but with different qualifiers.
        // This should be irrelevant for the testing, so the testing mode ignores qualifiers and only registers once.
        _applicationContext.registerBean(MatsSpringAnnotationRegistration.class,
                () -> MatsSpringAnnotationRegistration.createForTesting_IgnoreQualifiersAndDoubleRegistration());

        // Start the Spring context. Note that this will also call the MatsSpringAnnotationRegistration's
        // ContextRefreshedEvent. This will put it into "immediate annotation processing"-mode for subsequent
        // Mats-annotated beans that are registered, and thus read their annotations and start them immediately.
        _applicationContext.refresh();
    }

    protected AbstractMatsAnnotatedClass(MatsFactory matsFactory) {
        this();
        setMatsFactory(matsFactory);
    }

    protected MatsFactory _matsFactory;
    private List<Object> _testInstances;
    private final Map<String, BeanDefinition> _beanDefinitionToRegisterAtBeforeEach = new HashMap<>();
    private boolean _beforeEachCalled = false;

    protected void setMatsFactory(MatsFactory matsFactory) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Setting MatsFactory: " + matsFactory + " - on this: "
                + idThis());

        _matsFactory = matsFactory;

        // We register the MatsFactory as a singleton to "hide it" from the MatsSpringAnnotationRegistration's
        // postProcessAfterInitialization(..)-invocations, as otherwise it will start the MatsFactory on
        // ContextRefreshedEvent, which by itself isn't bad (starting is idempotent), but it also stops it (and thus
        // all endpoints) on ContextClosedEvent. We want to control stopping of the endpoints ourselves, since we only
        // want to stop the endpoints that we have created, so as not to interfere with other endpoints that might be
        // created by other means.
        _applicationContext.getBeanFactory().registerSingleton("matsFactory", _matsFactory);
    }

    /**
     * This is invoked by the implemntations Rule/Extension_MatsAnnotatedClass before each test method. The list shall
     * have the test class instance(s) that this Rule/Extension_MatsAnnotatedClass is part of. Jupiter have a concept of
     * nested tests by using the @Nested annotation, which will result in multiple test class instances in the list,
     * where the inner test class instance is the first in the list, and the outer test class instance is the last in
     * the list (leaf-to-parent).
     *
     * @param testInstances
     *            the test class instance(s) - can be multiple for Jupiter nested tests due to the nesting logic
     *            available in Jupiter (@Nested tests). The inner test class instance shall be the first in the list,
     *            and the outer test class instance is the last in the list (leaf-to-parent).
     */
    public void beforeEach(List<Object> testInstances) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "beforeEach: Set up for " + testInstances + " - on this: "
                + this.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this)));

        // Store the test instance for later reading of fields to register as beans.
        _testInstances = testInstances;

        // If this Rule was field-inited with 'registerMatsAnnotatedClasses', we need to initialize those beans now.
        _beanDefinitionToRegisterAtBeforeEach.forEach(_applicationContext::registerBeanDefinition);
        _beanDefinitionToRegisterAtBeforeEach.clear();
        initializeBeansAndRegisterEndpoints();

        // Set the flag that beforeEach has been called, so that we will henceforth register beans immediately.
        _beforeEachCalled = true;
    }

    public void afterEach() {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "afterEach: Remove Mats3 endpoints we created, and close"
                + " Spring context - on this: "
                + this.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this)));
        for (MatsEndpoint<?, ?> endpoint : _registeredEndpoints) {
            if (log.isDebugEnabled()) log.debug(LOG_PREFIX + " \\- Removing endpoint: " + endpoint);
            endpoint.remove(30_000);
        }
        _applicationContext.close();
    }

    /**
     * Add a class annotated with Mats annotations, so that it will be created and endpoints added to the MatsFactory,
     * for each test. This may be invoked both on field initialization (i.e. on the rule definition itself), and inside
     * the test methods.
     *
     * @param annotatedMatsClasses
     *            to introspect for annotations, instantiated, and add the described endpoint the MatsFactory.
     */
    public void registerMatsAnnotatedClasses(Class<?>... annotatedMatsClasses) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "registerMatsAnnotatedClasses: "
                + Arrays.asList(annotatedMatsClasses) + " - on this: "
                + this.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this)));
        for (Class<?> annotatedMatsClass : annotatedMatsClasses) {
            addAnnotatedMatsBean(new AnnotatedGenericBeanDefinition(annotatedMatsClass));
        }
    }

    /**
     * Add an already instantiated instance of a class annotated with Mats annotations, so that the endpoint will be
     * added to the MatsFactory, for each test. This may be invoked both on field initialization (i.e. on the rule
     * definition itself), and inside the test methods.
     *
     * @param annotatedMatsInstances
     *            to introspect for annotations, and add the described endpoint to the MatsFactory.
     */
    public void registerMatsAnnotatedInstances(Object... annotatedMatsInstances) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "registerMatsAnnotatedInstances: "
                + Arrays.asList(annotatedMatsInstances) + " - on this: "
                + this.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this)));
        for (Object annotatedMatsInstance : annotatedMatsInstances) {
            GenericBeanDefinition beanDefinition = new AnnotatedGenericBeanDefinition(annotatedMatsInstance.getClass());
            beanDefinition.setInstanceSupplier(() -> annotatedMatsInstance);

            addAnnotatedMatsBean(beanDefinition);
        }
    }

    /**
     * Internal method to register a bean with the Spring context, and add it to the list of beans that need to be
     * initialized.
     *
     * @param beanDefinition
     *            to register with the Spring context.
     */
    private void addAnnotatedMatsBean(GenericBeanDefinition beanDefinition) {
        String beanName = beanDefinition.getBeanClass().getName();

        if (_registrationLocations.containsKey(beanName)) {
            throw new AssertionError("Bean of type [" + beanName + "] already exists in the Spring context."
                    + "\n  Did you register the same class twice? Perhaps in a nested class?"
                    + "\n  Previous registration was at [" + _registrationLocations.get(beanName) + "]\n");
        }

        // There are 2 scenarios that we need to consider here, either the method call is directly on
        // AbstractMatsAnnotatedClass, or it is called from a subclass. We know our entry point is at
        // index 2, but we do not know if we are called directly, or from a subclass. As such we skip 3
        // elements, and then skip while the class name is the same as the current class. This should give us
        // the correct calling location.
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        int index = 3;
        while (stackTrace[index].getClassName().equals(getClass().getName())) {
            index++;
        }
        // Register where we found this bean, so that in case we have another registration of the same Bean, we can
        // provide a nice message to the user about where the previous registration of the same bean was.
        _registrationLocations.put(beanName, stackTrace[index]);

        // :: Register the matsAnnotatedClass.
        _uninitializedBeans.add(beanName);

        // ?: Have we already called beforeEach?
        if (_beforeEachCalled) {
            // -> Yes, beforeEach is already called, so we do immediate registration.
            _applicationContext.registerBeanDefinition(beanName, beanDefinition);
            initializeBeansAndRegisterEndpoints();
        }
        else {
            // -> No, we are not in beforeEach, so we defer registration until beforeEach is called.
            _beanDefinitionToRegisterAtBeforeEach.put(beanName, beanDefinition);
        }
    }

    /**
     * Helper method to call after we have added one or more beans to the Spring context, that have not yet been
     * initialized. This will force initialization of the beans, and register any new endpoints with the MatsFactory.
     */
    private void initializeBeansAndRegisterEndpoints() {
        // Register the fields from the test instance as beans in the Spring context.
        // Note that we might end up doing this multiple times, but it is idempotent, so it is safe.
        // The reason for multiple times, is to do it as late as possible, to handle late initialization of Mockito
        // mocks, which in some setups are initialized after beforeEach is called.
        _testInstances.forEach(this::addTestFieldsAsBeans);

        // We first need to capture the current set of endpoints, so that we can detect any new endpoints
        // created by forcing the bean initialization.
        MatsFactory matsFactory = _applicationContext.getBean(MatsFactory.class);
        List<MatsEndpoint<?, ?>> endpointsBeforeForcingBeans = matsFactory.getEndpoints();

        // :: Force initialization of all beans that we have added to the Spring context.
        // All beans that we create, will not be initialized, as nothing depends on them. So we force
        // initialization by calling getBean on each of them. This will cause the MatsSpringAnnotationRegistration to
        // read the Mats annotations, and register the endpoints with the MatsFactory.
        for (String uninitializedBean : _uninitializedBeans) {
            try {
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "\\- Forcing initialization of bean: "
                        + uninitializedBean);
                _applicationContext.getBean(uninitializedBean);
            }
            catch (UnsatisfiedDependencyException e) {
                // Add another wrapper to report this as a test failure, and provide some hints towards what
                // could be the cause for the UnsatisfiedDependencyException.
                throw new AssertionError("Failed to create bean of type [" + uninitializedBean + "]"
                        + " with dependencies from test class's fields."
                        + " Ensure that all dependencies for the bean(s) are present as fields.\n"
                        + "    You may use Mockito's @Mock annotation for this - but notice that if you use JUnit 4 in"
                        + " combination with field init endpoint registration of this Rule, you should use"
                        + " the Mockito Rule:\n"
                        + "    '@Rule public MockitoRule _mockitoRule ="
                        + " MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)'  instead of"
                        + "  '@RunWith(MockitoJUnitRunner.StrictStubs.class)' on the class.", e);
            }
        }
        // We've done the forced initialization of beans, so clear the list of uninitialized beans for next round.
        _uninitializedBeans.clear();

        // :: Calculate which endpoints were added by the above operations, and add them to the list of registered
        // endpoints to be removed in the afterEach.
        List<MatsEndpoint<?, ?>> endpointsAfterForcingBeans = matsFactory.getEndpoints();
        endpointsAfterForcingBeans.stream()
                .filter(endpoint -> !endpointsBeforeForcingBeans.contains(endpoint))
                .forEach(_registeredEndpoints::add);
    }

    private void addTestFieldsAsBeans(Object testInstance) {
        // ?: I have no clue why this could ever be the case, but original author had a check for this!
        if (testInstance == null) {
            return;
        }
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "\\- Registering \"field beans\" from test instance as"
                + " Spring beans: " + testInstance);

        ReflectionUtils.doWithFields(testInstance.getClass(), field -> {
            field.setAccessible(true);
            String fieldName = field.getName();
            String descFieldName = testInstance.getClass().getSimpleName() + "." + fieldName;
            String beanName = testInstance.getClass().getName() + "." + fieldName; // Unique name for the bean
            Object fieldInstance = field.get(testInstance);
            if (log.isTraceEnabled()) log.trace(LOG_PREFIX + ".. Evaluating field: " + field + ", instance: "
                    + fieldInstance);

            // :: Skip registering meaningless stuff.

            // ?: Do we have a value for the field?
            if (fieldInstance == null) {
                // -> No, then we cannot register this as a bean
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "   \\- Skipping field [" + descFieldName
                        + "], as it is currently null.");
                return;
            }
            // ?: Is it the [Rule|Extension]_MatsAnnotatedClass?
            if (fieldInstance instanceof AbstractMatsAnnotatedClass) {
                // -> Yes, then we should not register this as a bean
                if (log.isTraceEnabled()) log.trace(LOG_PREFIX + "   \\- Skipping field [" + descFieldName
                        + "], as it is a [Rule|Extension]_MatsAnnotatedClass.");
                return;
            }
            // ?: Is it the [Rule|Extension]_Mats?
            if (fieldInstance instanceof AbstractMatsTest) {
                // -> Yes, then we should not register this as a bean
                if (log.isTraceEnabled()) log.trace(LOG_PREFIX + "   \\- Skipping field [" + descFieldName
                        + "], as it is a [Rule|Extension]_Mats.");
                return;
            }
            // ?: Is it a MatsFactory?
            if (fieldInstance instanceof MatsFactory) {
                // -> Yes, then we should not register this as a bean
                if (log.isTraceEnabled()) log.trace(LOG_PREFIX + "   \\- Skipping field [" + descFieldName
                        + "], as it is a MatsFactory.");
                return;
            }

            // ?: Is this a synthetic field created by the compiler for the enclosing class?
            if (field.getType().equals(testInstance.getClass().getEnclosingClass()) && field.isSynthetic()) {
                // -> Yes, so we should not register this as a bean
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "   \\- Skipping the enclosing class"
                        + " represented by synthetic field [" + descFieldName + "]: "
                        + testInstance.getClass().getEnclosingClass());
                return;
            }

            // :: If we find a field with the same type as we've already registered, we skip it.
            // This makes the feature whereby fields from the inner, @Nested test class should take precedence over
            // the fields from the outer, "parent" test class.

            // ?: Is there already a bean with this type?
            if (_registeredFieldTypes.contains(field.getType())) {
                // -> Yes, there is already a bean with this type, so we skip it.
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "   \\- Skipping field [" + descFieldName
                        + "], as a bean with the same type already exists.");
                return;
            }

            // :: Passed checks, register the field as a bean in the Spring context.

            // ?: Is there already a bean with this name?
            if (!_applicationContext.containsBean(beanName)) {
                // -> No, no existing bean -> add this to the Spring context as a singleton
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "   \\- Registering field [" + descFieldName
                        + "] as Spring bean: " + beanName + " -> " + fieldInstance);
                _applicationContext.getBeanFactory().registerSingleton(beanName, fieldInstance);
                _registeredFieldTypes.add(field.getType());
            }
            else {
                // -> Yes, it is already, so skip it.
                if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "   \\- Skipping field, as a bean with the same"
                        + " name already exists: " + beanName);
            }
        });
    }

    protected String id(Object instance) {
        return instance.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(instance));
    }

    protected String idThis() {
        return id(this);
    }
}
