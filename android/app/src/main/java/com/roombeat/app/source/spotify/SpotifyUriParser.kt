package com.roombeat.app.source.spotify

/**
 * Supported Spotify content types.
 */
enum class SpotifyContentType {
    TRACK,
    ALBUM,
    PLAYLIST,
    ARTIST,
    UNKNOWN;

    companion object {
        fun fromTag(tag: String): SpotifyContentType {
            return when (tag.lowercase()) {
                "track" -> TRACK
                "album" -> ALBUM
                "playlist" -> PLAYLIST
                "artist" -> ARTIST
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Representation of a selectable Spotify track for rapid testing and browsing in the UI.
 */
data class SpotifyTrackItem(
    val title: String,
    val artist: String,
    val uri: String,
    val durationMs: Long = 0L,
    val albumCoverUrl: String? = null
)

/**
 * Curated list of verified sample Spotify tracks for rapid tactile testing and room verification.
 */
val SAMPLE_SPOTIFY_TRACKS: List<SpotifyTrackItem> = listOf(
    SpotifyTrackItem(
        title = "One More Time",
        artist = "Daft Punk",
        uri = "spotify:track:0DiWol3AO6WpXZgp0goxAV",
        durationMs = 320_000L
    ),
    SpotifyTrackItem(
        title = "Blinding Lights",
        artist = "The Weeknd",
        uri = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b",
        durationMs = 200_000L
    ),
    SpotifyTrackItem(
        title = "Bohemian Rhapsody",
        artist = "Queen",
        uri = "spotify:track:7tFiyTwD0nx5a1eklYtX2J",
        durationMs = 354_000L
    ),
    SpotifyTrackItem(
        title = "Dreams - 2004 Remaster",
        artist = "Fleetwood Mac",
        uri = "spotify:track:0ofHAoxe9vBkTCp2UQIavz",
        durationMs = 257_000L
    ),
    SpotifyTrackItem(
        title = "Midnight City",
        artist = "M83",
        uri = "spotify:track:6GyFP1nfCDB87D2bGqw6v7",
        durationMs = 243_000L
    )
)

/**
 * Result of parsing and validating a Spotify URI or Web URL.
 */
sealed interface SpotifyUriResult {
    data class Success(
        val canonicalUri: String,
        val type: SpotifyContentType,
        val id: String
    ) : SpotifyUriResult

    data class Invalid(
        val reason: String
    ) : SpotifyUriResult
}

/**
 * High-performance parser and validator for Spotify URIs and Web URLs.
 *
 * Supported formats:
 * 1. Spotify URI: `spotify:track:0DiWol3AO6WpXZgp0goxAV`
 * 2. Spotify Web URL: `https://open.spotify.com/track/0DiWol3AO6WpXZgp0goxAV?si=xyz123`
 * 3. Localized Web URL: `https://open.spotify.com/intl-ja/track/0DiWol3AO6WpXZgp0goxAV`
 * 4. Raw 22-char base62 ID: `0DiWol3AO6WpXZgp0goxAV` (normalized to `spotify:track:0DiWol3AO6WpXZgp0goxAV`)
 */
object SpotifyUriParser {

    private val SPOTIFY_URI_REGEX = Regex("""^spotify:(track|album|playlist|artist):([a-zA-Z0-9]{22})$""")
    private val SPOTIFY_URL_REGEX = Regex("""^https?://open\.spotify\.com/(?:[a-zA-Z]{2,4}-[a-zA-Z]{2,4}/)?(track|album|playlist|artist)/([a-zA-Z0-9]{22})(?:[/?#].*)?$""")
    private val RAW_ID_REGEX = Regex("""^[a-zA-Z0-9]{22}$""")

    /**
     * Parses the incoming input string into a structured [SpotifyUriResult].
     */
    fun parse(input: String?): SpotifyUriResult {
        if (input.isNullOrBlank()) {
            return SpotifyUriResult.Invalid("Input cannot be empty")
        }

        val trimmed = input.trim()

        // 1. Check native Spotify URI format: spotify:{type}:{id}
        val uriMatch = SPOTIFY_URI_REGEX.matchEntire(trimmed)
        if (uriMatch != null) {
            val typeTag = uriMatch.groupValues[1]
            val id = uriMatch.groupValues[2]
            val type = SpotifyContentType.fromTag(typeTag)
            return SpotifyUriResult.Success(
                canonicalUri = "spotify:$typeTag:$id",
                type = type,
                id = id
            )
        }

        // 2. Check open.spotify.com URL format
        val urlMatch = SPOTIFY_URL_REGEX.matchEntire(trimmed)
        if (urlMatch != null) {
            val typeTag = urlMatch.groupValues[1]
            val id = urlMatch.groupValues[2]
            val type = SpotifyContentType.fromTag(typeTag)
            return SpotifyUriResult.Success(
                canonicalUri = "spotify:$typeTag:$id",
                type = type,
                id = id
            )
        }

        // 3. Check raw 22-character ID format (assume track by default)
        val rawIdMatch = RAW_ID_REGEX.matchEntire(trimmed)
        if (rawIdMatch != null) {
            val id = rawIdMatch.value
            return SpotifyUriResult.Success(
                canonicalUri = "spotify:track:$id",
                type = SpotifyContentType.TRACK,
                id = id
            )
        }

        return SpotifyUriResult.Invalid("Unrecognized Spotify URI or URL format: '$trimmed'")
    }

    /**
     * Checks if the given string is a valid Spotify URI or URL.
     */
    fun isValidSpotifyUri(input: String?): Boolean {
        return parse(input) is SpotifyUriResult.Success
    }

    /**
     * Checks if the given string points specifically to a valid track.
     */
    fun isValidTrackUri(input: String?): Boolean {
        val result = parse(input)
        return result is SpotifyUriResult.Success && result.type == SpotifyContentType.TRACK
    }

    /**
     * Normalizes the input into a standard `spotify:{type}:{id}` URI, or null if invalid.
     */
    fun normalizeToSpotifyUri(input: String?): String? {
        val result = parse(input)
        return (result as? SpotifyUriResult.Success)?.canonicalUri
    }

    /**
     * Extracts the 22-character track ID from the input if valid, or null otherwise.
     */
    fun extractTrackId(input: String?): String? {
        val result = parse(input)
        return (result as? SpotifyUriResult.Success)?.id
    }
}
