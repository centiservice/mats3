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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationScopeMetadataResolver;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ScopeMetadataResolver;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.annotation.AliasFor;

/**
 * A simple convenience replacement for @ComponentScan which excludes any configuration classes employing the special
 * {@link ConfigurationForTest @ConfigurationForTest} annotation instead of the standard <code>@Configuration</code>
 * annotation. This is meant as a solution for a rather common problem that arises when your application
 * uses @ComponentScan to find @Services, @Components etc, and have integration tests that reside in "src/test/java",
 * but has the same package as the application code (to allow for package access). In the tests, you might want to
 * include the entire application's Spring configuration, thus you include the same @ComponentScan. The problem now is
 * that since all the test classes are included on the classpath when running a test, and at the same time reside in the
 * same package structure, the component scan will pick up all tests' @Configuration classes too. This is absolutely not
 * what you wanted - in particular if e.g. two different tests tries to set up two different variants of a mock
 * collaborating Mats endpoint (that is, an application-external service that this application communicates with):
 * You'll end up trying to take up both variants at the same time, and since they have the same endpointId, Mats will
 * refuse this.
 * <p>
 * If you employ this annotation (@ComponentScanExcludingConfigurationForTest) - or set up the same "excludeFilters" as
 * this annotation does - you will <i>not</i> include any configuration classes that are annotated with
 * {@link ConfigurationForTest @ConfigurationForTest} instead of the ordinary @Configuration. However, each test will
 * still pick up its own inner static @ConfigurationForTest class(es).
 * <p>
 * A test class employing @ConfigurationForTest would look something like this:
 *
 * <pre>
 * &#64;MatsTestProfile // &lt;- If you employ the JmsSpringConnectionFactoryProducer's logic to handle JMS ConnectionFactory
 * &#64;RunWith(SpringRunner.class)
 * public class IntegrationTestClass {
 *
 *     &#64;ConfigurationForTest
 *     &#64;Import(ApplicationSpringConfigurationClass_Using_ComponentScanExcludingConfigurationForTest.class)
 *     public static class InnerStaticConfigurationClassForTest {
 *         // &#64;Bean definitions
 *         // &#64;MatsMapping definitions
 *     }
 *
 *     // @Inject'ed fields..
 *     // @Test-methods..
 * }
 * </pre>
 * <p>
 * If you want to do the exclusion on your own, you can use the standard @ComponentScan, making sure you include this
 * excludeFilters property:
 *
 * <pre>
 *  &#64;ComponentScan(excludeFilters = {
 *      &#64;Filter(type = FilterType.ANNOTATION, value = ConfigurationForTest.class)
 *  })
 * </pre>
 * <p>
 * This class has @AliasFor all properties that Spring 4.x has on @ComponentScan. If new properties arrive later which
 * you want to use, you will have to do the exclusion on your own.
 * <p>
 * <b>NOTICE:</b> An alternative way to achieve the same effect is to annotate the integration test class
 * with @ContextConfiguration, pointing to both the application's Spring setup, and also the configuration class
 * residing within the test class - <i>but where the test's configuration class is <b>not</b> annotated
 * with @Configuration.</i> Such an integration test class would look something like this:
 *
 * <pre>
 * &#64;MatsTestProfile // &lt;- If you employ the JmsSpringConnectionFactoryProducer's logic to handle JMS ConnectionFactory
 * &#64;RunWith(SpringRunner.class)
 * &#64;ContextConfiguration(classes = { ApplicationSpringConfigurationClass_Using_ComponentScan.class,
 *         InnerStaticConfigurationClassForTest.class })
 * public class IntegrationTestClass {
 *
 *     // Notice how this configuration class is NOT annotated with &#64;Configuration, to avoid being picked up by
 *     // application's &#64;ComponentScan
 *     public static class InnerStaticConfigurationClassForTest {
 *         // &#64;Bean definitions
 *         // &#64;MatsMapping definitions
 *     }
 *
 *     // @Inject'ed fields..
 *     // @Test-methods..
 * }
 * </pre>
 *
 * @see ConfigurationForTest
 * @see <a href="https://stackoverflow.com/a/46344822/39334">Stackoverflow answer to question about this problem, where
 *      the idea for this pair of annotations is ripped from.</a>
 *
 * @author Endre Stølsvik 2019-08-12 22:33 - http://stolsvik.com/, endre@stolsvik.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Configuration
// meta-annotation:
@ComponentScan(excludeFilters = {
        @Filter(type = FilterType.ANNOTATION, value = ConfigurationForTest.class)
})
public @interface ComponentScanExcludingConfigurationForTest {
    /**
     * Alias for {@link #basePackages}.
     * <p>
     * Allows for more concise annotation declarations if no other attributes are needed &mdash; for example,
     * {@code @ComponentScan("org.my.pkg")} instead of {@code @ComponentScan(basePackages = "org.my.pkg")}.
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "value")
    String[] value() default {};

    /**
     * Base packages to scan for annotated components.
     * <p>
     * {@link #value} is an alias for (and mutually exclusive with) this attribute.
     * <p>
     * Use {@link #basePackageClasses} for a type-safe alternative to String-based package names.
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackages")
    String[] basePackages() default {};

    /**
     * Type-safe alternative to {@link #basePackages} for specifying the packages to scan for annotated components. The
     * package of each class specified will be scanned.
     * <p>
     * Consider creating a special no-op marker class or interface in each package that serves no purpose other than
     * being referenced by this attribute.
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] basePackageClasses() default {};

    /**
     * The {@link BeanNameGenerator} class to be used for naming detected components within the Spring container.
     * <p>
     * The default value of the {@link BeanNameGenerator} interface itself indicates that the scanner used to process
     * this {@code @ComponentScan} annotation should use its inherited bean name generator, e.g. the default
     * {@link AnnotationBeanNameGenerator} or any custom instance supplied to the application context at bootstrap time.
     *
     * @see AnnotationConfigApplicationContext#setBeanNameGenerator(BeanNameGenerator)
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "nameGenerator")
    Class<? extends BeanNameGenerator> nameGenerator() default BeanNameGenerator.class;

    /**
     * The {@link ScopeMetadataResolver} to be used for resolving the scope of detected components.
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "scopeResolver")
    Class<? extends ScopeMetadataResolver> scopeResolver() default AnnotationScopeMetadataResolver.class;

    /**
     * Indicates whether proxies should be generated for detected components, which may be necessary when using scopes
     * in a proxy-style fashion.
     * <p>
     * The default is defer to the default behavior of the component scanner used to execute the actual scan.
     * <p>
     * Note that setting this attribute overrides any value set for {@link #scopeResolver}.
     *
     * @see ClassPathBeanDefinitionScanner#setScopedProxyMode(ScopedProxyMode)
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "scopedProxy")
    ScopedProxyMode scopedProxy() default ScopedProxyMode.DEFAULT;

    /**
     * Controls the class files eligible for component detection.
     * <p>
     * Consider use of {@link #includeFilters} and {@link #excludeFilters} for a more flexible approach.
     */
    // Notice: "ClassPathScanningCandidateComponentProvider.DEFAULT_RESOURCE_PATTERN" is package-private
    @AliasFor(annotation = ComponentScan.class, attribute = "resourcePattern")
    String resourcePattern() default "**/*.class";

    /**
     * Indicates whether automatic detection of classes annotated with {@code @Component} {@code @Repository},
     * {@code @Service}, or {@code @Controller} should be enabled.
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "useDefaultFilters")
    boolean useDefaultFilters() default true;

    /**
     * Specifies which types are eligible for component scanning.
     * <p>
     * Further narrows the set of candidate components from everything in {@link #basePackages} to everything in the
     * base packages that matches the given filter or filters.
     * <p>
     * Note that these filters will be applied in addition to the default filters, if specified. Any type under the
     * specified base packages which matches a given filter will be included, even if it does not match the default
     * filters (i.e. is not annotated with {@code @Component}).
     *
     * @see #resourcePattern()
     * @see #useDefaultFilters()
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "includeFilters")
    Filter[] includeFilters() default {};

    /**
     * Specifies which types are not eligible for component scanning. <B>NOTICE: For this special variant, the default
     * is set to exclude @ConfigurationForTest annotated classes</B>
     *
     * @see #resourcePattern
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "excludeFilters")
    // NOTICE: Must set the exclusion here as default, as that is what really what is applied - the stuff in the meta-
    // annotation at top of @interface definition is just to point out what happens (If this @AliasFor wasn't present,
    // it would pick up the one up there, though).
    Filter[] excludeFilters() default {
            @Filter(type = FilterType.ANNOTATION, value = ConfigurationForTest.class)
    };

    /**
     * Specify whether scanned beans should be registered for lazy initialization.
     * <p>
     * Default is {@code false}; switch this to {@code true} when desired.
     *
     * @since 4.1
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "lazyInit")
    boolean lazyInit() default false;

}
