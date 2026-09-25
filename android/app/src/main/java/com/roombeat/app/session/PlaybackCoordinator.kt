package com.roombeat.app.session

import android.util.Log
import com.roombeat.app.audio.PlaybackClockScheduler
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.network.multicast.MulticastBroadcaster
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.sync.MonotonicClock
import com.roombeat.app.sync.SystemMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Playback coordinator lifecycle states conforming to Roadmap §11.
 */
enum class PlaybackState {
    IDLE,
    SCHEDULING,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED
}

/**
 * Listener interface for playback lifecycle events triggered by [PlaybackCoordinator].
 */
interface PlaybackCoordinatorListener {
    fun onStateChanged(state: PlaybackState) {}
    fun onPlaybackStarted(targetPresentationTimeUs: Long) {}
    fun onPlaybackPaused(atPresentationTimeUs: Long) {}
    fun onPlaybackStopped() {}
}

/**
 * Master playback presentation scheduler and synchronized timeline coordinator for RoomBeat.
 *
 * Implements presentation time scheduling for session start:
 * 1. Host calculates presentation start timestamp $T_{target} = T_{now} + 350,000\mu\text{s}$ (350ms lead time).
 * 2. Broadcasts `SESSION_START { media_id, target_presentation_time, source_type = "LOCAL_FILE" }`.
 * 3. Streams initial pre-buffering `AUDIO_CHUNK` frames with scheduled timestamps $T \ge T_{target}$.
 * 4. On receiving nodes (host and all peers), arms the [AudioJitterBuffer] release lock and synchronizes
 *    unblocking of audio rendering at the exact microsecond $T_{target}$ arrives.
 */
class PlaybackCoordinator(
    val isHost: Boolean,
    val clock: MonotonicClock = SystemMonotonicClock,
    val jitterBuffer: AudioJitterBuffer,
    val scheduler: PlaybackClockScheduler = PlaybackClockScheduler(clock),
    var broadcaster: MulticastBroadcaster? = null,
    var transport: PeerSessionTransport? = null,
    var sessionManager: PeerSessionManager? = null,
    var clockOffsetMicros: Long = 0L,
    var clockOffsetProvider: (() -> Long)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    var listener: PlaybackCoordinatorListener? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "PlaybackCoordinator"

        /**
         * Standard lead time providing 350ms buffer lead time for network transit and jitter buffer filling.
         */
        const val LEAD_TIME_MICROS = 350_000L

        /**
         * Standard 20ms frame duration in microseconds (50 frames per second).
         */
        const val FRAME_DURATION_MICROS = 20_000L

        /**
         * Minimum pre-buffering frame count before playback begins (6 frames = 120ms depth).
         */
        const val PREBUFFER_FRAME_COUNT = 6
    }

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _targetPresentationTimeUs = MutableStateFlow(0L)
    val targetPresentationTimeUs: StateFlow<Long> = _targetPresentationTimeUs.asStateFlow()

    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaId: StateFlow<String?> = _currentMediaId.asStateFlow()

    private val _currentSourceType = MutableStateFlow<String?>(null)
    val currentSourceType: StateFlow<String?> = _currentSourceType.asStateFlow()

    private val _broadcastChunkCount = MutableStateFlow(0L)
    val broadcastChunkCount: StateFlow<Long> = _broadcastChunkCount.asStateFlow()

    private val _receivedChunkCount = MutableStateFlow(0L)
    val receivedChunkCount: StateFlow<Long> = _receivedChunkCount.asStateFlow()

    private val chunkCounter = AtomicLong(0L)
    private var countdownJob: Job? = null

    /**
     * Current clock offset $\theta$ in microseconds to convert host timestamps into local clock frame.
     */
    val currentClockOffsetMicros: Long
        get() = clockOffsetProvider?.invoke() ?: clockOffsetMicros

    /**
     * Host Start Flow:
     * Calculates $T_{target} = T_{now} + 350\text{ms}$, broadcasts `SESSION_START`,
     * arms local jitter buffer, streams pre-buffering frames, and arms presentation countdown.
     */
    fun startHostPlayback(
        mediaId: String,
        sourceType: String = "LOCAL_FILE",
        prebufferPackets: List<ByteArray> = emptyList()
    ): Long {
        require(isHost) { "startHostPlayback can only be initiated by the session host" }

        countdownJob?.cancel()
        countdownJob = null

        val nowUs = clock.nowMicros()
        val targetTimeUs = nowUs + LEAD_TIME_MICROS

        _currentMediaId.value = mediaId
        _currentSourceType.value = sourceType
        _targetPresentationTimeUs.value = targetTimeUs
        chunkCounter.set(0L)
        _broadcastChunkCount.value = 0L

        updateState(PlaybackState.SCHEDULING)

        // 1. Construct and broadcast SESSION_START packet
        val sessionStartPacket = RoomBeatPacket.SessionStart(
            mediaId = mediaId,
            targetPresentationTime = targetTimeUs,
            sourceType = sourceType
        )
        broadcastControlPacket(sessionStartPacket)

        // 2. Arm local jitter buffer release lock
        jitterBuffer.targetStartTimeUs = targetTimeUs

        updateState(PlaybackState.BUFFERING)

        // 3. Stream initial pre-buffering chunks tagged with T_presentation = T_target + (i * 20ms)
        for (i in prebufferPackets.indices) {
            val chunkSeq = i.toLong()
            val chunkPresentationTimeUs = targetTimeUs + (chunkSeq * FRAME_DURATION_MICROS)
            val opusBytes = prebufferPackets[i]

            // Broadcast over UDP Multicast to all peers
            broadcaster?.sendAudioChunk(
                seq = chunkSeq,
                targetPresentationTime = chunkPresentationTimeUs,
                opusData = opusBytes
            )
            _broadcastChunkCount.value++

            // Queue locally into host's jitter buffer
            jitterBuffer.pushPacket(
                sequenceNumber = chunkSeq,
                presentationTimeUs = chunkPresentationTimeUs,
                payload = opusBytes
            )
        }
        chunkCounter.set(prebufferPackets.size.toLong())

        // 4. Arm presentation countdown scheduler
        armCountdownScheduler(targetTimeUs)

        return targetTimeUs
    }

    /**
     * Peer / Client Start Flow:
     * Adjusts target presentation time using peer clock offset $\theta$,
     * arms local jitter buffer, and tracks countdown to $T_{target, local}$.
     */
    fun handleSessionStart(packet: RoomBeatPacket.SessionStart) {
        countdownJob?.cancel()
        countdownJob = null

        _currentMediaId.value = packet.mediaId
        _currentSourceType.value = packet.sourceType

        updateState(PlaybackState.SCHEDULING)

        // Adjust T_target to local clock frame using peer clock offset theta
        val targetLocalTimeUs = if (isHost) {
            packet.targetPresentationTime
        } else {
            packet.targetPresentationTime + currentClockOffsetMicros
        }
        _targetPresentationTimeUs.value = targetLocalTimeUs

        // Arm local jitter buffer with adjusted local target start timestamp
        jitterBuffer.targetStartTimeUs = targetLocalTimeUs

        updateState(PlaybackState.BUFFERING)

        // Arm countdown scheduler to target timestamp
        armCountdownScheduler(targetLocalTimeUs)
    }

    /**
     * Streams an individual Opus audio frame during active playback (Host).
     */
    fun streamAudioFrame(
        opusData: ByteArray,
        offset: Int = 0,
        length: Int = opusData.size
    ): Long {
        val seq = chunkCounter.getAndIncrement()
        val targetTimeUs = _targetPresentationTimeUs.value
        val framePresentationTimeUs = targetTimeUs + (seq * FRAME_DURATION_MICROS)

        if (isHost) {
            broadcaster?.sendAudioChunk(
                seq = seq,
                targetPresentationTime = framePresentationTimeUs,
                opusData = opusData,
                offset = offset,
                length = length
            )
            _broadcastChunkCount.value++
        }

        jitterBuffer.pushPacket(
            sequenceNumber = seq,
            presentationTimeUs = framePresentationTimeUs,
            payload = opusData,
            offset = offset,
            length = length
        )

        return framePresentationTimeUs
    }

    /**
     * Ingests an incoming [RoomBeatPacket.AudioChunk] from the network.
     */
    fun handleAudioChunk(packet: RoomBeatPacket.AudioChunk) {
        val rawOpus = try {
            Base64.getDecoder().decode(packet.opusFrame)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode base64 opus frame seq=${packet.seq}: ${e.message}")
            return
        }

        pushIncomingChunk(
            seq = packet.seq,
            presentationTimeUs = packet.targetPresentationTime,
            opusData = rawOpus
        )
    }

    /**
     * Ingests a raw Opus chunk with presentation timestamp adjustment.
     */
    fun pushIncomingChunk(
        seq: Long,
        presentationTimeUs: Long,
        opusData: ByteArray,
        offset: Int = 0,
        length: Int = opusData.size
    ): Boolean {
        val localPresentationTimeUs = if (isHost) {
            presentationTimeUs
        } else {
            presentationTimeUs + currentClockOffsetMicros
        }

        val pushed = jitterBuffer.pushPacket(
            sequenceNumber = seq,
            presentationTimeUs = localPresentationTimeUs,
            payload = opusData,
            offset = offset,
            length = length
        )
        if (pushed) {
            _receivedChunkCount.value++
        }
        return pushed
    }

    /**
     * Handles incoming [RoomBeatPacket.SessionPause].
     */
    fun handleSessionPause(packet: RoomBeatPacket.SessionPause) {
        countdownJob?.cancel()
        countdownJob = null
        updateState(PlaybackState.PAUSED)
        listener?.onPlaybackPaused(packet.atPresentationTime)
    }

    /**
     * Handles incoming [RoomBeatPacket.SessionStop].
     */
    fun handleSessionStop(packet: RoomBeatPacket.SessionStop) {
        countdownJob?.cancel()
        countdownJob = null
        jitterBuffer.reset()
        updateState(PlaybackState.STOPPED)
        listener?.onPlaybackStopped()
    }

    /**
     * Handles any incoming [RoomBeatPacket], dispatching to the appropriate handler.
     */
    fun handlePacket(packet: RoomBeatPacket): Boolean {
        return when (packet) {
            is RoomBeatPacket.SessionStart -> {
                handleSessionStart(packet)
                true
            }
            is RoomBeatPacket.AudioChunk -> {
                handleAudioChunk(packet)
                true
            }
            is RoomBeatPacket.SessionPause -> {
                handleSessionPause(packet)
                true
            }
            is RoomBeatPacket.SessionStop -> {
                handleSessionStop(packet)
                true
            }
            else -> false
        }
    }

    /**
     * Pauses playback. If host, broadcasts [RoomBeatPacket.SessionPause].
     */
    fun pause() {
        countdownJob?.cancel()
        countdownJob = null
        val nowUs = clock.nowMicros()
        updateState(PlaybackState.PAUSED)
        if (isHost) {
            broadcastControlPacket(RoomBeatPacket.SessionPause(atPresentationTime = nowUs))
        }
        listener?.onPlaybackPaused(nowUs)
    }

    /**
     * Resumes playback.
     */
    fun resume() {
        updateState(PlaybackState.PLAYING)
    }

    /**
     * Stops playback and resets buffer state. If host, broadcasts [RoomBeatPacket.SessionStop].
     */
    fun stop() {
        countdownJob?.cancel()
        countdownJob = null
        val nowUs = clock.nowMicros()
        jitterBuffer.reset()
        updateState(PlaybackState.STOPPED)
        if (isHost) {
            broadcastControlPacket(RoomBeatPacket.SessionStop(atPresentationTime = nowUs))
        }
        listener?.onPlaybackStopped()
    }

    /**
     * Resets the coordinator back to [PlaybackState.IDLE].
     */
    fun reset() {
        countdownJob?.cancel()
        countdownJob = null
        jitterBuffer.reset()
        _targetPresentationTimeUs.value = 0L
        _currentMediaId.value = null
        _currentSourceType.value = null
        _broadcastChunkCount.value = 0L
        _receivedChunkCount.value = 0L
        chunkCounter.set(0L)
        updateState(PlaybackState.IDLE)
    }

    private fun armCountdownScheduler(targetTimeUs: Long) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            scheduler.awaitTarget(targetTimeUs) {
                updateState(PlaybackState.PLAYING)
                listener?.onPlaybackStarted(targetTimeUs)
            }
        }
    }

    private fun updateState(newState: PlaybackState) {
        _state.value = newState
        listener?.onStateChanged(newState)
    }

    private fun broadcastControlPacket(packet: RoomBeatPacket) {
        val t = transport
        if (t != null) {
            t.broadcastToAll(packet)
        } else {
            sessionManager?.transport?.broadcastToAll(packet)
        }
    }

    override fun close() {
        countdownJob?.cancel()
        countdownJob = null
        broadcaster?.close()
    }
}
