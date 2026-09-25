package com.roombeat.app.audio

import com.roombeat.app.sync.MonotonicClock
import com.roombeat.app.sync.SystemMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/**
 * Diagnostics snapshot container for a scheduled presentation timestamp target.
 */
data class ClockScheduleTelemetry(
    val targetTimeUs: Long,
    val remainingMicros: Long,
    val isElapsed: Boolean
)

/**
 * Target timestamp presentation scheduler and countdown manager for RoomBeat.
 *
 * Coordinates precise countdowns to microsecond presentation timestamps ($T_{target}$).
 * Uses coarse coroutine delays for energy efficiency during long lead times (e.g. 350ms lead time),
 * followed by fine-grained microsecond alignment as the presentation deadline approaches.
 *
 * Decoupled from system time via [MonotonicClock] for 100% deterministic testability with [FakeMonotonicClock].
 */
class PlaybackClockScheduler(
    val clock: MonotonicClock = SystemMonotonicClock
) {
    companion object {
        const val COARSE_DELAY_THRESHOLD_US = 5_000L // 5ms
        const val COARSE_DELAY_HEADROOM_MS = 2L      // Wake up 2ms before deadline
        const val FINE_SPIN_THRESHOLD_US = 500L      // 500us
    }

    private val _activeTargetUs = MutableStateFlow<Long?>(null)
    val activeTargetUs: StateFlow<Long?> = _activeTargetUs.asStateFlow()

    private val _isTargetReached = MutableStateFlow(false)
    val isTargetReached: StateFlow<Boolean> = _isTargetReached.asStateFlow()

    /**
     * Calculates the time remaining until [targetTimeUs] in microseconds.
     * Returns negative values if the target timestamp has already elapsed.
     */
    fun remainingMicros(targetTimeUs: Long, nowMicros: Long = clock.nowMicros()): Long {
        return targetTimeUs - nowMicros
    }

    /**
     * Calculates the time remaining until [targetTimeUs] in milliseconds.
     */
    fun remainingMillis(targetTimeUs: Long, nowMicros: Long = clock.nowMicros()): Long {
        return (targetTimeUs - nowMicros) / 1_000L
    }

    /**
     * Returns true if [targetTimeUs] has arrived or already elapsed relative to [nowMicros].
     */
    fun isTargetElapsed(targetTimeUs: Long, nowMicros: Long = clock.nowMicros()): Boolean {
        return nowMicros >= targetTimeUs
    }

    /**
     * Returns a snapshot of telemetry metrics for the specified [targetTimeUs].
     */
    fun getTelemetry(targetTimeUs: Long): ClockScheduleTelemetry {
        val now = clock.nowMicros()
        val remaining = remainingMicros(targetTimeUs, now)
        return ClockScheduleTelemetry(
            targetTimeUs = targetTimeUs,
            remainingMicros = remaining,
            isElapsed = remaining <= 0L
        )
    }

    /**
     * Suspends until the monotonic clock reaches [targetTimeUs].
     *
     * If [targetTimeUs] has already elapsed, triggers [onTrigger] immediately without suspending.
     * If the coroutine is cancelled while waiting, cancels cleanly without invoking [onTrigger].
     */
    suspend fun awaitTarget(
        targetTimeUs: Long,
        onTrigger: (suspend () -> Unit)? = null
    ) {
        _activeTargetUs.value = targetTimeUs
        _isTargetReached.value = false

        try {
            while (coroutineContext.isActive) {
                val now = clock.nowMicros()
                val remUs = targetTimeUs - now
                if (remUs <= 0L) {
                    break
                }

                if (remUs > COARSE_DELAY_THRESHOLD_US) {
                    val delayMs = (remUs / 1_000L) - COARSE_DELAY_HEADROOM_MS
                    if (delayMs > 0L) {
                        delay(delayMs)
                    } else {
                        delay(1L)
                    }
                } else if (remUs >= 1_000L) {
                    delay(remUs / 1_000L)
                } else {
                    // Sub-millisecond residual (< 1000 us)
                    if (clock is SystemMonotonicClock) {
                        while (clock.nowMicros() < targetTimeUs && coroutineContext.isActive) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                Thread.onSpinWait()
                            } else {
                                Thread.yield()
                            }
                        }
                        break
                    } else {
                        // In test/mock clock environments, yield/delay without infinite spinning
                        if (clock.nowMicros() >= targetTimeUs) {
                            break
                        }
                        delay(1L)
                    }
                }
            }

            _isTargetReached.value = true
            onTrigger?.invoke()
        } finally {
            if (_activeTargetUs.value == targetTimeUs && !_isTargetReached.value) {
                // Cancelled before completion
                _activeTargetUs.value = null
            }
        }
    }

    /**
     * Asynchronously schedules an execution trigger at [targetTimeUs] within the given [scope].
     * Returns the [Job] which can be cancelled if the session is aborted.
     */
    fun scheduleTrigger(
        targetTimeUs: Long,
        scope: CoroutineScope,
        onTrigger: suspend () -> Unit
    ): Job {
        return scope.launch {
            awaitTarget(targetTimeUs, onTrigger)
        }
    }
}
