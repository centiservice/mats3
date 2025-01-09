package io.mats3.test.abstractunit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;
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
    private final List<MatsEndpoint<?, ?>> _endpoints = new ArrayList<>();
    private final List<Class<?>> _annotatedMatsClasses = new ArrayList<>();

    protected AbstractMatsAnnotatedClass(AbstractMatsTest matsTest) {
        _matsTest = matsTest;
    }

    /**
     * Add classes to act as a source for annotations to register Mats endpoints for each test.
     */
    public void addClasses(Class<?>... annotatedMatsClasses) {
        Collections.addAll(_annotatedMatsClasses, annotatedMatsClasses);
    }

    public void beforeEach(Object testInstance) {
        // If we have no classes, then early exit.
        if (_annotatedMatsClasses.isEmpty()) {
            return;
        }

        MatsFactory matsFactory = _matsTest.getMatsFactory();
        try (AnnotationConfigApplicationContext applicationcontext = new AnnotationConfigApplicationContext()) {
            // Register the MatsSpringAnnotationRegistration, which is responsible for reading Mats annotations
            applicationcontext.registerBean(MatsSpringAnnotationRegistration.class);

            // Note: we need to register the MatsFactory as a singleton, otherwise the MatsSpringAnnotationRegistration
            //       will register the MatsFactory to the Spring context, and start it when the Spring context starts.
            //       This will cause duplicate start of MatsFuturizer threads, which will throw IllegalStateException.
            applicationcontext.getBeanFactory().registerSingleton("matsFactory", matsFactory);

            for (Class<?> matsAnnotatedClass : _annotatedMatsClasses) {
                String matsBeanName = matsAnnotatedClass.getSimpleName();
                try {
                    // Register the matsAnnotatedClass, and refresh the Spring context. This will cause the
                    // MatsSpringAnnotationRegistration to read the Mats annotations, and register the endpoints
                    // with the MatsFactory.
                    applicationcontext.registerBean(matsBeanName, matsAnnotatedClass);
                }
                catch (UnsatisfiedDependencyException e) {
                    // Add another wrapper to report this as a test failure, and provide some hints towards what
                    // could be the cause for the UnsatisfiedDependencyException.
                    throw new AssertionError("Failed to create bean of type [" + matsBeanName + "]"
                                             + " with dependencies from test class fields."
                                             + " Ensure that all dependencies are present as fields. Use @Mock with"
                                             + " Mockito to initialize fields.", e);
                }
            }

            // Register each dependency as a singleton, so that we can properly construct the matsAnnotatedClass
            // from the Spring context.
            if (testInstance != null) {
                ReflectionUtils.doWithFields(testInstance.getClass(), field -> {
                    field.setAccessible(true);
                    String beanName = field.getName();
                    Object beanInstance = field.get(testInstance);

                    // ?: Is there no bean registered with this name, and we have a value for the field?
                    if (!applicationcontext.containsBean(beanName) && beanInstance != null) {
                        // Yes -> add this to the Spring context as a singleton
                        applicationcontext.getBeanFactory().registerSingleton(beanName, beanInstance);
                    }
                });
            }

            // Capture current mats endpoint before we refresh the context, so that we can determine which endpoints
            // are added when Spring refreshes the context. We need to know which endpoints are added, so that we can
            // remove them after each test.
            List<MatsEndpoint<?, ?>> initialEndpoints = matsFactory.getEndpoints();
            applicationcontext.refresh();
            matsFactory.getEndpoints().stream()
                    .filter(endpoint -> !initialEndpoints.contains(endpoint))
                    .forEach(_endpoints::add);
        }
    }

    /**
     * Add an already instantiated instance of a class annotated with Mats annotations to the MatsFactory
     *
     * @param annotatedMatsBeans
     *         to introspect for annotations, and add to the MatsFactory.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    // unchecked, rawtypes: Need to use unchecked and rawtypes here, as we really don't need to enforce generics here.
    public void addAnnotatedMatsBeans(Object... annotatedMatsBeans) {
        MatsFactory matsFactory = _matsTest.getMatsFactory();

        try (StaticApplicationContext applicationContext = new StaticApplicationContext()) {
            applicationContext.getBeanFactory().registerSingleton("matsFactory", matsFactory);

            // Use the MatsSpringAnnotationRegistration to register the endpoints based on annotations on the class,
            // with the MatsFactory.
            MatsSpringAnnotationRegistration matsSpringAnnotationRegistration = new MatsSpringAnnotationRegistration();
            matsSpringAnnotationRegistration.setApplicationContext(applicationContext);

            for (Object annotatedMatsBean : annotatedMatsBeans) {
                Class beanClass = annotatedMatsBean.getClass();
                String beanName = beanClass.getSimpleName();
                applicationContext.registerBean(beanName, beanClass, () -> annotatedMatsBean);
                matsSpringAnnotationRegistration.postProcessAfterInitialization(annotatedMatsBean, beanName);
            }

            // Capture the current endpoints prior to registering new endpoints
            List<MatsEndpoint<?, ?>> initialEndpoints = matsFactory.getEndpoints();
            // Register all endpoints with the MatsFactory that was added in the prior loop
            matsSpringAnnotationRegistration.onContextRefreshedEvent(new ContextRefreshedEvent(applicationContext));

            // Find the newly added endpoints, and add them to our list of endpoints to shut down after each test.
            matsFactory.getEndpoints().stream()
                    .filter(endpoint -> !initialEndpoints.contains(endpoint))
                    .forEach(_endpoints::add);
        }
    }

    public void afterEach() {
        for (MatsEndpoint<?, ?> endpoint : _endpoints) {
            endpoint.remove(30_000);
        }
    }
}
