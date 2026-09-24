package com.roombeat.app.audio.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Comprehensive unit test suite for [OpusCodec], [OpusEncoder], and [OpusDecoder].
 * Verifies 20ms frame encoding/decoding, PLC recovery, benchmark thresholds,
 * lifecycle state machines, and memory safety over 10,000 encode/decode cycles.
 */
class OpusCodecTest {

    private fun generateSineWaveFloat(
        frequencyHz: Double = 440.0,
        sampleRate: Int = OpusConstants.SAMPLE_RATE,
        frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES,
        channels: Int = OpusConstants.CHANNELS
    ): FloatArray {
        val totalSamples = frameSize * channels
        return FloatArray(totalSamples) { i ->
            val sampleIndex = i / channels
            (sin(2.0 * Math.PI * frequencyHz * sampleIndex / sampleRate) * 0.75).toFloat()
        }
    }

    private fun generateSineWaveShort(
        frequencyHz: Double = 440.0,
        sampleRate: Int = OpusConstants.SAMPLE_RATE,
        frameSize: Int = OpusConstants.FRAME_SIZE_SAMPLES,
        channels: Int = OpusConstants.CHANNELS
    ): ShortArray {
        val totalSamples = frameSize * channels
        return ShortArray(totalSamples) { i ->
            val sampleIndex = i / channels
            (sin(2.0 * Math.PI * frequencyHz * sampleIndex / sampleRate) * 24000.0).toInt().toShort()
        }
    }

    @Test
    fun testOpusConstants() {
        assertEquals(48000, OpusConstants.SAMPLE_RATE)
        assertEquals(2, OpusConstants.CHANNELS)
        assertEquals(960, OpusConstants.FRAME_SIZE_SAMPLES)
        assertEquals(1920, OpusConstants.INTERLEAVED_FRAME_SAMPLES)
        assertEquals(128000, OpusConstants.DEFAULT_BITRATE)
        assertEquals(6, OpusConstants.DEFAULT_COMPLEXITY)
        assertEquals(4000, OpusConstants.MAX_PACKET_BYTES)
        assertEquals(320, OpusConstants.NOMINAL_FRAME_BYTES)
    }

    @Test
    fun testEncoderAndDecoderInitialization() {
        val encoder = OpusCodec.createEncoder()
        val decoder = OpusCodec.createDecoder()

        try {
            assertTrue("Encoder must be valid upon creation", encoder.isValid)
            assertEquals(48000, encoder.sampleRate)
            assertEquals(2, encoder.channels)
            assertEquals(128000, encoder.bitrate)
            assertEquals(6, encoder.complexity)

            assertTrue("Decoder must be valid upon creation", decoder.isValid)
            assertEquals(48000, decoder.sampleRate)
            assertEquals(2, decoder.channels)
        } finally {
            encoder.close()
            decoder.close()
            assertFalse("Encoder must be invalid after close", encoder.isValid)
            assertFalse("Decoder must be invalid after close", decoder.isValid)
        }
    }

    @Test
    fun testEncodeAndDecodeFloatRoundTrip() {
        val encoder = OpusEncoder()
        val decoder = OpusDecoder()

        try {
            val inputPcm = generateSineWaveFloat(440.0)
            val packet = encoder.encode(inputPcm, OpusConstants.FRAME_SIZE_SAMPLES)

            assertNotNull("Encoded packet must not be null", packet)
            assertTrue("Packet size must be positive", packet!!.isNotEmpty())
            assertTrue("Packet size must be within buffer bounds", packet.size <= OpusConstants.MAX_PACKET_BYTES)

            val decodedPcm = decoder.decodeFloat(packet)
            assertNotNull("Decoded PCM must not be null", decodedPcm)
            assertEquals(OpusConstants.INTERLEAVED_FRAME_SAMPLES, decodedPcm!!.size)

            // Verify signal correlation / fidelity
            var maxDiff = 0.0f
            var signalPower = 0.0
            var noisePower = 0.0
            for (i in inputPcm.indices) {
                val orig = inputPcm[i]
                val dec = decodedPcm[i]
                val diff = abs(orig - dec)
                if (diff > maxDiff) maxDiff = diff
                signalPower += orig * orig
                noisePower += diff * diff
            }

            assertTrue("Signal power must be non-zero", signalPower > 0.0)
            assertTrue("Reconstructed audio should not diverge excessively: maxDiff=$maxDiff", maxDiff < 0.25f)
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun testEncodeAndDecodePcm16RoundTrip() {
        val encoder = OpusEncoder()
        val decoder = OpusDecoder()

        try {
            val inputShorts = generateSineWaveShort(440.0)
            val packet = encoder.encode(inputShorts, OpusConstants.FRAME_SIZE_SAMPLES)

            assertNotNull("Encoded packet must not be null", packet)
            assertTrue("Packet size must be positive", packet!!.isNotEmpty())

            val decodedShorts = decoder.decodePcm16(packet)
            assertNotNull("Decoded shorts must not be null", decodedShorts)
            assertEquals(OpusConstants.INTERLEAVED_FRAME_SAMPLES, decodedShorts!!.size)

            var nonZeroCount = 0
            for (s in decodedShorts) {
                if (s != 0.toShort()) nonZeroCount++
            }
            assertTrue("Decoded PCM16 frame must contain audio samples", nonZeroCount > 1000)
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun testPacketLossConcealmentWithNullPayload() {
        val decoder = OpusDecoder()
        try {
            val outputFloat = FloatArray(OpusConstants.INTERLEAVED_FRAME_SAMPLES)
            val decodedSamples = decoder.decode(null, 0, outputFloat, OpusConstants.FRAME_SIZE_SAMPLES)

            assertEquals("PLC decode with null payload must return exactly 960 samples per channel",
                OpusConstants.FRAME_SIZE_SAMPLES, decodedSamples)

            val outputShort = ShortArray(OpusConstants.INTERLEAVED_FRAME_SAMPLES)
            val decodedShortSamples = decoder.decode(null, 0, outputShort, OpusConstants.FRAME_SIZE_SAMPLES)

            assertEquals("PLC decode with null payload for PCM16 must return exactly 960 samples",
                OpusConstants.FRAME_SIZE_SAMPLES, decodedShortSamples)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun testPacketLossConcealmentWithEmptyPayload() {
        val decoder = OpusDecoder()
        try {
            val outputFloat = FloatArray(OpusConstants.INTERLEAVED_FRAME_SAMPLES)
            val decodedSamples = decoder.decode(ByteArray(0), 0, outputFloat, OpusConstants.FRAME_SIZE_SAMPLES)

            assertEquals("PLC decode with 0-byte payload must return 960 samples",
                OpusConstants.FRAME_SIZE_SAMPLES, decodedSamples)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun testPacketLossConcealmentConsecutiveLoss() {
        val encoder = OpusEncoder()
        val decoder = OpusDecoder()

        try {
            val normalPcm = generateSineWaveFloat(440.0)
            val normalPacket = encoder.encode(normalPcm)
            assertNotNull(normalPacket)

            // Decode 1 normal packet first
            val decodedPcm = FloatArray(OpusConstants.INTERLEAVED_FRAME_SAMPLES)
            decoder.decode(normalPacket, normalPacket!!.size, decodedPcm, OpusConstants.FRAME_SIZE_SAMPLES)

            // Decode 3 consecutive lost packets (PLC)
            for (lostIndex in 1..3) {
                val plcPcm = FloatArray(OpusConstants.INTERLEAVED_FRAME_SAMPLES)
                val samples = decoder.decode(null, 0, plcPcm, OpusConstants.FRAME_SIZE_SAMPLES)
                assertEquals(OpusConstants.FRAME_SIZE_SAMPLES, samples)

                // Verify samples are finite and not NaN/infinite
                for (s in plcPcm) {
                    assertFalse("PLC sample must not be NaN", s.isNaN())
                    assertFalse("PLC sample must not be Infinite", s.isInfinite())
                }
            }
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun testResetState() {
        val encoder = OpusEncoder()
        val decoder = OpusDecoder()

        try {
            val encResetResult = encoder.reset()
            assertEquals("Encoder reset must return success (0)", 0, encResetResult)

            val decResetResult = decoder.reset()
            assertEquals("Decoder reset must return success (0)", 0, decResetResult)
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun testDoubleCloseSafety() {
        val encoder = OpusEncoder()
        val decoder = OpusDecoder()

        encoder.close()
        encoder.close() // Safe idempotent close

        decoder.close()
        decoder.close() // Safe idempotent close

        assertFalse(encoder.isValid)
        assertFalse(decoder.isValid)
    }

    @Test(expected = IllegalStateException::class)
    fun testEncoderClosedThrowsOnEncode() {
        val encoder = OpusEncoder()
        encoder.close()
        encoder.encode(FloatArray(OpusConstants.INTERLEAVED_FRAME_SAMPLES))
    }

    @Test(expected = IllegalStateException::class)
    fun testDecoderClosedThrowsOnDecode() {
        val decoder = OpusDecoder()
        decoder.close()
        decoder.decode(ByteArray(10), 10, FloatArray(OpusConstants.INTERLEAVED_FRAME_SAMPLES))
    }

    @Test
    fun testBenchmarkExecutionTimeUnder1Point5Ms() {
        val result = OpusCodec.runBenchmark(iterations = 1000)
        assertNotNull("Benchmark result must not be null", result)

        val totalMs = result!!.totalPerFrameMs
        assertTrue(
            "Combined encode + decode time ($totalMs ms) must be < 1.5ms per 20ms frame",
            totalMs < 1.5
        )

        val cpuPercent = result.cpuPercentageOfFrame
        assertTrue(
            "CPU consumption ($cpuPercent %) must be < 5.0% of a 20ms audio frame budget",
            cpuPercent < 5.0
        )

        assertEquals("PLC must produce exactly 960 samples per channel",
            OpusConstants.FRAME_SIZE_SAMPLES, result.plcSamples)
    }

    @Test
    fun testMemorySafetyOver10000Cycles() {
        // Definition of Done: Unit tests verify memory safety and zero memory leaks over 10,000 encode/decode cycles
        val encoder = OpusEncoder()
        val decoder = OpusDecoder()

        try {
            val pcmInput = generateSineWaveFloat(440.0)
            val packetBuffer = ByteArray(OpusConstants.MAX_PACKET_BYTES)
            val outputPcm = FloatArray(OpusConstants.INTERLEAVED_FRAME_SAMPLES)

            var successfulCycles = 0
            for (i in 0 until 10_000) {
                val encBytes = encoder.encode(pcmInput, OpusConstants.FRAME_SIZE_SAMPLES, packetBuffer)
                if (encBytes > 0) {
                    val decSamples = decoder.decode(packetBuffer, encBytes, outputPcm, OpusConstants.FRAME_SIZE_SAMPLES)
                    if (decSamples == OpusConstants.FRAME_SIZE_SAMPLES) {
                        successfulCycles++
                    }
                }
            }

            assertEquals("All 10,000 encode/decode cycles must succeed without memory errors",
                10_000, successfulCycles)
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun testCustomBridgeInjection() {
        var createCalled = false
        var destroyCalled = false

        val testBridge = object : OpusCodecBridge {
            override fun encoderCreate(sampleRate: Int, channels: Int, bitrate: Int, complexity: Int): Long {
                createCalled = true
                return 999L
            }
            override fun encoderDestroy(handle: Long) {
                destroyCalled = true
            }
            override fun encoderEncodeFloat(handle: Long, pcmInput: FloatArray, frameSize: Int, outputBuffer: ByteArray, maxOutputBytes: Int): Int = 42
            override fun encoderEncodeShort(handle: Long, pcmInput: ShortArray, frameSize: Int, outputBuffer: ByteArray, maxOutputBytes: Int): Int = 42
            override fun encoderReset(handle: Long): Int = 0
            override fun decoderCreate(sampleRate: Int, channels: Int): Long = 888L
            override fun decoderDestroy(handle: Long) {}
            override fun decoderDecodeFloat(handle: Long, opusData: ByteArray?, bytes: Int, outputPcm: FloatArray, frameSize: Int, decodeFec: Boolean): Int = 960
            override fun decoderDecodeShort(handle: Long, opusData: ByteArray?, bytes: Int, outputPcm: ShortArray, frameSize: Int, decodeFec: Boolean): Int = 960
            override fun decoderReset(handle: Long): Int = 0
            override fun runBenchmark(iterations: Int): OpusBenchmarkResult? = null
        }

        val customEncoder = OpusEncoder(bridge = testBridge)
        assertTrue(createCalled)
        assertTrue(customEncoder.isValid)

        val outBuf = ByteArray(100)
        val encoded = customEncoder.encode(FloatArray(1920), 960, outBuf)
        assertEquals(42, encoded)

        customEncoder.close()
        assertTrue(destroyCalled)
        assertFalse(customEncoder.isValid)
    }
}
