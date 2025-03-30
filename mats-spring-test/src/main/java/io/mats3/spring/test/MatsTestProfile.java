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

import org.springframework.test.context.ActiveProfiles;

import io.mats3.spring.jms.factories.MatsProfiles;
import io.mats3.spring.jms.factories.ScenarioConnectionFactoryProducer;

/**
 * The only thing this annotation does, is to meta-annotate the test class with
 * <code>@ActiveProfiles({@link MatsProfiles#PROFILE_MATS_TEST})</code>. This is of relevance if you employ the
 * {@link ScenarioConnectionFactoryProducer JmsSpringConnectionFactoryProducer}
 * "scenario decider" system - the "mats-test" profile per default sends this into "in-vm broker mode".
 * <p />
 * You may just as well do the direct <code>{@literal @ActiveProfiles}</code> annotation yourself, but this is a few
 * letter shorter, and slightly more concise.
 *
 * @author Endre Stølsvik 2019-06-17 19:06 - http://stolsvik.com/, endre@stolsvik.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ActiveProfiles(MatsProfiles.PROFILE_MATS_TEST)
@Documented
public @interface MatsTestProfile {}
