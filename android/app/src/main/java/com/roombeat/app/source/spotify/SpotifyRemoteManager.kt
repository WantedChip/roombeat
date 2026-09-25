package com.roombeat.app.source.spotify

import android.content.Context
import android.net.Uri
import com.roombeat.app.protocol.HandlerRegistration
import com.roombeat.app.protocol.PacketDispatcher
import com.roombeat.app.protocol.RoomBeatPacket
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spotify App Remote connection and lifecycle manager (Sub-phase v0.7.0 / Roadmap §8, §10, §11).
 *
 * Responsibilities:
 * - Wraps `SpotifyAppRemote.connect(context, connectionParams, connectionListener)` through [SpotifyConnector].
 * - Decouples tests from physical Spotify IPC via [SpotifyAppRemoteFacade].
 * - Tracks reactive connection and authorization states via [SpotifyAuthState].
 * - Pre-warms the App Remote IPC connection during the calibration phase (`SPOTIFY_WARM`),
 *   absorbing cold-start overhead before track selection.
 * - Handles incoming and outgoing `SPOTIFY_WARM` protocol packets across the RoomBeat mesh.
 * - Manages custom redirect URI callbacks (`roombeat://spotify-callback`).
 */
class SpotifyRemoteManager(
    val clientId: String = SpotifyConstants.DEFAULT_CLIENT_ID,
    val redirectUri: String = SpotifyConstants.DEFAULT_REDIRECT_URI,
    val connector: SpotifyConnector = DefaultSpotifyConnector(),
    val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())
) : AutoCloseable {

    private val _authState = MutableStateFlow<SpotifyAuthState>(SpotifyAuthState.Disconnected)

    /**
     * Reactive StateFlow reflecting current Spotify auth and IPC connection state.
     */
    val authState: StateFlow<SpotifyAuthState> = _authState.asStateFlow()

    /**
     * Whether the Spotify App Remote IPC connection is currently active and pre-warmed.
     */
    val isConnected: Boolean
        get() = _authState.value.isConnected

    /**
     * Active facade handle to connected Spotify App Remote, or null if disconnected.
     */
    var currentRemote: SpotifyAppRemoteFacade? = null
        private set

    /**
     * Timestamp in milliseconds when the active connection was established.
     */
    var lastConnectedAtMs: Long = 0L
        private set

    init {
        registerInstance(this)
    }

    /**
     * Asynchronously initiates the Spotify App Remote authentication and IPC pre-warm handshake.
     *
     * @param context Android context for service binding (null in headless tests).
     * @param showAuthView If true, allows the Spotify auth dialog to appear if consent is missing.
     */
    fun initiateWarmUp(
        context: Context?,
        showAuthView: Boolean = true
    ) {
        if (isConnected) return
        if (_authState.value is SpotifyAuthState.Connecting) return

        _authState.value = SpotifyAuthState.Connecting(System.currentTimeMillis())

        connector.connect(
            context = context,
            clientId = clientId,
            redirectUri = redirectUri,
            showAuthView = showAuthView,
            listener = object : SpotifyConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemoteFacade) {
                    currentRemote = appRemote
                    lastConnectedAtMs = System.currentTimeMillis()
                    _authState.value = SpotifyAuthState.Connected(lastConnectedAtMs)
                }

                override fun onFailure(throwable: Throwable) {
                    currentRemote = null
                    val errorType = mapThrowableToErrorType(throwable)
                    val message = throwable.message ?: "Spotify App Remote connection failed"
                    _authState.value = SpotifyAuthState.Error(
                        errorType = errorType,
                        message = message,
                        cause = throwable,
                        timestampMs = System.currentTimeMillis()
                    )
                }
            }
        )
    }

    /**
     * Suspends until the Spotify App Remote pre-warm handshake finishes with [SpotifyAuthState.Connected]
     * or [SpotifyAuthState.Error], guarded by a configurable timeout.
     *
     * @param context Android context for service binding.
     * @param showAuthView Whether to display Spotify authorization prompt if needed.
     * @param timeoutMs Timeout duration in milliseconds before returning [SpotifyAuthErrorType.TIMEOUT].
     * @return Terminal [SpotifyAuthState] (Connected or Error).
     */
    suspend fun warmUp(
        context: Context?,
        showAuthView: Boolean = true,
        timeoutMs: Long = SpotifyConstants.DEFAULT_CONNECT_TIMEOUT_MS
    ): SpotifyAuthState {
        if (isConnected) return _authState.value

        initiateWarmUp(context, showAuthView)

        val result = withTimeoutOrNull(timeoutMs) {
            _authState.first { state ->
                state is SpotifyAuthState.Connected || state is SpotifyAuthState.Error
            }
        }

        return if (result != null) {
            result
        } else {
            val timeoutError = SpotifyAuthState.Error(
                errorType = SpotifyAuthErrorType.TIMEOUT,
                message = "Spotify App Remote IPC connection timed out after ${timeoutMs}ms",
                timestampMs = System.currentTimeMillis()
            )
            _authState.value = timeoutError
            timeoutError
        }
    }

    /**
     * Disconnects the active Spotify App Remote session and releases IPC resources.
     */
    fun disconnect() {
        val remote = currentRemote
        if (remote != null) {
            connector.disconnect(remote)
        }
        currentRemote = null
        _authState.value = SpotifyAuthState.Disconnected
    }

    /**
     * Resets the connection state back to [SpotifyAuthState.Disconnected].
     */
    fun resetState() {
        disconnect()
    }

    // ==========================================
    // SPOTIFY_WARM Protocol Handshake Handling
    // ==========================================

    /**
     * Creates a new [RoomBeatPacket.SpotifyWarm] packet ready for session broadcast.
     */
    fun createSpotifyWarmPacket(): RoomBeatPacket.SpotifyWarm {
        return RoomBeatPacket.SpotifyWarm(timestampMs = System.currentTimeMillis())
    }

    /**
     * Handles an incoming [RoomBeatPacket.SpotifyWarm] message (e.g. dispatched by host during calibration).
     * If this node is not already connected or pre-warming, triggers the local IPC warm-up handshake.
     */
    fun handleSpotifyWarmPacket(
        context: Context?,
        packet: RoomBeatPacket.SpotifyWarm
    ) {
        if (!isConnected && _authState.value !is SpotifyAuthState.Connecting) {
            initiateWarmUp(context, showAuthView = false)
        }
    }

    /**
     * Registers a listener on the provided [PacketDispatcher] to automatically intercept
     * incoming [RoomBeatPacket.SpotifyWarm] packets and trigger background pre-warming.
     */
    fun registerPacketHandler(
        dispatcher: PacketDispatcher,
        context: Context?
    ): HandlerRegistration {
        return dispatcher.registerHandler<RoomBeatPacket.SpotifyWarm> { packet, _ ->
            handleSpotifyWarmPacket(context, packet)
        }
    }

    /**
     * Suspends and broadcasts the [RoomBeatPacket.SpotifyWarm] packet to all connected peers
     * via the provided transmission lambda.
     */
    suspend fun broadcastWarmHandshake(
        broadcaster: suspend (RoomBeatPacket) -> Unit
    ) {
        val packet = createSpotifyWarmPacket()
        broadcaster(packet)
    }

    /**
     * Handles an auth redirect URI callback (`roombeat://spotify-callback`).
     *
     * @param uri The redirect URI parsed from the incoming intent.
     * @return true if the URI was recognized as a Spotify callback, false otherwise.
     */
    fun onAuthRedirect(uri: Uri?): Boolean {
        if (uri == null) return false
        return onAuthRedirect(uri.toString())
    }

    /**
     * Handles an auth redirect URI string callback (`roombeat://spotify-callback`).
     */
    fun onAuthRedirect(uriString: String): Boolean {
        if (uriString.startsWith("roombeat://spotify-callback")) {
            val error = extractQueryParam(uriString, "error")
            if (error != null) {
                _authState.value = SpotifyAuthState.Error(
                    errorType = SpotifyAuthErrorType.USER_NOT_AUTHORIZED,
                    message = "Spotify authorization failed: $error"
                )
            }
            return true
        }
        return false
    }

    private fun extractQueryParam(uriString: String, key: String): String? {
        val queryStart = uriString.indexOf('?')
        if (queryStart == -1) return null
        val query = uriString.substring(queryStart + 1)
        val pairs = query.split('&')
        for (pair in pairs) {
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2 && parts[0] == key) {
                return parts[1]
            }
        }
        return null
    }

    override fun close() {
        unregisterInstance(this)
        disconnect()
        try {
            scope.cancel()
        } catch (_: Exception) {}
    }

    companion object {

        /**
         * Shared default instance for Compose UI screens.
         */
        val DEFAULT: SpotifyRemoteManager by lazy {
            SpotifyRemoteManager()
        }

        private val activeInstances = CopyOnWriteArrayList<SpotifyRemoteManager>()

        internal fun registerInstance(instance: SpotifyRemoteManager) {
            if (!activeInstances.contains(instance)) {
                activeInstances.add(instance)
            }
        }

        internal fun unregisterInstance(instance: SpotifyRemoteManager) {
            activeInstances.remove(instance)
        }

        /**
         * Dispatches a received auth redirect URI to all registered [SpotifyRemoteManager] instances.
         */
        fun handleAuthRedirect(uri: Uri?): Boolean {
            if (uri == null) return false
            return handleAuthRedirect(uri.toString())
        }

        /**
         * Dispatches a received auth redirect URI string to all registered [SpotifyRemoteManager] instances.
         */
        fun handleAuthRedirect(uriString: String): Boolean {
            var handled = false
            for (instance in activeInstances) {
                if (instance.onAuthRedirect(uriString)) {
                    handled = true
                }
            }
            return handled
        }

        /**
         * Maps throwables from Spotify App Remote SDK to typed [SpotifyAuthErrorType].
         */
        fun mapThrowableToErrorType(throwable: Throwable): SpotifyAuthErrorType {
            return when (throwable) {
                is CouldNotFindSpotifyApp -> SpotifyAuthErrorType.APP_NOT_INSTALLED
                is NotLoggedInException -> SpotifyAuthErrorType.NOT_LOGGED_IN
                is UserNotAuthorizedException -> SpotifyAuthErrorType.USER_NOT_AUTHORIZED
                is AuthenticationFailedException -> SpotifyAuthErrorType.AUTHENTICATION_FAILED
                is OfflineModeException -> SpotifyAuthErrorType.OFFLINE_MODE
                is SpotifyConnectionTerminatedException -> SpotifyAuthErrorType.CONNECTION_TERMINATED
                is SpotifyDisconnectedException -> SpotifyAuthErrorType.CONNECTION_TERMINATED
                else -> {
                    val msg = throwable.message?.lowercase() ?: ""
                    when {
                        msg.contains("could not find") || msg.contains("not installed") ->
                            SpotifyAuthErrorType.APP_NOT_INSTALLED
                        msg.contains("not logged in") ->
                            SpotifyAuthErrorType.NOT_LOGGED_IN
                        msg.contains("not authorized") || msg.contains("denied") ->
                            SpotifyAuthErrorType.USER_NOT_AUTHORIZED
                        msg.contains("timeout") ->
                            SpotifyAuthErrorType.TIMEOUT
                        else -> SpotifyAuthErrorType.UNKNOWN
                    }
                }
            }
        }
    }
}
