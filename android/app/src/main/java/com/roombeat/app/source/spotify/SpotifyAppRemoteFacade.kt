package com.roombeat.app.source.spotify

import android.content.Context
import android.os.Build
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.PlayerApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
     * Access to Spotify player transport controls.
     */
    val playerApi: SpotifyPlayerApiFacade

    /**
     * Closes the active IPC service connection and releases resources.
     */
    fun disconnect()
}

/**
 * Abstraction over Spotify App Remote's PlayerApi.
 * Enables deterministic testing on headless JVM environments without physical Spotify IPC.
 */
interface SpotifyPlayerApiFacade {
    suspend fun play(uri: String): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun resume(): Result<Unit>
    suspend fun seekTo(positionMs: Long): Result<Unit>
    suspend fun skipNext(): Result<Unit>
    suspend fun skipPrevious(): Result<Unit>
}

/**
 * Exception indicating that a requested Spotify playback operation requires a Spotify Premium subscription.
 */
class SpotifyPremiumRequiredException(
    message: String = "Spotify Premium is required for on-demand playback, seeking, and multi-device synchronization.",
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Classifier for Spotify errors detecting Premium subscription limitations and restrictions.
 */
object SpotifyErrorClassifier {
    fun isPremiumRequired(throwable: Throwable): Boolean {
        if (throwable is SpotifyPremiumRequiredException) return true
        val className = throwable.javaClass.simpleName
        if (className.contains("Premium", ignoreCase = true)) return true

        val msg = throwable.message?.lowercase() ?: ""
        if (msg.contains("premium") ||
            msg.contains("free tier") ||
            msg.contains("subscription") ||
            msg.contains("not supported for free") ||
            msg.contains("playback_restrictions") ||
            msg.contains("commercial")
        ) {
            return true
        }

        val cause = throwable.cause
        if (cause != null && cause !== throwable) {
            return isPremiumRequired(cause)
        }
        return false
    }
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
 * Production implementation of [SpotifyPlayerApiFacade] delegating to Spotify SDK's [PlayerApi].
 */
class DefaultSpotifyPlayerApi(
    private val nativePlayerApi: PlayerApi
) : SpotifyPlayerApiFacade {

    override suspend fun play(uri: String): Result<Unit> = suspendCancellableCoroutine { cont ->
        try {
            nativePlayerApi.play(uri)
                .setResultCallback {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                .setErrorCallback { error ->
                    if (cont.isActive) cont.resume(Result.failure(error))
                }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(Result.failure(t))
        }
    }

    override suspend fun pause(): Result<Unit> = suspendCancellableCoroutine { cont ->
        try {
            nativePlayerApi.pause()
                .setResultCallback {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                .setErrorCallback { error ->
                    if (cont.isActive) cont.resume(Result.failure(error))
                }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(Result.failure(t))
        }
    }

    override suspend fun resume(): Result<Unit> = suspendCancellableCoroutine { cont ->
        try {
            nativePlayerApi.resume()
                .setResultCallback {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                .setErrorCallback { error ->
                    if (cont.isActive) cont.resume(Result.failure(error))
                }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(Result.failure(t))
        }
    }

    override suspend fun seekTo(positionMs: Long): Result<Unit> = suspendCancellableCoroutine { cont ->
        try {
            nativePlayerApi.seekTo(positionMs)
                .setResultCallback {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                .setErrorCallback { error ->
                    if (cont.isActive) cont.resume(Result.failure(error))
                }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(Result.failure(t))
        }
    }

    override suspend fun skipNext(): Result<Unit> = suspendCancellableCoroutine { cont ->
        try {
            nativePlayerApi.skipNext()
                .setResultCallback {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                .setErrorCallback { error ->
                    if (cont.isActive) cont.resume(Result.failure(error))
                }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(Result.failure(t))
        }
    }

    override suspend fun skipPrevious(): Result<Unit> = suspendCancellableCoroutine { cont ->
        try {
            nativePlayerApi.skipPrevious()
                .setResultCallback {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                .setErrorCallback { error ->
                    if (cont.isActive) cont.resume(Result.failure(error))
                }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(Result.failure(t))
        }
    }
}

/**
 * Production implementation of [SpotifyAppRemoteFacade] wrapping real [SpotifyAppRemote].
 */
class SpotifyAppRemoteWrapper(
    val nativeRemote: SpotifyAppRemote
) : SpotifyAppRemoteFacade {

    override val isConnected: Boolean
        get() = nativeRemote.isConnected

    override val playerApi: SpotifyPlayerApiFacade = DefaultSpotifyPlayerApi(nativeRemote.playerApi)

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
 * Fake implementation of [SpotifyPlayerApiFacade] for deterministic, headless JVM testing.
 */
class FakeSpotifyPlayerApi(
    var isPremium: Boolean = true,
    var shouldFailWithPremium: Boolean = false,
    var failureToEmit: Throwable? = null
) : SpotifyPlayerApiFacade {

    val callHistory = mutableListOf<String>()
    var lastPlayedUri: String? = null
    var lastSeekPositionMs: Long? = null
    var isPlaying: Boolean = false
    var currentPositionMs: Long = 0L

    fun recordCall(call: String) {
        callHistory.add(call)
    }

    override suspend fun play(uri: String): Result<Unit> {
        recordCall("play:$uri")
        if (!isPremium || shouldFailWithPremium) {
            val ex = SpotifyPremiumRequiredException("Spotify Premium required for playback of $uri")
            return Result.failure(ex)
        }
        val failure = failureToEmit
        if (failure != null) {
            return Result.failure(failure)
        }
        lastPlayedUri = uri
        isPlaying = true
        return Result.success(Unit)
    }

    override suspend fun pause(): Result<Unit> {
        recordCall("pause")
        val failure = failureToEmit
        if (failure != null) {
            return Result.failure(failure)
        }
        isPlaying = false
        return Result.success(Unit)
    }

    override suspend fun resume(): Result<Unit> {
        recordCall("resume")
        if (!isPremium || shouldFailWithPremium) {
            val ex = SpotifyPremiumRequiredException("Spotify Premium required to resume playback")
            return Result.failure(ex)
        }
        val failure = failureToEmit
        if (failure != null) {
            return Result.failure(failure)
        }
        isPlaying = true
        return Result.success(Unit)
    }

    override suspend fun seekTo(positionMs: Long): Result<Unit> {
        recordCall("seekTo:$positionMs")
        if (!isPremium || shouldFailWithPremium) {
            val ex = SpotifyPremiumRequiredException("Spotify Premium required for seekTo($positionMs)")
            return Result.failure(ex)
        }
        val failure = failureToEmit
        if (failure != null) {
            return Result.failure(failure)
        }
        lastSeekPositionMs = positionMs
        currentPositionMs = positionMs
        return Result.success(Unit)
    }

    override suspend fun skipNext(): Result<Unit> {
        recordCall("skipNext")
        if (!isPremium || shouldFailWithPremium) {
            val ex = SpotifyPremiumRequiredException("Spotify Premium required for skipNext")
            return Result.failure(ex)
        }
        val failure = failureToEmit
        if (failure != null) {
            return Result.failure(failure)
        }
        return Result.success(Unit)
    }

    override suspend fun skipPrevious(): Result<Unit> {
        recordCall("skipPrevious")
        if (!isPremium || shouldFailWithPremium) {
            val ex = SpotifyPremiumRequiredException("Spotify Premium required for skipPrevious")
            return Result.failure(ex)
        }
        val failure = failureToEmit
        if (failure != null) {
            return Result.failure(failure)
        }
        return Result.success(Unit)
    }
}

/**
 * Fake implementation of [SpotifyAppRemoteFacade] for deterministic testing.
 */
class FakeSpotifyAppRemoteFacade(
    override var isConnected: Boolean = true,
    override val playerApi: FakeSpotifyPlayerApi = FakeSpotifyPlayerApi()
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
