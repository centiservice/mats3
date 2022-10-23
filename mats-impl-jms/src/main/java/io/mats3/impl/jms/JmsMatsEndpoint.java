package io.mats3.impl.jms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.MatsStage;
import io.mats3.MatsStage.StageConfig;

/**
 * The JMS implementation of {@link MatsEndpoint}.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class JmsMatsEndpoint<R, S, Z> implements MatsEndpoint<R, S>, JmsMatsStatics, JmsMatsStartStoppable {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsEndpoint.class);

    private final JmsMatsFactory<Z> _parentFactory;
    private final String _endpointId;
    private final boolean _queue;
    private final Class<S> _stateClass;
    private final Class<R> _replyClass;

    JmsMatsEndpoint(JmsMatsFactory<Z> parentFactory, String endpointId, boolean queue, Class<S> stateClass,
            Class<R> replyClass) {
        _parentFactory = parentFactory;
        _endpointId = endpointId;
        _queue = queue;
        _stateClass = stateClass;
        _replyClass = replyClass;

        log.info(LOG_PREFIX + "Created Endpoint [" + id(_endpointId, this) + "].");
    }

    private final JmsEndpointConfig _endpointConfig = new JmsEndpointConfig();

    @Override
    public JmsMatsFactory<Z> getParentFactory() {
        return _parentFactory;
    }

    String getEndpointId() {
        return _endpointId;
    }

    private List<JmsMatsStage<R, S, ?, Z>> _stages = new CopyOnWriteArrayList<>();

    @Override
    public EndpointConfig<R, S> getEndpointConfig() {
        return _endpointConfig;
    }

    @Override
    public <I> MatsStage<R, S, I> stage(Class<I> incomingClass, ProcessLambda<R, S, I> processor) {
        return stage(incomingClass, MatsFactory.NO_CONFIG, processor);
    }

    @Override
    public <I> MatsStage<R, S, I> stage(Class<I> incomingClass,
            Consumer<? super StageConfig<R, S, I>> stageConfigLambda,
            ProcessLambda<R, S, I> processor) {
        // ?: Check whether we're already finished set up
        if (_finishedSetup) {
            throw new IllegalStateException("Endpoint [" + _endpointId
                    + "] has already had its finishSetup() invoked.");
        }
        // Get the stageIndex: Current number of stages in the _stages-list, the first get #0.
        int stageIndex = _stages.size();
        // Make stageId, which is the endpointId for the first, then endpointId.stage1, stage2 etc.
        String stageId = stageIndex == 0 ? _endpointId : _endpointId + ".stage" + (stageIndex);

        // :: Assert that we can instantiate an object of incoming class
        // ?: Is this "MatsObject", in which case the deserialization will happen runtime, i.e. cannot early check.
        if (!incomingClass.isAssignableFrom(MatsObject.class)) {
            // -> Not, it is not MatsObject, so test that we can instantiate it.
            _parentFactory.assertOkToInstantiateClass(incomingClass, "Incoming DTO Class", "Stage " + stageId);
        }

        JmsMatsStage<R, S, I, Z> stage = new JmsMatsStage<>(this, stageIndex, stageId, _queue,
                incomingClass, _stateClass, processor);
        // :: Set this next stage's Id on the previous stage, unless we're first, in which case there is no previous.
        if (_stages.size() > 0) {
            _stages.get(_stages.size() - 1).setNextStageId(stageId);
            _stages.get(_stages.size() - 1).setNextStage(stage);
        }
        _stages.add(stage);
        stage.getStageConfig().setOrigin(getInvocationPoint());
        stageConfigLambda.accept(stage.getStageConfig());
        @SuppressWarnings("unchecked")
        MatsStage<R, S, I> matsStage = stage;
        return matsStage;
    }

    @Override
    public <I> MatsStage<R, S, I> lastStage(Class<I> incomingClass, ProcessReturnLambda<R, S, I> processor) {
        return lastStage(incomingClass, MatsFactory.NO_CONFIG, processor);
    }

    @Override
    public <I> MatsStage<R, S, I> lastStage(Class<I> incomingClass,
            Consumer<? super StageConfig<R, S, I>> stageConfigLambda,
            io.mats3.MatsEndpoint.ProcessReturnLambda<R, S, I> processor) {
        // :: Wrap a standard ProcessLambda around the ProcessReturnLambda, performing the return-reply convenience.
        MatsStage<R, S, I> stage = stage(incomingClass, stageConfigLambda,
                (processContext, state, incomingDto) -> {
                    // Invoke the ProcessReturnLambda, holding on to the returned value from it.
                    R replyDto = processor.process(processContext, state, incomingDto);
                    // Replying with the returned value.
                    processContext.reply(replyDto);
                });
        // Since this is the last stage, we'll finish the setup.
        finishSetup();
        return stage;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MatsStage<R, S, ?>> getStages() {
        // Hack to have the compiler shut up.
        return (List<MatsStage<R, S, ?>>) (List<?>) _stages;
    }

    private volatile boolean _finishedSetup;

    @Override
    public void finishSetup() {
        // ?: Already invoked?
        if (_finishedSetup) {
            // -> Yes, already invoked, so ignore.
            return;
        }
        _finishedSetup = true;
        _parentFactory.addNewEndpointToFactory(this);
        if (!_parentFactory.isHoldEndpointsUntilFactoryIsStarted()) {
            log.info(LOG_PREFIX + "   \\- Finished setup of, and will immediately start, Endpoint [" + id(_endpointId,
                    this) + "].");
            start();
        }
        else {
            log.info(LOG_PREFIX + "   \\- Finished setup, but holding start of Endpoint [" + id(_endpointId, this)
                    + "].");
        }
    }

    @Override
    public void start() {
        if (!isFinishedSetup()) {
            throw new IllegalStateException("Cannot start Stages for Endpoint [" + _endpointId + "], as Endpoint is"
                    + " not finishSetup() yet!");
        }
        log.info(JmsMatsStatics.LOG_PREFIX + "Starting all Stages for Endpoint [" + _endpointId + "].");
        _stages.forEach(JmsMatsStage::start);
        log.info(JmsMatsStatics.LOG_PREFIX + "   \\- All stages started for Endpoint [" + _endpointId + "].");
    }

    boolean isFinishedSetup() {
        return _finishedSetup;
    }

    @Override
    public List<JmsMatsStartStoppable> getChildrenStartStoppable() {
        return new ArrayList<>(_stages);
    }

    @Override
    public boolean waitForReceiving(int timeoutMillis) {
        return JmsMatsStartStoppable.super.waitForReceiving(timeoutMillis);
    }

    @Override
    public boolean stop(int gracefulShutdownMillis) {
        return JmsMatsStartStoppable.super.stop(gracefulShutdownMillis);
    }

    @Override
    public boolean remove(int gracefulShutdownMillis) {
        boolean stopped = stop(gracefulShutdownMillis);
        if (!stopped) {
            return false;
        }
        _parentFactory.removeEndpoint(this);
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JmsMatsEndpoint<?, ?, ?> that = (JmsMatsEndpoint<?, ?, ?>) o;
        return _parentFactory.equals(that._parentFactory) && _endpointId.equals(that._endpointId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_parentFactory, _endpointId);
    }

    @Override
    public String idThis() {
        return id("JmsMatsEndpoint{" + _endpointId + "}", this) + "@" + _parentFactory;
    }

    @Override
    public String toString() {
        return idThis();
    }

    private class JmsEndpointConfig implements EndpointConfig<R, S> {
        private ConcurrentHashMap<String, Object> _attributes = new ConcurrentHashMap<>();
        private int _concurrency;
        private int _interactiveConcurrency;
        private String _creationInfo;

        @Override
        public int getConcurrency() {
            // ?: Is the concurrency set specifically?
            if (_concurrency != 0) {
                // -> Yes, set specifically, so return it.
                return _concurrency;
            }
            // E-> No, not specific concurrency, so return parent factory's concurrency.
            return _parentFactory.getFactoryConfig().getConcurrency();
        }

        @Override
        public EndpointConfig<R, S> setConcurrency(int concurrency) {
            setConcurrencyWithLog(log, "Endpoint[" + _endpointId + "] Concurrency",
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
            // E-> No, not specific /interactive/ concurrency
            // ?: Check for specific /normal/ concurrency
            if (_concurrency != 0) {
                // -> Yes, normal concurrency set specifically, so return it
                return _concurrency;
            }
            // E-> No, this endpoint has neither specific normal nor interactive concurrency, so go to parent factory.
            return _parentFactory.getFactoryConfig().getInteractiveConcurrency();
        }

        @Override
        public EndpointConfig<R, S> setInteractiveConcurrency(int concurrency) {
            setConcurrencyWithLog(log, "Endpoint[" + _endpointId + "] Interactive Concurrency",
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
            for (JmsMatsStage<R, S, ?, Z> stage : _stages) {
                if (stage.getStageConfig().isRunning()) {
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
        public Class<?> getIncomingClass() {
            return _stages.get(0).getStageConfig().getIncomingClass();
        }

        @Override
        public EndpointConfig<R, S> setOrigin(String origin) {
            if (origin == null) {
                throw new NullPointerException("origin");
            }
            _creationInfo = origin;
            return this;
        }

        @Override
        public String getOrigin() {
            return _creationInfo;
        }

        @Override
        public EndpointConfig<R, S> setAttribute(String key, Object value) {
            _attributes.put(key, value);
            return this;
        }

        @Override
        public String getEndpointId() {
            return _endpointId;
        }

        @Override
        public boolean isSubscription() {
            return !_queue;
        }

        @Override
        public Class<R> getReplyClass() {
            return _replyClass;
        }

        @Override
        public Class<S> getStateClass() {
            return _stateClass;
        }
    }
}
