package com.roombeat.app.ui.components

import com.roombeat.app.source.spotify.SAMPLE_SPOTIFY_TRACKS
import com.roombeat.app.source.spotify.SpotifyTrackItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SpotifyTrackSelectorSheetState] verifying visibility lifecycle,
 * URI input binding, validation reactivity, track selection, and premium warning handling.
 */
class SpotifyTrackSelectorSheetTest {

    @Test
    fun testInitialState() {
        val state = SpotifyTrackSelectorSheetState(initialOpen = false, initialUri = "")

        assertFalse(state.isOpen)
        assertEquals("", state.inputUri)
        assertFalse(state.isValid)
        assertNull(state.canonicalUri)
        assertFalse(state.isPremiumWarningVisible)
        assertNull(state.premiumWarningMessage)
        assertEquals(SAMPLE_SPOTIFY_TRACKS, state.recentTracks)
    }

    @Test
    fun testOpenAndDismiss() {
        val state = SpotifyTrackSelectorSheetState()

        state.open()
        assertTrue(state.isOpen)

        state.dismiss()
        assertFalse(state.isOpen)
    }

    @Test
    fun testSetInputWithValidUri() {
        val state = SpotifyTrackSelectorSheetState()

        state.setInput("spotify:track:0DiWol3AO6WpXZgp0goxAV")
        assertEquals("spotify:track:0DiWol3AO6WpXZgp0goxAV", state.inputUri)
        assertTrue(state.isValid)
        assertEquals("spotify:track:0DiWol3AO6WpXZgp0goxAV", state.canonicalUri)
    }

    @Test
    fun testSetInputWithWebUrlNormalizesCanonicalUri() {
        val state = SpotifyTrackSelectorSheetState()

        state.setInput("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=test12345")
        assertTrue(state.isValid)
        assertEquals("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", state.canonicalUri)
    }

    @Test
    fun testSetInputWithInvalidFormat() {
        val state = SpotifyTrackSelectorSheetState()

        state.setInput("not_a_valid_uri")
        assertFalse(state.isValid)
        assertNull(state.canonicalUri)
    }

    @Test
    fun testClearInput() {
        val state = SpotifyTrackSelectorSheetState(initialUri = "spotify:track:0DiWol3AO6WpXZgp0goxAV")
        assertTrue(state.isValid)

        state.clearInput()
        assertEquals("", state.inputUri)
        assertFalse(state.isValid)
        assertNull(state.canonicalUri)
    }

    @Test
    fun testSelectSampleTrack() {
        val state = SpotifyTrackSelectorSheetState()
        val sample = SpotifyTrackItem(
            title = "Test Track",
            artist = "Test Artist",
            uri = "spotify:track:7tFiyTwD0nx5a1eklYtX2J"
        )

        state.selectTrack(sample)
        assertEquals(sample.uri, state.inputUri)
        assertTrue(state.isValid)
        assertEquals(sample.uri, state.canonicalUri)
    }

    @Test
    fun testPremiumWarningVisibility() {
        val state = SpotifyTrackSelectorSheetState()

        state.showPremiumWarning("Spotify Free accounts are restricted from on-demand multi-device playback.")
        assertTrue(state.isPremiumWarningVisible)
        assertNotNull(state.premiumWarningMessage)
        assertTrue(state.premiumWarningMessage!!.contains("Spotify Free"))

        state.dismissPremiumWarning()
        assertFalse(state.isPremiumWarningVisible)
        assertNull(state.premiumWarningMessage)
    }
}
