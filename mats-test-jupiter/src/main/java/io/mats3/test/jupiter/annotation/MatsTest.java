package io.mats3.test.jupiter.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.Extension_MatsAnnotatedClass;
import io.mats3.test.jupiter.Extension_MatsEndpoint;
import io.mats3.util.MatsFuturizer;

/**
 * Annotation to provide {@link io.mats3.MatsFactory} and {@link io.mats3.util.MatsFuturizer} instances for testing.
 * <p>
 * This annotation will allow injection of {@link io.mats3.MatsFactory} and {@link io.mats3.util.MatsFuturizer}
 * instances into test constructors and test methods. This omits the need to register the
 * {@link io.mats3.test.jupiter.Extension_Mats} extension. Optionally, the annotation can be used to create
 * {@link io.mats3.test.jupiter.Extension_Mats} with a {@link io.mats3.serial.MatsSerializer} other than the default,
 * and to also set if MATS should be set up with a database or not.
 * <p>
 * The annotation {@link MatsTest.Endpoint} can be applied to a field of type
 * {@link io.mats3.test.jupiter.Extension_MatsEndpoint} to inject a test endpoint into the test class. Thus, there
 * is no need to initialize the field, as this extension will take care of resolving relevant types, and setting
 * the field before the test executes. This happens after the constructor, but before any test
 * and {@link org.junit.jupiter.api.BeforeEach} methods.
 * <p>
 * The annotation {@link MatsTest.AnnotatedClass} can be applied to a field that has Mats annotations, like
 * MatsMapping or MatsClassMapping. Similar to {@link io.mats3.test.jupiter.Extension_MatsAnnotatedClass}, these
 * endpoints will be registered before the test executes. If the field is null, the class will be registered, and
 * instantiated by the extension, as if you called
 * {@link io.mats3.test.jupiter.Extension_MatsAnnotatedClass#withAnnotatedMatsClasses(Class[])}. However, if the field
 * is already instantiated, the instance will be registered, as if you called {@link
 * io.mats3.test.jupiter.Extension_MatsAnnotatedClass#withAnnotatedMatsInstances(Object[])}. This happens after the
 * constructor and field initialization, but before any test and {@link org.junit.jupiter.api.BeforeEach} methods.
 * <p>
 * If the test class uses mockito, and @InjectMocks is used, then this becomes sensitive to the order of the
 * annotations. The MockitoExtension should be placed before the MatsTest annotation, so that it can create
 * instances of the annotated classes before MatsTest inspects them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith({
        MatsTest.Extension_MatsRegistration.class,
        MatsTest.ParameterResolver_MatsEntities.class,
        MatsTest.FieldProcess_MatsAnnotatedFields.class
})
public @interface MatsTest {

    /**
     * Should we create the {@link io.mats3.test.jupiter.Extension_Mats} with a database or not.
     * Default is no database.
     * @return if the {@link io.mats3.test.jupiter.Extension_Mats} should be created with a database.
     */
    boolean includeDatabase() default false;

    /**
     * The serializer factory to use for the {@link io.mats3.MatsFactory} created by the extension.
     *
     * By default, the {@link MatsSerializerJson} is used.
     *
     * @return the serializer factory to use for the {@link io.mats3.MatsFactory} created by the extension.
     */
    Class<? extends SerializerFactory> serializerFactory() default SerializerFactoryJson.class;

    /**
     * Factory interface for creating a {@link MatsSerializer} instance.
     * <p>
     * Note: This must have a no-args constructor.
     */
    interface SerializerFactory {

        MatsSerializer<?> createSerializer();
    }

    /**
     * Default serializer factory for creating a {@link MatsSerializerJson} instance.
     */
    class SerializerFactoryJson implements SerializerFactory {
        @Override
        public MatsSerializer<?> createSerializer() {
            return MatsSerializerJson.create();
        }
    }

    /**
     * Field annotation on fields of type {@link io.mats3.test.jupiter.Extension_MatsEndpoint}.
     * <p>
     * Use this annotation to declare that a field should be injected with a test endpoint. The name of the
     * endpoint should be provided as the value of the annotation. This must be an instance of
     * {@link io.mats3.test.jupiter.Extension_MatsEndpoint}. If the field is already set, then the extension will
     * not change the value.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Endpoint {

        String endpointId();
    }

    /**
     * Marks a field that has Mats annotations, like MatsMapping or MatsClassMapping.
     * <p>
     * Use this annotation to declare that a field should be registered as a Mats class. If the field is null, the
     * class will be registered, and instantiated by the extension. If the field is already instantiated, the instance
     * will be registered. This must be a class that has Mats annotations.
     * <p>
     * For further documentation, see {@link io.mats3.test.jupiter.Extension_MatsAnnotatedClass}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface AnnotatedClass { }

    /**
     * Extension to register the {@link io.mats3.test.jupiter.Extension_MatsEndpoint} via annotations.
     * <p>
     * Since the normal {@link Extension_Mats} does not have a no-args constructor, this extension is instead
     * used to register the {@link Extension_Mats} extension into a test context.
     */
    class Extension_MatsRegistration implements Extension, BeforeAllCallback, AfterAllCallback {

        @Override
        public void beforeAll(ExtensionContext context) throws ReflectiveOperationException {
            // If the Extension_Mats is already registered, we do not need to do anything.
            if (Extension_Mats.findFromContext(context).isPresent()) {
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
            Extension_Mats.findFromContext(context)
                    .ifPresent(extensionMats -> extensionMats.afterAll(context));
        }

    }

    /**
     * Extension to provide properties from the {@link Extension_Mats} as parameters to test instances.
     * <p>
     * Note, this is a part of {@link MatsTest}, and should not be used directly. It requires the {@link Extension_Mats}
     * to be run first.
     *
     */
    class ParameterResolver_MatsEntities implements ParameterResolver {

        // This corresponds to the getters on Extension_Mats
        private static final Set<Class<?>> SUPPORTED_TYPES = Set.of(
                MatsInitiator.class, MatsTestLatch.class, MatsFuturizer.class, MatsTestBrokerInterface.class,
                MatsFactory.class
        );


        @Override
        public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            if (Extension_Mats.findFromContext(extensionContext).isEmpty()) {
                return false;
            }
            return SUPPORTED_TYPES
                    .contains(parameterContext.getParameter().getType());
        }

        @Override
        public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            Extension_Mats extensionMats = Extension_Mats.findFromContext(extensionContext)
                    .orElseThrow(() -> new IllegalStateException("Could not find Extension_Mats in the test context."
                                                                 + " This should not happen, as we already verified that it"
                                                                 + " was present in #supportsParameter."));
            if (parameterContext.getParameter().getType().equals(MatsInitiator.class)) {
                return extensionMats.getMatsInitiator();
            }
            if (parameterContext.getParameter().getType().equals(MatsTestLatch.class)) {
                return extensionMats.getMatsTestLatch();
            }
            if (parameterContext.getParameter().getType().equals(MatsFuturizer.class)) {
                return extensionMats.getMatsFuturizer();
            }
            if (parameterContext.getParameter().getType().equals(MatsTestBrokerInterface.class)) {
                return extensionMats.getMatsTestBrokerInterface();
            }
            if (parameterContext.getParameter().getType().equals(MatsFactory.class)) {
                return extensionMats.getMatsFactory();
            }
            throw new IllegalStateException("Could not resolve parameter [" + parameterContext.getParameter() + "].");
        }
    }

    /**
     * Extension to support the {@link Endpoint} and {@link AnnotatedClass} annotations on fields in
     * test classes.
     * <p>
     * Note, this is a part of {@link MatsTest}, and should not be used directly. It requires the {@link Extension_Mats} to
     * be run first.
     *
     */
    class FieldProcess_MatsAnnotatedFields implements Extension,
            BeforeEachCallback, AfterEachCallback {

        static final String LOG_PREFIX = "#MATSTEST:MTA# ";

        private static final Logger log = LoggerFactory.getLogger(FieldProcess_MatsAnnotatedFields.class);

        private final List<Extension_MatsEndpoint<?, ?>> _testEndpoints = new ArrayList<>();
        private Extension_MatsAnnotatedClass _matsAnnotatedClass;

        @Override
        public void beforeEach(ExtensionContext context) throws IllegalAccessException {
            // Trigger the before each on the MatsAnnotatedClass, so that we register all endpoints found from annotated
            // fields.
            _matsAnnotatedClass = Extension_MatsAnnotatedClass.create();
            for (Object testInstance : context.getRequiredTestInstances().getAllInstances()) {
                Class<?> testClass = testInstance.getClass();
                Field[] declaredFields = testClass.getDeclaredFields();

                for (Field declaredField : declaredFields) {
                    if (declaredField.isAnnotationPresent(Endpoint.class)) {
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

            Endpoint endpoint = declaredField.getAnnotation(Endpoint.class);
            Extension_MatsEndpoint<?, ?> extensionMatsEndpoint = Extension_MatsEndpoint.create(
                    endpoint.endpointId(),
                    replyMsgClass,
                    incomingMsgClass
            );

            extensionMatsEndpoint.beforeEach(context);
            _testEndpoints.add(extensionMatsEndpoint);

            log.info(LOG_PREFIX + "Injecting MatsEndpoint [" + endpoint.endpointId() + "]"
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
}
