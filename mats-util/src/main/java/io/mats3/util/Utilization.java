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

package io.mats3.util;

/**
 * Tool to measure utilization, typically for threads in a fixed thread pool (each thread has its own instance). A
 * thread register itself "active" when starting to process a job, and "idle" when done. The tool can then report the
 * percentage of time spent "active" vs. "idle" for any interval between 1 second and one hour (albeit 1 second is
 * probably way to small to be meaningful for the intended usage - and keep in mind that the "current second" is not
 * available in the {@link #getSnapshot(int) statistics snapshot} - you must advance to next slot). It keeps a sliding
 * window of one hour in seconds resolution. The measurement is done using wall time, in milliseconds precision using
 * {@link System#currentTimeMillis()} - but with an attempt to handle very short jobs <b>within a single second-slot</b>
 * correctly using {@link System#nanoTime()}. It handles utilization correctly wrt. the current state: If e.g. the
 * thread is currently active, and has been active for 20 seconds, then this is taken into account when querying
 * utilization for the last minute. The <i>current</i> second slot is not included in utilization calculations, as it is
 * not yet finished.
 * <p>
 * The tool also optionally keeps a count of tasks finished on each second slot, and the total time spent on those tasks
 * ({@link #createWithTiming()} vs {@link #createWithoutTiming()}). Average task time can thus be calculated for any
 * interval. This feature uses nanotime. However, this statistic only takes into account intervals that are finished, so
 * if the thread is currently active, that job is not included in the count and total time.
 * <p>
 * The tool uses a fixed amount of memory: If created without count and timings: One array of 3600 float slots (3600 x 4
 * bytes = 14.4kB). If created with counts and timings: Three arrays of 3600 slots (float+int+float), one triplet per
 * second in the hour (3600 x 3 x 4 bytes = 43.2kB). It records the timings between setActive and setIdle calls (i.e.
 * the active periods), inferring the idle periods as the gaps between those. The data structures handle from thousands
 * of sub-millisecond active periods per second, to a few long in an hour.
 * <p>
 * System clock jumps are handled quite rudimentarily, just aiming to avoid grave consequences: If the clock jumps
 * backwards across the previous state change, the state is just updated along with the new state change, and the time
 * between the old last state change and now is ignored. If the clock jumps forwards, this will result in skewed
 * statistics, either counting too much idle or too much active time. Large jumps will give large skews, up to this tool
 * stating that "the thread was 100% active the entire last hour", even though it was just active 1 second, but the
 * clock jumped one hour. This should not be a problem in practice, as long jumps in wall time ought to be very rare.
 *
 * @author Endre Stølsvik 2025-10-30 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Utilization {
    private static final int NUM_SECONDS = 60 * 60;

    private final float[] _utilizationPerSecond = new float[NUM_SECONDS];
    private final boolean _hasCountsAndTimings;
    private final int[] _finishedTasksForSecond;
    private final float[] _totalMillisForFinishedTasksForSecond;

    private final MillisClock _millisclock;
    private final NanosClock _nanosClock;

    private Utilization(MillisClock millisclock, NanosClock nanosClock, boolean includeCountsAndTimings) {
        _millisclock = millisclock;
        _nanosClock = nanosClock;
        if (includeCountsAndTimings) {
            _hasCountsAndTimings = true;
            _finishedTasksForSecond = new int[NUM_SECONDS];
            _totalMillisForFinishedTasksForSecond = new float[NUM_SECONDS];
        }
        else {
            _hasCountsAndTimings = false;
            _finishedTasksForSecond = null; // Not used
            _totalMillisForFinishedTasksForSecond = null; // Not used
        }
    }

    public static Utilization createWithTiming() {
        return new Utilization(System::currentTimeMillis, System::nanoTime, true);
    }

    public static Utilization createWithTiming(MillisClock clock, NanosClock nanosClock) {
        return new Utilization(clock, nanosClock, true);
    }

    public static Utilization createWithoutTiming() {
        return new Utilization(System::currentTimeMillis, System::nanoTime, false);
    }

    public static Utilization createWithoutTiming(MillisClock clock, NanosClock nanosClock) {
        return new Utilization(clock, nanosClock, false);
    }

    /**
     * A snapshot of utilization for an interval.
     */
    public interface UtilizationSnapshot {
        /**
         * @return the utilization (0.0 to 1.0) for the interval.
         */
        float getUtilization();

        /**
         * @return the number of finished tasks in the interval.
         */
        int getFinishedTasksCount();

        /**
         * @return the total time in millis (fractional) for the finished tasks in the interval.
         */
        double getTotalMillisForFinishedTasks();

        /**
         * @return the average time in nanoseconds for the finished tasks in the interval. If no tasks were finished,
         *         returns 0.
         */
        default double getAverageMillisPerFinishedTask() {
            int count = getFinishedTasksCount();
            if (count == 0) {
                return 0L;
            }
            return getTotalMillisForFinishedTasks() / count;
        }
    }

    /**
     * For testing
     */
    public interface MillisClock {
        long millis();
    }

    /**
     * For testing
     */
    public interface NanosClock {
        long nanos();
    }

    private long _lastStateChangeEpochMillis;
    private long _lastStateChangeNanoTime;
    private boolean _isActive = false;

    private long _currentSecond_NanosUtilization = 0;
    private int _currentSecond_FinishedTasksCount = 0;
    private long _currentSecond_TotalNanosForFinishedTasks = 0L;

    public synchronized void setIdle() {
        if (!_isActive) {
            // ?: First time ever setIdle called?
            if (_lastStateChangeEpochMillis == 0L) {
                // -> Yes, first time setIdle, even if we start out idle.
                // This is allowed due to use case where thread starts out idle, and annoying to have to check whether
                // initial call or not - so we accept this as a no-op. Just set the time and return
                _lastStateChangeEpochMillis = _millisclock.millis();
                _lastStateChangeNanoTime = _nanosClock.nanos();
                return;
            }
            else {
                // -> No, not first time ever, so this is an error.
                throw new IllegalStateException("Thread is already idle - must alternate setActive/setIdle calls.");
            }
        }
        _isActive = false;

        long nowEpochMillis = _millisclock.millis();
        long nowNanoTime = _nanosClock.nanos();
        // :: Handle clock backwards jumps
        if (nowEpochMillis < _lastStateChangeEpochMillis) {
            // Clock has jumped backwards. We handle this by just setting the last state change to now, and return.
            // This means that the time between the old last state change and now is "lost", but there is no good way
            // to handle it anyway.
            _lastStateChangeEpochMillis = nowEpochMillis;
            _lastStateChangeNanoTime = nowNanoTime;
            return;
        }
        // :: Handle if a full hour has passed since last state change
        if (handleFullHour(nowEpochMillis, _lastStateChangeEpochMillis, true)) {
            // Full hour passed since last state change, so all slots are set to 1.0, and zero completions.
            // Record utilization for the current second, by how many milliseconds into the current second
            // Must use absolute millis-time for this calculation, since we have crossed second boundaries, and thus
            // nanoTime is not usable (nanoTime and millis are not correlated).
            long millisIntoCurrentSecond = nowEpochMillis % 1000L;
            _currentSecond_NanosUtilization = millisIntoCurrentSecond * 1_000_000L;
            // Record one task finished for current second
            _currentSecond_FinishedTasksCount = 1;
            // .. not using nanotime since the time span is so large.
            _currentSecond_TotalNanosForFinishedTasks = (nowEpochMillis - _lastStateChangeEpochMillis) * 1_000_000L;

            // Update for state change to idle
            _lastStateChangeEpochMillis = nowEpochMillis;
            _lastStateChangeNanoTime = nowNanoTime;
            return;
        }
        // E-> Full hour has NOT passed since last state change
        int previousSlot = slotFor(_lastStateChangeEpochMillis);
        int currentSlot = slotFor(nowEpochMillis);

        // ?: Are we still in the same slot?
        if (previousSlot == currentSlot) {
            // Yes, still in the same slot
            // Update utilization for the current slot
            long deltaNanos = nowNanoTime - _lastStateChangeNanoTime;
            _currentSecond_NanosUtilization += deltaNanos;
            _currentSecond_FinishedTasksCount++;
            _currentSecond_TotalNanosForFinishedTasks += deltaNanos;

            // Update for state change to idle
            _lastStateChangeEpochMillis = nowEpochMillis;
            _lastStateChangeNanoTime = nowNanoTime;
            return;
        }
        // E-> We have moved to a new slot
        // :: First, finish off the previous second slot
        // Must use absolute millis-time for this calculation, as we may have crossed second boundaries, and thus
        // nanoTime is not usable (nanoTime and millis are not correlated).
        long millisIntoPreviousSecond = _lastStateChangeEpochMillis % 1000L;
        long nanosLeftFromPreviousSecond = (1000L - millisIntoPreviousSecond) * 1_000_000L;
        _currentSecond_NanosUtilization += nanosLeftFromPreviousSecond;

        // :: Okay, we have finished the previous slot, so leave it behind on the arrays
        // Clamp to 1.0f in case of clock skew where we've recorded more than 1 second of utilization
        _utilizationPerSecond[previousSlot] = Math.min(1f, (float) (_currentSecond_NanosUtilization / 1_000_000_000d));
        if (_hasCountsAndTimings) {
            _finishedTasksForSecond[previousSlot] = _currentSecond_FinishedTasksCount;
            _totalMillisForFinishedTasksForSecond[previousSlot] = (float) (_currentSecond_TotalNanosForFinishedTasks
                    / 1_000_000d);
        }

        // :: Now "in-paint" any slots in between previousSlot and currentSlot: They were active.
        // Not including currentSlot. Note prev and curr cannot be the same, as that case is handled above.
        int slot = (previousSlot + 1) % NUM_SECONDS;
        while (slot != currentSlot) { // This will stop before currentSlot
            _utilizationPerSecond[slot] = 1.0f;
            if (_hasCountsAndTimings) {
                _finishedTasksForSecond[slot] = 0;
                _totalMillisForFinishedTasksForSecond[slot] = 0.0f;
            }
            slot = (slot + 1) % NUM_SECONDS;
        }

        // Update the current slot
        // We were active until now in the new slot
        long millisIntoCurrentSecond = nowEpochMillis % 1000L;
        _currentSecond_NanosUtilization = millisIntoCurrentSecond * 1_000_000L;
        _currentSecond_FinishedTasksCount = 1;
        _currentSecond_TotalNanosForFinishedTasks = nowNanoTime - _lastStateChangeNanoTime;

        // Update for state change to idle
        _lastStateChangeEpochMillis = nowEpochMillis;
        _lastStateChangeNanoTime = nowNanoTime;
    }

    public synchronized void setActive() {
        if (_isActive) {
            throw new IllegalStateException("Thread is already active - must alternate setActive/setIdle calls.");
        }
        _isActive = true;

        long nowEpochMillis = _millisclock.millis();
        long nowNanoTime = _nanosClock.nanos();
        // :: Handle clock backwards jumps
        if (nowEpochMillis < _lastStateChangeEpochMillis) {
            // Clock has jumped backwards. We handle this by just setting the last state change to now, and return.
            // This means that the time between the old last state change and now is "lost", but there is no good way
            // to handle it anyway.
            _lastStateChangeEpochMillis = nowEpochMillis;
            _lastStateChangeNanoTime = nowNanoTime;
            return;
        }

        // :: Handle if a full hour has passed since last state change
        if (handleFullHour(nowEpochMillis, _lastStateChangeEpochMillis, false)) {
            // Full hour passed since last state change, so all slots are set to 0.0 (was idle), and zero completions.
            // No utilization for current second yet, as we're just starting to be active
            _currentSecond_NanosUtilization = 0;
            _currentSecond_FinishedTasksCount = 0;
            _currentSecond_TotalNanosForFinishedTasks = 0;

            // Update for state change to active
            _lastStateChangeEpochMillis = nowEpochMillis;
            _lastStateChangeNanoTime = nowNanoTime;
            return;
        }
        // E-> Full hour has NOT passed since last state change
        int previousSlot = slotFor(_lastStateChangeEpochMillis);
        int currentSlot = slotFor(nowEpochMillis);

        // ?: Are we still in the same slot?
        if (previousSlot == currentSlot) {
            // Yes, still in the same slot
            // Just update the last state change time
            _lastStateChangeEpochMillis = nowEpochMillis;
            _lastStateChangeNanoTime = nowNanoTime;
            return;
        }
        // E-> We have moved to a new slot
        // Leave the previous slot behind on the arrays
        // Clamp to 1.0f in case of clock skew where we've recorded more than 1 second of utilization
        _utilizationPerSecond[previousSlot] = Math.min(1f, (float) (_currentSecond_NanosUtilization / 1_000_000_000d));
        if (_hasCountsAndTimings) {
            _finishedTasksForSecond[previousSlot] = _currentSecond_FinishedTasksCount;
            _totalMillisForFinishedTasksForSecond[previousSlot] = (float) (_currentSecond_TotalNanosForFinishedTasks
                    / 1_000_000d);
        }

        // :: Now "in-paint" any slots in between previousSlot and currentSlot, as they were idle
        // Not including currentSlot. Note that they cannot be the same, as that case is handled above.
        int slot = (previousSlot + 1) % NUM_SECONDS;
        while (slot != currentSlot) { // This will stop before currentSlot
            _utilizationPerSecond[slot] = 0.0f;
            if (_hasCountsAndTimings) {
                _finishedTasksForSecond[slot] = 0;
                _totalMillisForFinishedTasksForSecond[slot] = 0.0f;
            }
            slot = (slot + 1) % NUM_SECONDS;
        }

        // Reset current slot tracking (starting fresh as active)
        _currentSecond_NanosUtilization = 0;
        _currentSecond_FinishedTasksCount = 0;
        _currentSecond_TotalNanosForFinishedTasks = 0L;

        // Update for state change to active
        _lastStateChangeEpochMillis = nowEpochMillis;
        _lastStateChangeNanoTime = nowNanoTime;
    }

    public synchronized UtilizationSnapshot getSnapshot(int secondsBack) {
        if (secondsBack < 1 || secondsBack > 3600) {
            throw new IllegalArgumentException("secondsBack must be between 1 and 3600, was: " + secondsBack);
        }
        /*
         * We need the array to be in a correct state. The logic here is that we do the updates as if we were changing
         * state right now, but we do not actually change any state: We perform the "in-painting" of slots that have
         * passed since the last state change (which is just setting, so idempotent, so no matter if done twice), and if
         * we've moved to a new slot, we finalize the previous slot (which also just setting, so idempotent - but we
         * make sure not to update the `_currentSecond_NanosUtilization` variable, just calculate locally).
         */
        long nowEpochMillis = _millisclock.millis();

        int previousSlot = slotFor(_lastStateChangeEpochMillis);
        int currentSlot = slotFor(nowEpochMillis);

        if (!handleFullHour(nowEpochMillis, _lastStateChangeEpochMillis, _isActive)) {
            // -> Full hour has NOT passed since last state change

            // ?: Are we still in the same slot?
            if (previousSlot != currentSlot) {
                // -> We have moved to a new slot

                if (_isActive) {
                    // -> We're currently ACTIVE - act as we're transitioning to idle.
                    long millisIntoPreviousSecond = _lastStateChangeEpochMillis % 1000L;
                    long nanosLeftFromPreviousSecond = (1000L - millisIntoPreviousSecond) * 1_000_000L;
                    long currentSecond_NanosUtilization = _currentSecond_NanosUtilization + nanosLeftFromPreviousSecond;

                    // Okay, we have finished the previous slot, so leave it behind on the arrays
                    _utilizationPerSecond[previousSlot] = Math.min(1f, (float) (currentSecond_NanosUtilization
                            / 1_000_000_000d));
                }
                else {
                    // -> We're currently IDLE - act as we're transitioning to active.
                    // Leave the previous slot behind on the arrays
                    _utilizationPerSecond[previousSlot] = Math.min(1f, (float) (_currentSecond_NanosUtilization
                            / 1_000_000_000d));
                }
                if (_hasCountsAndTimings) {
                    _finishedTasksForSecond[previousSlot] = _currentSecond_FinishedTasksCount;
                    _totalMillisForFinishedTasksForSecond[previousSlot] = (float) (_currentSecond_TotalNanosForFinishedTasks
                            / 1_000_000d);
                }

                // :: Now "in-paint" any slots in between previousSlot and currentSlot
                // Not including currentSlot. Note that they cannot be the same, as that case is handled above.
                int slot = (previousSlot + 1) % NUM_SECONDS;
                while (slot != currentSlot) { // This will stop before currentSlot
                    _utilizationPerSecond[slot] = _isActive ? 1.0f : 0.0f;
                    if (_hasCountsAndTimings) {
                        _finishedTasksForSecond[slot] = 0;
                        _totalMillisForFinishedTasksForSecond[slot] = 0L;
                    }
                    slot = (slot + 1) % NUM_SECONDS;
                }
            }
        }

        // Now, gather statistics for the interval
        double utilizationSum = 0.0;
        int finishedTasksSum = _finishedTasksForSecond != null ? 0 : -1;
        double totalMillisForFinishedTasksSum = _finishedTasksForSecond != null ? 0.0 : -1.0;

        for (int i = 0; i < secondsBack; i++) {
            int slot = (currentSlot - 1 - i + NUM_SECONDS) % NUM_SECONDS; // -1 as current slot is not yet finished
            utilizationSum += _utilizationPerSecond[slot];
            if (_hasCountsAndTimings) {
                finishedTasksSum += _finishedTasksForSecond[slot];
                totalMillisForFinishedTasksSum += _totalMillisForFinishedTasksForSecond[slot];
            }
        }

        final float utilization = (float) (utilizationSum / (double) secondsBack);
        final int finishedTasksCount = finishedTasksSum;
        final double totalMillisForFinishedTasks = totalMillisForFinishedTasksSum;

        return new UtilizationSnapshot() {
            @Override
            public float getUtilization() {
                return utilization;
            }

            @Override
            public int getFinishedTasksCount() {
                return finishedTasksCount;
            }

            @Override
            public double getTotalMillisForFinishedTasks() {
                return totalMillisForFinishedTasks;
            }
        };
    }

    // --------------------------

    private boolean handleFullHour(long nowMillis, long previousMillis, boolean active) {
        if (nowMillis - previousMillis < 3600_000L) {
            return false; // Not yet a full hour
        }
        // E-> Full hour passed since last state change
        // Reset all arrays according to state
        for (int i = 0; i < NUM_SECONDS; i++) {
            _utilizationPerSecond[i] = active ? 1.0f : 0.0f;
            if (_hasCountsAndTimings) {
                _finishedTasksForSecond[i] = 0;
                _totalMillisForFinishedTasksForSecond[i] = 0L;
            }
        }
        // Reset "current second"
        _currentSecond_NanosUtilization = 0;
        _currentSecond_FinishedTasksCount = 0;
        _currentSecond_TotalNanosForFinishedTasks = 0L;
        return true;
    }

    static int slotFor(long epochMillis) {
        // convert to seconds, then wrap to 0..3599
        long epochSeconds = epochMillis / 1000L;
        return Math.floorMod(epochSeconds, 3600); // handles negative too
    }
}
