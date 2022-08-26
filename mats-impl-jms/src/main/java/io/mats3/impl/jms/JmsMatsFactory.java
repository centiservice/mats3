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
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.jms.MessageConsumer;
import javax.sql.DataSource;

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
import io.mats3.MatsStage.StageConfig;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateInterceptContext;
import io.mats3.api.intercept.MatsInterceptable;
import io.mats3.api.intercept.MatsInterceptableMatsFactory;
import io.mats3.api.intercept.MatsStageInterceptor;
import io.mats3.api.intercept.MatsStageInterceptor.StageInterceptContext;
import io.mats3.impl.jms.JmsMatsInitiator.MatsInitiator_TxRequired;
import io.mats3.impl.jms.JmsMatsInitiator.MatsInitiator_TxRequiresNew;
import io.mats3.impl.jms.JmsMatsProcessContext.DoAfterCommitRunnableHolder;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsTrace;

public class JmsMatsFactory<Z> implements MatsInterceptableMatsFactory, JmsMatsStatics, JmsMatsStartStoppable {

    private static String MATS_IMPLEMENTATION_NAME = "JMS Mats";
    private static String MATS_IMPLEMENTATION_VERSION = "0.18.7-2022-04-06";

    public static final String INTERCEPTOR_CLASS_MATS_LOGGING = "io.mats3.intercept.logging.MatsMetricsLoggingInterceptor";
    public static final String INTERCEPTOR_CLASS_MATS_METRICS = "io.mats3.intercept.micrometer.MatsMicrometerInterceptor";

    private static final Logger log = LoggerFactory.getLogger(JmsMatsFactory.class);

    static final String DEFAULT_INITIATOR_NAME = "default";

    public static <Z> JmsMatsFactory<Z> createMatsFactory_JmsOnlyTransactions(String appName, String appVersion,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler,
            MatsSerializer<Z> matsSerializer) {
        return createMatsFactory(appName, appVersion, jmsMatsJmsSessionHandler,
                JmsMatsTransactionManager_Jms.create(), matsSerializer);
    }

    public static <Z> JmsMatsFactory<Z> createMatsFactory_JmsAndJdbcTransactions(String appName, String appVersion,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler, DataSource dataSource,
            MatsSerializer<Z> matsSerializer) {
        return createMatsFactory(appName, appVersion, jmsMatsJmsSessionHandler,
                JmsMatsTransactionManager_JmsAndJdbc.create(dataSource), matsSerializer);
    }

    public static <Z> JmsMatsFactory<Z> createMatsFactory(String appName, String appVersion,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler,
            JmsMatsTransactionManager jmsMatsTransactionManager,
            MatsSerializer<Z> matsSerializer) {
        return new JmsMatsFactory<>(appName, appVersion, jmsMatsJmsSessionHandler, jmsMatsTransactionManager,
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
    private final MatsSerializer<Z> _matsSerializer;
    private final JmsMatsFactoryConfig _factoryConfig;

    private JmsMatsFactory(String appName, String appVersion,
            JmsMatsJmsSessionHandler jmsMatsJmsSessionHandler,
            JmsMatsTransactionManager jmsMatsTransactionManager,
            MatsSerializer<Z> matsSerializer) {
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

        installIfPresent(_matsLoggingInterceptor, MatsInterceptable.class);
        installIfPresent(_matsMetricsInterceptor, MatsInterceptableMatsFactory.class);

        log.info(LOG_PREFIX + "Created [" + idThis() + "].");
    }

    // :: Configurable variables:

    // Set to default, which is null.
    private Function<String, String> _initiateTraceIdModifier = null;

    // Set to default, which is 0 (which means default logic; 2x numCpus)
    private int _concurrency = 0;

    // Set to default, which is empty string (not null).
    private String _name = "";

    // Set to default, which is "mats.".
    private String _matsDestinationPrefix = "mats.";

    // Set to default, which is "mats:trace".
    private String _matsTraceKey = "mats:trace";

    // Set to default, which is what returned from command 'hostname', failing that, InetAddress..getHostName().
    private String _nodename = getHostname_internal();

    // Set to default, which is false. Volatile since quite probably set by differing threads.
    private volatile boolean _holdEndpointsUntilFactoryIsStarted = false;

    // Set to default, which is FULL
    private KeepTrace _defaultKeepTrace = KeepTrace.FULL;

    Function<String, String> getInitiateTraceIdModifier() {
        return _initiateTraceIdModifier;
    }

    private void installIfPresent(Class<?> standardInterceptorClass, Class<?> interceptable) {
        if (standardInterceptorClass != null) {
            log.info(LOG_PREFIX + "Found '" + standardInterceptorClass.getSimpleName()
                    + "' on classpath, installing for [" + idThis() + "].");
            try {
                Method install = standardInterceptorClass.getDeclaredMethod("install", interceptable);
                install.invoke(null, this);
            }
            catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                log.warn(LOG_PREFIX + "Got problems installing '" + standardInterceptorClass.getSimpleName() + "'.", e);
            }
        }
    }

    private final List<JmsMatsEndpoint<?, ?, Z>> _createdEndpoints = new ArrayList<>();
    private final List<JmsMatsInitiator<Z>> _createdInitiators = new ArrayList<>();

    private static String getHostname_internal() {
        try (BufferedInputStream in = new BufferedInputStream(Runtime.getRuntime().exec("hostname").getInputStream())) {
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

    /**
     * Sets the default KeepTrace if the initiation doesn't set one itself. The default for this default is
     * {@link KeepTrace#FULL}. Not yet moved to FactoryConfig, because I want to evaluate.
     * <p/>
     * Must be set before the MatsFactory is "published", memory wise.
     *
     * @param defaultKeepTrace
     *            the default KeepTrace for Mats flows - the default for this default is {@link KeepTrace#FULL}.
     */
    public void setDefaultKeepTrace(KeepTrace defaultKeepTrace) {
        _defaultKeepTrace = defaultKeepTrace;
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

    public MatsSerializer<Z> getMatsSerializer() {
        return _matsSerializer;
    }

    // :: Interceptors

    private final CopyOnWriteArrayList<MatsInitiateInterceptor> _initiationInterceptors = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<MatsStageInterceptor> _stageInterceptors = new CopyOnWriteArrayList<>();

    @Override
    public void addInitiationInterceptor(MatsInitiateInterceptor initiateInterceptor) {
        addInterceptor(MatsInitiateInterceptor.class, initiateInterceptor, _initiationInterceptors);
    }

    @Override
    public List<MatsInitiateInterceptor> getInitiationInterceptors() {
        return new ArrayList<>(_initiationInterceptors);
    }

    @Override
    public <T extends MatsInitiateInterceptor> Optional<T> getInitiationInterceptor(Class<T> interceptorClass) {
        return getInterceptorSingleton(_initiationInterceptors, interceptorClass);
    }

    @Override
    public void removeInitiationInterceptor(MatsInitiateInterceptor initiateInterceptor) {
        log.info(LOG_PREFIX + "Removing " + MatsInitiateInterceptor.class.getSimpleName()
                + ": [" + initiateInterceptor + "].");
        boolean removed = _initiationInterceptors.remove(initiateInterceptor);
        if (!removed) {
            throw new IllegalStateException("Cannot remove because not added: [" + initiateInterceptor + "]");
        }
    }

    @Override
    public void addStageInterceptor(MatsStageInterceptor stageInterceptor) {
        addInterceptor(MatsStageInterceptor.class, stageInterceptor, _stageInterceptors);
    }

    @Override
    public List<MatsStageInterceptor> getStageInterceptors() {
        return new ArrayList<>(_stageInterceptors);
    }

    @Override
    public <T extends MatsStageInterceptor> Optional<T> getStageInterceptor(Class<T> interceptorClass) {
        return getInterceptorSingleton(_stageInterceptors, interceptorClass);
    }

    @Override
    public void removeStageInterceptor(MatsStageInterceptor stageInterceptor) {
        log.info(LOG_PREFIX + "Removing " + MatsStageInterceptor.class.getSimpleName()
                + ": [" + stageInterceptor + "].");
        boolean removed = _stageInterceptors.remove(stageInterceptor);
        if (!removed) {
            throw new IllegalStateException("Cannot remove because not added: [" + stageInterceptor + "]");
        }
    }

    private <I, T extends I> Optional<T> getInterceptorSingleton(Collection<I> singletons, Class<T> typeToFind) {
        for (I singleton : singletons) {
            if (typeToFind.isInstance(singleton)) {
                @SuppressWarnings("unchecked")
                Optional<T> ret = (Optional<T>) Optional.of(singleton);
                return ret;
            }
        }
        return Optional.empty();
    }

    private <I> void addInterceptor(Class<I> interceptorType, I interceptor, CopyOnWriteArrayList<I> interceptors) {
        log.info(LOG_PREFIX + "Adding " + interceptorType.getSimpleName() + ": [" + interceptor + "].");
        // :: Special handling for our special interceptors
        // ?: Is this a MatsLoggingInterceptor?
        if (interceptor instanceof MatsLoggingInterceptor) {
            // -> Yes, MatsLoggingInterceptor - so clear out any existing to add this new.
            removeInterceptorType(interceptors, MatsLoggingInterceptor.class);
        }
        if (interceptor instanceof MatsMetricsInterceptor) {
            // -> Yes, MatsMetricsInterceptor - so clear out any existing to add this new.
            removeInterceptorType(interceptors, MatsMetricsInterceptor.class);
        }

        // ----- Not our special handling interceptors

        // :: Handle double-adding of same instance (not allowed)
        if (!((interceptor instanceof MatsLoggingInterceptor))
                || (interceptor instanceof MatsMetricsInterceptor)) {
            // ?: Have we already added this same instance?
            if (interceptors.contains(interceptor)) {
                // -> Yes, already added, cannot add twice.
                throw new IllegalStateException(interceptorType.getSimpleName()
                        + ": Interceptor Singleton already added: " + interceptor + ".");
            }
        }

        // Add the new
        interceptors.add(interceptor);
    }

    private <I> void removeInterceptorType(CopyOnWriteArrayList<I> interceptors, Class<?> typeToRemove) {
        Iterator<I> it = interceptors.iterator();
        while (it.hasNext()) {
            I next = it.next();
            // ?: Is this existing interceptor of a type to remove?
            if (typeToRemove.isInstance(next)) {
                // -> Yes, so remove it, and from the providers.
                log.info(LOG_PREFIX + ".. removing existing: [" + next + "], since adding new "
                        + typeToRemove.getSimpleName());
                it.remove();
            }
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
    public <R, S> JmsMatsEndpoint<R, S, Z> staged(String endpointId, Class<R> replyClass, Class<S> stateClass) {
        return staged(endpointId, replyClass, stateClass, NO_CONFIG);
    }

    @Override
    public <R, S> JmsMatsEndpoint<R, S, Z> staged(String endpointId, Class<R> replyClass, Class<S> stateClass,
            Consumer<? super EndpointConfig<R, S>> endpointConfigLambda) {
        JmsMatsEndpoint<R, S, Z> endpoint = new JmsMatsEndpoint<>(this, endpointId, true, stateClass, replyClass);
        endpoint.getEndpointConfig().setOrigin(getInvocationPoint());
        validateNewEndpoint(endpoint);
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
    public <R, I> JmsMatsEndpoint<R, Void, Z> single(String endpointId,
            Class<R> replyClass, Class<I> incomingClass,
            Consumer<? super EndpointConfig<R, Void>> endpointConfigLambda,
            Consumer<? super StageConfig<R, Void, I>> stageConfigLambda,
            ProcessSingleLambda<R, I> processor) {
        // Get a normal Staged Endpoint
        JmsMatsEndpoint<R, Void, Z> endpoint = staged(endpointId, replyClass, Void.TYPE, endpointConfigLambda);
        // :: Wrap the ProcessSingleLambda in a single lastStage-ProcessReturnLambda
        endpoint.lastStage(incomingClass, stageConfigLambda,
                (processContext, state, incomingDto) -> {
                    // This is just a direct forward - albeit a single stage has no state.
                    return processor.process(processContext, incomingDto);
                });
        return endpoint;
    }

    @Override
    public <S, I> JmsMatsEndpoint<Void, S, Z> terminator(String endpointId,
            Class<S> stateClass, Class<I> incomingClass,
            ProcessTerminatorLambda<S, I> processor) {
        return terminator(true, endpointId, incomingClass, stateClass, NO_CONFIG, NO_CONFIG, processor);
    }

    @Override
    public <S, I> JmsMatsEndpoint<Void, S, Z> terminator(String endpointId,
            Class<S> stateClass, Class<I> incomingClass,
            Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
            Consumer<? super StageConfig<Void, S, I>> stageConfigLambda,
            ProcessTerminatorLambda<S, I> processor) {
        return terminator(true, endpointId, incomingClass, stateClass, endpointConfigLambda, stageConfigLambda,
                processor);
    }

    @Override
    public <S, I> JmsMatsEndpoint<Void, S, Z> subscriptionTerminator(String endpointId, Class<S> stateClass,
            Class<I> incomingClass,
            ProcessTerminatorLambda<S, I> processor) {
        return terminator(false, endpointId, incomingClass, stateClass, NO_CONFIG, NO_CONFIG, processor);
    }

    @Override
    public <S, I> JmsMatsEndpoint<Void, S, Z> subscriptionTerminator(String endpointId, Class<S> stateClass,
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
    private <S, I> JmsMatsEndpoint<Void, S, Z> terminator(boolean queue, String endpointId,
            Class<I> incomingClass,
            Class<S> stateClass,
            Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
            Consumer<? super StageConfig<Void, S, I>> stageConfigLambda,
            ProcessTerminatorLambda<S, I> processor) {
        // Need to create the JmsMatsEndpoint ourselves, since we need to set the queue-parameter.
        JmsMatsEndpoint<Void, S, Z> endpoint = new JmsMatsEndpoint<>(this, endpointId, queue, stateClass,
                Void.TYPE);
        endpoint.getEndpointConfig().setOrigin(getInvocationPoint());
        validateNewEndpoint(endpoint);
        endpointConfigLambda.accept(endpoint.getEndpointConfig());
        // :: Wrap the ProcessTerminatorLambda in a single stage that does not return.
        // This is just a direct forward, w/o any return value.
        endpoint.stage(incomingClass, stageConfigLambda, processor::process);
        endpoint.finishSetup();
        return endpoint;
    }

    private ThreadLocal<Supplier<MatsInitiate>> __containingMatsInitiate = new ThreadLocal<>();
    private ThreadLocal<WithinStageContext<Z>> __withinStageContext = new ThreadLocal<>();

    static class WithinStageContext<Z> {
        private final MatsTrace<Z> _matsTrace;
        private final MessageConsumer _messageConsumer;

        public WithinStageContext(MatsTrace<Z> matsTrace, MessageConsumer messageConsumer) {
            _matsTrace = matsTrace;
            _messageConsumer = messageConsumer;
        }

        public MatsTrace<Z> getMatsTrace() {
            return _matsTrace;
        }

        public MessageConsumer getMessageConsumer() {
            return _messageConsumer;
        }
    }

    /**
     * Solution for "hoisting" transactions
     * <ul>
     * <li>If this exists, and DefaultInitiator: Use the existing - otherwise normal.</li>
     * <li>If this exists, and non-default Initiator: Fork out in thread, to get own context - otherwise normal.</li>
     * </ul>
     * Note: This ThreadLocal is on the MatsFactory, thus the {@link MatsInitiate} is scoped to the MatsFactory. This
     * should make some sense: If you in a Stage do a new Initiation, even with the
     * {@link MatsFactory#getDefaultInitiator()}, if this is <i>on a different MatsFactory</i>, then the transaction
     * should not be hoisted.
     */
    void setCurrentMatsFactoryThreadLocal_MatsInitiate(Supplier<MatsInitiate> matsInitiateSupplier) {
        __containingMatsInitiate.set(matsInitiateSupplier);
    }

    /**
     * Solution for keeping Stage context when doing nested initiations.
     * <ul>
     * <li>If this exists, then use
     * {@link JmsMatsInitiate#createForChildFlow(JmsMatsFactory, List, JmsMatsInternalExecutionContext, DoAfterCommitRunnableHolder, MatsTrace)}</li>
     * <li>If this does not exists, then use
     * {@link JmsMatsInitiate#createForTrueInitiation(JmsMatsFactory, List, JmsMatsInternalExecutionContext, DoAfterCommitRunnableHolder, String)}</li>
     * </ul>
     * <b>Notice: When forking out in new thread, make sure to copy over this ThreadLocal!</b>
     */
    void setCurrentMatsFactoryThreadLocal_WithinStageContext(MatsTrace<Z> matsTrace, MessageConsumer messageConsumer) {
        __withinStageContext.set(new WithinStageContext<>(matsTrace, messageConsumer));
    }

    void setCurrentMatsFactoryThreadLocal_WithinStageContext(WithinStageContext<Z> existing) {
        __withinStageContext.set(existing);
    }

    Optional<Supplier<MatsInitiate>> getCurrentMatsFactoryThreadLocal_MatsInitiate() {
        return Optional.ofNullable(__containingMatsInitiate.get());
    }

    Optional<WithinStageContext<Z>> getCurrentMatsFactoryThreadLocal_WithinStageContext() {
        return Optional.ofNullable(__withinStageContext.get());
    }

    void clearCurrentMatsFactoryThreadLocal_MatsInitiate() {
        __containingMatsInitiate.remove();
    }

    void clearCurrentMatsFactoryThreadLocal_WithinStageContext() {
        __withinStageContext.remove();
    }

    private volatile JmsMatsInitiator<Z> _defaultMatsInitiator;

    @Override
    public MatsInitiator getDefaultInitiator() {
        if (_defaultMatsInitiator == null) {
            synchronized (_createdInitiators) {
                if (_defaultMatsInitiator == null) {
                    _defaultMatsInitiator = getOrCreateInitiator_internal(DEFAULT_INITIATOR_NAME);
                }
            }
        }
        return new MatsInitiator_TxRequired<Z>(this, _defaultMatsInitiator);
    }

    @Override
    public MatsInitiator getOrCreateInitiator(String name) {
        return new MatsInitiator_TxRequiresNew<Z>(this, getOrCreateInitiator_internal(name));
    }

    public JmsMatsInitiator<Z> getOrCreateInitiator_internal(String name) {
        synchronized (_createdInitiators) {
            for (JmsMatsInitiator<Z> init : _createdInitiators) {
                if (init.getName().equals(name)) {
                    return init;
                }
            }
            // E-> Not found, make new
            JmsMatsInitiator<Z> initiator = new JmsMatsInitiator<>(name, this,
                    _jmsMatsJmsSessionHandler, _jmsMatsTransactionManager);
            addCreatedInitiator(initiator);
            return initiator;
        }
    }

    private void validateNewEndpoint(JmsMatsEndpoint<?, ?, Z> newEndpoint) {
        // :: Assert that it is possible to instantiate the State and Reply classes.
        assertOkToInstantiateClass(newEndpoint.getEndpointConfig().getStateClass(), "State 'STO' Class",
                "Endpoint " + newEndpoint.getEndpointId());
        assertOkToInstantiateClass(newEndpoint.getEndpointConfig().getReplyClass(), "Reply DTO Class",
                "Endpoint " + newEndpoint.getEndpointId());

        // :: Check that we do not have the endpoint already.
        synchronized (_createdEndpoints) {
            Optional<MatsEndpoint<?, ?>> existingEndpoint = getEndpoint(newEndpoint.getEndpointConfig()
                    .getEndpointId());
            if (existingEndpoint.isPresent()) {
                throw new IllegalStateException("An Endpoint with endpointId='"
                        + newEndpoint.getEndpointConfig().getEndpointId()
                        + "' was already present. Existing: [" + existingEndpoint.get()
                        + "], attempted registered:[" + newEndpoint + "].");
            }
        }
    }

    /**
     * Invoked by the endpoint upon {@link MatsEndpoint#finishSetup()}.
     */
    void addNewEndpointToFactory(JmsMatsEndpoint<?, ?, Z> newEndpoint) {
        // :: Check that we do not have the endpoint already - and if not, add it.
        synchronized (_createdEndpoints) {
            Optional<MatsEndpoint<?, ?>> existingEndpoint = getEndpoint(newEndpoint.getEndpointConfig()
                    .getEndpointId());
            if (existingEndpoint.isPresent()) {
                throw new IllegalStateException("An Endpoint with endpointId='"
                        + newEndpoint.getEndpointConfig().getEndpointId()
                        + "' was already present. Existing: [" + existingEndpoint.get()
                        + "], attempted registered:[" + newEndpoint + "].");
            }
            _createdEndpoints.add(newEndpoint);
        }
    }

    void removeEndpoint(JmsMatsEndpoint<?, ?, Z> endpointToRemove) {
        synchronized (_createdEndpoints) {
            Optional<MatsEndpoint<?, ?>> existingEndpoint = getEndpoint(endpointToRemove.getEndpointConfig()
                    .getEndpointId());
            if (!existingEndpoint.isPresent()) {
                throw new IllegalStateException("When trying to remove the endpoint [" + endpointToRemove + "], it was"
                        + " not present in the MatsFactory! EndpointId:[" + endpointToRemove.getEndpointId() + "]");
            }
            _createdEndpoints.remove(endpointToRemove);
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
        ArrayList<MatsEndpoint<?, ?>> matsEndpoints;
        synchronized (_createdEndpoints) {
            matsEndpoints = new ArrayList<>(_createdEndpoints);
        }
        // :: Split out private endpoints
        ArrayList<MatsEndpoint<?, ?>> privateEndpoints = new ArrayList<>();
        for (Iterator<MatsEndpoint<?, ?>> iterator = matsEndpoints.iterator(); iterator.hasNext();) {
            MatsEndpoint<?, ?> next = iterator.next();
            if (next.getEndpointConfig().getEndpointId().contains(".private.")) {
                privateEndpoints.add(next);
                iterator.remove();
            }
        }
        matsEndpoints.sort(Comparator.comparing(ep -> ep.getEndpointConfig().getEndpointId()));
        privateEndpoints.sort(Comparator.comparing(ep -> ep.getEndpointConfig().getEndpointId()));
        matsEndpoints.addAll(privateEndpoints);
        return matsEndpoints;
    }

    @Override
    public Optional<MatsEndpoint<?, ?>> getEndpoint(String endpointId) {
        synchronized (_createdEndpoints) {
            for (MatsEndpoint<?, ?> endpoint : _createdEndpoints) {
                if (endpoint.getEndpointConfig().getEndpointId().equals(endpointId)) {
                    return Optional.of(endpoint);
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public List<MatsInitiator> getInitiators() {
        ArrayList<MatsInitiator> matsInitiators;
        synchronized (_createdInitiators) {
            matsInitiators = new ArrayList<>(_createdInitiators);
        }
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

    private void addCreatedInitiator(JmsMatsInitiator<Z> initiator) {
        synchronized (_createdInitiators) {
            _createdInitiators.add(initiator);
        }
    }

    @Override
    public void start() {
        log.info(LOG_PREFIX + "Starting [" + idThis() + "], thus starting all created endpoints.");
        // First setting the "hold" to false, so if any subsequent endpoints are added, they will auto-start.
        _holdEndpointsUntilFactoryIsStarted = false;
        // :: Now start all the already configured endpoints
        for (MatsEndpoint<?, ?> endpoint : getEndpoints()) {
            endpoint.start();
        }
        // :: Wait for them entering receiving.
        for (MatsEndpoint<?, ?> endpoint : getEndpoints()) {
            endpoint.waitForReceiving(10_000);
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
        synchronized (_createdEndpoints) {
            return new ArrayList<>(_createdEndpoints);
        }
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
        log.info(LOG_PREFIX + getClass().getSimpleName() + ".close() invoked"
                + " (probably via Spring's default lifecycle), forwarding to stop().");
        stop(30_000);
    }

    @Override
    public boolean stop(int gracefulShutdownMillis) {
        log.info(LOG_PREFIX + "Stopping [" + idThis()
                + "], thus stopping/closing all created endpoints and initiators."
                + " Graceful shutdown millis: ["+gracefulShutdownMillis+"].");
        boolean stopped = JmsMatsStartStoppable.super.stop(gracefulShutdownMillis);

        for (MatsInitiator initiator : getInitiators()) {
            initiator.close();
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

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String idThis() {
        return id("JmsMatsFactory{" + _name + "}", this);
    }

    @Override
    public String toString() {
        return idThis();
    }

    private class JmsMatsFactoryConfig implements FactoryConfig {
        @Override
        public FactoryConfig setName(String name) {
            if (name == null) {
                throw new NullPointerException("name");
            }
            String idBefore = idThis();
            _name = name;
            log.info(LOG_PREFIX + "Set MatsFactory name to [" + name + "], previously [" + idBefore + "]"
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
        public FactoryConfig setConcurrency(int concurrency) {
            log.info(LOG_PREFIX + "MatsFactory's Concurrency is set to [" + concurrency
                    + "] (was:[" + _concurrency + "]" + (isConcurrencyDefault()
                            ? ", default, yielded:[" + getConcurrency() + "]"
                            : "") + ").");
            _concurrency = concurrency;
            return this;
        }

        @Override
        public boolean isConcurrencyDefault() {
            return _concurrency == 0;
        }

        @Override
        public int getConcurrency() {
            if (_concurrency == 0) {
                return 2 * getNumberOfCpus();
            }
            return _concurrency;
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
        public String getAppName() {
            return _appName;
        }

        @Override
        public String getAppVersion() {
            return _appVersion;
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
    }
}
