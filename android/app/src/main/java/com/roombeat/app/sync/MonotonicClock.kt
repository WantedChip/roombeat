package com.roombeat.app.sync

/**
 * High-precision monotonic time source interface for RoomBeat acoustic calibration,
 * NTP probe exchanges, and audio presentation scheduling.
 *
 * All timestamps produced by this clock are guaranteed to be immune to system wall-clock
 * adjustments (such as NTP wall-clock updates, timezone transitions, or manual clock edits).
 */
interface MonotonicClock {

    /**
     * Current time in nanoseconds from the monotonic timeline.
     */
    fun nowNanos(): Long

    /**
     * Current time in microseconds from the monotonic timeline.
     * (1 microsecond = 1,000 nanoseconds).
     */
    fun nowMicros(): Long = nowNanos() / 1_000L

    /**
     * Current time in milliseconds from the monotonic timeline.
     * (1 millisecond = 1,000,000 nanoseconds).
     */
    fun nowMillis(): Long = nowNanos() / 1_000_000L
}

/**
 * Production implementation of [MonotonicClock] backed by [System.nanoTime],
 * which wraps Linux `clock_gettime(CLOCK_MONOTONIC)` on Android.
 */
object SystemMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

/**
 * Deterministic, controllable in-memory clock double for unit and integration tests.
 *
 * Supports manual discrete time advances as well as automatic incrementing on every query.
 */
class FakeMonotonicClock(
    initialNanos: Long = 1_000_000_000L,
    private val autoIncrementNanos: Long = 0L
) : MonotonicClock {

    private var currentNanos: Long = initialNanos

    override fun nowNanos(): Long {
        val now = currentNanos
        if (autoIncrementNanos > 0L) {
            currentNanos += autoIncrementNanos
        }
        return now
    }

    /**
     * Advances the clock by [deltaNanos]. Must be non-negative.
     */
    fun advanceNanos(deltaNanos: Long) {
        require(deltaNanos >= 0L) { "Cannot advance monotonic clock backwards: deltaNanos=$deltaNanos" }
        currentNanos += deltaNanos
    }

    /**
     * Advances the clock by [deltaMicros] microseconds. Must be non-negative.
     */
    fun advanceMicros(deltaMicros: Long) {
        advanceNanos(deltaMicros * 1_000L)
    }

    /**
     * Advances the clock by [deltaMillis] milliseconds. Must be non-negative.
     */
    fun advanceMillis(deltaMillis: Long) {
        advanceNanos(deltaMillis * 1_000_000L)
    }

    /**
     * Sets the clock to an absolute [nanos] value. Must be greater than or equal to current value.
     */
    fun setNanos(nanos: Long) {
        require(nanos >= currentNanos) { "Cannot set monotonic clock backwards: current=$currentNanos, target=$nanos" }
        currentNanos = nanos
    }
}
