package io.mats3.test.jupiter.annotation;

import static io.mats3.test.jupiter.annotation.Extension_MatsRegistration.LOG_PREFIX;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsAnnotatedClass;
import io.mats3.test.jupiter.Extension_MatsEndpoint;
import io.mats3.test.jupiter.annotation.MatsTest.AnnotatedClass;

/**
 * Extension to support the {@link MatsTest.Endpoint} and {@link MatsTest.AnnotatedClass} annotations on fields in
 * test classes.
 * <p>
 * Note, this is a part of {@link MatsTest}, and should not be used directly. It requires the {@link Extension_Mats} to
 * be run first.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
class Extension_ProcessMatsAnnotatedFields implements Extension, BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(Extension_ProcessMatsAnnotatedFields.class);

    private final List<Extension_MatsEndpoint<?, ?>> _testEndpoints = new ArrayList<>();
    private Extension_MatsAnnotatedClass _matsAnnotatedClass;

    @Override
    public void beforeEach(ExtensionContext context) throws IllegalAccessException {
        _matsAnnotatedClass = Extension_MatsAnnotatedClass.create(Extension_Mats.getExtension(context));

        List<Object> testInstances = context.getRequiredTestInstances().getAllInstances();

        for (Object testInstance : testInstances) {
            Class<?> testClass = testInstance.getClass();
            Field[] declaredFields = testClass.getDeclaredFields();

            for (Field declaredField : declaredFields) {
                if (declaredField.isAnnotationPresent(MatsTest.Endpoint.class)) {
                    injectTestEndpoint(context, testInstance, declaredField);
                }
                else if (declaredField.isAnnotationPresent(AnnotatedClass.class)) {
                    injectMatsAnnotatedClass(testInstance, declaredField);
                }
                else {
                    log.debug(LOG_PREFIX + "Field [" + declaredField + "] in test class [" + testClass + "]"
                              + " is not annotated with @MatsTest annotations, so it will not be injected.");
                }
            }
        }

        // Trigger the before each on the MatsAnnotatedClass, so that we register all endpoints found from annotated
        // fields.
        _matsAnnotatedClass.beforeEach(context);
    }


    @Override
    public void afterEach(ExtensionContext context) {
        _testEndpoints.forEach(extensionMatsEndpoint -> extensionMatsEndpoint.afterEach(context));
        _matsAnnotatedClass.afterEach(context);
    }

    private void injectTestEndpoint(ExtensionContext context, Object testInstance, Field declaredField)
            throws IllegalAccessException {
        if (!declaredField.trySetAccessible()) {
            throw new IllegalStateException("Could not set accessible on field [" + declaredField + "],"
                                            + " in test class [" + declaredField.getDeclaringClass() + "]."
                                            + " We are not able to inject the MatsEndpoint into this class.");
        }
        if (!Extension_MatsEndpoint.class.equals(declaredField.getType())) {
            throw new IllegalStateException(
                    "Field [" + declaredField + "] in test class [" + declaredField.getDeclaringClass() + "]"
                    + " is not of type Extension_MatsEndpoint.");
        }
        if (declaredField.get(testInstance) != null) {
            log.debug(LOG_PREFIX + "Field [" + declaredField + "]"
                      + " in test class [" + declaredField.getDeclaringClass() + "] is already initialized, skipping.");
            return;
        }

        // Resolve the type parameters from the generic type signature of the field. These should always resolve to
        // some form of concrete class.
        Type[] endpointTypeArguments = ((ParameterizedType) declaredField.getGenericType()).getActualTypeArguments();
        Class<?> replyMsgClass = (Class<?>) endpointTypeArguments[0];
        Class<?> incomingMsgClass = (Class<?>) endpointTypeArguments[1];

        MatsTest.Endpoint endpoint = declaredField.getAnnotation(MatsTest.Endpoint.class);
        Extension_MatsEndpoint<?, ?> extensionMatsEndpoint = Extension_MatsEndpoint.create(
                Extension_Mats.getExtension(context),
                endpoint.name(),
                replyMsgClass,
                incomingMsgClass
        );

        extensionMatsEndpoint.beforeEach(context);
        _testEndpoints.add(extensionMatsEndpoint);

        log.info(LOG_PREFIX + "Injecting MatsEndpoint [" + endpoint.name() + "]"
                 + " into field [" + declaredField + "]"
                 + " in test class [" + declaredField.getDeclaringClass() + "].");
        declaredField.set(testInstance, extensionMatsEndpoint);
    }


    private void injectMatsAnnotatedClass(Object testInstance, Field declaredField) throws IllegalAccessException {
        if (!declaredField.trySetAccessible()) {
            throw new IllegalStateException("Could not set accessible on field [" + declaredField + "]"
                                            + " in test class [" + declaredField.getDeclaringClass() + "]."
                                            + " We are not able to register the annotated class"
                                            + " [" + declaredField.getType() + "].");
        }

        Object fieldValue = declaredField.get(testInstance);
        if (fieldValue == null) {
            log.info(LOG_PREFIX + "Registering annotated field [" + declaredField.getName() + "]"
                     + " in test class [" + declaredField.getDeclaringClass() + "]"
                     + " without an instance as an Annotated Class.");
            _matsAnnotatedClass.withAnnotatedMatsClasses(declaredField.getType());
        }
        else {
            log.info(LOG_PREFIX + "Registering annotated field [" + declaredField.getName() + "]"
                     + " in test class [" + declaredField.getDeclaringClass() + "]"
                     + " with an instance [" + fieldValue + "] as an Annotated Instance.");
            _matsAnnotatedClass.withAnnotatedMatsInstances(fieldValue);
        }
    }

}
