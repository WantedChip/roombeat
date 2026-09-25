package com.roombeat.app.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time audio levels and ballistic telemetry snapshot for an audio channel.
 *
 * @param channelId Unique identifier for this channel (e.g. "local_host" or peer deviceId).
 * @param name User-visible device/channel name (e.g. "Host (Pixel 8)", "Galaxy S24").
 * @param leftRmsDbfs Current ballistically decayed Left RMS level in dBFS.
 * @param rightRmsDbfs Current ballistically decayed Right RMS level in dBFS.
 * @param leftPeakDbfs Current Left peak amplitude in dBFS.
 * @param rightPeakDbfs Current Right peak amplitude in dBFS.
 * @param leftPeakHoldDbfs Current held Left peak amplitude in dBFS (300ms hold + 250ms decay).
 * @param rightPeakHoldDbfs Current held Right peak amplitude in dBFS (300ms hold + 250ms decay).
 * @param leftLinearRms Linear Left RMS amplitude (0.0 to 1.0+).
 * @param rightLinearRms Linear Right RMS amplitude (0.0 to 1.0+).
 * @param isClipping True if signal peak exceeds 0.0 dBFS (clip threshold).
 * @param lastUpdatedMs Monotonic or wall timestamp in milliseconds when this channel was updated.
 */
data class ChannelLevels(
    val channelId: String,
    val name: String = "",
    val leftRmsDbfs: Float = AudioRmsAnalyzer.MIN_DBFS,
    val rightRmsDbfs: Float = AudioRmsAnalyzer.MIN_DBFS,
    val leftPeakDbfs: Float = AudioRmsAnalyzer.MIN_DBFS,
    val rightPeakDbfs: Float = AudioRmsAnalyzer.MIN_DBFS,
    val leftPeakHoldDbfs: Float = AudioRmsAnalyzer.MIN_DBFS,
    val rightPeakHoldDbfs: Float = AudioRmsAnalyzer.MIN_DBFS,
    val leftLinearRms: Float = 0f,
    val rightLinearRms: Float = 0f,
    val isClipping: Boolean = false,
    val lastUpdatedMs: Long = 0L
) {
    val maxRmsDbfs: Float get() = maxOf(leftRmsDbfs, rightRmsDbfs)
    val maxPeakDbfs: Float get() = maxOf(leftPeakDbfs, rightPeakDbfs)
    val maxPeakHoldDbfs: Float get() = maxOf(leftPeakHoldDbfs, rightPeakHoldDbfs)

    /**
     * Normalized [0.0, 1.0] RMS level mapped between [AudioRmsAnalyzer.MIN_DBFS] and [AudioRmsAnalyzer.MAX_DBFS].
     */
    val normalizedRms: Float get() = AudioRmsAnalyzer.dbfsToNormalized(maxRmsDbfs)

    /**
     * Normalized [0.0, 1.0] Peak Hold level mapped between [AudioRmsAnalyzer.MIN_DBFS] and [AudioRmsAnalyzer.MAX_DBFS].
     */
    val normalizedPeakHold: Float get() = AudioRmsAnalyzer.dbfsToNormalized(maxPeakHoldDbfs)

    /**
     * Formatted monospaced dB string for telemetry readout (e.g. "-3.2 dB" or "-INF").
     */
    val formattedDbfs: String get() = AudioRmsAnalyzer.formatDbfs(maxRmsDbfs)
}

/**
 * Raw linear amplitude levels calculated from an audio sample buffer.
 */
data class RawAudioLevels(
    val leftRms: Float,
    val rightRms: Float,
    val leftPeak: Float,
    val rightPeak: Float
) {
    val maxRms: Float get() = maxOf(leftRms, rightRms)
    val maxPeak: Float get() = maxOf(leftPeak, rightPeak)
}

/**
 * High-performance, multi-channel real-time RMS, peak, and dBFS calculator
 * with IEC 60268-10 ballistic physics.
 *
 * Ballistic Specification (IEC 60268-10 / PPM):
 * - Instantaneous Attack / Rise: <= 16ms (transients snap instantly to new peak)
 * - Peak Hold: 300ms hold window before decay commences
 * - Ballistic Decay: 250ms exponential falloff time constant (tau = 250ms)
 *
 * Supports multi-channel telemetry streams for local host and connected peers.
 */
class AudioRmsAnalyzer {

    companion object {
        const val CHANNEL_LOCAL = "local_host"
        const val CHANNEL_MASTER = "master"

        // Ballistic Timing Constants
        const val ATTACK_MAX_MS = 16L
        const val PEAK_HOLD_DURATION_MS = 300L
        const val DECAY_TAU_MS = 250.0

        // dBFS Calibration Scales
        const val MIN_DBFS = -60.0f
        const val MAX_DBFS = 3.0f
        const val GREEN_AMBER_THRESHOLD_DBFS = -6.0f
        const val CLIP_THRESHOLD_DBFS = 0.0f

        /**
         * Converts linear amplitude (0.0 to 1.0+) to decibels full scale (dBFS).
         * Clamps to [MIN_DBFS] (-60.0 dBFS floor).
         */
        fun linearToDbfs(linear: Float): Float {
            if (linear <= 1e-5f) return MIN_DBFS
            val db = (20.0 * log10(linear.toDouble())).toFloat()
            return maxOf(MIN_DBFS, db)
        }

        /**
         * Converts decibels full scale (dBFS) back to linear amplitude.
         */
        fun dbfsToLinear(dbfs: Float): Float {
            if (dbfs <= MIN_DBFS) return 0f
            return 10.0.pow(dbfs.toDouble() / 20.0).toFloat()
        }

        /**
         * Maps a dBFS level into a normalized [0.0, 1.0] range between [minDbfs] and [maxDbfs].
         */
        fun dbfsToNormalized(dbfs: Float, minDbfs: Float = MIN_DBFS, maxDbfs: Float = MAX_DBFS): Float {
            if (dbfs <= minDbfs) return 0f
            if (dbfs >= maxDbfs) return 1f
            return (dbfs - minDbfs) / (maxDbfs - minDbfs)
        }

        /**
         * Formats a dBFS reading into a human-readable string (e.g. "-3.2 dB" or "-INF").
         */
        fun formatDbfs(dbfs: Float): String {
            if (dbfs <= -59.5f) return "-INF"
            return String.format(Locale.US, "%.1f dB", dbfs)
        }

        /**
         * Calculates raw linear RMS and peak levels from stereo or mono 16-bit PCM samples.
         */
        fun calculatePcm16Levels(
            pcm16: ShortArray,
            channels: Int = 2,
            offset: Int = 0,
            length: Int = pcm16.size
        ): RawAudioLevels {
            if (length <= 0 || offset < 0 || offset >= pcm16.size) {
                return RawAudioLevels(0f, 0f, 0f, 0f)
            }

            val validLength = minOf(length, pcm16.size - offset)
            if (channels == 2) {
                val numFrames = validLength / 2
                if (numFrames <= 0) return RawAudioLevels(0f, 0f, 0f, 0f)

                var sumSqL = 0.0
                var sumSqR = 0.0
                var peakL = 0f
                var peakR = 0f

                for (i in 0 until numFrames) {
                    val sL = (pcm16[offset + i * 2].toFloat() / 32768.0f)
                    val sR = (pcm16[offset + i * 2 + 1].toFloat() / 32768.0f)
                    val absL = kotlin.math.abs(sL)
                    val absR = kotlin.math.abs(sR)
                    if (absL > peakL) peakL = absL
                    if (absR > peakR) peakR = absR
                    sumSqL += sL * sL
                    sumSqR += sR * sR
                }

                val rmsL = sqrt(sumSqL / numFrames).toFloat()
                val rmsR = sqrt(sumSqR / numFrames).toFloat()
                return RawAudioLevels(rmsL, rmsR, peakL, peakR)
            } else {
                // Mono
                var sumSq = 0.0
                var peak = 0f
                for (i in 0 until validLength) {
                    val s = (pcm16[offset + i].toFloat() / 32768.0f)
                    val absS = kotlin.math.abs(s)
                    if (absS > peak) peak = absS
                    sumSq += s * s
                }
                val rms = sqrt(sumSq / validLength).toFloat()
                return RawAudioLevels(rms, rms, peak, peak)
            }
        }

        /**
         * Calculates raw linear RMS and peak levels from stereo or mono Float32 samples.
         */
        fun calculateFloatLevels(
            samples: FloatArray,
            channels: Int = 2,
            offset: Int = 0,
            length: Int = samples.size
        ): RawAudioLevels {
            if (length <= 0 || offset < 0 || offset >= samples.size) {
                return RawAudioLevels(0f, 0f, 0f, 0f)
            }

            val validLength = minOf(length, samples.size - offset)
            if (channels == 2) {
                val numFrames = validLength / 2
                if (numFrames <= 0) return RawAudioLevels(0f, 0f, 0f, 0f)

                var sumSqL = 0.0
                var sumSqR = 0.0
                var peakL = 0f
                var peakR = 0f

                for (i in 0 until numFrames) {
                    val sL = samples[offset + i * 2]
                    val sR = samples[offset + i * 2 + 1]
                    val absL = kotlin.math.abs(sL)
                    val absR = kotlin.math.abs(sR)
                    if (absL > peakL) peakL = absL
                    if (absR > peakR) peakR = absR
                    sumSqL += sL * sL
                    sumSqR += sR * sR
                }

                val rmsL = sqrt(sumSqL / numFrames).toFloat()
                val rmsR = sqrt(sumSqR / numFrames).toFloat()
                return RawAudioLevels(rmsL, rmsR, peakL, peakR)
            } else {
                var sumSq = 0.0
                var peak = 0f
                for (i in 0 until validLength) {
                    val s = samples[offset + i]
                    val absS = kotlin.math.abs(s)
                    if (absS > peak) peak = absS
                    sumSq += s * s
                }
                val rms = sqrt(sumSq / validLength).toFloat()
                return RawAudioLevels(rms, rms, peak, peak)
            }
        }
    }

    private class BallisticState(
        var name: String = "",
        var leftRmsDbfs: Float = MIN_DBFS,
        var rightRmsDbfs: Float = MIN_DBFS,
        var leftPeakDbfs: Float = MIN_DBFS,
        var rightPeakDbfs: Float = MIN_DBFS,
        var leftPeakHoldDbfs: Float = MIN_DBFS,
        var rightPeakHoldDbfs: Float = MIN_DBFS,
        var leftPeakHoldExpiresAtMs: Long = 0L,
        var rightPeakHoldExpiresAtMs: Long = 0L,
        var leftLinearRms: Float = 0f,
        var rightLinearRms: Float = 0f,
        var lastUpdatedMs: Long = 0L
    )

    private val lock = Any()
    private val channelStates = mutableMapOf<String, BallisticState>()
    private val _channelsFlow = MutableStateFlow<Map<String, ChannelLevels>>(emptyMap())

    /**
     * Observable StateFlow of all active channel levels.
     */
    val channelsFlow: StateFlow<Map<String, ChannelLevels>> = _channelsFlow.asStateFlow()

    // Reusable scratch array for reading native audio levels without allocation
    private val nativeScratchArray = FloatArray(4)

    /**
     * Ingests linear audio level measurements for a channel and applies IEC 60268-10 ballistics.
     */
    fun updateChannel(
        channelId: String,
        name: String = "",
        leftRmsLinear: Float,
        rightRmsLinear: Float,
        leftPeakLinear: Float,
        rightPeakLinear: Float,
        timestampMs: Long = System.currentTimeMillis()
    ): ChannelLevels {
        val targetLeftRmsDbfs = linearToDbfs(leftRmsLinear)
        val targetRightRmsDbfs = linearToDbfs(rightRmsLinear)
        val targetLeftPeakDbfs = linearToDbfs(leftPeakLinear)
        val targetRightPeakDbfs = linearToDbfs(rightPeakLinear)

        return updateChannelFromDbfs(
            channelId = channelId,
            name = name,
            leftRmsDbfs = targetLeftRmsDbfs,
            rightRmsDbfs = targetRightRmsDbfs,
            leftPeakDbfs = targetLeftPeakDbfs,
            rightPeakDbfs = targetRightPeakDbfs,
            leftLinearRms = leftRmsLinear,
            rightLinearRms = rightRmsLinear,
            timestampMs = timestampMs
        )
    }

    /**
     * Ingests dBFS audio levels directly and computes instantaneous rise, 300ms peak hold,
     * and 250ms exponential decay.
     */
    fun updateChannelFromDbfs(
        channelId: String,
        name: String = "",
        leftRmsDbfs: Float,
        rightRmsDbfs: Float,
        leftPeakDbfs: Float,
        rightPeakDbfs: Float,
        leftLinearRms: Float = dbfsToLinear(leftRmsDbfs),
        rightLinearRms: Float = dbfsToLinear(rightRmsDbfs),
        timestampMs: Long = System.currentTimeMillis()
    ): ChannelLevels = synchronized(lock) {
        val state = channelStates.getOrPut(channelId) {
            BallisticState(
                name = name.ifEmpty { channelId },
                lastUpdatedMs = timestampMs
            )
        }
        if (name.isNotEmpty()) {
            state.name = name
        }

        val deltaMs = if (state.lastUpdatedMs > 0L) {
            maxOf(0L, timestampMs - state.lastUpdatedMs)
        } else {
            0L
        }

        // Apply IEC 60268-10 ballistics to Left & Right RMS
        state.leftRmsDbfs = computeBallisticDecay(leftRmsDbfs, state.leftRmsDbfs, deltaMs)
        state.rightRmsDbfs = computeBallisticDecay(rightRmsDbfs, state.rightRmsDbfs, deltaMs)

        // Apply IEC 60268-10 ballistics to Left & Right Peak
        state.leftPeakDbfs = computeBallisticDecay(leftPeakDbfs, state.leftPeakDbfs, deltaMs)
        state.rightPeakDbfs = computeBallisticDecay(rightPeakDbfs, state.rightPeakDbfs, deltaMs)

        // Peak Hold logic: 300ms hold + 250ms decay
        val leftHoldResult = computePeakHold(
            targetPeakDbfs = leftPeakDbfs,
            currentHeldDbfs = state.leftPeakHoldDbfs,
            expiresAtMs = state.leftPeakHoldExpiresAtMs,
            nowMs = timestampMs,
            deltaMs = deltaMs
        )
        state.leftPeakHoldDbfs = leftHoldResult.first
        state.leftPeakHoldExpiresAtMs = leftHoldResult.second

        val rightHoldResult = computePeakHold(
            targetPeakDbfs = rightPeakDbfs,
            currentHeldDbfs = state.rightPeakHoldDbfs,
            expiresAtMs = state.rightPeakHoldExpiresAtMs,
            nowMs = timestampMs,
            deltaMs = deltaMs
        )
        state.rightPeakHoldDbfs = rightHoldResult.first
        state.rightPeakHoldExpiresAtMs = rightHoldResult.second

        state.leftLinearRms = leftLinearRms
        state.rightLinearRms = rightLinearRms
        state.lastUpdatedMs = timestampMs

        val isClipping = state.leftPeakDbfs >= CLIP_THRESHOLD_DBFS ||
                state.rightPeakDbfs >= CLIP_THRESHOLD_DBFS ||
                state.leftPeakHoldDbfs >= CLIP_THRESHOLD_DBFS ||
                state.rightPeakHoldDbfs >= CLIP_THRESHOLD_DBFS

        val levels = ChannelLevels(
            channelId = channelId,
            name = state.name,
            leftRmsDbfs = state.leftRmsDbfs,
            rightRmsDbfs = state.rightRmsDbfs,
            leftPeakDbfs = state.leftPeakDbfs,
            rightPeakDbfs = state.rightPeakDbfs,
            leftPeakHoldDbfs = state.leftPeakHoldDbfs,
            rightPeakHoldDbfs = state.rightPeakHoldDbfs,
            leftLinearRms = state.leftLinearRms,
            rightLinearRms = state.rightLinearRms,
            isClipping = isClipping,
            lastUpdatedMs = timestampMs
        )

        val updatedMap = channelStates.entries.associate { (id, s) ->
            id to ChannelLevels(
                channelId = id,
                name = s.name,
                leftRmsDbfs = s.leftRmsDbfs,
                rightRmsDbfs = s.rightRmsDbfs,
                leftPeakDbfs = s.leftPeakDbfs,
                rightPeakDbfs = s.rightPeakDbfs,
                leftPeakHoldDbfs = s.leftPeakHoldDbfs,
                rightPeakHoldDbfs = s.rightPeakHoldDbfs,
                leftLinearRms = s.leftLinearRms,
                rightLinearRms = s.rightLinearRms,
                isClipping = s.leftPeakDbfs >= CLIP_THRESHOLD_DBFS || s.rightPeakDbfs >= CLIP_THRESHOLD_DBFS,
                lastUpdatedMs = s.lastUpdatedMs
            )
        }
        _channelsFlow.value = updatedMap

        levels
    }

    /**
     * Polls the native C++ audio engine for the latest 20ms RMS and peak levels
     * and updates the local host channel.
     */
    fun updateLocalFromEngine(
        engine: NativeAudioEngine? = null,
        channelId: String = CHANNEL_LOCAL,
        name: String = "Host",
        timestampMs: Long = System.currentTimeMillis()
    ): ChannelLevels? {
        val success = synchronized(nativeScratchArray) {
            if (engine != null) {
                engine.getAudioLevels(nativeScratchArray)
            } else {
                NativeAudioEngine.getAudioLevels(nativeScratchArray)
            }
        }
        if (!success) return null

        val leftRms = nativeScratchArray[0]
        val rightRms = nativeScratchArray[1]
        val leftPeak = nativeScratchArray[2]
        val rightPeak = nativeScratchArray[3]

        return updateChannel(
            channelId = channelId,
            name = name,
            leftRmsLinear = leftRms,
            rightRmsLinear = rightRms,
            leftPeakLinear = leftPeak,
            rightPeakLinear = rightPeak,
            timestampMs = timestampMs
        )
    }

    /**
     * Analyzes a 16-bit PCM audio frame and updates the specified channel.
     */
    fun processPcmFrame(
        channelId: String,
        name: String = "",
        pcm16: ShortArray,
        channels: Int = 2,
        timestampMs: Long = System.currentTimeMillis()
    ): ChannelLevels {
        val raw = calculatePcm16Levels(pcm16, channels)
        return updateChannel(
            channelId = channelId,
            name = name,
            leftRmsLinear = raw.leftRms,
            rightRmsLinear = raw.rightRms,
            leftPeakLinear = raw.leftPeak,
            rightPeakLinear = raw.rightPeak,
            timestampMs = timestampMs
        )
    }

    /**
     * Analyzes a Float32 audio frame and updates the specified channel.
     */
    fun processFloatFrame(
        channelId: String,
        name: String = "",
        floatSamples: FloatArray,
        channels: Int = 2,
        timestampMs: Long = System.currentTimeMillis()
    ): ChannelLevels {
        val raw = calculateFloatLevels(floatSamples, channels)
        return updateChannel(
            channelId = channelId,
            name = name,
            leftRmsLinear = raw.leftRms,
            rightRmsLinear = raw.rightRms,
            leftPeakLinear = raw.leftPeak,
            rightPeakLinear = raw.rightPeak,
            timestampMs = timestampMs
        )
    }

    /**
     * Returns the current levels for a specific channel, or null if not registered.
     */
    fun getChannel(channelId: String): ChannelLevels? = synchronized(lock) {
        _channelsFlow.value[channelId]
    }

    /**
     * Returns a snapshot list of all tracked channels.
     */
    fun getAllChannels(): List<ChannelLevels> = synchronized(lock) {
        _channelsFlow.value.values.toList()
    }

    /**
     * Removes a channel from active tracking.
     */
    fun removeChannel(channelId: String): Boolean = synchronized(lock) {
        val removed = channelStates.remove(channelId) != null
        if (removed) {
            val updatedMap = channelStates.entries.associate { (id, s) ->
                id to ChannelLevels(
                    channelId = id,
                    name = s.name,
                    leftRmsDbfs = s.leftRmsDbfs,
                    rightRmsDbfs = s.rightRmsDbfs,
                    leftPeakDbfs = s.leftPeakDbfs,
                    rightPeakDbfs = s.rightPeakDbfs,
                    leftPeakHoldDbfs = s.leftPeakHoldDbfs,
                    rightPeakHoldDbfs = s.rightPeakHoldDbfs,
                    leftLinearRms = s.leftLinearRms,
                    rightLinearRms = s.rightLinearRms,
                    isClipping = s.leftPeakDbfs >= CLIP_THRESHOLD_DBFS || s.rightPeakDbfs >= CLIP_THRESHOLD_DBFS,
                    lastUpdatedMs = s.lastUpdatedMs
                )
            }
            _channelsFlow.value = updatedMap
        }
        removed
    }

    /**
     * Clears all channels and resets tracking state.
     */
    fun clearAll() = synchronized(lock) {
        channelStates.clear()
        _channelsFlow.value = emptyMap()
    }

    /**
     * IEC 60268-10 Ballistic Decay physics:
     * - Instantaneous attack if target >= current (<= 16ms rise)
     * - Exponential decay with tau = 250ms if target < current
     */
    private fun computeBallisticDecay(
        targetDbfs: Float,
        currentDbfs: Float,
        deltaMs: Long
    ): Float {
        if (targetDbfs >= currentDbfs) {
            // Instantaneous attack (snaps immediately to rising transient)
            return targetDbfs
        }
        if (deltaMs <= 0L) {
            return currentDbfs
        }
        val decayFactor = exp(-deltaMs.toDouble() / DECAY_TAU_MS).toFloat()
        val decayed = targetDbfs + (currentDbfs - targetDbfs) * decayFactor
        return maxOf(MIN_DBFS, decayed)
    }

    /**
     * IEC 60268-10 Peak Hold:
     * - When incoming peak >= current held, snap to new peak and hold for 300ms.
     * - While now <= expiresAt, hold current peak.
     * - Once hold expires, decay exponentially with tau = 250ms.
     */
    private fun computePeakHold(
        targetPeakDbfs: Float,
        currentHeldDbfs: Float,
        expiresAtMs: Long,
        nowMs: Long,
        deltaMs: Long
    ): Pair<Float, Long> {
        if (targetPeakDbfs >= currentHeldDbfs) {
            // Transient hits or exceeds current held peak: snap and hold for 300ms
            return Pair(targetPeakDbfs, nowMs + PEAK_HOLD_DURATION_MS)
        }

        if (nowMs <= expiresAtMs) {
            // Within 300ms hold period: hold steady
            return Pair(currentHeldDbfs, expiresAtMs)
        }

        // Hold window expired: decay exponentially with tau = 250ms towards incoming peak
        val decayElapsedMs = minOf(deltaMs, nowMs - expiresAtMs)
        if (decayElapsedMs <= 0L) {
            return Pair(currentHeldDbfs, expiresAtMs)
        }

        val decayFactor = exp(-decayElapsedMs.toDouble() / DECAY_TAU_MS).toFloat()
        val decayed = targetPeakDbfs + (currentHeldDbfs - targetPeakDbfs) * decayFactor
        val result = maxOf(targetPeakDbfs, maxOf(MIN_DBFS, decayed))
        return Pair(result, expiresAtMs)
    }
}
