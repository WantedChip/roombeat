package com.roombeat.app.sync

import android.util.Log
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.protocol.HandlerRegistration
import com.roombeat.app.protocol.PacketDispatcher
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.session.PeerSessionTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Result of evaluating client presentation drift and computing micro-speed adjustment.
 */
data class DriftCorrectionResult(
    val deviceId: String,
    val driftMs: Double,
    val speedPpmAdjust: Int,
    val correctionIssued: Boolean,
    val isInSync: Boolean,
    val packet: RoomBeatPacket.SessionDriftCorrect? = null
)

/**
 * Telemetry snapshot for an individual peer tracked by the drift controller.
 */
data class PeerDriftTelemetry(
    val deviceId: String,
    val currentDriftMs: Double = 0.0,
    val appliedSpeedPpm: Int = 0,
    val cumulativeDriftMs: Double = 0.0,
    val sampleCount: Long = 0L,
    val isInSync: Boolean = true,
    val lastReportTimeMs: Long = 0L,
    val lastCorrectionTimeMs: Long = 0L
)

/**
 * High-precision host-side and peer-side clock drift controller (Sub-phase v0.8.0 / Roadmap §8, §10, §11).
 *
 * Coordinates continuous clock drift micro-adjustments (+/-500 ppm = +/-0.05%, up to +/-1000 ppm = +/-0.1%)
 * to keep peer presentation timestamps phase-aligned within <1.0ms during multi-device playback.
 *
 * Core Responsibilities:
 * 1. Host Mode:
 *    - Ingests periodic telemetry reports (NTP clock offsets, presentation timestamps, playback reports).
 *    - Evaluates drift relative to the master monotonic clock: Delta = clientPresentationTime - hostExpectedPresentationTime.
 *    - Implements Proportional-Integral (PI) closed-loop control to compute speed_ppm_adjust.
 *    - Rate-limits and broadcasts SESSION_DRIFT_CORRECT { device_id, speed_ppm_adjust } packets.
 * 2. Peer Client Mode:
 *    - Receives SESSION_DRIFT_CORRECT packets targeted for local device.
 *    - Applies speed PPM adjustment to local NativeAudioEngine / FractionalResampler / AudioJitterBuffer.
 *    - Smoothly accelerates or decelerates local audio rendering without audible pitch shifts, clicks, or pops.
 * 3. Headless Fallback:
 *    - Fully functional on host-side JVM unit tests without Android OS or native libraries.
 */
class DriftCorrectionController(
    val isHost: Boolean,
    val localDeviceId: String = "",
    val clock: MonotonicClock = SystemMonotonicClock,
    val audioEngine: NativeAudioEngine? = null,
    val jitterBuffer: AudioJitterBuffer? = null,
    val transport: PeerSessionTransport? = null,
    val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val targetAlignmentThresholdMs: Double = DEFAULT_TARGET_ALIGNMENT_MS,
    val deadbandMs: Double = DEFAULT_DEADBAND_MS,
    val maxSpeedPpm: Int = DEFAULT_MAX_SPEED_PPM,
    val minCorrectionIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    val kp: Double = DEFAULT_KP,
    val ki: Double = DEFAULT_KI
) : AutoCloseable {

    companion object {
        private const val TAG = "DriftCorrectionCtrl"

        /**
         * Maximum allowed clock drift for acoustic synchronization (1.0ms).
         */
        const val DEFAULT_TARGET_ALIGNMENT_MS = 1.0

        /**
         * Tight deadband around 0.0ms where no speed adjustment is needed (50µs).
         */
        const val DEFAULT_DEADBAND_MS = 0.05

        /**
         * Standard maximum micro-speed modulation (+/-500 ppm = +/-0.05%).
         */
        const val DEFAULT_MAX_SPEED_PPM = 500

        /**
         * Absolute safety limit on speed modulation (+/-1000 ppm = +/-0.1%).
         */
        const val HARD_LIMIT_MAX_SPEED_PPM = 1000

        /**
         * Minimum rate-limiting interval between correction packets for the same peer (500ms).
         */
        const val DEFAULT_MIN_INTERVAL_MS = 500L

        /**
         * Proportional controller gain: 500 ppm per 1.0ms error.
         */
        const val DEFAULT_KP = 500.0

        /**
         * Integral controller gain: 50 ppm per (ms * second).
         */
        const val DEFAULT_KI = 50.0

        /**
         * Anti-windup limit on accumulated integral error (ms * seconds).
         */
        const val MAX_INTEGRAL_ERROR = 2.0
    }

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)

    // Internal state tracking per peer on Host
    private data class PeerControlState(
        var integralErrorMs: Double = 0.0,
        var lastDriftMs: Double = 0.0,
        var lastReportTimeMs: Long = 0L,
        var lastCorrectionTimeMs: Long = 0L,
        var lastSpeedPpm: Int = 0,
        var cumulativeDriftMs: Double = 0.0,
        var sampleCount: Long = 0L
    )

    private val peerStates = ConcurrentHashMap<String, PeerControlState>()

    // Reactive telemetry StateFlows
    private val _peerDriftState = MutableStateFlow<Map<String, PeerDriftTelemetry>>(emptyMap())
    val peerDriftState: StateFlow<Map<String, PeerDriftTelemetry>> = _peerDriftState.asStateFlow()

    private val _localSpeedPpm = MutableStateFlow(0)
    val localSpeedPpm: StateFlow<Int> = _localSpeedPpm.asStateFlow()

    private val _isOverallInSync = MutableStateFlow(true)
    val isOverallInSync: StateFlow<Boolean> = _isOverallInSync.asStateFlow()

    private val _maxDriftMs = MutableStateFlow(0.0)
    val maxDriftMs: StateFlow<Double> = _maxDriftMs.asStateFlow()

    /**
     * Callback invoked when a drift correction packet is emitted (useful for test assertions and transport dispatch).
     */
    var onDriftCorrectPacketEmitted: ((RoomBeatPacket.SessionDriftCorrect) -> Unit)? = null

    /**
     * Callback invoked when a speed PPM adjustment is applied locally on peer.
     */
    var onSpeedPpmApplied: ((Int) -> Unit)? = null

    // ========================================================================
    // Host-Side Drift Evaluation & Control Loop
    // ========================================================================

    /**
     * Evaluates measured client drift in milliseconds, runs the closed-loop PI controller,
     * updates telemetry, and dispatches a SESSION_DRIFT_CORRECT packet if adjustment is needed.
     *
     * @param deviceId Identifier of the peer device.
     * @param driftMs Signed presentation drift: positive means client is ahead of host; negative means behind.
     * @param timestampMs Monotonic timestamp in milliseconds when drift was sampled.
     */
    fun evaluateDrift(
        deviceId: String,
        driftMs: Double,
        timestampMs: Long = clock.nowMicros() / 1000L
    ): DriftCorrectionResult {
        val state = peerStates.computeIfAbsent(deviceId) { PeerControlState() }

        val timeDeltaSec = if (state.lastReportTimeMs > 0L) {
            maxOf(0.05, (timestampMs - state.lastReportTimeMs) / 1000.0)
        } else {
            1.0
        }

        state.lastReportTimeMs = timestampMs
        state.lastDriftMs = driftMs
        state.sampleCount++
        state.cumulativeDriftMs += abs(driftMs)

        val absDrift = abs(driftMs)
        val inSync = absDrift < targetAlignmentThresholdMs

        // Calculate control adjustment
        val targetSpeedPpm: Int
        if (absDrift <= deadbandMs) {
            // Inside deadband: zero out proportional term, decay integral term
            state.integralErrorMs *= 0.95
            targetSpeedPpm = (-ki * state.integralErrorMs).roundToInt().coerceIn(-maxSpeedPpm, maxSpeedPpm)
        } else {
            // Update integral with anti-windup clamping
            state.integralErrorMs = (state.integralErrorMs + driftMs * timeDeltaSec)
                .coerceIn(-MAX_INTEGRAL_ERROR, MAX_INTEGRAL_ERROR)

            // Proportional and Integral terms
            // Note: Positive drift means client is ahead (running too fast) -> needs negative PPM to slow down
            val pTerm = -kp * driftMs
            val iTerm = -ki * state.integralErrorMs
            val rawPpm = (pTerm + iTerm).roundToInt()
            targetSpeedPpm = rawPpm.coerceIn(-maxSpeedPpm, maxSpeedPpm)
        }

        // Rate limiting check: issue packet if minimum interval has elapsed or significant delta.
        // If speed is already 0 and remains 0 (inside deadband), do not emit redundant zero-adjustment packets.
        val timeSinceLast = timestampMs - state.lastCorrectionTimeMs
        val shouldIssue = if (targetSpeedPpm == 0 && state.lastSpeedPpm == 0) {
            false
        } else {
            (timeSinceLast >= minCorrectionIntervalMs) || (abs(targetSpeedPpm - state.lastSpeedPpm) >= 200)
        }

        var packet: RoomBeatPacket.SessionDriftCorrect? = null
        if (shouldIssue) {
            state.lastCorrectionTimeMs = timestampMs
            state.lastSpeedPpm = targetSpeedPpm

            packet = RoomBeatPacket.SessionDriftCorrect(
                deviceId = deviceId,
                speedPpmAdjust = targetSpeedPpm
            )

            // Emit packet through transport and callback
            scope.launch {
                try {
                    val sent = transport?.sendToPeer(deviceId, packet) ?: false
                    if (!sent) {
                        transport?.broadcastToAll(packet)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error broadcasting drift correction packet: ${e.message}")
                }
            }
            onDriftCorrectPacketEmitted?.invoke(packet)
        }

        // Update public telemetry state
        updateTelemetry(deviceId, driftMs, targetSpeedPpm, inSync, timestampMs, state)

        return DriftCorrectionResult(
            deviceId = deviceId,
            driftMs = driftMs,
            speedPpmAdjust = targetSpeedPpm,
            correctionIssued = shouldIssue,
            isInSync = inSync,
            packet = packet
        )
    }

    /**
     * Ingests clock offset and RTT telemetry from NTP probe or calibration result.
     * Translates clock offset drift into presentation drift.
     */
    fun onPeerTelemetry(
        deviceId: String,
        offsetMs: Double,
        rttMs: Double,
        timestampMs: Long = clock.nowMicros() / 1000L
    ): DriftCorrectionResult {
        // Offset theta = Peer - Host. Positive offset means Peer clock is ahead.
        return evaluateDrift(deviceId, offsetMs, timestampMs)
    }

    /**
     * Ingests a SpotifyStateReport packet to evaluate drift from track playback position.
     */
    fun onSpotifyStateReport(
        report: RoomBeatPacket.SpotifyStateReport,
        expectedMasterPositionMs: Long,
        timestampMs: Long = clock.nowMicros() / 1000L
    ): DriftCorrectionResult {
        val driftMs = (report.reportedPositionMs - expectedMasterPositionMs).toDouble()
        return evaluateDrift(report.deviceId, driftMs, timestampMs)
    }

    private fun updateTelemetry(
        deviceId: String,
        driftMs: Double,
        appliedPpm: Int,
        inSync: Boolean,
        timestampMs: Long,
        state: PeerControlState
    ) {
        _peerDriftState.update { current ->
            val updated = current.toMutableMap()
            updated[deviceId] = PeerDriftTelemetry(
                deviceId = deviceId,
                currentDriftMs = driftMs,
                appliedSpeedPpm = appliedPpm,
                cumulativeDriftMs = state.cumulativeDriftMs,
                sampleCount = state.sampleCount,
                isInSync = inSync,
                lastReportTimeMs = timestampMs,
                lastCorrectionTimeMs = state.lastCorrectionTimeMs
            )
            updated
        }

        val allPeers = _peerDriftState.value.values
        if (allPeers.isNotEmpty()) {
            _isOverallInSync.value = allPeers.all { it.isInSync }
            _maxDriftMs.value = allPeers.maxOfOrNull { abs(it.currentDriftMs) } ?: 0.0
        } else {
            _isOverallInSync.value = true
            _maxDriftMs.value = 0.0
        }
    }

    // ========================================================================
    // Peer Client-Side Packet Handling
    // ========================================================================

    /**
     * Handles an incoming SESSION_DRIFT_CORRECT packet on a peer node.
     * Applies the requested PPM adjustment to the local audio engine and resampler.
     *
     * @return true if the packet was targeted for this device and applied, false otherwise.
     */
    fun handleDriftCorrection(packet: RoomBeatPacket.SessionDriftCorrect): Boolean {
        // Verify target device ID matches this node (or broadcast wildcard)
        if (packet.deviceId.isNotEmpty() &&
            packet.deviceId != "all" &&
            localDeviceId.isNotEmpty() &&
            packet.deviceId != localDeviceId
        ) {
            return false
        }

        val clampedPpm = packet.speedPpmAdjust.coerceIn(-HARD_LIMIT_MAX_SPEED_PPM, HARD_LIMIT_MAX_SPEED_PPM)

        // 1. Apply to NativeAudioEngine if present
        audioEngine?.setSpeedPpm(clampedPpm)

        // 2. Apply to AudioJitterBuffer if present
        jitterBuffer?.speedPpm = clampedPpm

        // 3. Update reactive state
        _localSpeedPpm.value = clampedPpm

        // 4. Notify listener
        onSpeedPpmApplied?.invoke(clampedPpm)

        Log.d(TAG, "Applied drift correction: speedPpm=$clampedPpm (ratio=${1.0 + clampedPpm / 1000000.0})")
        return true
    }

    /**
     * Resets internal drift tracking and sets local audio speed to nominal 0 PPM.
     */
    fun reset() {
        peerStates.clear()
        _peerDriftState.value = emptyMap()
        _localSpeedPpm.value = 0
        _isOverallInSync.value = true
        _maxDriftMs.value = 0.0
        audioEngine?.setSpeedPpm(0)
        jitterBuffer?.speedPpm = 0
    }

    override fun close() {
        reset()
    }
}

/**
 * PacketDispatcher extension for registering SESSION_DRIFT_CORRECT packet handler.
 */
fun PacketDispatcher.registerDriftCorrectHandler(controller: DriftCorrectionController): HandlerRegistration {
    return registerHandler<RoomBeatPacket.SessionDriftCorrect> { packet, _ ->
        controller.handleDriftCorrection(packet)
    }
}
