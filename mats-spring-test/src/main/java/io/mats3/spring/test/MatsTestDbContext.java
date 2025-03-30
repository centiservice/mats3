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

package io.mats3.spring.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

import io.mats3.spring.test.MatsTestDbContext.MatsSimpleTestInfrastructureDbContextInitializer;

/**
 * Same as {@link MatsTestContext}, but includes a H2 DataSource, as configured by
 * {@link MatsTestInfrastructureDbConfiguration}.
 *
 * @see TestSpringMatsFactoryProvider
 * @author Endre Stølsvik - 2020-11 - http://endre.stolsvik.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// @ContextConfiguration makes it possible to annotate the test class itself with this annotation
@ContextConfiguration(initializers = MatsSimpleTestInfrastructureDbContextInitializer.class)
// @Import makes it possible to annotate a @Configuration class with this annotation
@Import(MatsTestInfrastructureDbConfiguration.class)
// @DirtiesContext since most tests needs this.
@DirtiesContext
// @Documented is only for JavaDoc: The documentation will show that the class is annotated with this annotation.
@Documented
// Meta for @ActiveProfiles(MatsProfiles.PROFILE_MATS_TEST)
@MatsTestProfile
public @interface MatsTestDbContext {

    /**
     * The reason for this obscure way to add the {@link MatsTestInfrastructureDbConfiguration} (as opposed to just
     * point to it with "classes=..") is as follows: Spring's testing integration has this feature where any static
     * inner @Configuration class of the test class is automatically loaded. If we specify specify classes= or
     * location=, this default will be thwarted.
     *
     * @see <a href=
     *      "https://docs.spring.io/spring-framework/docs/current/reference/html/testing.html#testcontext-ctx-management-javaconfig">
     *      Context Configuration with Component Classes</a>.
     */
    class MatsSimpleTestInfrastructureDbContextInitializer implements
            ApplicationContextInitializer<ConfigurableApplicationContext> {
        // Use clogging, since that's what Spring does.
        private static final Log log = LogFactory.getLog(MatsSimpleTestInfrastructureDbContextInitializer.class);
        private static final String LOG_PREFIX = "#SPRINGMATS# ";

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            log.debug(LOG_PREFIX + "Registering " + MatsTestInfrastructureDbConfiguration.class.getSimpleName()
                    + " on: " + applicationContext);
            /*
             * Hopefully all the ConfigurableApplicationContexts presented here will also be a BeanDefinitionRegistry.
             * This at least holds for the default 'GenericApplicationContext'.
             */
            new AnnotatedBeanDefinitionReader((BeanDefinitionRegistry) applicationContext.getBeanFactory())
                    .register(MatsTestInfrastructureDbConfiguration.class);
        }
    }

}
