package io.mats3.impl.jms;

import io.mats3.MatsConfig.StartStoppable;

import java.util.List;

/**
 * @author Endre Stølsvik 2019-08-23 23:30 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface JmsMatsStartStoppable extends JmsMatsStatics, StartStoppable {

    /**
     * Must be implemented to provide your children.
     */
    List<JmsMatsStartStoppable> getChildrenStartStoppable();

    @Override
    default boolean waitForReceiving(int timeoutMillis) {
        int millisLeft = timeoutMillis;
        boolean started = true;
        for (JmsMatsStartStoppable child : getChildrenStartStoppable()) {
            long millisBefore = System.currentTimeMillis();
            started &= child.waitForReceiving(millisLeft);
            millisLeft -= (System.currentTimeMillis() - millisBefore);
            millisLeft = Math.max(millisLeft, EXTRA_GRACE_MILLIS);
        }
        return started;
    }

    // ===== Graceful shutdown algorithm.

    default void stopPhase0_SetRunFlagFalseAndCloseSessionIfInReceive() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase0_SetRunFlagFalseAndCloseSessionIfInReceive);
    }

    default void stopPhase1_GracefulWaitAfterRunflagFalse(int gracefulShutdownMillis) {
        int millisLeft = gracefulShutdownMillis;
        for (JmsMatsStartStoppable child : getChildrenStartStoppable()) {
            long millisBefore = System.currentTimeMillis();
            child.stopPhase1_GracefulWaitAfterRunflagFalse(millisLeft);
            millisLeft -= (System.currentTimeMillis() - millisBefore);
            millisLeft = Math.max(millisLeft, EXTRA_GRACE_MILLIS);
        }
    }

    default void stopPhase2_InterruptIfStillAlive() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase2_InterruptIfStillAlive);
    }

    default boolean stopPhase3_GracefulAfterInterrupt() {
        boolean stopped = true;
        for (JmsMatsStartStoppable child : getChildrenStartStoppable()) {
            stopped &= child.stopPhase3_GracefulAfterInterrupt();
        }
        return stopped;
    }

    @Override
    default boolean stop(int gracefulShutdownMillis) {
        stopPhase0_SetRunFlagFalseAndCloseSessionIfInReceive();
        stopPhase1_GracefulWaitAfterRunflagFalse(gracefulShutdownMillis);
        stopPhase2_InterruptIfStillAlive();
        return stopPhase3_GracefulAfterInterrupt();
    }
}
