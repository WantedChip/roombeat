package com.roombeat.app.audio.buffer

import com.roombeat.app.audio.NativeAudioEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Constants governing the RoomBeat scheduled audio jitter buffer.
 */
object JitterBufferConstants {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2 // Stereo
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_CHANNEL = 960 // 20ms at 48kHz
    const val INTERLEAVED_SAMPLES = SAMPLES_PER_CHANNEL * CHANNELS // 1920 floats
    const val DEFAULT_TARGET_DEPTH_MS = 120 // 6 frames of 20ms
    const val DEFAULT_CAPACITY_FRAMES = 64 // ~1280ms
    const val MAX_PLC_CONSECUTIVE_FRAMES = 3 // Maximum consecutive lost frames concealed before fade-out
    const val MAX_OPUS_PAYLOAD_BYTES = 1500
}

/**
 * Performance and diagnostic telemetry metrics for the jitter buffer.
 */
data class JitterBufferStats(
    val totalPacketsReceived: Long = 0L,
    val packetsPlayed: Long = 0L,
    val plcCount: Long = 0L,
    val latePacketsDropped: Long = 0L,
    val duplicatePacketsDropped: Long = 0L,
    val underrunCount: Long = 0L,
    val currentQueuedFrames: Int = 0,
    val targetDepthMs: Int = JitterBufferConstants.DEFAULT_TARGET_DEPTH_MS,
    val consecutiveLostFrames: Int = 0
)

/**
 * Interface abstracting the native JNI layer of AudioJitterBuffer.
 * Enables 100% deterministic software-mocked execution in host-side JVM unit tests
 * without UnsatisfiedLinkError failures.
 */
interface AudioJitterBufferBridge {
    fun create(targetDepthMs: Int, capacityFrames: Int): Long
    fun destroy(handle: Long)
    fun pushPacket(handle: Long, sequenceNumber: Long, presentationTimeUs: Long, payload: ByteArray, offset: Int, length: Int): Boolean
    fun pushDecodedFrame(handle: Long, sequenceNumber: Long, presentationTimeUs: Long, pcmData: FloatArray, numFrames: Int): Boolean
    fun pullFrames(handle: Long, outputBuffer: FloatArray, numFrames: Int): Int
    fun setTargetDepthMs(handle: Long, depthMs: Int)
    fun getTargetDepthMs(handle: Long): Int
    fun getQueuedFrames(handle: Long): Int
    fun getStats(handle: Long): JitterBufferStats
    fun reset(handle: Long)
    fun attachToEngine(handle: Long): Boolean
}

/**
 * Thread-safe, lockless circular jitter buffer for RoomBeat.
 *
 * Orders incoming packets by sequence number and scheduled presentation timestamp (T_presentation),
 * absorbs up to 120ms of network arrival jitter, detects sequence gaps to trigger Opus PLC
 * (Packet Loss Concealment), and applies 20ms raised-cosine fade-out/fade-in transitions
 * to prevent audio clicks and pops.
 */
class AudioJitterBuffer(
    targetDepthMs: Int = JitterBufferConstants.DEFAULT_TARGET_DEPTH_MS,
    capacityFrames: Int = JitterBufferConstants.DEFAULT_CAPACITY_FRAMES,
    private val bridge: AudioJitterBufferBridge = DefaultAudioJitterBufferBridge.INSTANCE
) : AutoCloseable {

    var handle: Long = bridge.create(targetDepthMs, capacityFrames)
        private set

    private var isClosed = false

    var targetDepthMs: Int
        get() {
            check(!isClosed) { "AudioJitterBuffer is closed" }
            return bridge.getTargetDepthMs(handle)
        }
        set(value) {
            check(!isClosed) { "AudioJitterBuffer is closed" }
            bridge.setTargetDepthMs(handle, value)
        }

    val queuedFrames: Int
        get() {
            check(!isClosed) { "AudioJitterBuffer is closed" }
            return bridge.getQueuedFrames(handle)
        }

    /**
     * Ingest an Opus-compressed packet.
     * Returns true if buffered, false if dropped (late, duplicate, or capacity limit).
     */
    fun pushPacket(
        sequenceNumber: Long,
        presentationTimeUs: Long,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ): Boolean {
        check(!isClosed) { "AudioJitterBuffer is closed" }
        return bridge.pushPacket(handle, sequenceNumber, presentationTimeUs, payload, offset, length)
    }

    /**
     * Ingest a pre-decoded Float32 audio frame (1920 interleaved stereo samples = 960 frames).
     */
    fun pushDecodedFrame(
        sequenceNumber: Long,
        presentationTimeUs: Long,
        pcmData: FloatArray,
        numFrames: Int = pcmData.size / JitterBufferConstants.CHANNELS
    ): Boolean {
        check(!isClosed) { "AudioJitterBuffer is closed" }
        return bridge.pushDecodedFrame(handle, sequenceNumber, presentationTimeUs, pcmData, numFrames)
    }

    /**
     * Pulls interleaved stereo Float32 audio samples from the jitter buffer.
     * Implements clock scheduling, PLC synthesis for gaps, and smooth crossfading.
     * Returns the actual number of frames rendered into outputBuffer.
     */
    fun pullFrames(outputBuffer: FloatArray, numFrames: Int = outputBuffer.size / JitterBufferConstants.CHANNELS): Int {
        check(!isClosed) { "AudioJitterBuffer is closed" }
        return bridge.pullFrames(handle, outputBuffer, numFrames)
    }

    /**
     * Returns a snapshot of buffer telemetry and diagnostics counters.
     */
    fun getStats(): JitterBufferStats {
        check(!isClosed) { "AudioJitterBuffer is closed" }
        return bridge.getStats(handle)
    }

    /**
     * Discards all queued frames and resets buffer state.
     */
    fun reset() {
        check(!isClosed) { "AudioJitterBuffer is closed" }
        bridge.reset(handle)
    }

    /**
     * Connects this jitter buffer directly to the native OboeAudioPlayer stream as an AudioSource.
     */
    fun attachToEngine(): Boolean {
        check(!isClosed) { "AudioJitterBuffer is closed" }
        return bridge.attachToEngine(handle)
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            if (handle != 0L) {
                bridge.destroy(handle)
                handle = 0L
            }
        }
    }
}

/**
 * Default bridge implementation delegating to JNI native methods on Android,
 * or falling back to a deterministic software mock state machine during JVM unit tests.
 */
open class DefaultAudioJitterBufferBridge : AudioJitterBufferBridge {
    companion object {
        val INSTANCE: DefaultAudioJitterBufferBridge = DefaultAudioJitterBufferBridge()
    }

    private val isNative: Boolean
        get() = NativeAudioEngine.isNativeLoaded

    private val nextHandle = AtomicLong(1L)
    private val buffers = ConcurrentHashMap<Long, MockJitterBufferState>()

    override fun create(targetDepthMs: Int, capacityFrames: Int): Long {
        return if (isNative) {
            nativeCreate(targetDepthMs, capacityFrames)
        } else {
            val handle = nextHandle.getAndIncrement()
            buffers[handle] = MockJitterBufferState(targetDepthMs, capacityFrames)
            handle
        }
    }

    override fun destroy(handle: Long) {
        if (isNative) {
            nativeDestroy(handle)
        } else {
            buffers.remove(handle)
        }
    }

    override fun pushPacket(
        handle: Long,
        sequenceNumber: Long,
        presentationTimeUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        return if (isNative) {
            nativePushPacket(handle, sequenceNumber, presentationTimeUs, payload, offset, length)
        } else {
            buffers[handle]?.pushPacket(sequenceNumber, presentationTimeUs, payload, offset, length) ?: false
        }
    }

    override fun pushDecodedFrame(
        handle: Long,
        sequenceNumber: Long,
        presentationTimeUs: Long,
        pcmData: FloatArray,
        numFrames: Int
    ): Boolean {
        return if (isNative) {
            nativePushDecodedFrame(handle, sequenceNumber, presentationTimeUs, pcmData, numFrames)
        } else {
            buffers[handle]?.pushDecodedFrame(sequenceNumber, presentationTimeUs, pcmData, numFrames) ?: false
        }
    }

    override fun pullFrames(handle: Long, outputBuffer: FloatArray, numFrames: Int): Int {
        return if (isNative) {
            nativePullFrames(handle, outputBuffer, numFrames)
        } else {
            buffers[handle]?.pullFrames(outputBuffer, numFrames) ?: 0
        }
    }

    override fun setTargetDepthMs(handle: Long, depthMs: Int) {
        if (isNative) {
            nativeSetTargetDepthMs(handle, depthMs)
        } else {
            buffers[handle]?.targetDepthMs = depthMs
        }
    }

    override fun getTargetDepthMs(handle: Long): Int {
        return if (isNative) {
            nativeGetTargetDepthMs(handle)
        } else {
            buffers[handle]?.targetDepthMs ?: JitterBufferConstants.DEFAULT_TARGET_DEPTH_MS
        }
    }

    override fun getQueuedFrames(handle: Long): Int {
        return if (isNative) {
            nativeGetQueuedFrames(handle)
        } else {
            buffers[handle]?.queuedFrames ?: 0
        }
    }

    override fun getStats(handle: Long): JitterBufferStats {
        return if (isNative) {
            val arr = nativeGetStats(handle)
            if (arr != null && arr.size >= 9) {
                JitterBufferStats(
                    totalPacketsReceived = arr[0],
                    packetsPlayed = arr[1],
                    plcCount = arr[2],
                    latePacketsDropped = arr[3],
                    duplicatePacketsDropped = arr[4],
                    underrunCount = arr[5],
                    currentQueuedFrames = arr[6].toInt(),
                    targetDepthMs = arr[7].toInt(),
                    consecutiveLostFrames = arr[8].toInt()
                )
            } else {
                JitterBufferStats()
            }
        } else {
            buffers[handle]?.getStats() ?: JitterBufferStats()
        }
    }

    override fun reset(handle: Long) {
        if (isNative) {
            nativeReset(handle)
        } else {
            buffers[handle]?.reset()
        }
    }

    override fun attachToEngine(handle: Long): Boolean {
        return if (isNative) {
            nativeAttachToEngine(handle)
        } else {
            true // Mock attach always succeeds
        }
    }

    // ========================================================================
    // Native JNI Declarations
    // ========================================================================

    private external fun nativeCreate(targetDepthMs: Int, capacityFrames: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativePushPacket(
        handle: Long,
        sequenceNumber: Long,
        presentationTimeUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int
    ): Boolean
    private external fun nativePushDecodedFrame(
        handle: Long,
        sequenceNumber: Long,
        presentationTimeUs: Long,
        pcmData: FloatArray,
        numFrames: Int
    ): Boolean
    private external fun nativePullFrames(handle: Long, outputBuffer: FloatArray, numFrames: Int): Int
    private external fun nativeSetTargetDepthMs(handle: Long, depthMs: Int)
    private external fun nativeGetTargetDepthMs(handle: Long): Int
    private external fun nativeGetQueuedFrames(handle: Long): Int
    private external fun nativeGetStats(handle: Long): LongArray?
    private external fun nativeReset(handle: Long)
    private external fun nativeAttachToEngine(handle: Long): Boolean

    // ========================================================================
    // Deterministic Software Mock for Host-Side JVM Unit Tests
    // ========================================================================

    internal class MockJitterBufferState(
        initialTargetDepthMs: Int,
        val capacityFrames: Int
    ) {
        private data class StoredFrame(
            val sequenceNumber: Long,
            val presentationTimeUs: Long,
            val pcm: FloatArray
        )

        private val lock = Any()
        private var _targetDepthMs: Int = max(20, initialTargetDepthMs)

        var targetDepthMs: Int
            get() = synchronized(lock) { _targetDepthMs }
            set(value) = synchronized(lock) { _targetDepthMs = max(20, value) }

        private val slots = HashMap<Long, StoredFrame>()
        private var playbackStarted = false
        private var nextPlaySeq = 0L
        private var consecutiveLostFrames = 0
        private var isBuffering = true
        private var isMuted = true

        private var totalPacketsReceived = 0L
        private var packetsPlayed = 0L
        private var plcCount = 0L
        private var latePacketsDropped = 0L
        private var duplicatePacketsDropped = 0L
        private var underrunCount = 0L

        // Active remainder buffer for sub-frame renders (e.g. burst of 192 samples)
        private var activeRemainder: FloatArray? = null
        private var activeRemainderOffset = 0

        val queuedFrames: Int
            get() = synchronized(lock) { slots.size }

        fun reset() {
            synchronized(lock) {
                slots.clear()
                playbackStarted = false
                nextPlaySeq = 0L
                consecutiveLostFrames = 0
                isBuffering = true
                isMuted = true
                activeRemainder = null
                activeRemainderOffset = 0
            }
        }

        fun pushPacket(
            seq: Long,
            presentationTimeUs: Long,
            payload: ByteArray,
            offset: Int,
            length: Int
        ): Boolean {
            if (length <= 0) return false
            // Create a synthetic stereo PCM wave representing this Opus packet
            val syntheticPcm = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
            for (i in 0 until JitterBufferConstants.SAMPLES_PER_CHANNEL) {
                val sampleVal = 0.5f * (seq % 10 + 1) / 10.0f
                syntheticPcm[i * 2] = sampleVal
                syntheticPcm[i * 2 + 1] = sampleVal
            }
            return storeFrame(seq, presentationTimeUs, syntheticPcm)
        }

        fun pushDecodedFrame(
            seq: Long,
            presentationTimeUs: Long,
            pcmData: FloatArray,
            numFrames: Int
        ): Boolean {
            if (numFrames <= 0) return false
            val copy = pcmData.copyOf(numFrames * JitterBufferConstants.CHANNELS)
            return storeFrame(seq, presentationTimeUs, copy)
        }

        private fun storeFrame(seq: Long, presentationTimeUs: Long, pcm: FloatArray): Boolean {
            synchronized(lock) {
                if (playbackStarted && seq < nextPlaySeq) {
                    latePacketsDropped++
                    return false
                }
                if (slots.containsKey(seq)) {
                    duplicatePacketsDropped++
                    return false
                }
                if (playbackStarted && seq >= nextPlaySeq + capacityFrames) {
                    return false
                }

                slots[seq] = StoredFrame(seq, presentationTimeUs, pcm)
                totalPacketsReceived++
                return true
            }
        }

        fun pullFrames(outputBuffer: FloatArray, numFrames: Int): Int {
            if (numFrames <= 0) return 0

            var framesRendered = 0
            val channels = JitterBufferConstants.CHANNELS

            while (framesRendered < numFrames) {
                val remainder = activeRemainder
                if (remainder != null && activeRemainderOffset < (remainder.size / channels)) {
                    val remainingInFrame = (remainder.size / channels) - activeRemainderOffset
                    val toCopy = min(numFrames - framesRendered, remainingInFrame)

                    System.arraycopy(
                        remainder,
                        activeRemainderOffset * channels,
                        outputBuffer,
                        framesRendered * channels,
                        toCopy * channels
                    )
                    activeRemainderOffset += toCopy
                    framesRendered += toCopy
                } else {
                    val next20ms = fetchNext20msFrame()
                    if (next20ms != null) {
                        activeRemainder = next20ms
                        activeRemainderOffset = 0
                    } else {
                        // Silence
                        val silence = numFrames - framesRendered
                        for (i in (framesRendered * channels) until (numFrames * channels)) {
                            outputBuffer[i] = 0.0f
                        }
                        framesRendered += silence
                        break
                    }
                }
            }
            return framesRendered
        }

        private fun fetchNext20msFrame(): FloatArray? {
            synchronized(lock) {
                if (isBuffering) {
                    val targetFrames = max(1, targetDepthMs / JitterBufferConstants.FRAME_DURATION_MS)
                    if (slots.size < targetFrames) {
                        return null
                    }
                    isBuffering = false
                    playbackStarted = true
                    nextPlaySeq = slots.keys.minOrNull() ?: 0L
                }

                val frame = slots.remove(nextPlaySeq)
                if (frame != null) {
                    consecutiveLostFrames = 0
                    nextPlaySeq++
                    packetsPlayed++

                    val out = frame.pcm.copyOf()
                    if (isMuted) {
                        applyFadeIn(out)
                        isMuted = false
                    }
                    return out
                } else {
                    // Sequence gap -> PLC
                    consecutiveLostFrames++

                    if (consecutiveLostFrames <= JitterBufferConstants.MAX_PLC_CONSECUTIVE_FRAMES) {
                        plcCount++
                        nextPlaySeq++
                        packetsPlayed++

                        // Synthesize smooth replacement frame
                        val plcFrame = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES) { 0.25f }
                        if (consecutiveLostFrames == JitterBufferConstants.MAX_PLC_CONSECUTIVE_FRAMES) {
                            applyFadeOut(plcFrame)
                            isMuted = true
                        }
                        return plcFrame
                    } else {
                        underrunCount++
                        isMuted = true
                        if (slots.isNotEmpty()) {
                            val nextAvail = slots.keys.filter { it > nextPlaySeq }.minOrNull()
                            if (nextAvail != null) {
                                nextPlaySeq = nextAvail
                            } else {
                                nextPlaySeq++
                            }
                        } else {
                            isBuffering = true
                        }
                        return null
                    }
                }
            }
        }

        fun getStats(): JitterBufferStats {
            synchronized(lock) {
                return JitterBufferStats(
                    totalPacketsReceived = totalPacketsReceived,
                    packetsPlayed = packetsPlayed,
                    plcCount = plcCount,
                    latePacketsDropped = latePacketsDropped,
                    duplicatePacketsDropped = duplicatePacketsDropped,
                    underrunCount = underrunCount,
                    currentQueuedFrames = slots.size,
                    targetDepthMs = targetDepthMs,
                    consecutiveLostFrames = consecutiveLostFrames
                )
            }
        }

        private fun applyFadeOut(pcm: FloatArray) {
            val n = JitterBufferConstants.SAMPLES_PER_CHANNEL
            for (i in 0 until n) {
                val t = i.toDouble() / (n - 1)
                val w = (0.5 * (1.0 + cos(Math.PI * t))).toFloat()
                pcm[i * 2] *= w
                pcm[i * 2 + 1] *= w
            }
        }

        private fun applyFadeIn(pcm: FloatArray) {
            val n = JitterBufferConstants.SAMPLES_PER_CHANNEL
            for (i in 0 until n) {
                val t = i.toDouble() / (n - 1)
                val w = (0.5 * (1.0 - cos(Math.PI * t))).toFloat()
                pcm[i * 2] *= w
                pcm[i * 2 + 1] *= w
            }
        }
    }
}
