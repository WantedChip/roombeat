package com.roombeat.app.source.spotify

import android.util.Log
import com.roombeat.app.audio.PlaybackClockScheduler
import com.roombeat.app.protocol.HandlerRegistration
import com.roombeat.app.protocol.PacketDispatcher
import com.roombeat.app.protocol.RoomBeatPacket
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State hierarchy representing Spotify synchronized playback status.
 */
sealed interface SpotifyPlaybackState {

    /**
     * Idle, no active Spotify track playback scheduled.
     */
    data object Idle : SpotifyPlaybackState

    /**
     * Audio track is cued and buffering, counting down to target presentation timestamp.
     */
    data class Buffering(
        val trackUri: String,
        val targetPresentationTimeUs: Long,
        val scheduledAction: String
    ) : SpotifyPlaybackState

    /**
     * Active synchronized Spotify playback is currently running.
     */
    data class Playing(
        val trackUri: String,
        val positionMs: Long = 0L,
        val startedAtUs: Long = 0L
    ) : SpotifyPlaybackState

    /**
     * Active Spotify playback is currently paused.
     */
    data class Paused(
        val trackUri: String,
        val positionMs: Long = 0L
    ) : SpotifyPlaybackState

    /**
     * An error occurred during command dispatch or playback execution.
     */
    data class Error(
        val message: String,
        val isPremiumRequired: Boolean = false,
        val cause: Throwable? = null,
        val timestampMs: Long = System.currentTimeMillis()
    ) : SpotifyPlaybackState
}

/**
 * High-precision Spotify command-sync protocol dispatcher (Sub-phase v0.7.1 / Roadmap §8, §10, §11).
 *
 * Responsibilities:
 * 1. Schedules and executes synchronized play, pause, seek, and next transport commands against
 *    the Spotify [SpotifyPlayerApiFacade] at $T_{\text{target}} = \text{MonotonicClock.nowMicros()} + 400,000\mu\text{s}$ (400ms lead time).
 * 2. Translates presentation targets on peer nodes using acoustic clock offset:
 *    $T_{\text{target, local}} = T_{\text{target, host}} + \theta$.
 * 3. Pre-buffers audio: on receiving play commands, invokes `playerApi.play(track_uri)` paused to prime
 *    Spotify's internal audio pipeline, then triggers `resume()` or `seekTo()` precisely at $T_{\text{target}}$.
 * 4. Detects non-Premium Spotify limitations (e.g. [SpotifyPremiumRequiredException]) and surfaces
 *    structured errors and callbacks for tactile UI handling.
 * 5. Integrates with [PacketDispatcher] to automatically intercept and process incoming `SPOTIFY_CMD` packets.
 */
class SpotifyCommandDispatcher(
    val isHost: Boolean,
    val remoteManager: SpotifyRemoteManager? = null,
    val customPlayerApi: SpotifyPlayerApiFacade? = null,
    val clock: MonotonicClock = SystemMonotonicClock,
    val scheduler: PlaybackClockScheduler = PlaybackClockScheduler(clock),
    var clockOffsetMicros: Long = 0L,
    var clockOffsetProvider: (() -> Long)? = null,
    var broadcaster: (suspend (RoomBeatPacket) -> Unit)? = null,
    val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(coroutineDispatcher + SupervisorJob()),
    var onPremiumRequired: ((SpotifyPremiumRequiredException) -> Unit)? = null,
    var onPlaybackError: ((Throwable) -> Unit)? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "SpotifyCmdDispatcher"

        /**
         * 400ms lead time in microseconds ensuring all peer devices have sufficient time
         * to receive packet, resolve URI, pre-buffer audio stream, and align presentation countdown.
         */
        const val DEFAULT_LEAD_TIME_MICROS = 400_000L
    }

    private val _playbackState = MutableStateFlow<SpotifyPlaybackState>(SpotifyPlaybackState.Idle)
    val playbackState: StateFlow<SpotifyPlaybackState> = _playbackState.asStateFlow()

    /**
     * Currently active or cued track URI.
     */
    var currentTrackUri: String? = null
        private set

    private var activeJob: Job? = null

    /**
     * Resolves the active [SpotifyPlayerApiFacade] instance.
     */
    val activePlayerApi: SpotifyPlayerApiFacade?
        get() = customPlayerApi ?: remoteManager?.currentRemote?.playerApi

    /**
     * Current clock offset in microseconds ($\theta = T_{local} - T_{host}$).
     */
    val currentClockOffsetMicros: Long
        get() = clockOffsetProvider?.invoke() ?: clockOffsetMicros

    // ========================================================================
    // Host Command Dispatches (Computes T_target = T_now + 400ms & Broadcasts)
    // ========================================================================

    /**
     * Host triggers synchronized play of a Spotify track at $T_{\text{target}} = T_{now} + 400\text{ms}$.
     *
     * @param trackUri Canonical Spotify track URI (e.g. `spotify:track:...`).
     * @param startPositionMs Optional starting position in milliseconds (default 0L).
     */
    fun dispatchPlay(trackUri: String, startPositionMs: Long = 0L) {
        if (!isHost) {
            Log.w(TAG, "dispatchPlay called on peer node; only host can initiate synchronized play")
            return
        }

        val nowUs = clock.nowMicros()
        val tTarget = nowUs + DEFAULT_LEAD_TIME_MICROS

        currentTrackUri = trackUri

        val packet = RoomBeatPacket.SpotifyCmd(
            trackUri = trackUri,
            targetPositionMs = startPositionMs,
            targetPresentationTime = tTarget,
            command = RoomBeatPacket.SpotifyCmd.CMD_PLAY
        )

        // Broadcast to peers
        broadcastCmd(packet)

        // Execute locally
        executeSpotifyCmd(packet, tTargetLocal = tTarget)
    }

    /**
     * Host triggers synchronized pause at $T_{\text{target}} = T_{now} + 400\text{ms}$.
     */
    fun dispatchPause() {
        if (!isHost) {
            Log.w(TAG, "dispatchPause called on peer node; only host can initiate pause")
            return
        }

        val trackUri = currentTrackUri ?: ""
        val nowUs = clock.nowMicros()
        val tTarget = nowUs + DEFAULT_LEAD_TIME_MICROS

        val packet = RoomBeatPacket.SpotifyCmd(
            trackUri = trackUri,
            targetPositionMs = 0L,
            targetPresentationTime = tTarget,
            command = RoomBeatPacket.SpotifyCmd.CMD_PAUSE
        )

        broadcastCmd(packet)
        executeSpotifyCmd(packet, tTargetLocal = tTarget)
    }

    /**
     * Host triggers synchronized resume at $T_{\text{target}} = T_{now} + 400\text{ms}$.
     */
    fun dispatchResume() {
        if (!isHost) {
            Log.w(TAG, "dispatchResume called on peer node; only host can initiate resume")
            return
        }

        val trackUri = currentTrackUri ?: ""
        val nowUs = clock.nowMicros()
        val tTarget = nowUs + DEFAULT_LEAD_TIME_MICROS

        val packet = RoomBeatPacket.SpotifyCmd(
            trackUri = trackUri,
            targetPositionMs = 0L,
            targetPresentationTime = tTarget,
            command = RoomBeatPacket.SpotifyCmd.CMD_RESUME
        )

        broadcastCmd(packet)
        executeSpotifyCmd(packet, tTargetLocal = tTarget)
    }

    /**
     * Host triggers synchronized seek to [positionMs] at $T_{\text{target}} = T_{now} + 400\text{ms}$.
     */
    fun dispatchSeek(positionMs: Long) {
        if (!isHost) {
            Log.w(TAG, "dispatchSeek called on peer node; only host can initiate seek")
            return
        }

        val trackUri = currentTrackUri ?: ""
        val nowUs = clock.nowMicros()
        val tTarget = nowUs + DEFAULT_LEAD_TIME_MICROS

        val packet = RoomBeatPacket.SpotifyCmd(
            trackUri = trackUri,
            targetPositionMs = positionMs,
            targetPresentationTime = tTarget,
            command = RoomBeatPacket.SpotifyCmd.CMD_SEEK
        )

        broadcastCmd(packet)
        executeSpotifyCmd(packet, tTargetLocal = tTarget)
    }

    /**
     * Host triggers synchronized skip to next track at $T_{\text{target}} = T_{now} + 400\text{ms}$.
     */
    fun dispatchNext() {
        if (!isHost) {
            Log.w(TAG, "dispatchNext called on peer node; only host can initiate skip")
            return
        }

        val nowUs = clock.nowMicros()
        val tTarget = nowUs + DEFAULT_LEAD_TIME_MICROS

        val packet = RoomBeatPacket.SpotifyCmd(
            trackUri = currentTrackUri ?: "",
            targetPositionMs = 0L,
            targetPresentationTime = tTarget,
            command = RoomBeatPacket.SpotifyCmd.CMD_NEXT
        )

        broadcastCmd(packet)
        executeSpotifyCmd(packet, tTargetLocal = tTarget)
    }

    /**
     * Host triggers synchronized skip to previous track at $T_{\text{target}} = T_{now} + 400\text{ms}$.
     */
    fun dispatchPrevious() {
        if (!isHost) {
            Log.w(TAG, "dispatchPrevious called on peer node; only host can initiate skip")
            return
        }

        val nowUs = clock.nowMicros()
        val tTarget = nowUs + DEFAULT_LEAD_TIME_MICROS

        val packet = RoomBeatPacket.SpotifyCmd(
            trackUri = currentTrackUri ?: "",
            targetPositionMs = 0L,
            targetPresentationTime = tTarget,
            command = RoomBeatPacket.SpotifyCmd.CMD_PREVIOUS
        )

        broadcastCmd(packet)
        executeSpotifyCmd(packet, tTargetLocal = tTarget)
    }

    private fun broadcastCmd(packet: RoomBeatPacket.SpotifyCmd) {
        val send = broadcaster ?: return
        scope.launch {
            try {
                send(packet)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to broadcast SpotifyCmd packet: ${t.message}", t)
            }
        }
    }

    // ========================================================================
    // Peer / Local Command Handler
    // ========================================================================

    /**
     * Handles an incoming [RoomBeatPacket.SpotifyCmd] received from the host.
     * Translates target presentation timestamp to local clock frame ($T_{target, local} = T_{target, host} + \theta$).
     */
    fun handleSpotifyCmd(packet: RoomBeatPacket.SpotifyCmd) {
        val theta = currentClockOffsetMicros
        val tTargetLocal = if (isHost) packet.targetPresentationTime else packet.targetPresentationTime + theta
        executeSpotifyCmd(packet, tTargetLocal)
    }

    /**
     * Schedules and executes the Spotify command against [activePlayerApi] at [tTargetLocal].
     */
    private fun executeSpotifyCmd(packet: RoomBeatPacket.SpotifyCmd, tTargetLocal: Long) {
        activeJob?.cancel()

        if (packet.trackUri.isNotBlank()) {
            currentTrackUri = packet.trackUri
        }

        activeJob = scope.launch {
            val player = activePlayerApi
            if (player == null) {
                val errorMsg = "Spotify PlayerApi is not connected. Pre-warm handshake (SPOTIFY_WARM) may be required."
                Log.e(TAG, errorMsg)
                _playbackState.value = SpotifyPlaybackState.Error(message = errorMsg)
                onPlaybackError?.invoke(IllegalStateException(errorMsg))
                return@launch
            }

            try {
                when (packet.command) {
                    RoomBeatPacket.SpotifyCmd.CMD_PLAY -> {
                        executePlayFlow(player, packet, tTargetLocal)
                    }
                    RoomBeatPacket.SpotifyCmd.CMD_PAUSE -> {
                        executePauseFlow(player, packet, tTargetLocal)
                    }
                    RoomBeatPacket.SpotifyCmd.CMD_RESUME -> {
                        executeResumeFlow(player, packet, tTargetLocal)
                    }
                    RoomBeatPacket.SpotifyCmd.CMD_SEEK -> {
                        executeSeekFlow(player, packet, tTargetLocal)
                    }
                    RoomBeatPacket.SpotifyCmd.CMD_NEXT -> {
                        executeNextFlow(player, packet, tTargetLocal)
                    }
                    RoomBeatPacket.SpotifyCmd.CMD_PREVIOUS -> {
                        executePreviousFlow(player, packet, tTargetLocal)
                    }
                    else -> {
                        Log.w(TAG, "Unrecognized SpotifyCmd command: ${packet.command}")
                    }
                }
            } catch (ce: CancellationException) {
                // Cancelled due to subsequent command dispatch
            } catch (t: Throwable) {
                handleExecutionFailure(t)
            }
        }
    }

    /**
     * Synchronized Play Flow:
     * 1. Immediately invokes `playerApi.play(trackUri)` and pauses it to prime the buffer pipeline.
     * 2. Sets state to [SpotifyPlaybackState.Buffering].
     * 3. Awaits arrival of $T_{target, local}$ via [PlaybackClockScheduler.awaitTarget].
     * 4. Precisely at $T_{target, local}$, if target position > 0, seeks to target position, then resumes playback.
     */
    private suspend fun executePlayFlow(
        player: SpotifyPlayerApiFacade,
        packet: RoomBeatPacket.SpotifyCmd,
        tTargetLocal: Long
    ) {
        _playbackState.value = SpotifyPlaybackState.Buffering(
            trackUri = packet.trackUri,
            targetPresentationTimeUs = tTargetLocal,
            scheduledAction = "PLAY"
        )

        // 1. Prime buffer: invoke play, then immediately pause
        val playResult = player.play(packet.trackUri)
        if (playResult.isFailure) {
            handleExecutionFailure(playResult.exceptionOrNull()!!)
            return
        }

        // Pause to avoid playing ahead of T_target
        val pauseResult = player.pause()
        if (pauseResult.isFailure) {
            handleExecutionFailure(pauseResult.exceptionOrNull()!!)
            return
        }

        // 2. Countdown to T_target
        scheduler.awaitTarget(tTargetLocal)

        // 3. Precisely at target timestamp, seek if needed and resume
        if (packet.targetPositionMs > 0L) {
            val seekResult = player.seekTo(packet.targetPositionMs)
            if (seekResult.isFailure) {
                handleExecutionFailure(seekResult.exceptionOrNull()!!)
                return
            }
        }

        val resumeResult = player.resume()
        if (resumeResult.isFailure) {
            handleExecutionFailure(resumeResult.exceptionOrNull()!!)
            return
        }

        _playbackState.value = SpotifyPlaybackState.Playing(
            trackUri = packet.trackUri,
            positionMs = packet.targetPositionMs,
            startedAtUs = tTargetLocal
        )
    }

    /**
     * Synchronized Pause Flow:
     * Awaits $T_{target, local}$, then issues `playerApi.pause()`.
     */
    private suspend fun executePauseFlow(
        player: SpotifyPlayerApiFacade,
        packet: RoomBeatPacket.SpotifyCmd,
        tTargetLocal: Long
    ) {
        _playbackState.value = SpotifyPlaybackState.Buffering(
            trackUri = currentTrackUri ?: packet.trackUri,
            targetPresentationTimeUs = tTargetLocal,
            scheduledAction = "PAUSE"
        )

        scheduler.awaitTarget(tTargetLocal)

        val result = player.pause()
        if (result.isFailure) {
            handleExecutionFailure(result.exceptionOrNull()!!)
            return
        }

        _playbackState.value = SpotifyPlaybackState.Paused(
            trackUri = currentTrackUri ?: packet.trackUri,
            positionMs = packet.targetPositionMs
        )
    }

    /**
     * Synchronized Resume Flow:
     * Awaits $T_{target, local}$, then issues `playerApi.resume()`.
     */
    private suspend fun executeResumeFlow(
        player: SpotifyPlayerApiFacade,
        packet: RoomBeatPacket.SpotifyCmd,
        tTargetLocal: Long
    ) {
        _playbackState.value = SpotifyPlaybackState.Buffering(
            trackUri = currentTrackUri ?: packet.trackUri,
            targetPresentationTimeUs = tTargetLocal,
            scheduledAction = "RESUME"
        )

        scheduler.awaitTarget(tTargetLocal)

        val result = player.resume()
        if (result.isFailure) {
            handleExecutionFailure(result.exceptionOrNull()!!)
            return
        }

        _playbackState.value = SpotifyPlaybackState.Playing(
            trackUri = currentTrackUri ?: packet.trackUri,
            positionMs = packet.targetPositionMs,
            startedAtUs = tTargetLocal
        )
    }

    /**
     * Synchronized Seek Flow:
     * Awaits $T_{target, local}$, then issues `playerApi.seekTo(positionMs)`.
     */
    private suspend fun executeSeekFlow(
        player: SpotifyPlayerApiFacade,
        packet: RoomBeatPacket.SpotifyCmd,
        tTargetLocal: Long
    ) {
        _playbackState.value = SpotifyPlaybackState.Buffering(
            trackUri = currentTrackUri ?: packet.trackUri,
            targetPresentationTimeUs = tTargetLocal,
            scheduledAction = "SEEK"
        )

        scheduler.awaitTarget(tTargetLocal)

        val result = player.seekTo(packet.targetPositionMs)
        if (result.isFailure) {
            handleExecutionFailure(result.exceptionOrNull()!!)
            return
        }

        _playbackState.value = SpotifyPlaybackState.Playing(
            trackUri = currentTrackUri ?: packet.trackUri,
            positionMs = packet.targetPositionMs,
            startedAtUs = tTargetLocal
        )
    }

    /**
     * Synchronized Skip Next Flow:
     * Awaits $T_{target, local}$, then issues `playerApi.skipNext()`.
     */
    private suspend fun executeNextFlow(
        player: SpotifyPlayerApiFacade,
        packet: RoomBeatPacket.SpotifyCmd,
        tTargetLocal: Long
    ) {
        _playbackState.value = SpotifyPlaybackState.Buffering(
            trackUri = currentTrackUri ?: packet.trackUri,
            targetPresentationTimeUs = tTargetLocal,
            scheduledAction = "NEXT"
        )

        scheduler.awaitTarget(tTargetLocal)

        val result = player.skipNext()
        if (result.isFailure) {
            handleExecutionFailure(result.exceptionOrNull()!!)
            return
        }

        _playbackState.value = SpotifyPlaybackState.Playing(
            trackUri = currentTrackUri ?: packet.trackUri,
            startedAtUs = tTargetLocal
        )
    }

    /**
     * Synchronized Skip Previous Flow:
     * Awaits $T_{target, local}$, then issues `playerApi.skipPrevious()`.
     */
    private suspend fun executePreviousFlow(
        player: SpotifyPlayerApiFacade,
        packet: RoomBeatPacket.SpotifyCmd,
        tTargetLocal: Long
    ) {
        _playbackState.value = SpotifyPlaybackState.Buffering(
            trackUri = currentTrackUri ?: packet.trackUri,
            targetPresentationTimeUs = tTargetLocal,
            scheduledAction = "PREVIOUS"
        )

        scheduler.awaitTarget(tTargetLocal)

        val result = player.skipPrevious()
        if (result.isFailure) {
            handleExecutionFailure(result.exceptionOrNull()!!)
            return
        }

        _playbackState.value = SpotifyPlaybackState.Playing(
            trackUri = currentTrackUri ?: packet.trackUri,
            startedAtUs = tTargetLocal
        )
    }

    /**
     * Analyzes and classifies failures, detecting Non-Premium limitations and triggering alerts.
     */
    private fun handleExecutionFailure(throwable: Throwable) {
        val isPremium = SpotifyErrorClassifier.isPremiumRequired(throwable)
        val message = if (isPremium) {
            "Spotify Premium is required for synchronized on-demand playback and seeking across devices."
        } else {
            throwable.message ?: "Spotify playback command failed"
        }

        Log.e(TAG, "Spotify command execution error (isPremiumRequired=$isPremium): $message", throwable)

        _playbackState.value = SpotifyPlaybackState.Error(
            message = message,
            isPremiumRequired = isPremium,
            cause = throwable
        )

        if (isPremium) {
            val ex = if (throwable is SpotifyPremiumRequiredException) {
                throwable
            } else {
                SpotifyPremiumRequiredException(message, throwable)
            }
            onPremiumRequired?.invoke(ex)
        } else {
            onPlaybackError?.invoke(throwable)
        }
    }

    /**
     * Registers a listener on [PacketDispatcher] to automatically intercept and process
     * incoming [RoomBeatPacket.SpotifyCmd] packets.
     */
    fun registerPacketHandler(dispatcher: PacketDispatcher): HandlerRegistration {
        return dispatcher.registerHandler<RoomBeatPacket.SpotifyCmd> { packet, _ ->
            handleSpotifyCmd(packet)
        }
    }

    override fun close() {
        activeJob?.cancel()
        _playbackState.value = SpotifyPlaybackState.Idle
    }
}
