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
