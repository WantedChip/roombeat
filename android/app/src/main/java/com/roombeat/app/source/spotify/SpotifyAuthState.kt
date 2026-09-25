package com.roombeat.app.source.spotify

/**
 * Categorized error types for Spotify App Remote authentication, IPC connection,
 * and pre-warm handshakes.
 */
enum class SpotifyAuthErrorType {
    /**
     * Spotify Android application is not installed on this device.
     * Corresponds to [com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp].
     */
    APP_NOT_INSTALLED,

    /**
     * Spotify is installed, but no user is currently authenticated/logged into the Spotify app.
     * Corresponds to [com.spotify.android.appremote.api.error.NotLoggedInException].
     */
    NOT_LOGGED_IN,

    /**
     * User explicitly denied or dismissed the authorization consent screen.
     * Corresponds to [com.spotify.android.appremote.api.error.UserNotAuthorizedException].
     */
    USER_NOT_AUTHORIZED,

    /**
     * Authentication failed due to invalid credentials, mismatched client ID, or signature failure.
     * Corresponds to [com.spotify.android.appremote.api.error.AuthenticationFailedException].
     */
    AUTHENTICATION_FAILED,

    /**
     * Spotify client is running in offline mode.
     * Corresponds to [com.spotify.android.appremote.api.error.OfflineModeException].
     */
    OFFLINE_MODE,

    /**
     * The IPC service connection to Spotify App Remote was abruptly terminated.
     * Corresponds to [com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException]
     * or [com.spotify.android.appremote.api.error.SpotifyDisconnectedException].
     */
    CONNECTION_TERMINATED,

    /**
     * The IPC handshake or connection request timed out before receiving a response.
     */
    TIMEOUT,

    /**
     * An unrecognized or general I/O or platform exception occurred.
     */
    UNKNOWN
}

/**
 * Sealed hierarchy modeling Spotify App Remote authentication, IPC connection,
 * and warm-up handshake states.
 */
sealed interface SpotifyAuthState {

    /**
     * Initial or disconnected state. No active App Remote IPC connection exists.
     */
    data object Disconnected : SpotifyAuthState

    /**
     * Pre-warming IPC handshake or authorization flow is actively in progress.
     */
    data class Connecting(
        val startedAtMs: Long = System.currentTimeMillis()
    ) : SpotifyAuthState

    /**
     * Successfully authenticated and connected to Spotify App Remote via local IPC.
     * The session is pre-warmed and ready to receive command sync or control playback.
     */
    data class Connected(
        val connectedAtMs: Long = System.currentTimeMillis()
    ) : SpotifyAuthState

    /**
     * An error occurred during the authentication, connection, or pre-warming phase.
     */
    data class Error(
        val errorType: SpotifyAuthErrorType,
        val message: String,
        val cause: Throwable? = null,
        val timestampMs: Long = System.currentTimeMillis()
    ) : SpotifyAuthState {
        val isAppMissing: Boolean get() = errorType == SpotifyAuthErrorType.APP_NOT_INSTALLED
        val isAuthDenied: Boolean get() = errorType == SpotifyAuthErrorType.USER_NOT_AUTHORIZED || errorType == SpotifyAuthErrorType.AUTHENTICATION_FAILED
        val isNotLoggedIn: Boolean get() = errorType == SpotifyAuthErrorType.NOT_LOGGED_IN
        val isTimeout: Boolean get() = errorType == SpotifyAuthErrorType.TIMEOUT
        val isRecoverableWithRetry: Boolean get() = errorType != SpotifyAuthErrorType.APP_NOT_INSTALLED
    }
}

/**
 * Convenience extension: returns true if currently connected and pre-warmed.
 */
val SpotifyAuthState.isConnected: Boolean
    get() = this is SpotifyAuthState.Connected

/**
 * Convenience extension: returns true if currently connecting or pre-warming.
 */
val SpotifyAuthState.isConnecting: Boolean
    get() = this is SpotifyAuthState.Connecting

/**
 * Convenience extension: returns true if an error state is active.
 */
val SpotifyAuthState.isError: Boolean
    get() = this is SpotifyAuthState.Error

/**
 * Convenience extension: returns the active [SpotifyAuthErrorType] if in [SpotifyAuthState.Error], else null.
 */
val SpotifyAuthState.errorTypeOrNull: SpotifyAuthErrorType?
    get() = (this as? SpotifyAuthState.Error)?.errorType
