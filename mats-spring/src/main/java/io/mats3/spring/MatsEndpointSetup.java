package io.mats3.spring;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AliasFor;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsEndpoint.ProcessReturnLambda;
import io.mats3.MatsFactory;
import io.mats3.spring.MatsEndpointSetup.MatsEndpointSetups;

/**
 * A method annotated with this repeatable annotation specifies a method that shall <em>set up</em> a (usually)
 * Multi-Staged Mats Endpoint. Note that as opposed to {@link MatsMapping @MatsMapping}, this method will be invoked
 * <em>once</em> to <i>set up</i> the endpoint, and will not be invoked each time when the Mats endpoint is invoked (as
 * is the case with {@literal @MatsMapping}). <b>You might want to check up on the {@link MatsClassMapping} instead.</b>
 * <p />
 * You specify the EndpointId, state STO type and the reply DTO type using annotation parameters. The method is then
 * invoked with a {@link MatsEndpoint MatsEndpoint} instance as argument, and the method should then invoke
 * {@link MatsEndpoint#stage(Class, ProcessLambda) endpoint.stage(..)} and
 * {@link MatsEndpoint#lastStage(Class, ProcessReturnLambda) endpoint.lastStage(..)} to set up the MatsEndpoint. You can
 * also have an argument of type {@link EndpointConfig} to be able to configure the endpoint. Remember again that this
 * is a <i>setup method</i> where you should set up the endpoint, and it is invoked only <i>once</i> during startup, and
 * then never again.
 * <p />
 * In a multi-MatsFactory setup, you may qualify which MatsFactory this Endpoint should be constructed on - read JavaDoc
 * on @{@link MatsMapping} for how this works.
 *
 * @see MatsMapping
 * @see MatsClassMapping
 *
 * @author Endre Stølsvik - 2016-08-07 - http://endre.stolsvik.com
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Repeatable(MatsEndpointSetups.class)
public @interface MatsEndpointSetup {
    /**
     * The Mats <em>Endpoint Id</em> that this endpoint should listen to.
     *
     * @return the Mats <em>Endpoint Id</em> which this endpoint listens to.
     */
    @AliasFor("value")
    String endpointId() default "";

    /**
     * Alias for "endpointId", so that if you only need to set the endpointId, you can do so directly:
     * <code>@MatsEndpointSetup("endpointId")</code>
     *
     * @return the endpointId.
     */
    @AliasFor("endpointId")
    String value() default "";

    /**
     * The Mats <em>State Transfer Object</em> class that should be employed for all of the stages for this endpoint.
     *
     * @return the <em>State Transfer Object</em> class that should be employed for all of the stages for this endpoint.
     */
    Class<?> state() default void.class;

    /**
     * The Mats <em>Data Transfer Object</em> class that will be returned by the last stage of the staged endpoint.
     *
     * @return the <em>Data Transfer Object</em> class that will be returned by the last stage of the staged endpoint.
     */
    Class<?> reply() default void.class;

    /**
     * Specifies the {@link MatsFactory} to use by means of a specific qualifier annotation type (which thus must be
     * meta-annotated with {@link Qualifier}). Notice that this will search for the custom qualifier annotation
     * <i>type</i>, as opposed to if you add the annotation to the @MatsEndpointSetup-annotated method directly, in
     * which case it "equals" the annotation <i>instance</i> (as Spring also does when performing injection with such
     * qualifiers). The difference comes into play if the annotation has values, where e.g. a
     * <code>@SpecialMatsFactory(location="central")</code> is not equal to
     * <code>@SpecialMatsFactory(location="region_west")</code> - but they are equal when comparing types, as the
     * qualification here does. Thus, if using this qualifier-approach, you should probably not use values on your
     * custom qualifier annotations (instead make separate custom qualifier annotations, e.g.
     * <code>@MatsFactoryCentral</code> and <code>@MatsFactoryRegionWest</code> for the example).
     *
     * @return the <i>custom qualifier type</i> which the wanted {@link MatsFactory} is qualified with.
     */
    Class<? extends Annotation> matsFactoryCustomQualifierType() default Annotation.class;

    /**
     * Specified the {@link MatsFactory} to use by means of specifying the <code>@Qualifier</code> <i>value</i>. Spring
     * performs such lookup by first looking for actual qualifiers with the specified value, e.g.
     * <code>@Qualifier(value="the_value")</code>. If this does not produce a result, it will try to find a bean with
     * this value as the bean name.
     *
     * @return the <i>qualifier value</i> which the wanted {@link MatsFactory} is qualified with.
     */
    String matsFactoryQualifierValue() default "";

    /**
     * Specified the {@link MatsFactory} to use by means of specifying the bean name of the {@link MatsFactory}.
     *
     * @return the <i>bean name</i> of the wanted {@link MatsFactory}.
     */
    String matsFactoryBeanName() default "";

    @Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface MatsEndpointSetups {
        MatsEndpointSetup[] value();
    }
}
