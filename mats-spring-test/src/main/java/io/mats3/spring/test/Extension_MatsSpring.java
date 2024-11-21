package io.mats3.spring.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.util.ReflectionUtils;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.spring.MatsSpringAnnotationRegistration;
import io.mats3.test.jupiter.Extension_Mats;

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
public final class Extension_MatsSpring implements BeforeEachCallback, AfterEachCallback {

    private final Extension_Mats _extensionMats;
    private final List<MatsEndpoint<?, ?>> _endpoints = new ArrayList<>();
    private final List<Class<?>> _annotatedMatsClasses = new ArrayList<>();

    private Extension_MatsSpring(Extension_Mats extensionMats) {
        _extensionMats = extensionMats;
    }

    /**
     * Create a new Extension_MatsSpring instance, register to Junit using
     * {@link org.junit.jupiter.api.extension.RegisterExtension}.
     * @param extensionMats {@link Extension_Mats} to read the {@link MatsFactory} from.
     * @return a new {@link Extension_MatsSpring}
     */
    public static Extension_MatsSpring create(Extension_Mats extensionMats) {
        return new Extension_MatsSpring(extensionMats);
    }


    /**
     * Add classes to act as a source for annotations to register Mats endpoints for each test.
     */
    public Extension_MatsSpring withClasses(Class<?>... annotatedMatsClasses) {
        Collections.addAll(_annotatedMatsClasses, annotatedMatsClasses);
        return this;
    }

    @Override
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    // AvoidAccessibilityAlteration - need to reflect into test class to get fields.
    public void beforeEach(ExtensionContext extensionContext) {
        // If we have no classes, then early exit.
        if (_annotatedMatsClasses.isEmpty()) {
            return;
        }

        MatsFactory matsFactory = _extensionMats.getMatsFactory();
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
                    throw new AssertionFailedError("Failed to create bean of type [" + matsBeanName + "]"
                                                   + " with dependencies from test class fields."
                                                   + " Ensure that all dependencies are present as fields. Use @Mock with Mockito to"
                                                   + " initialize fields.", e);
                }
            }

            // Register each dependency as a singleton, so that we can properly construct the matsAnnotatedClass
            // from the Spring context.
            extensionContext.getTestInstance().ifPresent(testInstance -> {
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
            });

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
        MatsFactory matsFactory = _extensionMats.getMatsFactory();

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

    @Override
    public void afterEach(ExtensionContext context) {
        for (MatsEndpoint<?, ?> endpoint : _endpoints) {
            endpoint.remove(30_000);
        }
    }

}
