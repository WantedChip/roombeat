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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for [PlaybackCoordinator] verifying host playback initiation, T_target presentation
 * calculation (T_now + 350ms), SESSION_START broadcasting, pre-buffering frame chunking,
 * peer clock offset timestamp adjustments (T_target + theta), and state machine transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {

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

    private fun createSyntheticFrame(seq: Long, value: Float = 0.5f): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = (seq and 0xFF).toByte()
        bytes[1] = ((seq shr 8) and 0xFF).toByte()
        bytes[2] = (value * 100).toInt().toByte()
        return bytes
    }

    @Test
    fun testHostStartPresentationCalculationAndPacketBroadcast() = runTest {
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

        assertEquals(PlaybackState.IDLE, coordinator.state.value)

        // Generate 6 pre-buffering frames (120ms of audio)
        val prebufferFrames = (0 until 6).map { createSyntheticFrame(it.toLong(), 0.6f) }

        val targetPresentationTimeUs = coordinator.startHostPlayback(
            mediaId = "track_123",
            sourceType = "LOCAL_FILE",
            prebufferPackets = prebufferFrames
        )

        // 1. Verify T_target calculation: T_now (1,000,000) + 350,000us = 1,350,000us
        assertEquals(1_350_000L, targetPresentationTimeUs)
        assertEquals(1_350_000L, coordinator.targetPresentationTimeUs.value)
        assertEquals("track_123", coordinator.currentMediaId.value)
        assertEquals("LOCAL_FILE", coordinator.currentSourceType.value)

        // 2. Verify SESSION_START packet broadcast
        assertEquals(1, fakeTransport.broadcastPackets.size)
        val startPacket = fakeTransport.broadcastPackets[0] as RoomBeatPacket.SessionStart
        assertEquals("track_123", startPacket.mediaId)
        assertEquals(1_350_000L, startPacket.targetPresentationTime)
        assertEquals("LOCAL_FILE", startPacket.sourceType)

        // 3. Verify jitter buffer armed with target start time and 6 frames pre-buffered
        assertEquals(1_350_000L, jitterBuffer.targetStartTimeUs)
        assertEquals(6, jitterBuffer.queuedFrames)

        // 4. Verify state transitioned to BUFFERING while awaiting countdown
        assertEquals(PlaybackState.BUFFERING, coordinator.state.value)

        // Try pulling audio: target timestamp has NOT arrived yet -> must output silence!
        val outPcm = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        jitterBuffer.pullFrames(outPcm, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        for (sample in outPcm) {
            assertEquals("Silence must be rendered while awaiting T_target", 0.0f, sample, 0.0001f)
        }

        // 5. Advance clock and coroutines by 350ms to reach T_target
        fakeClock.advanceMicros(350_000L)
        testScope.advanceTimeBy(350L)
        testScope.advanceUntilIdle()

        // 6. Verify transition to PLAYING
        assertEquals(PlaybackState.PLAYING, coordinator.state.value)

        // Now pull frames: jitter buffer unblocks and renders audio!
        val rendered = jitterBuffer.pullFrames(outPcm, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        assertEquals(JitterBufferConstants.SAMPLES_PER_CHANNEL, rendered)
        assertTrue("Audio rendering must begin once T_target arrives", jitterBuffer.getStats().packetsPlayed > 0)

        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testClientStartAdjustsTargetWithClockOffset() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 5_000_000_000L) // 5,000,000 us local time
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 120)
        jitterBuffer.setClockFunction { fakeClock.nowMicros() }

        val scheduler = PlaybackClockScheduler(fakeClock)

        // Peer is 25ms (25,000us) ahead of host clock (offset theta = +25,000us)
        val peerClockOffsetMicros = 25_000L

        val coordinator = PlaybackCoordinator(
            isHost = false,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            clockOffsetMicros = peerClockOffsetMicros,
            scope = testScope
        )

        val hostTargetTimeUs = 5_350_000L // Host scheduled target
        val expectedLocalTargetTimeUs = hostTargetTimeUs + peerClockOffsetMicros // 5,375,000us

        val sessionStartPacket = RoomBeatPacket.SessionStart(
            mediaId = "track_456",
            targetPresentationTime = hostTargetTimeUs,
            sourceType = "LOCAL_FILE"
        )

        coordinator.handleSessionStart(sessionStartPacket)

        // Verify adjusted local target presentation timestamp
        assertEquals(expectedLocalTargetTimeUs, coordinator.targetPresentationTimeUs.value)
        assertEquals(expectedLocalTargetTimeUs, jitterBuffer.targetStartTimeUs)
        assertEquals(PlaybackState.BUFFERING, coordinator.state.value)

        // Ingest 6 audio chunks sent from host
        for (i in 0 until 6) {
            val chunkPresentationTimeHost = hostTargetTimeUs + (i * 20_000L)
            val chunkBytes = createSyntheticFrame(i.toLong(), 0.5f)
            coordinator.pushIncomingChunk(
                seq = i.toLong(),
                presentationTimeUs = chunkPresentationTimeHost,
                opusData = chunkBytes
            )
        }
        assertEquals(6, jitterBuffer.queuedFrames)

        // While clock is at 5,000,000us (< 5,375,000us), audio is blocked
        val outPcm = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        jitterBuffer.pullFrames(outPcm, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        for (sample in outPcm) {
            assertEquals(0.0f, sample, 0.0001f)
        }

        // Advance local clock to local target time: 5,375,000us (+375,000us)
        fakeClock.advanceMicros(375_000L)
        testScope.advanceTimeBy(375L)
        testScope.advanceUntilIdle()

        assertEquals(PlaybackState.PLAYING, coordinator.state.value)

        // Now pull frames: unblocks and plays
        val rendered = jitterBuffer.pullFrames(outPcm, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        assertEquals(JitterBufferConstants.SAMPLES_PER_CHANNEL, rendered)
        assertTrue(jitterBuffer.getStats().packetsPlayed > 0)

        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testHandleAudioChunkWithBase64Payload() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 20)
        val scheduler = PlaybackClockScheduler(fakeClock)

        val coordinator = PlaybackCoordinator(
            isHost = false,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler,
            clockOffsetMicros = 10_000L // +10ms offset
        )

        val rawBytes = byteArrayOf(1, 2, 3, 4, 5)
        val base64Payload = Base64.getEncoder().encodeToString(rawBytes)

        val chunkPacket = RoomBeatPacket.AudioChunk(
            seq = 0L,
            opusFrame = base64Payload,
            targetPresentationTime = 1_100_000L
        )

        coordinator.handleAudioChunk(chunkPacket)
        assertEquals(1, jitterBuffer.queuedFrames)
        assertEquals(1L, coordinator.receivedChunkCount.value)

        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testTransportControlsPauseAndStop() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 20)
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

        coordinator.startHostPlayback(mediaId = "track_1")
        assertEquals(PlaybackState.BUFFERING, coordinator.state.value)

        coordinator.pause()
        assertEquals(PlaybackState.PAUSED, coordinator.state.value)
        assertTrue(fakeTransport.broadcastPackets.any { it is RoomBeatPacket.SessionPause })

        coordinator.resume()
        assertEquals(PlaybackState.PLAYING, coordinator.state.value)

        coordinator.stop()
        assertEquals(PlaybackState.STOPPED, coordinator.state.value)
        assertEquals(0, jitterBuffer.queuedFrames)
        assertTrue(fakeTransport.broadcastPackets.any { it is RoomBeatPacket.SessionStop })

        coordinator.reset()
        assertEquals(PlaybackState.IDLE, coordinator.state.value)

        coordinator.close()
        jitterBuffer.close()
    }

    @Test
    fun testPolymorphicPacketDispatch() {
        val fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L)
        val jitterBuffer = AudioJitterBuffer(targetDepthMs = 20)
        val scheduler = PlaybackClockScheduler(fakeClock)

        val coordinator = PlaybackCoordinator(
            isHost = false,
            clock = fakeClock,
            jitterBuffer = jitterBuffer,
            scheduler = scheduler
        )

        val start = RoomBeatPacket.SessionStart("test", 1_350_000L, "LOCAL_FILE")
        assertTrue(coordinator.handlePacket(start))
        assertEquals(PlaybackState.BUFFERING, coordinator.state.value)

        val pause = RoomBeatPacket.SessionPause(1_200_000L)
        assertTrue(coordinator.handlePacket(pause))
        assertEquals(PlaybackState.PAUSED, coordinator.state.value)

        val stop = RoomBeatPacket.SessionStop(1_300_000L)
        assertTrue(coordinator.handlePacket(stop))
        assertEquals(PlaybackState.STOPPED, coordinator.state.value)

        val unhandled = RoomBeatPacket.PeerPing(100L)
        assertFalse(coordinator.handlePacket(unhandled))

        coordinator.close()
        jitterBuffer.close()
    }
}
