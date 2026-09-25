package com.roombeat.app.source.spotify

import android.util.Log
import com.roombeat.app.protocol.HandlerRegistration
import com.roombeat.app.protocol.PacketContext
import com.roombeat.app.protocol.PacketDispatcher
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.session.PeerSessionManager
import com.roombeat.app.session.PeerSessionTransport
import com.roombeat.app.sync.MonotonicClock
import com.roombeat.app.sync.SystemMonotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * Result of evaluating peer playback position drift against the master presentation timeline.
 */
data class DriftEvaluationResult(
    val deviceId: String,
    val reportedPositionMs: Long,
    val projectedPositionMs: Long,
    val expectedMasterPositionMs: Long,
    val driftMs: Long,
    val exceedsThreshold: Boolean,
    val correctionIssued: Boolean,
    val correctionPacket: RoomBeatPacket.SpotifyCmd? = null
)

/**
 * Spotify playback position state subscriber and drift monitor loop (Sub-phase v0.7.2 / Roadmap §8, §10, §11).
 *
 * Core Responsibilities:
 * 1. Maintains the authoritative master presentation timeline for Spotify playback ($T_{\text{target}}$, $P_0$, speed).
 * 2. Projects peer playback positions to current time using local monotonic clock and peer clock offset:
 *    $T_{\text{sample, host}} = T_{\text{sample, peer}} - \theta$, $\text{projectedPos} = P_{\text{reported}} + \Delta T_{\text{elapsed}}$.
 * 3. Computes drift: $\Delta = \text{projectedPeerPosition} - \text{expectedMasterPosition}$.
 * 4. If $|\Delta| > 50\text{ms}$ (50ms drift threshold), issues micro-seek correction via [SpotifyCommandDispatcher]
 *    or targeted `SPOTIFY_CMD` with updated seek position and scheduled presentation timestamp ($T_{\text{target}}$)
 *    to smoothly realign the node without interrupting music flow.
 * 5. Applies per-peer correction debounce / cooldown to eliminate micro-seek oscillations.
 * 6. Handles track finish detection and seamless synchronous multi-device playlist progression transitions.
 */
class SpotifyDriftMonitor(
    val isHost: Boolean,
    val clock: MonotonicClock = SystemMonotonicClock,
    val commandDispatcher: SpotifyCommandDispatcher? = null,
    val transport: PeerSessionTransport? = null,
    val sessionManager: PeerSessionManager? = null,
    val telemetryAggregator: SpotifyTelemetryAggregator = SpotifyTelemetryAggregator(),
    val driftThresholdMs: Long = DEFAULT_DRIFT_THRESHOLD_MS,
    val correctionLeadTimeUs: Long = DEFAULT_CORRECTION_LEAD_TIME_US,
    val correctionCooldownUs: Long = DEFAULT_CORRECTION_COOLDOWN_US,
    val peerOffsetProvider: ((deviceId: String) -> Long)? = null,
    val targetedSender: ((deviceId: String, packet: RoomBeatPacket) -> Unit)? = null,
    val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(coroutineDispatcher + SupervisorJob()),
    var onDriftDetected: ((deviceId: String, driftMs: Long) -> Unit)? = null,
    var onCorrectionIssued: ((deviceId: String, driftMs: Long, targetSeekMs: Long, targetPresentationTimeUs: Long) -> Unit)? = null,
    var onTrackFinished: ((trackUri: String) -> Unit)? = null,
    var onTrackProgressed: ((oldUri: String, newUri: String) -> Unit)? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "SpotifyDriftMonitor"

        /**
         * 50ms drift threshold per sub-phase v0.7.2 specification.
         */
        const val DEFAULT_DRIFT_THRESHOLD_MS = 50L

        /**
         * 400ms execution lead time for micro-seek corrections.
         */
        const val DEFAULT_CORRECTION_LEAD_TIME_US = 400_000L

        /**
         * 2.0-second cooldown per peer device preventing correction storms.
         */
        const val DEFAULT_CORRECTION_COOLDOWN_US = 2_000_000L

        /**
         * Milliseconds before track end considered near-finish transition window.
         */
        const val TRACK_END_THRESHOLD_MS = 500L
    }

    // ========================================================================
    // Master Presentation Timeline State
    // ========================================================================

    private val timelineLock = Any()

    var masterTrackUri: String = ""
        private set

    var masterTrackDurationMs: Long = 0L
        private set

    var targetPresentationTimeUs: Long = 0L
        private set

    var masterStartPositionMs: Long = 0L
        private set

    var masterPlaybackSpeed: Float = 1.0f
        private set

    var isMasterPlaying: Boolean = false
        private set

    var isTrackFinished: Boolean = false
        private set

    private val lastCorrectionIssuedUs = ConcurrentHashMap<String, Long>()
    private val nextTrackQueue = ConcurrentLinkedQueue<Pair<String, Long>>()

    private var dispatcherObserverJob: Job? = null

    init {
        // Automatically sync timeline with SpotifyCommandDispatcher if provided
        if (commandDispatcher != null) {
            dispatcherObserverJob = scope.launch {
                commandDispatcher.playbackState.collect { state ->
                    onCommandDispatcherStateChanged(state)
                }
            }
        }
    }

    /**
     * Sets or updates the master presentation timeline.
     */
    fun setMasterTimeline(
        trackUri: String,
        durationMs: Long,
        targetPresentationTimeUs: Long,
        startPositionMs: Long = 0L,
        playbackSpeed: Float = 1.0f,
        isPlaying: Boolean = true
    ) {
        synchronized(timelineLock) {
            val trackChanged = masterTrackUri.isNotEmpty() && masterTrackUri != trackUri
            val oldUri = masterTrackUri

            masterTrackUri = trackUri
            masterTrackDurationMs = durationMs
            this.targetPresentationTimeUs = targetPresentationTimeUs
            masterStartPositionMs = startPositionMs
            masterPlaybackSpeed = playbackSpeed
            isMasterPlaying = isPlaying
            isTrackFinished = false

            if (trackChanged) {
                onTrackProgressed?.invoke(oldUri, trackUri)
            }
        }
    }

    /**
     * Updates master presentation state to Paused.
     */
    fun updateMasterPause(atPositionMs: Long) {
        synchronized(timelineLock) {
            masterStartPositionMs = atPositionMs
            isMasterPlaying = false
        }
    }

    /**
     * Updates master presentation state to Resumed.
     */
    fun updateMasterResume(targetPresentationTimeUs: Long, fromPositionMs: Long) {
        synchronized(timelineLock) {
            this.targetPresentationTimeUs = targetPresentationTimeUs
            masterStartPositionMs = fromPositionMs
            isMasterPlaying = true
        }
    }

    /**
     * Updates master presentation state after Seek.
     */
    fun updateMasterSeek(targetPresentationTimeUs: Long, toPositionMs: Long) {
        synchronized(timelineLock) {
            this.targetPresentationTimeUs = targetPresentationTimeUs
            masterStartPositionMs = toPositionMs
            isMasterPlaying = true
        }
    }

    /**
     * Resets master timeline on playback stop or idle.
     */
    fun updateMasterStop() {
        synchronized(timelineLock) {
            isMasterPlaying = false
            masterStartPositionMs = 0L
        }
    }

    /**
     * Queues a track URI and duration to transition to upon current track completion.
     */
    fun queueNextTrack(trackUri: String, durationMs: Long = 0L) {
        nextTrackQueue.add(trackUri to durationMs)
    }

    // ========================================================================
    // Position & Drift Computations
    // ========================================================================

    /**
     * Calculates the expected master track position in milliseconds at [nowMicros] based on
     * the master presentation timeline and elapsed time.
     */
    fun calculateExpectedMasterPosition(nowMicros: Long = clock.nowMicros()): Long {
        synchronized(timelineLock) {
            if (!isMasterPlaying) {
                return masterStartPositionMs
            }

            if (nowMicros < targetPresentationTimeUs) {
                // Pre-presentation buffering window
                return masterStartPositionMs
            }

            val elapsedUs = nowMicros - targetPresentationTimeUs
            val elapsedMs = (elapsedUs * masterPlaybackSpeed / 1000L).toLong()
            val expectedPos = masterStartPositionMs + elapsedMs

            return if (masterTrackDurationMs > 0L) {
                expectedPos.coerceAtMost(masterTrackDurationMs)
            } else {
                expectedPos
            }
        }
    }

    /**
     * Projects a reported peer position to the current host time [nowMicros] using
     * local monotonic clock and peer clock offset:
     * $T_{\text{sample, host}} = T_{\text{sample, peer}} - \theta$.
     *
     * @param reportedPositionMs Playback position reported by peer in milliseconds.
     * @param sampledAtUs Monotonic timestamp when peer sampled position (microseconds).
     * @param peerOffsetMicros Peer clock offset relative to Host ($\theta = T_{peer} - T_{host}$).
     * @param nowMicros Current monotonic timestamp on Host.
     * @param playbackSpeed Playback speed (default master speed).
     */
    fun projectPeerPosition(
        reportedPositionMs: Long,
        sampledAtUs: Long,
        peerOffsetMicros: Long,
        nowMicros: Long = clock.nowMicros(),
        playbackSpeed: Float = masterPlaybackSpeed
    ): Long {
        // Translate sample timestamp from Peer clock frame to Host clock frame
        val sampleHostUs = sampledAtUs - peerOffsetMicros
        val elapsedUs = (nowMicros - sampleHostUs).coerceAtLeast(0L)
        val elapsedMs = (elapsedUs * playbackSpeed / 1000L).toLong()

        val projected = reportedPositionMs + elapsedMs
        return if (masterTrackDurationMs > 0L) {
            projected.coerceAtMost(masterTrackDurationMs)
        } else {
            projected
        }
    }

    /**
     * Evaluates drift for a peer node and determines whether micro-seek correction is required.
     */
    fun evaluateDrift(
        deviceId: String,
        reportedPositionMs: Long,
        sampledAtUs: Long,
        peerOffsetMicros: Long? = null,
        nowMicros: Long = clock.nowMicros()
    ): DriftEvaluationResult {
        val offsetUs = peerOffsetMicros
            ?: peerOffsetProvider?.invoke(deviceId)
            ?: resolvePeerOffsetFromSession(deviceId)

        val expectedMasterPos = calculateExpectedMasterPosition(nowMicros)
        val projectedPeerPos = projectPeerPosition(reportedPositionMs, sampledAtUs, offsetUs, nowMicros)

        // Drift Δ = peerPosition - expectedMasterPosition
        // Positive => peer leading; Negative => peer lagging
        val driftMs = projectedPeerPos - expectedMasterPos
        val absDrift = abs(driftMs)
        val exceedsThreshold = absDrift > driftThresholdMs

        var correctionIssued = false
        var correctionPacket: RoomBeatPacket.SpotifyCmd? = null

        if (exceedsThreshold && isMasterPlaying) {
            onDriftDetected?.invoke(deviceId, driftMs)

            // Check cooldown to avoid correction storms
            val lastCorrection = lastCorrectionIssuedUs[deviceId] ?: 0L
            val elapsedSinceCorrection = nowMicros - lastCorrection

            if (elapsedSinceCorrection >= correctionCooldownUs) {
                // Calculate target presentation timestamp and target seek position at T_target
                val tTargetUs = nowMicros + correctionLeadTimeUs
                val targetSeekMs = calculateExpectedMasterPosition(tTargetUs)

                val cmd = RoomBeatPacket.SpotifyCmd(
                    trackUri = masterTrackUri,
                    targetPositionMs = targetSeekMs,
                    targetPresentationTime = tTargetUs,
                    command = RoomBeatPacket.SpotifyCmd.CMD_SEEK
                )

                issueCorrection(deviceId, cmd, driftMs, targetSeekMs, tTargetUs)
                lastCorrectionIssuedUs[deviceId] = nowMicros
                correctionIssued = true
                correctionPacket = cmd
            } else {
                Log.d(TAG, "Drift of ${driftMs}ms detected on peer $deviceId but cooldown is active (${elapsedSinceCorrection / 1000}ms < ${correctionCooldownUs / 1000}ms)")
            }
        }

        // Update telemetry aggregator
        val telemetry = SpotifyPeerTelemetry(
            deviceId = deviceId,
            reportedPositionMs = reportedPositionMs,
            sampledAtUs = sampledAtUs,
            projectedPositionMs = projectedPeerPos,
            expectedMasterPositionMs = expectedMasterPos,
            driftMs = driftMs,
            isSynced = !exceedsThreshold,
            lastReportReceivedAtMs = System.currentTimeMillis()
        )
        telemetryAggregator.updatePeerTelemetry(telemetry)

        // Check track completion transitions
        checkTrackProgression(nowMicros)

        return DriftEvaluationResult(
            deviceId = deviceId,
            reportedPositionMs = reportedPositionMs,
            projectedPositionMs = projectedPeerPos,
            expectedMasterPositionMs = expectedMasterPos,
            driftMs = driftMs,
            exceedsThreshold = exceedsThreshold,
            correctionIssued = correctionIssued,
            correctionPacket = correctionPacket
        )
    }

    /**
     * Processes an incoming [RoomBeatPacket.SpotifyStateReport] packet from a peer.
     */
    fun handlePeerStateReport(
        report: RoomBeatPacket.SpotifyStateReport,
        peerOffsetMicros: Long? = null
    ): DriftEvaluationResult {
        return evaluateDrift(
            deviceId = report.deviceId,
            reportedPositionMs = report.reportedPositionMs,
            sampledAtUs = report.sampledAt,
            peerOffsetMicros = peerOffsetMicros
        )
    }

    private fun issueCorrection(
        deviceId: String,
        packet: RoomBeatPacket.SpotifyCmd,
        driftMs: Long,
        targetSeekMs: Long,
        tTargetUs: Long
    ) {
        Log.i(TAG, "Issuing micro-seek correction to peer $deviceId: drift=${driftMs}ms, seekTo=${targetSeekMs}ms at T_target=${tTargetUs}us")

        // 1. Direct targeted sender callback
        if (targetedSender != null) {
            targetedSender.invoke(deviceId, packet)
        }
        // 2. PeerSessionTransport
        else if (transport != null) {
            transport.sendToPeer(deviceId, packet)
        }
        // 3. Fallback to SpotifyCommandDispatcher broadcast
        else if (commandDispatcher != null) {
            commandDispatcher.dispatchSeek(targetSeekMs)
        }

        telemetryAggregator.recordCorrectionIssued(deviceId)
        onCorrectionIssued?.invoke(deviceId, driftMs, targetSeekMs, tTargetUs)
    }

    private fun resolvePeerOffsetFromSession(deviceId: String): Long {
        val peer = sessionManager?.getPeer(deviceId) ?: return 0L
        return (peer.offsetMs * 1000.0).toLong()
    }

    // ========================================================================
    // Track Progression & Completion Handling
    // ========================================================================

    /**
     * Checks if the master track has reached completion or near-completion, triggering
     * track progression or end callbacks.
     */
    fun checkTrackProgression(nowMicros: Long = clock.nowMicros()): Boolean {
        synchronized(timelineLock) {
            if (!isMasterPlaying || masterTrackDurationMs <= 0L || isTrackFinished) {
                return false
            }

            val currentPos = calculateExpectedMasterPosition(nowMicros)
            if (currentPos >= masterTrackDurationMs - TRACK_END_THRESHOLD_MS) {
                isTrackFinished = true
                val finishedUri = masterTrackUri
                Log.i(TAG, "Spotify track finished: $finishedUri (position=${currentPos}ms, duration=${masterTrackDurationMs}ms)")
                onTrackFinished?.invoke(finishedUri)

                // Advance to next queued track if available
                val nextTrack = nextTrackQueue.poll()
                if (nextTrack != null) {
                    val (nextUri, nextDuration) = nextTrack
                    advanceToNextTrack(nextUri, nextDuration)
                }
                return true
            }
            return false
        }
    }

    /**
     * Advances master playback to the next track synchronously across all devices.
     */
    fun advanceToNextTrack(nextTrackUri: String, nextDurationMs: Long = 0L) {
        val nowUs = clock.nowMicros()
        val tTargetUs = nowUs + DEFAULT_CORRECTION_LEAD_TIME_US

        setMasterTimeline(
            trackUri = nextTrackUri,
            durationMs = nextDurationMs,
            targetPresentationTimeUs = tTargetUs,
            startPositionMs = 0L,
            isPlaying = true
        )

        // Broadcast synchronized play command via command dispatcher
        commandDispatcher?.dispatchPlay(nextTrackUri, startPositionMs = 0L)
    }

    /**
     * Observes state updates from [SpotifyCommandDispatcher] to keep the master timeline in lockstep.
     */
    private fun onCommandDispatcherStateChanged(state: SpotifyPlaybackState) {
        when (state) {
            is SpotifyPlaybackState.Playing -> {
                setMasterTimeline(
                    trackUri = state.trackUri,
                    durationMs = masterTrackDurationMs,
                    targetPresentationTimeUs = state.startedAtUs,
                    startPositionMs = state.positionMs,
                    isPlaying = true
                )
            }
            is SpotifyPlaybackState.Paused -> {
                updateMasterPause(state.positionMs)
            }
            is SpotifyPlaybackState.Buffering -> {
                // Buffering for target presentation time
            }
            is SpotifyPlaybackState.Idle -> {
                updateMasterStop()
            }
            is SpotifyPlaybackState.Error -> {
                updateMasterStop()
            }
        }
    }

    /**
     * Registers an incoming [RoomBeatPacket.SpotifyStateReport] handler on [PacketDispatcher].
     */
    fun registerPacketHandler(dispatcher: PacketDispatcher): HandlerRegistration {
        return dispatcher.registerHandler<RoomBeatPacket.SpotifyStateReport> { packet, context ->
            val senderId = context.senderId ?: packet.deviceId
            handlePeerStateReport(packet)
        }
    }

    override fun close() {
        dispatcherObserverJob?.cancel()
        updateMasterStop()
    }
}
