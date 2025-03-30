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

import javax.jms.ConnectionFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;

import io.mats3.util.wrappers.ConnectionFactoryWrapper;

/**
 * A <code>ConnectionFactoryWrapper</code> which lazily decides which of the three {@link MatsScenario}s are active, and
 * produces the wrapper-target {@link ConnectionFactory} based on that - you most probably want to use
 * {@link ScenarioConnectionFactoryProducer} to make an instance of this class, but you can configure it directly too.
 * <p />
 * <b>The main documentation for this MatsScenario concept is in the JavaDoc of
 * {@link ScenarioConnectionFactoryProducer}</b>.
 *
 * @see ScenarioConnectionFactoryProducer
 * @see MatsProfiles
 * @see MatsScenario
 * @author Endre Stølsvik 2019-06-10 23:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class ScenarioConnectionFactoryWrapper
        extends ConnectionFactoryWrapper
        implements EnvironmentAware, BeanNameAware, SmartLifecycle {
    // Use clogging, since that's what Spring does.
    private static final Log log = LogFactory.getLog(ScenarioConnectionFactoryWrapper.class);
    private static final String LOG_PREFIX = "#SPRINGJMATS# ";

    /**
     * A ConnectionFactory provider which can throw Exceptions - if it returns a
     * {@link ConnectionFactoryWithStartStopWrapper}, start() and stop() will be invoked on that, read more on its
     * JavaDoc.
     */
    @FunctionalInterface
    public interface ConnectionFactoryProvider {
        ConnectionFactory get(Environment springEnvironment) throws Exception;
    }

    /**
     * We need a way to decide between the three different {@link MatsScenario}s. Check out the
     * {@link ConfigurableScenarioDecider}.
     *
     * @see ConfigurableScenarioDecider
     */
    @FunctionalInterface
    public interface ScenarioDecider {
        MatsScenario decision(Environment springEnvironment);
    }

    protected ConnectionFactoryProvider _regularConnectionFactoryProvider;
    protected ConnectionFactoryProvider _localhostConnectionFactoryProvider;
    protected ConnectionFactoryProvider _localVmConnectionFactoryProvider;
    protected ScenarioDecider _scenarioDecider;

    /**
     * Constructor taking {@link ConnectionFactoryProvider}s for each of the three {@link MatsScenario}s and a
     * {@link ScenarioDecider} to decide which of these to employ - you most probably want to use
     * {@link ScenarioConnectionFactoryProducer} to make one of these.
     */
    public ScenarioConnectionFactoryWrapper(ConnectionFactoryProvider regular, ConnectionFactoryProvider localhost,
            ConnectionFactoryProvider localvm, ScenarioDecider scenarioDecider) {
        _regularConnectionFactoryProvider = regular;
        _localhostConnectionFactoryProvider = localhost;
        _localVmConnectionFactoryProvider = localvm;
        _scenarioDecider = scenarioDecider;
    }

    protected String _beanName;

    @Override
    public void setBeanName(String name) {
        _beanName = name;
    }

    protected Environment _environment;

    @Override
    public void setEnvironment(Environment environment) {
        _environment = environment;
    }

    @Override
    public void setWrappee(ConnectionFactory targetConnectionFactory) {
        throw new IllegalStateException("You cannot set a target ConnectionFactory on a "
                + this.getClass().getSimpleName()
                + "; A set of suppliers will have to be provided in the constructor.");
    }

    protected volatile ConnectionFactory _targetConnectionFactory;
    protected volatile MatsScenario _matsScenarioDecision;

    @Override
    public ConnectionFactory unwrap() {
        /*
         * Perform lazy init, even though it should have been produced by SmartLifeCycle.start() below. It is here for
         * the situation where all beans have been put into lazy init mode (the test-helper project "Remock" does this).
         * Evidently what can happen then, is that life cycle process can have been run, and then you get more beans
         * being pulled up - but these were too late to be lifecycled. Thus, the start() won't be run, so we'll get a
         * null target ConnectionFactory when we request it. By performing lazy-init check here, we hack it in place in
         * such scenarios.
         */
        if (_targetConnectionFactory == null) {
            log.info(LOG_PREFIX + "TargetConnectionFactory is null upon unwrap() - perform lazy-init.");
            synchronized (this) {
                if (_targetConnectionFactory == null) {
                    createTargetConnectionFactoryBasedOnScenarioDecider();
                }
            }
        }
        return _targetConnectionFactory;
    }

    /**
     * @return the {@link MatsScenario} that was used to make the {@link ConnectionFactory} returned by
     *         {@link #unwrap()}.
     */
    public MatsScenario getMatsScenarioUsedToMakeConnectionFactory() {
        if (_matsScenarioDecision == null) {
            synchronized (this) {
                if (_matsScenarioDecision == null) {
                    _matsScenarioDecision = _scenarioDecider.decision(_environment);
                }
            }
            log.info(LOG_PREFIX + "Decided MatsScenario: " + _matsScenarioDecision);
        }
        return _matsScenarioDecision;
    }

    protected void createTargetConnectionFactoryBasedOnScenarioDecider() {
        // Assert sync
        if (!Thread.holdsLock(this)) {
            throw new AssertionError("This should only be invoked while holding sync on 'this'.");
        }
        // Assert that it is not already decided and made
        if (_targetConnectionFactory != null) {
            throw new AssertionError("The ConnectionFactory is already decided and made, why here again?");
        }

        ConnectionFactoryProvider decidedProvider;
        MatsScenario matsScenarioDecision = getMatsScenarioUsedToMakeConnectionFactory();
        switch (matsScenarioDecision) {
            case REGULAR:
                decidedProvider = _regularConnectionFactoryProvider;
                break;
            case LOCALHOST:
                decidedProvider = _localhostConnectionFactoryProvider;
                break;
            case LOCALVM:
                decidedProvider = _localVmConnectionFactoryProvider;
                break;
            default:
                throw new AssertionError("Unknown MatsScenario enum value [" + matsScenarioDecision + "]!");
        }
        log.info(LOG_PREFIX + "Creating ConnectionFactory decided by MatsScenario [" + matsScenarioDecision
                + "] from decided provider [" + decidedProvider + "].");

        // :: Actually get the ConnectionFactory.

        ConnectionFactory providedConnectionFactory;
        try {
            providedConnectionFactory = decidedProvider.get(_environment);
        }
        catch (Exception e) {
            throw new CouldNotGetConnectionFactoryFromProviderException("Got problems when getting the"
                    + " ConnectionFactory from ConnectionFactoryProvider [" + decidedProvider + "] from Scenario ["
                    + matsScenarioDecision + "]", e);
        }

        // :: If the provided ConnectionFactory is "start-stoppable", then we must start it

        // ?: Is it a start-stoppable ConnectionFactory?
        if (providedConnectionFactory instanceof ConnectionFactoryWithStartStopWrapper) {
            // -> Yes, start-stoppable, so start it now (and set any returned target ConnectionFactory..)
            log.info(LOG_PREFIX + "The provided ConnectionFactory from Scenario [" + matsScenarioDecision
                    + "] implements " + ConnectionFactoryWithStartStopWrapper.class.getSimpleName()
                    + ", so invoking start(..) on it.");
            ConnectionFactoryWithStartStopWrapper startStopWrapper = (ConnectionFactoryWithStartStopWrapper) providedConnectionFactory;
            try {
                ConnectionFactory targetConnectionFactory = startStopWrapper.start(_beanName);
                // ?: If the return value is non-null, we'll set it.
                if (targetConnectionFactory != null) {
                    // -> Yes, non-null, so set it per contract.
                    startStopWrapper.setWrappee(targetConnectionFactory);
                }
            }
            catch (Exception e) {
                throw new CouldNotStartConnectionFactoryWithStartStopWrapperException("Got problems starting the"
                        + " ConnectionFactoryWithStartStopWrapper [" + startStopWrapper + "] from Scenario ["
                        + matsScenarioDecision
                        + "].", e);
            }
        }

        // Finally, set the newly produced scenario-specific ConnectionFactory.
        _targetConnectionFactory = providedConnectionFactory;
    }

    protected static class CouldNotGetConnectionFactoryFromProviderException extends RuntimeException {
        public CouldNotGetConnectionFactoryFromProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    protected static class CouldNotStartConnectionFactoryWithStartStopWrapperException extends RuntimeException {
        public CouldNotStartConnectionFactoryWithStartStopWrapperException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    protected static class CouldNotStopConnectionFactoryWithStartStopWrapperException extends RuntimeException {
        public CouldNotStopConnectionFactoryWithStartStopWrapperException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ===== Implementation of SmartLifeCycle

    @Override
    public int getPhase() {
        // Returning a quite low number to be STARTED early, and STOPPED late.
        return -2_000_000;
    }

    private boolean _started;

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void start() {
        log.info(LOG_PREFIX + "SmartLifeCycle.start on [" + _beanName
                + "]: Creating Target ConnectionFactory based on ScenarioDecider [" + _scenarioDecider + "].");
        synchronized (this) {
            if (_targetConnectionFactory == null) {
                createTargetConnectionFactoryBasedOnScenarioDecider();
            }
        }
        _started = true;
    }

    @Override
    public boolean isRunning() {
        return _started;
    }

    @Override
    public void stop() {
        _started = false;
        if ((_targetConnectionFactory != null)
                && (_targetConnectionFactory instanceof ConnectionFactoryWithStartStopWrapper)) {
            try {
                log.info(LOG_PREFIX + "  \\- The current target ConnectionFactory implements "
                        + ConnectionFactoryWithStartStopWrapper.class.getSimpleName()
                        + ", so invoking stop(..) on it.");
                ((ConnectionFactoryWithStartStopWrapper) _targetConnectionFactory).stop();
            }
            catch (Exception e) {
                throw new CouldNotStopConnectionFactoryWithStartStopWrapperException("Got problems stopping the"
                        + " current target ConnectionFactoryWithStartStopWrapper [" + _targetConnectionFactory + "].",
                        e);
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        log.info(LOG_PREFIX + "SmartLifeCycle.stop(callback) on [" + _beanName
                + "].");
        stop();
        callback.run();
    }
}