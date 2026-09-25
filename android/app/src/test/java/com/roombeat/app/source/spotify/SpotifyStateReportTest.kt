package com.roombeat.app.source.spotify

import com.roombeat.app.protocol.PacketContext
import com.roombeat.app.protocol.PacketDispatcher
import com.roombeat.app.protocol.RoomBeatPacket
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyStateReportTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeClock: FakeMonotonicClock
    private lateinit var fakePlayerApi: FakeSpotifyPlayerApi
    private val sentReports = mutableListOf<RoomBeatPacket.SpotifyStateReport>()

    @Before
    fun setUp() {
        fakeClock = FakeMonotonicClock(initialNanos = 10_000_000_000L) // 10s
        fakePlayerApi = FakeSpotifyPlayerApi(isPremium = true)
        sentReports.clear()
    }

    private fun createReporter(
        deviceId: String = "peer_node_1",
        reportIntervalMs: Long = 1500L,
        significantJumpThresholdMs: Long = SpotifyPeerPositionReporter.DEFAULT_SIGNIFICANT_JUMP_THRESHOLD_MS
    ): SpotifyPeerPositionReporter {
        return SpotifyPeerPositionReporter(
            deviceId = deviceId,
            playerApi = fakePlayerApi,
            clock = fakeClock,
            reportIntervalMs = reportIntervalMs,
            significantJumpThresholdMs = significantJumpThresholdMs,
            sender = { report ->
                sentReports.add(report)
            },
            coroutineDispatcher = testDispatcher
        )
    }

    @Test
    fun testReporterEmitsOnInitialSubscriptionState() = testScope.runTest {
        val reporter = createReporter()
        val initialSnapshot = SpotifyPlayerStateSnapshot(
            trackUri = "spotify:track:start",
            playbackPositionMs = 5000L,
            isPaused = false
        )
        fakePlayerApi.emitPlayerState(initialSnapshot)

        reporter.start()
        advanceTimeBy(1)

        assertEquals(1, sentReports.size)
        val report = sentReports.first()
        assertEquals("peer_node_1", report.deviceId)
        assertEquals(5000L, report.reportedPositionMs)
        assertEquals(fakeClock.nowMicros(), report.sampledAt)

        reporter.stop()
    }

    @Test
    fun testReporterEmitsPeriodicallyEvery1500msWhenPlaying() = testScope.runTest {
        val reporter = createReporter(reportIntervalMs = 1500L)
        fakePlayerApi.emitPlayerState(
            SpotifyPlayerStateSnapshot(
                trackUri = "spotify:track:steady",
                playbackPositionMs = 1000L,
                isPaused = false
            )
        )

        reporter.start()
        advanceTimeBy(1)
        assertEquals(1, sentReports.size)

        // Advance by 1500ms
        fakeClock.advanceMillis(1500)
        advanceTimeBy(1500)
        assertEquals(2, sentReports.size)

        // Advance by another 1500ms
        fakeClock.advanceMillis(1500)
        advanceTimeBy(1500)
        assertEquals(3, sentReports.size)

        reporter.stop()
    }

    @Test
    fun testReporterDoesNotEmitPeriodicallyWhenPaused() = testScope.runTest {
        val reporter = createReporter(reportIntervalMs = 1500L)
        fakePlayerApi.emitPlayerState(
            SpotifyPlayerStateSnapshot(
                trackUri = "spotify:track:paused",
                playbackPositionMs = 1000L,
                isPaused = true
            )
        )

        reporter.start()
        advanceTimeBy(1)
        assertEquals(1, sentReports.size) // Initial report

        // Advance time while paused
        fakeClock.advanceMillis(3000)
        advanceTimeBy(3000)

        // No new periodic reports should have been sent while paused
        assertEquals(1, sentReports.size)

        reporter.stop()
    }

    @Test
    fun testReporterEmitsImmediatelyOnTrackUriChange() = testScope.runTest {
        val reporter = createReporter()
        fakePlayerApi.emitPlayerState(
            SpotifyPlayerStateSnapshot(
                trackUri = "spotify:track:song1",
                playbackPositionMs = 120_000L,
                isPaused = false
            )
        )

        reporter.start()
        advanceTimeBy(1)
        assertEquals(1, sentReports.size)

        // Switch to song2
        fakeClock.advanceMillis(200)
        fakePlayerApi.emitPlayerState(
            SpotifyPlayerStateSnapshot(
                trackUri = "spotify:track:song2",
                playbackPositionMs = 0L,
                isPaused = false
            )
        )
        advanceTimeBy(1)

        assertEquals(2, sentReports.size)
        val report = sentReports.last()
        assertEquals(0L, report.reportedPositionMs)

        reporter.stop()
    }

    @Test
    fun testReporterEmitsImmediatelyOnPlayPauseToggle() = testScope.runTest {
        val reporter = createReporter()
        fakePlayerApi.emitPlayerState(
            SpotifyPlayerStateSnapshot(
                trackUri = "spotify:track:toggle",
                playbackPositionMs = 10_000L,
                isPaused = false
            )
        )

        reporter.start()
        advanceTimeBy(1)
        assertEquals(1, sentReports.size)

        // Pause
        fakeClock.advanceMillis(100)
        fakePlayerApi.pause()
        advanceTimeBy(1)

        assertEquals(2, sentReports.size)

        // Resume
        fakeClock.advanceMillis(100)
        fakePlayerApi.resume()
        advanceTimeBy(1)

        assertEquals(3, sentReports.size)

        reporter.stop()
    }

    @Test
    fun testReporterEmitsImmediatelyOnLargePositionJump() = testScope.runTest {
        val reporter = createReporter(significantJumpThresholdMs = 500L)
        val startMs = System.currentTimeMillis()
        fakePlayerApi.emitPlayerState(
            SpotifyPlayerStateSnapshot(
                trackUri = "spotify:track:jump",
                playbackPositionMs = 5000L,
                isPaused = false,
                sampledAtMs = startMs
            )
        )

        reporter.start()
        advanceTimeBy(1)
        assertEquals(1, sentReports.size)

        // Sudden jump of 10 seconds with only 200ms elapsed
        fakeClock.advanceMillis(200)
        fakePlayerApi.emitPlayerState(
            SpotifyPlayerStateSnapshot(
                trackUri = "spotify:track:jump",
                playbackPositionMs = 15_000L,
                isPaused = false,
                sampledAtMs = startMs + 200L
            )
        )
        advanceTimeBy(1)

        assertEquals(2, sentReports.size)
        assertEquals(15_000L, sentReports.last().reportedPositionMs)

        reporter.stop()
    }

    @Test
    fun testReportNowForcesImmediateEmission() = testScope.runTest {
        val reporter = createReporter()
        fakePlayerApi.emitPlayerState(
            SpotifyPlayerStateSnapshot(
                trackUri = "spotify:track:force",
                playbackPositionMs = 25_000L,
                isPaused = false
            )
        )

        reporter.start()
        advanceTimeBy(1)
        assertEquals(1, sentReports.size)

        fakeClock.advanceMillis(100)
        reporter.reportNow()
        advanceTimeBy(1)

        assertEquals(2, sentReports.size)
        assertEquals(25_000L, sentReports.last().reportedPositionMs)

        reporter.stop()
    }

    // ========================================================================
    // SpotifyTelemetryAggregator Tests
    // ========================================================================

    @Test
    fun testTelemetryAggregatorMetricsComputation() {
        val aggregator = SpotifyTelemetryAggregator()

        assertEquals(0, aggregator.activePeerCount.value)
        assertEquals(0, aggregator.syncedPeerCount.value)
        assertEquals(0L, aggregator.maxDriftMs.value)
        assertTrue(aggregator.isOverallInSync.value)

        // Record peer 1: synced (drift = +25ms)
        val peer1 = SpotifyPeerTelemetry(
            deviceId = "peer_1",
            reportedPositionMs = 10000L,
            sampledAtUs = 10000000L,
            projectedPositionMs = 10025L,
            expectedMasterPositionMs = 10000L,
            driftMs = 25L,
            isSynced = true
        )
        aggregator.updatePeerTelemetry(peer1)

        assertEquals(1, aggregator.activePeerCount.value)
        assertEquals(1, aggregator.syncedPeerCount.value)
        assertEquals(25L, aggregator.maxDriftMs.value)
        assertTrue(aggregator.isOverallInSync.value)

        // Record peer 2: out of sync (drift = -75ms)
        val peer2 = SpotifyPeerTelemetry(
            deviceId = "peer_2",
            reportedPositionMs = 9925L,
            sampledAtUs = 10000000L,
            projectedPositionMs = 9925L,
            expectedMasterPositionMs = 10000L,
            driftMs = -75L,
            isSynced = false
        )
        aggregator.updatePeerTelemetry(peer2)

        assertEquals(2, aggregator.activePeerCount.value)
        assertEquals(1, aggregator.syncedPeerCount.value)
        assertEquals(75L, aggregator.maxDriftMs.value)
        assertFalse(aggregator.isOverallInSync.value)

        // Record correction issued to peer 2
        aggregator.recordCorrectionIssued("peer_2")
        val p2 = aggregator.getTelemetry("peer_2")
        assertNotNull(p2)
        assertEquals(1L, p2!!.correctionsIssued)

        // Formatted drift check
        assertEquals("+25ms", peer1.formattedDrift)
        assertEquals("-75ms", peer2.formattedDrift)

        // Remove peer 2
        aggregator.removePeer("peer_2")
        assertEquals(1, aggregator.activePeerCount.value)
        assertEquals(1, aggregator.syncedPeerCount.value)
        assertEquals(25L, aggregator.maxDriftMs.value)
        assertTrue(aggregator.isOverallInSync.value)

        // Clear
        aggregator.clear()
        assertEquals(0, aggregator.activePeerCount.value)
        assertTrue(aggregator.isOverallInSync.value)
    }

    @Test
    fun testPacketDispatcherIntegration() = testScope.runTest {
        val dispatcher = PacketDispatcher(defaultDispatcher = testDispatcher)
        var receivedReport: RoomBeatPacket.SpotifyStateReport? = null

        val reg = dispatcher.registerSpotifyStateReportHandler { packet, _ ->
            receivedReport = packet
        }

        val testPacket = RoomBeatPacket.SpotifyStateReport(
            deviceId = "peer_42",
            reportedPositionMs = 77000L,
            sampledAt = 12345678L
        )

        dispatcher.dispatch(testPacket, PacketContext(senderId = "peer_42"))
        advanceUntilIdle()

        assertNotNull(receivedReport)
        assertEquals("peer_42", receivedReport!!.deviceId)
        assertEquals(77000L, receivedReport!!.reportedPositionMs)
        assertEquals(12345678L, receivedReport!!.sampledAt)

        reg.unregister()
    }
}
