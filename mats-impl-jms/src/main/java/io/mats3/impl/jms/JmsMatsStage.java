package io.mats3.impl.jms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsConfig;
import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsStage;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;

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

    private String _nextStageId;

    void setNextStageId(String nextStageId) {
        _nextStageId = nextStageId;
    }

    String getNextStageId() {
        return _nextStageId;
    }

    String getStageId() {
        return _stageId;
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
            // TODO: Throw in version >= 0.17.0
            log.warn(LOG_PREFIX + ILLEGAL_CALL_FLOWS + "NOTICE!! WRONG API USE! WILL THROW IN A LATER VERSION!"
                    + " Cannot start Stage [" + id(_stageId, this) + "] of Endpoint [" + _parentEndpoint
                    + "], as Endpoint is not finishSetup() yet!");
            return;
        }

        log.info(LOG_PREFIX + "   |-  Starting Stage [" + id(_stageId, this) + "].");
        if (_stageProcessors.size() > 1) {
            log.warn(LOG_PREFIX + "   |- When asked to start Stage, it was ALREADY STARTED! [" + id(_stageId, this)
                    + "].");
            return;
        }

        // :: Fire up the actual stage processors, using the configured (or default) concurrency
        int numberOfProcessors = getStageConfig().getConcurrency();
        // ?: Is this a topic?
        if (!_queue) {
            /*
             * -> Yes, is it a Topic, and in that case, there shall only be one StageProcessor for the endpoint. The
             * whole point of a MQ Topic is that all listeners to the topic will get the same messages, and thus running
             * multiple identical Stages (i.e. listeners) on a MatsFactory for a Topic makes zero sense.
             *
             * (Optimizations along the line of using a thread pool for the actual work of the processor must be done in
             * user code, as the MATS framework must acknowledge (commit/rollback) each message, and cannot decide what
             * code could potentially be done concurrently.. Such a thread pool is for example used in the
             * "MatsFuturizer" tool)
             */
            numberOfProcessors = 1;
        }

        // :: Add all the ordinary stage processors
        for (int i = 0; i < numberOfProcessors; i++) {
            _stageProcessors.add(new JmsMatsStageProcessor<>(this, i, false));
        }
        // :: Add interactive stage processors
        // ?: Should we add them? (RabbitMQ does not support message selectors for Queues (only topics!))
        // Hacky way to determine the underlying ConnectionFactory
        boolean shouldStartInteractiveStageProcessors = false;
        try {
            JmsSessionHolder sessionHolder = _parentFactory.getJmsMatsJmsSessionHandler()
                    .getSessionHolder(_parentFactory
                            .getOrCreateInitiator_internal(JmsMatsFactory.DEFAULT_INITIATOR_NAME));
            Session session = sessionHolder.getSession();
            // We should start the interactive stage processors UNLESS the underlying JMS Client is RabbitMQ.
            shouldStartInteractiveStageProcessors = ! session.getClass().getName().contains(".rabbitmq.");
            sessionHolder.release();
        }
        catch (JmsMatsJmsException e) {
            log.warn("Got problems getting a JMS Session to determine underlying broker brand we're connected to;"
                    + " Checking whether RabbitMQ, in which case we can't start interactive StageProcessors."
                    + " Assuming worst case: Not starting interactive StageProcessors.", e);
        }
        // ?: Is this a Queue? (Cannot add multiple processors for topic endpoints - read comment above).
        // .. AND should we start interactive StageProcessors?
        if (_queue && shouldStartInteractiveStageProcessors) {
            // -> Yes, this is a queue, so then we can add the interactive processors
            // Add floor'ed half of the normal numberOfProcessors, but at least 1.
            int numberOfInteractiveProcessors = Math.max(1, (int) (numberOfProcessors / 2d));
            for (int i = 0; i < numberOfInteractiveProcessors; i++) {
                _stageProcessors.add(new JmsMatsStageProcessor<>(this, i, true));
            }
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
        private int _concurrency;

        @Override
        public MatsConfig setConcurrency(int concurrency) {
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
                return _parentEndpoint.getEndpointConfig().getConcurrency();
            }
            return _concurrency;
        }

        @Override
        public boolean isRunning() {
            return _stageProcessors.size() > 0;
        }

        @Override
        public int getRunningStageProcessors() {
            return _stageProcessors.size();
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
        @Deprecated
        public Class<I> getIncomingMessageClass() {
            return getIncomingClass();
        }

        @Override
        public Class<I> getIncomingClass() {
            return _incomingClass;
        }
    }

}
