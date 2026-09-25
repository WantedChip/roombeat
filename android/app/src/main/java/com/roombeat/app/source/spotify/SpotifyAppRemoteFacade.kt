package com.roombeat.app.source.spotify

import android.content.Context
import android.os.Build
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

/**
 * Abstraction over a connected Spotify App Remote instance.
 * Allows decoupling unit tests from the real Android IPC service binding.
 */
interface SpotifyAppRemoteFacade {
    /**
     * Whether the underlying App Remote IPC service connection is alive and active.
     */
    val isConnected: Boolean

    /**
     * Closes the active IPC service connection and releases resources.
     */
    fun disconnect()
}

/**
 * Callback listener interface receiving results from [SpotifyConnector.connect].
 */
interface SpotifyConnectionListener {
    /**
     * Invoked when the IPC connection is established and authenticated.
     */
    fun onConnected(appRemote: SpotifyAppRemoteFacade)

    /**
     * Invoked when connection, IPC bind, or authentication fails.
     */
    fun onFailure(throwable: Throwable)
}

/**
 * Connector abstraction encapsulating the static [SpotifyAppRemote.connect] factory method.
 */
interface SpotifyConnector {

    /**
     * Initiates connection to Spotify App Remote.
     *
     * @param context Android context for IPC binding (may be null in fake test harnesses).
     * @param clientId Client ID registered with Spotify.
     * @param redirectUri Custom scheme redirect URI matching AndroidManifest.
     * @param showAuthView If true, prompts user to authorize if not already granted.
     * @param listener Callback receiver.
     */
    fun connect(
        context: Context?,
        clientId: String,
        redirectUri: String,
        showAuthView: Boolean,
        listener: SpotifyConnectionListener
    )

    /**
     * Disconnects an active [SpotifyAppRemoteFacade] instance.
     */
    fun disconnect(appRemote: SpotifyAppRemoteFacade)

    /**
     * Checks if the Spotify app is installed on the device via PackageManager.
     */
    fun isSpotifyInstalled(context: Context?): Boolean
}

/**
 * Production implementation of [SpotifyAppRemoteFacade] wrapping real [SpotifyAppRemote].
 */
class SpotifyAppRemoteWrapper(
    val nativeRemote: SpotifyAppRemote
) : SpotifyAppRemoteFacade {

    override val isConnected: Boolean
        get() = nativeRemote.isConnected

    override fun disconnect() {
        SpotifyAppRemote.disconnect(nativeRemote)
    }
}

/**
 * Production connector delegating directly to [SpotifyAppRemote.connect] and [SpotifyAppRemote.disconnect].
 */
open class DefaultSpotifyConnector : SpotifyConnector {

    override fun connect(
        context: Context?,
        clientId: String,
        redirectUri: String,
        showAuthView: Boolean,
        listener: SpotifyConnectionListener
    ) {
        if (context == null) {
            listener.onFailure(IllegalArgumentException("Android Context cannot be null for DefaultSpotifyConnector"))
            return
        }

        try {
            val connectionParams = ConnectionParams.Builder(clientId)
                .setRedirectUri(redirectUri)
                .showAuthView(showAuthView)
                .build()

            SpotifyAppRemote.connect(
                context,
                connectionParams,
                object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) {
                        listener.onConnected(SpotifyAppRemoteWrapper(appRemote))
                    }

                    override fun onFailure(throwable: Throwable) {
                        listener.onFailure(throwable)
                    }
                }
            )
        } catch (t: Throwable) {
            listener.onFailure(t)
        }
    }

    override fun disconnect(appRemote: SpotifyAppRemoteFacade) {
        try {
            if (appRemote is SpotifyAppRemoteWrapper) {
                SpotifyAppRemote.disconnect(appRemote.nativeRemote)
            } else {
                appRemote.disconnect()
            }
        } catch (_: Throwable) {
            // Error containment during teardown
        }
    }

    override fun isSpotifyInstalled(context: Context?): Boolean {
        if (context == null) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SpotifyConstants.SPOTIFY_PACKAGE_NAME,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    SpotifyConstants.SPOTIFY_PACKAGE_NAME,
                    0
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Fake implementation of [SpotifyAppRemoteFacade] for deterministic testing.
 */
class FakeSpotifyAppRemoteFacade(
    override var isConnected: Boolean = true
) : SpotifyAppRemoteFacade {

    var disconnectCallCount: Int = 0

    override fun disconnect() {
        disconnectCallCount++
        isConnected = false
    }
}

/**
 * Fake implementation of [SpotifyConnector] for deterministic, headless JVM testing.
 */
class FakeSpotifyConnector(
    var isInstalled: Boolean = true,
    var autoRespondConnected: Boolean = true
) : SpotifyConnector {

    var connectCallCount: Int = 0
    var disconnectCallCount: Int = 0
    var lastClientId: String? = null
    var lastRedirectUri: String? = null
    var lastShowAuthView: Boolean = false
    var lastListener: SpotifyConnectionListener? = null
    var failureToEmit: Throwable? = null
    var lastConnectedRemote: FakeSpotifyAppRemoteFacade? = null

    override fun connect(
        context: Context?,
        clientId: String,
        redirectUri: String,
        showAuthView: Boolean,
        listener: SpotifyConnectionListener
    ) {
        connectCallCount++
        lastClientId = clientId
        lastRedirectUri = redirectUri
        lastShowAuthView = showAuthView
        lastListener = listener

        if (!isInstalled) {
            listener.onFailure(com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp())
            return
        }

        val failure = failureToEmit
        if (failure != null) {
            listener.onFailure(failure)
            return
        }

        if (autoRespondConnected) {
            val fakeRemote = FakeSpotifyAppRemoteFacade()
            lastConnectedRemote = fakeRemote
            listener.onConnected(fakeRemote)
        }
    }

    override fun disconnect(appRemote: SpotifyAppRemoteFacade) {
        disconnectCallCount++
        appRemote.disconnect()
    }

    override fun isSpotifyInstalled(context: Context?): Boolean = isInstalled

    fun triggerConnected(remote: FakeSpotifyAppRemoteFacade = FakeSpotifyAppRemoteFacade()) {
        lastConnectedRemote = remote
        lastListener?.onConnected(remote)
    }

    fun triggerFailure(throwable: Throwable) {
        lastListener?.onFailure(throwable)
    }
}
