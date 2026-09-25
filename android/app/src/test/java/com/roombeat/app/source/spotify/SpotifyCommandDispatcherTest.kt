package com.roombeat.app.source.spotify

import com.roombeat.app.audio.PlaybackClockScheduler
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyCommandDispatcherTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeClock: FakeMonotonicClock
    private lateinit var scheduler: PlaybackClockScheduler
    private lateinit var fakePlayerApi: FakeSpotifyPlayerApi

    private val broadcastedPackets = mutableListOf<RoomBeatPacket>()
    private var premiumRequiredException: SpotifyPremiumRequiredException? = null
    private var playbackError: Throwable? = null

    @Before
    fun setUp() {
        fakeClock = FakeMonotonicClock(initialNanos = 10_000_000_000L) // 10s monotonic
        scheduler = PlaybackClockScheduler(clock = fakeClock)
        fakePlayerApi = FakeSpotifyPlayerApi(isPremium = true)
        broadcastedPackets.clear()
        premiumRequiredException = null
        playbackError = null
    }

    private fun createDispatcher(
        isHost: Boolean = true,
        clockOffsetMicros: Long = 0L,
        playerApi: SpotifyPlayerApiFacade? = fakePlayerApi
    ): SpotifyCommandDispatcher {
        return SpotifyCommandDispatcher(
            isHost = isHost,
            customPlayerApi = playerApi,
            clock = fakeClock,
            scheduler = scheduler,
            clockOffsetMicros = clockOffsetMicros,
            broadcaster = { packet ->
                broadcastedPackets.add(packet)
            },
            coroutineDispatcher = testDispatcher,
            scope = testScope,
            onPremiumRequired = { ex ->
                premiumRequiredException = ex
            },
            onPlaybackError = { err ->
                playbackError = err
            }
        )
    }

    @Test
    fun testHostDispatchPlaySchedulesAt400msLeadTimeAndBroadcasts() = testScope.runTest {
        val dispatcher = createDispatcher(isHost = true)

        val trackUri = "spotify:track:0DiWol3AO6WpXZgp0goxAV"
        val startNanos = fakeClock.nowNanos()
        val startMicros = fakeClock.nowMicros()
        val expectedTargetUs = startMicros + SpotifyCommandDispatcher.DEFAULT_LEAD_TIME_MICROS

        dispatcher.dispatchPlay(trackUri, startPositionMs = 3500L)
        advanceTimeBy(1)

        // Broadcasted packet verification
        assertEquals(1, broadcastedPackets.size)
        val packet = broadcastedPackets.first() as RoomBeatPacket.SpotifyCmd
        assertEquals(RoomBeatPacket.SpotifyCmd.CMD_PLAY, packet.command)
        assertEquals(trackUri, packet.trackUri)
        assertEquals(3500L, packet.targetPositionMs)
        assertEquals(expectedTargetUs, packet.targetPresentationTime)

        // At T_now: play then immediate pause must be issued to prime the audio buffer
        assertTrue(fakePlayerApi.callHistory.contains("play:$trackUri"))
        assertTrue(fakePlayerApi.callHistory.contains("pause"))
        assertTrue(dispatcher.playbackState.value is SpotifyPlaybackState.Buffering)

        val buffering = dispatcher.playbackState.value as SpotifyPlaybackState.Buffering
        assertEquals(expectedTargetUs, buffering.targetPresentationTimeUs)
        assertEquals("PLAY", buffering.scheduledAction)

        // Advance clock to target timestamp
        fakeClock.advanceMicros(SpotifyCommandDispatcher.DEFAULT_LEAD_TIME_MICROS)
        advanceTimeBy(401)
        advanceUntilIdle()

        // Seek and resume must now have been called
        assertTrue(fakePlayerApi.callHistory.contains("seekTo:3500"))
        assertTrue(fakePlayerApi.callHistory.contains("resume"))
        assertTrue(dispatcher.playbackState.value is SpotifyPlaybackState.Playing)

        val playing = dispatcher.playbackState.value as SpotifyPlaybackState.Playing
        assertEquals(trackUri, playing.trackUri)
        assertEquals(3500L, playing.positionMs)

        dispatcher.close()
    }

    @Test
    fun testPeerHandleSpotifyCmdTranslatesClockOffset() = testScope.runTest {
        val clockOffsetUs = 35_000L // Peer is 35ms ahead of host
        val dispatcher = createDispatcher(isHost = false, clockOffsetMicros = clockOffsetUs)

        val trackUri = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b"
        val hostTargetUs = fakeClock.nowMicros() + 400_000L
        val expectedPeerLocalTargetUs = hostTargetUs + clockOffsetUs

        val packet = RoomBeatPacket.SpotifyCmd(
            trackUri = trackUri,
            targetPositionMs = 0L,
            targetPresentationTime = hostTargetUs,
            command = RoomBeatPacket.SpotifyCmd.CMD_PLAY
        )

        dispatcher.handleSpotifyCmd(packet)

        // At arrival: pre-buffering
        advanceTimeBy(1)
        assertTrue(fakePlayerApi.callHistory.contains("play:$trackUri"))
        assertTrue(fakePlayerApi.callHistory.contains("pause"))
        assertTrue(dispatcher.playbackState.value is SpotifyPlaybackState.Buffering)

        val buffering = dispatcher.playbackState.value as SpotifyPlaybackState.Buffering
        assertEquals(expectedPeerLocalTargetUs, buffering.targetPresentationTimeUs)

        // Advance clock to expectedPeerLocalTargetUs
        val deltaMicros = expectedPeerLocalTargetUs - fakeClock.nowMicros()
        fakeClock.advanceMicros(deltaMicros)
        advanceTimeBy(500)
        advanceUntilIdle()

        assertTrue(fakePlayerApi.callHistory.contains("resume"))
        assertTrue(dispatcher.playbackState.value is SpotifyPlaybackState.Playing)

        dispatcher.close()
    }

    @Test
    fun testTransportControlsPauseResumeSeekNextPrevious() = testScope.runTest {
        val dispatcher = createDispatcher(isHost = true)

        // 1. Initial play to set current track
        dispatcher.dispatchPlay("spotify:track:7tFiyTwD0nx5a1eklYtX2J")
        fakeClock.advanceMicros(400_000L)
        advanceTimeBy(401)
        advanceUntilIdle()
        fakePlayerApi.callHistory.clear()

        // 2. Pause
        dispatcher.dispatchPause()
        advanceTimeBy(1)
        assertEquals(2, broadcastedPackets.size)
        val pausePacket = broadcastedPackets.last() as RoomBeatPacket.SpotifyCmd
        assertEquals(RoomBeatPacket.SpotifyCmd.CMD_PAUSE, pausePacket.command)

        fakeClock.advanceMicros(400_000L)
        advanceTimeBy(401)
        advanceUntilIdle()
        assertTrue(fakePlayerApi.callHistory.contains("pause"))
        assertTrue(dispatcher.playbackState.value is SpotifyPlaybackState.Paused)
        fakePlayerApi.callHistory.clear()

        // 3. Resume
        dispatcher.dispatchResume()
        advanceTimeBy(1)
        assertEquals(3, broadcastedPackets.size)
        val resumePacket = broadcastedPackets.last() as RoomBeatPacket.SpotifyCmd
        assertEquals(RoomBeatPacket.SpotifyCmd.CMD_RESUME, resumePacket.command)

        fakeClock.advanceMicros(400_000L)
        advanceTimeBy(401)
        advanceUntilIdle()
        assertTrue(fakePlayerApi.callHistory.contains("resume"))
        assertTrue(dispatcher.playbackState.value is SpotifyPlaybackState.Playing)
        fakePlayerApi.callHistory.clear()

        // 4. Seek
        dispatcher.dispatchSeek(42_000L)
        advanceTimeBy(1)
        assertEquals(4, broadcastedPackets.size)
        val seekPacket = broadcastedPackets.last() as RoomBeatPacket.SpotifyCmd
        assertEquals(RoomBeatPacket.SpotifyCmd.CMD_SEEK, seekPacket.command)
        assertEquals(42_000L, seekPacket.targetPositionMs)

        fakeClock.advanceMicros(400_000L)
        advanceTimeBy(401)
        advanceUntilIdle()
        assertTrue(fakePlayerApi.callHistory.contains("seekTo:42000"))
        fakePlayerApi.callHistory.clear()

        // 5. Next
        dispatcher.dispatchNext()
        advanceTimeBy(1)
        assertEquals(5, broadcastedPackets.size)
        val nextPacket = broadcastedPackets.last() as RoomBeatPacket.SpotifyCmd
        assertEquals(RoomBeatPacket.SpotifyCmd.CMD_NEXT, nextPacket.command)

        fakeClock.advanceMicros(400_000L)
        advanceTimeBy(401)
        advanceUntilIdle()
        assertTrue(fakePlayerApi.callHistory.contains("skipNext"))
        fakePlayerApi.callHistory.clear()

        // 6. Previous
        dispatcher.dispatchPrevious()
        advanceTimeBy(1)
        assertEquals(6, broadcastedPackets.size)
        val prevPacket = broadcastedPackets.last() as RoomBeatPacket.SpotifyCmd
        assertEquals(RoomBeatPacket.SpotifyCmd.CMD_PREVIOUS, prevPacket.command)

        fakeClock.advanceMicros(400_000L)
        advanceTimeBy(401)
        advanceUntilIdle()
        assertTrue(fakePlayerApi.callHistory.contains("skipPrevious"))

        dispatcher.close()
    }

    @Test
    fun testNonPremiumErrorDetectionAndCallback() = testScope.runTest {
        fakePlayerApi.shouldFailWithPremium = true
        val dispatcher = createDispatcher(isHost = true)

        dispatcher.dispatchPlay("spotify:track:0ofHAoxe9vBkTCp2UQIavz")
        advanceUntilIdle()

        // Must detect non-premium limitation immediately upon play attempt
        assertNotNull("onPremiumRequired must be invoked", premiumRequiredException)
        assertTrue(dispatcher.playbackState.value is SpotifyPlaybackState.Error)

        val error = dispatcher.playbackState.value as SpotifyPlaybackState.Error
        assertTrue("Error state must be flagged as isPremiumRequired", error.isPremiumRequired)
        assertTrue(error.message.contains("Spotify Premium is required"))

        dispatcher.close()
    }

    @Test
    fun testNonPremiumErrorDetectionViaExceptionMessage() = testScope.runTest {
        fakePlayerApi.failureToEmit = RuntimeException("Remote protocol error: Playback not permitted on free tier account")
        val dispatcher = createDispatcher(isHost = true)

        dispatcher.dispatchPlay("spotify:track:0ofHAoxe9vBkTCp2UQIavz")
        advanceUntilIdle()

        assertNotNull("onPremiumRequired must be triggered via message classifier", premiumRequiredException)
        val error = dispatcher.playbackState.value as SpotifyPlaybackState.Error
        assertTrue(error.isPremiumRequired)

        dispatcher.close()
    }

    @Test
    fun testMissingPlayerApiTransitionsToError() = testScope.runTest {
        val dispatcher = createDispatcher(isHost = true, playerApi = null)

        dispatcher.dispatchPlay("spotify:track:0ofHAoxe9vBkTCp2UQIavz")
        advanceUntilIdle()

        assertTrue(dispatcher.playbackState.value is SpotifyPlaybackState.Error)
        val error = dispatcher.playbackState.value as SpotifyPlaybackState.Error
        assertFalse(error.isPremiumRequired)
        assertTrue(error.message.contains("PlayerApi is not connected"))
        assertNotNull(playbackError)

        dispatcher.close()
    }

    @Test
    fun testPacketDispatcherRegistrationReceivesCmd() = testScope.runTest {
        val packetDispatcher = PacketDispatcher()
        val cmdDispatcher = createDispatcher(isHost = false)

        val registration = cmdDispatcher.registerPacketHandler(packetDispatcher)

        val packet = RoomBeatPacket.SpotifyCmd(
            trackUri = "spotify:track:6GyFP1nfCDB87D2bGqw6v7",
            targetPositionMs = 0L,
            targetPresentationTime = fakeClock.nowMicros() + 400_000L,
            command = RoomBeatPacket.SpotifyCmd.CMD_PLAY
        )

        packetDispatcher.dispatch(packet, PacketContext(remoteAddress = "192.168.1.50"))
        advanceTimeBy(1)

        assertTrue(fakePlayerApi.callHistory.contains("play:spotify:track:6GyFP1nfCDB87D2bGqw6v7"))

        registration.unregister()
        cmdDispatcher.close()
    }
}
