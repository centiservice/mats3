package io.mats3.test.junit.matsannotatedclass;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Qualifier;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.MatsMapping;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsAnnotatedClass;
import io.mats3.test.junit.matsannotatedclass.U_RuleMatsAnnotatedClassBasicsTest.ServiceDependency;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test of {@link Rule_MatsAnnotatedClass}, when the annotated endpoint uses MatsFactory qualifiers, and also uses
 * duplicated endpoints (i.e. one on each of two MatsFactories). This cannot be emulated with the MatsAnnotatedClass
 * thingy - it only have one MatsFactory, but it should be able to set up a single instance on the one MatsFactory, i.e.
 * ignoring the qualifier.
 */
public class U_RuleMatsAnnotatedClass_MatsFactoryQualifiers_Test {

    public static final String ENDPOINT_ID_METHOD = "AnnotatedEndpoint.MatsMapping";
    public static final String ENDPOINT_ID_CLASS = "AnnotatedEndpoint.MatsClassMapping";

    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    public @interface TestCustomQualifier {
        String region() default "";
    }

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Rule
    public final Rule_MatsAnnotatedClass _matsAnnotationRule = Rule_MatsAnnotatedClass.create(MATS);

    private final ServiceDependency _serviceDependency = new ServiceDependency();

    /**
     * Example of a class with method annotated with two MatsMappings, using MatsFactory qualifiers - that should be
     * ignored.
     */
    public static class MatsMappingAnnotatedMats3Endpoint {
        private ServiceDependency _serviceDependency;

        public MatsMappingAnnotatedMats3Endpoint() {
            /* No-args constructor for Jackson deserialization. */
        }

        @Inject
        public MatsMappingAnnotatedMats3Endpoint(ServiceDependency serviceDependency) {
            _serviceDependency = serviceDependency;
        }

        /**
         * Simple endpoint that is defined twice, using different MatsFactory qualifiers.
         */
        @MatsMapping(endpointId = ENDPOINT_ID_METHOD, matsFactoryCustomQualifierType = TestCustomQualifier.class)
        @MatsMapping(endpointId = ENDPOINT_ID_METHOD, matsFactoryQualifierValue = "matsFactoryABC")
        public String springMatsTerminatorEndpoint(@Dto String msg) {
            return _serviceDependency.formatMessage(msg);
        }
    }

    /**
     * Example of a class with method annotated with two MatsClassMappings, using MatsFactory qualifiers - which should
     * be ignored.
     */
    @MatsClassMapping(endpointId = ENDPOINT_ID_CLASS, matsFactoryCustomQualifierType = TestCustomQualifier.class)
    @MatsClassMapping(endpointId = ENDPOINT_ID_CLASS, matsFactoryQualifierValue = "matsFactoryABC")
    public static class MatsClassMappingAnnotatedMats3Endpoint {
        private transient ServiceDependency _serviceDependency;

        public MatsClassMappingAnnotatedMats3Endpoint() {
            /* No-args constructor for Jackson deserialization. */
        }

        @Inject
        public MatsClassMappingAnnotatedMats3Endpoint(ServiceDependency serviceDependency) {
            _serviceDependency = serviceDependency;
        }

        @Stage(0)
        public String stage0(@Dto String msg) {
            return _serviceDependency.formatMessage(msg);
        }
    }

    @Test
    public void testAnnotatedMatsWithQualifiers_Class_MatsMapping() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Arrange
        _matsAnnotationRule.withAnnotatedMatsClasses(MatsMappingAnnotatedMats3Endpoint.class);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(ENDPOINT_ID_METHOD, "World 1!");

        // :: Assert
        Assert.assertEquals("Hello World 1!", reply);
    }

    @Test
    public void testAnnotatedMatsWithQualifiers_Instance_MatsMapping() throws ExecutionException, InterruptedException,
            TimeoutException {
        // :: Arrange
        // We now create the instance ourselves, including dependency injection, and then register it.
        MatsMappingAnnotatedMats3Endpoint annotatedMatsInstance = new MatsMappingAnnotatedMats3Endpoint(
                _serviceDependency);
        _matsAnnotationRule.withAnnotatedMatsInstances(annotatedMatsInstance);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(ENDPOINT_ID_METHOD, "World 2!");

        // :: Assert
        Assert.assertEquals("Hello World 2!", reply);
    }

    @Test
    public void testAnnotatedMatsWithQualifiers_Class_MatsClassMapping() throws ExecutionException, InterruptedException,
            TimeoutException {
        // :: Arrange
        _matsAnnotationRule.withAnnotatedMatsClasses(MatsClassMappingAnnotatedMats3Endpoint.class);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(ENDPOINT_ID_CLASS, "Earth 1!");

        // :: Assert
        Assert.assertEquals("Hello Earth 1!", reply);
    }

    @Test
    public void testAnnotatedMatsWithQualifiers_Instance_MatsClassMapping() throws ExecutionException, InterruptedException,
            TimeoutException {
        // :: Arrange
        // We now create the instance ourselves, including dependency injection, and then register it.
        MatsClassMappingAnnotatedMats3Endpoint annotatedMatsInstance = new MatsClassMappingAnnotatedMats3Endpoint(
                _serviceDependency);
        _matsAnnotationRule.withAnnotatedMatsInstances(annotatedMatsInstance);

        // :: Act
        String reply = callMatsAnnotatedEndpoint(ENDPOINT_ID_CLASS, "Earth 2!");

        // :: Assert
        Assert.assertEquals("Hello Earth 2!", reply);
    }

    @Test
    public void testAnnotatedMatsWithQualifiers_Class_Both() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Arrange
        _matsAnnotationRule.withAnnotatedMatsClasses(MatsMappingAnnotatedMats3Endpoint.class)
                .withAnnotatedMatsClasses(MatsClassMappingAnnotatedMats3Endpoint.class);

        // :: Act
        String reply1 = callMatsAnnotatedEndpoint(ENDPOINT_ID_METHOD, "World!");
        String reply2 = callMatsAnnotatedEndpoint(ENDPOINT_ID_METHOD, "Earth!");

        // :: Assert
        Assert.assertEquals("Hello World!", reply1);
        Assert.assertEquals("Hello Earth!", reply2);
    }

    static String callMatsAnnotatedEndpoint(String endpointId, String message)
            throws InterruptedException, ExecutionException, TimeoutException {
        return MATS.getMatsFuturizer().futurizeNonessential(
                        "invokeAnnotatedEndpoint",
                        "UnitTest",
                        endpointId,
                        String.class,
                        message)
                .thenApply(Reply::get)
                .get(10, TimeUnit.SECONDS);
    }
}
