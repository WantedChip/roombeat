package com.roombeat.app.source.spotify

/**
 * Constants for Spotify App Remote SDK integration, authentication, and package resolution.
 */
object SpotifyConstants {

    /**
     * Official Spotify Android package name required for package visibility queries
     * and Play Store intent resolution.
     */
    const val SPOTIFY_PACKAGE_NAME: String = "com.spotify.music"

    /**
     * Default Client ID for RoomBeat registered with Spotify Developer Dashboard.
     * Can be overridden per instance in [SpotifyRemoteManager].
     */
    const val DEFAULT_CLIENT_ID: String = "roombeat-android-client"

    /**
     * Custom redirect URI matching the AndroidManifest intent-filter.
     */
    const val DEFAULT_REDIRECT_URI: String = "roombeat://spotify-callback"

    /**
     * Deep-link URI to open Spotify on Google Play Store.
     */
    const val PLAY_STORE_URI: String = "market://details?id=com.spotify.music"

    /**
     * Fallback web URL for Spotify on Google Play Store when market:// is unhandled.
     */
    const val PLAY_STORE_WEB_URL: String = "https://play.google.com/store/apps/details?id=com.spotify.music"

    /**
     * Default IPC connection timeout for pre-warming in milliseconds.
     */
    const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000L
}
