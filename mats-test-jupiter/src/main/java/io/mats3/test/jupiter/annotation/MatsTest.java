package io.mats3.test.jupiter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;

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
        Extension_MatsRegistration.class,
        ParameterResolver_MatsFactory.class,
        ParameterResolver_MatsFuturizer.class,
        PostProcessor_MatsEndpoint.class,
        PostProcessor_MatsAnnotatedClass.class
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

        String name();
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
}
