package com.roombeat.app.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class AudioRmsAnalyzerTest {

    private lateinit var analyzer: AudioRmsAnalyzer

    @Before
    fun setUp() {
        analyzer = AudioRmsAnalyzer()
    }

    // =========================================================================
    // 1. dBFS and Normalization Math Tests
    // =========================================================================

    @Test
    fun testLinearToDbfsConversion() {
        // 1.0 linear = 0.0 dBFS
        assertEquals(0.0f, AudioRmsAnalyzer.linearToDbfs(1.0f), 0.001f)

        // 0.5 linear = 20 * log10(0.5) ≈ -6.0206 dBFS
        assertEquals(-6.0206f, AudioRmsAnalyzer.linearToDbfs(0.5f), 0.01f)

        // 0.1 linear = 20 * log10(0.1) = -20.0 dBFS
        assertEquals(-20.0f, AudioRmsAnalyzer.linearToDbfs(0.1f), 0.01f)

        // Zero / near-zero clamped to MIN_DBFS (-60.0 dBFS)
        assertEquals(AudioRmsAnalyzer.MIN_DBFS, AudioRmsAnalyzer.linearToDbfs(0.0f), 0.001f)
        assertEquals(AudioRmsAnalyzer.MIN_DBFS, AudioRmsAnalyzer.linearToDbfs(1e-7f), 0.001f)

        // Values above 1.0 (clipping / headroom)
        val clipDbfs = AudioRmsAnalyzer.linearToDbfs(1.2589f) // ~ +2.0 dBFS
        assertTrue(clipDbfs > 0.0f)
        assertEquals(2.0f, clipDbfs, 0.05f)
    }

    @Test
    fun testDbfsToLinearConversion() {
        assertEquals(1.0f, AudioRmsAnalyzer.dbfsToLinear(0.0f), 0.001f)
        assertEquals(0.5f, AudioRmsAnalyzer.dbfsToLinear(-6.0206f), 0.01f)
        assertEquals(0.1f, AudioRmsAnalyzer.dbfsToLinear(-20.0f), 0.01f)
        assertEquals(0.0f, AudioRmsAnalyzer.dbfsToLinear(AudioRmsAnalyzer.MIN_DBFS), 0.001f)
        assertEquals(0.0f, AudioRmsAnalyzer.dbfsToLinear(-90.0f), 0.001f)
    }

    @Test
    fun testDbfsToNormalized() {
        // -60 dBFS floor -> 0.0
        assertEquals(0.0f, AudioRmsAnalyzer.dbfsToNormalized(-60.0f), 0.001f)
        assertEquals(0.0f, AudioRmsAnalyzer.dbfsToNormalized(-80.0f), 0.001f)

        // +3 dBFS ceiling -> 1.0
        assertEquals(1.0f, AudioRmsAnalyzer.dbfsToNormalized(3.0f), 0.001f)
        assertEquals(1.0f, AudioRmsAnalyzer.dbfsToNormalized(10.0f), 0.001f)

        // Midpoint: (-60 + 3) / 2 = -28.5 dBFS -> 0.5
        assertEquals(0.5f, AudioRmsAnalyzer.dbfsToNormalized(-28.5f), 0.01f)

        // 0.0 dBFS normalized: (0 - (-60)) / 63 = 60 / 63 ≈ 0.9524
        assertEquals(60f / 63f, AudioRmsAnalyzer.dbfsToNormalized(0.0f), 0.001f)

        // -6.0 dBFS normalized: (-6 - (-60)) / 63 = 54 / 63 ≈ 0.8571
        assertEquals(54f / 63f, AudioRmsAnalyzer.dbfsToNormalized(-6.0f), 0.001f)
    }

    @Test
    fun testFormatDbfs() {
        assertEquals("0.0 dB", AudioRmsAnalyzer.formatDbfs(0.0f))
        assertEquals("-3.2 dB", AudioRmsAnalyzer.formatDbfs(-3.24f))
        assertEquals("-6.0 dB", AudioRmsAnalyzer.formatDbfs(-6.0f))
        assertEquals("-INF", AudioRmsAnalyzer.formatDbfs(-60.0f))
        assertEquals("-INF", AudioRmsAnalyzer.formatDbfs(-75.0f))
    }

    // =========================================================================
    // 2. IEC 60268-10 Ballistics Physics Tests
    // =========================================================================

    @Test
    fun testInstantaneousAttackOnTransient() {
        // Initial state at t = 0
        analyzer.updateChannel(
            channelId = "test_ch",
            leftRmsLinear = 0.0f,
            rightRmsLinear = 0.0f,
            leftPeakLinear = 0.0f,
            rightPeakLinear = 0.0f,
            timestampMs = 0L
        )

        // Drum hit at t = 16ms: peak hits 1.0 (0.0 dBFS), RMS 0.707 (-3.0 dBFS)
        val levels = analyzer.updateChannel(
            channelId = "test_ch",
            leftRmsLinear = 0.707f,
            rightRmsLinear = 0.707f,
            leftPeakLinear = 1.0f,
            rightPeakLinear = 1.0f,
            timestampMs = 16L
        )

        // Verifies instantaneous attack (<= 16ms rise): values jump immediately
        assertEquals(0.0f, levels.leftPeakDbfs, 0.01f)
        assertEquals(0.0f, levels.rightPeakDbfs, 0.01f)
        assertEquals(-3.01f, levels.leftRmsDbfs, 0.05f)
        assertEquals(-3.01f, levels.rightRmsDbfs, 0.05f)
        assertEquals(0.0f, levels.leftPeakHoldDbfs, 0.01f)
        assertEquals(0.0f, levels.rightPeakHoldDbfs, 0.01f)
    }

    @Test
    fun testPeakHoldDurationExact300ms() {
        val t0 = 1000L

        // Drum transient at t0: 0 dBFS peak
        val initial = analyzer.updateChannelFromDbfs(
            channelId = "snare",
            leftRmsDbfs = -6.0f,
            rightRmsDbfs = -6.0f,
            leftPeakDbfs = 0.0f,
            rightPeakDbfs = 0.0f,
            timestampMs = t0
        )
        assertEquals(0.0f, initial.leftPeakHoldDbfs, 0.001f)

        // At t = t0 + 100ms (incoming signal drops to silence)
        val at100ms = analyzer.updateChannelFromDbfs(
            channelId = "snare",
            leftRmsDbfs = -60.0f,
            rightRmsDbfs = -60.0f,
            leftPeakDbfs = -60.0f,
            rightPeakDbfs = -60.0f,
            timestampMs = t0 + 100L
        )
        // Peak hold must remain exactly 0 dBFS during 300ms hold window!
        assertEquals(0.0f, at100ms.leftPeakHoldDbfs, 0.001f)
        assertEquals(0.0f, at100ms.rightPeakHoldDbfs, 0.001f)

        // At t = t0 + 200ms
        val at200ms = analyzer.updateChannelFromDbfs(
            channelId = "snare",
            leftRmsDbfs = -60.0f,
            rightRmsDbfs = -60.0f,
            leftPeakDbfs = -60.0f,
            rightPeakDbfs = -60.0f,
            timestampMs = t0 + 200L
        )
        assertEquals(0.0f, at200ms.leftPeakHoldDbfs, 0.001f)

        // At t = t0 + 300ms (exact expiration boundary)
        val at300ms = analyzer.updateChannelFromDbfs(
            channelId = "snare",
            leftRmsDbfs = -60.0f,
            rightRmsDbfs = -60.0f,
            leftPeakDbfs = -60.0f,
            rightPeakDbfs = -60.0f,
            timestampMs = t0 + 300L
        )
        assertEquals(0.0f, at300ms.leftPeakHoldDbfs, 0.001f)
    }

    @Test
    fun testExponentialBallisticDecayAfterPeakHold() {
        val t0 = 1000L

        // Initial 0 dBFS transient at t0
        analyzer.updateChannelFromDbfs(
            channelId = "decay_test",
            leftRmsDbfs = 0.0f,
            rightRmsDbfs = 0.0f,
            leftPeakDbfs = 0.0f,
            rightPeakDbfs = 0.0f,
            timestampMs = t0
        )

        // Hold window is [1000ms, 1300ms]. At t = 1300ms + 250ms = 1550ms:
        // Elapsed decay time = 250ms = 1 tau (AudioRmsAnalyzer.DECAY_TAU_MS).
        // Expected value: target + (initial - target) * exp(-1)
        val target = AudioRmsAnalyzer.MIN_DBFS // -60 dBFS
        val initialHeld = 0.0f
        val expectedDbfs = target + (initialHeld - target) * exp(-1.0).toFloat()
        // -60 + 60 * 0.367879 = -60 + 22.0727 = -37.927 dBFS

        val at1550ms = analyzer.updateChannelFromDbfs(
            channelId = "decay_test",
            leftRmsDbfs = -60.0f,
            rightRmsDbfs = -60.0f,
            leftPeakDbfs = -60.0f,
            rightPeakDbfs = -60.0f,
            timestampMs = t0 + 550L // 1550ms
        )

        assertEquals(expectedDbfs, at1550ms.leftPeakHoldDbfs, 0.1f)
        assertEquals(expectedDbfs, at1550ms.rightPeakHoldDbfs, 0.1f)
    }

    @Test
    fun testSubsequentHigherPeakResetsPeakHoldTimer() {
        val t0 = 1000L

        // First peak at -6 dBFS at t0 -> holds until 1300ms
        val first = analyzer.updateChannelFromDbfs(
            channelId = "multi_hit",
            leftRmsDbfs = -10.0f,
            rightRmsDbfs = -10.0f,
            leftPeakDbfs = -6.0f,
            rightPeakDbfs = -6.0f,
            timestampMs = t0
        )
        assertEquals(-6.0f, first.leftPeakHoldDbfs, 0.01f)

        // Second louder peak (0.0 dBFS) at t0 + 150ms -> snaps and extends hold to 1450ms
        val second = analyzer.updateChannelFromDbfs(
            channelId = "multi_hit",
            leftRmsDbfs = -3.0f,
            rightRmsDbfs = -3.0f,
            leftPeakDbfs = 0.0f,
            rightPeakDbfs = 0.0f,
            timestampMs = t0 + 150L
        )
        assertEquals(0.0f, second.leftPeakHoldDbfs, 0.01f)

        // At t0 + 350ms (first peak would have expired, but second peak is held until t0 + 450ms)
        val third = analyzer.updateChannelFromDbfs(
            channelId = "multi_hit",
            leftRmsDbfs = -60.0f,
            rightRmsDbfs = -60.0f,
            leftPeakDbfs = -60.0f,
            rightPeakDbfs = -60.0f,
            timestampMs = t0 + 350L
        )
        assertEquals(0.0f, third.leftPeakHoldDbfs, 0.01f)
    }

    @Test
    fun testSubsequentLowerPeakDoesNotLowerHeldPeak() {
        val t0 = 1000L

        // Loud hit: 0 dBFS at t0
        analyzer.updateChannelFromDbfs(
            channelId = "snare2",
            leftRmsDbfs = 0.0f,
            rightRmsDbfs = 0.0f,
            leftPeakDbfs = 0.0f,
            rightPeakDbfs = 0.0f,
            timestampMs = t0
        )

        // Softer hit: -12 dBFS at t0 + 100ms
        val second = analyzer.updateChannelFromDbfs(
            channelId = "snare2",
            leftRmsDbfs = -18.0f,
            rightRmsDbfs = -18.0f,
            leftPeakDbfs = -12.0f,
            rightPeakDbfs = -12.0f,
            timestampMs = t0 + 100L
        )

        // Held peak must remain at 0.0 dBFS
        assertEquals(0.0f, second.leftPeakHoldDbfs, 0.001f)
    }

    @Test
    fun testRmsBallisticDecayRate() {
        val t0 = 1000L
        // Full level 0 dBFS at t0
        analyzer.updateChannelFromDbfs(
            channelId = "rms_test",
            leftRmsDbfs = 0.0f,
            rightRmsDbfs = 0.0f,
            leftPeakDbfs = 0.0f,
            rightPeakDbfs = 0.0f,
            timestampMs = t0
        )

        // 250ms later (1 tau of exponential decay), signal falls to silence (-60 dBFS)
        val target = AudioRmsAnalyzer.MIN_DBFS
        val expected = target + (0.0f - target) * exp(-1.0).toFloat()

        val decayed = analyzer.updateChannelFromDbfs(
            channelId = "rms_test",
            leftRmsDbfs = -60.0f,
            rightRmsDbfs = -60.0f,
            leftPeakDbfs = -60.0f,
            rightPeakDbfs = -60.0f,
            timestampMs = t0 + 250L
        )

        assertEquals(expected, decayed.leftRmsDbfs, 0.1f)
    }

    // =========================================================================
    // 3. Multi-Channel Telemetry Stream Tests
    // =========================================================================

    @Test
    fun testMultiChannelManagementAndStateFlow() {
        // Ingest Host channel
        analyzer.updateChannel(
            channelId = "host",
            name = "Host (Pixel 8)",
            leftRmsLinear = 0.5f,
            rightRmsLinear = 0.5f,
            leftPeakLinear = 0.8f,
            rightPeakLinear = 0.8f,
            timestampMs = 100L
        )

        // Ingest Peer 1
        analyzer.updateChannel(
            channelId = "peer_1",
            name = "Galaxy S24",
            leftRmsLinear = 0.25f,
            rightRmsLinear = 0.25f,
            leftPeakLinear = 0.5f,
            rightPeakLinear = 0.5f,
            timestampMs = 100L
        )

        // Ingest Peer 2
        analyzer.updateChannel(
            channelId = "peer_2",
            name = "OnePlus 12",
            leftRmsLinear = 0.1f,
            rightRmsLinear = 0.1f,
            leftPeakLinear = 0.2f,
            rightPeakLinear = 0.2f,
            timestampMs = 100L
        )

        val all = analyzer.getAllChannels()
        assertEquals(3, all.size)

        val host = analyzer.getChannel("host")
        assertNotNull(host)
        assertEquals("Host (Pixel 8)", host!!.name)
        assertEquals(-6.02f, host.leftRmsDbfs, 0.05f)

        val peer1 = analyzer.getChannel("peer_1")
        assertNotNull(peer1)
        assertEquals("Galaxy S24", peer1!!.name)

        // StateFlow emission check
        val flowMap = analyzer.channelsFlow.value
        assertEquals(3, flowMap.size)
        assertTrue(flowMap.containsKey("host"))
        assertTrue(flowMap.containsKey("peer_1"))
        assertTrue(flowMap.containsKey("peer_2"))

        // Remove Peer 1
        assertTrue(analyzer.removeChannel("peer_1"))
        assertEquals(2, analyzer.getAllChannels().size)
        assertNull(analyzer.getChannel("peer_1"))
        assertEquals(2, analyzer.channelsFlow.value.size)

        // Clear all
        analyzer.clearAll()
        assertEquals(0, analyzer.getAllChannels().size)
        assertTrue(analyzer.channelsFlow.value.isEmpty())
    }

    // =========================================================================
    // 4. Raw PCM & Float Sample Calculation Tests
    // =========================================================================

    @Test
    fun testCalculatePcm16LevelsSilence() {
        val silence = ShortArray(1920) // 20ms stereo at 48kHz
        val levels = AudioRmsAnalyzer.calculatePcm16Levels(silence, channels = 2)
        assertEquals(0.0f, levels.leftRms, 0.0001f)
        assertEquals(0.0f, levels.rightRms, 0.0001f)
        assertEquals(0.0f, levels.leftPeak, 0.0001f)
        assertEquals(0.0f, levels.rightPeak, 0.0001f)
    }

    @Test
    fun testCalculatePcm16LevelsFullScaleSquareWave() {
        // Full scale alternating +32767 and -32768
        val samples = ShortArray(960 * 2)
        for (i in 0 until 960) {
            val s = if (i % 2 == 0) 32767.toShort() else (-32767).toShort()
            samples[i * 2] = s
            samples[i * 2 + 1] = s
        }
        val levels = AudioRmsAnalyzer.calculatePcm16Levels(samples, channels = 2)
        assertEquals(1.0f, levels.leftPeak, 0.01f)
        assertEquals(1.0f, levels.rightPeak, 0.01f)
        assertEquals(1.0f, levels.leftRms, 0.01f)
        assertEquals(1.0f, levels.rightRms, 0.01f)
    }

    @Test
    fun testCalculateFloatLevels() {
        // 960 stereo frames with L=0.5, R=1.0
        val floatSamples = FloatArray(960 * 2)
        for (i in 0 until 960) {
            floatSamples[i * 2] = 0.5f
            floatSamples[i * 2 + 1] = 1.0f
        }
        val levels = AudioRmsAnalyzer.calculateFloatLevels(floatSamples, channels = 2)
        assertEquals(0.5f, levels.leftRms, 0.001f)
        assertEquals(1.0f, levels.rightRms, 0.001f)
        assertEquals(0.5f, levels.leftPeak, 0.001f)
        assertEquals(1.0f, levels.rightPeak, 0.001f)
    }

    @Test
    fun testClippingDetection() {
        // 1.5 linear amplitude = clipping
        val floatSamples = FloatArray(960 * 2) { 1.5f }
        val ch = analyzer.processFloatFrame("clip_ch", "Clipped", floatSamples, channels = 2, timestampMs = 100L)
        assertTrue(ch.isClipping)
        assertTrue(ch.maxPeakDbfs > 0.0f)
    }

    // =========================================================================
    // 5. NativeAudioEngine Mock Integration Test
    // =========================================================================

    @Test
    fun testUpdateLocalFromEngine() {
        val engine = NativeAudioEngine()
        engine.mockAudioLevels = floatArrayOf(0.45f, 0.45f, 0.90f, 0.90f)

        val ch = analyzer.updateLocalFromEngine(
            engine = engine,
            channelId = AudioRmsAnalyzer.CHANNEL_LOCAL,
            name = "Host Device",
            timestampMs = 500L
        )

        assertNotNull(ch)
        assertEquals("Host Device", ch!!.name)
        assertEquals(AudioRmsAnalyzer.linearToDbfs(0.45f), ch.leftRmsDbfs, 0.01f)
        assertEquals(AudioRmsAnalyzer.linearToDbfs(0.90f), ch.leftPeakDbfs, 0.01f)
    }

    // =========================================================================
    // 6. UI Model & Reduced Motion Helper Tests
    // =========================================================================

    @Test
    fun testChannelLevelsModelHelpers() {
        val model = ChannelLevels(
            channelId = "ch1",
            name = "Pixel 8",
            leftRmsDbfs = -3.2f,
            rightRmsDbfs = -4.0f,
            leftPeakHoldDbfs = 0.5f,
            rightPeakHoldDbfs = -1.0f,
            isClipping = true
        )

        assertEquals(-3.2f, model.maxRmsDbfs, 0.01f)
        assertEquals(0.5f, model.maxPeakHoldDbfs, 0.01f)
        assertEquals("-3.2 dB", model.formattedDbfs)
        assertTrue(model.isClipping)

        // Check normalized values
        val normRms = model.normalizedRms
        val expectedNormRms = (-3.2f - (-60.0f)) / 63.0f
        assertEquals(expectedNormRms, normRms, 0.001f)
    }
}
