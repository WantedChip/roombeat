package com.roombeat.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.roombeat.app.service.RoomBeatCaptureService

/**
 * Result abstraction representing the user's response to Android's MediaProjection consent prompt.
 */
sealed interface CaptureConsentResult {
    /**
     * User approved the capture request in the system dialog.
     *
     * @param resultCode The result code returned by Android, typically [Activity.RESULT_OK] (-1).
     * @param data The intent containing the MediaProjection token granted by the system.
     */
    data class Granted(val resultCode: Int, val data: Intent) : CaptureConsentResult

    /**
     * User explicitly cancelled, dismissed, or denied the capture prompt.
     *
     * @param resultCode The result code returned by the system, typically [Activity.RESULT_CANCELED] (0).
     */
    data class Denied(val resultCode: Int = Activity.RESULT_CANCELED) : CaptureConsentResult

    /**
     * An unexpected system error occurred (e.g., MediaProjectionManager unavailable).
     */
    data class Error(val message: String, val cause: Throwable? = null) : CaptureConsentResult
}

/**
 * Factory interface for creating the MediaProjection screen capture intent.
 * Decouples system service lookup for deterministic headless JVM testing.
 */
interface ScreenCaptureIntentFactory {
    fun createScreenCaptureIntent(context: Context): Intent
}

/**
 * Default Android implementation using [MediaProjectionManager].
 */
class DefaultScreenCaptureIntentFactory : ScreenCaptureIntentFactory {
    override fun createScreenCaptureIntent(context: Context): Intent {
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: throw IllegalStateException("MediaProjectionManager is not available on this device")
        return mediaProjectionManager.createScreenCaptureIntent()
    }
}

/**
 * An [ActivityResultContract] for requesting screen and audio capture authorization via [MediaProjectionManager].
 *
 * Android 14+ (API 34) Lifecycle:
 * Under Android 14+ security policies, the returned consent token [Intent] is single-use and non-reusable.
 * Each new streaming session must execute this contract again to obtain a fresh consent token.
 */
class MediaProjectionContract(
    private val intentFactory: ScreenCaptureIntentFactory = DefaultScreenCaptureIntentFactory()
) : ActivityResultContract<Unit, CaptureConsentResult>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        return intentFactory.createScreenCaptureIntent(context)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): CaptureConsentResult {
        return if (resultCode == Activity.RESULT_OK && intent != null) {
            CaptureConsentResult.Granted(resultCode, intent)
        } else {
            CaptureConsentResult.Denied(resultCode)
        }
    }
}

/**
 * Controller interface wrapping the MediaProjection consent launcher.
 */
interface MediaProjectionLauncher {
    fun launch()
}

/**
 * Abstraction representing an active MediaProjection token handle.
 */
interface MediaProjectionHandle {
    val rawProjection: MediaProjection?
    fun registerCallback(callback: MediaProjection.Callback, handler: Handler? = null)
    fun unregisterCallback(callback: MediaProjection.Callback)
    fun stop()
}

/**
 * Android framework implementation wrapping an active [MediaProjection].
 */
class AndroidMediaProjectionHandle(
    private val projection: MediaProjection
) : MediaProjectionHandle {
    override val rawProjection: MediaProjection get() = projection

    override fun registerCallback(callback: MediaProjection.Callback, handler: Handler?) {
        projection.registerCallback(callback, handler)
    }

    override fun unregisterCallback(callback: MediaProjection.Callback) {
        try {
            projection.unregisterCallback(callback)
        } catch (e: Exception) {
            Log.w("AndroidMediaProjectionHandle", "Error unregistering callback: ${e.message}")
        }
    }

    override fun stop() {
        try {
            projection.stop()
        } catch (e: Exception) {
            Log.w("AndroidMediaProjectionHandle", "Error stopping MediaProjection: ${e.message}")
        }
    }
}

/**
 * Interface abstracting MediaProjection token instantiation and lifecycle management
 * for headless JVM testing and decoupled Android service execution.
 */
interface MediaProjectionProvider {
    /**
     * Instantiates a [MediaProjectionHandle] using the granted result code and intent data.
     */
    fun getMediaProjection(resultCode: Int, resultData: Intent): MediaProjectionHandle?
}

/**
 * Default Android implementation using [MediaProjectionManager].
 */
class DefaultMediaProjectionProvider(
    private val context: Context
) : MediaProjectionProvider {

    private val mediaProjectionManager: MediaProjectionManager? by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    override fun getMediaProjection(resultCode: Int, resultData: Intent): MediaProjectionHandle? {
        val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
        return projection?.let { AndroidMediaProjectionHandle(it) }
    }
}

/**
 * Helper utilities for launching, parsing, and starting foreground capture service with MediaProjection.
 */
object MediaProjectionHelper {
    private const val TAG = "MediaProjectionHelper"

    /**
     * Configurable intent factory for testing and custom intent generation.
     */
    var intentFactory: ScreenCaptureIntentFactory = DefaultScreenCaptureIntentFactory()

    /**
     * Resets the intent factory to the default Android implementation.
     */
    fun resetIntentFactory() {
        intentFactory = DefaultScreenCaptureIntentFactory()
    }

    /**
     * Creates the system capture consent intent via [MediaProjectionManager].
     */
    fun createCaptureIntent(context: Context): Intent {
        return intentFactory.createScreenCaptureIntent(context)
    }

    /**
     * Parses the result code and data intent from ActivityResult into [CaptureConsentResult].
     */
    fun parseConsentResult(resultCode: Int, data: Intent?): CaptureConsentResult {
        return if (resultCode == Activity.RESULT_OK && data != null) {
            CaptureConsentResult.Granted(resultCode, data)
        } else {
            CaptureConsentResult.Denied(resultCode)
        }
    }

    /**
     * Checks if the user granted capture consent with valid intent data.
     */
    fun isConsentGranted(resultCode: Int, data: Intent?): Boolean {
        return resultCode == Activity.RESULT_OK && data != null
    }

    /**
     * Builds the intent to start [RoomBeatCaptureService] in the foreground with the granted MediaProjection token.
     */
    fun buildServiceIntent(context: Context, resultCode: Int, data: Intent): Intent {
        return Intent(context, RoomBeatCaptureService::class.java).apply {
            action = RoomBeatCaptureService.ACTION_START_CAPTURE
            putExtra(RoomBeatCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RoomBeatCaptureService.EXTRA_RESULT_DATA, data)
        }
    }

    /**
     * Starts the [RoomBeatCaptureService] in foreground mode, passing the granted consent token.
     */
    fun startCaptureService(context: Context, resultCode: Int, data: Intent) {
        val intent = buildServiceIntent(context, resultCode, data)
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Stops the [RoomBeatCaptureService] and releases any active MediaProjection.
     */
    fun stopCaptureService(context: Context) {
        RoomBeatCaptureService.stop(context)
    }

    /**
     * Extracts a [MediaProjectionHandle] using the provided [MediaProjectionProvider].
     */
    fun extractMediaProjection(
        provider: MediaProjectionProvider,
        resultCode: Int,
        data: Intent
    ): MediaProjectionHandle? {
        return provider.getMediaProjection(resultCode, data)
    }

    /**
     * Extracts a [MediaProjectionHandle] using the system [MediaProjectionManager] from [context].
     */
    fun extractMediaProjection(
        context: Context,
        resultCode: Int,
        data: Intent
    ): MediaProjectionHandle? {
        val provider = DefaultMediaProjectionProvider(context)
        return provider.getMediaProjection(resultCode, data)
    }
}

/**
 * Creates and remembers a [MediaProjectionLauncher] that can be invoked to prompt the user
 * for Android MediaProjection authorization.
 */
@Composable
fun rememberMediaProjectionLauncher(
    onResult: (CaptureConsentResult) -> Unit
): MediaProjectionLauncher {
    val launcher = rememberLauncherForActivityResult(
        contract = MediaProjectionContract(),
        onResult = onResult
    )
    return remember(launcher) {
        object : MediaProjectionLauncher {
            override fun launch() {
                launcher.launch(Unit)
            }
        }
    }
}
