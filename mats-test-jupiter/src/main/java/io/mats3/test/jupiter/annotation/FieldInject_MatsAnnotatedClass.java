package io.mats3.test.jupiter.annotation;

import static io.mats3.test.jupiter.annotation.Extension_MatsRegistration.LOG_PREFIX;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsAnnotatedClass;
import io.mats3.test.jupiter.annotation.MatsTest.AnnotatedClass;

/**
 * Extension to support the {@link MatsTest.AnnotatedClass} annotation on fields in a test class.
 * <p>
 * Note, this is a part of {@link MatsTest}, and should not be used directly. It requires the {@link Extension_Mats}
 * to be run first.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
class FieldInject_MatsAnnotatedClass implements Extension, BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(FieldInject_MatsAnnotatedClass.class);

    private Extension_MatsAnnotatedClass _matsAnnotatedClass;

    @Override
    public void beforeEach(ExtensionContext context) throws IllegalAccessException {
        _matsAnnotatedClass = Extension_MatsAnnotatedClass.create(Extension_Mats.getExtension(context));

        // Get all instances under test, and inject fields, or register their instance with the
        // MatsAnnotatedClass.
        List<Object> testInstances = context.getRequiredTestInstances().getAllInstances();

        for (Object instance : testInstances) {
            Class<?> testClass = instance.getClass();
            Field[] declaredFields = testClass.getDeclaredFields();
            for (Field declaredField : declaredFields) {
                if (declaredField.isAnnotationPresent(AnnotatedClass.class)) {
                    if (!declaredField.trySetAccessible()) {
                        throw new IllegalStateException("Could not set accessible on field [" + declaredField + "]"
                                                        + " in test class [" + testClass + "]."
                                                        + " We are not able to register the annotated class"
                                                        + " [" + declaredField.getType() + "].");
                    }
                    Object fieldValue = declaredField.get(instance);
                    if (fieldValue == null) {
                        log.info(LOG_PREFIX + "Registering annotated field [" + declaredField.getName() + "]"
                                 + " in test class [" + testClass + "]"
                                 + " without an instance as an Annotated Class.");
                        _matsAnnotatedClass.withAnnotatedMatsClasses(declaredField.getType());
                    }
                    else {
                        log.info(LOG_PREFIX + "Registering annotated field [" + declaredField.getName() + "]"
                                 + " in test class [" + testClass + "]"
                                 + " with an instance [" + fieldValue + "] as an Annotated Instance.");
                        _matsAnnotatedClass.withAnnotatedMatsInstances(fieldValue);
                    }
                }
            }
        }
        _matsAnnotatedClass.beforeEach(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        _matsAnnotatedClass.afterEach(context);
    }

}
