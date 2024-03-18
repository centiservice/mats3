package io.mats3.impl.jms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsStage;
import io.mats3.impl.jms.JmsMatsStageProcessor.PriorityFilter;

/**
 * The JMS implementation of {@link MatsStage}.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class JmsMatsStage<R, S, I, Z> implements MatsStage<R, S, I>, JmsMatsStatics, JmsMatsStartStoppable {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsStage.class);

    private final JmsMatsEndpoint<R, S, Z> _parentEndpoint;
    private final int _stageIndex;
    private final String _stageId;
    private final boolean _queue;
    private final Class<S> _stateClass;
    private final Class<I> _incomingClass;
    private final ProcessLambda<R, S, I> _processLambda;

    private final JmsMatsFactory<Z> _parentFactory;

    private final JmsStageConfig _stageConfig = new JmsStageConfig();

    public JmsMatsStage(JmsMatsEndpoint<R, S, Z> parentEndpoint, int stageIndex, String stageId, boolean queue,
            Class<I> incomingClass, Class<S> stateClass, ProcessLambda<R, S, I> processLambda) {
        _parentEndpoint = parentEndpoint;
        _stageIndex = stageIndex;
        _stageId = stageId;
        _queue = queue;
        _stateClass = stateClass;
        _incomingClass = incomingClass;
        _processLambda = processLambda;

        _parentFactory = _parentEndpoint.getParentFactory();

        log.info(LOG_PREFIX + "   |- Created Stage [" + id(_stageId, this) + "].");
    }

    @Override
    public StageConfig<R, S, I> getStageConfig() {
        return _stageConfig;
    }

    @Override
    public JmsMatsEndpoint<R, S, Z> getParentEndpoint() {
        return _parentEndpoint;
    }

    boolean isQueue() {
        return _queue;
    }

    Class<S> getStateClass() {
        return _stateClass;
    }

    Class<I> getMessageClass() {
        return _incomingClass;
    }

    ProcessLambda<R, S, I> getProcessLambda() {
        return _processLambda;
    }

    JmsMatsFactory<Z> getParentFactory() {
        return _parentFactory;
    }

    CountDownLatch getAnyProcessorMadeConsumerLatch() {
        return _anyProcessorMadeConsumerLatch;
    }

    String getStageId() {
        return _stageId;
    }

    public String getStageDestinationName() {
        return _parentFactory.getFactoryConfig().getMatsDestinationPrefix() + getStageId();
    }

    private String _nextStageId;

    void setNextStageId(String nextStageId) {
        _nextStageId = nextStageId;
    }

    String getNextStageId() {
        return _nextStageId;
    }

    private JmsMatsStage<R, S, ?, Z> _nextStage;

    void setNextStage(JmsMatsStage<R, S, ?, Z> nextStage) {
        _nextStage = nextStage;
    }

    public JmsMatsStage<R, S, ?, Z> getNextStage() {
        return _nextStage;
    }

    private final CopyOnWriteArrayList<JmsMatsStageProcessor<R, S, I, Z>> _stageProcessors = new CopyOnWriteArrayList<>();

    /**
     * Called by the {@link JmsMatsStageProcessor} when its thread exists.
     */
    void removeStageProcessorFromList(JmsMatsStageProcessor<R, S, I, Z> stageProcessor) {
        _stageProcessors.remove(stageProcessor);
    }

    private CountDownLatch _anyProcessorMadeConsumerLatch = new CountDownLatch(1);

    @Override
    public synchronized void start() {
        if (!_parentEndpoint.isFinishedSetup()) {
            throw new IllegalStateException(" Cannot start Stage [" + id(_stageId, this) + "] of Endpoint ["
                    + _parentEndpoint + "], as Endpoint is not finishSetup() yet!");
        }

        log.info(LOG_PREFIX + "   |-  Starting Stage [" + id(_stageId, this) + "].");
        if (_stageProcessors.size() > 1) {
            log.warn(LOG_PREFIX + "   |- When asked to start Stage, it was ALREADY STARTED! [" + id(_stageId, this)
                    + "].");
            return;
        }

        // ?: Queue or Topic?
        if (_queue) {
            // -> Queue: Fire up the actual stage processors, using the configured (or default) concurrency
            // Add the Standard-Priority-Only processors.
            for (int i = 0; i < getStageConfig().getConcurrency(); i++) {
                _stageProcessors.add(new JmsMatsStageProcessor<>(this, i, PriorityFilter.STANDARD_ONLY));
            }
            // Add the Interactive-Priority-Only processors.
            for (int i = 0; i < getStageConfig().getInteractiveConcurrency(); i++) {
                _stageProcessors.add(new JmsMatsStageProcessor<>(this, i, PriorityFilter.INTERACTIVE_ONLY));
            }
        }
        else {
            /*
             * -> Topic: there can and shall only be one StageProcessor for the SubscriptionEndpoint. The whole point of
             * a MQ Topic is that all listeners to the topic will get the same messages, and thus running multiple
             * identical Stages (i.e. listeners) on a MatsFactory for a given Topic makes zero sense.
             *
             * (Optimizations along the line of using a thread pool for the actual work of the processor must be done in
             * user code, as the Mats framework must acknowledge (commit/rollback) each message, and cannot decide what
             * code could potentially be done concurrently.. Such a thread pool is for example used in the
             * "MatsFuturizer" tool)
             */
            _stageProcessors.add(new JmsMatsStageProcessor<>(this, 1, PriorityFilter.UNFILTERED));
        }

        // Start all stage processors
        for (JmsMatsStageProcessor<R, S, I, Z> stageProcessor : _stageProcessors) {
            stageProcessor.start();
        }
    }

    @Override
    public boolean waitForReceiving(int timoutMillis) {
        try {
            return _anyProcessorMadeConsumerLatch.await(timoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            throw new IllegalStateException("Got interrupted while waitForStarted().", e);
        }
    }

    @Override
    public boolean stop(int gracefulShutdownMillis) {
        log.info(LOG_PREFIX + "Stopping [" + _stageId + "]: Stopping all StageProcessors.");
        return JmsMatsStartStoppable.super.stop(gracefulShutdownMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JmsMatsStage<?, ?, ?, ?> that = (JmsMatsStage<?, ?, ?, ?>) o;
        return _parentFactory.equals(that._parentFactory) && _stageId.equals(that._stageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_parentFactory, _stageId);
    }

    @Override
    public String idThis() {
        return id("JmsMatsStage{" + _stageId + "}", this) + "@" + _parentFactory;
    }

    @Override
    public String toString() {
        return idThis();
    }

    @Override
    public List<JmsMatsStartStoppable> getChildrenStartStoppable() {
        return new ArrayList<>(_stageProcessors);
    }

    private class JmsStageConfig implements StageConfig<R, S, I> {
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
            // E-> No, not specific concurrency, so return parent endpoint's concurrency.
            return _parentEndpoint.getEndpointConfig().getConcurrency();
        }

        @Override
        public StageConfig<R, S, I> setConcurrency(int concurrency) {
            setConcurrencyWithLog(log, "Stage[" + _stageId + "] Concurrency",
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
            // E-> No, this stage has neither specific normal nor interactive concurrency, so go to parent stage.
            return _parentEndpoint.getEndpointConfig().getInteractiveConcurrency();
        }

        @Override
        public StageConfig<R, S, I> setInteractiveConcurrency(int concurrency) {
            setConcurrencyWithLog(log, "Stage[" + _stageId + "] Interactive Concurrency",
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
            return _stageProcessors.size() > 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(String key) {
            return (T) _attributes.get(key);
        }

        @Override
        public int getRunningStageProcessors() {
            return _stageProcessors.size();
        }

        @Override
        public StageConfig<R, S, I> setOrigin(String origin) {
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
        public StageConfig<R, S, I> setAttribute(String key, Object value) {
            if (value == null) {
                _attributes.remove(key);
            }
            else {
                _attributes.put(key, value);
            }
            return this;
        }

        @Override
        public String getStageId() {
            return _stageId;
        }

        @Override
        public int getStageIndex() {
            return _stageIndex;
        }

        @Override
        public Class<I> getIncomingClass() {
            return _incomingClass;
        }

        @Override
        public ProcessLambda<R, S, I> getProcessLambda() {
            return _processLambda;
        }
    }
}
