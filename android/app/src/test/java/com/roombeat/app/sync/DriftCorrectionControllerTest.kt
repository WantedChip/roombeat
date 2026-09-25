package com.roombeat.app.sync

import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.protocol.PacketContext
import com.roombeat.app.protocol.PacketDispatcher
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.session.PeerSessionTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Comprehensive test suite for [DriftCorrectionController] (Sub-phase v0.8.0).
 *
 * Validates:
 * 1. Host-side PI closed-loop drift evaluation (deadband, P-term, I-term, clamping, rate limiting).
 * 2. Peer-side packet handling, local device ID filtering, wildcard acceptance, and hard-limit clamping.
 * 3. Reactive StateFlow telemetry updates for individual peers and session-wide sync state.
 * 4. Integration with [PacketDispatcher] and [PeerSessionTransport].
 * 5. Spotify playback state report and NTP telemetry ingestion.
 * 6. Definition of Done: Continuous 30-minute simulated playback test maintaining phase alignment < 1.0ms
 *    under realistic crystal oscillator clock skews (+/-75 ppm).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriftCorrectionControllerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class MockTransport : PeerSessionTransport {
        val sentPackets = mutableListOf<Pair<String, RoomBeatPacket>>()
        val broadcastPackets = mutableListOf<RoomBeatPacket>()

        override fun sendToPeer(peerId: String, packet: RoomBeatPacket): Boolean {
            sentPackets.add(peerId to packet)
            return true
        }

        override fun broadcastToAll(packet: RoomBeatPacket): Int {
            broadcastPackets.add(packet)
            return 1
        }

        override fun disconnectPeer(peerId: String, reason: String?) {}
    }

    // ========================================================================
    // 1. Host Drift Evaluation & Control Loop
    // ========================================================================

    @Test
    fun testEvaluationInsideDeadbandDoesNotTriggerAdjustment() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val host = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-01",
            clock = fakeClock,
            coroutineDispatcher = testDispatcher
        )

        // Drift within deadband (0.02ms <= 0.05ms)
        val result = host.evaluateDrift("peer-01", 0.02, timestampMs = 1000L)

        assertEquals("peer-01", result.deviceId)
        assertEquals(0.02, result.driftMs, 0.0001)
        assertEquals(0, result.speedPpmAdjust)
        assertFalse("Should not issue correction packet inside deadband", result.correctionIssued)
        assertTrue("Should be marked in-sync", result.isInSync)
        assertNull(result.packet)

        host.close()
    }

    @Test
    fun testEvaluationPositiveDriftCalculatesNegativeSpeedPpm() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val host = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-01",
            clock = fakeClock,
            coroutineDispatcher = testDispatcher
        )

        // Client is 0.5ms ahead of host -> needs negative PPM to slow down
        // P-term = -500 * 0.5 = -250 ppm
        val result = host.evaluateDrift("peer-01", 0.5, timestampMs = 1000L)

        assertEquals("peer-01", result.deviceId)
        assertEquals(0.5, result.driftMs, 0.0001)
        assertTrue("Speed PPM must be negative to slow down leading peer", result.speedPpmAdjust < 0)
        assertTrue("Correction packet must be issued", result.correctionIssued)
        assertTrue("Drift is 0.5ms (< 1.0ms threshold), so peer is in-sync", result.isInSync)
        assertNotNull(result.packet)
        assertEquals("peer-01", result.packet?.deviceId)
        assertEquals(result.speedPpmAdjust, result.packet?.speedPpmAdjust)

        host.close()
    }

    @Test
    fun testEvaluationNegativeDriftCalculatesPositiveSpeedPpm() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val host = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-01",
            clock = fakeClock,
            coroutineDispatcher = testDispatcher
        )

        // Client is 0.6ms behind host -> needs positive PPM to catch up
        // P-term = -500 * (-0.6) = +300 ppm
        val result = host.evaluateDrift("peer-02", -0.6, timestampMs = 1000L)

        assertEquals("peer-02", result.deviceId)
        assertEquals(-0.6, result.driftMs, 0.0001)
        assertTrue("Speed PPM must be positive to accelerate lagging peer", result.speedPpmAdjust > 0)
        assertTrue("Correction packet must be issued", result.correctionIssued)
        assertTrue("Drift is -0.6ms (< 1.0ms threshold), so peer is in-sync", result.isInSync)
        assertNotNull(result.packet)
        assertEquals("peer-02", result.packet?.deviceId)
        assertEquals(result.speedPpmAdjust, result.packet?.speedPpmAdjust)

        host.close()
    }

    @Test
    fun testEvaluationClampsToConfiguredMaxSpeedPpm() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val host = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-01",
            clock = fakeClock,
            maxSpeedPpm = 500,
            coroutineDispatcher = testDispatcher
        )

        // Severe positive drift (+3.0ms): P-term = -1500 ppm, clamped to -500 ppm
        val resultFast = host.evaluateDrift("peer-01", 3.0, timestampMs = 1000L)
        assertEquals(-500, resultFast.speedPpmAdjust)
        assertFalse("3.0ms drift is out of sync (> 1.0ms)", resultFast.isInSync)

        // Severe negative drift (-3.0ms): clamped to +500 ppm
        val resultSlow = host.evaluateDrift("peer-02", -3.0, timestampMs = 1000L)
        assertEquals(500, resultSlow.speedPpmAdjust)
        assertFalse("-3.0ms drift is out of sync", resultSlow.isInSync)

        host.close()
    }

    @Test
    fun testEvaluationRateLimiting() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val host = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-01",
            clock = fakeClock,
            minCorrectionIntervalMs = 500L,
            coroutineDispatcher = testDispatcher
        )

        // Initial evaluation at t = 1000ms: packet issued
        val r1 = host.evaluateDrift("peer-01", 0.4, timestampMs = 1000L)
        assertTrue("Initial evaluation should issue packet", r1.correctionIssued)
        assertNotNull(r1.packet)

        // Second evaluation at t = 1200ms (only 200ms elapsed, < 500ms min interval) with minor change
        val r2 = host.evaluateDrift("peer-01", 0.42, timestampMs = 1200L)
        assertFalse("Second evaluation within min interval should be rate-limited", r2.correctionIssued)
        assertNull(r2.packet)

        // Third evaluation at t = 1600ms (600ms elapsed since last packet, >= 500ms): packet issued
        val r3 = host.evaluateDrift("peer-01", 0.42, timestampMs = 1600L)
        assertTrue("Evaluation after min interval should issue packet", r3.correctionIssued)
        assertNotNull(r3.packet)

        host.close()
    }

    @Test
    fun testEvaluationIntegralAntiWindup() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val host = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-01",
            clock = fakeClock,
            kp = 200.0,
            ki = 50.0,
            coroutineDispatcher = testDispatcher
        )

        var time = 1000L
        for (i in 0 until 50) {
            host.evaluateDrift("peer-01", 1.5, timestampMs = time)
            time += 1000L
        }

        val telemetry = host.peerDriftState.value["peer-01"]
        assertNotNull(telemetry)
        // Controller output must be bounded and not NaN / Infinity
        assertTrue("Applied speed PPM must be within valid range", abs(telemetry!!.appliedSpeedPpm) <= 500)

        host.close()
    }

    // ========================================================================
    // 2. Peer Client Packet Handling & Resampler Integration
    // ========================================================================

    @Test
    fun testPeerAppliesMatchingDriftCorrectionPacket() {
        val engine = NativeAudioEngine()
        val buffer = AudioJitterBuffer(targetDepthMs = 60)
        engine.attachJitterBuffer(buffer)

        var lastAppliedPpm = 0
        val peer = DriftCorrectionController(
            isHost = false,
            localDeviceId = "peer-alpha",
            audioEngine = engine,
            jitterBuffer = buffer,
            coroutineDispatcher = testDispatcher
        ).apply {
            onSpeedPpmApplied = { lastAppliedPpm = it }
        }

        val packet = RoomBeatPacket.SessionDriftCorrect(
            deviceId = "peer-alpha",
            speedPpmAdjust = -320
        )

        val handled = peer.handleDriftCorrection(packet)
        assertTrue("Matching deviceId must be handled", handled)
        assertEquals(-320, lastAppliedPpm)
        assertEquals(-320, peer.localSpeedPpm.value)
        assertEquals(-320, engine.speedPpm)
        assertEquals(-320, buffer.speedPpm)

        peer.close()
        buffer.close()
    }

    @Test
    fun testPeerFiltersPacketForDifferentDevice() {
        val engine = NativeAudioEngine()
        val buffer = AudioJitterBuffer(targetDepthMs = 60)
        engine.attachJitterBuffer(buffer)

        val peer = DriftCorrectionController(
            isHost = false,
            localDeviceId = "peer-alpha",
            audioEngine = engine,
            jitterBuffer = buffer,
            coroutineDispatcher = testDispatcher
        )

        val packet = RoomBeatPacket.SessionDriftCorrect(
            deviceId = "peer-beta",
            speedPpmAdjust = 400
        )

        val handled = peer.handleDriftCorrection(packet)
        assertFalse("Packet for different device must be ignored", handled)
        assertEquals(0, peer.localSpeedPpm.value)
        assertEquals(0, engine.speedPpm)
        assertEquals(0, buffer.speedPpm)

        peer.close()
        buffer.close()
    }

    @Test
    fun testPeerAcceptsWildcardAllPacket() {
        val engine = NativeAudioEngine()
        val peer = DriftCorrectionController(
            isHost = false,
            localDeviceId = "peer-gamma",
            audioEngine = engine,
            coroutineDispatcher = testDispatcher
        )

        val packet = RoomBeatPacket.SessionDriftCorrect(
            deviceId = "all",
            speedPpmAdjust = 150
        )

        val handled = peer.handleDriftCorrection(packet)
        assertTrue("Wildcard packet must be accepted", handled)
        assertEquals(150, peer.localSpeedPpm.value)
        assertEquals(150, engine.speedPpm)

        peer.close()
    }

    @Test
    fun testPeerClampsToHardLimitMaxSpeedPpm() {
        val engine = NativeAudioEngine()
        val peer = DriftCorrectionController(
            isHost = false,
            localDeviceId = "peer-delta",
            audioEngine = engine,
            coroutineDispatcher = testDispatcher
        )

        // Exceeds HARD_LIMIT_MAX_SPEED_PPM (1000)
        val packet = RoomBeatPacket.SessionDriftCorrect(
            deviceId = "peer-delta",
            speedPpmAdjust = 2500
        )

        peer.handleDriftCorrection(packet)
        assertEquals(1000, peer.localSpeedPpm.value)
        assertEquals(1000, engine.speedPpm)

        peer.close()
    }

    // ========================================================================
    // 3. Telemetry Ingestion (NTP & Spotify)
    // ========================================================================

    @Test
    fun testSpotifyStateReportEvaluation() {
        val host = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-01",
            coroutineDispatcher = testDispatcher
        )

        val report = RoomBeatPacket.SpotifyStateReport(
            deviceId = "client-spotify",
            reportedPositionMs = 50200L,
            sampledAt = 2000L
        )

        // Expected position on master host is 50000ms -> client is 200ms ahead
        val result = host.onSpotifyStateReport(report, expectedMasterPositionMs = 50000L, timestampMs = 2000L)
        assertEquals(200.0, result.driftMs, 0.001)
        assertEquals("client-spotify", result.deviceId)
        assertEquals(-500, result.speedPpmAdjust) // Clamped to max negative PPM
        assertFalse(result.isInSync)

        host.close()
    }

    @Test
    fun testPeerNtpTelemetryEvaluation() {
        val host = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-01",
            coroutineDispatcher = testDispatcher
        )

        // Peer offset = 0.35ms (peer clock ahead by 350µs)
        val result = host.onPeerTelemetry("peer-ntp", offsetMs = 0.35, rttMs = 12.0, timestampMs = 3000L)
        assertEquals(0.35, result.driftMs, 0.001)
        assertTrue("PPM should be negative", result.speedPpmAdjust < 0)
        assertTrue("350µs is in sync (< 1.0ms)", result.isInSync)

        val telemetry = host.peerDriftState.value["peer-ntp"]
        assertNotNull(telemetry)
        assertEquals(0.35, telemetry!!.currentDriftMs, 0.001)

        host.close()
    }

    @Test
    fun testPacketDispatcherIntegration() = runTest(testDispatcher) {
        val dispatcher = PacketDispatcher(defaultDispatcher = testDispatcher)
        val engine = NativeAudioEngine()
        val peer = DriftCorrectionController(
            isHost = false,
            localDeviceId = "peer-dispatcher",
            audioEngine = engine,
            coroutineDispatcher = testDispatcher
        )

        val registration = dispatcher.registerDriftCorrectHandler(peer)

        // Dispatch packet through dispatcher
        val packet = RoomBeatPacket.SessionDriftCorrect(
            deviceId = "peer-dispatcher",
            speedPpmAdjust = -200
        )
        dispatcher.dispatch(packet, PacketContext(senderId = "host-01"))

        assertEquals(-200, peer.localSpeedPpm.value)
        assertEquals(-200, engine.speedPpm)

        registration.unregister()
        peer.close()
    }

    // ========================================================================
    // 4. Definition of Done: Continuous 30-Minute Playback Drift Simulation
    // ========================================================================

    /**
     * Definition of Done Validation:
     * "Continuous 30-minute playback test maintains acoustic phase alignment within <1.0ms"
     *
     * Simulates 1,800 seconds (30 minutes) of continuous multi-device playback.
     * Models realistic crystal oscillator hardware skew:
     *   - Peer A: Fast clock oscillator (+75.0 ppm skew)
     *   - Peer B: Slow clock oscillator (-60.0 ppm skew)
     *
     * Without resampler drift correction, uncorrected drift would accumulate:
     *   - Peer A: +75 ppm * 1800s = +135.0 ms drift!
     *   - Peer B: -60 ppm * 1800s = -108.0 ms drift!
     * Both would suffer severe echo and loss of acoustic sync.
     *
     * With DriftCorrectionController + FractionalResampler:
     *   - Drift is continuously measured every second.
     *   - Controller applies micro-speed modulation (speedPpm) to peer resamplers.
     *   - We verify that after initial 5-second convergence, the drift for BOTH peers
     *     remains strictly < 1.0 ms throughout the entire 30-minute duration.
     */
    @Test
    fun testContinuous30MinutePlaybackMaintainsPhaseAlignmentUnderClockSkew() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val transport = MockTransport()

        val hostController = DriftCorrectionController(
            isHost = true,
            localDeviceId = "host-master",
            clock = fakeClock,
            transport = transport,
            coroutineDispatcher = testDispatcher,
            targetAlignmentThresholdMs = 1.0,
            deadbandMs = 0.05,
            maxSpeedPpm = 500,
            minCorrectionIntervalMs = 500L,
            kp = 400.0,
            ki = 30.0
        )

        // Peer A: Hardware crystal runs fast by +75 ppm
        val peerAEngine = NativeAudioEngine()
        val peerABuffer = AudioJitterBuffer(targetDepthMs = 60)
        peerAEngine.attachJitterBuffer(peerABuffer)
        val peerA = DriftCorrectionController(
            isHost = false,
            localDeviceId = "peer-A",
            audioEngine = peerAEngine,
            jitterBuffer = peerABuffer,
            coroutineDispatcher = testDispatcher
        )
        val crystalSkewPpmA = +75.0 // +75 ppm

        // Peer B: Hardware crystal runs slow by -60 ppm
        val peerBEngine = NativeAudioEngine()
        val peerBBuffer = AudioJitterBuffer(targetDepthMs = 60)
        peerBEngine.attachJitterBuffer(peerBBuffer)
        val peerB = DriftCorrectionController(
            isHost = false,
            localDeviceId = "peer-B",
            audioEngine = peerBEngine,
            jitterBuffer = peerBBuffer,
            coroutineDispatcher = testDispatcher
        )
        val crystalSkewPpmB = -60.0 // -60 ppm

        // Connect packet emissions from host directly to peers
        hostController.onDriftCorrectPacketEmitted = { packet ->
            peerA.handleDriftCorrection(packet)
            peerB.handleDriftCorrection(packet)
        }

        // State variables for continuous 30-minute simulation
        var driftMsA = 0.0
        var driftMsB = 0.0

        val totalDurationSeconds = 1800 // 30 minutes = 1,800 seconds
        val stepIntervalSec = 1.0       // 1 second evaluation intervals

        var maxObservedDriftA = 0.0
        var maxObservedDriftB = 0.0
        var convergedDriftAExceeded1msCount = 0
        var convergedDriftBExceeded1msCount = 0

        for (second in 1..totalDurationSeconds) {
            val timestampMs = second * 1000L
            fakeClock.advanceMillis(1000L)

            // 1. Advance physical time step with current active resampler speeds
            // Effective drift rate = (crystalSkew + resamplerSpeedPpm) in ppm
            // Each second adds: effectiveRatePpm * 10^-6 * 1000 ms to drift
            val currentResamplerPpmA = peerA.localSpeedPpm.value.toDouble()
            val netSkewPpmA = crystalSkewPpmA + currentResamplerPpmA
            val deltaDriftMsA = netSkewPpmA * 1e-6 * stepIntervalSec * 1000.0
            driftMsA += deltaDriftMsA

            val currentResamplerPpmB = peerB.localSpeedPpm.value.toDouble()
            val netSkewPpmB = crystalSkewPpmB + currentResamplerPpmB
            val deltaDriftMsB = netSkewPpmB * 1e-6 * stepIntervalSec * 1000.0
            driftMsB += deltaDriftMsB

            // 2. Host samples peer drift and evaluates PI control
            val resultA = hostController.evaluateDrift("peer-A", driftMsA, timestampMs)
            val resultB = hostController.evaluateDrift("peer-B", driftMsB, timestampMs)

            if (second > 5) {
                // Post-convergence checks (after initial loop lock-in)
                val absA = abs(driftMsA)
                val absB = abs(driftMsB)

                if (absA > maxObservedDriftA) maxObservedDriftA = absA
                if (absB > maxObservedDriftB) maxObservedDriftB = absB

                if (absA >= 1.0) {
                    convergedDriftAExceeded1msCount++
                }
                if (absB >= 1.0) {
                    convergedDriftBExceeded1msCount++
                }

                assertTrue(
                    "Peer A drift ($absA ms) must remain < 1.0ms at second $second (30-min test)",
                    absA < 1.0
                )
                assertTrue(
                    "Peer B drift ($absB ms) must remain < 1.0ms at second $second (30-min test)",
                    absB < 1.0
                )
            }
        }

        // Summary assertions for 30-minute playback test
        assertEquals(
            "Peer A must NEVER exceed 1.0ms drift after convergence during 30-minute playback",
            0,
            convergedDriftAExceeded1msCount
        )
        assertEquals(
            "Peer B must NEVER exceed 1.0ms drift after convergence during 30-minute playback",
            0,
            convergedDriftBExceeded1msCount
        )

        assertTrue(
            "Max observed drift for Peer A ($maxObservedDriftA ms) must be < 1.0ms",
            maxObservedDriftA < 1.0
        )
        assertTrue(
            "Max observed drift for Peer B ($maxObservedDriftB ms) must be < 1.0ms",
            maxObservedDriftB < 1.0
        )

        // Verify overall sync status on host
        assertTrue("Session must report overall in-sync", hostController.isOverallInSync.value)
        assertTrue("Session max drift must be < 1.0ms", hostController.maxDriftMs.value < 1.0)

        // Resampler speed PPM should have converged near the inverse of crystal skew
        // Peer A crystal was +75 ppm, resampler should be around -75 ppm (within deadband)
        val finalResamplerA = peerA.localSpeedPpm.value
        assertTrue(
            "Peer A final resampler PPM ($finalResamplerA) should counteract +75 ppm skew",
            finalResamplerA in -150..0
        )

        // Peer B crystal was -60 ppm, resampler should be around +60 ppm (within deadband)
        val finalResamplerB = peerB.localSpeedPpm.value
        assertTrue(
            "Peer B final resampler PPM ($finalResamplerB) should counteract -60 ppm skew",
            finalResamplerB in 0..150
        )

        hostController.close()
        peerA.close()
        peerB.close()
        peerABuffer.close()
        peerBBuffer.close()
    }
}
