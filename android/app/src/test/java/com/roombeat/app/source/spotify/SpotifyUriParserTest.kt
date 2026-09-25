package com.roombeat.app.source.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SpotifyUriParser] validating track, album, playlist, artist URI parsing,
 * web URL conversion, raw ID normalization, and sample track catalog integrity.
 */
class SpotifyUriParserTest {

    @Test
    fun testValidSpotifyTrackUri() {
        val input = "spotify:track:0DiWol3AO6WpXZgp0goxAV"
        val result = SpotifyUriParser.parse(input)

        assertTrue(result is SpotifyUriResult.Success)
        val success = result as SpotifyUriResult.Success
        assertEquals("spotify:track:0DiWol3AO6WpXZgp0goxAV", success.canonicalUri)
        assertEquals(SpotifyContentType.TRACK, success.type)
        assertEquals("0DiWol3AO6WpXZgp0goxAV", success.id)
        assertTrue(SpotifyUriParser.isValidSpotifyUri(input))
        assertTrue(SpotifyUriParser.isValidTrackUri(input))
        assertEquals("spotify:track:0DiWol3AO6WpXZgp0goxAV", SpotifyUriParser.normalizeToSpotifyUri(input))
        assertEquals("0DiWol3AO6WpXZgp0goxAV", SpotifyUriParser.extractTrackId(input))
    }

    @Test
    fun testValidSpotifyAlbumUri() {
        val input = "spotify:album:4aawyAB9vmqN3uQ7FjRGTy"
        val result = SpotifyUriParser.parse(input)

        assertTrue(result is SpotifyUriResult.Success)
        val success = result as SpotifyUriResult.Success
        assertEquals("spotify:album:4aawyAB9vmqN3uQ7FjRGTy", success.canonicalUri)
        assertEquals(SpotifyContentType.ALBUM, success.type)
        assertEquals("4aawyAB9vmqN3uQ7FjRGTy", success.id)
        assertTrue(SpotifyUriParser.isValidSpotifyUri(input))
        assertFalse(SpotifyUriParser.isValidTrackUri(input))
    }

    @Test
    fun testValidSpotifyPlaylistUri() {
        val input = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        val result = SpotifyUriParser.parse(input)

        assertTrue(result is SpotifyUriResult.Success)
        val success = result as SpotifyUriResult.Success
        assertEquals("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", success.canonicalUri)
        assertEquals(SpotifyContentType.PLAYLIST, success.type)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", success.id)
        assertTrue(SpotifyUriParser.isValidSpotifyUri(input))
        assertFalse(SpotifyUriParser.isValidTrackUri(input))
    }

    @Test
    fun testValidSpotifyArtistUri() {
        val input = "spotify:artist:4tZwfgrHOc3mvqYxwDOCn1"
        val result = SpotifyUriParser.parse(input)

        assertTrue(result is SpotifyUriResult.Success)
        val success = result as SpotifyUriResult.Success
        assertEquals("spotify:artist:4tZwfgrHOc3mvqYxwDOCn1", success.canonicalUri)
        assertEquals(SpotifyContentType.ARTIST, success.type)
        assertEquals("4tZwfgrHOc3mvqYxwDOCn1", success.id)
        assertTrue(SpotifyUriParser.isValidSpotifyUri(input))
        assertFalse(SpotifyUriParser.isValidTrackUri(input))
    }

    @Test
    fun testValidWebUrlWithQueryParams() {
        val url = "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=abc12345def67890"
        val result = SpotifyUriParser.parse(url)

        assertTrue(result is SpotifyUriResult.Success)
        val success = result as SpotifyUriResult.Success
        assertEquals("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", success.canonicalUri)
        assertEquals(SpotifyContentType.TRACK, success.type)
        assertEquals("0VjIjW4GlUZAMYd2vXMi3b", success.id)
        assertTrue(SpotifyUriParser.isValidSpotifyUri(url))
        assertTrue(SpotifyUriParser.isValidTrackUri(url))
    }

    @Test
    fun testValidWebUrlWithLocalizedLocalePrefix() {
        val url = "https://open.spotify.com/intl-ja/track/7tFiyTwD0nx5a1eklYtX2J"
        val result = SpotifyUriParser.parse(url)

        assertTrue(result is SpotifyUriResult.Success)
        val success = result as SpotifyUriResult.Success
        assertEquals("spotify:track:7tFiyTwD0nx5a1eklYtX2J", success.canonicalUri)
        assertEquals(SpotifyContentType.TRACK, success.type)
        assertEquals("7tFiyTwD0nx5a1eklYtX2J", success.id)
    }

    @Test
    fun testRaw22CharacterTrackId() {
        val rawId = "0ofHAoxe9vBkTCp2UQIavz"
        val result = SpotifyUriParser.parse(rawId)

        assertTrue(result is SpotifyUriResult.Success)
        val success = result as SpotifyUriResult.Success
        assertEquals("spotify:track:0ofHAoxe9vBkTCp2UQIavz", success.canonicalUri)
        assertEquals(SpotifyContentType.TRACK, success.type)
        assertEquals("0ofHAoxe9vBkTCp2UQIavz", success.id)
    }

    @Test
    fun testInvalidFormats() {
        val invalidInputs = listOf(
            "",
            "   ",
            null,
            "not a spotify url",
            "https://youtube.com/watch?v=12345",
            "spotify:track:shortId",
            "spotify:track:wayTooLongIdWithMoreThanTwentyTwoCharacters123456",
            "spotify:unsupported:0DiWol3AO6WpXZgp0goxAV",
            "http://otherdomain.com/track/0DiWol3AO6WpXZgp0goxAV"
        )

        for (input in invalidInputs) {
            val result = SpotifyUriParser.parse(input)
            assertTrue("Expected Invalid for input: '$input'", result is SpotifyUriResult.Invalid)
            assertFalse("Expected isValid to be false for input: '$input'", SpotifyUriParser.isValidSpotifyUri(input))
            assertFalse("Expected isValidTrack to be false for input: '$input'", SpotifyUriParser.isValidTrackUri(input))
            assertNull("Expected normalize to return null for input: '$input'", SpotifyUriParser.normalizeToSpotifyUri(input))
            assertNull("Expected extractTrackId to return null for input: '$input'", SpotifyUriParser.extractTrackId(input))
        }
    }

    @Test
    fun testSampleTracksListIntegrity() {
        assertTrue("SAMPLE_SPOTIFY_TRACKS must not be empty", SAMPLE_SPOTIFY_TRACKS.isNotEmpty())

        for (track in SAMPLE_SPOTIFY_TRACKS) {
            assertTrue("Title should not be blank for ${track.uri}", track.title.isNotBlank())
            assertTrue("Artist should not be blank for ${track.uri}", track.artist.isNotBlank())
            assertTrue("URI should be valid track: ${track.uri}", SpotifyUriParser.isValidTrackUri(track.uri))
            assertTrue("Duration should be positive for ${track.uri}", track.durationMs > 0L)
        }
    }
}
