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

package io.mats3.impl.jms;

import java.io.BufferedInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import jakarta.jms.MessageConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsEndpoint.ProcessSingleLambda;
import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.MatsInitiator.KeepTrace;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.MatsStage;
import io.mats3.MatsStage.StageConfig;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateInterceptContext;
import io.mats3.api.intercept.MatsLoggingInterceptor;
import io.mats3.api.intercept.MatsMetricsInterceptor;
import io.mats3.api.intercept.MatsStageInterceptor;
import io.mats3.api.intercept.MatsStageInterceptor.StageInterceptContext;
import io.mats3.impl.jms.JmsMatsInitiator.MatsInitiator_DefaultInitiator_TxRequired;
import io.mats3.impl.jms.JmsMatsInitiator.MatsInitiator_NamedInitiator_TxRequiresNew;
import io.mats3.impl.jms.JmsMatsProcessContext.DoAfterCommitRunnableHolder;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsTrace;

public class JmsMatsFactory implements JmsMatsStatics, JmsMatsStartStoppable, MatsFactory {

    private static final String MATS_IMPLEMENTATION_NAME = "JMS Mats3";
    private static final String MATS_IMPLEMENTATION_VERSION = "B-2.0.0.B0+2025-10-22";

    public static final String INTERCEPTOR_CLASS_MATS_LOGGING = "io.mats3.intercept.logging.MatsMetricsLoggingInterceptor";
    public static final String INTERCEPTOR_CLASS_MATS_METRICS = "io.mats3.intercept.micrometer.MatsMicrometerInterceptor";

    private static final Logger log = LoggerFactory.getLogger(JmsMatsFactory.class);

    static final String DEFAULT_INITIATOR_NAME = "default";

    public static  JmsMatsFactory createMatsFactory_JmsOnlyTransactions(String appName, String appVersion,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler,
            MatsSerializer matsSerializer) {
        return createMatsFactory(appName, appVersion, jmsMatsJmsSessionHandler,
                JmsMatsTransactionManager_Jms.create(), matsSerializer);
    }

    public static  JmsMatsFactory createMatsFactory_JmsAndJdbcTransactions(String appName, String appVersion,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler, DataSource dataSource,
            MatsSerializer matsSerializer) {
        return createMatsFactory(appName, appVersion, jmsMatsJmsSessionHandler,
                JmsMatsTransactionManager_JmsAndJdbc.create(dataSource), matsSerializer);
    }

    public static  JmsMatsFactory createMatsFactory(String appName, String appVersion,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler,
            JmsMatsTransactionManager jmsMatsTransactionManager,
            MatsSerializer matsSerializer) {
        return new JmsMatsFactory(appName, appVersion, jmsMatsJmsSessionHandler, jmsMatsTransactionManager,
                matsSerializer);
    }

    private static final Class<?> _matsLoggingInterceptor;
    private static final Class<?> _matsMetricsInterceptor;

    static {
        // Initialize any Specifics for the MessageBrokers that are on the classpath (i.e. if ActiveMQ is there..)
        JmsMatsMessageBrokerSpecifics.init();
        // Load the MatsMetricsLoggingInterceptor class if we have it on classpath
        Class<?> matsLoggingInterceptor = null;
        try {
            matsLoggingInterceptor = Class.forName(INTERCEPTOR_CLASS_MATS_LOGGING);
        }
        catch (ClassNotFoundException e) {
            log.info(LOG_PREFIX + "Did NOT find '" + INTERCEPTOR_CLASS_MATS_LOGGING + "' on classpath, won't install.");
        }
        _matsLoggingInterceptor = matsLoggingInterceptor;
        Class<?> matsMetricsInterceptor = null;
        try {
            matsMetricsInterceptor = Class.forName(INTERCEPTOR_CLASS_MATS_METRICS);
        }
        catch (ClassNotFoundException e) {
            log.info(LOG_PREFIX + "Did NOT find '" + INTERCEPTOR_CLASS_MATS_METRICS + "' on classpath, won't install.");
        }
        _matsMetricsInterceptor = matsMetricsInterceptor;
    }

    private final String _appName;
    private final String _appVersion;
    private final JmsMatsJmsSessionHandler _jmsMatsJmsSessionHandler;
    private final JmsMatsTransactionManager _jmsMatsTransactionManager;
    private final MatsSerializer _matsSerializer;
    private final JmsMatsFactoryConfig _factoryConfig;

    private JmsMatsFactory(String appName, String appVersion,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler,
            JmsMatsTransactionManager jmsMatsTransactionManager,
            MatsSerializer matsSerializer) {
        try {
            Field callback = ContextLocal.class.getDeclaredField("callback");
            callback.setAccessible(true);
            synchronized (ContextLocal.class) {
                Object o = callback.get(null);
                if (o == null) {
                    callback.set(null, new JmsMatsContextLocalCallback());
                }
            }
        }
        catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError("Could not access the MatsFactory.ContextLocal.callback field.", e);
        }

        if (appName == null) {
            throw new NullPointerException("appName");
        }
        if (appVersion == null) {
            throw new NullPointerException("appVersion");
        }
        if (jmsMatsJmsSessionHandler == null) {
            throw new NullPointerException("jmsMatsJmsSessionHandler");
        }
        if (jmsMatsTransactionManager == null) {
            throw new NullPointerException("jmsMatsTransactionManager");
        }
        if (matsSerializer == null) {
            throw new NullPointerException("matsSerializer");
        }

        _appName = appName;
        _appVersion = appVersion;
        _jmsMatsJmsSessionHandler = jmsMatsJmsSessionHandler;
        _jmsMatsTransactionManager = jmsMatsTransactionManager;
        _matsSerializer = matsSerializer;
        _factoryConfig = new JmsMatsFactoryConfig();

        installIfPresent(_matsLoggingInterceptor);
        installIfPresent(_matsMetricsInterceptor);

        log.info(LOG_PREFIX + "Created [" + idThis() + "],"
                + " Mats:[" + MATS_IMPLEMENTATION_NAME + ",v" + MATS_IMPLEMENTATION_VERSION + "],"
                + " App:[" + _appName + ",v" + _appVersion + "]");
    }

    // :: Configurable variables:

    // From JmsMatsFactory "proprietary" config

    // DLQ handling: Set to default 6. Note: 0 means "not using MatsManagedDlqDivert"
    private int _numberOfDeliveryAttemptsBeforeMatsManagedDlqDivert = 6;

    // MatsRefuseException handling by Mats: Set to default true.
    private boolean _useMatsManagedDlqDivertOnMatsRefuseException = true;

    // Where to DLQ: Set to default: Prefix original queue name with "DLQ.", remember the MatsDestinationPrefix.
    private Function<MatsStage<?, ?, ?>, String> _destinationNameModifierForDlqs = (matsStage) -> "DLQ."
            + matsStage.getParentEndpoint().getParentFactory().getFactoryConfig().getMatsDestinationPrefix()
            + matsStage.getStageConfig().getStageId();

    // Set to default, which is COMPACT
    private KeepTrace _defaultKeepTrace = KeepTrace.COMPACT;

    // From API Config

    // Set to default, which is null.
    private Function<String, String> _initiateTraceIdModifier = null;

    // Set to default, which is 0 - which means default logic; min(10, numCpus x 2)
    private int _concurrency = 0;
    private int _interactiveConcurrency = 0;

    // Set to default, which is empty string (not null).
    private String _name = "";

    // Set to default, which is "mats.".
    private String _matsDestinationPrefix = "mats.";

    // Set to default, which is "mats:trace".
    private String _matsTraceKey = "mats:trace";

    // Set to default, which is what returned from command 'hostname', failing that, InetAddress..getHostName().
    private String _nodename = getHostname_internal();

    // Default is null, which means to use the AppName
    private String _commonEndpointGroupId;

    // Set to default, which is false. Volatile since quite probably set by differing threads.
    private volatile boolean _holdEndpointsUntilFactoryIsStarted = false;

    // :: Internal state

    // ALL *modifications* to state shall take this sync object. All read can be done without sync.
    // Note that Endpoints' Stages' StageProcessors will not ever need sync while working.
    private final Object _stateLockObject = new Object();
    // State of Plugin - started or not. SYNCHED on _stateLockObject.
    private final IdentityHashMap<MatsPlugin, Boolean> _pluginsStarted = new IdentityHashMap<>();

    // ALL these are COWAL, so that we can iterate over them without sync - but modifications are with sync on above.

    private final CopyOnWriteArrayList<MatsPlugin> _plugins = new CopyOnWriteArrayList<>();
    // These are "filter views" of the plugins list, so that we don't have to iterate over all plugins for each
    // initiation and stage processing.
    private final CopyOnWriteArrayList<MatsInitiateInterceptor> _initiationInterceptors = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MatsStageInterceptor> _stageInterceptors = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<JmsMatsEndpoint<?, ?>> _createdEndpoints = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MatsInitiator> _createdInitiators = new CopyOnWriteArrayList<>();

    // =========== End of fields

    // =========== Implementation-specific config setters

    /**
     * Sets the number of delivery attempts before MatsFactory "manually" moves a message over to the DLQ. Zero means
     * unlimited, i.e. that the broker must handle it. The default is <code>6</code>: If
     * 'message.getIntProperty("JMSXDeliveryCount")' &ge; 6, then move to DLQ - using the
     * {@link #setDestinationNameModifierForDlqs(Function)} to modify the destination name to the DLQ.
     *
     * @param numberOfDeliveryAttemptsBeforeDlq
     *            the number of delivery attempts before MatsFactory "manually" moves a message over to the DLQ. Zero
     *            means unlimited, i.e. that the broker will handle it.
     */
    public void setMatsManagedDlqDivert(int numberOfDeliveryAttemptsBeforeDlq) {
        if (numberOfDeliveryAttemptsBeforeDlq < 0) {
            throw new IllegalArgumentException("numberOfDeliveryAttemptsBeforeDlq must be >= 0,"
                    + " was [" + numberOfDeliveryAttemptsBeforeDlq + "]");
        }
        if (numberOfDeliveryAttemptsBeforeDlq > 0) {
            log.info(LOG_PREFIX + "Enabling MatsManagedDlqDivert, with max delivery attempts to ["
                    + numberOfDeliveryAttemptsBeforeDlq + "].");
        }
        else {
            log.info(LOG_PREFIX + "Disabling MatsManagedDlqDivert - letting the broker handle DLQing.");
        }
        _numberOfDeliveryAttemptsBeforeMatsManagedDlqDivert = numberOfDeliveryAttemptsBeforeDlq;
    }

    /**
     * Sets whether to use Mats Controlled DLQ Routing for MatsRefuseException, i.e. that MatsFactory "manually" move a
     * message over to the DLQ if the user lambda throws a MatsRefuseException. The default is <code>true</code>. The
     * {@link #setDestinationNameModifierForDlqs(Function)} is used to modify the destination name to the DLQ.
     */
    public void setMatsManagedDlqDivertOnMatsRefuseException(
            boolean useMatsManagedDlqDivertOnMatsRefuseException) {
        _useMatsManagedDlqDivertOnMatsRefuseException = useMatsManagedDlqDivertOnMatsRefuseException;
        if (useMatsManagedDlqDivertOnMatsRefuseException) {
            log.info(LOG_PREFIX + "Enabling MatsManagedDlqDivertOnMatsRefuseException.");
        }
        else {
            log.info(LOG_PREFIX + "Disabling MatsManagedDlqDivertOnMatsRefuseException.");
        }
    }

    /**
     * Sets the {@link Function} that is used to modify the destination name to the DLQ. The default is to prefix the
     * original destination name with "DLQ.". The {@link Function} is invoked with the MatsStage in question, and should
     * return the DLQ destination name. The method can return a constant to always use the same DLQ: It might be of
     * interest to direct dead letters to the same address that is the default global DLQ for the broker in use; E.g.
     * Apache ActiveMQ Classic has default global DLQ: "ActiveMQ.DLQ", and Apache ActiveMQ Artemis has "DLQ". However,
     * this is very much not recommended - individual dead letter queues should be used for each MatsStage.
     *
     * @param destinationNameModifierForDlqs
     *            the {@link Function} that is used to modify the destination name to the DLQ. The default is to prefix
     *            the original destination name with "DLQ.".
     */
    public void setDestinationNameModifierForDlqs(
            Function<MatsStage<?, ?, ?>, String> destinationNameModifierForDlqs) {
        if (destinationNameModifierForDlqs == null) {
            throw new NullPointerException("destinationNameModifierForDlqs");
        }
        log.info("Setting DestinationNameModifierForDlqs Function to [" + destinationNameModifierForDlqs + "].");
        _destinationNameModifierForDlqs = destinationNameModifierForDlqs;
    }

    public void useSeparateQueueForInteractiveNonPersistent(boolean separate) {
        /* no-op - not yet implemented */
    }

    /**
     * Sets the default KeepTrace if the initiation doesn't set one itself. The default for this default is
     * {@link KeepTrace#COMPACT}. Not yet moved to FactoryConfig, because I want to evaluate.
     * <p/>
     * Must be set before the MatsFactory is "published", memory wise.
     *
     * @param defaultKeepTrace
     *            the default KeepTrace for Mats flows - the default for this default is {@link KeepTrace#COMPACT}.
     */
    public void setDefaultKeepTrace(KeepTrace defaultKeepTrace) {
        _defaultKeepTrace = defaultKeepTrace;
    }

    // --- getters

    int getNumberOfDeliveryAttemptsBeforeMatsManagedDlqDivert() {
        return _numberOfDeliveryAttemptsBeforeMatsManagedDlqDivert;
    }

    boolean isMatsManagedDlqDivertOnMatsRefuseException() {
        return _useMatsManagedDlqDivertOnMatsRefuseException;
    }

    Function<MatsStage<?, ?, ?>, String> getDestinationNameModifierForDlqs() {
        return _destinationNameModifierForDlqs;
    }

    // =========== Implementation

    Function<String, String> getInitiateTraceIdModifier() {
        return _initiateTraceIdModifier;
    }

    private void installIfPresent(Class<?> standardInterceptorClass) {
        if (standardInterceptorClass != null) {
            log.info(LOG_PREFIX + "Found '" + standardInterceptorClass.getSimpleName()
                    + "' on classpath, installing for [" + idThis() + "].");
            try {
                Method install = standardInterceptorClass.getDeclaredMethod("install", MatsFactory.class);
                install.invoke(null, this);
            }
            catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                log.warn(LOG_PREFIX + "Got problems installing '" + standardInterceptorClass.getSimpleName() + "'.", e);
            }
        }
    }

    private static String getHostname_internal() {
        try (BufferedInputStream in = new BufferedInputStream(Runtime.getRuntime()
                .exec(new String[] { "hostname" })
                .getInputStream())) {
            byte[] b = new byte[256];
            int readBytes = in.read(b, 0, b.length);
            // Using platform default charset, which probably is exactly what we want in this one specific case.
            return new String(b, 0, readBytes).trim();
        }
        catch (Throwable t) {
            try {
                return InetAddress.getLocalHost().getHostName();
            }
            catch (UnknownHostException e) {
                return "_cannot_find_hostname_";
            }
        }
    }

    KeepTrace getDefaultKeepTrace() {
        return _defaultKeepTrace;
    }

    public JmsMatsJmsSessionHandler getJmsMatsJmsSessionHandler() {
        return _jmsMatsJmsSessionHandler;
    }

    public JmsMatsTransactionManager getJmsMatsTransactionManager() {
        return _jmsMatsTransactionManager;
    }

    public MatsSerializer getMatsSerializer() {
        return _matsSerializer;
    }

    private void installPlugin(MatsPlugin plugin) {
        if (plugin == null) {
            throw new NullPointerException("plugin");
        }
        synchronized (_stateLockObject) {
            // :: Assert that we don't add the same instance of the plugin twice.
            for (MatsPlugin existing : _plugins) {
                if (existing == plugin) {
                    throw new IllegalStateException("Cannot add plugin twice: [" + plugin + "]");
                }
            }

            // :: FIRST start the plugin, THEN add it to the MatsFactory.

            // :: Start the plugin (done within sync block to keep MatsFactory's state consistent)
            // NOTE: We let any Exceptions propagate out, both so that we do not add this plugin, and so that the
            // application can fail to start if a plugin fails to start.
            plugin.start(this);

            // ----- It started OK, so we can add it to the list of plugins.

            // :: Special cases: MatsLoggingInterceptor and MatsMetricsInterceptor: Remove existing if adding new.

            // ?: Is this a MatsLoggingInterceptor?
            if (plugin instanceof MatsLoggingInterceptor) {
                // -> Yes, MatsLoggingInterceptor: Remove any existing before adding this new.
                removeInterceptorType(_plugins, MatsLoggingInterceptor.class, true);
                removeInterceptorType(_pluginsStarted.keySet(), MatsLoggingInterceptor.class, false);
                removeInterceptorType(_initiationInterceptors, MatsLoggingInterceptor.class, false);
                removeInterceptorType(_stageInterceptors, MatsLoggingInterceptor.class, false);
            }

            // ?: Is this a MatsMetricsInterceptor?
            if (plugin instanceof MatsMetricsInterceptor) {
                // -> Yes, MatsMetricsInterceptor: Remove any existing before adding this new.
                removeInterceptorType(_plugins, MatsMetricsInterceptor.class, true);
                removeInterceptorType(_pluginsStarted.keySet(), MatsMetricsInterceptor.class, false);
                removeInterceptorType(_initiationInterceptors, MatsMetricsInterceptor.class, false);
                removeInterceptorType(_stageInterceptors, MatsMetricsInterceptor.class, false);
            }

            // :: "Cache" a filtered view of init and stage interceptors, used by init and stage processors.

            if (plugin instanceof MatsInitiateInterceptor) {
                _initiationInterceptors.add((MatsInitiateInterceptor) plugin);
            }
            if (plugin instanceof MatsStageInterceptor) {
                _stageInterceptors.add((MatsStageInterceptor) plugin);
            }

            // :: Actually add the plugin to the list of plugins.
            _plugins.add(plugin);

            // :: Mark the plugin as started.
            _pluginsStarted.put(plugin, true);
        }
    }

    private <T extends MatsPlugin> List<T> getPlugins(Class<T> filterByClass) {
        if (filterByClass == null) {
            throw new NullPointerException("filterByClass");
        }
        List<T> ret = new ArrayList<>();
        synchronized (_stateLockObject) {
            for (Object plugin : _plugins) {
                if (filterByClass.isInstance(plugin)) {
                    T t = filterByClass.cast(plugin);
                    ret.add(t);
                }
            }
        }
        return ret;
    }

    private boolean removePlugin(MatsPlugin plugin) {
        if (plugin == null) {
            throw new NullPointerException("plugin");
        }
        boolean wasPresent;
        synchronized (_stateLockObject) {
            // :: Remove the plugin from the started set
            _pluginsStarted.remove(plugin);
            // :: Remove from "filter lists" if relevant interface
            if (plugin instanceof MatsInitiateInterceptor) {
                _initiationInterceptors.remove(plugin);
            }
            if (plugin instanceof MatsStageInterceptor) {
                _stageInterceptors.remove(plugin);
            }
            // :: Remove from the actual list of plugins
            wasPresent = _plugins.remove(plugin);
            // Stop it within sync
            if (wasPresent) {
                stopSinglePluginAfterRemoved(plugin);
            }
        }
        return wasPresent;
    }

    private <I extends MatsPlugin> void removeInterceptorType(Collection<I> interceptors, Class<?> typeToRemove,
                                                              boolean stopPlugin) {
        if (!Thread.holdsLock(_stateLockObject)) {
            throw new AssertionError("Should have held lock on '_stateLockObject'.");
        }
        Iterator<I> it = interceptors.iterator();
        while (it.hasNext()) {
            I next = it.next();
            // ?: Is this existing interceptor of a type to remove?
            if (typeToRemove.isInstance(next)) {
                // -> Yes, so remove it, and stop if asked.
                log.info(LOG_PREFIX + ".. removing existing: [" + next + "], since adding new "
                        + typeToRemove.getSimpleName());
                it.remove();
                if (stopPlugin) {
                    stopSinglePluginAfterRemoved(next);
                }
            }
        }
    }

    private void stopSinglePluginAfterRemoved(MatsPlugin plugin) {
        if (!Thread.holdsLock(_stateLockObject)) {
            throw new AssertionError("Should have held lock on '_stateLockObject'.");
        }
        try {
            plugin.preStop();
            plugin.stop();
        }
        catch (Throwable t) {
            log.warn(LOG_PREFIX + "Got problems when closing plugin [" + plugin + "].", t);
        }
    }

    List<MatsInitiateInterceptor> getInterceptorsForInitiation(InitiateInterceptContext context) {
        return _initiationInterceptors;
    }

    List<MatsStageInterceptor> getInterceptorsForStage(StageInterceptContext context) {
        return _stageInterceptors;
    }

    @Override
    public FactoryConfig getFactoryConfig() {
        return _factoryConfig;
    }

    @Override
    public <R, S> JmsMatsEndpoint<R, S> staged(String endpointId, Class<R> replyClass, Class<S> stateClass) {
        return staged(endpointId, replyClass, stateClass, NO_CONFIG);
    }

    @Override
    public <R, S> JmsMatsEndpoint<R, S> staged(String endpointId, Class<R> replyClass, Class<S> stateClass,
            Consumer<? super EndpointConfig<R, S>> endpointConfigLambda) {
        JmsMatsEndpoint<R, S> endpoint = new JmsMatsEndpoint<>(this, endpointId, true, stateClass, replyClass);
        endpoint.getEndpointConfig().setOrigin(getInvocationPoint());
        validateNewEndpointAtCreation(endpoint);
        endpointConfigLambda.accept(endpoint.getEndpointConfig());
        return endpoint;
    }

    @Override
    public <R, I> MatsEndpoint<R, Void> single(String endpointId,
            Class<R> replyClass, Class<I> incomingClass,
            ProcessSingleLambda<R, I> processor) {
        return single(endpointId, replyClass, incomingClass, NO_CONFIG, NO_CONFIG, processor);
    }

    @Override
    public <R, I> JmsMatsEndpoint<R, Void> single(String endpointId,
            Class<R> replyClass, Class<I> incomingClass,
            Consumer<? super EndpointConfig<R, Void>> endpointConfigLambda,
            Consumer<? super StageConfig<R, Void, I>> stageConfigLambda,
            ProcessSingleLambda<R, I> processor) {
        // Get a normal Staged Endpoint
        JmsMatsEndpoint<R, Void> endpoint = staged(endpointId, replyClass, Void.TYPE, endpointConfigLambda);
        // :: Wrap the ProcessSingleLambda in a single lastStage-ProcessReturnLambda
        endpoint.lastStage(incomingClass, stageConfigLambda,
                (processContext, state, incomingDto) -> {
                    // This is just a direct forward - albeit a single stage has no state.
                    return processor.process(processContext, incomingDto);
                });
        return endpoint;
    }

    @Override
    public <S, I> JmsMatsEndpoint<Void, S> terminator(String endpointId,
            Class<S> stateClass, Class<I> incomingClass,
            ProcessTerminatorLambda<S, I> processor) {
        return terminator(true, endpointId, incomingClass, stateClass, NO_CONFIG, NO_CONFIG, processor);
    }

    @Override
    public <S, I> JmsMatsEndpoint<Void, S> terminator(String endpointId,
            Class<S> stateClass, Class<I> incomingClass,
            Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
            Consumer<? super StageConfig<Void, S, I>> stageConfigLambda,
            ProcessTerminatorLambda<S, I> processor) {
        return terminator(true, endpointId, incomingClass, stateClass, endpointConfigLambda, stageConfigLambda,
                processor);
    }

    @Override
    public <S, I> JmsMatsEndpoint<Void, S> subscriptionTerminator(String endpointId, Class<S> stateClass,
            Class<I> incomingClass,
            ProcessTerminatorLambda<S, I> processor) {
        return terminator(false, endpointId, incomingClass, stateClass, NO_CONFIG, NO_CONFIG, processor);
    }

    @Override
    public <S, I> JmsMatsEndpoint<Void, S> subscriptionTerminator(String endpointId, Class<S> stateClass,
            Class<I> incomingClass,
            Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
            Consumer<? super StageConfig<Void, S, I>> stageConfigLambda,
            ProcessTerminatorLambda<S, I> processor) {
        return terminator(false, endpointId, incomingClass, stateClass, endpointConfigLambda, stageConfigLambda,
                processor);
    }

    /**
     * INTERNAL method, since terminator(...) and subscriptionTerminator(...) are near identical.
     */
    private <S, I> JmsMatsEndpoint<Void, S> terminator(boolean queue, String endpointId,
            Class<I> incomingClass,
            Class<S> stateClass,
            Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
            Consumer<? super StageConfig<Void, S, I>> stageConfigLambda,
            ProcessTerminatorLambda<S, I> processor) {
        // Need to create the JmsMatsEndpoint ourselves, since we need to set the queue-parameter.
        JmsMatsEndpoint<Void, S> endpoint = new JmsMatsEndpoint<>(this, endpointId, queue, stateClass,
                Void.TYPE);
        endpoint.getEndpointConfig().setOrigin(getInvocationPoint());
        validateNewEndpointAtCreation(endpoint);
        endpointConfigLambda.accept(endpoint.getEndpointConfig());
        // :: Wrap the ProcessTerminatorLambda in a single stage that does not return.
        // This is just a direct forward, w/o any return value.
        endpoint.stage(incomingClass, stageConfigLambda, processor::process);
        endpoint.finishSetup();
        return endpoint;
    }

    private final ThreadLocal<Supplier<MatsInitiate>> __existingMatsInitiate = new ThreadLocal<>();
    private final ThreadLocal<NestingWithinStageProcessing> __nestingWithinStageProcessing = new ThreadLocal<>();

    static class NestingWithinStageProcessing {
        private final MatsTrace _matsTrace;
        private final JmsMatsStage<?, ?, ?> _currentStage;
        private final MessageConsumer _messageConsumer;

        public NestingWithinStageProcessing(MatsTrace matsTrace, JmsMatsStage<?, ?, ?> currentStage,
                MessageConsumer messageConsumer) {
            _matsTrace = matsTrace;
            _currentStage = currentStage;
            _messageConsumer = messageConsumer;
        }

        public MatsTrace getMatsTrace() {
            return _matsTrace;
        }

        public MessageConsumer getMessageConsumer() {
            return _messageConsumer;
        }

        public JmsMatsStage<?, ?, ?> getCurrentStage() {
            return _currentStage;
        }
    }

    /**
     *
     * NESTING of Mats Initiations: When we're within an existing Mats TX Demarcation, both within Initiate and Stage,
     * any new initiations using DefaultInitiator should execute within the existing demarcation - while any new
     * initiations using non-default Initiator (gotten using 'getOrCreateInitiator') should "fork out" of the existing
     * demarcation.
     * <ul>
     * <li>If this exists, and DefaultInitiator ('getDefaultInitiator'): Use the existing (supplied) MatsInitiate. If
     * not exists: Normal initiate.</li>
     * <li>If this exists, and non-default Initiator ('getOrCreateInitiator'): Fork out in thread, to get own context
     * (i.e. ignore the existing MatsInitiate, fork out of it). If not exists: Normal initiate.</li>
     * </ul>
     * Note: This ThreadLocal is on the MatsFactory, thus the {@link MatsInitiate} is scoped to the MatsFactory. This
     * should make some sense: If you in a Stage do a new Initiation, even with the
     * {@link MatsFactory#getDefaultInitiator()}, if this is <i>on a different MatsFactory</i>, then the transaction
     * should not be hoisted.
     */
    void setCurrentMatsFactoryThreadLocal_ExistingMatsInitiate(Supplier<MatsInitiate> matsInitiateSupplier) {
        __existingMatsInitiate.set(matsInitiateSupplier);
    }

    /**
     * Clear out.
     */
    void clearCurrentMatsFactoryThreadLocal_ExistingMatsInitiate() {
        __existingMatsInitiate.remove();
    }

    /**
     * CURRENT NESTING HIERARCHY STARTS WITHIN A STAGE PROCESSING: When doing nested initiations from within a Stage,
     * the Stage context should follow along just like if using context.initiate(..).
     * <ul>
     * <li>If this exists (i.e. current nesting hierarchy starts from within a stage processing), then use
     * {@link JmsMatsInitiate#createForChildFlow(JmsMatsFactory, List, JmsMatsInternalExecutionContext, DoAfterCommitRunnableHolder, MatsTrace, String)}</li>
     * <li>If this does not exists (i.e. current nesting hierarchy starts from "outside"), then use
     * {@link JmsMatsInitiate#createForTrueInitiation(JmsMatsFactory, List, JmsMatsInternalExecutionContext, DoAfterCommitRunnableHolder, String)}</li>
     * </ul>
     * <b>Notice: When forking out in new thread, make sure to copy over this ThreadLocal!</b>
     * <p/>
     * Notice: This concept is NOT overlapping with "existing mats initiate": The properties from current stage
     * processing shall be moved over to the new initiation EVEN IF this is done with a non-default initiator. The
     * "hoisting" of an initiation transactions into an existing transaction is orthogonal to whether the processing is
     * within a stage or "outside".
     */
    void setCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing(MatsTrace matsTrace,
            JmsMatsStage<?, ?, ?> currentStage,
            MessageConsumer messageConsumer) {
        __nestingWithinStageProcessing.set(new NestingWithinStageProcessing(matsTrace, currentStage,
                messageConsumer));
    }

    /**
     * Used to move a {@link NestingWithinStageProcessing} from an existing thread to a "context fork out" thread.
     */
    void setCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing(NestingWithinStageProcessing existing) {
        __nestingWithinStageProcessing.set(existing);
    }

    /**
     * Clear out.
     */
    void clearCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing() {
        __nestingWithinStageProcessing.remove();
    }

    Optional<Supplier<MatsInitiate>> getCurrentMatsFactoryThreadLocal_ExistingMatsInitiate() {
        return Optional.ofNullable(__existingMatsInitiate.get());
    }

    Optional<NestingWithinStageProcessing> getCurrentMatsFactoryThreadLocal_NestingWithinStageProcessing() {
        return Optional.ofNullable(__nestingWithinStageProcessing.get());
    }

    private volatile MatsInitiator _defaultMatsInitiator;

    @Override
    public MatsInitiator getDefaultInitiator() {
        // :: Fast-path for the default initiator (name="default"), which resides in its own field.
        if (_defaultMatsInitiator == null) {
            // Note: getOrCreateInitiator_internal(..) will sync, and check whether it has been created.
            // We can ignore any races, as the initiator will be the same.
            _defaultMatsInitiator = getOrCreateInitiator_internal(DEFAULT_INITIATOR_NAME);
        }
        // Wrap it in a MatsInitiator that either joins existing transaction, or starts new.
        return new MatsInitiator_DefaultInitiator_TxRequired(this, _defaultMatsInitiator);
    }

    @Override
    public MatsInitiator getOrCreateInitiator(String name) {
        // Wrap it in a MatsInitiator that always starts new transaction.
        return new MatsInitiator_NamedInitiator_TxRequiresNew(this, getOrCreateInitiator_internal(name));
    }

    public MatsInitiator getOrCreateInitiator_internal(String name) {
        // "Double checked locking" part 1: First check if it is there (the list is a COWAL)
        for (MatsInitiator init : _createdInitiators) {
            if (init.getName().equals(name)) {
                return init;
            }
        }

        // E-> Not found, now sync and check again
        synchronized (_stateLockObject) {
            // "Double checked locking" part 2: Check again, now that we're synched.
            for (MatsInitiator init : _createdInitiators) {
                if (init.getName().equals(name)) {
                    return init;
                }
            }
            // E-> Not found, make new
            MatsInitiator initiator = new JmsMatsInitiator(name, this,
                    _jmsMatsJmsSessionHandler, _jmsMatsTransactionManager);

            // First notify all plugins that we're about to add a new Initiator.
            for (MatsPlugin plugin : _plugins) {
                try {
                    plugin.addingInitiator(initiator);
                }
                catch (Throwable t) {
                    log.error(LOG_PREFIX + "Got problems when notifying plugin [" + plugin + "] that we're adding"
                            + " a new initiator [" + initiator + "]. Ignoring, but this is probably bad.", t);
                }
            }

            // .. then add it
            _createdInitiators.add(initiator);
            return initiator;
        }
    }

    private void validateNewEndpointAtCreation(JmsMatsEndpoint<?, ?> newEndpoint) {
        // :: Assert that it is possible to instantiate the State and Reply classes.
        assertOkToInstantiateClass(newEndpoint.getEndpointConfig().getStateClass(), "State 'STO' Class",
                "Endpoint " + newEndpoint.getEndpointId());
        assertOkToInstantiateClass(newEndpoint.getEndpointConfig().getReplyClass(), "Reply DTO Class",
                "Endpoint " + newEndpoint.getEndpointId());

        // :: Early check that we do not have the endpoint already.
        // (We also check at actual insertion (finishSetup()), to handle concurrent creation.)
        Optional<MatsEndpoint<?, ?>> existingEndpoint = getEndpoint(newEndpoint.getEndpointConfig()
                .getEndpointId());
        if (existingEndpoint.isPresent()) {
            throw new IllegalStateException("An Endpoint with endpointId='"
                    + newEndpoint.getEndpointConfig().getEndpointId()
                    + "' was already present. Existing: [" + existingEndpoint.get()
                    + "], attempted registered:[" + newEndpoint + "].");
        }
    }

    /**
     * Invoked by the endpoint upon {@link MatsEndpoint#finishSetup()}.
     */
    void addNewEndpointToFactory(JmsMatsEndpoint<?, ?> newEndpoint) {
        // :: We've already checked that we didn't have the endpoint when it was created.
        // Now we'll go into sync for the actual insertion, to handle concurrent creation.
        synchronized (_stateLockObject) {
            // :: Check again that we do not have the endpoint already
            Optional<MatsEndpoint<?, ?>> existingEndpoint = getEndpoint(newEndpoint.getEndpointConfig()
                    .getEndpointId());
            if (existingEndpoint.isPresent()) {
                throw new IllegalStateException("An Endpoint with endpointId='"
                        + newEndpoint.getEndpointConfig().getEndpointId()
                        + "' was already present. Existing: [" + existingEndpoint.get()
                        + "], attempted registered:[" + newEndpoint + "].");
            }
            // First notify all plugins that we're about to add a new Endpoint.
            for (MatsPlugin plugin : _plugins) {
                try {
                    plugin.addingEndpoint(newEndpoint);
                }
                catch (Throwable t) {
                    log.error(LOG_PREFIX + "Got problems when notifying plugin [" + plugin + "] that we're adding"
                            + " a new Endpoint [" + newEndpoint + "]. Ignoring, but this is probably bad.", t);
                }
            }
            // .. then add it
            _createdEndpoints.add(newEndpoint);
        }
    }

    void removeEndpoint(JmsMatsEndpoint<?, ?> endpointToRemove) {
        synchronized (_stateLockObject) {
            Optional<MatsEndpoint<?, ?>> existingEndpoint = getEndpoint(endpointToRemove.getEndpointConfig()
                    .getEndpointId());
            if (existingEndpoint.isEmpty()) {
                throw new IllegalStateException("When trying to remove the endpoint [" + endpointToRemove + "], it was"
                        + " not present in the MatsFactory! EndpointId:[" + endpointToRemove.getEndpointId() + "]");
            }

            // First remove the endpoint
            _createdEndpoints.remove(endpointToRemove);

            // .. then notify all plugins that we've removed an Endpoint.
            for (MatsPlugin plugin : _plugins) {
                try {
                    plugin.removedEndpoint(endpointToRemove);
                }
                catch (Throwable t) {
                    log.error(LOG_PREFIX + "Got problems when notifying plugin [" + plugin + "] that we've removed"
                            + " Endpoint [" + endpointToRemove + "]. Ignoring, but this is probably bad.", t);
                }
            }
        }
    }

    void assertOkToInstantiateClass(Class<?> clazz, String what, String whatInstance) {
        // ?: Void is allowed to "instantiate" - as all places where this is attempted, 'null' will be used instead.
        if (clazz == Void.TYPE) {
            return;
        }
        if (clazz == Void.class) {
            return;
        }
        try {
            _matsSerializer.newInstance(clazz);
        }
        catch (Throwable t) {
            throw new CannotInstantiateClassException("Got problem when using current MatsSerializer to test"
                    + " instantiate [" + what + "] class [" + clazz + "] of [" + whatInstance + "]. MatsSerializer: ["
                    + _matsSerializer + "].", t);
        }
    }

    public static class CannotInstantiateClassException extends RuntimeException {
        public CannotInstantiateClassException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public List<MatsEndpoint<?, ?>> getEndpoints() {
        // Can copy without sync, as the list is a COWAL.
        ArrayList<MatsEndpoint<?, ?>> matsEndpoints = new ArrayList<>(_createdEndpoints);

        // :: Make it into a nice list, where the endpoints are sorted by endpointId, and private are at the end.
        // 1. Sort all
        matsEndpoints.sort(Comparator.comparing(ep -> ep.getEndpointConfig().getEndpointId()));
        // 2. split out private Endpoints, by iterating over all, moving over the private to a new list
        ArrayList<MatsEndpoint<?, ?>> privateEndpoints = new ArrayList<>();
        for (Iterator<MatsEndpoint<?, ?>> iterator = matsEndpoints.iterator(); iterator.hasNext();) {
            MatsEndpoint<?, ?> next = iterator.next();
            if (next.getEndpointConfig().getEndpointId().contains(".private.")) {
                privateEndpoints.add(next);
                iterator.remove();
            }
        }
        // 3. then add the private Endpoints last
        matsEndpoints.addAll(privateEndpoints);
        return matsEndpoints;
    }

    @Override
    public Optional<MatsEndpoint<?, ?>> getEndpoint(String endpointId) {
        // Can iterate without sync, as the list is a COWAL.
        for (MatsEndpoint<?, ?> endpoint : _createdEndpoints) {
            if (endpoint.getEndpointConfig().getEndpointId().equals(endpointId)) {
                return Optional.of(endpoint);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<MatsInitiator> getInitiators() {
        // Can copy without sync, as the list is a COWAL.
        ArrayList<MatsInitiator> matsInitiators = new ArrayList<>(_createdInitiators);
        // :: Put "default" as first entry
        MatsInitiator defaultInitiator = null;
        for (Iterator<MatsInitiator> iter = matsInitiators.iterator(); iter.hasNext();) {
            MatsInitiator matsInitiator = iter.next();
            if ("default".equals(matsInitiator.getName())) {
                iter.remove();
                defaultInitiator = matsInitiator;
            }
        }
        matsInitiators.sort(Comparator.comparing(MatsInitiator::getName));
        if (defaultInitiator != null) {
            matsInitiators.add(0, defaultInitiator);
        }
        return matsInitiators;
    }

    @Override
    public void start() {
        log.info(LOG_PREFIX + "Starting [" + idThis() + "], thus starting all created endpoints.");
        // First setting the "hold" to false, so if any subsequent endpoints are added, they will auto-start.
        _holdEndpointsUntilFactoryIsStarted = false;

        synchronized (_stateLockObject) {
            // :: First start all already registered plugins which aren't started yet.
            for (MatsPlugin plugin : _plugins) {
                // ?: Is this plugin not started yet?
                if (_pluginsStarted.get(plugin) == null) {
                    // -> Yes, not started yet, so start it.
                    // Mark as stared
                    _pluginsStarted.put(plugin, true);
                    // Start it
                    try {
                        plugin.start(this);
                    }
                    catch (Throwable t) {
                        log.warn(LOG_PREFIX + "Got problems when invoking start() on plugin [" + plugin + "].", t);
                    }
                }
            }
        }

        // :: Now start all the already configured endpoints
        for (MatsEndpoint<?, ?> endpoint : getEndpoints()) {
            endpoint.start();
        }
        // :: Wait for them entering receiving.
        boolean startedOk = waitForReceiving(30_000);
        if (startedOk) {
            log.info(LOG_PREFIX + "Done. Started [" + idThis() + "], and all endpoints' all stages have entered their"
                    + " receive-loop.");
        }
        else {
            log.warn(LOG_PREFIX + "Done. Started [" + idThis()
                    + "], BUT not all endpoints have entered their receive-loop?!");
        }
    }

    @Override
    public void holdEndpointsUntilFactoryIsStarted() {
        log.info(LOG_PREFIX + getClass().getSimpleName() + ".holdEndpointsUntilFactoryIsStarted() invoked - will not"
                + " start any configured endpoints until .start() is explicitly invoked!");
        _holdEndpointsUntilFactoryIsStarted = true;
    }

    @Override
    public List<JmsMatsStartStoppable> getChildrenStartStoppable() {
        // Can clone with vengeance, as the list is a COWAL.
        @SuppressWarnings("unchecked")
        List<JmsMatsStartStoppable> clone = (List<JmsMatsStartStoppable>) _createdEndpoints.clone();
        return clone;
    }

    @Override
    public boolean waitForReceiving(int timeoutMillis) {
        return JmsMatsStartStoppable.super.waitForReceiving(timeoutMillis);
    }

    public boolean isHoldEndpointsUntilFactoryIsStarted() {
        return _holdEndpointsUntilFactoryIsStarted;
    }

    /**
     * Method for Spring's default lifecycle - directly invokes {@link #stop(int) stop(30_000)}.
     */
    public void close() {
        log.info(LOG_PREFIX + getClass().getSimpleName() + ".close() invoked, forwarding to stop().");
        stop(30_000);
    }

    @Override
    public boolean stop(int gracefulShutdownMillis) {
        log.info(LOG_PREFIX + "Stopping MatsFactory [" + idThis()
                + "], thus stopping/closing all plugins, and created endpoints and initiators."
                + " Graceful shutdown millis: [" + gracefulShutdownMillis + "].");

        boolean stopped = true; // Can only be pulled 'false'.
        synchronized (_stateLockObject) {
            // :: Prestop all plugins.
            for (MatsPlugin plugin : _plugins) {
                // Remove the plugin from the started-map, so that it will be restarted if matsFactory.start()
                // is invoked
                _pluginsStarted.remove(plugin);
                // preStop it within sync
                try {
                    plugin.preStop();
                }
                catch (Throwable t) {
                    stopped = false;
                    log.error(LOG_PREFIX + "Got problems when invoking preStop() on plugin [" + plugin + "].", t);
                }
            }

            // :: Stop all endpoints (very graceful stuff)
            boolean endpointsStopped = JmsMatsStartStoppable.super.stop(gracefulShutdownMillis);
            if (!endpointsStopped) {
                stopped = false;
            }

            // :: Stop all initiators.
            for (MatsInitiator initiator : getInitiators()) {
                initiator.close();
            }

            // :: Now, final stop of all the plugins
            for (MatsPlugin plugin : _plugins) {
                try {
                    plugin.stop();
                }
                catch (Throwable t) {
                    stopped = false;
                    log.warn(LOG_PREFIX + "Got problems when invoking stop() on plugin [" + plugin + "].", t);
                }
            }
        }

        if (stopped) {
            log.info(LOG_PREFIX + "Everything of [" + idThis() + "} stopped nicely. Now cleaning JMS Session pool.");
        }
        else {
            log.warn(LOG_PREFIX + "Evidently some components of [" + idThis() + "} DIT NOT stop in ["
                    + gracefulShutdownMillis + "] millis, giving up. Now cleaning JMS Session pool.");
        }

        // :: "Closing the JMS pool"; closing all available SessionHolder, which should lead to the Connections closing.
        int liveConnectionsAfter = _jmsMatsJmsSessionHandler.closeAllAvailableSessions();
        if (liveConnectionsAfter > 0) {
            log.warn(LOG_PREFIX + "We evidently didn't manage to close all JMS Sessions, and there are still ["
                    + liveConnectionsAfter + "] live JMS Connections present. This isn't ideal,"
                    + " and you should figure out why.");
        }
        return stopped && (liveConnectionsAfter == 0);
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    /**
     * @return <code>System.identityHashCode(this)</code>, as the JmsMatsFactory is a long-lived singleton.
     */
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String idThis() {
        return id("JmsMatsFactory{" + _name + "@" + _appName + ",v." + _appVersion + "}", this);
    }

    @Override
    public String toString() {
        return idThis();
    }

    private class JmsMatsFactoryConfig implements FactoryConfig {
        private final ConcurrentHashMap<String, Object> _attributes = new ConcurrentHashMap<>();

        @Override
        public FactoryConfig setName(String name) {
            if (name == null) {
                throw new NullPointerException("name");
            }
            String idBefore = idThis();
            _name = name;
            log.info(LOG_PREFIX + "Set MatsFactory name to [" + name + "]. Previous id: [" + idBefore + "]"
                    + " - new id: [" + idThis() + "].");
            return this;
        }

        @Override
        public String getName() {
            return _name;
        }

        @Override
        public int getNumberOfCpus() {
            return Integer.parseInt(System.getProperty("mats.cpus",
                    Integer.toString(Runtime.getRuntime().availableProcessors())));
        }

        @Override
        public FactoryConfig setInitiateTraceIdModifier(Function<String, String> modifier) {
            _initiateTraceIdModifier = modifier;
            return this;
        }

        @Override
        public FactoryConfig setMatsDestinationPrefix(String prefix) {
            log.info("MatsFactory's Mats Destination Prefix is set to [" + prefix + "] (was: [" + _matsDestinationPrefix
                    + "]).");
            _matsDestinationPrefix = prefix;
            return this;
        }

        @Override
        public String getMatsDestinationPrefix() {
            return _matsDestinationPrefix;
        }

        @Override
        public FactoryConfig setMatsTraceKey(String key) {
            log.info("MatsFactory's Mats Trace Key is set to [" + key + "] (was: [" + _matsTraceKey + "]).");
            _matsTraceKey = key;
            return this;
        }

        @Override
        public String getMatsTraceKey() {
            return _matsTraceKey;
        }

        @Override
        public int getConcurrency() {
            // ?: Is the concurrency set specifically?
            if (_concurrency != 0) {
                // -> Yes, set specifically, so return it.
                return _concurrency;
            }
            // E-> No, not specific concurrency, so return default calculation per API.
            return Math.min(10, getNumberOfCpus() * 2);
        }

        @Override
        public FactoryConfig setConcurrency(int concurrency) {
            setConcurrencyWithLog(log, "MatsFactory Concurrency",
                    this::getConcurrency,
                    this::isConcurrencyDefault,
                    (Integer i) -> {
                        _concurrency = i;
                    },
                    concurrency);
            return this;
        }

        @Override
        public boolean isConcurrencyDefault() {
            return _concurrency == 0;
        }

        @Override
        public int getInteractiveConcurrency() {
            // ?: Is the interactiveConcurrency set specifically?
            if (_interactiveConcurrency != 0) {
                // -> Yes, set specifically, so return it.
                return _interactiveConcurrency;
            }
            // E-> No, so return the factory's concurrency.
            return getConcurrency();
        }

        @Override
        public FactoryConfig setInteractiveConcurrency(int concurrency) {
            setConcurrencyWithLog(log, "MatsFactory Interactive Concurrency",
                    this::getInteractiveConcurrency,
                    this::isInteractiveConcurrencyDefault,
                    (Integer i) -> {
                        _interactiveConcurrency = i;
                    },
                    concurrency);
            return this;
        }

        @Override
        public boolean isInteractiveConcurrencyDefault() {
            return _interactiveConcurrency == 0;
        }

        @Override
        public boolean isRunning() {
            // :: Return true if /any/ endpoint is running.
            for (MatsEndpoint<?, ?> endpoint : getEndpoints()) {
                if (endpoint.getEndpointConfig().isRunning()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(String key) {
            return (T) _attributes.get(key);
        }

        @Override
        public String getAppName() {
            return _appName;
        }

        @Override
        public String getAppVersion() {
            return _appVersion;
        }

        @Override
        public String getSystemInformation() {
            int totalNumStages = 0;
            int totalStageProcessors = 0;
            for (JmsMatsEndpoint<?, ?> createdEndpoint : _createdEndpoints) {
                totalNumStages += createdEndpoint.getStages().size();
                // loop stages
                for (MatsStage<?, ?, ?> stage : createdEndpoint.getStages()) {
                    totalStageProcessors += stage.getStageConfig().getRunningStageProcessors();
                }

            }

            return "JMS MatsFactory: " + idThis()
                    + "\n  Endpoints: "
                    + _createdEndpoints.size() + " (total stages:" + totalNumStages
                    + ", stage processors:" + totalStageProcessors
                    + ")\n  Initiators: " + _createdInitiators.size()
                    + "\n  TransactionManager: " + id(_jmsMatsTransactionManager)
                    + "\n  JMS Session Handler: " + id(_jmsMatsJmsSessionHandler)
                    + "\n  MatsSerializer: " + id(_matsSerializer)
                    + "\n\n"
                    + _jmsMatsTransactionManager.getSystemInformation()
                    + "\n\n"
                    + _jmsMatsJmsSessionHandler.getSystemInformation();
        }

        @Override
        public String getNodename() {
            return _nodename;
        }

        @Override
        public String getMatsImplementationName() {
            return MATS_IMPLEMENTATION_NAME;
        }

        @Override
        public String getMatsImplementationVersion() {
            return MATS_IMPLEMENTATION_VERSION;
        }

        @Override
        public FactoryConfig setNodename(String nodename) {
            _nodename = nodename;
            return this;
        }

        @Override
        public String getCommonEndpointGroupId() {
            return _commonEndpointGroupId == null ? getAppName() : _commonEndpointGroupId;
        }

        @Override
        public FactoryConfig setCommonEndpointGroupId(String commonEndpointGroupId) {
            _commonEndpointGroupId = commonEndpointGroupId;
            return this;
        }

        @Override
        public <T> T instantiateNewObject(Class<T> type) {
            try {
                return _matsSerializer.newInstance(type);
            }
            catch (Throwable t) {
                throw new CannotInstantiateClassException("Got problem when using current MatsSerializer to test"
                        + " instantiate class [" + type + "] MatsSerializer: ["
                        + _matsSerializer + "].", t);
            }
        }

        @Override
        public FactoryConfig installPlugin(MatsPlugin plugin) {
            JmsMatsFactory.this.installPlugin(plugin);
            return this;
        }

        @Override
        public <T extends MatsPlugin> List<T> getPlugins(Class<T> filterByClass) {
            return JmsMatsFactory.this.getPlugins(filterByClass);
        }

        @Override
        public boolean removePlugin(MatsPlugin instanceToRemove) {
            return JmsMatsFactory.this.removePlugin(instanceToRemove);
        }

        @Override
        public FactoryConfig setAttribute(String key, Object value) {
            if (value == null) {
                _attributes.remove(key);
            }
            else {
                _attributes.put(key, value);
            }
            return this;
        }
    }
}
