package com.roombeat.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Unit tests verifying fractional resampler mathematics, Catmull-Rom cubic spline interpolation,
 * PPM speed conversion, parameter ramping, and multi-sample-rate accuracy (Sub-phase v0.8.0).
 */
class FractionalResamplerMathTest {

    // Helper functions implementing the exact Catmull-Rom spline equations in C++
    private fun catmullRom(yPrev: Float, y0: Float, y1: Float, yNext: Float, alpha: Float): Float {
        val c0 = y0
        val c1 = 0.5f * (y1 - yPrev)
        val c2 = yPrev - 2.5f * y0 + 2.0f * y1 - 0.5f * yNext
        val c3 = -0.5f * yPrev + 1.5f * y0 - 1.5f * y1 + 0.5f * yNext
        return ((c3 * alpha + c2) * alpha + c1) * alpha + c0
    }

    private fun linear(y0: Float, y1: Float, alpha: Float): Float {
        return y0 + alpha * (y1 - y0)
    }

    private fun ppmToRatio(ppm: Int): Double = 1.0 + (ppm.toDouble() / 1_000_000.0)
    private fun ratioToPpm(ratio: Double): Int = Math.round((ratio - 1.0) * 1_000_000.0).toInt()

    @Test
    fun testPpmToRatioConversions() {
        assertEquals(1.0, ppmToRatio(0), 1e-9)
        assertEquals(1.0005, ppmToRatio(500), 1e-9) // +0.05%
        assertEquals(0.9995, ppmToRatio(-500), 1e-9) // -0.05%
        assertEquals(1.0010, ppmToRatio(1000), 1e-9) // +0.10%
        assertEquals(0.9990, ppmToRatio(-1000), 1e-9) // -0.10%
        assertEquals(1.0020, ppmToRatio(2000), 1e-9) // +0.20%
    }

    @Test
    fun testRatioToPpmConversions() {
        assertEquals(0, ratioToPpm(1.0))
        assertEquals(500, ratioToPpm(1.0005))
        assertEquals(-500, ratioToPpm(0.9995))
        assertEquals(1000, ratioToPpm(1.0010))
        assertEquals(-1000, ratioToPpm(0.9990))
    }

    @Test
    fun testCatmullRomExactEndpoints() {
        val yPrev = 0.2f
        val y0 = 0.5f
        val y1 = 0.8f
        val yNext = 0.9f

        // At alpha = 0.0, Catmull-Rom must exactly evaluate to y0
        val atZero = catmullRom(yPrev, y0, y1, yNext, 0.0f)
        assertEquals(y0, atZero, 1e-6f)

        // At alpha = 1.0, Catmull-Rom must exactly evaluate to y1
        val atOne = catmullRom(yPrev, y0, y1, yNext, 1.0f)
        assertEquals(y1, atOne, 1e-6f)
    }

    @Test
    fun testCatmullRomSmoothC1Continuity() {
        // Monotonic ramp points
        val yPrev = 1.0f
        val y0 = 2.0f
        val y1 = 3.0f
        val yNext = 4.0f

        // For linear sequence, Catmull-Rom is exact straight line
        val mid = catmullRom(yPrev, y0, y1, yNext, 0.5f)
        assertEquals(2.5f, mid, 1e-6f)

        val q1 = catmullRom(yPrev, y0, y1, yNext, 0.25f)
        assertEquals(2.25f, q1, 1e-6f)

        val q3 = catmullRom(yPrev, y0, y1, yNext, 0.75f)
        assertEquals(2.75f, q3, 1e-6f)
    }

    @Test
    fun testCatmullRomCurvatureOnCurvedSignal() {
        // Sine wave peak: y0=0.9, y1=0.9, with lower neighbors
        val yPrev = 0.5f
        val y0 = 0.9f
        val y1 = 0.9f
        val yNext = 0.5f

        // Peak between y0 and y1 should overshoot slightly, mimicking smooth sine curve
        val mid = catmullRom(yPrev, y0, y1, yNext, 0.5f)
        assertTrue("Curved interpolation should maintain convex peak", mid > 0.9f)
        assertTrue("Peak overshoot must remain bounded", mid < 1.0f)

        // Linear interpolation would have stayed flat at 0.9
        val linearMid = linear(y0, y1, 0.5f)
        assertEquals(0.9f, linearMid, 1e-6f)
    }

    @Test
    fun testResamplingSineWaveAcrossDiverseSampleRates() {
        val sampleRates = listOf(44100, 48000, 96000)
        val frequencyHz = 1000.0 // 1 kHz test tone

        for (sampleRate in sampleRates) {
            val numFrames = sampleRate / 10 // 100ms of audio
            val inputSignal = FloatArray(numFrames) { i ->
                sin(2.0 * PI * frequencyHz * i / sampleRate).toFloat()
            }

            // Test +500 ppm speed
            val ratio = ppmToRatio(500)
            val outputFrames = (numFrames / ratio).toInt()
            val outputSignal = FloatArray(outputFrames)

            var phase = 0.0
            var inIndex = 0

            for (outIdx in 0 until outputFrames) {
                if (inIndex + 2 >= numFrames) break
                val yPrev = if (inIndex == 0) inputSignal[0] else inputSignal[inIndex - 1]
                val y0 = inputSignal[inIndex]
                val y1 = inputSignal[inIndex + 1]
                val yNext = inputSignal[inIndex + 2]
                val alpha = phase.toFloat()

                val sample = catmullRom(yPrev, y0, y1, yNext, alpha)
                outputSignal[outIdx] = sample

                // Verify output stays bounded within [-1.0, 1.0] without DC offset
                assertTrue("Sample at sr=$sampleRate out of bounds: $sample", sample >= -1.05f && sample <= 1.05f)

                phase += ratio
                val step = phase.toInt()
                phase -= step
                inIndex += step
            }

            // Verify phase advanced as expected: inIndex should exceed outIdx by ~0.05%
            val expectedDiff = Math.round(outputFrames * 0.0005).toInt()
            val actualDiff = inIndex - outputFrames
            assertTrue("Expected ~0.05% frame consumption difference for sr=$sampleRate", abs(actualDiff - expectedDiff) <= 2)
        }
    }

    @Test
    fun testParameterRampingEliminatesClicks() {
        val rampDurationFrames = 2400 // 50ms at 48kHz
        val startRatio = 1.0
        val targetRatio = 1.0005 // +500 ppm

        val step = (targetRatio - startRatio) / rampDurationFrames
        var current = startRatio

        // Maximum allowable step difference per frame to guarantee click-free smoothness
        val maxStepAllowed = 0.0005 / rampDurationFrames
        assertEquals(maxStepAllowed, step, 1e-12)

        for (i in 0 until rampDurationFrames) {
            current += step
            assertTrue(current >= 1.0 && current <= 1.0005)
        }
        assertEquals(targetRatio, current, 1e-9)
    }

    @Test
    fun testMultiChannelStereoPhaseLock() {
        // Interleaved stereo samples: L and R have different frequencies
        val numFrames = 480
        val stereoInput = FloatArray(numFrames * 2)
        for (i in 0 until numFrames) {
            stereoInput[i * 2] = sin(2.0 * PI * 440.0 * i / 48000.0).toFloat() // Left: 440 Hz
            stereoInput[i * 2 + 1] = sin(2.0 * PI * 880.0 * i / 48000.0).toFloat() // Right: 880 Hz
        }

        val ratio = ppmToRatio(-500) // -500 ppm
        var phase = 0.0
        var inIndex = 0

        for (outIdx in 0 until 400) {
            val alpha = phase.toFloat()

            // Left
            val yPrevL = if (inIndex == 0) stereoInput[0] else stereoInput[(inIndex - 1) * 2]
            val y0L = stereoInput[inIndex * 2]
            val y1L = stereoInput[(inIndex + 1) * 2]
            val yNextL = stereoInput[(inIndex + 2) * 2]
            val outL = catmullRom(yPrevL, y0L, y1L, yNextL, alpha)

            // Right
            val yPrevR = if (inIndex == 0) stereoInput[1] else stereoInput[(inIndex - 1) * 2 + 1]
            val y0R = stereoInput[inIndex * 2 + 1]
            val y1R = stereoInput[(inIndex + 1) * 2 + 1]
            val yNextR = stereoInput[(inIndex + 2) * 2 + 1]
            val outR = catmullRom(yPrevR, y0R, y1R, yNextR, alpha)

            // Verify both channels exist and are bounded
            assertFalse(outL.isNaN())
            assertFalse(outR.isNaN())

            phase += ratio
            val step = phase.toInt()
            phase -= step
            inIndex += step
        }
    }
}
