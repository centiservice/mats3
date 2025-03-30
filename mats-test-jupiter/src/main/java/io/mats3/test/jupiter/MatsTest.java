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

package io.mats3.test.jupiter;

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

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
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
import io.mats3.test.TestH2DataSource;
import io.mats3.test.jupiter.MatsTest.FieldProcess_MatsTestAnnotations;
import io.mats3.util.MatsFuturizer;

/**
 * Convenience composite annotation for providing test infrastructure for Mats3 testing - notably what you get from the
 * Jupiter extension {@link Extension_Mats} - as well as support for instantiating and registering endpoints as provided
 * by the extensions {@link Extension_MatsEndpoint} and {@link Extension_MatsAnnotatedClass} by use of the two
 * sub-annotations {@link MatsTestEndpoint MatsTestEndpoint} and {@link MatsTestAnnotatedClass MatsTestAnnotatedClass},
 * and with the MatsTest annotation parameter {@link #matsAnnotatedClasses()}.
 * <p>
 * <b><i>Note that this tool is not a requirement for writing tests for Mats3! This is a pure convenience that can
 * remove a minor bit of boilerplate, and can make the tests a few lines smaller, and maybe - for developers already
 * proficient with Mats3 - a bit quicker to read by hiding away some minor elements of infrastructure. You should
 * probably NOT start your Mats3 testing journey with this tool - rather understand how Mats3 works, and how the testing
 * tools works, before shaving off these few infrastructural code bytes.</i></b>
 * <p>
 * By annotating a test class with this composite annotation, the tool instantiates and registers the
 * {@link Extension_Mats} extension, and then provides <i>parameter resolving</i> for test methods (and @Nested-tests
 * construction injection), for the following parameter types:
 * <ul>
 * <li>{@link Extension_Mats} - The extension itself.</li>
 * <li>{@link MatsFactory} - as gotten by {@link Extension_Mats#getMatsFactory()}.</li>
 * <li>{@link MatsFuturizer} - as gotten by {@link Extension_Mats#getMatsFuturizer()}.</li>
 * <li>{@link MatsInitiator} - as gotten by {@link Extension_Mats#getMatsInitiator()}.</li>
 * <li>{@link MatsTestLatch} - as gotten by {@link Extension_Mats#getMatsTestLatch()}.</li>
 * <li>{@link MatsTestBrokerInterface} - as gotten by {@link Extension_Mats#getMatsTestBrokerInterface()}.</li>
 * <li>{@link DataSource} - as gotten by {@link Extension_Mats#getDataSource()}, which is an H2 DataSource created by
 * invoking {@link TestH2DataSource#createStandard() TestH2DataSource.createStandard()}. <i>This is only available if
 * the {@link Extension_Mats} was created with a database ({@link MatsTest#db} = <code>true</code>).</i></li>
 * </ul>
 * <p>
 * The MatsTest annotation has parameters directing {@link Extension_Mats} to use a {@link MatsSerializer} other than
 * the default, and to decide if it should be set up with an H2 database or not (as when using the
 * {@link Extension_Mats#createWithDb()}).
 * <p>
 * <h3>{@link Extension_MatsEndpoint} support</h3>
 *
 * The annotation {@link MatsTestEndpoint MatsTestEndpoint} can be applied to a field of type
 * {@link Extension_MatsEndpoint} to inject a test endpoint into the test class. Thus, there is no need to initialize
 * the field, as this extension will take care of resolving relevant types, and constructing the instance, and setting
 * the field before the test executes. This happens after the constructor, but before any test and {@link BeforeEach}
 * methods.
 * <p>
 * <h3>{@link Extension_MatsAnnotatedClass} support</h3>
 *
 * Support in two ways:
 * <ol>
 * <li><b>{@link MatsTestAnnotatedClass MatsTestAnnotatedClass} annotation on test classes' fields:</b> The annotation
 * {@link MatsTestAnnotatedClass MatsTestAnnotatedClass} can be applied to fields in the test class whose type refers to
 * a Mats3 SpringConfig annotations defined endpoint (classes annotated with <code>MatsMapping</code> and
 * <code>MatsClassMapping</code>). Internally it uses the {@link Extension_MatsAnnotatedClass} feature. If the field is
 * null, the class will be instantiated and registered by the extension, as if you called
 * {@link Extension_MatsAnnotatedClass#withAnnotatedMatsClasses(Class[])}. However, if the field is already
 * instantiated, the instance will be registered, as if you called
 * {@link Extension_MatsAnnotatedClass#withAnnotatedMatsInstances(Object[])}. This happens after the constructor and
 * field initialization, but before any test and {@link BeforeEach} methods.</li>
 *
 * <li><b>MatsTest's {@link MatsTest#matsAnnotatedClasses} parameter:</b> You may list classes that are annotated with
 * Mats3 SpringConfig annotations, and these will be instantiated and registered before the test executes.</li>
 * </ol>
 * <p>
 * <h3>Mockito interaction</h3>
 *
 * If the test class uses Mockito, and @InjectMocks is used, then this becomes sensitive to the order of the
 * annotations: The {@code @ExtendWith(MockitoExtension.class)} annotation should be placed above the {@code MatsTest}
 * annotation, so that it can create instances of the annotated classes before MatsTest inspects them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith({
        MatsTest.Extension_MatsRegistration.class,
        MatsTest.ParameterResolver_MatsEntities.class,
        FieldProcess_MatsTestAnnotations.class
})
public @interface MatsTest {
    /**
     * Should we create the {@link Extension_Mats} by invoking the {@link Extension_Mats#createWithDb()} method, which
     * again creates a H2 DataSource by invoking {@link TestH2DataSource#createStandard()}. Default is
     * <code>false</code>, no database.
     *
     * @return if the {@link Extension_Mats} should be created with a database.
     */
    boolean db() default false;

    /**
     * The serializer factory to use for the {@link MatsFactory} created by the extension.
     *
     * By default, the {@link MatsSerializerJson} is used.
     *
     * @return the serializer factory to use for the {@link MatsFactory} created by the extension.
     */
    Class<? extends SerializerFactory> serializerFactory() default SerializerFactoryJson.class;

    /**
     * Add classes here that you wish to be registered as Mats annotated classes.
     */
    Class<?>[] matsAnnotatedClasses() default {};

    /**
     * Factory interface for creating a {@link MatsSerializer} instance.
     * <p>
     * Note: This must have a no-args constructor.
     */
    interface SerializerFactory {
        MatsSerializer createSerializer();
    }

    /**
     * Default serializer factory for creating a {@link MatsSerializerJson} instance.
     */
    class SerializerFactoryJson implements SerializerFactory {
        @Override
        public MatsSerializer createSerializer() {
            return MatsSerializerJson.create();
        }
    }

    /**
     * Field annotation on fields of type {@link Extension_MatsEndpoint}.
     * <p>
     * Use this annotation to declare that a field should be injected with a test endpoint. The name of the endpoint
     * should be provided as the value of the annotation. This must be an instance of {@link Extension_MatsEndpoint}. If
     * the field is already set, then the extension will not change the value.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface MatsTestEndpoint {
        /**
         * @return the endpointId of the endpoint to create.
         */
        String endpointId();
    }

    /**
     * Marks a field that has Mats annotations, like MatsMapping or MatsClassMapping.
     * <p>
     * Use this annotation to declare that a field should be registered as a Mats class. If the field is null, the class
     * will be registered, and instantiated by the extension. If the field is already instantiated, the instance will be
     * registered. This must be a class that has Mats annotations.
     * <p>
     * For further documentation, see {@link Extension_MatsAnnotatedClass}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface MatsTestAnnotatedClass {}

    /**
     * Extension to create and register the {@link Extension_Mats} into the test context.
     * <p>
     * Since the normal {@link Extension_Mats} does not have a no-args constructor, this extension is instead used to
     * register the {@link Extension_Mats} extension into a test context.
     * <p>
     * <i>Note, this is a part of {@link MatsTest}, and should not be used directly.</i>
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
            MatsSerializer matsSerializer = serializerFactory.createSerializer();

            Extension_Mats extensionMats = matsTest.db()
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
     * ParameterResolved to provide properties from the {@link Extension_Mats} as parameters to test instances' test
     * methods, and constructor injection.
     * <p>
     * <i>Note, this is a part of {@link MatsTest}, and should not be used directly.</i>
     */
    class ParameterResolver_MatsEntities implements ParameterResolver {

        // This corresponds to the getters on Extension_Mats
        private static final Set<Class<?>> SUPPORTED_TYPES = Set.of(
                Extension_Mats.class, MatsFactory.class, MatsFuturizer.class, MatsInitiator.class,
                MatsTestLatch.class, MatsTestBrokerInterface.class, DataSource.class);

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
            if (parameterContext.getParameter().getType().equals(Extension_Mats.class)) {
                return extensionMats;
            }
            if (parameterContext.getParameter().getType().equals(MatsFactory.class)) {
                return extensionMats.getMatsFactory();
            }
            if (parameterContext.getParameter().getType().equals(MatsFuturizer.class)) {
                return extensionMats.getMatsFuturizer();
            }
            if (parameterContext.getParameter().getType().equals(MatsInitiator.class)) {
                return extensionMats.getMatsInitiator();
            }
            if (parameterContext.getParameter().getType().equals(MatsTestLatch.class)) {
                return extensionMats.getMatsTestLatch();
            }
            if (parameterContext.getParameter().getType().equals(MatsTestBrokerInterface.class)) {
                return extensionMats.getMatsTestBrokerInterface();
            }
            // This is only available if the Extension_Mats was created with a database, 'db=true'.
            if (parameterContext.getParameter().getType().equals(DataSource.class)) {
                return extensionMats.getDataSource();
            }
            // NOTE: We do NOT provide the JMS ConnectionFactory here, as that is not a part of the normal Mats
            // infrastructure, and we've not imported the JMS stuff into this module. If needed, request the
            // Extension_Mats instance, and get it from there.

            throw new IllegalStateException("Could not resolve parameter [" + parameterContext.getParameter() + "].");
        }
    }

    /**
     * Extension to support the {@link MatsTestEndpoint} and {@link MatsTestAnnotatedClass} annotations on fields in
     * test classes.
     * <p>
     * <i>Note, this is a part of {@link MatsTest}, and should not be used directly.</i>
     */
    class FieldProcess_MatsTestAnnotations implements Extension,
            BeforeEachCallback, AfterEachCallback {

        private static final String LOG_PREFIX = "#MATSTEST:MTA# "; // "Mats Test Annotations"

        private static final Namespace NAMESPACE = Namespace.create(Extension_Mats.class.getName());

        private static final Logger log = LoggerFactory.getLogger(FieldProcess_MatsTestAnnotations.class);

        private final List<Extension_MatsEndpoint<?, ?>> _createdTestEndpoints = new ArrayList<>();

        private static Extension_MatsAnnotatedClass lazyGet(ExtensionContext context) {
            return context.getStore(NAMESPACE).get(FieldProcess_MatsTestAnnotations.class.getName(),
                    Extension_MatsAnnotatedClass.class);
        }

        private static Extension_MatsAnnotatedClass lazyCreate(ExtensionContext context) {
            Extension_MatsAnnotatedClass lazy = lazyGet(context);
            if (lazy == null) {
                lazy = Extension_MatsAnnotatedClass.create();
                context.getStore(NAMESPACE).put(FieldProcess_MatsTestAnnotations.class.getName(), lazy);
            }
            return lazy;
        }

        @Override
        public void beforeEach(ExtensionContext context) throws IllegalAccessException {
            // Trigger the before each on the MatsAnnotatedClass, so that we register all endpoints found from annotated
            // fields.
            for (Object testInstance : context.getRequiredTestInstances().getAllInstances()) {
                Class<?> testClass = testInstance.getClass();
                Field[] declaredFields = testClass.getDeclaredFields();

                for (Field declaredField : declaredFields) {
                    if (declaredField.isAnnotationPresent(MatsTestEndpoint.class)) {
                        injectTestEndpoint(context, testInstance, declaredField);
                    }
                    else if (declaredField.isAnnotationPresent(MatsTestAnnotatedClass.class)) {
                        injectMatsAnnotatedClass(context, testInstance, declaredField);
                    }
                    else {
                        log.debug(LOG_PREFIX + "Field [" + declaredField + "] in test class [" + testClass + "]"
                                + " is not annotated with @MatsTest annotations, so it will not be injected.");
                    }
                }
            }
            MatsTest matsTest = context.getRequiredTestClass().getAnnotation(MatsTest.class);
            if (matsTest != null) {
                for (Class<?> matsAnnotatedClass : matsTest.matsAnnotatedClasses()) {
                    lazyCreate(context).withAnnotatedMatsClasses(matsAnnotatedClass);
                }
            }

            // ?: Did we end up creating the Extension_MatsAnnotatedClass?
            if (lazyGet(context) != null) {
                // -> Yes, we created it, so let's run beforeEach on it.
                lazyGet(context).beforeEach(context);
            }
        }

        @Override
        public void afterEach(ExtensionContext context) {
            _createdTestEndpoints.forEach(extensionMatsEndpoint -> extensionMatsEndpoint.afterEach(context));
            // ?: Have we created the Extension_MatsAnnotatedClass?
            if (lazyGet(context) != null) {
                // -> Yes, we created it, so let's run afterEach on it.
                lazyGet(context).afterEach(context);
            }
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
                        + " in test class [" + declaredField.getDeclaringClass()
                        + "] is already initialized, skipping.");
                return;
            }

            // Resolve the type parameters from the generic type signature of the field. These should always resolve to
            // some form of concrete class.
            Type[] endpointTypeArguments = ((ParameterizedType) declaredField.getGenericType())
                    .getActualTypeArguments();
            Class<?> replyMsgClass = (Class<?>) endpointTypeArguments[0];
            Class<?> incomingMsgClass = (Class<?>) endpointTypeArguments[1];

            MatsTestEndpoint matsTestEndpoint = declaredField.getAnnotation(MatsTestEndpoint.class);
            Extension_MatsEndpoint<?, ?> extensionMatsEndpoint = Extension_MatsEndpoint.create(
                    matsTestEndpoint.endpointId(),
                    replyMsgClass,
                    incomingMsgClass);

            extensionMatsEndpoint.beforeEach(context);
            _createdTestEndpoints.add(extensionMatsEndpoint);

            log.info(LOG_PREFIX + "Injecting MatsEndpoint [" + matsTestEndpoint.endpointId() + "]"
                    + " into field [" + declaredField + "]"
                    + " in test class [" + declaredField.getDeclaringClass() + "].");
            declaredField.set(testInstance, extensionMatsEndpoint);
        }

        private void injectMatsAnnotatedClass(ExtensionContext context, Object testInstance, Field declaredField)
                throws IllegalAccessException {
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
                lazyCreate(context).withAnnotatedMatsClasses(declaredField.getType());
            }
            else {
                log.info(LOG_PREFIX + "Registering annotated field [" + declaredField.getName() + "]"
                        + " in test class [" + declaredField.getDeclaringClass() + "]"
                        + " with an instance [" + fieldValue + "] as an Annotated Instance.");
                lazyCreate(context).withAnnotatedMatsInstances(fieldValue);
            }
        }
    }
}
