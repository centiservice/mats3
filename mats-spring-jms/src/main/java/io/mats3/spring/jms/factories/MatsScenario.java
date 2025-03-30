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

package io.mats3.spring.jms.factories;

import io.mats3.spring.jms.factories.ScenarioConnectionFactoryWrapper.ConnectionFactoryProvider;
import io.mats3.spring.jms.factories.ScenarioConnectionFactoryWrapper.ScenarioDecider;

/**
 * The three different Mats Scenarios that the {@link ScenarioConnectionFactoryWrapper} juggles between based on the
 * results of a set of three {@link ScenarioDecider}s, with an optional default choice.
 * <p />
 * The method {@link ConfigurableScenarioDecider#createDefaultScenarioDecider()} creates a
 * {@link ConfigurableScenarioDecider} that sets up the standard links between the different {@link MatsProfiles} values
 * and these scenarios, as described in the JavaDoc of those values.
 * <p />
 * <b>The main documentation for this MatsScenario concept is in the JavaDoc of
 * {@link ScenarioConnectionFactoryProducer}</b>.
 *
 * @see ScenarioConnectionFactoryProducer
 * @see ConfigurableScenarioDecider
 * @see ScenarioConnectionFactoryWrapper
 */
public enum MatsScenario {
    /**
     * For Production, Staging, Pre-prod, etc.
     *
     * @see ScenarioConnectionFactoryProducer#withRegularConnectionFactory(ConnectionFactoryProvider)
     */
    REGULAR,

    /**
     * <b>NOTICE: Only meant for development and testing.</b>
     *
     * @see ScenarioConnectionFactoryProducer#withLocalhostConnectionFactory(ConnectionFactoryProvider)
     */
    LOCALHOST,

    /**
     * <b>NOTICE: Only meant for development and testing.</b>
     *
     * @see ScenarioConnectionFactoryProducer#withLocalVmConnectionFactory(ConnectionFactoryProvider)
     */
    LOCALVM
}
