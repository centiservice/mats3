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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Import;

import io.mats3.MatsFactory;

/**
 * Enables Mats "SpringConfig", which is bean-scanning for methods on Spring beans annotated with {@link MatsMapping},
 * {@link MatsClassMapping} and {@link MatsEndpointSetup}, conceptually inspired by the {@literal @EnableWebMvc}
 * annotation. One (or several) {@link MatsFactory}s must be set up in the Spring context. Methods (or classes in case
 * of @MatsClassMapping) having the specified annotations will get Mats endpoints set up for them on the
 * <code>MatsFactory</code>.
 * <p>
 * This annotation simply imports the {@link MatsSpringAnnotationRegistration} bean, which is a Spring
 * {@link BeanPostProcessor}. Read more JavaDoc there!
 *
 * @author Endre Stølsvik - 2016-05-21 - http://endre.stolsvik.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(MatsSpringAnnotationRegistration.class)
@Documented
public @interface EnableMats {
}
