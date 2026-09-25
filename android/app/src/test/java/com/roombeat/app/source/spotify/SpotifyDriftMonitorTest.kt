package com.roombeat.app.source.spotify

import com.roombeat.app.audio.PlaybackClockScheduler
import com.roombeat.app.protocol.PacketContext
import com.roombeat.app.protocol.PacketDispatcher
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.PeerSessionManager
import com.roombeat.app.session.PeerSessionTransport
import com.roombeat.app.sync.FakeMonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyDriftMonitorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeClock: FakeMonotonicClock
    private val targetedPackets = mutableListOf<Pair<String, RoomBeatPacket>>()
    private val detectedDrifts = mutableListOf<Pair<String, Long>>()
    private val issuedCorrections = mutableListOf<RoomBeatPacket.SpotifyCmd>()
    private var finishedTrackUri: String? = null
    private var progressedTrackTransition: Pair<String, String>? = null

    @Before
    fun setUp() {
        fakeClock = FakeMonotonicClock(initialNanos = 10_000_000_000L) // 10s monotonic
        targetedPackets.clear()
        detectedDrifts.clear()
        issuedCorrections.clear()
        finishedTrackUri = null
        progressedTrackTransition = null
    }

    private fun createMonitor(
        isHost: Boolean = true,
        driftThresholdMs: Long = 50L,
        cooldownUs: Long = 2_000_000L,
        transport: PeerSessionTransport? = null,
        sessionManager: PeerSessionManager? = null,
        commandDispatcher: SpotifyCommandDispatcher? = null
    ): SpotifyDriftMonitor {
        return SpotifyDriftMonitor(
            isHost = isHost,
            clock = fakeClock,
            commandDispatcher = commandDispatcher,
            transport = transport,
            sessionManager = sessionManager,
            driftThresholdMs = driftThresholdMs,
            correctionCooldownUs = cooldownUs,
            targetedSender = { deviceId, packet ->
                targetedPackets.add(deviceId to packet)
            },
            coroutineDispatcher = testDispatcher,
            onDriftDetected = { id, drift ->
                detectedDrifts.add(id to drift)
            },
            onCorrectionIssued = { _, _, _, _ ->
                // captured via targetedPackets
            },
            onTrackFinished = { uri ->
                finishedTrackUri = uri
            },
            onTrackProgressed = { oldUri, newUri ->
                progressedTrackTransition = oldUri to newUri
            }
        )
    }

    // ========================================================================
    // Master Timeline Tests
    // ========================================================================

    @Test
    fun testExpectedMasterPositionBeforePresentationTimeReturnsStartPosition() {
        val monitor = createMonitor()
        val nowUs = fakeClock.nowMicros() // 10,000,000 us
        val targetPresentationUs = nowUs + 400_000L // 10,400,000 us

        monitor.setMasterTimeline(
            trackUri = "spotify:track:timeline1",
            durationMs = 200_000L,
            targetPresentationTimeUs = targetPresentationUs,
            startPositionMs = 5000L,
            isPlaying = true
        )

        // At T_now (before T_target)
        val posBefore = monitor.calculateExpectedMasterPosition(nowUs)
        assertEquals(5000L, posBefore)

        // Halfway through countdown (T_now + 200ms < T_target)
        val posHalfway = monitor.calculateExpectedMasterPosition(nowUs + 200_000L)
        assertEquals(5000L, posHalfway)
    }

    @Test
    fun testExpectedMasterPositionAdvancesWithElapsedTimeAfterPresentationTime() {
        val monitor = createMonitor()
        val targetPresentationUs = fakeClock.nowMicros() // 10,000,000 us

        monitor.setMasterTimeline(
            trackUri = "spotify:track:timeline1",
            durationMs = 200_000L,
            targetPresentationTimeUs = targetPresentationUs,
            startPositionMs = 0L,
            isPlaying = true
        )

        // At T_target: position = 0ms
        assertEquals(0L, monitor.calculateExpectedMasterPosition(targetPresentationUs))

        // 1 second elapsed (1,000,000 us): position = 1000ms
        assertEquals(1000L, monitor.calculateExpectedMasterPosition(targetPresentationUs + 1_000_000L))

        // 5.5 seconds elapsed: position = 5500ms
        assertEquals(5500L, monitor.calculateExpectedMasterPosition(targetPresentationUs + 5_500_000L))

        // Clamped at duration (200,000ms)
        assertEquals(200_000L, monitor.calculateExpectedMasterPosition(targetPresentationUs + 250_000_000L))
    }

    @Test
    fun testMasterPositionStationaryWhenPaused() {
        val monitor = createMonitor()
        val tTarget = fakeClock.nowMicros()

        monitor.setMasterTimeline(
            trackUri = "spotify:track:pauseTest",
            durationMs = 180_000L,
            targetPresentationTimeUs = tTarget,
            startPositionMs = 10_000L,
            isPlaying = true
        )

        // Play for 2 seconds
        val posAfter2s = monitor.calculateExpectedMasterPosition(tTarget + 2_000_000L)
        assertEquals(12_000L, posAfter2s)

        // Pause at 12_000ms
        monitor.updateMasterPause(atPositionMs = 12_000L)
        assertFalse(monitor.isMasterPlaying)

        // Position does not advance while paused
        val posPaused = monitor.calculateExpectedMasterPosition(tTarget + 10_000_000L)
        assertEquals(12_000L, posPaused)

        // Resume at new target timestamp
        val newTargetUs = tTarget + 12_000_000L
        monitor.updateMasterResume(targetPresentationTimeUs = newTargetUs, fromPositionMs = 12_000L)
        assertTrue(monitor.isMasterPlaying)

        // 1 second after resume: 13_000ms
        val posAfterResume = monitor.calculateExpectedMasterPosition(newTargetUs + 1_000_000L)
        assertEquals(13_000L, posAfterResume)
    }

    // ========================================================================
    // Peer Position Projection Tests
    // ========================================================================

    @Test
    fun testProjectPeerPositionWithZeroClockOffset() {
        val monitor = createMonitor()
        val nowUs = 10_000_000L // 10.0s host time
        val sampleUs = 9_900_000L // 9.9s peer time (100ms ago)
        val peerOffsetUs = 0L // Clocks identical

        // Peer reported 5000ms 100ms ago -> projected should be 5100ms
        val projected = monitor.projectPeerPosition(
            reportedPositionMs = 5000L,
            sampledAtUs = sampleUs,
            peerOffsetMicros = peerOffsetUs,
            nowMicros = nowUs
        )
        assertEquals(5100L, projected)
    }

    @Test
    fun testProjectPeerPositionWithPositiveClockOffset() {
        val monitor = createMonitor()
        val nowUs = 10_000_000L // 10.0s host time
        val peerOffsetUs = 200_000L // Peer is 200ms ahead of host (T_peer = T_host + 200ms)
        // If peer took sample at peer time 10,100,000 us, in host time it was 10,100,000 - 200,000 = 9,900,000 us (100ms ago)
        val samplePeerUs = 10_100_000L

        val projected = monitor.projectPeerPosition(
            reportedPositionMs = 5000L,
            sampledAtUs = samplePeerUs,
            peerOffsetMicros = peerOffsetUs,
            nowMicros = nowUs
        )
        // Elapsed in host time = 100ms -> projected position = 5100ms
        assertEquals(5100L, projected)
    }

    @Test
    fun testProjectPeerPositionWithNegativeClockOffset() {
        val monitor = createMonitor()
        val nowUs = 10_000_000L // 10.0s host time
        val peerOffsetUs = -150_000L // Peer is 150ms behind host (T_peer = T_host - 150ms)
        // If peer took sample at peer time 9,750,000 us, in host time it was 9,750,000 - (-150,000) = 9,900,000 us (100ms ago)
        val samplePeerUs = 9_750_000L

        val projected = monitor.projectPeerPosition(
            reportedPositionMs = 5000L,
            sampledAtUs = samplePeerUs,
            peerOffsetMicros = peerOffsetUs,
            nowMicros = nowUs
        )
        assertEquals(5100L, projected)
    }

    // ========================================================================
    // Drift Evaluation & Micro-Seek Correction Tests
    // ========================================================================

    @Test
    fun testInSyncPeerWithin50msThresholdIssuesNoCorrection() {
        val monitor = createMonitor(driftThresholdMs = 50L)
        val tTargetUs = fakeClock.nowMicros() // 10,000,000 us

        monitor.setMasterTimeline(
            trackUri = "spotify:track:syncCheck",
            durationMs = 200_000L,
            targetPresentationTimeUs = tTargetUs,
            startPositionMs = 0L,
            isPlaying = true
        )

        // Advance 5 seconds: expected master position = 5000ms
        fakeClock.advanceMillis(5000)
        val nowUs = fakeClock.nowMicros()

        // Peer reports position that projects to 5025ms (+25ms drift, within 50ms)
        val result = monitor.evaluateDrift(
            deviceId = "peer_node_sync",
            reportedPositionMs = 5025L,
            sampledAtUs = nowUs,
            peerOffsetMicros = 0L,
            nowMicros = nowUs
        )

        assertEquals(25L, result.driftMs)
        assertFalse(result.exceedsThreshold)
        assertFalse(result.correctionIssued)
        assertNull(result.correctionPacket)
        assertEquals(0, targetedPackets.size)
        assertEquals(0, detectedDrifts.size)
    }

    @Test
    fun testLaggingPeerExceeding50msIssuesMicroSeekCorrection() {
        val monitor = createMonitor(driftThresholdMs = 50L, cooldownUs = 2_000_000L)
        val tTargetUs = fakeClock.nowMicros()

        monitor.setMasterTimeline(
            trackUri = "spotify:track:lagTest",
            durationMs = 200_000L,
            targetPresentationTimeUs = tTargetUs,
            startPositionMs = 0L,
            isPlaying = true
        )

        // Advance 5 seconds: expected master position = 5000ms
        fakeClock.advanceMillis(5000)
        val nowUs = fakeClock.nowMicros()

        // Peer projects to 4920ms (-80ms drift, lagging)
        val result = monitor.evaluateDrift(
            deviceId = "peer_lagging",
            reportedPositionMs = 4920L,
            sampledAtUs = nowUs,
            peerOffsetMicros = 0L,
            nowMicros = nowUs
        )

        assertEquals(-80L, result.driftMs)
        assertTrue(result.exceedsThreshold)
        assertTrue(result.correctionIssued)
        assertNotNull(result.correctionPacket)

        // Verify targeted packet
        assertEquals(1, targetedPackets.size)
        val (targetDevice, packet) = targetedPackets.first()
        assertEquals("peer_lagging", targetDevice)
        val seekCmd = packet as RoomBeatPacket.SpotifyCmd
        assertEquals(RoomBeatPacket.SpotifyCmd.CMD_SEEK, seekCmd.command)
        assertEquals("spotify:track:lagTest", seekCmd.trackUri)

        // Correction lead time is 400ms: expected master position at T_target (5000 + 400 = 5400ms)
        val expectedTargetTime = nowUs + SpotifyDriftMonitor.DEFAULT_CORRECTION_LEAD_TIME_US
        assertEquals(expectedTargetTime, seekCmd.targetPresentationTime)
        assertEquals(5400L, seekCmd.targetPositionMs)

        // Verify callback
        assertEquals(1, detectedDrifts.size)
        assertEquals("peer_lagging" to -80L, detectedDrifts.first())
    }

    @Test
    fun testLeadingPeerExceeding50msIssuesMicroSeekCorrection() {
        val monitor = createMonitor(driftThresholdMs = 50L)
        val tTargetUs = fakeClock.nowMicros()

        monitor.setMasterTimeline(
            trackUri = "spotify:track:leadTest",
            durationMs = 200_000L,
            targetPresentationTimeUs = tTargetUs,
            startPositionMs = 0L,
            isPlaying = true
        )

        // Advance 5 seconds: expected master position = 5000ms
        fakeClock.advanceMillis(5000)
        val nowUs = fakeClock.nowMicros()

        // Peer projects to 5090ms (+90ms drift, leading)
        val result = monitor.evaluateDrift(
            deviceId = "peer_leading",
            reportedPositionMs = 5090L,
            sampledAtUs = nowUs,
            peerOffsetMicros = 0L,
            nowMicros = nowUs
        )

        assertEquals(90L, result.driftMs)
        assertTrue(result.exceedsThreshold)
        assertTrue(result.correctionIssued)

        assertEquals(1, targetedPackets.size)
        val seekCmd = targetedPackets.first().second as RoomBeatPacket.SpotifyCmd
        assertEquals(5400L, seekCmd.targetPositionMs)
    }

    @Test
    fun testCooldownEliminatesCorrectionStorms() {
        val monitor = createMonitor(driftThresholdMs = 50L, cooldownUs = 2_000_000L) // 2s cooldown
        val tTargetUs = fakeClock.nowMicros()

        monitor.setMasterTimeline(
            trackUri = "spotify:track:cooldown",
            durationMs = 200_000L,
            targetPresentationTimeUs = tTargetUs,
            startPositionMs = 0L,
            isPlaying = true
        )

        fakeClock.advanceMillis(5000)
        val nowUs = fakeClock.nowMicros()

        // First report: -70ms drift -> issues correction
        val result1 = monitor.evaluateDrift(
            deviceId = "peer_storm",
            reportedPositionMs = 4930L,
            sampledAtUs = nowUs,
            peerOffsetMicros = 0L,
            nowMicros = nowUs
        )
        assertTrue(result1.correctionIssued)
        assertEquals(1, targetedPackets.size)

        // Second report 500ms later: still drifting (-60ms), but within 2s cooldown
        fakeClock.advanceMillis(500)
        val nowUs2 = fakeClock.nowMicros()

        val result2 = monitor.evaluateDrift(
            deviceId = "peer_storm",
            reportedPositionMs = 5440L, // master is at 5500ms, peer is at 5440ms (-60ms)
            sampledAtUs = nowUs2,
            peerOffsetMicros = 0L,
            nowMicros = nowUs2
        )
        assertTrue(result2.exceedsThreshold)
        assertFalse(result2.correctionIssued) // Suppressed by cooldown!
        assertEquals(1, targetedPackets.size) // No extra packet sent

        // Advance past 2s cooldown (2100ms elapsed since first correction)
        fakeClock.advanceMillis(1600)
        val nowUs3 = fakeClock.nowMicros()

        val result3 = monitor.evaluateDrift(
            deviceId = "peer_storm",
            reportedPositionMs = 7020L, // master is at 7100ms, peer is at 7020ms (-80ms)
            sampledAtUs = nowUs3,
            peerOffsetMicros = 0L,
            nowMicros = nowUs3
        )
        assertTrue(result3.correctionIssued) // Now allowed!
        assertEquals(2, targetedPackets.size)
    }

    // ========================================================================
    // Transport & SessionManager Integration Tests
    // ========================================================================

    @Test
    fun testTransportSendToPeerUsedWhenTargetedSenderNull() {
        val sentPeerPackets = mutableListOf<Pair<String, RoomBeatPacket>>()
        val fakeTransport = object : PeerSessionTransport {
            override fun sendToPeer(peerId: String, packet: RoomBeatPacket): Boolean {
                sentPeerPackets.add(peerId to packet)
                return true
            }

            override fun broadcastToAll(packet: RoomBeatPacket): Int = 0
            override fun disconnectPeer(peerId: String, reason: String?) {}
        }

        val monitor = SpotifyDriftMonitor(
            isHost = true,
            clock = fakeClock,
            transport = fakeTransport,
            targetedSender = null,
            coroutineDispatcher = testDispatcher
        )

        monitor.setMasterTimeline("spotify:track:t1", 100_000L, fakeClock.nowMicros(), 0L, isPlaying = true)
        fakeClock.advanceMillis(1000)

        // Drift report
        monitor.evaluateDrift("peer_x", 850L, fakeClock.nowMicros(), 0L)

        assertEquals(1, sentPeerPackets.size)
        assertEquals("peer_x", sentPeerPackets.first().first)
        assertTrue(sentPeerPackets.first().second is RoomBeatPacket.SpotifyCmd)
    }

    @Test
    fun testSessionManagerResolvesPeerOffsetAutomatically() {
        val sessionManager = PeerSessionManager(
            isHost = true,
            sessionId = "room_session_1"
        )
        val peer = PeerNode(
            id = "peer_calibrated",
            name = "Bedroom Speaker",
            ip = "192.168.1.50",
            offsetMs = 120.0 // 120ms offset (120,000 us)
        )
        sessionManager.registerPeer(peer)

        val monitor = createMonitor(sessionManager = sessionManager)
        val tTarget = fakeClock.nowMicros()
        monitor.setMasterTimeline("spotify:track:calib", 100_000L, tTarget, 0L, isPlaying = true)

        fakeClock.advanceMillis(2000)
        val nowUs = fakeClock.nowMicros()

        // Master expected position at 2.0s = 2000ms
        // Peer sampled position at local time (T_host + 120ms = nowUs + 120_000us)
        // If peer reported 2000ms at sample time nowUs + 120_000us:
        // monitor will subtract offset 120_000us -> sampleHostUs = nowUs -> elapsed = 0 -> projected = 2000ms -> drift = 0ms!
        val result = monitor.evaluateDrift(
            deviceId = "peer_calibrated",
            reportedPositionMs = 2000L,
            sampledAtUs = nowUs + 120_000L // Peer local clock
        )

        assertEquals(0L, result.driftMs)
        assertFalse(result.exceedsThreshold)
    }

    // ========================================================================
    // Track Finish & Playlist Progression Tests
    // ========================================================================

    @Test
    fun testTrackFinishedCallbackInvokedNearCompletion() {
        val monitor = createMonitor()
        val tTarget = fakeClock.nowMicros()
        val trackDurationMs = 60_000L

        monitor.setMasterTimeline(
            trackUri = "spotify:track:endingSong",
            durationMs = trackDurationMs,
            targetPresentationTimeUs = tTarget,
            startPositionMs = 0L,
            isPlaying = true
        )

        // Advance to 59.6 seconds (400ms before duration, within 500ms threshold)
        fakeClock.advanceMillis(59_600)

        val progressed = monitor.checkTrackProgression()
        assertTrue(progressed)
        assertEquals("spotify:track:endingSong", finishedTrackUri)
        assertTrue(monitor.isTrackFinished)

        // Repeated check does not re-trigger
        finishedTrackUri = null
        val secondCheck = monitor.checkTrackProgression()
        assertFalse(secondCheck)
        assertNull(finishedTrackUri)
    }

    @Test
    fun testTrackQueueAutoAdvancesToNextTrackUponCompletion() {
        val monitor = createMonitor()
        val tTarget = fakeClock.nowMicros()

        monitor.setMasterTimeline(
            trackUri = "spotify:track:songA",
            durationMs = 30_000L,
            targetPresentationTimeUs = tTarget,
            startPositionMs = 0L,
            isPlaying = true
        )

        // Queue next song
        monitor.queueNextTrack("spotify:track:songB", durationMs = 45_000L)

        // Advance to track end
        fakeClock.advanceMillis(29_800)
        monitor.checkTrackProgression()

        assertEquals("spotify:track:songA", finishedTrackUri)
        assertEquals("spotify:track:songA" to "spotify:track:songB", progressedTrackTransition)
        assertEquals("spotify:track:songB", monitor.masterTrackUri)
        assertEquals(45_000L, monitor.masterTrackDurationMs)
        assertEquals(0L, monitor.masterStartPositionMs)
        assertTrue(monitor.isMasterPlaying)
    }

    // ========================================================================
    // Packet Dispatcher & Command Dispatcher Integration Tests
    // ========================================================================

    @Test
    fun testPacketDispatcherRoutesStateReportsDirectlyToMonitor() = testScope.runTest {
        val dispatcher = PacketDispatcher(defaultDispatcher = testDispatcher)
        val monitor = createMonitor()
        monitor.setMasterTimeline("spotify:track:disp", 100_000L, fakeClock.nowMicros(), 0L, isPlaying = true)

        val reg = monitor.registerPacketHandler(dispatcher)

        val report = RoomBeatPacket.SpotifyStateReport(
            deviceId = "peer_remote",
            reportedPositionMs = 5000L,
            sampledAt = fakeClock.nowMicros()
        )

        dispatcher.dispatch(report, PacketContext(senderId = "peer_remote"))
        advanceUntilIdle()

        val telemetry = monitor.telemetryAggregator.getTelemetry("peer_remote")
        assertNotNull(telemetry)
        assertEquals(5000L, telemetry!!.reportedPositionMs)

        reg.unregister()
    }

    @Test
    fun testSpotifyCommandDispatcherStateChangesSyncMasterTimeline() = testScope.runTest {
        val fakePlayerApi = FakeSpotifyPlayerApi(isPremium = true)
        val scheduler = PlaybackClockScheduler(clock = fakeClock)
        val cmdDispatcher = SpotifyCommandDispatcher(
            isHost = true,
            customPlayerApi = fakePlayerApi,
            clock = fakeClock,
            scheduler = scheduler,
            coroutineDispatcher = testDispatcher,
            scope = testScope
        )

        val monitor = createMonitor(commandDispatcher = cmdDispatcher)
        advanceTimeBy(1)

        // Dispatch play
        val trackUri = "spotify:track:syncedThroughCmd"
        cmdDispatcher.dispatchPlay(trackUri, startPositionMs = 0L)
        advanceTimeBy(1)

        // Buffering -> advance clock to T_target
        fakeClock.advanceMicros(SpotifyCommandDispatcher.DEFAULT_LEAD_TIME_MICROS)
        advanceUntilIdle()

        // Monitor should now be in Playing state with trackUri
        assertEquals(trackUri, monitor.masterTrackUri)
        assertTrue(monitor.isMasterPlaying)

        // Dispatch pause
        cmdDispatcher.dispatchPause()
        advanceTimeBy(1)
        fakeClock.advanceMicros(SpotifyCommandDispatcher.DEFAULT_LEAD_TIME_MICROS)
        advanceUntilIdle()

        assertFalse(monitor.isMasterPlaying)

        cmdDispatcher.close()
        monitor.close()
    }
}
