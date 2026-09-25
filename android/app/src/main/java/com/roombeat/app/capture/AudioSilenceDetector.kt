package com.roombeat.app.capture

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Result data class for audio silence and energy analysis.
 *
 * @param isSilent Whether the stream is currently considered silent (considering hangover logic).
 * @param rmsDbfs Root-mean-square energy level in decibels relative to full scale (dBFS).
 * @param rmsLinear Normalized linear root-mean-square amplitude in [0.0, 1.0].
 * @param consecutiveSilentFrames Number of consecutive frames below the energy threshold.
 * @param stateChanged Whether this frame triggered a state change (ACTIVE -> SILENT or SILENT -> ACTIVE).
 */
data class SilenceDetectionResult(
    val isSilent: Boolean,
    val rmsDbfs: Double,
    val rmsLinear: Double,
    val consecutiveSilentFrames: Int,
    val stateChanged: Boolean
)

/**
 * High-performance RMS audio energy calculator and silence detector for 16-bit PCM audio streams.
 *
 * Designed to conserve Wi-Fi transmission bandwidth during playback lulls, quiet passages, or when
 * third-party media players (YouTube, Spotify, VLC) are paused or stopped on the host phone.
 *
 * Key Capabilities:
 * 1. Root-Mean-Square (RMS) amplitude and dBFS energy calculation over 16-bit PCM buffers.
 * 2. Calibrated silence threshold (in dBFS or linear amplitude).
 * 3. Fast Attack: Instantly transitions from SILENCE to ACTIVE upon detecting audio energy above threshold,
 *    guaranteeing zero latency or clipped transients when playback resumes.
 * 4. Hangover Duration: Holds the ACTIVE state for a configurable duration (e.g. 500ms = 25 frames @ 20ms)
 *    after audio energy drops below threshold, preventing packet stream flapping, stutter, or dropouts during
 *    micro-pauses, speech cadence, and staccato musical beats.
 * 5. Dynamic threshold calibration based on background noise floor.
 */
class AudioSilenceDetector(
    initialThresholdDbfs: Double = DEFAULT_THRESHOLD_DBFS,
    hangoverDurationMs: Long = DEFAULT_HANGOVER_MS,
    val frameDurationMs: Long = DEFAULT_FRAME_DURATION_MS
) {
    companion object {
        /** Default silence gating threshold (-50 dBFS). */
        const val DEFAULT_THRESHOLD_DBFS = -50.0

        /** Default hangover duration (500ms) before declaring silence. */
        const val DEFAULT_HANGOVER_MS = 500L

        /** Standard 20ms frame duration for RoomBeat Opus streaming. */
        const val DEFAULT_FRAME_DURATION_MS = 20L

        /** Numerical noise floor representing complete digital silence. */
        const val SILENCE_DBFS_FLOOR = -120.0

        /** Maximum magnitude of a signed 16-bit PCM sample. */
        const val MAX_PCM16_VALUE = 32768.0
    }

    /**
     * Active silence gating threshold in dBFS.
     * Frames with energy >= thresholdDbfs are treated as active audio.
     */
    @Volatile
    var thresholdDbfs: Double = initialThresholdDbfs
        set(value) {
            field = max(SILENCE_DBFS_FLOOR, min(0.0, value))
        }

    /**
     * Hangover duration in milliseconds before active audio transitions to silence.
     */
    @Volatile
    var hangoverDurationMs: Long = hangoverDurationMs
        set(value) {
            field = max(0L, value)
        }

    /**
     * Number of consecutive below-threshold frames required to transition from ACTIVE to SILENT.
     */
    val hangoverFrames: Int
        get() = max(1, (hangoverDurationMs / frameDurationMs).toInt())

    /**
     * Current silence state. True when silence is active (transmission should pause).
     */
    @Volatile
    var isSilent: Boolean = true
        private set

    /**
     * Number of consecutive frames below the threshold.
     */
    var consecutiveSilentFrames: Int = hangoverFrames
        private set

    /**
     * Calculates the normalized linear RMS amplitude of a 16-bit PCM buffer in range [0.0, 1.0].
     *
     * @param samples Array of interleaved 16-bit signed PCM samples.
     * @param offset Starting index in [samples].
     * @param length Number of samples to process.
     * @return Normalized linear RMS amplitude in [0.0, 1.0].
     */
    fun calculateRmsLinear(
        samples: ShortArray,
        offset: Int = 0,
        length: Int = samples.size - offset
    ): Double {
        if (length <= 0 || offset < 0 || offset + length > samples.size) {
            return 0.0
        }

        var sumSquares = 0.0
        val end = offset + length
        for (i in offset until end) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }

        val meanSquare = sumSquares / length
        return sqrt(meanSquare) / MAX_PCM16_VALUE
    }

    /**
     * Calculates the RMS energy level in decibels relative to full scale (dBFS).
     *
     * @param samples Array of interleaved 16-bit signed PCM samples.
     * @param offset Starting index in [samples].
     * @param length Number of samples to process.
     * @return RMS energy in dBFS (clamped to [SILENCE_DBFS_FLOOR]).
     */
    fun calculateRmsDbfs(
        samples: ShortArray,
        offset: Int = 0,
        length: Int = samples.size - offset
    ): Double {
        val rmsLinear = calculateRmsLinear(samples, offset, length)
        return linearToDbfs(rmsLinear)
    }

    /**
     * Converts a normalized linear amplitude [0.0, 1.0] to decibels relative to full scale (dBFS).
     */
    fun linearToDbfs(linear: Double): Double {
        if (linear <= 0.0) return SILENCE_DBFS_FLOOR
        val dbfs = 20.0 * log10(linear)
        return max(SILENCE_DBFS_FLOOR, min(0.0, dbfs))
    }

    /**
     * Converts a dBFS value to normalized linear amplitude [0.0, 1.0].
     */
    fun dbfsToLinear(dbfs: Double): Double {
        if (dbfs <= SILENCE_DBFS_FLOOR) return 0.0
        return 10.0.pow(dbfs / 20.0)
    }

    /**
     * Evaluates a PCM frame against the silence threshold and hangover state machine.
     *
     * State Machine:
     * - If RMS >= threshold: Fast Attack! Immediately transitions to ACTIVE ([isSilent] = false),
     *   clearing [consecutiveSilentFrames] to 0.
     * - If RMS < threshold:
     *   - If currently SILENT: Stays SILENT, incrementing [consecutiveSilentFrames].
     *   - If currently ACTIVE: Enters/continues hangover duration. Increments [consecutiveSilentFrames].
     *     Only transitions to SILENT once [consecutiveSilentFrames] >= [hangoverFrames].
     *
     * @param samples Array of 16-bit PCM samples (typically 1920 samples for 20ms stereo).
     * @param offset Starting index in [samples].
     * @param length Number of samples to analyze.
     * @return [SilenceDetectionResult] containing updated silence decision and energy metrics.
     */
    @Synchronized
    fun processFrame(
        samples: ShortArray,
        offset: Int = 0,
        length: Int = samples.size - offset
    ): SilenceDetectionResult {
        val rmsLinear = calculateRmsLinear(samples, offset, length)
        val rmsDbfs = linearToDbfs(rmsLinear)
        val previousSilentState = isSilent

        if (rmsDbfs >= thresholdDbfs) {
            // Fast attack: immediately transition to active audio
            consecutiveSilentFrames = 0
            isSilent = false
        } else {
            consecutiveSilentFrames++
            if (isSilent) {
                // Cold silence or already in silence: remain silent
                isSilent = true
            } else {
                // Currently active: apply hangover hold
                if (consecutiveSilentFrames >= hangoverFrames) {
                    isSilent = true
                } else {
                    isSilent = false
                }
            }
        }

        val stateChanged = (isSilent != previousSilentState)
        return SilenceDetectionResult(
            isSilent = isSilent,
            rmsDbfs = rmsDbfs,
            rmsLinear = rmsLinear,
            consecutiveSilentFrames = consecutiveSilentFrames,
            stateChanged = stateChanged
        )
    }

    /**
     * Calibrates the silence threshold based on an observed ambient noise profile.
     * Sets [thresholdDbfs] to peak noise level + [marginDb].
     *
     * @param noiseFrames List of PCM frames captured during ambient/background noise.
     * @param marginDb Safety margin in decibels above peak noise floor (default: 6.0 dB).
     * @return The newly calibrated [thresholdDbfs].
     */
    @Synchronized
    fun calibrateThreshold(noiseFrames: List<ShortArray>, marginDb: Double = 6.0): Double {
        if (noiseFrames.isEmpty()) return thresholdDbfs

        var maxNoiseDbfs = SILENCE_DBFS_FLOOR
        for (frame in noiseFrames) {
            val dbfs = calculateRmsDbfs(frame)
            if (dbfs > maxNoiseDbfs) {
                maxNoiseDbfs = dbfs
            }
        }

        thresholdDbfs = max(SILENCE_DBFS_FLOOR, min(0.0, maxNoiseDbfs + marginDb))
        return thresholdDbfs
    }

    /**
     * Resets the silence detector state.
     *
     * @param startSilent If true, initializes in SILENT state with full hangover counter.
     */
    @Synchronized
    fun reset(startSilent: Boolean = true) {
        isSilent = startSilent
        consecutiveSilentFrames = if (startSilent) hangoverFrames else 0
    }
}
