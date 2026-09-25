package com.roombeat.app.source.spotify

import android.util.Log
import com.roombeat.app.protocol.HandlerRegistration
import com.roombeat.app.protocol.PacketContext
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Real-time telemetry snapshot for a peer node participating in Spotify synchronized playback (Sub-phase v0.7.2).
 */
data class SpotifyPeerTelemetry(
    val deviceId: String,
    val reportedPositionMs: Long,
    val sampledAtUs: Long,
    val projectedPositionMs: Long,
    val expectedMasterPositionMs: Long,
    val driftMs: Long,
    val isSynced: Boolean,
    val lastReportReceivedAtMs: Long = System.currentTimeMillis(),
    val totalReportsReceived: Long = 0L,
    val correctionsIssued: Long = 0L
) {
    /**
     * Absolute magnitude of playback position drift in milliseconds.
     */
    val absDriftMs: Long get() = abs(driftMs)

    /**
     * Human-readable drift string with direction indicator:
     * e.g. "+35ms" (leading), "-42ms" (lagging), "0ms" (locked).
     */
    val formattedDrift: String
        get() = when {
            driftMs > 0 -> "+${driftMs}ms"
            driftMs < 0 -> "${driftMs}ms"
            else -> "0ms"
        }
}

/**
 * Peer client position reporter (Sub-phase v0.7.2 / Roadmap §8, §10, §11).
 *
 * Responsibilities:
 * 1. Subscribes to Spotify `PlayerState` events via [SpotifyPlayerApiFacade.subscribeToPlayerState].
 * 2. Emits [RoomBeatPacket.SpotifyStateReport] to the host every 1.5 seconds during active playback.
 * 3. Immediately detects and reports significant state changes:
 *    - Track transitions / playlist progression (new track URI)
 *    - Play/pause/resume transitions
 *    - Explicit user seek operations or unexpected position jumps ($>500\text{ms}$)
 * 4. Dispatches reports over the socket control plane via the provided [sender] function.
 */
class SpotifyPeerPositionReporter(
    val deviceId: String,
    val playerApi: SpotifyPlayerApiFacade,
    val clock: MonotonicClock = SystemMonotonicClock,
    val reportIntervalMs: Long = DEFAULT_REPORT_INTERVAL_MS,
    val significantJumpThresholdMs: Long = DEFAULT_SIGNIFICANT_JUMP_THRESHOLD_MS,
    val sender: suspend (RoomBeatPacket.SpotifyStateReport) -> Unit,
    val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(coroutineDispatcher + SupervisorJob())
) : AutoCloseable {

    companion object {
        private const val TAG = "SpotifyPeerReporter"

        /**
         * Standard 1.5-second reporting cadence per sub-phase v0.7.2 specification.
         */
        const val DEFAULT_REPORT_INTERVAL_MS = 1500L

        /**
         * Threshold to detect an abrupt seek or track jump rather than normal elapsed playback.
         */
        const val DEFAULT_SIGNIFICANT_JUMP_THRESHOLD_MS = 500L
    }

    private var subscriptionJob: Job? = null
    private var periodicJob: Job? = null

    private var latestSnapshot: SpotifyPlayerStateSnapshot? = null
    private var lastReportedPositionMs: Long = -1L
    private var lastReportedAtUs: Long = 0L
    private var lastReportedTrackUri: String = ""
    private var lastReportedIsPaused: Boolean? = null

    private val _totalReportsSent = AtomicLong(0L)
    val totalReportsSent: Long get() = _totalReportsSent.get()

    val isRunning: Boolean
        get() = subscriptionJob?.isActive == true || periodicJob?.isActive == true

    /**
     * Starts listening to Spotify player state events and launches the 1.5s periodic reporting loop.
     */
    fun start() {
        if (isRunning) return

        subscriptionJob = scope.launch {
            try {
                playerApi.subscribeToPlayerState().collect { snapshot ->
                    onPlayerStateUpdated(snapshot)
                }
            } catch (ce: CancellationException) {
                // Cooperative cancellation
            } catch (t: Throwable) {
                Log.e(TAG, "Error collecting player state on peer $deviceId: ${t.message}", t)
            }
        }

        periodicJob = scope.launch {
            try {
                while (isActive) {
                    delay(reportIntervalMs)
                    val snapshot = latestSnapshot
                    if (snapshot != null && !snapshot.isPaused) {
                        emitReport(snapshot, isSignificantChange = false)
                    }
                }
            } catch (ce: CancellationException) {
                // Periodic loop stopped
            }
        }
    }

    /**
     * Stops state tracking and cancels all active reporting coroutines.
     */
    fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        periodicJob?.cancel()
        periodicJob = null
    }

    /**
     * Forces immediate report emission using latest known state or querying player API directly.
     */
    fun reportNow() {
        scope.launch {
            val snapshot = latestSnapshot ?: playerApi.getPlayerState().getOrNull()
            if (snapshot != null) {
                emitReport(snapshot, isSignificantChange = true)
            }
        }
    }

    private suspend fun onPlayerStateUpdated(snapshot: SpotifyPlayerStateSnapshot) {
        val previousSnapshot = latestSnapshot
        latestSnapshot = snapshot

        val isSignificant = isSignificantStateChange(previousSnapshot, snapshot)
        if (isSignificant) {
            emitReport(snapshot, isSignificantChange = true)
        }
    }

    /**
     * Determines whether a new player state represents a significant event requiring immediate reporting.
     */
    private fun isSignificantStateChange(
        old: SpotifyPlayerStateSnapshot?,
        new: SpotifyPlayerStateSnapshot
    ): Boolean {
        if (old == null) return true

        // 1. Track changed
        if (old.trackUri != new.trackUri && new.trackUri.isNotEmpty()) {
            return true
        }

        // 2. Play/Pause state flipped
        if (old.isPaused != new.isPaused) {
            return true
        }

        // 3. User seek or discontinuous position jump
        if (!new.isPaused && !old.isPaused) {
            val expectedElapsedMs = (new.sampledAtMs - old.sampledAtMs).coerceAtLeast(0L)
            val actualPositionDelta = new.playbackPositionMs - old.playbackPositionMs
            val discrepancy = abs(actualPositionDelta - expectedElapsedMs)
            if (discrepancy > significantJumpThresholdMs) {
                return true
            }
        }

        return false
    }

    private suspend fun emitReport(
        snapshot: SpotifyPlayerStateSnapshot,
        isSignificantChange: Boolean
    ) {
        val nowUs = clock.nowMicros()

        // Debounce: avoid duplicate zero-time transmissions
        if (!isSignificantChange && nowUs - lastReportedAtUs < (reportIntervalMs * 800L)) {
            return
        }

        val packet = RoomBeatPacket.SpotifyStateReport(
            deviceId = deviceId,
            reportedPositionMs = snapshot.playbackPositionMs,
            sampledAt = nowUs
        )

        try {
            sender(packet)
            _totalReportsSent.incrementAndGet()
            lastReportedPositionMs = snapshot.playbackPositionMs
            lastReportedAtUs = nowUs
            lastReportedTrackUri = snapshot.trackUri
            lastReportedIsPaused = snapshot.isPaused
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to send SpotifyStateReport from peer $deviceId: ${t.message}", t)
        }
    }

    override fun close() {
        stop()
    }
}

/**
 * Host-side telemetry aggregator for multi-device Spotify synchronization (Sub-phase v0.7.2).
 *
 * Maintains a real-time reactive state map of all connected peer devices, their computed drifts,
 * and room-wide synchronization metrics.
 */
class SpotifyTelemetryAggregator {

    private val _telemetryMap = MutableStateFlow<Map<String, SpotifyPeerTelemetry>>(emptyMap())
    val telemetryMap: StateFlow<Map<String, SpotifyPeerTelemetry>> = _telemetryMap.asStateFlow()

    private val _activePeerCount = MutableStateFlow(0)
    val activePeerCount: StateFlow<Int> = _activePeerCount.asStateFlow()

    private val _syncedPeerCount = MutableStateFlow(0)
    val syncedPeerCount: StateFlow<Int> = _syncedPeerCount.asStateFlow()

    private val _maxDriftMs = MutableStateFlow(0L)
    val maxDriftMs: StateFlow<Long> = _maxDriftMs.asStateFlow()

    private val _isOverallInSync = MutableStateFlow(true)
    val isOverallInSync: StateFlow<Boolean> = _isOverallInSync.asStateFlow()

    /**
     * Updates or inserts a peer's telemetry snapshot in the aggregation matrix.
     */
    fun updatePeerTelemetry(telemetry: SpotifyPeerTelemetry) {
        _telemetryMap.update { current ->
            val updated = current.toMutableMap()
            val existing = updated[telemetry.deviceId]
            val totalReports = (existing?.totalReportsReceived ?: 0L) + 1L
            val totalCorrections = existing?.correctionsIssued ?: telemetry.correctionsIssued

            updated[telemetry.deviceId] = telemetry.copy(
                totalReportsReceived = totalReports,
                correctionsIssued = totalCorrections
            )
            updated
        }
        recomputeMetrics()
    }

    /**
     * Increments the count of micro-seek corrections issued to a specific peer device.
     */
    fun recordCorrectionIssued(deviceId: String) {
        _telemetryMap.update { current ->
            val existing = current[deviceId] ?: return@update current
            val updated = current.toMutableMap()
            updated[deviceId] = existing.copy(correctionsIssued = existing.correctionsIssued + 1L)
            updated
        }
    }

    /**
     * Removes a peer device from telemetry tracking (e.g. on disconnect or room exit).
     */
    fun removePeer(deviceId: String) {
        _telemetryMap.update { current ->
            val updated = current.toMutableMap()
            updated.remove(deviceId)
            updated
        }
        recomputeMetrics()
    }

    /**
     * Clears all peer telemetry records.
     */
    fun clear() {
        _telemetryMap.value = emptyMap()
        recomputeMetrics()
    }

    /**
     * Retrieves the latest telemetry record for a specific peer device, or null if untracked.
     */
    fun getTelemetry(deviceId: String): SpotifyPeerTelemetry? = _telemetryMap.value[deviceId]

    private fun recomputeMetrics() {
        val map = _telemetryMap.value
        _activePeerCount.value = map.size
        _syncedPeerCount.value = map.values.count { it.isSynced }
        _maxDriftMs.value = map.values.maxOfOrNull { it.absDriftMs } ?: 0L
        _isOverallInSync.value = map.isEmpty() || map.values.all { it.isSynced }
    }
}

/**
 * Registers an incoming [RoomBeatPacket.SpotifyStateReport] handler on [PacketDispatcher].
 */
fun PacketDispatcher.registerSpotifyStateReportHandler(
    dispatcher: CoroutineDispatcher? = null,
    handler: suspend (packet: RoomBeatPacket.SpotifyStateReport, context: PacketContext) -> Unit
): HandlerRegistration {
    return registerHandler(dispatcher, handler)
}
