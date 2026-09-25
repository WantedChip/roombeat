package com.roombeat.app.source.local

import com.roombeat.app.audio.codec.OpusConstants

/**
 * Slices a continuous stream of interleaved 48kHz stereo PCM samples into exact 20ms frames.
 *
 * For standard RoomBeat 48,000 Hz stereo audio:
 * - 20ms = 960 frames per channel.
 * - Stereo (2 channels) = 1920 interleaved samples per 20ms frame.
 *
 * Capabilities:
 * - Buffers remainder across incoming buffers of variable or uneven size.
 * - Callback-based and collection-based frame emission.
 * - Supports both [ShortArray] (PCM16) and [FloatArray] formats.
 * - Supports zero-padded flushing of final frames and state reset.
 */
class AudioFrameChunker(
    val frameSizePerChannel: Int = OpusConstants.FRAME_SIZE_SAMPLES, // 960
    val channels: Int = OpusConstants.CHANNELS                      // 2
) {
    val frameSamples: Int = frameSizePerChannel * channels          // 1920

    // Internal remainder buffer for ShortArray (PCM16)
    private val shortRemainder = ShortArray(frameSamples)
    private var shortRemainderCount = 0

    // Internal remainder buffer for FloatArray (Float32)
    private val floatRemainder = FloatArray(frameSamples)
    private var floatRemainderCount = 0

    val currentShortRemainderCount: Int get() = shortRemainderCount
    val currentFloatRemainderCount: Int get() = floatRemainderCount

    /**
     * Pushes incoming ShortArray PCM samples, emitting complete 20ms frames to [onFrame].
     */
    fun pushShort(samples: ShortArray, onFrame: (ShortArray) -> Unit) {
        var srcPos = 0
        val total = samples.size

        // 1. If we have a pending remainder, attempt to complete it
        if (shortRemainderCount > 0) {
            val needed = frameSamples - shortRemainderCount
            if (total - srcPos >= needed) {
                System.arraycopy(samples, srcPos, shortRemainder, shortRemainderCount, needed)
                srcPos += needed
                shortRemainderCount = 0
                onFrame(shortRemainder.copyOf())
            } else {
                val available = total - srcPos
                System.arraycopy(samples, srcPos, shortRemainder, shortRemainderCount, available)
                shortRemainderCount += available
                return
            }
        }

        // 2. Emit full 20ms frames directly from the incoming array
        while (total - srcPos >= frameSamples) {
            val frame = ShortArray(frameSamples)
            System.arraycopy(samples, srcPos, frame, 0, frameSamples)
            srcPos += frameSamples
            onFrame(frame)
        }

        // 3. Store any trailing remainder
        val leftover = total - srcPos
        if (leftover > 0) {
            System.arraycopy(samples, srcPos, shortRemainder, 0, leftover)
            shortRemainderCount = leftover
        }
    }

    /**
     * Convenience method returning all complete 20ms frames from the pushed samples.
     */
    fun chunkShort(samples: ShortArray): List<ShortArray> {
        val result = mutableListOf<ShortArray>()
        pushShort(samples) { frame -> result.add(frame) }
        return result
    }

    /**
     * Pushes incoming FloatArray PCM samples, emitting complete 20ms frames to [onFrame].
     */
    fun pushFloat(samples: FloatArray, onFrame: (FloatArray) -> Unit) {
        var srcPos = 0
        val total = samples.size

        // 1. If we have a pending remainder, attempt to complete it
        if (floatRemainderCount > 0) {
            val needed = frameSamples - floatRemainderCount
            if (total - srcPos >= needed) {
                System.arraycopy(samples, srcPos, floatRemainder, floatRemainderCount, needed)
                srcPos += needed
                floatRemainderCount = 0
                onFrame(floatRemainder.copyOf())
            } else {
                val available = total - srcPos
                System.arraycopy(samples, srcPos, floatRemainder, floatRemainderCount, available)
                floatRemainderCount += available
                return
            }
        }

        // 2. Emit full 20ms frames directly from the incoming array
        while (total - srcPos >= frameSamples) {
            val frame = FloatArray(frameSamples)
            System.arraycopy(samples, srcPos, frame, 0, frameSamples)
            srcPos += frameSamples
            onFrame(frame)
        }

        // 3. Store any trailing remainder
        val leftover = total - srcPos
        if (leftover > 0) {
            System.arraycopy(samples, srcPos, floatRemainder, 0, leftover)
            floatRemainderCount = leftover
        }
    }

    /**
     * Convenience method returning all complete 20ms frames from the pushed float samples.
     */
    fun chunkFloat(samples: FloatArray): List<FloatArray> {
        val result = mutableListOf<FloatArray>()
        pushFloat(samples) { frame -> result.add(frame) }
        return result
    }

    /**
     * Flushes any remaining samples in the short remainder buffer.
     * If [zeroPad] is true and samples exist, emits a zero-padded 20ms frame.
     */
    fun flushShort(zeroPad: Boolean = true, onFrame: (ShortArray) -> Unit) {
        if (shortRemainderCount > 0) {
            if (zeroPad) {
                shortRemainder.fill(0, shortRemainderCount, frameSamples)
                onFrame(shortRemainder.copyOf())
            }
            shortRemainderCount = 0
        }
    }

    /**
     * Flushes short remainder returning a list of emitted frames (0 or 1 frame).
     */
    fun flushShort(zeroPad: Boolean = true): List<ShortArray> {
        val result = mutableListOf<ShortArray>()
        flushShort(zeroPad) { frame -> result.add(frame) }
        return result
    }

    /**
     * Flushes any remaining samples in the float remainder buffer.
     * If [zeroPad] is true and samples exist, emits a zero-padded 20ms frame.
     */
    fun flushFloat(zeroPad: Boolean = true, onFrame: (FloatArray) -> Unit) {
        if (floatRemainderCount > 0) {
            if (zeroPad) {
                floatRemainder.fill(0.0f, floatRemainderCount, frameSamples)
                onFrame(floatRemainder.copyOf())
            }
            floatRemainderCount = 0
        }
    }

    /**
     * Flushes float remainder returning a list of emitted frames (0 or 1 frame).
     */
    fun flushFloat(zeroPad: Boolean = true): List<FloatArray> {
        val result = mutableListOf<FloatArray>()
        flushFloat(zeroPad) { frame -> result.add(frame) }
        return result
    }

    /**
     * Resets all internal buffers and discards any partial remainders.
     */
    fun reset() {
        shortRemainderCount = 0
        floatRemainderCount = 0
    }
}
