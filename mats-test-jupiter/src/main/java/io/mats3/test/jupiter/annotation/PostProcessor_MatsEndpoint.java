package io.mats3.test.jupiter.annotation;

import static io.mats3.test.jupiter.annotation.Extension_MatsRegistration.LOG_PREFIX;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsEndpoint;

/**
 * Extension to support the {@link MatsTest.Endpoint} annotation on fields in a test class.
 * <p>
 * Note, this is a part of {@link MatsTest}, and should not be used directly. It requires the {@link Extension_Mats}
 * to be run first.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
class PostProcessor_MatsEndpoint implements
        Extension, TestInstancePostProcessor,
        BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(PostProcessor_MatsEndpoint.class);

    private final List<Extension_MatsEndpoint<?, ?>> _testEndpoints = new ArrayList<>();

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        Field[] declaredFields = context.getRequiredTestClass().getDeclaredFields();
        Extension_Mats extensionMats = Extension_Mats.getExtension(context);

        for (Field declaredField : declaredFields) {
            if (declaredField.isAnnotationPresent(MatsTest.Endpoint.class)) {
                if (!declaredField.trySetAccessible()) {
                    throw new IllegalStateException("Could not set accessible on field [" + declaredField + "],"
                                                    + " in test class [" + context.getRequiredTestClass() + "]."
                                                    + " We are not able to inject the MatsEndpoint into this class.");
                }
                if (!Extension_MatsEndpoint.class.equals(declaredField.getType())) {
                    throw new IllegalStateException(
                            "Field [" + declaredField + "] in test class [" + context.getRequiredTestClass() + "]"
                            + " is not of type Extension_MatsEndpoint.");
                }
                if (declaredField.get(testInstance) != null) {
                    log.debug(LOG_PREFIX + "Field [" + declaredField + "]"
                             + " in test class [" + context.getRequiredTestClass() + "] is already initialized");
                    continue;
                }

                MatsTest.Endpoint endpoint = declaredField.getAnnotation(MatsTest.Endpoint.class);
                Extension_MatsEndpoint<?, ?> extensionMatsEndpoint = Extension_MatsEndpoint.create(
                        extensionMats,
                        endpoint.name(),
                        (Class<?>) ((ParameterizedType) declaredField.getGenericType()).getActualTypeArguments()[0],
                        (Class<?>) ((ParameterizedType) declaredField.getGenericType()).getActualTypeArguments()[1]
                );
                log.info(LOG_PREFIX + "Injecting MatsEndpoint [" + endpoint.name() + "]"
                         + " into field [" + declaredField + "]"
                         + " in test class [" + context.getRequiredTestClass() + "].");
                _testEndpoints.add(extensionMatsEndpoint);
                declaredField.set(testInstance, extensionMatsEndpoint);
            }
            else {
                log.debug(LOG_PREFIX + "Field [" + declaredField + "] in test class [" + context.getRequiredTestClass()
                        + "] is not annotated with @MatsTestEndpoint, so it will not be injected.");
            }
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        _testEndpoints.forEach(extensionMatsEndpoint -> extensionMatsEndpoint.beforeEach(context));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        _testEndpoints.forEach(extensionMatsEndpoint -> extensionMatsEndpoint.afterEach(context));
    }
}
