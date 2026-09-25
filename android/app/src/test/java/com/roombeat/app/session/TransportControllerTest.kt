package com.roombeat.app.session

import com.roombeat.app.audio.PlaybackClockScheduler
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.audio.buffer.JitterBufferConstants
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.sync.FakeMonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TransportController] verifying synchronized transport controls:
 * 1. Host pause dispatch (100ms execution lead time) and scheduled execution.
 * 2. Peer pause handling with clock offset adjustment ($\theta$).
 * 3. Host seek dispatch (jitter buffer flush, packet broadcast, 350ms lead time).
 * 4. Peer seek packet ingestion (jitter buffer flush, clock offset translation, countdown).
 * 5. Host play/resume and stop flows.
 * 6. Track metadata and position updates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportControllerTest {

    private class FakeSessionTransport : PeerSessionTransport {
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

    private fun createSyntheticFrame(value: Float = 0.5f): FloatArray {
        val pcm = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        for (i in 0 until JitterBufferConstants.SAMPLES_PER_CHANNEL) {
            pcm[i * 2] = value
            pcm[i * 2 + 1] = value
        }
        return pcm
    }

    @Test
    fun testHostPauseDispatchAndScheduledExecution() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L) // 1,000,000 us
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 120)
        jitterBuffer.setClockFunction { fakeClock.nowMicros() }

        val scheduler = PlaybackClockScheduler(fakeClock)
        val fakeTransport = FakeSessionTransport()

        val coordinator = PlaybackCoordinator(
            isHost = true,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            transport = fakeTransport,
            scope = testScope
        )

        val controller = TransportController(
            isHost = true,
            coordinator = coordinator,
            scheduler = scheduler,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            transport = fakeTransport,
            scope = testScope
        )

        controller.setTrack("Test Track", "Artist", 180_000L)
        controller.play()

        // Advance 100ms so play takes effect
        fakeClock.advanceMillis(100L)
        testScope.advanceTimeBy(100L)
        testScope.runCurrent()
        assertEquals(TransportStatus.PLAYING, controller.state.value.status)

        // Clear broadcast packets from play call
        fakeTransport.broadcastPackets.clear()

        // 1. Host initiates pause
        controller.pause()

        // 2. Verify SESSION_PAUSE packet broadcast with atPresentationTime = now (1,100,000) + 100,000us = 1,200,000us
        assertEquals(1, fakeTransport.broadcastPackets.size)
        val pausePacket = fakeTransport.broadcastPackets[0] as RoomBeatPacket.SessionPause
        assertEquals(1_200_000L, pausePacket.atPresentationTime)

        // 3. Before simulated deadline (e.g. 50ms): pause has not triggered yet
        fakeClock.advanceMillis(50L)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()
        assertEquals(TransportStatus.PLAYING, controller.state.value.status)

        // 4. Advance remaining 50ms to reach exact presentation deadline
        fakeClock.advanceMillis(50L)
        testScope.advanceTimeBy(50L)
        testScope.runCurrent()

        // Verified: state is now PAUSED!
        assertEquals(TransportStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.isPaused)
        assertFalse(controller.state.value.isPlaying)

        controller.close()
        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testPeerPauseHandlingWithClockOffset() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 2_000_000_000L) // 2,000,000 us local time
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 120)
        jitterBuffer.setClockFunction { fakeClock.nowMicros() }

        val scheduler = PlaybackClockScheduler(fakeClock)
        val fakeTransport = FakeSessionTransport()

        val coordinator = PlaybackCoordinator(
            isHost = false,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            transport = fakeTransport,
            clockOffsetMicros = 25_000L, // Peer is +25ms ahead of host
            scope = testScope
        )

        val controller = TransportController(
            isHost = false,
            coordinator = coordinator,
            scheduler = scheduler,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            clockOffsetMicros = 25_000L,
            transport = fakeTransport,
            scope = testScope
        )

        // Set peer in playing state
        controller.handleSessionStart(
            RoomBeatPacket.SessionStart(
                mediaId = "track_1",
                targetPresentationTime = 2_000_000L - 25_000L, // Host target
                sourceType = "LOCAL_FILE"
            )
        )
        testScope.runCurrent()

        // Incoming pause packet from host with host presentation time: 2,100,000us
        // Local presentation time must be: 2,100,000 + 25,000 = 2,125,000us (125ms from local now)
        val pausePacket = RoomBeatPacket.SessionPause(atPresentationTime = 2_100_000L)
        controller.handleSessionPause(pausePacket)

        // Advance by 60ms: not yet paused
        fakeClock.advanceMillis(60L)
        testScope.advanceTimeBy(60L)
        testScope.runCurrent()
        assertFalse(controller.state.value.isPaused)

        // Advance remaining 65ms (total 125ms advance): reached 2,125,000us
        fakeClock.advanceMillis(65L)
        testScope.advanceTimeBy(65L)
        testScope.runCurrent()

        // Peer is paused!
        assertEquals(TransportStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.isPaused)

        controller.close()
        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testHostSeekDispatchAndJitterBufferFlush() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L) // 1,000,000 us
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 120)
        jitterBuffer.setClockFunction { fakeClock.nowMicros() }

        val scheduler = PlaybackClockScheduler(fakeClock)
        val fakeTransport = FakeSessionTransport()

        val coordinator = PlaybackCoordinator(
            isHost = true,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            transport = fakeTransport,
            scope = testScope
        )

        val controller = TransportController(
            isHost = true,
            coordinator = coordinator,
            scheduler = scheduler,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            transport = fakeTransport,
            scope = testScope
        )

        controller.setTrack("Test Track", "Artist", 240_000L)

        // Pre-populate jitter buffer with 5 obsolete frames
        for (i in 0L until 5L) {
            jitterBuffer.pushDecodedFrame(i, 1_000_000L + i * 20_000L, createSyntheticFrame(0.5f))
        }
        assertEquals(5, jitterBuffer.queuedFrames)

        // Host seeks to 45,000 ms (45 seconds)
        controller.seekTo(45_000L)

        // 1. Verify jitter buffer is immediately flushed
        assertEquals(0, jitterBuffer.queuedFrames)

        // 2. Verify target presentation time is now (1,000,000) + 350,000us = 1,350,000us
        assertEquals(1_350_000L, jitterBuffer.targetStartTimeUs)

        // 3. Verify SESSION_SEEK packet broadcast
        assertEquals(1, fakeTransport.broadcastPackets.size)
        val seekPacket = fakeTransport.broadcastPackets[0] as RoomBeatPacket.SessionSeek
        assertEquals(45_000L, seekPacket.positionMs)
        assertEquals(1_350_000L, seekPacket.targetPresentationTime)

        // 4. Verify state transitions to BUFFERING at seek position
        assertEquals(TransportStatus.BUFFERING, controller.state.value.status)
        assertEquals(45_000L, controller.state.value.currentPositionMs)
        assertTrue(controller.state.value.isBuffering)

        // 5. Advance time by 200ms: still buffering
        fakeClock.advanceMillis(200L)
        testScope.advanceTimeBy(200L)
        testScope.runCurrent()
        assertEquals(TransportStatus.BUFFERING, controller.state.value.status)

        // 6. Advance remaining 150ms (total 350ms): target deadline reached!
        fakeClock.advanceMillis(150L)
        testScope.advanceTimeBy(150L)
        testScope.runCurrent()

        assertEquals(TransportStatus.PLAYING, controller.state.value.status)
        assertTrue(controller.state.value.isPlaying)

        controller.close()
        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testPeerSeekPacketIngestionAndTargetCountdown() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 2_000_000_000L) // 2,000,000 us local time
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 120)
        jitterBuffer.setClockFunction { fakeClock.nowMicros() }

        val scheduler = PlaybackClockScheduler(fakeClock)
        val fakeTransport = FakeSessionTransport()

        val coordinator = PlaybackCoordinator(
            isHost = false,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            transport = fakeTransport,
            clockOffsetMicros = -40_000L, // Peer is -40ms behind host
            scope = testScope
        )

        val controller = TransportController(
            isHost = false,
            coordinator = coordinator,
            scheduler = scheduler,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            clockOffsetMicros = -40_000L,
            transport = fakeTransport,
            scope = testScope
        )

        // Pre-populate jitter buffer
        for (i in 0L until 4L) {
            jitterBuffer.pushDecodedFrame(i, 2_000_000L + i * 20_000L, createSyntheticFrame(0.5f))
        }
        assertEquals(4, jitterBuffer.queuedFrames)

        // Incoming SESSION_SEEK packet from host: position 80s, target 2,390,000us host time
        // Local presentation time: 2,390,000 + (-40,000) = 2,350,000us (350ms lead time from local now 2,000,000us)
        val seekPacket = RoomBeatPacket.SessionSeek(
            positionMs = 80_000L,
            targetPresentationTime = 2_390_000L
        )

        controller.handleSessionSeek(seekPacket)

        // 1. Verify local jitter buffer flushed and armed with local presentation timestamp
        assertEquals(0, jitterBuffer.queuedFrames)
        assertEquals(2_350_000L, jitterBuffer.targetStartTimeUs)

        // 2. Verify state is BUFFERING with updated seek position
        assertEquals(TransportStatus.BUFFERING, controller.state.value.status)
        assertEquals(80_000L, controller.state.value.currentPositionMs)

        // 3. Advance by 200ms: still buffering
        fakeClock.advanceMillis(200L)
        testScope.advanceTimeBy(200L)
        testScope.runCurrent()
        assertEquals(TransportStatus.BUFFERING, controller.state.value.status)

        // 4. Advance remaining 150ms: presentation timestamp reached!
        fakeClock.advanceMillis(150L)
        testScope.advanceTimeBy(150L)
        testScope.runCurrent()

        assertEquals(TransportStatus.SYNCED_TO_HOST, controller.state.value.status)
        assertTrue(controller.state.value.isPlaying)

        controller.close()
        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testPlayResumeAndStopFlows() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 120)
        jitterBuffer.setClockFunction { fakeClock.nowMicros() }

        val scheduler = PlaybackClockScheduler(fakeClock)
        val fakeTransport = FakeSessionTransport()

        val coordinator = PlaybackCoordinator(
            isHost = true,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            transport = fakeTransport,
            scope = testScope
        )

        val controller = TransportController(
            isHost = true,
            coordinator = coordinator,
            scheduler = scheduler,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            transport = fakeTransport,
            scope = testScope
        )

        controller.setTrack("Song 1", "Artist 1", 120_000L)
        assertEquals(TransportStatus.IDLE, controller.state.value.status)

        // 1. Host Play
        controller.play()
        assertEquals(TransportStatus.BUFFERING, controller.state.value.status)
        assertEquals(1, fakeTransport.broadcastPackets.size)
        assertTrue(fakeTransport.broadcastPackets[0] is RoomBeatPacket.SessionStart)

        fakeClock.advanceMillis(100L)
        testScope.advanceTimeBy(100L)
        testScope.runCurrent()
        assertEquals(TransportStatus.PLAYING, controller.state.value.status)

        // Update position to 30s
        controller.updatePosition(30_000L)
        assertEquals(30_000L, controller.state.value.currentPositionMs)

        // 2. Host Stop
        fakeTransport.broadcastPackets.clear()
        controller.stop()

        assertEquals(1, fakeTransport.broadcastPackets.size)
        assertTrue(fakeTransport.broadcastPackets[0] is RoomBeatPacket.SessionStop)
        assertEquals(TransportStatus.STOPPED, controller.state.value.status)
        assertEquals(0L, controller.state.value.currentPositionMs)
        assertTrue(controller.state.value.isStopped)

        controller.close()
        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testTrackMetadataAndPositionBounds() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val jitterBuffer = AudioJitterBuffer()
        val scheduler = PlaybackClockScheduler(fakeClock)
        val coordinator = PlaybackCoordinator(
            isHost = true,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            scope = testScope
        )

        val controller = TransportController(
            isHost = true,
            coordinator = coordinator,
            scheduler = scheduler,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scope = testScope
        )

        controller.setTrack("Midnight Walk", "Artist X", 100_000L)
        assertEquals("Midnight Walk", controller.state.value.trackTitle)
        assertEquals("Artist X", controller.state.value.artist)
        assertEquals(100_000L, controller.state.value.durationMs)

        // Update position within bounds
        controller.updatePosition(50_000L)
        assertEquals(50_000L, controller.state.value.currentPositionMs)

        // Update position beyond duration: clamped to duration
        controller.updatePosition(150_000L)
        assertEquals(100_000L, controller.state.value.currentPositionMs)

        // Update position negative: clamped to 0
        controller.updatePosition(-5_000L)
        assertEquals(0L, controller.state.value.currentPositionMs)

        controller.close()
        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testPacketDispatcherRouting() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val jitterBuffer = AudioJitterBuffer()
        val scheduler = PlaybackClockScheduler(fakeClock)
        val coordinator = PlaybackCoordinator(
            isHost = false,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            scope = testScope
        )

        val controller = TransportController(
            isHost = false,
            coordinator = coordinator,
            scheduler = scheduler,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scope = testScope
        )

        assertTrue(controller.handlePacket(RoomBeatPacket.SessionStart("id", 1_000_000L, "LOCAL_FILE")))
        assertTrue(controller.handlePacket(RoomBeatPacket.SessionPause(1_000_000L)))
        assertTrue(controller.handlePacket(RoomBeatPacket.SessionSeek(50_000L, 1_000_000L)))
        assertTrue(controller.handlePacket(RoomBeatPacket.SessionStop(1_000_000L)))

        // Unknown / unhandled packet
        assertFalse(controller.handlePacket(RoomBeatPacket.RoomJoin("123456")))

        controller.close()
        coordinator.close()
        jitterBuffer.close()
    }
}
