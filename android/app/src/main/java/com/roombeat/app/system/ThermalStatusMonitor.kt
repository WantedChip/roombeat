package com.roombeat.app.system

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.audio.buffer.JitterBufferConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor

/**
 * Snapshot of device thermal status and associated jitter buffer recommendations.
 *
 * @param status Raw status code from [PowerManager.THERMAL_STATUS_*].
 * @param statusName Human-readable designation ("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN").
 * @param isThrottling True if device is actively experiencing thermal throttling (MODERATE or higher).
 * @param isSevereThrottling True if throttling is SEVERE, CRITICAL, or EMERGENCY.
 * @param recommendedBufferDepthMs Recommended jitter buffer target depth in milliseconds.
 * @param timestampNs System monotonic time when this thermal event was captured.
 */
data class ThermalStatusInfo(
    val status: Int = PowerManager.THERMAL_STATUS_NONE,
    val statusName: String = "NONE",
    val isThrottling: Boolean = false,
    val isSevereThrottling: Boolean = false,
    val recommendedBufferDepthMs: Int = JitterBufferConstants.DEFAULT_TARGET_DEPTH_MS,
    val timestampNs: Long = System.nanoTime()
)

/**
 * Abstraction for querying and observing OS thermal events.
 * Enables 100% deterministic JVM unit testing without physical device thermal manipulation.
 */
interface ThermalStatusProvider {
    val currentThermalStatus: Int
    fun registerThermalListener(executor: Executor, listener: (Int) -> Unit): Boolean
    fun unregisterThermalListener(listener: (Int) -> Unit): Boolean
}

/**
 * Real Android implementation delegating to [PowerManager].
 */
class AndroidThermalStatusProvider(private val powerManager: PowerManager) : ThermalStatusProvider {
    private val listenerMap = mutableMapOf<(Int) -> Unit, PowerManager.OnThermalStatusChangedListener>()

    override val currentThermalStatus: Int
        get() = try {
            powerManager.currentThermalStatus
        } catch (e: Exception) {
            PowerManager.THERMAL_STATUS_NONE
        }

    override fun registerThermalListener(executor: Executor, listener: (Int) -> Unit): Boolean {
        return try {
            val osListener = PowerManager.OnThermalStatusChangedListener { status ->
                listener(status)
            }
            listenerMap[listener] = osListener
            powerManager.addThermalStatusListener(executor, osListener)
            true
        } catch (e: Exception) {
            Log.e("ThermalStatusMonitor", "Failed to register thermal listener: ${e.message}", e)
            false
        }
    }

    override fun unregisterThermalListener(listener: (Int) -> Unit): Boolean {
        val osListener = listenerMap.remove(listener) ?: return false
        return try {
            powerManager.removeThermalStatusListener(osListener)
            true
        } catch (e: Exception) {
            Log.w("ThermalStatusMonitor", "Failed to unregister thermal listener: ${e.message}")
            false
        }
    }
}

/**
 * Monitors OS thermal throttling states via [PowerManager.OnThermalStatusChangedListener]
 * and dynamically adjusts audio jitter buffer target depths.
 *
 * When a mobile device heats up during sustained 8-device Wi-Fi streaming, the Linux kernel
 * DVFS (Dynamic Voltage and Frequency Scaling) governor throttles CPU frequencies and takes
 * CPU cores offline. This introduces thread scheduling delays and audio buffer underruns
 * if the jitter buffer depth remains rigid at 120ms.
 *
 * [ThermalStatusMonitor] dynamically expands jitter buffer headroom:
 * - NONE / LIGHT: Baseline (120ms / 6 frames)
 * - MODERATE: 160ms (+40ms / +2 frames)
 * - SEVERE: 200ms (+80ms / +4 frames)
 * - CRITICAL / EMERGENCY: 240ms (+120ms / +6 frames)
 *
 * It provides smooth buffer expansion without audio dropouts.
 */
class ThermalStatusMonitor(
    context: Context? = null,
    provider: ThermalStatusProvider? = null,
    val baseDepthMs: Int = JitterBufferConstants.DEFAULT_TARGET_DEPTH_MS
) : AutoCloseable {

    companion object {
        private const val TAG = "ThermalStatusMonitor"

        // Thermal depth expansion deltas in milliseconds
        const val MODERATE_EXPANSION_MS = 40
        const val SEVERE_EXPANSION_MS = 80
        const val CRITICAL_EXPANSION_MS = 120
        const val MAX_SAFE_DEPTH_MS = 240

        fun statusToString(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
    }

    private val thermalProvider: ThermalStatusProvider? = provider ?: run {
        val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.let { AndroidThermalStatusProvider(it) }
    }

    private val _thermalState = MutableStateFlow(
        createThermalStatusInfo(thermalProvider?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE)
    )
    val thermalState: StateFlow<ThermalStatusInfo> = _thermalState.asStateFlow()

    val currentThermalStatus: Int
        get() = _thermalState.value.status

    val isThrottling: Boolean
        get() = _thermalState.value.isThrottling

    val recommendedBufferDepthMs: Int
        get() = _thermalState.value.recommendedBufferDepthMs

    var attachedJitterBuffer: AudioJitterBuffer? = null
    var onThermalStatusChanged: ((ThermalStatusInfo) -> Unit)? = null

    private var isMonitoring = false
    private var registeredListener: ((Int) -> Unit)? = null

    /**
     * Calculates recommended jitter buffer depth given a thermal status code.
     */
    fun calculateRecommendedDepth(status: Int): Int {
        val base = kotlin.math.max(20, baseDepthMs)
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> base
            PowerManager.THERMAL_STATUS_MODERATE -> kotlin.math.min(MAX_SAFE_DEPTH_MS, base + MODERATE_EXPANSION_MS)
            PowerManager.THERMAL_STATUS_SEVERE -> kotlin.math.min(MAX_SAFE_DEPTH_MS, base + SEVERE_EXPANSION_MS)
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> kotlin.math.min(MAX_SAFE_DEPTH_MS, base + CRITICAL_EXPANSION_MS)
            else -> base
        }
    }

    fun createThermalStatusInfo(status: Int): ThermalStatusInfo {
        val isThrottling = status >= PowerManager.THERMAL_STATUS_MODERATE
        val isSevere = status >= PowerManager.THERMAL_STATUS_SEVERE
        val depthMs = calculateRecommendedDepth(status)
        return ThermalStatusInfo(
            status = status,
            statusName = statusToString(status),
            isThrottling = isThrottling,
            isSevereThrottling = isSevere,
            recommendedBufferDepthMs = depthMs,
            timestampNs = System.nanoTime()
        )
    }

    /**
     * Starts listening for OS thermal status changes.
     */
    fun startMonitoring(executor: Executor = Executor { it.run() }): Boolean {
        if (isMonitoring) return true
        val provider = thermalProvider ?: run {
            Log.w(TAG, "ThermalStatusProvider is null; thermal monitoring unavailable")
            return false
        }

        val listener: (Int) -> Unit = { status ->
            handleThermalStatusChanged(status)
        }
        registeredListener = listener

        val success = provider.registerThermalListener(executor, listener)
        if (success) {
            isMonitoring = true
            // Sync initial state
            handleThermalStatusChanged(provider.currentThermalStatus)
            Log.i(TAG, "Started OS thermal monitoring (initial status: ${_thermalState.value.statusName})")
        } else {
            registeredListener = null
        }
        return success
    }

    /**
     * Stops listening for OS thermal status changes.
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        val listener = registeredListener
        if (listener != null) {
            thermalProvider?.unregisterThermalListener(listener)
            registeredListener = null
        }
        isMonitoring = false
        Log.i(TAG, "Stopped OS thermal monitoring")
    }

    /**
     * Core handler for thermal status transitions.
     * Updates internal state flow, notifies callback, and dynamically expands attached jitter buffer.
     */
    fun handleThermalStatusChanged(status: Int) {
        val info = createThermalStatusInfo(status)
        _thermalState.update { info }

        // Dynamically update attached jitter buffer if present
        attachedJitterBuffer?.let { buffer ->
            val oldDepth = buffer.targetDepthMs
            val newDepth = info.recommendedBufferDepthMs
            if (oldDepth != newDepth) {
                buffer.targetDepthMs = newDepth
                Log.i(TAG, "Thermal state transition to ${info.statusName}: adjusted jitter buffer depth ${oldDepth}ms -> ${newDepth}ms")
            }
        }

        onThermalStatusChanged?.invoke(info)
    }

    override fun close() {
        stopMonitoring()
        attachedJitterBuffer = null
        onThermalStatusChanged = null
    }
}
