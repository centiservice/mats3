package io.mats3.test.abstractunit;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.util.ReflectionUtils;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.spring.MatsSpringAnnotationRegistration;

/**
 * Base class used in specific test runtimes to support testing of classes annotated with Mats annotations.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-01-09
 */
public class AbstractMatsAnnotatedClass {

    private final AbstractMatsTest _matsTest;

    // List of endpoints that we have created. Since this util can be used in conjunction with other tools to create
    // endpoints, we need to know which endpoint we created for a test, so that we can remove them after the test, but
    // not remove unrelated endpoints.
    private final List<MatsEndpoint<?, ?>> _registeredEndpoints = new ArrayList<>();

    private final AnnotationConfigApplicationContext _applicationContext = new AnnotationConfigApplicationContext(
            // Register the MatsSpringAnnotationRegistration, which is responsible for reading Mats annotations
            MatsSpringAnnotationRegistration.class
    );
    private final List<String> _uninitializedBeans = new ArrayList<>();

    protected AbstractMatsAnnotatedClass(AbstractMatsTest matsTest) {
        _matsTest = matsTest;
    }

    public void beforeEach(Object testInstance) {
        MatsFactory matsFactory = _matsTest.getMatsFactory();
        // Note: we need to register the MatsFactory as a singleton, otherwise the MatsSpringAnnotationRegistration
        //       will register the MatsFactory to the Spring context, and start it when the Spring context starts.
        //       This will cause duplicate start of MatsFuturizer threads, which will throw IllegalStateException.
        _applicationContext.getBeanFactory().registerSingleton("matsFactory", matsFactory);

        // If we have a test instance, we register each non-null field as a singleton in the Spring context.
        // This is so that those fields are available to inject into the Mats annotated classes.
        if (testInstance != null) {
            ReflectionUtils.doWithFields(testInstance.getClass(), field -> {
                field.setAccessible(true);
                String beanName = field.getName();
                Object beanInstance = field.get(testInstance);

                // ?: Is there no bean registered with this name, and we have a value for the field?
                if (!_applicationContext.containsBean(beanName) && beanInstance != null) {
                    // Yes -> add this to the Spring context as a singleton
                    _applicationContext.getBeanFactory().registerSingleton(beanName, beanInstance);
                }
            });
        }
        initializeBeansAndRegisterEndpoints();
    }

    public void afterEach() {
        for (MatsEndpoint<?, ?> endpoint : _registeredEndpoints) {
            endpoint.remove(30_000);
        }
        _applicationContext.close();
    }

    /**
     * Add a class annotated with Mats annotations, so that it will be created and endpoints registered for each test.
     */
    public void registerMatsAnnotatedClasses(Class<?>... annotatedMatsClasses) {
        for (Class<?> annotatedMatsClass : annotatedMatsClasses) {
            addAnnotatedMatsBean(new AnnotatedGenericBeanDefinition(annotatedMatsClass));
        }
        initializeBeansAndRegisterEndpoints();
    }

    /**
     * Add an already instantiated instance of a class annotated with Mats annotations to the MatsFactory
     *
     * @param annotatedMatsInstances
     *         to introspect for annotations, and add to the MatsFactory.
     */
    public void registerMatsAnnotatedInstances(Object... annotatedMatsInstances) {
        for (Object annotatedMatsInstance: annotatedMatsInstances) {
            GenericBeanDefinition beanDefinition = new AnnotatedGenericBeanDefinition(annotatedMatsInstance.getClass());
            beanDefinition.setInstanceSupplier(() -> annotatedMatsInstance);

            addAnnotatedMatsBean(beanDefinition);
        }
        initializeBeansAndRegisterEndpoints();
    }

    /**
     * Internal method to register a bean with the Spring context, and add it to the list of beans that need to be
     * initialized.
     *
     * @param beanDefinition to register with the Spring context.
     */
    private void addAnnotatedMatsBean(GenericBeanDefinition beanDefinition) {
        String beanName = beanDefinition.getBeanClass().getSimpleName();

        try {
            // Register the matsAnnotatedClass, and refresh the Spring context. This will cause the
            // MatsSpringAnnotationRegistration to read the Mats annotations, and register the endpoints
            // with the MatsFactory.
            _applicationContext.registerBeanDefinition(beanName, beanDefinition);
            _uninitializedBeans.add(beanName);
        }
        catch (UnsatisfiedDependencyException e) {
            // Add another wrapper to report this as a test failure, and provide some hints towards what
            // could be the cause for the UnsatisfiedDependencyException.
            throw new AssertionError("Failed to create bean of type [" + beanName + "]"
                                     + " with dependencies from test class fields."
                                     + " Ensure that all dependencies are present as fields. Use @Mock with"
                                     + " Mockito to initialize fields.", e);
        }
    }

    /**
     * Helper method to call after we have added one or more beans to the Spring context, that have not yet been
     * initialized. This will force initialization of the beans, and register any new endpoints with the MatsFactory.
     * If the application context has not yet been initialized, this will do nothing, and the beans will be initialized
     * when the {@link #beforeEach(Object)} method is called.
     */
    private void initializeBeansAndRegisterEndpoints() {
        // If this is called before the matsFactory is added to the application context, we cannot yet
        // initialize the beans, as the MatsSpringAnnotationRegistration will not be able to register the
        // endpoints with the MatsFactory.
        // Also, if there are no beans to initialize, we can skip this.
        if (!_applicationContext.containsBean("matsFactory") || _uninitializedBeans.isEmpty()) {
            return;
        }

        // We first need to capture the current set of endpoints, so that we can detect any new endpoints
        // created by forcing the bean initialization.
        MatsFactory matsFactory = _applicationContext.getBean(MatsFactory.class);
        List<MatsEndpoint<?, ?>> initialEndpoints = matsFactory.getEndpoints();
        // All beans that we create, will not be initialized, as nothing depends on them. So we force
        // initialization by calling getBean on each of them.
        _uninitializedBeans.forEach(_applicationContext::getBean);
        _uninitializedBeans.clear();

        List<MatsEndpoint<?, ?>> currentEndpoints = matsFactory.getEndpoints();
        // Add all new endpoints that where created by initializing the beans.
        currentEndpoints.stream()
                .filter(endpoint -> !initialEndpoints.contains(endpoint))
                .forEach(_registeredEndpoints::add);
    }

}
