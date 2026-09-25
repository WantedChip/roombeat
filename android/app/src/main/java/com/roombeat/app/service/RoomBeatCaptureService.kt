package com.roombeat.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.roombeat.app.capture.DefaultMediaProjectionProvider
import com.roombeat.app.capture.MediaProjectionHandle
import com.roombeat.app.capture.MediaProjectionProvider
import com.roombeat.app.system.PowerLockManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State of [RoomBeatCaptureService] lifecycle.
 */
sealed interface CaptureServiceState {
    object Idle : CaptureServiceState
    object Starting : CaptureServiceState
    object Active : CaptureServiceState
    object Stopped : CaptureServiceState
    data class Error(val message: String, val throwable: Throwable? = null) : CaptureServiceState
}

/**
 * Delegate interface abstracting Android foreground service transitions for deterministic testability.
 */
interface ForegroundDelegate {
    fun startForeground(service: Service, id: Int, notification: Notification, type: Int)
    fun stopForeground(service: Service, flags: Int)
}

/**
 * Default Android implementation using [ServiceCompat].
 */
class DefaultForegroundDelegate : ForegroundDelegate {
    override fun startForeground(service: Service, id: Int, notification: Notification, type: Int) {
        ServiceCompat.startForeground(service, id, notification, type)
    }

    override fun stopForeground(service: Service, flags: Int) {
        ServiceCompat.stopForeground(service, flags)
    }
}

/**
 * Foreground service managing audio capture and low-latency multicast distribution.
 *
 * Requirements:
 * - Foreground service type: [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
 * - Android 14+ (API 34) Mandatory Lifecycle:
 *   [ServiceCompat.startForeground] with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
 *   MUST be executed before calling [android.media.projection.MediaProjectionManager.getMediaProjection]
 *   to prevent a fatal [SecurityException].
 * - Persistent notification for foreground execution and transport status.
 * - Acquisition of MulticastLock, low-latency WifiLock, and WakeLock via [PowerLockManager].
 * - Clean [MediaProjection.Callback] registration and safe teardown on stop.
 */
class RoomBeatCaptureService : Service() {

    companion object {
        private const val TAG = "RoomBeatCaptureService"

        const val CHANNEL_ID = "roombeat_audio_capture_channel"
        const val CHANNEL_NAME = "RoomBeat Audio Capture & Streaming"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_CAPTURE = "com.roombeat.app.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.roombeat.app.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "com.roombeat.app.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.roombeat.app.extra.RESULT_DATA"

        /**
         * Convenience helper to launch [RoomBeatCaptureService] in the foreground with a granted MediaProjection token.
         */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, RoomBeatCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Convenience helper to launch [RoomBeatCaptureService] in foreground standby mode.
         */
        fun start(context: Context) {
            val intent = Intent(context, RoomBeatCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Convenience helper to stop [RoomBeatCaptureService].
         */
        fun stop(context: Context) {
            val intent = Intent(context, RoomBeatCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }
    }

    /**
     * Local binder for service connection and inspection.
     */
    inner class LocalBinder : Binder() {
        fun getService(): RoomBeatCaptureService = this@RoomBeatCaptureService
    }

    private val binder = LocalBinder()

    // Configurable collaborators for unit testing
    var powerLockManager: PowerLockManager? = null
    var mediaProjectionProvider: MediaProjectionProvider? = null
    var foregroundDelegate: ForegroundDelegate = DefaultForegroundDelegate()

    // Active capture state
    var isCapturing: Boolean = false
        private set

    var isForegroundActive: Boolean = false
        private set

    var activeProjectionHandle: MediaProjectionHandle? = null
        private set

    val activeMediaProjection: MediaProjection?
        get() = activeProjectionHandle?.rawProjection

    var activeCallback: MediaProjection.Callback? = null
        private set

    /**
     * Optional listener invoked when the active MediaProjection is stopped by the user or system.
     */
    var onProjectionStoppedListener: (() -> Unit)? = null

    private val _captureState = MutableStateFlow<CaptureServiceState>(CaptureServiceState.Idle)
    val captureState: StateFlow<CaptureServiceState> = _captureState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating RoomBeatCaptureService")
        if (powerLockManager == null) {
            powerLockManager = PowerLockManager(this)
        }
        if (mediaProjectionProvider == null) {
            mediaProjectionProvider = DefaultMediaProjectionProvider(this)
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_CAPTURE
        Log.d(TAG, "onStartCommand received action: $action")

        when (action) {
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_CAPTURE -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED

                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                startCapture(resultCode, resultData)
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Releases the active MediaProjection handle and unregisters callbacks.
     */
    private fun releaseActiveProjection() {
        activeProjectionHandle?.let { handle ->
            activeCallback?.let { callback ->
                handle.unregisterCallback(callback)
            }
            try {
                handle.stop()
                Log.d(TAG, "MediaProjection handle stopped successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaProjection handle: ${e.message}")
            }
        }
        activeProjectionHandle = null
        activeCallback = null
    }

    /**
     * Starts the foreground service and initializes the MediaProjection audio capture pipeline.
     *
     * Android 14+ (API 34) Strict Order:
     * 1. Call [ForegroundDelegate.startForeground] with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION].
     * 2. Acquire Wi-Fi and power locks.
     * 3. Instantiate [MediaProjection] token and register lifecycle callback.
     */
    fun startCapture(resultCode: Int = Activity.RESULT_CANCELED, resultData: Intent? = null) {
        if (isCapturing && activeProjectionHandle != null) {
            if (resultData != null && resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "New projection token received while capturing; replacing active token")
                releaseActiveProjection()
            } else {
                Log.d(TAG, "Capture already running with active MediaProjection")
                return
            }
        }

        _captureState.value = CaptureServiceState.Starting
        Log.i(TAG, "Starting audio capture foreground service")

        val notification = buildPersistentNotification()
        val foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        // STEP 1: MUST start foreground BEFORE calling getMediaProjection on API 34+
        foregroundDelegate.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
        isForegroundActive = true

        // STEP 2: Acquire Wi-Fi low-latency and multicast locks for uninterrupted distribution
        powerLockManager?.acquireAll()

        // STEP 3: Instantiate MediaProjection token if consent data was supplied
        if (resultData != null && resultCode == Activity.RESULT_OK) {
            val provider = mediaProjectionProvider ?: DefaultMediaProjectionProvider(this)
            try {
                val handle = provider.getMediaProjection(resultCode, resultData)
                if (handle != null) {
                    activeProjectionHandle = handle

                    val callback = object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.i(TAG, "MediaProjection stopped by system or user")
                            activeCallback = null
                            onProjectionStoppedListener?.invoke()
                            stopCapture()
                            stopSelf()
                        }

                        override fun onCapturedContentResize(width: Int, height: Int) {
                            Log.d(TAG, "MediaProjection content resized: ${width}x${height}")
                        }

                        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                            Log.d(TAG, "MediaProjection content visibility changed: $isVisible")
                        }
                    }
                    activeCallback = callback
                    handle.registerCallback(callback, null)

                    isCapturing = true
                    _captureState.value = CaptureServiceState.Active
                    Log.i(TAG, "MediaProjection token acquired and callback registered successfully")
                } else {
                    Log.e(TAG, "MediaProjectionProvider returned null MediaProjectionHandle")
                    _captureState.value = CaptureServiceState.Error("MediaProjection token is null")
                    stopCapture()
                    stopSelf()
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException acquiring MediaProjection: ${se.message}", se)
                _captureState.value = CaptureServiceState.Error("SecurityException: ${se.message}", se)
                stopCapture()
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Exception acquiring MediaProjection: ${e.message}", e)
                _captureState.value = CaptureServiceState.Error("Exception: ${e.message}", e)
                stopCapture()
                stopSelf()
            }
        } else if (resultData != null && resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Capture started with non-OK resultCode ($resultCode); consent denied")
            _captureState.value = CaptureServiceState.Error("MediaProjection consent denied (resultCode=$resultCode)")
            stopCapture()
            stopSelf()
        } else {
            // Started without projection extras (e.g. testing or standby mode)
            isCapturing = true
            _captureState.value = CaptureServiceState.Active
            Log.i(TAG, "Capture service active in standby mode without projection token")
        }
    }

    /**
     * Cleanly stops capture, releases the MediaProjection token, releases locks, and removes foreground notification.
     */
    fun stopCapture() {
        if (!isCapturing && activeProjectionHandle == null && !isForegroundActive) {
            Log.d(TAG, "Capture service already stopped")
            return
        }

        Log.i(TAG, "Stopping audio capture foreground service")

        // 1. Release active MediaProjection and unregister callback
        releaseActiveProjection()

        // 2. Release power and Wi-Fi locks
        powerLockManager?.releaseAll()

        // 3. Remove foreground service notification
        if (isForegroundActive) {
            foregroundDelegate.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForegroundActive = false
        }

        isCapturing = false
        if (_captureState.value !is CaptureServiceState.Error) {
            _captureState.value = CaptureServiceState.Stopped
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying RoomBeatCaptureService")
        stopCapture()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Initializes the notification channel required for Android 8.0 (API 26) and above.
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java) ?: return
            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent indicator for synchronized low-latency audio capture and multicast streaming"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created: $CHANNEL_ID")
            }
        }
    }

    var notificationProvider: (() -> Notification)? = null

    /**
     * Builds the persistent foreground notification conforming to RoomBeat's
     * Tactile Acoustic Industrial design principles.
     */
    fun buildPersistentNotification(): Notification {
        notificationProvider?.let { return it.invoke() }
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RoomBeat — Sync Stream Active")
                .setContentText("Broadcasting synchronized low-latency audio")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        } catch (_: Exception) {
            Notification()
        }
    }
}
