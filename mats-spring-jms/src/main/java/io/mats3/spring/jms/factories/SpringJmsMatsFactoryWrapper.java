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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import io.mats3.MatsFactory;
import io.mats3.MatsFactory.MatsFactoryWrapper;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.test.MatsTestFactory;

import jakarta.annotation.PostConstruct;
import jakarta.jms.ConnectionFactory;

/**
 * Wrapper that should be used for a JmsMatsFactory in a Spring context. In addition to the wrapped {@link MatsFactory},
 * it also needs the JMS {@link ConnectionFactory} which the MatsFactory employs as that will be used to handle the
 * properties of "MatsTestBrokerInterface" for when the MatsFactory produced will be used in a test scenario (which it
 * will in a setup employing the {@link ScenarioConnectionFactoryProducer}).
 * <p />
 * Current features:
 * <ul>
 * <li>If the Spring context contains an (empty) instance of 'MatsTestBrokerInterface', it populates it with the
 * required properties.</li>
 * <li>When in a test or development scenario <i>(as defined by either Spring profile "mats-test" being active, or the
 * ConnectionFactory provided is of type {@link ScenarioConnectionFactoryWrapper} and the scenario is
 * {@link MatsScenario#LOCALVM})</i>, it sets the MatsFactory's default concurrency to 2, to avoid tons of unnecessary
 * threads and polluted log output.</li>
 * </ul>
 * <p />
 * <b>Notice! It by default relies on Spring property injection and @PostConstruct being run to do its thing - if you
 * are in a FactoryBean scenario, then read up on the
 * {@link #postConstructForFactoryBean(Environment, ApplicationContext)} method!</b>
 *
 * @author Endre Stølsvik 2020-11-28 01:28 - http://stolsvik.com/, endre@stolsvik.com
 */
public class SpringJmsMatsFactoryWrapper extends MatsFactoryWrapper {

    public static final String MATS_TEST_BROKER_INTERFACE_CLASSNAME = "io.mats3.test.MatsTestBrokerInterface";
    public static final String LATE_POPULATE_METHOD_NAME = "_latePopulate";

    private static final Logger log = LoggerFactory.getLogger(SpringJmsMatsFactoryWrapper.class);
    private static final String LOG_PREFIX = "#SPRINGJMATS# ";

    private final ConnectionFactory _connectionFactory;
    private final MatsFactory _matsFactory;

    private Class<?> _matsTestBrokerInterfaceClass;

    private Environment _environment;
    private ApplicationContext _applicationContext;

    @Autowired
    public void setEnvironment(Environment environment) {
        _environment = environment;
    }

    @Autowired
    public void setApplicationContext(ApplicationContext applicationContext) {
        _applicationContext = applicationContext;
    }

    {
        // :: Using reflection here to see if we have the MatsTestBrokerInterface (from mats-test) on classpath.
        try {
            _matsTestBrokerInterfaceClass = Class.forName(MATS_TEST_BROKER_INTERFACE_CLASSNAME);
        }
        catch (ClassNotFoundException e) {
            // Handled in the @PostConstruct codepath.
        }
    }

    /**
     * <b>Note: The MatsFactory provided may be a {@link MatsFactoryWrapper}, but it must resolve to a
     * {@link JmsMatsFactory} via the {@link MatsFactory#unwrapFully()}.</b>
     */
    public SpringJmsMatsFactoryWrapper(ConnectionFactory connectionFactory, MatsFactory matsFactory) {
        super(matsFactory);
        _connectionFactory = connectionFactory;
        if (!(matsFactory.unwrapFully() instanceof JmsMatsFactory)) {
            throw new IllegalArgumentException("The supplied matsFactory may be a MatsFactoryWrapper, but it must"
                    + " resolve to a JmsMatsFactory when invoking matsFactory.unwrapFully() - this doesn't [" +
                    matsFactory + "].");
        }
        _matsFactory = matsFactory;
    }

    /**
     * If created as a @Bean, thus sitting directly in the Spring context, this class relies on Spring property
     * injection and <code>@PostConstruct</code> being run. If you need to create this bean using a FactoryMethod
     * <i>(e.g. because you have made a cool Mats single-annotation-configuration solution for your multiple
     * codebases)</i>, you must handle the lifecycle yourself - employ
     * {@link #postConstructForFactoryBean(Environment, ApplicationContext)}.
     *
     * @see #postConstructForFactoryBean(Environment, ApplicationContext)
     */
    @PostConstruct
    public void postConstruct() {
        log.info(LOG_PREFIX + SpringJmsMatsFactoryWrapper.class.getSimpleName() + " got @PostConstructed.");
        @SuppressWarnings("deprecation") // Supporting Spring 4 still
        boolean matsTestProfileActive = _environment.acceptsProfiles(MatsProfiles.PROFILE_MATS_TEST);
        handleMatsTestBrokerInterfacePopulation(matsTestProfileActive);
        handleMatsFactoryConcurrencyForTestAndDevelopment(matsTestProfileActive);
    }

    /**
     * If you construct this bean using a Spring {@link FactoryBean} <i>(e.g. because you have made a cool Mats
     * single-annotation-configuration solution for your multiple codebases)</i>, then you are responsible for its
     * lifecycle, and hence cannot rely on property setting and <code>@PostConstruct</code> being run. Invoke this
     * method in your <code>getObject()</code> (raw FactoryBean implementation) or <code>createInstance()</code>
     * (AbstractFactoryBean implementation). To get hold of the Spring {@link Environment} and Spring
     * {@link ApplicationContext} in the FactoryBean, simply use Spring injection on the FactoryBean, e.g. field-inject.
     *
     * @param environment
     *            the Spring {@link Environment}
     * @param applicationContext
     *            the Spring {@link ApplicationContext}.
     * @see #postConstruct()
     */
    public void postConstructForFactoryBean(Environment environment, ApplicationContext applicationContext) {
        log.info(LOG_PREFIX + SpringJmsMatsFactoryWrapper.class.getSimpleName() + " got invoked"
                + " postConstructForFactoryBean(env, appContext).");
        _environment = environment;
        _applicationContext = applicationContext;
        @SuppressWarnings("deprecation") // Supporting Spring 4 still
        boolean matsTestProfileActive = environment.acceptsProfiles(MatsProfiles.PROFILE_MATS_TEST);
        handleMatsTestBrokerInterfacePopulation(matsTestProfileActive);
        handleMatsFactoryConcurrencyForTestAndDevelopment(matsTestProfileActive);
    }

    protected void handleMatsTestBrokerInterfacePopulation(boolean matsTestProfileActive) {
        if (_matsTestBrokerInterfaceClass == null) {
            if (matsTestProfileActive) {
                log.warn(LOG_PREFIX + " \\- Class '" + MATS_TEST_BROKER_INTERFACE_CLASSNAME
                        + "' not found on classpath. If you need this tool, you would want to have it on classpath,"
                        + " and have your testing Spring context to contain an \"empty\" such bean"
                        + " (MatsTestBrokerInterface.createForLaterPopulation()) so that I could populate it for you"
                        + " with the JMS ConnectionFactory and necessary properties. (The @MatsTestContext and"
                        + " MatsTestInfrastructureConfiguration will do this for you).");
            }
            else {
                log.info(LOG_PREFIX + " \\- Class '" + MATS_TEST_BROKER_INTERFACE_CLASSNAME
                        + "' not found on classpath, probably not in a testing scenario.");
            }
            return;
        }

        AutowireCapableBeanFactory autowireCapableBeanFactory = _applicationContext.getAutowireCapableBeanFactory();
        Object matsTestBrokerInterface;
        try {
            matsTestBrokerInterface = autowireCapableBeanFactory.getBean(_matsTestBrokerInterfaceClass);
        }
        catch (NoSuchBeanDefinitionException e) {
            String msg = " \\- Testing tool '" + MATS_TEST_BROKER_INTERFACE_CLASSNAME
                    + "' found on classpath, but not in Spring context. If you need this tool, you would want your"
                    + " testing Spring context to contain an \"empty\" such bean"
                    + " (MatsTestBrokerInterface.createForLaterPopulation()) so that I could populate it for you with"
                    + " the JMS ConnectionFactory and necessary properties. (The @MatsTestContext and"
                    + " MatsTestInfrastructureConfiguration will do this for you).";
            if (matsTestProfileActive) {
                log.warn(LOG_PREFIX + msg);
            }
            else {
                log.info(LOG_PREFIX + msg);
            }
            return;
        }
        log.info(LOG_PREFIX + " |- Found '" + MATS_TEST_BROKER_INTERFACE_CLASSNAME + "' in Spring Context: "
                + matsTestBrokerInterface);

        // :: Now populate the tool we found in the Spring context.
        try {
            Method latePopulateMethod = _matsTestBrokerInterfaceClass.getMethod(LATE_POPULATE_METHOD_NAME,
                    ConnectionFactory.class,
                    MatsFactory.class);
            latePopulateMethod.invoke(matsTestBrokerInterface, _connectionFactory, unwrap());
            if (log.isDebugEnabled()) log.debug(LOG_PREFIX + " \\- Invoked the " + LATE_POPULATE_METHOD_NAME + " on '"
                    + MATS_TEST_BROKER_INTERFACE_CLASSNAME + " to make the tool ready.");
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError("Didn't find method '" + LATE_POPULATE_METHOD_NAME + "(..)' on Class '"
                    + MATS_TEST_BROKER_INTERFACE_CLASSNAME + "!", e);
        }
        catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Couldn't invoke method '" + LATE_POPULATE_METHOD_NAME + "(..)' on Class '"
                    + MATS_TEST_BROKER_INTERFACE_CLASSNAME + "!", e);
        }
    }

    protected void handleMatsFactoryConcurrencyForTestAndDevelopment(boolean matsTestPofileActive) {
        // ?: Are we in explicit testing profile? (i.e. Spring Profile 'mats-test' is active)
        if (matsTestPofileActive) {
            // -> Yes, mats-test profile active, so set concurrency low.
            // ?: Are we in default concurrency?
            if (getFactoryConfig().isConcurrencyDefault()) {
                // -> Yes, default concurrency - so set testing specs for concurrency and max delivery attempts.
                log.info(LOG_PREFIX + "We're in Spring Profile '" + MatsProfiles.PROFILE_MATS_TEST
                        + "', so set concurrency to " + MatsTestFactory.TEST_CONCURRENCY
                        + " to avoid dozens of threads and messy logs, and set"
                        + " max delivery attempts to " + MatsTestFactory.TEST_MAX_DELIVERY_ATTEMPTS + ".");
                // Set test concurrency
                getFactoryConfig().setConcurrency(MatsTestFactory.TEST_CONCURRENCY);
                // Also set test max delivery attempts
                ((JmsMatsFactory) unwrapFully()).setMatsManagedDlqDivert(
                        MatsTestFactory.TEST_MAX_DELIVERY_ATTEMPTS);
            }
            else {
                // -> No, concurrency evidently already set to something non-default, so will not override this.
                log.info(LOG_PREFIX + "We're in Spring Profile '" + MatsProfiles.PROFILE_MATS_TEST
                        + "', but the concurrency of MatsFactory is already set to something non-default ("
                        + getFactoryConfig().getConcurrency() + "), so will not mess with that"
                        + " (would have set to " + MatsTestFactory.TEST_CONCURRENCY + ").");
            }
        }
        else {
            // -> No, not testing, but might still be LOCALVM (probably development mode, or non-explicit testing)
            // ?: Is the provided JMS ConnectionFactory a ConnectionFactoryScenarioWrapper?
            if (_connectionFactory instanceof ScenarioConnectionFactoryWrapper) {
                // -> Yes it is, check if we're in LOCALVM mode
                ScenarioConnectionFactoryWrapper scenarioWrapped = (ScenarioConnectionFactoryWrapper) _connectionFactory;
                // ?: Are we in MatsScenario.LOCALVM?
                MatsScenario matsScenario = scenarioWrapped.getMatsScenarioUsedToMakeConnectionFactory();
                if (matsScenario == MatsScenario.LOCALVM) {
                    // -> Yes, so assume development
                    // ?: Are we in default concurrency?
                    if (getFactoryConfig().isConcurrencyDefault()) {
                        // -> Yes, default concurrency - so set testing specs for concurrency and max delivery attempts.
                        log.info(LOG_PREFIX + "The supplied ConnectionFactory was created with MatsScenario.LOCALVM,"
                                + " so we assume this is a development situation (or testing where the user forgot to"
                                + " add the Spring active profile 'mats-test' as with @MatsTestProfile), and thus set"
                                + " the concurrency to " + MatsTestFactory.TEST_CONCURRENCY
                                + " to avoid dozens of threads and messy logs, and set"
                                + " max delivery attempts to " + MatsTestFactory.TEST_MAX_DELIVERY_ATTEMPTS + ".");
                        // Set test concurrency
                        getFactoryConfig().setConcurrency(MatsTestFactory.TEST_CONCURRENCY);
                        // Also set test max delivery attempts
                        ((JmsMatsFactory) unwrapFully()).setMatsManagedDlqDivert(
                                MatsTestFactory.TEST_MAX_DELIVERY_ATTEMPTS);
                    }
                    else {
                        // -> No, concurrency evidently already set to something non-default, so will not override this.
                        log.info(LOG_PREFIX + "The supplied ConnectionFactory was created with MatsScenario.LOCALVM,"
                                + " so we assume this is a development situation (or testing where the user forgot to"
                                + " add the Spring active profile 'mats-test' as with @MatsTestProfile),"
                                + " HOWEVER, the concurrency is already set to something non-default ("
                                + getFactoryConfig().getConcurrency() + "), so will not mess with that"
                                + " (would have set it to " + MatsTestFactory.TEST_CONCURRENCY + ").");
                    }
                }
            }
        }
    }
}
