package com.roombeat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roombeat.app.audio.AudioRmsAnalyzer
import com.roombeat.app.audio.ChannelLevels
import com.roombeat.app.session.PeerConnectionState
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.PeerSessionManager
import com.roombeat.app.session.PlaybackCoordinator
import com.roombeat.app.session.TransportController
import com.roombeat.app.session.TransportState
import com.roombeat.app.session.TransportStatus
import com.roombeat.app.session.VolumeCoordinator
import com.roombeat.app.sync.DriftCorrectionController
import com.roombeat.app.ui.components.StreamTelemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Immutable UI State for the Active Playback Telemetry HUD screen.
 */
data class ActivePlaybackHudUiState(
    val roomName: String = "ROOMBEAT RIG",
    val sessionId: String = "",
    val isHost: Boolean = true,
    val localDeviceId: String = "host_rig",
    val streamTelemetry: StreamTelemetry = StreamTelemetry(),
    val peers: List<PeerNode> = emptyList(),
    val soloedDevices: Set<String> = emptySet(),
    val masterVolume: Float = 1.0f,
    val isMasterMuted: Boolean = false,
    val transportState: TransportState = TransportState(),
    val channelLevels: List<ChannelLevels> = emptyList(),
    val isEndSessionDialogVisible: Boolean = false,
    val isReducedMotion: Boolean = false
) {
    val totalDeviceCount: Int
        get() = (if (isHost) 1 else 0) + peers.count { !it.isDisconnectedOrReconnecting }

    val activePeers: List<PeerNode>
        get() = peers.filter { it.state == PeerConnectionState.CONNECTED }
}

/**
 * ViewModel managing the Active Playback HUD state, live 500ms telemetry cadence,
 * channel strip volume/mute/solo dispatching, VU meter levels, and transport controls.
 */
class ActivePlaybackHudViewModel(
    val volumeCoordinator: VolumeCoordinator = VolumeCoordinator(),
    var sessionManager: PeerSessionManager? = null,
    val audioRmsAnalyzer: AudioRmsAnalyzer? = null,
    var transportController: TransportController? = null,
    var playbackCoordinator: PlaybackCoordinator? = null,
    val driftController: DriftCorrectionController? = null,
    val autoStartTelemetry: Boolean = true
) : ViewModel() {

    companion object {
        const val TELEMETRY_REFRESH_INTERVAL_MS = 500L
    }

    private val _uiState = MutableStateFlow(ActivePlaybackHudUiState())
    val uiState: StateFlow<ActivePlaybackHudUiState> = _uiState.asStateFlow()

    private var telemetryLoopJob: Job? = null

    init {
        sessionManager?.let { bindSessionManager(it) }
        initFlowSubscriptions()
        if (autoStartTelemetry) {
            startTelemetryLoop()
        }
    }

    /**
     * Binds a [PeerSessionManager] to the HUD.
     */
    fun bindSessionManager(manager: PeerSessionManager) {
        this.sessionManager = manager
        volumeCoordinator.attachSessionManager(manager)

        _uiState.update { current ->
            current.copy(
                isHost = manager.isHost,
                sessionId = manager.sessionId,
                peers = manager.peers.value,
                masterVolume = manager.masterVolume.value
            )
        }

        viewModelScope.launch {
            manager.peers.collect { peerList ->
                _uiState.update { it.copy(peers = peerList) }
            }
        }

        viewModelScope.launch {
            manager.masterVolume.collect { masterVol ->
                _uiState.update { it.copy(masterVolume = masterVol) }
            }
        }
    }

    private fun initFlowSubscriptions() {
        // Collect soloed devices from VolumeCoordinator
        viewModelScope.launch {
            volumeCoordinator.soloedDevices.collect { solos ->
                _uiState.update { it.copy(soloedDevices = solos) }
            }
        }

        // Collect master gain
        viewModelScope.launch {
            volumeCoordinator.masterGain.collect { gain ->
                _uiState.update { it.copy(masterVolume = gain) }
            }
        }

        // Collect audio levels for VU meter
        audioRmsAnalyzer?.let { analyzer ->
            viewModelScope.launch {
                analyzer.channelsFlow.collect { channelsMap ->
                    _uiState.update { it.copy(channelLevels = channelsMap.values.toList()) }
                }
            }
        }

        // Collect transport state
        transportController?.let { controller ->
            viewModelScope.launch {
                controller.state.collect { tState ->
                    _uiState.update { it.copy(transportState = tState) }
                }
            }
        }
    }

    /**
     * Periodic 500ms ticker updating real-time stream telemetry smoothly.
     */
    fun startTelemetryLoop() {
        if (telemetryLoopJob != null) return
        telemetryLoopJob = viewModelScope.launch {
            while (isActive) {
                delay(TELEMETRY_REFRESH_INTERVAL_MS)
                updateTelemetryTick()
            }
        }
    }

    /**
     * Stops the periodic telemetry ticker loop.
     */
    fun stopTelemetryLoop() {
        telemetryLoopJob?.cancel()
        telemetryLoopJob = null
    }

    /**
     * Evaluates and updates telemetry state for the current 500ms tick.
     */
    fun updateTelemetryTick() {
        val currentState = _uiState.value
        val driftMs = driftController?.maxDriftMs?.value ?: 0.2
        val isInSync = driftController?.isOverallInSync?.value ?: true
        val speedPpm = driftController?.localSpeedPpm?.value ?: 0

        val connStatus = when {
            speedPpm != 0 -> "DRIFT PULL ${speedPpm}ppm"
            !isInSync -> "SYNC RECOVER"
            else -> "ACTIVE"
        }

        val updatedTelemetry = currentState.streamTelemetry.copy(
            driftMs = driftMs,
            roomName = currentState.roomName,
            peerCount = currentState.totalDeviceCount,
            isLocked = isInSync && abs(driftMs) <= 1.5,
            connectionStatus = connStatus
        )

        _uiState.update { it.copy(streamTelemetry = updatedTelemetry) }
    }

    // ========================================================================
    // Channel Strip Controls (Volume, Mute, Solo)
    // ========================================================================

    /**
     * Sets volume for a specific channel (local device or connected peer).
     */
    fun setDeviceVolume(deviceId: String, linearGain: Float) {
        volumeCoordinator.setDeviceVolume(deviceId, linearGain)
    }

    /**
     * Sets master volume across all participating nodes.
     */
    fun setMasterVolume(linearGain: Float) {
        volumeCoordinator.setMasterVolume(linearGain)
    }

    /**
     * Toggles mute state for a channel.
     */
    fun toggleMute(deviceId: String) {
        volumeCoordinator.toggleMute(deviceId)
    }

    /**
     * Toggles solo state for a channel.
     */
    fun toggleSolo(deviceId: String) {
        volumeCoordinator.toggleSolo(deviceId)
    }

    /**
     * Snaps a channel volume to unity gain (1.0f / 0 dB).
     */
    fun snapDeviceVolume(deviceId: String) {
        volumeCoordinator.setDeviceVolume(deviceId, 1.0f)
    }

    /**
     * Snaps master volume to unity gain (1.0f / 0 dB).
     */
    fun snapMasterVolume() {
        volumeCoordinator.snapMasterToUnityGain()
    }

    // ========================================================================
    // Transport Controls
    // ========================================================================

    fun onPlay() {
        transportController?.play()
    }

    fun onPause() {
        transportController?.pause()
    }

    fun onSeek(positionMs: Long) {
        transportController?.seekTo(positionMs)
    }

    fun onStop() {
        transportController?.stop()
    }

    fun seekByDelta(deltaMs: Long) {
        val current = _uiState.value.transportState.currentPositionMs
        val duration = _uiState.value.transportState.durationMs
        val target = (current + deltaMs).coerceIn(0L, duration.coerceAtLeast(1L))
        onSeek(target)
    }

    // ========================================================================
    // Session Teardown
    // ========================================================================

    fun setEndSessionDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(isEndSessionDialogVisible = visible) }
    }

    fun kickPeer(peerId: String) {
        sessionManager?.kickPeer(peerId)
    }

    fun endSession() {
        telemetryLoopJob?.cancel()
        sessionManager?.leaveRoom()
        transportController?.close()
        playbackCoordinator?.close()
        _uiState.update { it.copy(isEndSessionDialogVisible = false) }
    }

    override fun onCleared() {
        super.onCleared()
        telemetryLoopJob?.cancel()
    }
}
