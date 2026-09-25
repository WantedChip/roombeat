package com.roombeat.app.audio

import com.roombeat.app.sync.FakeMonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlaybackClockScheduler] verifying microsecond countdown calculation,
 * target reached asynchronous trigger, past targets handling, and cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackClockSchedulerTest {

    @Test
    fun testRemainingMicrosAndMillisCalculations() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L) // 1,000,000 us
        val scheduler = PlaybackClockScheduler(fakeClock)

        val targetUs = 1_350_000L // +350ms (350,000 us)

        assertEquals(350_000L, scheduler.remainingMicros(targetUs))
        assertEquals(350L, scheduler.remainingMillis(targetUs))
        assertFalse(scheduler.isTargetElapsed(targetUs))

        val telemetry = scheduler.getTelemetry(targetUs)
        assertEquals(targetUs, telemetry.targetTimeUs)
        assertEquals(350_000L, telemetry.remainingMicros)
        assertFalse(telemetry.isElapsed)

        // Advance clock by 150ms
        fakeClock.advanceMicros(150_000L)
        assertEquals(200_000L, scheduler.remainingMicros(targetUs))
        assertEquals(200L, scheduler.remainingMillis(targetUs))
        assertFalse(scheduler.isTargetElapsed(targetUs))

        // Advance clock to exact target
        fakeClock.advanceMicros(200_000L)
        assertEquals(0L, scheduler.remainingMicros(targetUs))
        assertEquals(0L, scheduler.remainingMillis(targetUs))
        assertTrue(scheduler.isTargetElapsed(targetUs))

        // Advance clock past target
        fakeClock.advanceMicros(50_000L)
        assertEquals(-50_000L, scheduler.remainingMicros(targetUs))
        assertEquals(-50L, scheduler.remainingMillis(targetUs))
        assertTrue(scheduler.isTargetElapsed(targetUs))
    }

    @Test
    fun testPastTargetTriggersImmediately() = runTest {
        val fakeClock = FakeMonotonicClock(initialNanos = 2_000_000_000L) // 2,000,000 us
        val scheduler = PlaybackClockScheduler(fakeClock)

        val pastTargetUs = 1_500_000L // 500ms in the past
        var triggered = false

        scheduler.awaitTarget(pastTargetUs) {
            triggered = true
        }

        assertTrue("Past target must trigger immediately without suspending", triggered)
        assertTrue(scheduler.isTargetReached.value)
    }

    @Test
    fun testAwaitTargetTriggersWhenClockReachesDeadline() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L) // 1,000,000 us
        val scheduler = PlaybackClockScheduler(fakeClock)

        val targetUs = 1_350_000L // +350ms
        var triggered = false

        testScope.launch {
            scheduler.awaitTarget(targetUs) {
                triggered = true
            }
        }

        testScope.runCurrent()
        assertFalse("Should not trigger before clock advances", triggered)

        // Advance clock and virtual time to target
        fakeClock.advanceMicros(350_000L)
        testScope.advanceTimeBy(350L)
        testScope.runCurrent()

        assertTrue("Trigger should execute once target timestamp arrives", triggered)
        assertTrue(scheduler.isTargetReached.value)
    }

    @Test
    fun testScheduleTriggerHelperReturnsCancellableJob() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val scheduler = PlaybackClockScheduler(fakeClock)

        val targetUs = 2_000_000L // +1000ms
        var triggered = false

        val job = scheduler.scheduleTrigger(targetUs, testScope) {
            triggered = true
        }

        assertNotNull(job)
        assertTrue(job.isActive)

        // Cancel job before clock reaches target
        job.cancelAndJoin()
        assertFalse(job.isActive)

        // Advance clock past target
        fakeClock.advanceMicros(2_000_000L)
        testScope.advanceTimeBy(2_000L)
        testScope.runCurrent()

        assertFalse("Cancelled job must not invoke trigger callback", triggered)
    }

    @Test
    fun testCoarseAndFineTimingTransitions() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 10_000_000_000L) // 10,000,000 us
        val scheduler = PlaybackClockScheduler(fakeClock)

        val targetUs = 10_010_000L // +10ms (10,000 us)
        var triggerCount = 0

        testScope.launch {
            scheduler.awaitTarget(targetUs) {
                triggerCount++
            }
        }

        testScope.runCurrent()
        assertEquals(0, triggerCount)

        fakeClock.advanceMicros(5_000L) // halfway: 5ms remaining
        testScope.advanceTimeBy(5L)
        testScope.runCurrent()
        assertEquals(0, triggerCount)

        fakeClock.advanceMicros(5_000L) // exact deadline
        testScope.advanceTimeBy(5L)
        testScope.runCurrent()
        assertEquals(1, triggerCount)
    }
}
