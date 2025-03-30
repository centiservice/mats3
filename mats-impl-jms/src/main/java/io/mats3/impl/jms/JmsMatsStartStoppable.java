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

import java.util.List;

import io.mats3.MatsConfig.StartStoppable;

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
            millisLeft -= (int) (System.currentTimeMillis() - millisBefore);
            millisLeft = Math.max(millisLeft, EXTRA_GRACE_MILLIS);
        }
        return started;
    }

    // ===== Graceful shutdown algorithm.

    default void stopPhase0_SetRunFlagFalse() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase0_SetRunFlagFalse);
    }

    default void stopPhase1_QuickCloseSessionIfInReceive() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase1_QuickCloseSessionIfInReceive);
    }

    default void stopPhase2_GracefulCloseSessionIfInReceive() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase2_GracefulCloseSessionIfInReceive);
    }

    default void stopPhase3_GracefulWaitAfterRunflagFalseAndSessionClosed(int gracefulShutdownMillis) {
        int millisLeft = gracefulShutdownMillis;
        for (JmsMatsStartStoppable child : getChildrenStartStoppable()) {
            long millisBefore = System.currentTimeMillis();
            child.stopPhase3_GracefulWaitAfterRunflagFalseAndSessionClosed(millisLeft);
            millisLeft -= (int) (System.currentTimeMillis() - millisBefore);
            millisLeft = Math.max(millisLeft, EXTRA_GRACE_MILLIS);
        }
    }

    default void stopPhase4_InterruptIfStillAlive() {
        getChildrenStartStoppable().forEach(JmsMatsStartStoppable::stopPhase4_InterruptIfStillAlive);
    }

    default boolean stopPhase5_GracefulWaitAfterInterrupt() {
        boolean stopped = true;
        for (JmsMatsStartStoppable child : getChildrenStartStoppable()) {
            stopped &= child.stopPhase5_GracefulWaitAfterInterrupt();
        }
        return stopped;
    }

    @Override
    default boolean stop(int gracefulShutdownMillis) {
        stopPhase0_SetRunFlagFalse();
        stopPhase1_QuickCloseSessionIfInReceive();
        stopPhase2_GracefulCloseSessionIfInReceive();
        stopPhase3_GracefulWaitAfterRunflagFalseAndSessionClosed(gracefulShutdownMillis);
        stopPhase4_InterruptIfStillAlive();
        return stopPhase5_GracefulWaitAfterInterrupt();
    }
}
