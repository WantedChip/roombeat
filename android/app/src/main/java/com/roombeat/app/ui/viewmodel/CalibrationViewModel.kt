package com.roombeat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roombeat.app.network.multicast.MulticastHealthProbe
import com.roombeat.app.network.multicast.MulticastHealthStatus
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.PeerSessionManager
import com.roombeat.app.sync.CalibrationProbeEngine
import com.roombeat.app.sync.CalibrationProbeListener
import com.roombeat.app.sync.MonotonicClock
import com.roombeat.app.sync.ProbeSample
import com.roombeat.app.sync.SystemMonotonicClock
import com.roombeat.app.source.spotify.SpotifyAuthState
import com.roombeat.app.source.spotify.SpotifyRemoteManager
import com.roombeat.app.source.spotify.isConnected
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Lifecycle status of the Acoustic Calibration screen.
 */
enum class CalibrationStatus {
    IDLE,
    CALIBRATING,
    SYNC_LOCKED,
    MULTICAST_BLOCKED,
    FAILED
}

/**
 * Per-peer synchronization and network telemetry displayed during calibration.
 */
data class PeerCalibrationUiModel(
    val peerId: String,
    val deviceName: String,
    val offsetMs: Double = 0.0,
    val rttMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val packetLossPercent: Double = 0.0,
    val samplesReceived: Int = 0,
    val totalProbes: Int = 0,
    val isLocked: Boolean = false
) {
    val formattedOffset: String
        get() = String.format(Locale.US, "%+.1fms", offsetMs)

    val formattedRtt: String
        get() = String.format(Locale.US, "%.1fms", rttMs)

    val formattedJitter: String
        get() = String.format(Locale.US, "±%.1fms", jitterMs)

    val formattedLoss: String
        get() = String.format(Locale.US, "%.1f%%", packetLossPercent)
}

/**
 * Immutable UI state for [CalibrationScreen].
 */
data class CalibrationUiState(
    val isHost: Boolean = true,
    val status: CalibrationStatus = CalibrationStatus.IDLE,
    val countdownRemainingSeconds: Int = 5,
    val totalDurationSeconds: Int = 5,
    val calibrationProgress: Float = 0.0f,
    val currentProbeSequence: Int = 0,
    val totalExpectedProbes: Int = 50,
    val peers: List<PeerCalibrationUiModel> = emptyList(),
    val averageOffsetMs: Double = 0.0,
    val maxOffsetMs: Double = 0.0,
    val averageRttMs: Double = 0.0,
    val averageJitterMs: Double = 0.0,
    val averagePacketLossPercent: Double = 0.0,
    val isSyncLocked: Boolean = false,
    val syncLockStamp: String? = null,
    val canProceedToAudioSource: Boolean = false,
    val isMulticastBlocked: Boolean = false,
    val isRouterWarningVisible: Boolean = false,
    val statusMessage: String = "STANDBY · READY TO CALIBRATE",
    val lastPulseTimestamp: Long = 0L,
    val sessionId: String = "",
    val isSpotifyWarmed: Boolean = false
) {
    val formattedAverageOffset: String
        get() = String.format(Locale.US, "%+.2fms", averageOffsetMs)

    val formattedMaxOffset: String
        get() = String.format(Locale.US, "±%.1fms", maxOffsetMs)

    val formattedAverageRtt: String
        get() = String.format(Locale.US, "%.1fms", averageRttMs)

    val formattedAverageJitter: String
        get() = String.format(Locale.US, "±%.2fms", averageJitterMs)

    val formattedAverageLoss: String
        get() = String.format(Locale.US, "%.1f%%", averagePacketLossPercent)
}

/**
 * State holder ViewModel for the Acoustic Calibration Screen.
 *
 * Coordinates:
 * - High-frequency NTP 4-timestamp probe bursts via [CalibrationProbeEngine].
 * - UDP Multicast beacon validation and AP Isolation detection via [MulticastHealthProbe].
 * - Peer lifecycle tracking via [PeerSessionManager].
 * - Sub-millisecond sync-lock determination (< 2.0ms offset tolerance).
 * - Navigation gating to Audio Source Selection.
 */
class CalibrationViewModel(
    initialEngine: CalibrationProbeEngine? = null,
    initialMulticastProbe: MulticastHealthProbe? = null,
    initialSessionManager: PeerSessionManager? = null,
    val clock: MonotonicClock = SystemMonotonicClock,
    val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val totalDurationSeconds: Int = 5,
    val syncToleranceMs: Double = 2.0
) : ViewModel() {

    var engine: CalibrationProbeEngine? = initialEngine
        private set

    var multicastProbe: MulticastHealthProbe? = initialMulticastProbe
        private set

    var sessionManager: PeerSessionManager? = initialSessionManager
        private set

    var isSpotifySource: Boolean = false
        private set

    var spotifyRemoteManager: SpotifyRemoteManager? = null
        private set

    private var spotifyCollectorJob: Job? = null

    private val _uiState = MutableStateFlow(
        CalibrationUiState(
            totalDurationSeconds = totalDurationSeconds,
            countdownRemainingSeconds = totalDurationSeconds
        )
    )
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    private var calibrationJob: Job? = null
    private var peerCollectorJob: Job? = null
    private var multicastCollectorJob: Job? = null

    init {
        initialEngine?.let { bindEngine(it) }
        initialMulticastProbe?.let { bindMulticastProbe(it) }
        initialSessionManager?.let { bindSessionManager(it) }
    }

    /**
     * Binds [SpotifyRemoteManager] and flags whether the active audio source is Spotify,
     * enabling the SPOTIFY_WARM handshake during the calibration phase (Roadmap §8, §10).
     */
    fun bindSpotifyRemote(manager: SpotifyRemoteManager, isSpotifySource: Boolean = true) {
        this.spotifyRemoteManager = manager
        this.isSpotifySource = isSpotifySource

        spotifyCollectorJob?.cancel()
        spotifyCollectorJob = viewModelScope.launch(defaultDispatcher) {
            manager.authState.collect { authState ->
                _uiState.update { current ->
                    current.copy(isSpotifyWarmed = authState is SpotifyAuthState.Connected)
                }
            }
        }
    }

    /**
     * Initializes the calibration session as Host.
     */
    fun initAsHost(
        sessionId: String,
        initialPeers: List<PeerNode> = emptyList(),
        engine: CalibrationProbeEngine? = null,
        multicastProbe: MulticastHealthProbe? = null,
        sessionManager: PeerSessionManager? = null
    ) {
        engine?.let { bindEngine(it) }
        multicastProbe?.let { bindMulticastProbe(it) }
        sessionManager?.let { bindSessionManager(it) }

        val peerModels = initialPeers.map { node ->
            PeerCalibrationUiModel(
                peerId = node.id,
                deviceName = node.name
            )
        }

        _uiState.update { current ->
            current.copy(
                isHost = true,
                sessionId = sessionId,
                peers = peerModels,
                status = CalibrationStatus.IDLE,
                statusMessage = "HOST RIG READY · TAP TO CALIBRATE",
                canProceedToAudioSource = false,
                isSyncLocked = false,
                isMulticastBlocked = false
            )
        }
    }

    /**
     * Initializes the calibration session as Client.
     */
    fun initAsClient(
        sessionId: String,
        hostName: String = "Host Rig",
        engine: CalibrationProbeEngine? = null,
        multicastProbe: MulticastHealthProbe? = null,
        sessionManager: PeerSessionManager? = null
    ) {
        engine?.let { bindEngine(it) }
        multicastProbe?.let { bindMulticastProbe(it) }
        sessionManager?.let { bindSessionManager(it) }

        _uiState.update { current ->
            current.copy(
                isHost = false,
                sessionId = sessionId,
                status = CalibrationStatus.CALIBRATING,
                statusMessage = "CONNECTED TO $hostName · LISTENING FOR PROBES",
                canProceedToAudioSource = false,
                isSyncLocked = false,
                isMulticastBlocked = false
            )
        }
    }

    /**
     * Binds and configures [CalibrationProbeEngine] with this ViewModel.
     */
    fun bindEngine(newEngine: CalibrationProbeEngine) {
        this.engine = newEngine
        newEngine.listener = object : CalibrationProbeListener {
            override fun onProbeDispatched(peerId: String, sequenceNumber: Long, t0: Long) {
                _uiState.update { current ->
                    current.copy(
                        currentProbeSequence = (sequenceNumber + 1).toInt(),
                        lastPulseTimestamp = t0,
                        calibrationProgress = ((sequenceNumber + 1).toFloat() / current.totalExpectedProbes).coerceIn(0f, 1f)
                    )
                }
            }

            override fun onSampleReceived(sample: ProbeSample) {
                val metrics = newEngine.getSyncMetricsForPeer(sample.peerId)
                val stats = newEngine.getPeerStats(sample.peerId)
                updatePeerMetrics(
                    peerId = sample.peerId,
                    offsetMs = metrics.offsetMs,
                    rttMs = metrics.rttMs,
                    jitterMs = metrics.jitterMs,
                    packetLossPercent = stats.packetLossPercent,
                    samplesReceived = stats.samplesReceived,
                    totalProbes = stats.totalProbesSent
                )
            }

            override fun onBurstProgress(peerId: String, completed: Int, total: Int) {
                _uiState.update { current ->
                    current.copy(
                        calibrationProgress = (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    )
                }
            }
        }
    }

    /**
     * Binds and observes [MulticastHealthProbe] state.
     */
    fun bindMulticastProbe(probe: MulticastHealthProbe) {
        this.multicastProbe = probe
        multicastCollectorJob?.cancel()
        multicastCollectorJob = viewModelScope.launch(defaultDispatcher) {
            probe.healthStatus.collect { health ->
                if (health == MulticastHealthStatus.BLOCKED) {
                    _uiState.update {
                        it.copy(
                            isMulticastBlocked = true,
                            status = CalibrationStatus.MULTICAST_BLOCKED,
                            canProceedToAudioSource = false,
                            isSyncLocked = false,
                            statusMessage = "ROUTER AP ISOLATION DETECTED · MULTICAST BLOCKED"
                        )
                    }
                }
            }
        }
    }

    /**
     * Binds and observes [PeerSessionManager] for dynamic peer join/leave/update events.
     */
    fun bindSessionManager(manager: PeerSessionManager) {
        this.sessionManager = manager
        peerCollectorJob?.cancel()
        peerCollectorJob = viewModelScope.launch(defaultDispatcher) {
            manager.peers.collect { activePeers ->
                handlePeersListUpdate(activePeers)
            }
        }
    }

    /**
     * Synchronizes UI peer models with the latest active peers list from session manager.
     */
    fun handlePeersListUpdate(activePeers: List<PeerNode>) {
        _uiState.update { current ->
            val activeMap = activePeers.associateBy { it.id }
            val updatedList = mutableListOf<PeerCalibrationUiModel>()

            // Update existing peers or keep their metrics
            for (p in current.peers) {
                val active = activeMap[p.peerId]
                if (active != null) {
                    updatedList.add(p.copy(deviceName = active.name))
                }
            }

            // Add newly joined peers
            val existingIds = current.peers.map { it.peerId }.toSet()
            for (active in activePeers) {
                if (!existingIds.contains(active.id)) {
                    updatedList.add(
                        PeerCalibrationUiModel(
                            peerId = active.id,
                            deviceName = active.name
                        )
                    )
                }
            }

            val summary = recalculateSummary(updatedList)
            current.copy(
                peers = updatedList,
                averageOffsetMs = summary.avgOffset,
                maxOffsetMs = summary.maxOffset,
                averageRttMs = summary.avgRtt,
                averageJitterMs = summary.avgJitter,
                averagePacketLossPercent = summary.avgLoss
            )
        }
    }

    /**
     * Starts the calibration process (5–10s countdown, probe bursts, multicast verification).
     */
    fun startCalibration(peerIds: Collection<String>? = null) {
        calibrationJob?.cancel()

        _uiState.update { current ->
            current.copy(
                status = CalibrationStatus.CALIBRATING,
                countdownRemainingSeconds = totalDurationSeconds,
                calibrationProgress = 0.0f,
                currentProbeSequence = 0,
                isSyncLocked = false,
                syncLockStamp = null,
                canProceedToAudioSource = false,
                isMulticastBlocked = false,
                statusMessage = "CALIBRATING ACOUSTIC RIG..."
            )
        }

        calibrationJob = viewModelScope.launch(defaultDispatcher) {
            // 1. Launch Multicast Health Probe Burst asynchronously
            multicastProbe?.let { probe ->
                launch(ioDispatcher) {
                    try {
                        probe.broadcastProbeBurst()
                    } catch (_: Exception) {}
                }
            }

            // 2. Launch NTP Calibration Probe Bursts
            val targetPeers = peerIds ?: _uiState.value.peers.map { it.peerId }
            val probeBurstJob = launch {
                engine?.let { eng ->
                    if (targetPeers.isNotEmpty()) {
                        eng.runCalibrationBurstAll(targetPeers)
                    }
                }
            }

            // 3. Launch Spotify pre-warm handshake if Spotify source is active (Roadmap §8, §10, v0.7.0)
            if (isSpotifySource) {
                spotifyRemoteManager?.let { manager ->
                    launch(defaultDispatcher) {
                        try {
                            if (!manager.isConnected) {
                                manager.initiateWarmUp(null, showAuthView = false)
                            }
                            if (_uiState.value.isHost) {
                                sessionManager?.broadcast(manager.createSpotifyWarmPacket())
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // 4. Run Countdown Timer
            for (sec in totalDurationSeconds downTo 1) {
                _uiState.update { it.copy(countdownRemainingSeconds = sec) }
                delay(1000L)
            }
            _uiState.update { it.copy(countdownRemainingSeconds = 0) }

            probeBurstJob.join()

            // 5. Determine Sync-Lock and Update Navigation State
            evaluateSyncQuality()
        }
    }

    /**
     * Retries calibration from scratch.
     */
    fun recalibrate() {
        startCalibration()
    }

    /**
     * Evaluates current peer telemetry and multicast health to determine sync-lock status.
     */
    fun evaluateSyncQuality() {
        val currentState = _uiState.value

        // Check multicast health
        val isMulticastPassed = multicastProbe?.healthStatus?.value?.isBlocked != true && !currentState.isMulticastBlocked
        if (!isMulticastPassed) {
            _uiState.update {
                it.copy(
                    status = CalibrationStatus.MULTICAST_BLOCKED,
                    isMulticastBlocked = true,
                    isSyncLocked = false,
                    canProceedToAudioSource = false,
                    statusMessage = "ROUTER AP ISOLATION DETECTED · MULTICAST BLOCKED"
                )
            }
            return
        }

        // Check peer clock offsets against tolerance (< 2.0ms)
        val peers = currentState.peers
        val allPeersWithinTolerance = if (peers.isEmpty()) {
            true // Standalone host calibration
        } else {
            peers.all { abs(it.offsetMs) < syncToleranceMs }
        }

        if (allPeersWithinTolerance) {
            val maxOffset = if (peers.isEmpty()) 0.0 else peers.maxOf { abs(it.offsetMs) }
            val stamp = String.format(Locale.US, "ACOUSTIC SYNC LOCKED — OFFSET: ±%.1fms", maxOffset)

            _uiState.update {
                it.copy(
                    status = CalibrationStatus.SYNC_LOCKED,
                    isSyncLocked = true,
                    syncLockStamp = stamp,
                    canProceedToAudioSource = it.isHost,
                    statusMessage = "ACOUSTIC RIG LOCKED IN SYNC",
                    peers = it.peers.map { p -> p.copy(isLocked = true) }
                )
            }

            // Broadcast CalibResult packets to all peers
            engine?.broadcastAllCalibResults()
        } else {
            _uiState.update {
                it.copy(
                    status = CalibrationStatus.FAILED,
                    isSyncLocked = false,
                    canProceedToAudioSource = false,
                    statusMessage = "SYNC TOLERANCE EXCEEDED (>2.0ms) · TAP RE-CALIBRATE"
                )
            }
        }
    }

    /**
     * Updates recorded metrics for a specific peer node and recalculates averages.
     */
    fun updatePeerMetrics(
        peerId: String,
        offsetMs: Double,
        rttMs: Double,
        jitterMs: Double,
        packetLossPercent: Double = 0.0,
        samplesReceived: Int = 1,
        totalProbes: Int = 50
    ) {
        _uiState.update { current ->
            val updated = current.peers.map { peer ->
                if (peer.peerId == peerId) {
                    peer.copy(
                        offsetMs = offsetMs,
                        rttMs = rttMs,
                        jitterMs = jitterMs,
                        packetLossPercent = packetLossPercent,
                        samplesReceived = samplesReceived,
                        totalProbes = totalProbes,
                        isLocked = abs(offsetMs) < syncToleranceMs
                    )
                } else {
                    peer
                }
            }

            val summary = recalculateSummary(updated)
            current.copy(
                peers = updated,
                averageOffsetMs = summary.avgOffset,
                maxOffsetMs = summary.maxOffset,
                averageRttMs = summary.avgRtt,
                averageJitterMs = summary.avgJitter,
                averagePacketLossPercent = summary.avgLoss
            )
        }
    }

    /**
     * Directly adds or updates a peer in the calibration UI state.
     */
    fun addOrUpdatePeer(peer: PeerCalibrationUiModel) {
        _uiState.update { current ->
            val exists = current.peers.any { it.peerId == peer.peerId }
            val updated = if (exists) {
                current.peers.map { if (it.peerId == peer.peerId) peer else it }
            } else {
                current.peers + peer
            }
            val summary = recalculateSummary(updated)
            current.copy(
                peers = updated,
                averageOffsetMs = summary.avgOffset,
                maxOffsetMs = summary.maxOffset,
                averageRttMs = summary.avgRtt,
                averageJitterMs = summary.avgJitter,
                averagePacketLossPercent = summary.avgLoss
            )
        }
    }

    /**
     * Removes a peer from the calibration UI state.
     */
    fun removePeer(peerId: String) {
        _uiState.update { current ->
            val updated = current.peers.filter { it.peerId != peerId }
            val summary = recalculateSummary(updated)
            current.copy(
                peers = updated,
                averageOffsetMs = summary.avgOffset,
                maxOffsetMs = summary.maxOffset,
                averageRttMs = summary.avgRtt,
                averageJitterMs = summary.avgJitter,
                averagePacketLossPercent = summary.avgLoss
            )
        }
    }

    /**
     * Central message processor for calibration-related packets.
     */
    fun handleIncomingPacket(senderId: String, packet: RoomBeatPacket): Boolean {
        when (packet) {
            is RoomBeatPacket.CalibProbe -> {
                val handled = engine?.handleIncomingPacket(senderId, packet) ?: false
                _uiState.update { it.copy(lastPulseTimestamp = packet.t0) }
                return handled
            }
            is RoomBeatPacket.CalibEcho -> {
                return engine?.handleIncomingPacket(senderId, packet) ?: false
            }
            is RoomBeatPacket.CalibResult -> {
                handleCalibResult(packet)
                return true
            }
            is RoomBeatPacket.MulticastProbeReport -> {
                evaluateMulticastReport(packet)
                return true
            }
            else -> return false
        }
    }

    /**
     * Handles incoming calculated calibration results from the host (Client mode).
     */
    fun handleCalibResult(result: RoomBeatPacket.CalibResult) {
        val isOffsetOk = abs(result.offsetMs) < syncToleranceMs
        val isMulticastOk = !_uiState.value.isMulticastBlocked
        val isLocked = isOffsetOk && isMulticastOk
        val stamp = String.format(Locale.US, "ACOUSTIC SYNC LOCKED — OFFSET: ±%.1fms", abs(result.offsetMs))

        _uiState.update { current ->
            current.copy(
                averageOffsetMs = result.offsetMs,
                maxOffsetMs = abs(result.offsetMs),
                averageRttMs = result.rttMs,
                averageJitterMs = result.jitterMs,
                isSyncLocked = isLocked,
                status = if (isLocked) CalibrationStatus.SYNC_LOCKED else current.status,
                syncLockStamp = if (isLocked) stamp else null,
                statusMessage = if (isLocked) "ACOUSTIC RIG LOCKED IN SYNC" else "CALIBRATING WITH HOST..."
            )
        }
    }

    /**
     * Evaluates a multicast probe report, triggering the fallback banner if blocked.
     */
    fun evaluateMulticastReport(report: RoomBeatPacket.MulticastProbeReport) {
        multicastProbe?.evaluateClientReport(report)
        if (report.isBlocked || report.receptionRate < MulticastHealthProbe.PASS_THRESHOLD_RATE) {
            _uiState.update {
                it.copy(
                    isMulticastBlocked = true,
                    status = CalibrationStatus.MULTICAST_BLOCKED,
                    canProceedToAudioSource = false,
                    isSyncLocked = false,
                    statusMessage = "ROUTER AP ISOLATION DETECTED · MULTICAST BLOCKED"
                )
            }
        } else {
            evaluateSyncQuality()
        }
    }

    /**
     * Sets visibility for the [RouterWarningDialog].
     */
    fun showRouterWarning(visible: Boolean) {
        _uiState.update { it.copy(isRouterWarningVisible = visible) }
    }

    /**
     * Manually overrides multicast blocked state (e.g. for fallback testing or dismissal).
     */
    fun setMulticastBlocked(blocked: Boolean) {
        _uiState.update {
            it.copy(
                isMulticastBlocked = blocked,
                status = if (blocked) CalibrationStatus.MULTICAST_BLOCKED else it.status,
                canProceedToAudioSource = if (blocked) false else it.canProceedToAudioSource
            )
        }
    }

    /**
     * Gated navigation check to Audio Source Selection.
     * @return true if sync-lock is achieved and navigation is allowed.
     */
    fun proceedToAudioSource(): Boolean {
        return _uiState.value.canProceedToAudioSource
    }

    private data class TelemetrySummary(
        val avgOffset: Double,
        val maxOffset: Double,
        val avgRtt: Double,
        val avgJitter: Double,
        val avgLoss: Double
    )

    private fun recalculateSummary(peers: List<PeerCalibrationUiModel>): TelemetrySummary {
        if (peers.isEmpty()) {
            return TelemetrySummary(0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val avgOffset = peers.map { it.offsetMs }.average()
        val maxOffset = peers.maxOf { abs(it.offsetMs) }
        val avgRtt = peers.map { it.rttMs }.average()
        val avgJitter = peers.map { it.jitterMs }.average()
        val avgLoss = peers.map { it.packetLossPercent }.average()
        return TelemetrySummary(avgOffset, maxOffset, avgRtt, avgJitter, avgLoss)
    }

    override fun onCleared() {
        super.onCleared()
        calibrationJob?.cancel()
        peerCollectorJob?.cancel()
        multicastCollectorJob?.cancel()
    }
}
