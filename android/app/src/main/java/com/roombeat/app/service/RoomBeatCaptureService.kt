package com.roombeat.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.roombeat.app.system.PowerLockManager

/**
 * Foreground service managing audio capture and low-latency multicast distribution.
 *
 * Requirements:
 * - Foreground service type: [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]
 * - Persistent notification for foreground execution and transport status
 * - Acquisition of MulticastLock, low-latency WifiLock, and WakeLock via [PowerLockManager]
 */
class RoomBeatCaptureService : Service() {

    companion object {
        private const val TAG = "RoomBeatCaptureService"

        const val CHANNEL_ID = "roombeat_audio_capture_channel"
        const val CHANNEL_NAME = "RoomBeat Audio Capture & Streaming"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_CAPTURE = "com.roombeat.app.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.roombeat.app.action.STOP_CAPTURE"

        /**
         * Convenience helper to launch [RoomBeatCaptureService] in the foreground.
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

    private lateinit var powerLockManager: PowerLockManager
    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating RoomBeatCaptureService")
        powerLockManager = PowerLockManager(this)
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
                startCapture()
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (isCapturing) {
            Log.d(TAG, "Capture already running")
            return
        }

        Log.i(TAG, "Starting audio capture foreground service")
        val notification = buildPersistentNotification()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )

        // Acquire Wi-Fi low-latency and multicast locks for uninterrupted distribution
        powerLockManager.acquireAll()
        isCapturing = true
    }

    private fun stopCapture() {
        if (!isCapturing) return

        Log.i(TAG, "Stopping audio capture foreground service")
        powerLockManager.releaseAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isCapturing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying RoomBeatCaptureService")
        stopCapture()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
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

    /**
     * Builds the persistent foreground notification conforming to RoomBeat's
     * Tactile Acoustic Industrial design principles.
     */
    fun buildPersistentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoomBeat — Sync Stream Active")
            .setContentText("Broadcasting synchronized low-latency audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
