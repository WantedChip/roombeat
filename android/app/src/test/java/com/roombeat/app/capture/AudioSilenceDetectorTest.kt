package com.roombeat.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioSilenceDetectorTest {

    private fun generateSineWave(amplitude: Short, samplesCount: Int = 1920): ShortArray {
        val samples = ShortArray(samplesCount)
        val frequency = 440.0
        val sampleRate = 48000.0
        for (i in 0 until samplesCount) {
            val t = i / sampleRate
            samples[i] = (amplitude * sin(2.0 * PI * frequency * t)).toInt().toShort()
        }
        return samples
    }

    private fun generateSilence(samplesCount: Int = 1920): ShortArray {
        return ShortArray(samplesCount)
    }

    private fun generateFullScaleSquareWave(samplesCount: Int = 1920): ShortArray {
        val samples = ShortArray(samplesCount)
        for (i in 0 until samplesCount) {
            samples[i] = if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        }
        return samples
    }

    @Test
    fun testDefaultValues() {
        val detector = AudioSilenceDetector()
        assertEquals(AudioSilenceDetector.DEFAULT_THRESHOLD_DBFS, detector.thresholdDbfs, 0.001)
        assertEquals(500L, detector.hangoverDurationMs)
        assertEquals(20L, detector.frameDurationMs)
        assertEquals(25, detector.hangoverFrames)
        assertTrue(detector.isSilent)
        assertEquals(25, detector.consecutiveSilentFrames)
    }

    @Test
    fun testCalculateRmsLinear_ZeroSilence() {
        val detector = AudioSilenceDetector()
        val silence = generateSilence(1920)

        val rmsLinear = detector.calculateRmsLinear(silence)
        assertEquals(0.0, rmsLinear, 0.0001)

        val rmsDbfs = detector.calculateRmsDbfs(silence)
        assertEquals(AudioSilenceDetector.SILENCE_DBFS_FLOOR, rmsDbfs, 0.001)
    }

    @Test
    fun testCalculateRmsLinear_FullScaleSquareWave() {
        val detector = AudioSilenceDetector()
        val squareWave = generateFullScaleSquareWave(1920)

        val rmsLinear = detector.calculateRmsLinear(squareWave)
        // 32767 / 32768 ≈ 0.999969
        assertEquals(1.0, rmsLinear, 0.001)

        val rmsDbfs = detector.calculateRmsDbfs(squareWave)
        // 20 * log10(0.999969) ≈ 0.0 dBFS
        assertEquals(0.0, rmsDbfs, 0.01)
    }

    @Test
    fun testCalculateRmsLinear_SineWave() {
        val detector = AudioSilenceDetector()
        // Half-scale amplitude: 16384
        val halfScaleSine = generateSineWave(16384, 1920)

        val rmsLinear = detector.calculateRmsLinear(halfScaleSine)
        // Theoretical RMS of sine with amplitude A = A / sqrt(2)
        // 16384 / sqrt(2) ≈ 11585.24. Normalized: 11585.24 / 32768 ≈ 0.35355
        assertEquals(0.35355, rmsLinear, 0.01)

        val rmsDbfs = detector.calculateRmsDbfs(halfScaleSine)
        // 20 * log10(0.35355) ≈ -9.03 dBFS
        assertEquals(-9.03, rmsDbfs, 0.2)
    }

    @Test
    fun testCalculateRmsLinear_EmptyOrInvalidRange() {
        val detector = AudioSilenceDetector()
        assertEquals(0.0, detector.calculateRmsLinear(ShortArray(0)), 0.0001)
        assertEquals(0.0, detector.calculateRmsLinear(ShortArray(10), offset = -1, length = 5), 0.0001)
        assertEquals(0.0, detector.calculateRmsLinear(ShortArray(10), offset = 5, length = 10), 0.0001)
        assertEquals(0.0, detector.calculateRmsLinear(ShortArray(10), offset = 0, length = 0), 0.0001)
    }

    @Test
    fun testLinearToDbfsAndDbfsToLinear() {
        val detector = AudioSilenceDetector()
        assertEquals(0.0, detector.linearToDbfs(1.0), 0.001)
        assertEquals(-20.0, detector.linearToDbfs(0.1), 0.001)
        assertEquals(-40.0, detector.linearToDbfs(0.01), 0.001)
        assertEquals(-60.0, detector.linearToDbfs(0.001), 0.001)
        assertEquals(-120.0, detector.linearToDbfs(0.0), 0.001)

        assertEquals(1.0, detector.dbfsToLinear(0.0), 0.001)
        assertEquals(0.1, detector.dbfsToLinear(-20.0), 0.001)
        assertEquals(0.01, detector.dbfsToLinear(-40.0), 0.001)
        assertEquals(0.001, detector.dbfsToLinear(-60.0), 0.001)
        assertEquals(0.0, detector.dbfsToLinear(-120.0), 0.001)
    }

    @Test
    fun testColdStart_SilenceRemainsSilent() {
        val detector = AudioSilenceDetector(hangoverDurationMs = 500L, frameDurationMs = 20L)
        val silence = generateSilence(1920)

        for (i in 1..10) {
            val result = detector.processFrame(silence)
            assertTrue("Should be silent on frame $i", result.isSilent)
            assertFalse("Cold silence should not trigger state change", result.stateChanged)
            assertEquals(25 + i, result.consecutiveSilentFrames)
        }
    }

    @Test
    fun testFastAttack_SoundImmediatelyActivates() {
        val detector = AudioSilenceDetector()
        assertTrue(detector.isSilent)

        val sound = generateSineWave(amplitude = 10000)
        val result = detector.processFrame(sound)

        assertFalse("Sound should trigger active state", result.isSilent)
        assertTrue("State should have changed from silent to active", result.stateChanged)
        assertEquals(0, result.consecutiveSilentFrames)
        assertFalse(detector.isSilent)
    }

    @Test
    fun testHangoverDuration_HoldsActiveStateBeforeSilence() {
        val detector = AudioSilenceDetector(hangoverDurationMs = 100L, frameDurationMs = 20L)
        // 100ms / 20ms = 5 frames hangover
        assertEquals(5, detector.hangoverFrames)

        // 1. Activate with sound
        val sound = generateSineWave(amplitude = 10000)
        val activeResult = detector.processFrame(sound)
        assertFalse(activeResult.isSilent)

        val silence = generateSilence(1920)

        // 2. Feed 4 silent frames: should remain active during hangover
        for (i in 1..4) {
            val hangoverResult = detector.processFrame(silence)
            assertFalse("Frame $i should stay active due to hangover", hangoverResult.isSilent)
            assertFalse(hangoverResult.stateChanged)
            assertEquals(i, hangoverResult.consecutiveSilentFrames)
        }

        // 3. 5th frame hits hangover threshold: transitions to silent!
        val silentResult = detector.processFrame(silence)
        assertTrue("Frame 5 should transition to silence", silentResult.isSilent)
        assertTrue("State change should be reported on frame 5", silentResult.stateChanged)
        assertEquals(5, silentResult.consecutiveSilentFrames)

        // 4. Subsequent silent frame stays silent
        val subsequentResult = detector.processFrame(silence)
        assertTrue("Frame 6 should stay silent", subsequentResult.isSilent)
        assertFalse("State should not change again", subsequentResult.stateChanged)
        assertEquals(6, subsequentResult.consecutiveSilentFrames)
    }

    @Test
    fun testMicroPause_SoundResumesDuringHangoverDoesNotDrop() {
        val detector = AudioSilenceDetector(hangoverDurationMs = 200L, frameDurationMs = 20L)
        // 10 frames hangover
        assertEquals(10, detector.hangoverFrames)

        val sound = generateSineWave(amplitude = 8000)
        detector.processFrame(sound)
        assertFalse(detector.isSilent)

        val silence = generateSilence(1920)

        // 6 frames of pause (less than 10)
        for (i in 1..6) {
            val result = detector.processFrame(silence)
            assertFalse("Micro-pause frame $i must remain active", result.isSilent)
            assertEquals(i, result.consecutiveSilentFrames)
        }

        // Sound resumes before hangover expired
        val resumeResult = detector.processFrame(sound)
        assertFalse("Audio resumed: must be active", resumeResult.isSilent)
        assertFalse("Did not drop, so no state change", resumeResult.stateChanged)
        assertEquals(0, resumeResult.consecutiveSilentFrames)
    }

    @Test
    fun testFastAttack_AfterProlongedSilence() {
        val detector = AudioSilenceDetector(hangoverDurationMs = 100L, frameDurationMs = 20L)
        val silence = generateSilence(1920)

        // 20 frames of silence
        for (i in 1..20) {
            detector.processFrame(silence)
        }
        assertTrue(detector.isSilent)

        // New audio starts
        val sound = generateSineWave(amplitude = 5000)
        val result = detector.processFrame(sound)

        assertFalse("Sound must immediately activate", result.isSilent)
        assertTrue("State must change to active", result.stateChanged)
        assertEquals(0, result.consecutiveSilentFrames)
    }

    @Test
    fun testThresholdCalibration() {
        val detector = AudioSilenceDetector()

        // Generate low-level background noise: amplitude ~20 (normalized: 20/32768 ≈ 0.00061 ≈ -64.3 dBFS)
        val noiseFrames = listOf(
            generateSineWave(amplitude = 15),
            generateSineWave(amplitude = 20),
            generateSineWave(amplitude = 18)
        )

        val newThreshold = detector.calibrateThreshold(noiseFrames, marginDb = 6.0)
        // Max noise is approx -67 dBFS, + 6dB margin is approx -61 dBFS
        assertTrue("Calibrated threshold should be between -65 and -55 dBFS", newThreshold in -65.0..-55.0)

        // Frame slightly louder than noise floor but below margin
        val subThresholdFrame = generateSineWave(amplitude = 22)
        val subResult = detector.processFrame(subThresholdFrame)
        assertTrue("Sub-threshold frame should be silent", subResult.isSilent)

        // Frame clearly above margin: amplitude 100 (100/32768 ≈ 0.00305 ≈ -50.3 dBFS)
        val activeFrame = generateSineWave(amplitude = 100)
        val activeResult = detector.processFrame(activeFrame)
        assertFalse("Frame above threshold must be active", activeResult.isSilent)
    }

    @Test
    fun testThresholdBoundsClamping() {
        val detector = AudioSilenceDetector()
        detector.thresholdDbfs = 10.0 // Above 0 dBFS
        assertEquals(0.0, detector.thresholdDbfs, 0.001)

        detector.thresholdDbfs = -150.0 // Below silence floor
        assertEquals(AudioSilenceDetector.SILENCE_DBFS_FLOOR, detector.thresholdDbfs, 0.001)
    }

    @Test
    fun testReset() {
        val detector = AudioSilenceDetector()
        val sound = generateSineWave(amplitude = 5000)
        detector.processFrame(sound)
        assertFalse(detector.isSilent)

        // Reset to silent
        detector.reset(startSilent = true)
        assertTrue(detector.isSilent)
        assertEquals(detector.hangoverFrames, detector.consecutiveSilentFrames)

        // Reset to active
        detector.reset(startSilent = false)
        assertFalse(detector.isSilent)
        assertEquals(0, detector.consecutiveSilentFrames)
    }
}
