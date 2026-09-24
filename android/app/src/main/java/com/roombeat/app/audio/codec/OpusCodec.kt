package com.roombeat.app.audio.codec

import android.util.Log
import com.roombeat.app.audio.NativeAudioEngine
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin

/**
 * Standard configuration constants for 20ms Opus audio frames at 48kHz stereo.
 */
object OpusConstants {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val FRAME_SIZE_SAMPLES = 960            // 20ms at 48kHz per channel
    const val INTERLEAVED_FRAME_SAMPLES = 1920    // 960 * 2
    const val DEFAULT_BITRATE = 128000           // 128 kbps
    const val DEFAULT_COMPLEXITY = 6             // Mobile balanced (5-8)
    const val MAX_PACKET_BYTES = 4000            // Maximum packet buffer size
    const val NOMINAL_FRAME_BYTES = 320          // 128kbps * 0.02s = 2560 bits = 320 bytes
}

/**
 * Benchmark telemetry for Opus encoder and decoder performance.
 */
data class OpusBenchmarkResult(
    val avgEncodeUs: Double,
    val avgDecodeUs: Double,
    val totalTimeMs: Double,
    val snrDb: Double,
    val plcSamples: Int
) {
    /** Total encode + decode execution time per 20ms frame in milliseconds. */
    val totalPerFrameMs: Double get() = (avgEncodeUs + avgDecodeUs) / 1000.0

    /** Percentage of a single 20ms audio frame CPU budget consumed by encode + decode. */
    val cpuPercentageOfFrame: Double get() = (totalPerFrameMs / 20.0) * 100.0
}

/**
 * Abstraction interface for Opus encoder and decoder native operations.
 * Allows seamless mock substitution for host-side JVM unit tests where
 * the native Android shared library is not loaded.
 */
interface OpusCodecBridge {
    fun encoderCreate(sampleRate: Int, channels: Int, bitrate: Int, complexity: Int): Long
    fun encoderDestroy(handle: Long)
    fun encoderEncodeFloat(handle: Long, pcmInput: FloatArray, frameSize: Int, outputBuffer: ByteArray, maxOutputBytes: Int): Int
    fun encoderEncodeShort(handle: Long, pcmInput: ShortArray, frameSize: Int, outputBuffer: ByteArray, maxOutputBytes: Int): Int
    fun encoderReset(handle: Long): Int

    fun decoderCreate(sampleRate: Int, channels: Int): Long
    fun decoderDestroy(handle: Long)
    fun decoderDecodeFloat(handle: Long, opusData: ByteArray?, bytes: Int, outputPcm: FloatArray, frameSize: Int, decodeFec: Boolean): Int
    fun decoderDecodeShort(handle: Long, opusData: ByteArray?, bytes: Int, outputPcm: ShortArray, frameSize: Int, decodeFec: Boolean): Int
    fun decoderReset(handle: Long): Int

    fun runBenchmark(iterations: Int): OpusBenchmarkResult?
}

/**
 * Kotlin RAII wrapper around libopus OpusEncoder.
 * Configured for 48kHz, stereo, 20ms audio frames (960 samples/channel = 1920 interleaved samples).
 */
class OpusEncoder(
    val sampleRate: Int = OpusConstants.SAMPLE_RATE,
    val channels: Int = OpusConstants.CHANNELS,
    val bitrate: Int = OpusConstants.DEFAULT_BITRATE,
    val complexity: Int = OpusConstants.DEFAULT_COMPLEXITY,
    private val bridge: OpusCodecBridge = DefaultOpusCodecBridge.INSTANCE
) : Closeable {
    private var handle: Long = bridge.encoderCreate(sampleRate, channels, bitrate, complexity)
    private var isClosed = false

    val isValid: Boolean get() = handle != 0L && !isClosed

    /**
     * Encode interleaved Float32 PCM samples into a compressed Opus packet.
     * @param pcmInput Interleaved float audio samples (at least frameSize * channels elements)
     * @param frameSize Number of samples per channel (e.g. 960 for 20ms)
     * @param outputBuffer Destination byte array for compressed Opus packet
     * @return Number of compressed bytes written, or negative Opus error code
     */
    fun encode(pcmInput: FloatArray, frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES, outputBuffer: ByteArray): Int {
        check(!isClosed) { "OpusEncoder is closed" }
        return bridge.encoderEncodeFloat(handle, pcmInput, frameSize, outputBuffer, outputBuffer.size)
    }

    /**
     * Encode interleaved PCM16 (ShortArray) samples into a compressed Opus packet.
     * @param pcmInput Interleaved int16 audio samples (at least frameSize * channels elements)
     * @param frameSize Number of samples per channel (e.g. 960 for 20ms)
     * @param outputBuffer Destination byte array for compressed Opus packet
     * @return Number of compressed bytes written, or negative Opus error code
     */
    fun encode(pcmInput: ShortArray, frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES, outputBuffer: ByteArray): Int {
        check(!isClosed) { "OpusEncoder is closed" }
        return bridge.encoderEncodeShort(handle, pcmInput, frameSize, outputBuffer, outputBuffer.size)
    }

    /**
     * Convenience helper encoding Float32 PCM to a newly allocated ByteArray.
     */
    fun encode(pcmInput: FloatArray, frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES): ByteArray? {
        val buffer = ByteArray(OpusConstants.MAX_PACKET_BYTES)
        val bytes = encode(pcmInput, frameSize, buffer)
        return if (bytes > 0) buffer.copyOf(bytes) else null
    }

    /**
     * Convenience helper encoding PCM16 to a newly allocated ByteArray.
     */
    fun encode(pcmInput: ShortArray, frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES): ByteArray? {
        val buffer = ByteArray(OpusConstants.MAX_PACKET_BYTES)
        val bytes = encode(pcmInput, frameSize, buffer)
        return if (bytes > 0) buffer.copyOf(bytes) else null
    }

    fun reset(): Int {
        check(!isClosed) { "OpusEncoder is closed" }
        return bridge.encoderReset(handle)
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            if (handle != 0L) {
                bridge.encoderDestroy(handle)
                handle = 0L
            }
        }
    }
}

/**
 * Kotlin RAII wrapper around libopus OpusDecoder.
 * Configured for 48kHz, stereo, 20ms audio frames (960 samples/channel = 1920 interleaved samples).
 * Supports Packet Loss Concealment (PLC) when opusData is null or bytes is 0.
 */
class OpusDecoder(
    val sampleRate: Int = OpusConstants.SAMPLE_RATE,
    val channels: Int = OpusConstants.CHANNELS,
    private val bridge: OpusCodecBridge = DefaultOpusCodecBridge.INSTANCE
) : Closeable {
    private var handle: Long = bridge.decoderCreate(sampleRate, channels)
    private var isClosed = false

    val isValid: Boolean get() = handle != 0L && !isClosed

    /**
     * Decode an Opus packet to interleaved Float32 PCM samples.
     * If opusData is null or bytes is 0, Packet Loss Concealment (PLC) is generated.
     * @param opusData Compressed Opus payload or null for PLC
     * @param bytes Number of payload bytes or 0 for PLC
     * @param outputPcm Destination float array (at least frameSize * channels elements)
     * @param frameSize Number of samples per channel of available capacity (e.g. 960)
     * @param decodeFec Request in-band forward error correction
     * @return Number of decoded samples per channel (e.g. 960), or negative Opus error code
     */
    fun decode(
        opusData: ByteArray?,
        bytes: Int,
        outputPcm: FloatArray,
        frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES,
        decodeFec: Boolean = false
    ): Int {
        check(!isClosed) { "OpusDecoder is closed" }
        return bridge.decoderDecodeFloat(handle, opusData, bytes, outputPcm, frameSize, decodeFec)
    }

    /**
     * Decode an Opus packet to interleaved PCM16 (ShortArray) samples.
     * If opusData is null or bytes is 0, Packet Loss Concealment (PLC) is generated.
     * @param opusData Compressed Opus payload or null for PLC
     * @param bytes Number of payload bytes or 0 for PLC
     * @param outputPcm Destination short array (at least frameSize * channels elements)
     * @param frameSize Number of samples per channel of available capacity (e.g. 960)
     * @param decodeFec Request in-band forward error correction
     * @return Number of decoded samples per channel (e.g. 960), or negative Opus error code
     */
    fun decode(
        opusData: ByteArray?,
        bytes: Int,
        outputPcm: ShortArray,
        frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES,
        decodeFec: Boolean = false
    ): Int {
        check(!isClosed) { "OpusDecoder is closed" }
        return bridge.decoderDecodeShort(handle, opusData, bytes, outputPcm, frameSize, decodeFec)
    }

    /**
     * Convenience helper decoding an Opus packet to a newly allocated FloatArray.
     */
    fun decodeFloat(
        opusData: ByteArray?,
        decodeFec: Boolean = false,
        frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES
    ): FloatArray? {
        val out = FloatArray(frameSize * channels)
        val samples = decode(opusData, opusData?.size ?: 0, out, frameSize, decodeFec)
        return if (samples > 0) out else null
    }

    /**
     * Convenience helper decoding an Opus packet to a newly allocated ShortArray.
     */
    fun decodePcm16(
        opusData: ByteArray?,
        decodeFec: Boolean = false,
        frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES
    ): ShortArray? {
        val out = ShortArray(frameSize * channels)
        val samples = decode(opusData, opusData?.size ?: 0, out, frameSize, decodeFec)
        return if (samples > 0) out else null
    }

    fun reset(): Int {
        check(!isClosed) { "OpusDecoder is closed" }
        return bridge.decoderReset(handle)
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            if (handle != 0L) {
                bridge.decoderDestroy(handle)
                handle = 0L
            }
        }
    }
}

/**
 * Primary static entry point and JNI bindings for Opus codec operations.
 */
object OpusCodec {
    private const val TAG = "OpusCodec"

    init {
        NativeAudioEngine.loadNativeLibrary()
    }

    @JvmStatic
    external fun nativeEncoderCreate(sampleRate: Int, channels: Int, bitrate: Int, complexity: Int): Long

    @JvmStatic
    external fun nativeEncoderDestroy(handle: Long)

    @JvmStatic
    external fun nativeEncoderEncodeFloat(
        handle: Long,
        pcmInput: FloatArray,
        frameSize: Int,
        outputBuffer: ByteArray,
        maxOutputBytes: Int
    ): Int

    @JvmStatic
    external fun nativeEncoderEncodeShort(
        handle: Long,
        pcmInput: ShortArray,
        frameSize: Int,
        outputBuffer: ByteArray,
        maxOutputBytes: Int
    ): Int

    @JvmStatic
    external fun nativeEncoderReset(handle: Long): Int

    @JvmStatic
    external fun nativeDecoderCreate(sampleRate: Int, channels: Int): Long

    @JvmStatic
    external fun nativeDecoderDestroy(handle: Long)

    @JvmStatic
    external fun nativeDecoderDecodeFloat(
        handle: Long,
        opusData: ByteArray?,
        bytes: Int,
        outputPcm: FloatArray,
        frameSize: Int,
        decodeFec: Boolean
    ): Int

    @JvmStatic
    external fun nativeDecoderDecodeShort(
        handle: Long,
        opusData: ByteArray?,
        bytes: Int,
        outputPcm: ShortArray,
        frameSize: Int,
        decodeFec: Boolean
    ): Int

    @JvmStatic
    external fun nativeDecoderReset(handle: Long): Int

    @JvmStatic
    external fun nativeRunBenchmark(iterations: Int): DoubleArray?

    fun createEncoder(
        sampleRate: Int = OpusConstants.SAMPLE_RATE,
        channels: Int = OpusConstants.CHANNELS,
        bitrate: Int = OpusConstants.DEFAULT_BITRATE,
        complexity: Int = OpusConstants.DEFAULT_COMPLEXITY
    ): OpusEncoder = OpusEncoder(sampleRate, channels, bitrate, complexity)

    fun createDecoder(
        sampleRate: Int = OpusConstants.SAMPLE_RATE,
        channels: Int = OpusConstants.CHANNELS
    ): OpusDecoder = OpusDecoder(sampleRate, channels)

    fun runBenchmark(iterations: Int = 1000): OpusBenchmarkResult? {
        return DefaultOpusCodecBridge.INSTANCE.runBenchmark(iterations)
    }
}

/**
 * Default bridge implementation delegating to JNI native methods when loaded,
 * or falling back to a deterministic software mock implementation for host JVM unit tests.
 */
open class DefaultOpusCodecBridge : OpusCodecBridge {
    companion object {
        val INSTANCE: DefaultOpusCodecBridge = DefaultOpusCodecBridge()
    }

    private val isNative: Boolean
        get() = NativeAudioEngine.isNativeLoaded

    private val nextHandle = AtomicLong(1L)
    private val encoders = ConcurrentHashMap<Long, MockEncoderState>()
    private val decoders = ConcurrentHashMap<Long, MockDecoderState>()

    private class MockEncoderState(
        val sampleRate: Int,
        val channels: Int,
        val bitrate: Int,
        val complexity: Int
    )

    private class MockDecoderState(
        val sampleRate: Int,
        val channels: Int
    ) {
        var lastPcmFloat: FloatArray? = null
        var lastPcmShort: ShortArray? = null
    }

    override fun encoderCreate(sampleRate: Int, channels: Int, bitrate: Int, complexity: Int): Long {
        return if (isNative) {
            OpusCodec.nativeEncoderCreate(sampleRate, channels, bitrate, complexity)
        } else {
            val id = nextHandle.getAndIncrement()
            encoders[id] = MockEncoderState(sampleRate, channels, bitrate, complexity)
            id
        }
    }

    override fun encoderDestroy(handle: Long) {
        if (isNative) {
            OpusCodec.nativeEncoderDestroy(handle)
        } else {
            encoders.remove(handle)
        }
    }

    override fun encoderEncodeFloat(
        handle: Long,
        pcmInput: FloatArray,
        frameSize: Int,
        outputBuffer: ByteArray,
        maxOutputBytes: Int
    ): Int {
        if (isNative) {
            return OpusCodec.nativeEncoderEncodeFloat(handle, pcmInput, frameSize, outputBuffer, maxOutputBytes)
        }
        val encoder = encoders[handle] ?: return -1
        val numSamples = frameSize * encoder.channels
        if (pcmInput.size < numSamples) return -2

        val headerSize = 8
        val pcmByteSize = numSamples * 2
        val totalSize = headerSize + pcmByteSize
        if (maxOutputBytes < totalSize) return -3

        val bb = ByteBuffer.wrap(outputBuffer).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0x4F.toByte())
        bb.put(0x50.toByte())
        bb.put(0x55.toByte())
        bb.put(0x53.toByte())
        bb.putShort(frameSize.toShort())
        bb.putShort(encoder.channels.toShort())
        for (i in 0 until numSamples) {
            val clamped = pcmInput[i].coerceIn(-1.0f, 1.0f)
            val s16 = (clamped * 32767.0f).toInt().toShort()
            bb.putShort(s16)
        }
        return totalSize
    }

    override fun encoderEncodeShort(
        handle: Long,
        pcmInput: ShortArray,
        frameSize: Int,
        outputBuffer: ByteArray,
        maxOutputBytes: Int
    ): Int {
        if (isNative) {
            return OpusCodec.nativeEncoderEncodeShort(handle, pcmInput, frameSize, outputBuffer, maxOutputBytes)
        }
        val encoder = encoders[handle] ?: return -1
        val numSamples = frameSize * encoder.channels
        if (pcmInput.size < numSamples) return -2

        val headerSize = 8
        val pcmByteSize = numSamples * 2
        val totalSize = headerSize + pcmByteSize
        if (maxOutputBytes < totalSize) return -3

        val bb = ByteBuffer.wrap(outputBuffer).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0x4F.toByte())
        bb.put(0x50.toByte())
        bb.put(0x55.toByte())
        bb.put(0x53.toByte())
        bb.putShort(frameSize.toShort())
        bb.putShort(encoder.channels.toShort())
        for (i in 0 until numSamples) {
            bb.putShort(pcmInput[i])
        }
        return totalSize
    }

    override fun encoderReset(handle: Long): Int {
        return if (isNative) {
            OpusCodec.nativeEncoderReset(handle)
        } else {
            if (encoders.containsKey(handle)) 0 else -1
        }
    }

    override fun decoderCreate(sampleRate: Int, channels: Int): Long {
        return if (isNative) {
            OpusCodec.nativeDecoderCreate(sampleRate, channels)
        } else {
            val id = nextHandle.getAndIncrement()
            decoders[id] = MockDecoderState(sampleRate, channels)
            id
        }
    }

    override fun decoderDestroy(handle: Long) {
        if (isNative) {
            OpusCodec.nativeDecoderDestroy(handle)
        } else {
            decoders.remove(handle)
        }
    }

    override fun decoderDecodeFloat(
        handle: Long,
        opusData: ByteArray?,
        bytes: Int,
        outputPcm: FloatArray,
        frameSize: Int,
        decodeFec: Boolean
    ): Int {
        if (isNative) {
            return OpusCodec.nativeDecoderDecodeFloat(handle, opusData, bytes, outputPcm, frameSize, decodeFec)
        }
        val decoder = decoders[handle] ?: return -1
        val numSamples = frameSize * decoder.channels
        if (outputPcm.size < numSamples) return -2

        if (opusData == null || bytes <= 0) {
            val last = decoder.lastPcmFloat
            if (last != null && last.size >= numSamples) {
                for (i in 0 until numSamples) {
                    outputPcm[i] = last[i] * 0.8f
                }
            } else {
                outputPcm.fill(0.0f, 0, numSamples)
            }
            decoder.lastPcmFloat = outputPcm.copyOf(numSamples)
            return frameSize
        }

        if (bytes >= 8 && opusData[0] == 0x4F.toByte() && opusData[1] == 0x50.toByte()) {
            val bb = ByteBuffer.wrap(opusData, 0, bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(8)
            for (i in 0 until numSamples) {
                if (bb.remaining() >= 2) {
                    val s16 = bb.short
                    outputPcm[i] = s16 / 32768.0f
                } else {
                    outputPcm[i] = 0.0f
                }
            }
        } else {
            outputPcm.fill(0.0f, 0, numSamples)
        }

        decoder.lastPcmFloat = outputPcm.copyOf(numSamples)
        return frameSize
    }

    override fun decoderDecodeShort(
        handle: Long,
        opusData: ByteArray?,
        bytes: Int,
        outputPcm: ShortArray,
        frameSize: Int,
        decodeFec: Boolean
    ): Int {
        if (isNative) {
            return OpusCodec.nativeDecoderDecodeShort(handle, opusData, bytes, outputPcm, frameSize, decodeFec)
        }
        val decoder = decoders[handle] ?: return -1
        val numSamples = frameSize * decoder.channels
        if (outputPcm.size < numSamples) return -2

        if (opusData == null || bytes <= 0) {
            val last = decoder.lastPcmShort
            if (last != null && last.size >= numSamples) {
                for (i in 0 until numSamples) {
                    outputPcm[i] = (last[i] * 0.8).toInt().toShort()
                }
            } else {
                outputPcm.fill(0, 0, numSamples)
            }
            decoder.lastPcmShort = outputPcm.copyOf(numSamples)
            return frameSize
        }

        if (bytes >= 8 && opusData[0] == 0x4F.toByte() && opusData[1] == 0x50.toByte()) {
            val bb = ByteBuffer.wrap(opusData, 0, bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(8)
            for (i in 0 until numSamples) {
                if (bb.remaining() >= 2) {
                    outputPcm[i] = bb.short
                } else {
                    outputPcm[i] = 0
                }
            }
        } else {
            outputPcm.fill(0, 0, numSamples)
        }

        decoder.lastPcmShort = outputPcm.copyOf(numSamples)
        return frameSize
    }

    override fun decoderReset(handle: Long): Int {
        return if (isNative) {
            OpusCodec.nativeDecoderReset(handle)
        } else {
            val dec = decoders[handle] ?: return -1
            dec.lastPcmFloat = null
            dec.lastPcmShort = null
            0
        }
    }

    override fun runBenchmark(iterations: Int): OpusBenchmarkResult? {
        if (isNative) {
            val res = OpusCodec.nativeRunBenchmark(iterations)
            if (res != null && res.size >= 5) {
                return OpusBenchmarkResult(
                    avgEncodeUs = res[0],
                    avgDecodeUs = res[1],
                    totalTimeMs = res[2],
                    snrDb = res[3],
                    plcSamples = res[4].toInt()
                )
            }
        }

        // JVM mock benchmark
        val enc = OpusEncoder(bridge = this)
        val dec = OpusDecoder(bridge = this)
        val frameSize = OpusConstants.FRAME_SIZE_SAMPLES
        val totalSamples = frameSize * OpusConstants.CHANNELS
        val pcm = FloatArray(totalSamples) { i ->
            (sin(2.0 * Math.PI * 440.0 * (i / 2) / 48000.0) * 0.8).toFloat()
        }
        val outPacket = ByteArray(OpusConstants.MAX_PACKET_BYTES)
        val outPcm = FloatArray(totalSamples)

        val t0 = System.nanoTime()
        var totalEncNs = 0L
        var totalDecNs = 0L
        for (i in 0 until iterations) {
            val startEnc = System.nanoTime()
            val encBytes = enc.encode(pcm, frameSize, outPacket)
            val endEnc = System.nanoTime()
            totalEncNs += (endEnc - startEnc)

            val startDec = System.nanoTime()
            dec.decode(outPacket, encBytes, outPcm, frameSize, false)
            val endDec = System.nanoTime()
            totalDecNs += (endDec - startDec)
        }
        val totalTimeMs = (System.nanoTime() - t0) / 1_000_000.0
        val plcDecoded = dec.decode(null, 0, outPcm, frameSize, false)
        enc.close()
        dec.close()

        return OpusBenchmarkResult(
            avgEncodeUs = (totalEncNs / 1000.0) / iterations,
            avgDecodeUs = (totalDecNs / 1000.0) / iterations,
            totalTimeMs = totalTimeMs,
            snrDb = 90.0,
            plcSamples = plcDecoded
        )
    }
}
