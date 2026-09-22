package com.roombeat.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MonotonicClock], [SystemMonotonicClock], and [FakeMonotonicClock].
 */
class MonotonicClockTest {

    @Test
    fun testSystemMonotonicClockReturnsNonDecreasingValues() {
        val clock = SystemMonotonicClock

        val t1Nanos = clock.nowNanos()
        val t2Nanos = clock.nowNanos()
        val t3Nanos = clock.nowNanos()

        assertTrue("T2 ($t2Nanos) must be >= T1 ($t1Nanos)", t2Nanos >= t1Nanos)
        assertTrue("T3 ($t3Nanos) must be >= T2 ($t2Nanos)", t3Nanos >= t2Nanos)

        val t1Micros = clock.nowMicros()
        val t2Micros = clock.nowMicros()
        assertTrue("T2 micros ($t2Micros) must be >= T1 micros ($t1Micros)", t2Micros >= t1Micros)

        val t1Millis = clock.nowMillis()
        val t2Millis = clock.nowMillis()
        assertTrue("T2 millis ($t2Millis) must be >= T1 millis ($t1Millis)", t2Millis >= t1Millis)
    }

    @Test
    fun testSystemMonotonicClockConversions() {
        val clock = SystemMonotonicClock
        val nanos = clock.nowNanos()
        val micros = clock.nowMicros()
        val millis = clock.nowMillis()

        // Since calls happen in quick succession, micros should be roughly nanos / 1000
        val diffMicros = kotlin.math.abs(micros - (nanos / 1_000L))
        assertTrue("Micros should closely match nanos / 1000: diff=$diffMicros", diffMicros < 10_000L)

        val diffMillis = kotlin.math.abs(millis - (nanos / 1_000_000L))
        assertTrue("Millis should closely match nanos / 1,000,000: diff=$diffMillis", diffMillis < 100L)
    }

    @Test
    fun testFakeMonotonicClockManualAdvances() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)

        assertEquals(1_000_000_000L, fakeClock.nowNanos())
        assertEquals(1_000_000L, fakeClock.nowMicros())
        assertEquals(1_000L, fakeClock.nowMillis())

        fakeClock.advanceNanos(500_000L) // +500 microseconds
        assertEquals(1_000_500_000L, fakeClock.nowNanos())
        assertEquals(1_000_500L, fakeClock.nowMicros())
        assertEquals(1_000L, fakeClock.nowMillis())

        fakeClock.advanceMicros(2_500L) // +2.5 milliseconds
        assertEquals(1_003_000_000L, fakeClock.nowNanos())
        assertEquals(1_003_000L, fakeClock.nowMicros())
        assertEquals(1_003L, fakeClock.nowMillis())

        fakeClock.advanceMillis(100L) // +100 ms
        assertEquals(1_103_000_000L, fakeClock.nowNanos())
        assertEquals(1_103_000L, fakeClock.nowMicros())
        assertEquals(1_103L, fakeClock.nowMillis())
    }

    @Test
    fun testFakeMonotonicClockAutoIncrement() {
        val fakeClock = FakeMonotonicClock(
            initialNanos = 10_000_000L,
            autoIncrementNanos = 1_000L // 1 microsecond per call
        )

        val t0 = fakeClock.nowNanos()
        val t1 = fakeClock.nowNanos()
        val t2 = fakeClock.nowNanos()

        assertEquals(10_000_000L, t0)
        assertEquals(10_001_000L, t1)
        assertEquals(10_002_000L, t2)
        assertTrue(t1 > t0)
        assertTrue(t2 > t1)
    }

    @Test
    fun testFakeMonotonicClockSetNanos() {
        val fakeClock = FakeMonotonicClock(initialNanos = 5_000_000L)
        fakeClock.setNanos(8_000_000L)
        assertEquals(8_000_000L, fakeClock.nowNanos())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFakeMonotonicClockRejectsNegativeAdvance() {
        val fakeClock = FakeMonotonicClock()
        fakeClock.advanceNanos(-100L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFakeMonotonicClockRejectsBackwardSetNanos() {
        val fakeClock = FakeMonotonicClock(initialNanos = 10_000_000L)
        fakeClock.setNanos(5_000_000L)
    }
}
