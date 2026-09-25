package com.roombeat.app.audio.buffer

import com.roombeat.app.audio.NativeAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests verifying fractional resampler integration and micro-speed modulation (speedPpm)
 * within AudioJitterBuffer (Sub-phase v0.8.0).
 */
class AudioJitterBufferResamplerTest {

    private fun createSyntheticFrame(seq: Long, value: Float = 0.5f): FloatArray {
        val pcm = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        for (i in 0 until JitterBufferConstants.SAMPLES_PER_CHANNEL) {
            pcm[i * 2] = value
            pcm[i * 2 + 1] = value
        }
        return pcm
    }

    private fun createSineFrame(sampleOffset: Int, frequency: Double = 440.0, sampleRate: Int = 48000): FloatArray {
        val pcm = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        val n = JitterBufferConstants.SAMPLES_PER_CHANNEL
        for (i in 0 until n) {
            val t = (sampleOffset + i).toDouble() / sampleRate
            val sample = kotlin.math.sin(2.0 * Math.PI * frequency * t).toFloat() * 0.5f
            pcm[i * 2] = sample
            pcm[i * 2 + 1] = sample
        }
        return pcm
    }

    @Test
    fun testDefaultSpeedPpmIsZero() {
        val buffer = AudioJitterBuffer(targetDepthMs = 120)
        assertEquals(0, buffer.speedPpm)
        buffer.close()
    }

    @Test
    fun testSetAndGetSpeedPpm() {
        val buffer = AudioJitterBuffer(targetDepthMs = 120)

        buffer.speedPpm = 250
        assertEquals(250, buffer.speedPpm)

        buffer.speedPpm = -350
        assertEquals(-350, buffer.speedPpm)

        buffer.speedPpm = 500
        assertEquals(500, buffer.speedPpm)

        buffer.speedPpm = -500
        assertEquals(-500, buffer.speedPpm)

        buffer.close()
    }

    @Test
    fun testSpeedPpmClamping() {
        val buffer = AudioJitterBuffer(targetDepthMs = 120)

        // Beyond normal operational limit (+/-2000 ppm clamp in mock)
        buffer.speedPpm = 5000
        assertEquals(2000, buffer.speedPpm)

        buffer.speedPpm = -5000
        assertEquals(-2000, buffer.speedPpm)

        buffer.close()
    }

    @Test
    fun testResetRestoresSpeedPpmToZero() {
        val buffer = AudioJitterBuffer(targetDepthMs = 120)
        buffer.speedPpm = 450
        assertEquals(450, buffer.speedPpm)

        buffer.reset()
        assertEquals(0, buffer.speedPpm)
        buffer.close()
    }

    @Test
    fun testAudioRenderingWithNominalSpeedPpm() {
        val buffer = AudioJitterBuffer(targetDepthMs = 20)
        // Push 3 frames
        for (i in 0L until 3L) {
            buffer.pushDecodedFrame(i, 0L, createSyntheticFrame(i, 0.4f))
        }

        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        // Frame 0 is rendered (with smooth initial fade-in)
        val rendered0 = buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        assertEquals(JitterBufferConstants.SAMPLES_PER_CHANNEL, rendered0)
        for (sample in out) {
            assertFalse("Sample should not be NaN", sample.isNaN())
            assertFalse("Sample should not be Infinite", sample.isInfinite())
            assertTrue("Sample amplitude within range [0.0, 0.41]", sample in -0.001f..0.401f)
        }

        // Frame 1 is rendered (steady-state, all samples are exactly 0.4f)
        val rendered1 = buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        assertEquals(JitterBufferConstants.SAMPLES_PER_CHANNEL, rendered1)
        for (sample in out) {
            assertEquals(0.4f, sample, 0.001f)
        }

        buffer.close()
    }

    @Test
    fun testAudioRenderingWithPositiveSpeedPpm() {
        // Positive speed PPM (+500 ppm) -> resampler accelerates playback by 0.05%
        val buffer = AudioJitterBuffer(targetDepthMs = 20)
        buffer.speedPpm = 500

        // Push 10 sine frames
        for (i in 0L until 10L) {
            buffer.pushDecodedFrame(
                i,
                0L,
                createSineFrame((i * JitterBufferConstants.SAMPLES_PER_CHANNEL).toInt())
            )
        }

        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        for (f in 0 until 5) {
            val rendered = buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
            assertEquals(JitterBufferConstants.SAMPLES_PER_CHANNEL, rendered)

            for (sample in out) {
                assertFalse("Sample should not be NaN", sample.isNaN())
                assertFalse("Sample should not be Infinite", sample.isInfinite())
                assertTrue("Sample amplitude within range [-1.0, 1.0]", abs(sample) <= 1.0f)
            }
        }

        buffer.close()
    }

    @Test
    fun testAudioRenderingWithNegativeSpeedPpm() {
        // Negative speed PPM (-500 ppm) -> resampler decelerates playback by 0.05%
        val buffer = AudioJitterBuffer(targetDepthMs = 20)
        buffer.speedPpm = -500

        // Push 10 sine frames
        for (i in 0L until 10L) {
            buffer.pushDecodedFrame(
                i,
                0L,
                createSineFrame((i * JitterBufferConstants.SAMPLES_PER_CHANNEL).toInt())
            )
        }

        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        for (f in 0 until 5) {
            val rendered = buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
            assertEquals(JitterBufferConstants.SAMPLES_PER_CHANNEL, rendered)

            for (sample in out) {
                assertFalse("Sample should not be NaN", sample.isNaN())
                assertFalse("Sample should not be Infinite", sample.isInfinite())
                assertTrue("Sample amplitude within range [-1.0, 1.0]", abs(sample) <= 1.0f)
            }
        }

        buffer.close()
    }

    @Test
    fun testSubFramePullWithFractionalResampling() {
        // Test burst sub-frame pulls (e.g. 192 samples = 4ms at 48kHz)
        val buffer = AudioJitterBuffer(targetDepthMs = 20)
        buffer.speedPpm = 200

        for (i in 0L until 5L) {
            buffer.pushDecodedFrame(
                i,
                0L,
                createSineFrame((i * JitterBufferConstants.SAMPLES_PER_CHANNEL).toInt(), 1000.0)
            )
        }

        val burstSize = 192
        val burstOut = FloatArray(burstSize * JitterBufferConstants.CHANNELS)
        var totalRendered = 0

        // Pull 10 bursts (1920 samples = 2 frames worth)
        for (b in 0 until 10) {
            val rendered = buffer.pullFrames(burstOut, burstSize)
            assertEquals(burstSize, rendered)
            totalRendered += rendered

            for (sample in burstOut) {
                assertFalse("Sample should not be NaN", sample.isNaN())
                assertFalse("Sample should not be Infinite", sample.isInfinite())
            }
        }

        assertEquals(1920, totalRendered)
        buffer.close()
    }

    @Test
    fun testNativeAudioEngineSpeedPpmIntegration() {
        val engine = NativeAudioEngine()
        val buffer = AudioJitterBuffer(targetDepthMs = 60)

        engine.attachJitterBuffer(buffer)
        assertEquals(0, engine.speedPpm)
        assertEquals(0, buffer.speedPpm)
        assertEquals(1.0, engine.playbackRate, 0.000001)

        // Set speed PPM via engine
        engine.setSpeedPpm(300)
        assertEquals(300, engine.speedPpm)
        assertEquals(300, buffer.speedPpm)
        assertEquals(1.0003, engine.playbackRate, 0.000001)

        // Set speed PPM via playback rate
        engine.setPlaybackRate(0.9997)
        assertEquals(-300, engine.speedPpm)
        assertEquals(-300, buffer.speedPpm)
        assertEquals(0.9997, engine.playbackRate, 0.000001)

        // Reset
        engine.setSpeedPpm(0)
        assertEquals(0, engine.speedPpm)
        assertEquals(0, buffer.speedPpm)

        buffer.close()
    }
}
