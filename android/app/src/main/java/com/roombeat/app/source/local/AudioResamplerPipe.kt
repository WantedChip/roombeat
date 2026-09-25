package com.roombeat.app.source.local

import com.roombeat.app.audio.codec.OpusConstants
import kotlin.math.floor

/**
 * Resampling interpolation mode.
 */
enum class ResamplerQuality {
    LINEAR,
    CUBIC
}

/**
 * Converts arbitrary source audio formats (sample rates: 44.1kHz, 32kHz, 22.05kHz, 96kHz, etc.;
 * channels: 1 mono or 2 stereo) to standard RoomBeat format: 48,000 Hz, stereo (2 channels).
 *
 * Features:
 * - High-quality linear or cubic Hermite spline interpolation without pitch distortion or clicks.
 * - Fast-path passthrough when input is already 48,000 Hz stereo.
 * - Channel handling: mono duplicate (L = R = sample), stereo preserved (L, R).
 * - Full support for both ShortArray (PCM16) and FloatArray audio representations.
 * - Stateful streaming continuity across incoming chunks with fractional sample tracking
 *   to eliminate boundary clicks.
 */
class AudioResamplerPipe(
    inputSampleRate: Int = 44100,
    inputChannels: Int = 2,
    val targetSampleRate: Int = OpusConstants.SAMPLE_RATE, // 48000
    val targetChannels: Int = OpusConstants.CHANNELS,      // 2
    var quality: ResamplerQuality = ResamplerQuality.LINEAR
) {
    var inputSampleRate: Int = inputSampleRate
        private set
    var inputChannels: Int = inputChannels
        private set

    val isPassthrough: Boolean
        get() = inputSampleRate == targetSampleRate && inputChannels == targetChannels

    // Step size: ratio of input frames to output frames
    private var step: Double = inputSampleRate.toDouble() / targetSampleRate.toDouble()

    // History state across successive chunk calls to maintain seamless phase continuity
    private var lastInputL: Float = 0f
    private var lastInputR: Float = 0f
    private var prevInputL: Float = 0f
    private var prevInputR: Float = 0f
    private var hasLastInput: Boolean = false

    // Relative continuous position in the current chunk coordinate system for the next output frame
    private var currentT: Double = 0.0

    init {
        require(inputSampleRate > 0) { "inputSampleRate must be positive: $inputSampleRate" }
        require(inputChannels > 0) { "inputChannels must be positive: $inputChannels" }
        require(targetSampleRate > 0) { "targetSampleRate must be positive: $targetSampleRate" }
        require(targetChannels == 2) { "targetChannels must be 2 (stereo standard)" }
    }

    /**
     * Reconfigures input format parameters. Resets internal phase state if parameters change.
     */
    fun configure(sampleRate: Int, channels: Int) {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channels > 0) { "channels must be positive: $channels" }

        if (this.inputSampleRate != sampleRate || this.inputChannels != channels) {
            this.inputSampleRate = sampleRate
            this.inputChannels = channels
            this.step = sampleRate.toDouble() / targetSampleRate.toDouble()
            reset()
        }
    }

    /**
     * Resets internal interpolation state, phase, and history.
     */
    fun reset() {
        lastInputL = 0f
        lastInputR = 0f
        prevInputL = 0f
        prevInputR = 0f
        hasLastInput = false
        currentT = 0.0
    }

    /**
     * Resamples interleaved FloatArray audio samples to 48kHz stereo FloatArray.
     */
    fun resample(input: FloatArray): FloatArray {
        val inFrames = input.size / inputChannels
        if (inFrames == 0) return FloatArray(0)

        // Fast path: already 48kHz stereo
        if (isPassthrough) {
            return input.copyOf(inFrames * targetChannels)
        }

        // Fast path: 48kHz mono -> 48kHz stereo duplication
        if (inputSampleRate == targetSampleRate && inputChannels == 1) {
            val out = FloatArray(inFrames * 2)
            for (i in 0 until inFrames) {
                val s = input[i]
                out[i * 2] = s
                out[i * 2 + 1] = s
            }
            return out
        }

        val estimatedOutFrames = ((inFrames - currentT) / step).toInt().coerceAtLeast(0) + 4
        val tempOut = FloatArray(estimatedOutFrames * targetChannels)
        val writtenSamples = resampleInternal(input, tempOut)
        return tempOut.copyOf(writtenSamples)
    }

    /**
     * Resamples interleaved ShortArray (PCM16) audio samples to 48kHz stereo ShortArray.
     */
    fun resample(input: ShortArray): ShortArray {
        val inFrames = input.size / inputChannels
        if (inFrames == 0) return ShortArray(0)

        // Fast path: already 48kHz stereo
        if (isPassthrough) {
            return input.copyOf(inFrames * targetChannels)
        }

        // Fast path: 48kHz mono -> 48kHz stereo duplication
        if (inputSampleRate == targetSampleRate && inputChannels == 1) {
            val out = ShortArray(inFrames * 2)
            for (i in 0 until inFrames) {
                val s = input[i]
                out[i * 2] = s
                out[i * 2 + 1] = s
            }
            return out
        }

        // Convert ShortArray to FloatArray for linear/cubic interpolation
        val floatInput = FloatArray(input.size)
        for (i in input.indices) {
            floatInput[i] = input[i] / 32768.0f
        }

        val floatOutput = resample(floatInput)
        val shortOutput = ShortArray(floatOutput.size)
        for (i in floatOutput.indices) {
            val clamped = floatOutput[i].coerceIn(-1.0f, 1.0f)
            shortOutput[i] = (clamped * 32767.0f).toInt().toShort()
        }
        return shortOutput
    }

    /**
     * Resamples ShortArray (PCM16) input directly to FloatArray 48kHz stereo output.
     */
    fun resampleToFloat(input: ShortArray): FloatArray {
        val floatInput = FloatArray(input.size)
        for (i in input.indices) {
            floatInput[i] = input[i] / 32768.0f
        }
        return resample(floatInput)
    }

    /**
     * Resamples FloatArray input directly to ShortArray (PCM16) 48kHz stereo output.
     */
    fun resampleToShort(input: FloatArray): ShortArray {
        val floatOutput = resample(input)
        val shortOutput = ShortArray(floatOutput.size)
        for (i in floatOutput.indices) {
            val clamped = floatOutput[i].coerceIn(-1.0f, 1.0f)
            shortOutput[i] = (clamped * 32767.0f).toInt().toShort()
        }
        return shortOutput
    }

    /**
     * Core streaming resampling implementation operating on normalized Float32 samples.
     * Writes interleaved stereo samples to [outBuffer] and returns the count of samples written.
     */
    private fun resampleInternal(input: FloatArray, outBuffer: FloatArray): Int {
        val inFrames = input.size / inputChannels
        var outSampleIndex = 0

        var t = currentT
        if (!hasLastInput) {
            t = 0.0
        }

        val maxT = inFrames - 1.0

        while (t <= maxT && outSampleIndex + 1 < outBuffer.size) {
            var sampleL: Float
            var sampleR: Float

            if (t < 0.0) {
                // Interpolation across the boundary between lastInput (at t = -1.0) and frame 0 (at t = 0.0)
                val alpha = (t + 1.0).toFloat().coerceIn(0.0f, 1.0f)
                val (f0L, f0R) = getFrameStereo(input, 0)
                sampleL = (1f - alpha) * lastInputL + alpha * f0L
                sampleR = (1f - alpha) * lastInputR + alpha * f0R
            } else {
                val idx = floor(t).toInt()
                val alpha = (t - idx).toFloat().coerceIn(0.0f, 1.0f)

                if (idx < inFrames - 1) {
                    val (f0L, f0R) = getFrameStereo(input, idx)
                    val (f1L, f1R) = getFrameStereo(input, idx + 1)

                    if (quality == ResamplerQuality.CUBIC && idx > 0 && idx < inFrames - 2) {
                        val (prevL, prevR) = getFrameStereo(input, idx - 1)
                        val (next2L, next2R) = getFrameStereo(input, idx + 2)
                        sampleL = cubicInterpolate(prevL, f0L, f1L, next2L, alpha)
                        sampleR = cubicInterpolate(prevR, f0R, f1R, next2R, alpha)
                    } else {
                        sampleL = (1f - alpha) * f0L + alpha * f1L
                        sampleR = (1f - alpha) * f0R + alpha * f1R
                    }
                } else {
                    // Exactly at the last sample
                    val (fLastL, fLastR) = getFrameStereo(input, inFrames - 1)
                    sampleL = fLastL
                    sampleR = fLastR
                }
            }

            outBuffer[outSampleIndex++] = sampleL
            outBuffer[outSampleIndex++] = sampleR

            t += step
        }

        // Store boundary frames for next chunk
        val (finalL, finalR) = getFrameStereo(input, inFrames - 1)
        if (inFrames >= 2) {
            val (penL, penR) = getFrameStereo(input, inFrames - 2)
            prevInputL = penL
            prevInputR = penR
        } else {
            prevInputL = lastInputL
            prevInputR = lastInputR
        }
        lastInputL = finalL
        lastInputR = finalR
        hasLastInput = true

        // Next chunk coordinate position: relative to the new chunk's frame 0
        currentT = t - inFrames

        return outSampleIndex
    }

    /**
     * Flushes any remaining trailing frame at the end of the stream.
     */
    fun flush(): ShortArray {
        val floatFlushed = flushFloat()
        val shortFlushed = ShortArray(floatFlushed.size)
        for (i in floatFlushed.indices) {
            shortFlushed[i] = (floatFlushed[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
        }
        return shortFlushed
    }

    /**
     * Flushes trailing audio state into FloatArray.
     */
    fun flushFloat(): FloatArray {
        if (!hasLastInput) return FloatArray(0)
        // If currentT is before 0, emit the boundary sample
        if (currentT < 0.0) {
            val alpha = (currentT + 1.0).toFloat().coerceIn(0.0f, 1.0f)
            val l = (1f - alpha) * prevInputL + alpha * lastInputL
            val r = (1f - alpha) * prevInputR + alpha * lastInputR
            reset()
            return floatArrayOf(l, r)
        }
        reset()
        return FloatArray(0)
    }

    private fun getFrameStereo(input: FloatArray, frameIndex: Int): Pair<Float, Float> {
        return if (inputChannels == 1) {
            val s = input[frameIndex]
            Pair(s, s)
        } else {
            val offset = frameIndex * inputChannels
            Pair(input[offset], input[offset + 1])
        }
    }

    private fun cubicInterpolate(y0: Float, y1: Float, y2: Float, y3: Float, mu: Float): Float {
        val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
        val a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3
        val a2 = -0.5f * y0 + 0.5f * y2
        val a3 = y1
        return ((a0 * mu + a1) * mu + a2) * mu + a3
    }
}
