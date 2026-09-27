package com.roombeat.app.session

import android.util.Log
import com.roombeat.app.audio.AudioEngineState
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.network.client.ClientSocketConnection
import com.roombeat.app.network.discovery.NsdClientDiscovery
import com.roombeat.app.network.discovery.NsdHostService
import com.roombeat.app.network.multicast.MulticastBroadcaster
import com.roombeat.app.network.multicast.MulticastReceiver
import com.roombeat.app.network.server.HostSessionServer
import com.roombeat.app.protocol.PacketSerializer
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.system.PowerLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10

/**
 * Sequential phases during graceful session teardown.
 */
enum class TeardownPhase {
    IDLE,
    FADING_OUT,
    STOPPING_AUDIO,
    RELEASING_LOCKS,
    CLOSING_SOCKETS,
    STOPPING_SERVICES,
    COMPLETED,
    FAILED
}

/**
 * Lifecycle state for peer-side unexpected host connection loss and recovery.
 */
sealed interface RecoveryState {
    data object Idle : RecoveryState
    data object Connected : RecoveryState
    data class HostLost(
        val attemptCount: Int = 1,
        val maxAttempts: Int = 3,
        val elapsedSinceLastPacketMs: Long = 3000L
    ) : RecoveryState
    data class Reconnecting(
        val attemptCount: Int,
        val maxAttempts: Int
    ) : RecoveryState
    data class RecoveryFailed(
        val reason: String
    ) : RecoveryState
    data object ReturnedToLobby : RecoveryState
}

/**
 * Centralized session teardown and unexpected disconnect recovery coordinator.
 *
 * Implements sub-phase v0.8.3:
 * 1. Host Session Termination Flow:
 *    - Broadcasts [RoomBeatPacket.SessionEnd] to all connected peers.
 *    - Executes clean 50ms audio fade-out to prevent pops/clicks before stopping streams.
 *    - Stops Oboe native audio stream pipelines and resets jitter buffer.
 *    - Releases MulticastLock, low-latency WifiLock, and partial WakeLock via [PowerLockManager].
 *    - Unregisters mDNS service ([NsdHostService]), closes UDP multicast broadcaster, and stops [HostSessionServer].
 *    - Stops foreground capture service ([RoomBeatCaptureService]) and releases MediaProjection.
 *    - Clears active session state so a new session can start immediately without app restart.
 *
 * 2. Peer Leave Flow:
 *    - Dispatches [RoomBeatPacket.RoomLeave] with deviceId to host.
 *    - Executes 50ms audio fade-out and stops local playback pipeline.
 *    - Releases local system locks, stops mDNS discovery, closes receiver, and disconnects client socket.
 *    - Smoothly transitions peer back to mode select or lobby without disturbing other nodes.
 *
 * 3. Unexpected Host Loss Recovery:
 *    - Detects keep-alive timeout (>3s without packets or heartbeat).
 *    - Immediately executes 50ms audio fade-out to prevent speaker pops/clicks.
 *    - Transitions state to [RecoveryState.HostLost] exposing retry reconnect and return to lobby flows.
 *    - Safe coroutine watchdog lifecycle: [autoStartWatchdog] is false by default to prevent virtual time hangs in tests.
 */
class SessionTeardownManager(
    var isHost: Boolean = true,
    var localDeviceId: String = "local_node",
    var powerLockManager: PowerLockManager? = null,
    var audioEngine: NativeAudioEngine? = null,
    var playbackCoordinator: PlaybackCoordinator? = null,
    var volumeCoordinator: VolumeCoordinator? = null,
    var sessionManager: PeerSessionManager? = null,
    var hostServer: HostSessionServer? = null,
    var clientSocket: ClientSocketConnection? = null,
    var nsdHostService: NsdHostService? = null,
    var nsdClientDiscovery: NsdClientDiscovery? = null,
    var multicastBroadcaster: MulticastBroadcaster? = null,
    var multicastReceiver: MulticastReceiver? = null,
    var onStopForegroundService: (() -> Unit)? = null,
    var onReleaseMediaProjection: (() -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    val autoStartWatchdog: Boolean = false,
    val hostLossTimeoutMs: Long = DEFAULT_HOST_LOSS_TIMEOUT_MS,
    val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS
) : AutoCloseable {

    companion object {
        private const val TAG = "SessionTeardownManager"
        const val DEFAULT_HOST_LOSS_TIMEOUT_MS = 3000L
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 3
        const val DEFAULT_FADE_DURATION_MS = 50L
        const val DEFAULT_FADE_STEPS = 5
    }

    private val _teardownPhase = MutableStateFlow(TeardownPhase.IDLE)
    val teardownPhase: StateFlow<TeardownPhase> = _teardownPhase.asStateFlow()

    private val _recoveryState = MutableStateFlow<RecoveryState>(RecoveryState.Idle)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()

    private val _lastPacketTimestampMs = MutableStateFlow(timeProvider())
    val lastPacketTimestampMs: StateFlow<Long> = _lastPacketTimestampMs.asStateFlow()

    private var watchdogJob: Job? = null
    private val teardownLock = Any()

    init {
        if (!isHost && autoStartWatchdog) {
            startWatchdog()
        }
    }

    // ========================================================================
    // 1. Audio Fade-Out Engine (Click / Pop Prevention)
    // ========================================================================

    /**
     * Executes clean linear-to-logarithmic audio fade-out over [durationMs] (default 50ms)
     * across [volumeCoordinator] and [audioEngine].
     */
    suspend fun fadeOutAudio(
        durationMs: Long = DEFAULT_FADE_DURATION_MS,
        steps: Int = DEFAULT_FADE_STEPS,
        onStepGain: ((Float) -> Unit)? = null
    ) {
        val isAudioActive = playbackCoordinator?.state?.value == PlaybackState.PLAYING ||
            audioEngine?.state == AudioEngineState.STREAMING

        if (!isAudioActive || durationMs <= 0L) {
            volumeCoordinator?.setMasterVolume(0.0f)
            audioEngine?.setMasterVolume(-100.0f)
            audioEngine?.setMuted(true)
            onStepGain?.invoke(0.0f)
            return
        }

        val initialGain = volumeCoordinator?.masterGain?.value ?: 1.0f
        val stepDelay = (durationMs / steps.coerceAtLeast(1)).coerceAtLeast(1L)

        for (step in 1..steps) {
            val progress = step.toFloat() / steps.toFloat()
            val currentGain = (initialGain * (1.0f - progress)).coerceAtLeast(0.0f)

            try {
                volumeCoordinator?.setMasterVolume(currentGain)
                audioEngine?.let { engine ->
                    if (currentGain <= 0.0001f) {
                        engine.setMasterVolume(-100.0f)
                        engine.setMuted(true)
                    } else {
                        val db = (20.0 * log10(currentGain.toDouble())).toFloat()
                        engine.setMasterVolume(db)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception during audio fade step $step: ${e.message}")
            }

            onStepGain?.invoke(currentGain)
            delay(stepDelay)
        }

        // Finalize silence
        volumeCoordinator?.setMasterVolume(0.0f)
        audioEngine?.setMasterVolume(-100.0f)
        audioEngine?.setMuted(true)
    }

    // ========================================================================
    // 2. Host Session Teardown Flow
    // ========================================================================

    /**
     * Gracefully terminates an active host session, broadcasting [RoomBeatPacket.SessionEnd]
     * to all connected peer nodes, fading out audio, releasing locks, closing sockets,
     * stopping background services, and resetting state for immediate new session creation.
     *
     * @param reason Optional termination reason string.
     * @param fadeDurationMs Duration in milliseconds for clean audio fade-out (default 50ms).
     * @return true if teardown completed cleanly.
     */
    suspend fun teardownHostSession(
        reason: String? = "Host terminated session",
        fadeDurationMs: Long = DEFAULT_FADE_DURATION_MS
    ): Boolean = synchronized(teardownLock) {
        if (_teardownPhase.value == TeardownPhase.COMPLETED) {
            return true
        }
        _teardownPhase.value = TeardownPhase.FADING_OUT
        true
    }.let {
        try {
            // 1. Broadcast SESSION_END to all peers
            val endPacket = RoomBeatPacket.SessionEnd(reason = reason)
            try {
                sessionManager?.broadcast(endPacket)
            } catch (e: Exception) {
                Log.w(TAG, "Error broadcasting SessionEnd via sessionManager: ${e.message}")
            }
            try {
                val json = PacketSerializer.serialize(endPacket)
                hostServer?.broadcastMessage(json)
            } catch (e: Exception) {
                Log.w(TAG, "Error broadcasting SessionEnd via hostServer: ${e.message}")
            }

            // 2. Execute clean 50ms audio fade-out to prevent clicks/pops
            fadeOutAudio(durationMs = fadeDurationMs)

            // 3. Stop audio streams and playback pipeline
            _teardownPhase.value = TeardownPhase.STOPPING_AUDIO
            try {
                playbackCoordinator?.stop()
                playbackCoordinator?.reset()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping playback coordinator: ${e.message}")
            }
            try {
                audioEngine?.stopStream()
                audioEngine?.teardownEngine()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping native audio engine: ${e.message}")
            }

            // 4. Release MulticastLock, WifiLock, and WakeLock
            _teardownPhase.value = TeardownPhase.RELEASING_LOCKS
            try {
                powerLockManager?.releaseAll()
                powerLockManager?.assertNoLocksHeld("SessionTeardownManager.teardownHostSession", throwOnError = false)
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing power locks: ${e.message}")
            }

            // 5. Close mDNS, multicast broadcaster, and host socket server
            _teardownPhase.value = TeardownPhase.CLOSING_SOCKETS
            try {
                nsdHostService?.unregisterService()
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering NSD host service: ${e.message}")
            }
            try {
                multicastBroadcaster?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing multicast broadcaster: ${e.message}")
            }
            try {
                hostServer?.stopServer()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping host server: ${e.message}")
            }

            // 6. Stop foreground service & release MediaProjection
            _teardownPhase.value = TeardownPhase.STOPPING_SERVICES
            try {
                onStopForegroundService?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping foreground service: ${e.message}")
            }
            try {
                onReleaseMediaProjection?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaProjection: ${e.message}")
            }

            // 7. Clear active session state
            try {
                sessionManager?.leaveRoom()
            } catch (e: Exception) {
                Log.w(TAG, "Error resetting peer session manager: ${e.message}")
            }
            stopWatchdog()

            _recoveryState.value = RecoveryState.Idle
            _teardownPhase.value = TeardownPhase.COMPLETED
            Log.i(TAG, "Host session cleanly torn down")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during host teardown: ${e.message}", e)
            _teardownPhase.value = TeardownPhase.FAILED
            false
        }
    }

    // ========================================================================
    // 3. Peer Leave Flow
    // ========================================================================

    /**
     * Gracefully leaves an active session as a peer node, dispatching [RoomBeatPacket.RoomLeave]
     * to the host, fading out local audio, and releasing local system and network resources.
     *
     * @param deviceId Local device identifier.
     * @param fadeDurationMs Duration in milliseconds for clean audio fade-out (default 50ms).
     * @return true if peer leave completed cleanly.
     */
    suspend fun leaveSessionAsPeer(
        deviceId: String = localDeviceId,
        fadeDurationMs: Long = DEFAULT_FADE_DURATION_MS
    ): Boolean = synchronized(teardownLock) {
        if (_teardownPhase.value == TeardownPhase.COMPLETED) {
            return true
        }
        _teardownPhase.value = TeardownPhase.FADING_OUT
        true
    }.let {
        try {
            // 1. Send ROOM_LEAVE to host
            val leavePacket = RoomBeatPacket.RoomLeave(deviceId = deviceId)
            try {
                val json = PacketSerializer.serialize(leavePacket)
                clientSocket?.sendMessage(json)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending RoomLeave packet: ${e.message}")
            }

            // 2. 50ms clean audio fade-out
            fadeOutAudio(durationMs = fadeDurationMs)

            // 3. Stop local audio stream and playback coordinator
            _teardownPhase.value = TeardownPhase.STOPPING_AUDIO
            try {
                playbackCoordinator?.stop()
                playbackCoordinator?.reset()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping playback coordinator: ${e.message}")
            }
            try {
                audioEngine?.stopStream()
                audioEngine?.teardownEngine()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping native audio engine: ${e.message}")
            }

            // 4. Release local locks
            _teardownPhase.value = TeardownPhase.RELEASING_LOCKS
            try {
                powerLockManager?.releaseAll()
                powerLockManager?.assertNoLocksHeld("SessionTeardownManager.teardownPeerSession", throwOnError = false)
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing power locks: ${e.message}")
            }

            // 5. Close local sockets, discovery, and multicast receiver
            _teardownPhase.value = TeardownPhase.CLOSING_SOCKETS
            try {
                nsdClientDiscovery?.stopDiscovery()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping NSD discovery: ${e.message}")
            }
            try {
                multicastReceiver?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing multicast receiver: ${e.message}")
            }
            try {
                clientSocket?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting client socket: ${e.message}")
            }

            // 6. Clear local session state
            _teardownPhase.value = TeardownPhase.STOPPING_SERVICES
            try {
                sessionManager?.leaveRoom()
            } catch (e: Exception) {
                Log.w(TAG, "Error leaving session in manager: ${e.message}")
            }
            stopWatchdog()

            _recoveryState.value = RecoveryState.Idle
            _teardownPhase.value = TeardownPhase.COMPLETED
            Log.i(TAG, "Peer cleanly left session: $deviceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during peer leave: ${e.message}", e)
            _teardownPhase.value = TeardownPhase.FAILED
            false
        }
    }

    /**
     * Handles reception of [RoomBeatPacket.SessionEnd] on a peer device.
     */
    suspend fun onHostSessionEnded(
        reason: String? = null,
        fadeDurationMs: Long = DEFAULT_FADE_DURATION_MS
    ) {
        Log.i(TAG, "Received SESSION_END from host: $reason")
        leaveSessionAsPeer(deviceId = localDeviceId, fadeDurationMs = fadeDurationMs)
    }

    // ========================================================================
    // 4. Unexpected Host Loss & Recovery Engine
    // ========================================================================

    /**
     * Records reception of an incoming packet or heartbeat to refresh the liveness timestamp.
     */
    fun notifyPacketReceived(timestampMs: Long = timeProvider()) {
        _lastPacketTimestampMs.value = timestampMs
        if (_recoveryState.value is RecoveryState.HostLost || _recoveryState.value is RecoveryState.Reconnecting) {
            _recoveryState.value = RecoveryState.Connected
        }
    }

    /**
     * Checks if the time elapsed since the last received packet exceeds [hostLossTimeoutMs].
     * If timed out, triggers host loss handling.
     *
     * @return true if host is lost, false if connection is healthy.
     */
    suspend fun checkHostLiveness(): Boolean {
        val now = timeProvider()
        val elapsed = now - _lastPacketTimestampMs.value
        return if (elapsed >= hostLossTimeoutMs) {
            if (_recoveryState.value !is RecoveryState.HostLost &&
                _recoveryState.value !is RecoveryState.Reconnecting &&
                _recoveryState.value !is RecoveryState.RecoveryFailed &&
                _recoveryState.value !is RecoveryState.ReturnedToLobby &&
                _teardownPhase.value != TeardownPhase.COMPLETED
            ) {
                handleHostLoss(attemptCount = 1, maxAttempts = maxReconnectAttempts)
            }
            true
        } else {
            false
        }
    }

    /**
     * Handles unexpected host connection drop:
     * 1. Executes 50ms audio fade-out to prevent clicks/pops.
     * 2. Pauses local playback pipeline.
     * 3. Transitions to [RecoveryState.HostLost] allowing user to Retry Reconnect or Return to Lobby.
     */
    suspend fun handleHostLoss(
        attemptCount: Int = 1,
        maxAttempts: Int = maxReconnectAttempts
    ) {
        val elapsed = (timeProvider() - _lastPacketTimestampMs.value).coerceAtLeast(0L)
        Log.w(TAG, "Unexpected host loss detected (timeout > ${hostLossTimeoutMs}ms, elapsed=${elapsed}ms)")

        // 1. 50ms clean audio fade-out to avoid pops/clicks
        fadeOutAudio(durationMs = DEFAULT_FADE_DURATION_MS)

        // 2. Pause local playback coordinator
        try {
            playbackCoordinator?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing playback coordinator: ${e.message}")
        }

        // 3. Update recovery state
        _recoveryState.value = RecoveryState.HostLost(
            attemptCount = attemptCount,
            maxAttempts = maxAttempts,
            elapsedSinceLastPacketMs = elapsed
        )
    }

    /**
     * User action: Attempts to reconnect to the lost host node.
     *
     * @param reconnectAction Optional custom reconnect suspend lambda (useful for unit tests).
     * @return true if reconnected successfully, false otherwise.
     */
    suspend fun retryReconnect(
        reconnectAction: (suspend () -> Boolean)? = null
    ): Boolean {
        val currentLost = _recoveryState.value as? RecoveryState.HostLost
        val attempt = currentLost?.attemptCount ?: 1
        val maxAttempts = currentLost?.maxAttempts ?: maxReconnectAttempts

        _recoveryState.value = RecoveryState.Reconnecting(attemptCount = attempt, maxAttempts = maxAttempts)

        val success = try {
            if (reconnectAction != null) {
                reconnectAction()
            } else {
                val host = clientSocket?.targetHost
                val port = clientSocket?.targetPort ?: -1
                if (host != null && port > 0) {
                    clientSocket?.connect(host, port) == true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during reconnect attempt $attempt: ${e.message}")
            false
        }

        return if (success) {
            Log.i(TAG, "Successfully reconnected to host on attempt $attempt")
            _recoveryState.value = RecoveryState.Connected
            _lastPacketTimestampMs.value = timeProvider()
            // Restore playback volume
            volumeCoordinator?.setMasterVolume(1.0f)
            audioEngine?.setMasterVolume(0.0f)
            audioEngine?.setMuted(false)
            true
        } else {
            Log.w(TAG, "Reconnect failed on attempt $attempt of $maxAttempts")
            if (attempt < maxAttempts) {
                _recoveryState.value = RecoveryState.HostLost(
                    attemptCount = attempt + 1,
                    maxAttempts = maxAttempts,
                    elapsedSinceLastPacketMs = timeProvider() - _lastPacketTimestampMs.value
                )
            } else {
                _recoveryState.value = RecoveryState.RecoveryFailed(
                    reason = "Failed to reconnect to host after $maxAttempts attempts"
                )
            }
            false
        }
    }

    /**
     * User action: Abandons recovery and returns cleanly to the lobby or mode selection.
     * Executes local resource teardown.
     */
    suspend fun returnToLobby(): Boolean {
        leaveSessionAsPeer(deviceId = localDeviceId, fadeDurationMs = 0L)
        _recoveryState.value = RecoveryState.ReturnedToLobby
        return true
    }

    /**
     * Dismisses the active recovery state back to [RecoveryState.Idle].
     */
    fun dismissRecovery() {
        _recoveryState.value = RecoveryState.Idle
    }

    // ========================================================================
    // 5. Watchdog Loop Lifecycle
    // ========================================================================

    /**
     * Starts the periodic host liveness watchdog loop.
     */
    fun startWatchdog(intervalMs: Long = 1000L) {
        if (watchdogJob != null) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!isHost && _recoveryState.value != RecoveryState.Idle && _recoveryState.value != RecoveryState.ReturnedToLobby) {
                    checkHostLiveness()
                }
            }
        }
    }

    /**
     * Stops the periodic host liveness watchdog loop.
     */
    fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Resets internal teardown and recovery states so a new session can start immediately.
     */
    fun resetForNewSession() {
        stopWatchdog()
        _teardownPhase.value = TeardownPhase.IDLE
        _recoveryState.value = RecoveryState.Idle
        _lastPacketTimestampMs.value = timeProvider()
    }

    override fun close() {
        stopWatchdog()
        try {
            powerLockManager?.releaseAll()
        } catch (_: Exception) {}
    }
}
