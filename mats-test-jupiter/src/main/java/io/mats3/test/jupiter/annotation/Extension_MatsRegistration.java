package io.mats3.test.jupiter.annotation;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.mats3.serial.MatsSerializer;
import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.annotation.MatsTest.SerializerFactory;

/**
 * Extension to register the {@link io.mats3.test.jupiter.Extension_MatsEndpoint} via annotations.
 * <p>
 * Since the normal {@link Extension_Mats} does not have a no-args constructor, this extension is instead
 * used to register the {@link Extension_Mats} extension into a test context.
 */
class Extension_MatsRegistration implements Extension, BeforeAllCallback, AfterAllCallback {

    static final String LOG_PREFIX = "#MATSTEST:MTA# ";

    @Override
    public void beforeAll(ExtensionContext context) throws ReflectiveOperationException {
        // If the Extension_Mats is already registered, we do not need to do anything.
        if (Extension_Mats.isExtensionRegistered(context)) {
            return;
        }
        MatsTest matsTest = context.getRequiredTestClass().getAnnotation(MatsTest.class);
        // If the test class is not annotated with MatsTest, then we should not register the Extension_Mats in
        // this context.
        if (matsTest == null) {
            return;
        }

        SerializerFactory serializerFactory = matsTest.serializerFactory().getDeclaredConstructor().newInstance();
        MatsSerializer<?> matsSerializer = serializerFactory.createSerializer();

        Extension_Mats extensionMats = matsTest.includeDatabase()
            ? Extension_Mats.createWithDb(matsSerializer)
            : Extension_Mats.create(matsSerializer);
        extensionMats.beforeAll(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Extension_Mats extensionMats = Extension_Mats.getExtension(context);
        extensionMats.afterAll(context);
    }

}
