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
import org.springframework.stereotype.Service;

import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.MatsStage.StageConfig;
import io.mats3.spring.MatsClassMapping.MatsClassMappings;

/**
 * A class annotated with this repeatable annotation will become a Mats Endpoint, where an instance of the class itself
 * is the State (STO) object for the Endpoint, and each @{@link Stage Stage}-annotated method on the class is a stage of
 * the Endpoint. This annotation is meta-annotated with @{@link Service}, and is thus also a Spring bean. This singleton
 * Spring bean acts as a "template" for the State, and any wired (injected/autowired) fields will be available for the
 * stage-methods, but will not be regarded as part of the State when serialized and sent on the wire. You should use the
 * Java-modifier <code>transient</code> on such Spring-injected fields, so that the serializer understands that it is
 * not a part of state. In addition, if a field is of type {@link ProcessContext ProcessContext}, it will be injected by
 * the Mats JavaConfig machinery before a stage-method is invoked.
 * <p/>
 * All methods that are annotated with @{@link Stage Stage} is regarded as a stage of the Mats Endpoint. To know the
 * order of the stages, and ordinal must be assigned to the Stage - read its JavaDoc. The initial stage is the method
 * annotated with <code>@Stage(0)</code> (The constant {@link Stage#INITIAL Stage.INITIAL} is conveniently available),
 * and the subsequent stages are the ordered list of these stage-methods. The last stage will typically have a return
 * type - which defines the return type of the endpoint - while no other stage can have a return type. It is, however,
 * still possible to return early if needed, by using {@link ProcessContext#reply(Object) context.reply(..)}. If the
 * last stage does not have a return type (i.e. it is <code>void</code>), it will be a Terminator. Any methods that are
 * not annotated with <code>@Stage</code> is ignored, thus you may structure and divide your code into sub methods as
 * you see fit.
 * <p/>
 * <b>Note:</b> If you only need a single stage, i.e. a single-stage Endpoint, or Terminator, you should instead
 * consider {@link MatsMapping}.
 * <p/>
 * <b>Note:</b> You should mark the injected/autowired fields with the java keyword <code>transient</code> to ensure
 * that the serializer understands that it is not part of the state. <i>(The values are <code>null</code>'ed out before
 * state serialization takes place, so an injected DataSource will never be attempted serialized as state. However, some
 * type of serializers, e.g. Gson, will prepare to serialize all fields, even though they will always be null, and thus
 * possibly fail on newer versions of Java. Such preparation does not take place if a field is marked transient.)</i>
 * <p/>
 * Each stage-method can take zero, one, two or many arguments. The machinery looks for a ProcessContext and an incoming
 * DTO argument. Neither ProcessContext nor an incoming DTO is required. The rationale for taking more than the required
 * arguments is that you could potentially want to invoke it differently in a testing scenario (depending on how you
 * feel towards testing).
 * <ul>
 * <li>0: No ProcessContext nor incoming DTO.</li>
 * <li>1: Either it is a ProcessContext, otherwise it must be the incoming DTO.</li>
 * <li>2: If one is a ProcessContext, the other must be the incoming DTO.</li>
 * <li>2+: One may be the ProcessContext, and the others are searched for the @{@link Dto} annotation, and if one is
 * found, this is the incoming DTO.</li>
 * </ul>
 * <p/>
 * The logic to determine which fields are state, and which fields are injected, is as follows: When Mats SpringConfig
 * gets the bean, injection is already performed by Spring. Any fields that are non-null is thus assumed to be injected
 * by Spring and not part of the State, <i>unless</i> the field is <i>also</i> non-null when simply instantiated as a
 * new object, using the current Mats serialization mechanism of creating an empty State object. The rationale for the
 * latter is that such fields must be default initialized, like e.g.
 * <code>List&lt;Car&gt; cars = new ArrayList&lt;&gt;()</code>. A field of type ProcessContext is not a state field.
 * <p/>
 * It is worth noting that the singleton Spring-constructed bean instance is never actually employed outside of being
 * inspected at start up: Its class is inspected to set up the MatsEndpoint and its stages, and the singleton instance
 * is used as a <i>template</i> for which fields of the Endpoint's State object are injected, and which fields are
 * state.
 * <p/>
 * In a multi-MatsFactory setup, you may qualify which MatsFactory this Endpoint should be constructed on - read JavaDoc
 * on @{@link MatsMapping} for how this works.
 *
 * @see MatsMapping
 * @see MatsEndpointSetup
 *
 * @author Endre Stølsvik 2019-08-17 21:53 - http://stolsvik.com/, endre@stolsvik.com
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Repeatable(MatsClassMappings.class)
@Service
public @interface MatsClassMapping {
    /**
     * The Mats <em>Endpoint Id</em> that this endpoint should listen to.
     *
     * @return the Mats <em>Endpoint Id</em> which this endpoint listens to.
     */
    @AliasFor("value")
    String endpointId() default "";

    /**
     * Alias for "endpointId", so that if you only need to set the endpointId, you can do so directly:
     * <code>@MatsClassMapping("endpointId")</code>
     *
     * @return the endpointId.
     */
    @AliasFor("endpointId")
    String value() default "";

    /**
     * A string representing the {@link EndpointConfig#setConcurrency(int) concurrency} of the Endpoint. Currently
     * only digits are allowed, and the value is passed directly to {@link Integer#parseInt(String)}. <i/>(In a future
     * version it might be possible to specify a Spring SpEL expression, which would be evaluated against a context
     * of the parent MatsFactory so that you could say "parentFactory * 2", and include the Spring Environment, so
     * that you could say "env['mats.concurrency'] * 2" or similar constructs.)</i>
     */
    String concurrency() default "";

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

    /**
     * Each method in the class that shall correspond to a Stage on the Mats endpoint must be annotated with this
     * <code>@Stage</code> annotation. An {@link #ordinal() ordinal} must be assigned to each stage, so that Mats knows
     * which order the stages are in - read more about the ordinal number at {@link #ordinal()}. The initial stage must
     * have the ordinal zero, which also shall be the first stage in the resulting sorted list of Stages (i.e. negative
     * values are not allowed) - this is the Stage which gets incoming messages targeted at the
     * {@link MatsClassMapping#endpointId() endpointId} of this endpoint.
     */
    @Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface Stage {
        /**
         * Constant for 0 (zero), the initial Stage's ordinal.
         */
        int INITIAL = 0;

        /**
         * The ordinal of this Stage in the sequence of stages of this endpoint - that is, an integer that expresses the
         * relative position of this Stage wrt. to the other stages. The initial Stage must have the ordinal zero, you
         * may use the constant {@link #INITIAL}. The magnitude of the number does not matter, only the "sort order", so
         * 0, 1, 2 is just as good as 0, 3, 5, which is just as good as 0, 4527890, 4527990 - although one can
         * definitely discuss the relative merits between each approach.
         *
         * @return the ordinal of this Stage in the sequence of stages of this endpoint.
         */
        @AliasFor("value")
        int ordinal() default -1;

        /**
         * Alias for "ordinal", so that if you only need to set the ordinal (which relative position in the sequence of
         * stages this Stage is), you can do so directly: <code>@Stage(INITIAL)</code> and <code>@Stage(3)</code>.
         *
         * @see #ordinal()
         *
         * @return the ordinal of this Stage in the sequence of stages of this endpoint.
         */
        @AliasFor("ordinal")
        int value() default -1;

        /**
         * A string representing the {@link StageConfig#setConcurrency(int) concurrency} for the Stage, overriding the
         * concurrency of the Endpoint. See {@link MatsClassMapping#concurrency()} for more information.
         */
        String concurrency() default "";
    }

    @Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface MatsClassMappings {
        MatsClassMapping[] value();
    }
}
