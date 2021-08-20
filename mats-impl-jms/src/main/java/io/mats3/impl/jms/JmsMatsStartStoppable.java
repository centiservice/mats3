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

    default void stopPhase0_SetRunFlagFalse() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase0_SetRunFlagFalse);
    }

    default void stopPhase1_CloseSessionIfInReceive() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase1_CloseSessionIfInReceive);
    }

    default void stopPhase2_GracefulWaitAfterRunflagFalse(int gracefulShutdownMillis) {
        int millisLeft = gracefulShutdownMillis;
        for (JmsMatsStartStoppable child : getChildrenStartStoppable()) {
            long millisBefore = System.currentTimeMillis();
            child.stopPhase2_GracefulWaitAfterRunflagFalse(millisLeft);
            millisLeft -= (System.currentTimeMillis() - millisBefore);
            millisLeft = Math.max(millisLeft, EXTRA_GRACE_MILLIS);
        }
    }

    default void stopPhase3_InterruptIfStillAlive() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase3_InterruptIfStillAlive);
    }

    default boolean stopPhase4_GracefulAfterInterrupt() {
        boolean stopped = true;
        for (JmsMatsStartStoppable child : getChildrenStartStoppable()) {
            stopped &= child.stopPhase4_GracefulAfterInterrupt();
        }
        return stopped;
    }

    @Override
    default boolean stop(int gracefulShutdownMillis) {
        stopPhase0_SetRunFlagFalse();
        stopPhase1_CloseSessionIfInReceive();
        stopPhase2_GracefulWaitAfterRunflagFalse(gracefulShutdownMillis);
        stopPhase3_InterruptIfStillAlive();
        return stopPhase4_GracefulAfterInterrupt();
    }
}
