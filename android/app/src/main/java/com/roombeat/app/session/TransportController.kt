package com.roombeat.app.session

import android.util.Log
import com.roombeat.app.audio.PlaybackClockScheduler
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.source.local.LocalAudioDecoder
import com.roombeat.app.sync.MonotonicClock
import com.roombeat.app.sync.SystemMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-level transport statuses for RoomBeat playback.
 */
enum class TransportStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    SYNCED_TO_HOST
}

/**
 * Immutable transport state snapshot exposed to UI components and session monitors.
 */
data class TransportState(
    val status: TransportStatus = TransportStatus.IDLE,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val trackTitle: String = "",
    val artist: String = "",
    val isHost: Boolean = true
) {
    val isPlaying: Boolean get() = status == TransportStatus.PLAYING || status == TransportStatus.SYNCED_TO_HOST
    val isPaused: Boolean get() = status == TransportStatus.PAUSED
    val isStopped: Boolean get() = status == TransportStatus.STOPPED
    val isBuffering: Boolean get() = status == TransportStatus.BUFFERING
}

/**
 * Master transport state manager and distributor coordinating play, pause, seek, and stop
 * across host and peer nodes with microsecond presentation timestamps.
 *
 * Implements:
 * 1. Host Pause Flow: Calculates $T_{exec} = T_{now} + 100,000\mu\text{s}$, broadcasts [RoomBeatPacket.SessionPause],
 *    and schedules pause at $T_{exec}$ via [PlaybackClockScheduler].
 * 2. Peer Pause Flow: On receiving [RoomBeatPacket.SessionPause], translates $T_{exec}$ to local clock domain
 *    ($T_{exec, local} = T_{exec, host} + \theta$) and schedules pause at $T_{exec, local}$.
 * 3. Host Seek Flow: Pauses decoder, calculates new presentation time ($T_{target} = T_{now} + 350,000\mu\text{s}$),
 *    flushes host jitter buffer via [AudioJitterBuffer.flushAndSeek], broadcasts [RoomBeatPacket.SessionSeek],
 *    seeks decoder to requested offset, and resumes streaming at $T_{target}$.
 * 4. Peer Seek Flow: On receiving [RoomBeatPacket.SessionSeek], translates $T_{target}$ to local clock domain
 *    ($T_{target, local} = T_{target, host} + \theta$), flushes local jitter buffer, and awaits $T_{target, local}$.
 * 5. State Tracking: Exposes real-time [TransportState] via [StateFlow].
 */
class TransportController(
    val isHost: Boolean,
    val coordinator: PlaybackCoordinator,
    val scheduler: PlaybackClockScheduler = PlaybackClockScheduler(),
    val clock: MonotonicClock = SystemMonotonicClock,
    val jitterBuffer: AudioJitterBuffer = coordinator.jitterBuffer,
    var decoder: LocalAudioDecoder? = null,
    var transport: PeerSessionTransport? = coordinator.transport,
    var sessionManager: PeerSessionManager? = coordinator.sessionManager,
    var clockOffsetMicros: Long = coordinator.clockOffsetMicros,
    var clockOffsetProvider: (() -> Long)? = coordinator.clockOffsetProvider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : AutoCloseable {

    companion object {
        private const val TAG = "TransportController"

        /**
         * 100ms execution lead time for synchronous pause and play commands across all nodes.
         */
        const val COMMAND_EXECUTION_DELAY_MICROS = 100_000L

        /**
         * 350ms lead time for seek operations allowing full decoder pipeline flush and network re-buffering.
         */
        const val SEEK_LEAD_TIME_MICROS = 350_000L

        /**
         * Periodic position ticker update interval in milliseconds.
         */
        private const val POSITION_TICK_INTERVAL_MS = 100L
    }

    private val _state = MutableStateFlow(
        TransportState(
            status = TransportStatus.IDLE,
            isHost = isHost
        )
    )
    val state: StateFlow<TransportState> = _state.asStateFlow()

    private var scheduledJob: Job? = null
    private var tickerJob: Job? = null

    /**
     * Resolves the current clock offset $\theta$ in microseconds to convert host timestamps
     * into the local monotonic clock domain.
     */
    val currentClockOffsetMicros: Long
        get() = clockOffsetProvider?.invoke() ?: coordinator.currentClockOffsetMicros

    /**
     * Updates track metadata displayed in the transport bar.
     */
    fun setTrack(title: String, artist: String, durationMs: Long) {
        _state.update {
            it.copy(
                trackTitle = title,
                artist = artist,
                durationMs = durationMs
            )
        }
    }

    /**
     * Manually updates the current playback position in milliseconds.
     */
    fun updatePosition(positionMs: Long) {
        _state.update {
            it.copy(currentPositionMs = positionMs.coerceIn(0L, it.durationMs.coerceAtLeast(0L)))
        }
    }

    // ========================================================================
    // Host Transport Triggers
    // ========================================================================

    /**
     * Host Play Flow:
     * Schedules synchronized playback initiation or resumption at $T_{exec} = T_{now} + 100\text{ms}$.
     */
    fun play() {
        if (!isHost) {
            Log.w(TAG, "play() called on peer node; only host can initiate play")
            return
        }

        scheduledJob?.cancel()

        val nowUs = clock.nowMicros()
        val tExec = nowUs + COMMAND_EXECUTION_DELAY_MICROS

        val mediaId = _state.value.trackTitle.ifEmpty { "roombeat_track" }
        val packet = RoomBeatPacket.SessionStart(
            mediaId = mediaId,
            targetPresentationTime = tExec,
            sourceType = "LOCAL_FILE"
        )
        broadcastControlPacket(packet)

        jitterBuffer.targetStartTimeUs = tExec
        _state.update { it.copy(status = TransportStatus.BUFFERING) }

        scheduledJob = scheduler.scheduleTrigger(tExec, scope) {
            decoder?.resume()
            coordinator.resume()
            _state.update { it.copy(status = TransportStatus.PLAYING) }
            startPositionTicker()
        }
    }

    /**
     * Host Pause Flow:
     * Calculates execution time: $T_{exec} = T_{now} + 100,000\mu\text{s}$, broadcasts [RoomBeatPacket.SessionPause],
     * and schedules pause at $T_{exec}$ via [PlaybackClockScheduler].
     */
    fun pause() {
        if (!isHost) {
            Log.w(TAG, "pause() called on peer node; only host can initiate pause")
            return
        }

        scheduledJob?.cancel()

        val nowUs = clock.nowMicros()
        val tExec = nowUs + COMMAND_EXECUTION_DELAY_MICROS

        val packet = RoomBeatPacket.SessionPause(atPresentationTime = tExec)
        broadcastControlPacket(packet)

        scheduledJob = scheduler.scheduleTrigger(tExec, scope) {
            stopPositionTicker()
            decoder?.pause()
            coordinator.handleSessionPause(packet)
            _state.update { it.copy(status = TransportStatus.PAUSED) }
        }
    }

    /**
     * Host Seek Flow:
     * 1. Pauses/flushes active decoder.
     * 2. Calculates target presentation time: $T_{target} = T_{now} + 350,000\mu\text{s}$.
     * 3. Flushes host jitter buffer via [AudioJitterBuffer.flushAndSeek].
     * 4. Broadcasts [RoomBeatPacket.SessionSeek] to all peers.
     * 5. Seeks decoder to requested position.
     * 6. Resumes streaming fresh chunks starting from new seek position.
     */
    fun seekTo(positionMs: Long) {
        if (!isHost) {
            Log.w(TAG, "seekTo() called on peer node; only host can initiate seek")
            return
        }

        scheduledJob?.cancel()
        stopPositionTicker()

        val positionUs = positionMs.coerceAtLeast(0L) * 1_000L
        val nowUs = clock.nowMicros()
        val tTarget = nowUs + SEEK_LEAD_TIME_MICROS

        // 1. Pause and seek active decoder
        decoder?.pause()
        decoder?.seekTo(positionUs)

        // 2. Flush host jitter buffer
        val newSeq = 0L
        jitterBuffer.flushAndSeek(newSeq, tTarget)

        // 3. Broadcast SESSION_SEEK packet
        val packet = RoomBeatPacket.SessionSeek(
            positionMs = positionMs,
            targetPresentationTime = tTarget
        )
        broadcastControlPacket(packet)

        _state.update {
            it.copy(
                status = TransportStatus.BUFFERING,
                currentPositionMs = positionMs
            )
        }

        // 4. Schedule resumption at T_target
        scheduledJob = scheduler.scheduleTrigger(tTarget, scope) {
            decoder?.resume()
            _state.update { it.copy(status = TransportStatus.PLAYING) }
            startPositionTicker()
        }
    }

    /**
     * Host Stop Flow:
     * Stops playback immediately, resets jitter buffer, broadcasts [RoomBeatPacket.SessionStop],
     * and resets position to 0.
     */
    fun stop() {
        if (!isHost) {
            Log.w(TAG, "stop() called on peer node; only host can initiate stop")
            return
        }

        scheduledJob?.cancel()
        stopPositionTicker()

        val nowUs = clock.nowMicros()
        val packet = RoomBeatPacket.SessionStop(atPresentationTime = nowUs)
        broadcastControlPacket(packet)

        decoder?.stop()
        coordinator.handleSessionStop(packet)
        jitterBuffer.reset()

        _state.update {
            it.copy(
                status = TransportStatus.STOPPED,
                currentPositionMs = 0L
            )
        }
    }

    // ========================================================================
    // Peer / Network Packet Ingestion Handlers
    // ========================================================================

    /**
     * Ingests [RoomBeatPacket.SessionStart], translating $T_{target}$ to local clock frame
     * ($T_{target, local} = T_{target, host} + \theta$) and awaiting presentation unblocking.
     */
    fun handleSessionStart(packet: RoomBeatPacket.SessionStart) {
        scheduledJob?.cancel()

        val theta = currentClockOffsetMicros
        val tTargetLocal = if (isHost) packet.targetPresentationTime else packet.targetPresentationTime + theta

        jitterBuffer.targetStartTimeUs = tTargetLocal
        _state.update { it.copy(status = TransportStatus.BUFFERING) }

        scheduledJob = scheduler.scheduleTrigger(tTargetLocal, scope) {
            coordinator.handleSessionStart(packet)
            _state.update {
                it.copy(status = if (isHost) TransportStatus.PLAYING else TransportStatus.SYNCED_TO_HOST)
            }
            startPositionTicker()
        }
    }

    /**
     * Peer Pause Flow:
     * On receiving [RoomBeatPacket.SessionPause], adjusts $T_{exec}$ to local clock frame
     * ($T_{exec, local} = T_{exec, host} + \theta$) and schedules pause at $T_{exec, local}$ via [PlaybackClockScheduler].
     */
    fun handleSessionPause(packet: RoomBeatPacket.SessionPause) {
        scheduledJob?.cancel()

        val theta = currentClockOffsetMicros
        val tExecLocal = if (isHost) packet.atPresentationTime else packet.atPresentationTime + theta

        scheduledJob = scheduler.scheduleTrigger(tExecLocal, scope) {
            stopPositionTicker()
            coordinator.handleSessionPause(packet)
            _state.update { it.copy(status = TransportStatus.PAUSED) }
        }
    }

    /**
     * Peer Seek Flow:
     * On receiving [RoomBeatPacket.SessionSeek], translates $T_{target}$ to local clock domain
     * ($T_{target, local} = T_{target, host} + \theta$), flushes local jitter buffer via
     * [AudioJitterBuffer.flushAndSeek], and awaits $T_{target, local}$ via [PlaybackClockScheduler].
     */
    fun handleSessionSeek(packet: RoomBeatPacket.SessionSeek) {
        scheduledJob?.cancel()
        stopPositionTicker()

        val theta = currentClockOffsetMicros
        val tTargetLocal = if (isHost) packet.targetPresentationTime else packet.targetPresentationTime + theta

        // Flush local jitter buffer and arm with local target start timestamp
        jitterBuffer.flushAndSeek(0L, tTargetLocal)

        _state.update {
            it.copy(
                status = TransportStatus.BUFFERING,
                currentPositionMs = packet.positionMs
            )
        }

        scheduledJob = scheduler.scheduleTrigger(tTargetLocal, scope) {
            _state.update {
                it.copy(status = if (isHost) TransportStatus.PLAYING else TransportStatus.SYNCED_TO_HOST)
            }
            startPositionTicker()
        }
    }

    /**
     * Handles [RoomBeatPacket.SessionStop]: resets buffers, updates state to STOPPED,
     * and resets position to 0.
     */
    fun handleSessionStop(packet: RoomBeatPacket.SessionStop) {
        scheduledJob?.cancel()
        stopPositionTicker()

        coordinator.handleSessionStop(packet)
        jitterBuffer.reset()

        _state.update {
            it.copy(
                status = TransportStatus.STOPPED,
                currentPositionMs = 0L
            )
        }
    }

    /**
     * Dispatches any incoming [RoomBeatPacket] to its respective transport handler.
     * Returns true if handled, false otherwise.
     */
    fun handlePacket(packet: RoomBeatPacket): Boolean {
        return when (packet) {
            is RoomBeatPacket.SessionStart -> {
                handleSessionStart(packet)
                true
            }
            is RoomBeatPacket.SessionPause -> {
                handleSessionPause(packet)
                true
            }
            is RoomBeatPacket.SessionSeek -> {
                handleSessionSeek(packet)
                true
            }
            is RoomBeatPacket.SessionStop -> {
                handleSessionStop(packet)
                true
            }
            else -> false
        }
    }

    // ========================================================================
    // Position Ticker & Cleanup
    // ========================================================================

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_INTERVAL_MS)
                val d = decoder
                if (d != null) {
                    val posMs = d.positionUs.value / 1_000L
                    _state.update { it.copy(currentPositionMs = posMs) }
                } else {
                    _state.update {
                        val duration = it.durationMs
                        val nextPos = it.currentPositionMs + POSITION_TICK_INTERVAL_MS
                        if (duration > 0 && nextPos >= duration) {
                            it.copy(currentPositionMs = duration)
                        } else {
                            it.copy(currentPositionMs = nextPos)
                        }
                    }
                }
            }
        }
    }

    private fun stopPositionTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun broadcastControlPacket(packet: RoomBeatPacket) {
        val t = transport ?: coordinator.transport
        if (t != null) {
            t.broadcastToAll(packet)
        } else {
            (sessionManager ?: coordinator.sessionManager)?.transport?.broadcastToAll(packet)
        }
    }

    override fun close() {
        scheduledJob?.cancel()
        scheduledJob = null
        stopPositionTicker()
    }
}
