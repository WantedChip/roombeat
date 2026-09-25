package com.roombeat.app.source.spotify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyPlayerStateTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakePlayerApi: FakeSpotifyPlayerApi

    @Before
    fun setUp() {
        fakePlayerApi = FakeSpotifyPlayerApi(isPremium = true)
    }

    @Test
    fun testSnapshotDefaultValues() {
        val snapshot = SpotifyPlayerStateSnapshot()
        assertEquals("", snapshot.trackUri)
        assertEquals(0L, snapshot.trackDurationMs)
        assertEquals(0L, snapshot.playbackPositionMs)
        assertEquals(1.0f, snapshot.playbackSpeed, 0.001f)
        assertTrue(snapshot.isPaused)
        assertFalse(snapshot.isBuffering)
        assertEquals("", snapshot.trackName)
        assertEquals("", snapshot.artistName)
    }

    @Test
    fun testSubscribeToPlayerStateEmitsInitialState() = testScope.runTest {
        val initialSnapshot = SpotifyPlayerStateSnapshot(
            trackUri = "spotify:track:initial123",
            trackDurationMs = 180_000L,
            playbackPositionMs = 15_000L,
            playbackSpeed = 1.0f,
            isPaused = false,
            trackName = "Test Song",
            artistName = "Test Artist"
        )
        val playerApi = FakeSpotifyPlayerApi(initialState = initialSnapshot)

        val state = playerApi.subscribeToPlayerState().first()
        assertEquals("spotify:track:initial123", state.trackUri)
        assertEquals(180_000L, state.trackDurationMs)
        assertEquals(15_000L, state.playbackPositionMs)
        assertFalse(state.isPaused)
        assertEquals("Test Song", state.trackName)
        assertEquals("Test Artist", state.artistName)
    }

    @Test
    fun testEmitPlayerStateUpdatesFlowAndPlayerState() = testScope.runTest {
        val updatedSnapshot = SpotifyPlayerStateSnapshot(
            trackUri = "spotify:track:updated456",
            trackDurationMs = 240_000L,
            playbackPositionMs = 45_000L,
            playbackSpeed = 1.25f,
            isPaused = false,
            isBuffering = false,
            trackName = "Updated Song",
            artistName = "Updated Artist"
        )

        fakePlayerApi.emitPlayerState(updatedSnapshot)

        val state = fakePlayerApi.subscribeToPlayerState().first()
        assertEquals("spotify:track:updated456", state.trackUri)
        assertEquals(240_000L, state.trackDurationMs)
        assertEquals(45_000L, state.playbackPositionMs)
        assertEquals(1.25f, state.playbackSpeed, 0.001f)
        assertFalse(state.isPaused)
        assertEquals(45_000L, fakePlayerApi.currentPositionMs)
        assertTrue(fakePlayerApi.isPlaying)
        assertEquals("spotify:track:updated456", fakePlayerApi.lastPlayedUri)
    }

    @Test
    fun testPlayPauseResumeSeekUpdatesPlayerStateFlow() = testScope.runTest {
        val trackUri = "spotify:track:lifecycle789"

        // Play
        fakePlayerApi.play(trackUri)
        var state = fakePlayerApi.subscribeToPlayerState().first()
        assertEquals(trackUri, state.trackUri)
        assertFalse(state.isPaused)
        assertEquals(0L, state.playbackPositionMs)

        // Pause
        fakePlayerApi.pause()
        state = fakePlayerApi.subscribeToPlayerState().first()
        assertTrue(state.isPaused)

        // Seek
        fakePlayerApi.seekTo(30_000L)
        state = fakePlayerApi.subscribeToPlayerState().first()
        assertEquals(30_000L, state.playbackPositionMs)

        // Resume
        fakePlayerApi.resume()
        state = fakePlayerApi.subscribeToPlayerState().first()
        assertFalse(state.isPaused)
        assertEquals(30_000L, state.playbackPositionMs)

        // Skip Next
        fakePlayerApi.skipNext()
        state = fakePlayerApi.subscribeToPlayerState().first()
        assertEquals(0L, state.playbackPositionMs)

        // Skip Previous
        fakePlayerApi.skipPrevious()
        state = fakePlayerApi.subscribeToPlayerState().first()
        assertEquals(0L, state.playbackPositionMs)
    }

    @Test
    fun testGetPlayerStateReturnsCurrentSnapshot() = testScope.runTest {
        val snapshot = SpotifyPlayerStateSnapshot(
            trackUri = "spotify:track:directQuery",
            playbackPositionMs = 99_000L,
            isPaused = false
        )
        fakePlayerApi.emitPlayerState(snapshot)

        val result = fakePlayerApi.getPlayerState()
        assertTrue(result.isSuccess)
        val queryState = result.getOrNull()!!
        assertEquals("spotify:track:directQuery", queryState.trackUri)
        assertEquals(99_000L, queryState.playbackPositionMs)
        assertFalse(queryState.isPaused)
    }
}
