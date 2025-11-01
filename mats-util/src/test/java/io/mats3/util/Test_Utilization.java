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

import java.time.Instant;
import java.util.Random;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import io.mats3.util.Utilization.MillisClock;
import io.mats3.util.Utilization.NanosClock;
import io.mats3.util.Utilization.UtilizationSnapshot;

/**
 * Tests the {@link Utilization} class.
 * <p>
 * A bunch of these tests are created by AI ("Grok Code Fast"), aiming to check all corners, checked and adjusted by
 * hand.
 *
 * @author Endre Stølsvik 2025-11-01 14:20 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_Utilization {

    private MockClock mockClock = new MockClock();

    @Before
    public void setup() {
        mockClock.setBaseTimeFromIso("2025-01-01T10:15:30.000Z");
    }

    @Test
    public void testMockClock() {
        MockClock clock = new MockClock();
        clock.setBaseTimeFromIso("2025-01-01T10:15:30Z");
        Assert.assertEquals(1735726530000L, clock.millis());

        long baseNanos = clock.nanos();

        clock.advanceMillis(500);
        Assert.assertEquals(1735726530500L, clock.millis());
        Assert.assertEquals(baseNanos + 500_000_000L, clock.nanos());

        clock.advanceNanos(250_000L);
        Assert.assertEquals(1735726530500L, clock.millis());
        Assert.assertEquals(baseNanos + 500_250_000L, clock.nanos());

        clock.advanceNanos(800_000L);
        Assert.assertEquals(1735726530501L, clock.millis());
        Assert.assertEquals(baseNanos + 501_050_000L, clock.nanos());
    }

    @Test
    public void testHalfOf15_7500ms7500ms() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        UtilizationSnapshot snapshot = utilz.getSnapshot(100);
        Assert.assertEquals(0d, snapshot.getUtilization(), 0d);
        Assert.assertEquals(0, snapshot.getFinishedTasksCount());
        Assert.assertEquals(0d, snapshot.getTotalMillisForFinishedTasks(), 0d);

        utilz.setActive();
        mockClock.advanceMillis(7500);
        utilz.setIdle();
        mockClock.advanceMillis(7500);
        snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0.5d, snapshot.getUtilization(), 0.0d);
        Assert.assertEquals(1, snapshot.getFinishedTasksCount(), 0d);
        Assert.assertEquals(7500, snapshot.getTotalMillisForFinishedTasks(), 0d);
        Assert.assertEquals(7500, snapshot.getAverageMillisPerFinishedTask(), 0d);
    }

    @Test
    public void testHalfOf15_75x_100ms100ms() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);

        for (int i = 0; i < 75; i++) {
            utilz.setActive();
            mockClock.advanceMillis(100);
            utilz.setIdle();
            mockClock.advanceMillis(100);
        }

        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0.5d, snapshot.getUtilization(), 0d);
        Assert.assertEquals(75, snapshot.getFinishedTasksCount(), 0d);
        Assert.assertEquals(7500, snapshot.getTotalMillisForFinishedTasks(), 0d);
        Assert.assertEquals(100, snapshot.getAverageMillisPerFinishedTask(), 0d);
    }

    @Test
    public void testHalfOf15_30kx_250kns250kns() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);

        for (int i = 0; i < 30_000; i++) {
            utilz.setActive();
            mockClock.advanceNanos(250_000);
            utilz.setIdle();
            mockClock.advanceNanos(250_000);
        }

        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0.5d, snapshot.getUtilization(), 0.0d);
        Assert.assertEquals(30_000, snapshot.getFinishedTasksCount(), 0d);
        Assert.assertEquals(7500, snapshot.getTotalMillisForFinishedTasks(), 0d);
        Assert.assertEquals(0.25, snapshot.getAverageMillisPerFinishedTask(), 0d);
    }

    // A. Initialization and Basic State
    @Test
    public void testInitialSnapshot() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0.0, snapshot.getUtilization(), 0.0);
        Assert.assertEquals(0, snapshot.getFinishedTasksCount());
        Assert.assertEquals(0d, snapshot.getTotalMillisForFinishedTasks(), 0d);
        Assert.assertEquals(0d, snapshot.getAverageMillisPerFinishedTask(), 0d);
    }

    @Test
    public void testFirstStateChange() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceNanos(100_000); // Small time
        utilz.setIdle();
        mockClock.advanceMillis(1000); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(1);
        Assert.assertEquals(0.0001d, snapshot.getUtilization(), 1e-7);
        Assert.assertEquals(1, snapshot.getFinishedTasksCount());
        Assert.assertEquals(0.1d, snapshot.getTotalMillisForFinishedTasks(), 1e-7);
    }

    @Test
    public void testIdleToIdle_FirstTime() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setIdle(); // Is allowed due to accepting an initial set to idle, even though default is idle
    }

    @Test(expected = IllegalStateException.class)
    public void testIdleToIdle_SecondTime() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setIdle();
        utilz.setIdle(); // Not allowed a second time.
    }

    @Test(expected = IllegalStateException.class)
    public void testInvalidInitialActive() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        utilz.setActive(); // Should throw
    }

    @Test
    public void testSnapshotOnFreshInstance() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        UtilizationSnapshot snapshot15 = utilz.getSnapshot(15);
        UtilizationSnapshot snapshot3600 = utilz.getSnapshot(3600);
        Assert.assertEquals(0.0, snapshot15.getUtilization(), 0.0);
        Assert.assertEquals(0.0, snapshot3600.getUtilization(), 0.0);
    }

    // B. Time Precision and Sub-Second Handling
    @Test
    public void testSubMillisecondTasks() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceNanos(500); // <1ms
        utilz.setIdle();
        mockClock.advanceMillis(1000); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(1, snapshot.getFinishedTasksCount());
        Assert.assertEquals(0.0005d, snapshot.getTotalMillisForFinishedTasks(), 0.000001d);
    }

    @Test
    public void testPartialSecondUtilization() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(500); // Half second
        utilz.setIdle();
        mockClock.advanceMillis(500); // Half second idle
        UtilizationSnapshot snapshot = utilz.getSnapshot(1);
        Assert.assertEquals(0.5, snapshot.getUtilization(), 0d);
    }

    @Test
    public void testMillisNanosDrift() {
        mockClock.advanceNanos(1_500_000); // Crosses 1ms
        Assert.assertEquals(1, mockClock.millis() % 1000); // Verify carry-over
    }

    @Test
    public void testVeryShortIntervals() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceNanos(1); // Tiny
        utilz.setIdle();
        mockClock.advanceMillis(1000); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(1, snapshot.getFinishedTasksCount());
    }

    @Test
    public void testLongTasksSpanningMillis() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(1500); // >1s
        utilz.setIdle();
        mockClock.advanceMillis(500); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(2);
        Assert.assertEquals(.75, snapshot.getUtilization(), 0.0);
    }

    @Test
    public void testPrecisionLossInAverages() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceNanos(7); // Odd number
        utilz.setIdle();
        mockClock.advanceMillis(1000); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0.000007d, snapshot.getAverageMillisPerFinishedTask(), 1e-10);
    }

    // C. Slot Boundaries and Wraparound
    @Test
    public void testSlotWraparound() {
        mockClock.setBaseTimeFromIso("2025-01-01T10:15:29.999Z"); // Slot 3599
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(1001); // To slot 0
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertTrue(snapshot.getUtilization() > 0); // Data in wrapped slots
    }

    @Test
    public void testCrossingSecondBoundaries() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(999); // Almost end of second
        utilz.setIdle();
        mockClock.advanceMillis(1); // Advance to next second
        UtilizationSnapshot snapshot = utilz.getSnapshot(1);
        Assert.assertEquals(.999, snapshot.getUtilization(), 1e-7); // Full second
    }

    @Test
    public void testMultipleSlotsInOneTransition_1() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(3000); // 3 seconds
        // Not setting to idle yet
        UtilizationSnapshot snapshot = utilz.getSnapshot(6);
        Assert.assertEquals(0.5, snapshot.getUtilization(), 0.0); // Half of 6 slots active
        utilz.setIdle();
        snapshot = utilz.getSnapshot(6);
        Assert.assertEquals(0.5, snapshot.getUtilization(), 0.0); // Half of 6 slots active
    }

    @Test
    public void testMultipleSlotsInOneTransition_2() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(3000); // 3 seconds
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(6);
        Assert.assertEquals(0.5, snapshot.getUtilization(), 0.0); // Half of 6 slots active
    }

    @Test
    public void testSlotResetOnFullHour() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(3_600_000); // Exactly 1 hour
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(1.0, snapshot.getUtilization(), 0.0); // All slots 1.0
    }

    @Test
    public void testWraparoundWithTasks() {
        mockClock.setBaseTimeFromIso("2025-01-01T10:15:29.999Z");
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(1001);
        utilz.setIdle();
        mockClock.advanceMillis(1000); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(1, snapshot.getFinishedTasksCount()); // Task counted across wrap
        Assert.assertEquals(1001d, snapshot.getTotalMillisForFinishedTasks(), 0d);
        Assert.assertEquals(1001d, snapshot.getAverageMillisPerFinishedTask(), 0d);
    }

    // D. Clock Jumps and Anomalies
    @Test
    public void testClockBackwardsJumpSmall() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock._millis -= 1000; // Jump back 1s
        utilz.setIdle(); // Should reset without crash
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0, snapshot.getFinishedTasksCount()); // Lost time ignored
    }

    @Test
    public void testClockBackwardsJumpLarge() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock._millis -= 3_600_000; // Jump back 1h
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0, snapshot.getFinishedTasksCount());
    }

    @Test
    public void testClockForwardsJumpSmall() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock._millis += 1_800_000; // Jump forward 30min
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(0.5, snapshot.getUtilization(), 0.0); // Half of hour active
    }

    @Test
    public void testClockForwardsJumpEqualHour() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock._millis += 3_600_000;
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(1.0, snapshot.getUtilization(), 0.0);
    }

    @Test
    public void testClockForwardsJumpLarge() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock._millis += 7_200_000; // 2 hours
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(1.0, snapshot.getUtilization(), 0.0); // Skewed but no crash
    }

    @Test
    public void testClockJumpDuringQuery() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(1000);
        mockClock._millis += 3_600_000; // Jump during getSnapshot
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(1.0, snapshot.getUtilization(), 0.0); // Handles in query
    }

    // E. State Transitions and Current State
    @Test
    public void testRapidTransitions() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        for (int i = 0; i < 10; i++) {
            utilz.setActive();
            mockClock.advanceNanos(100_000);
            utilz.setIdle();
        }
        mockClock.advanceMillis(1000); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(10, snapshot.getFinishedTasksCount());
    }

    @Test
    public void testTransitionAcrossSlots() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(1000); // To next slot
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(1);
        Assert.assertEquals(1.0, snapshot.getUtilization(), 0.0);
    }

    @Test
    public void testQueryWhileActive() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(1500); // 1.5 sec: 1 slot full, 1 slot half
        UtilizationSnapshot snapshot = utilz.getSnapshot(1);
        Assert.assertEquals(1.0, snapshot.getUtilization(), 0.0);
    }

    @Test
    public void testQueryWhileIdle() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(500);
        utilz.setIdle();
        mockClock.advanceMillis(500); // Total 1s: half active, half idle
        UtilizationSnapshot snapshot = utilz.getSnapshot(1);
        Assert.assertEquals(0.5, snapshot.getUtilization(), 0.01);
    }

    @Test
    public void testUnfinishedTaskInQuery() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(500);
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0, snapshot.getFinishedTasksCount()); // Ongoing not included
    }

    // F. Task Statistics and Counts
    @Test
    public void testZeroTasks() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        mockClock.advanceMillis(1000);
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(0d, snapshot.getAverageMillisPerFinishedTask(), 0d);
    }

    @Test
    public void testSingleTask() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceNanos(1_000_000);
        utilz.setIdle();
        mockClock.advanceMillis(1000); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(1, snapshot.getFinishedTasksCount());
        Assert.assertEquals(1d, snapshot.getAverageMillisPerFinishedTask(), 0d);
    }

    @Test
    public void testMultipleTasksInSameSecond() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        for (int i = 0; i < 10; i++) {
            utilz.setActive();
            mockClock.advanceNanos(100_000);
            utilz.setIdle();
        }
        mockClock.advanceMillis(1000);
        UtilizationSnapshot snapshot = utilz.getSnapshot(1);
        Assert.assertEquals(10, snapshot.getFinishedTasksCount());
    }

    @Test
    public void testTasksSpanningSlots() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(1500); // Spans slots
        utilz.setIdle();
        mockClock.advanceMillis(500); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(2);
        Assert.assertEquals(0.75, snapshot.getUtilization(), 0.0);
        Assert.assertEquals(1, snapshot.getFinishedTasksCount());
        Assert.assertEquals(1500, snapshot.getTotalMillisForFinishedTasks(), 0d);
        Assert.assertEquals(1500, snapshot.getAverageMillisPerFinishedTask(), 0d);
    }

    // G. Query Intervals and Validation
    @Test
    public void testMinInterval() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        UtilizationSnapshot snapshot = utilz.getSnapshot(1);
        Assert.assertEquals(0.0, snapshot.getUtilization(), 0.0);
    }

    @Test
    public void testMaxInterval() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(0.0, snapshot.getUtilization(), 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidIntervalTooSmall() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.getSnapshot(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidIntervalTooLarge() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.getSnapshot(3601);
    }

    // H. Full Hour and Reset Scenarios
    @Test
    public void testFullHourDuringActive() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(3_600_001); // >1h
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(1.0, snapshot.getUtilization(), 0.0);
    }

    @Test
    public void testFullHourDuringIdle() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setIdle(); // Wait, initial is idle, but to test: set active, idle, then jump
        utilz.setActive();
        utilz.setIdle();
        mockClock.advanceMillis(3_600_001);
        utilz.setActive();
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(0.0, snapshot.getUtilization(), 0.0);
    }

    @Test
    public void testFullHourDuringQuery() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        utilz.setIdle();
        mockClock.advanceMillis(3_600_001);
        UtilizationSnapshot snapshot = utilz.getSnapshot(3600);
        Assert.assertEquals(0.0, snapshot.getUtilization(), 0.0); // Reset in query
    }

    // I. Extreme Values and Edge Inputs
    @Test
    public void testNegativeEpochTime() {
        mockClock._millis = -1000;
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(1000);
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertTrue(snapshot.getUtilization() > 0); // Handles negative slots
    }

    @Test
    public void testVeryLargeEpochTime() {
        mockClock._millis = Long.MAX_VALUE - 1000;
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        utilz.setActive();
        mockClock.advanceMillis(500);
        utilz.setIdle();
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertTrue(snapshot.getUtilization() > 0); // No overflow crash
    }

    @Test
    public void testHighFrequencyOperations() {
        Utilization utilz = Utilization.createWithTiming(mockClock, mockClock);
        for (int i = 0; i < 100_000; i++) {
            utilz.setActive();
            mockClock.advanceNanos(1);
            utilz.setIdle();
        }
        mockClock.advanceMillis(1000); // To next slot
        UtilizationSnapshot snapshot = utilz.getSnapshot(15);
        Assert.assertEquals(100_000, snapshot.getFinishedTasksCount()); // Stress test
    }

    /**
     * A mock clock that implements both {@link MillisClock} and {@link NanosClock}, with methods to set the base time
     * and advance time in both milliseconds and nanoseconds. Note that millis and nanos are "independent" (as
     * System.currentTimeMillis() and System.nanoTime() are), but in this implementation advancing one will affect the
     * other.
     */
    private static class MockClock implements MillisClock, NanosClock {
        private long _millis;
        private long _baseNanos;
        private long _nanos;

        private long _remainderNanosBeforeMillisIncrement = 0;

        /**
         * Sets the time based on an ISO 8601 datetime timestamp. Accepts formats like: - "2025-01-01T10:15:30Z" (UTC) -
         * "2025-01-01T10:15:30.123Z" (with milliseconds) - "2025-01-01T10:15:30+01:00" (with timezone offset)
         *
         * @param isoTimestamp
         *            ISO 8601 formatted datetime string
         * @return this MockMillisClock instance for method chaining
         */
        public MockClock setBaseTimeFromIso(String isoTimestamp) {
            Instant instant = Instant.parse(isoTimestamp);
            _millis = instant.toEpochMilli();
            _baseNanos = new Random().nextLong(0, 100_000_000_000l);
            _nanos = _baseNanos;
            return this;
        }

        public MockClock advanceMillis(long delta) {
            _millis += delta;
            _nanos += delta * 1_000_000L;
            return this;
        }

        public MockClock advanceNanos(long delta) {
            _nanos += delta;
            _millis += delta / 1_000_000L;
            _remainderNanosBeforeMillisIncrement += delta % 1_000_000L;
            if (_remainderNanosBeforeMillisIncrement >= 1_000_000L) {
                _millis += 1;
                _remainderNanosBeforeMillisIncrement -= 1_000_000L;
            }
            return this;
        }

        @Override
        public long millis() {
            return _millis;
        }

        @Override
        public long nanos() {
            return _nanos;
        }
    }
}
