package com.roombeat.app.service

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Handler
import com.roombeat.app.capture.MediaProjectionHandle
import com.roombeat.app.capture.MediaProjectionProvider
import com.roombeat.app.system.LockFactory
import com.roombeat.app.system.LockHandle
import com.roombeat.app.system.PowerLockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoomBeatCaptureServiceTest {

    private class FakeForegroundDelegate : ForegroundDelegate {
        var startForegroundCount = 0
        var stopForegroundCount = 0
        var lastForegroundService: Service? = null
        var lastNotificationId: Int = 0
        var lastForegroundType: Int = 0
        var lastStopFlags: Int = 0

        override fun startForeground(service: Service, id: Int, notification: Notification, type: Int) {
            startForegroundCount++
            lastForegroundService = service
            lastNotificationId = id
            lastForegroundType = type
        }

        override fun stopForeground(service: Service, flags: Int) {
            stopForegroundCount++
            lastStopFlags = flags
        }
    }

    private class FakeMediaProjectionHandle : MediaProjectionHandle {
        override val rawProjection: MediaProjection? = null
        var registeredCallback: MediaProjection.Callback? = null
        var unregisterCallbackCount = 0
        var stopCount = 0

        override fun registerCallback(callback: MediaProjection.Callback, handler: Handler?) {
            registeredCallback = callback
        }

        override fun unregisterCallback(callback: MediaProjection.Callback) {
            unregisterCallbackCount++
            if (registeredCallback == callback) {
                registeredCallback = null
            }
        }

        override fun stop() {
            stopCount++
        }
    }

    private class FakeMediaProjectionProvider(
        private val foregroundDelegate: FakeForegroundDelegate
    ) : MediaProjectionProvider {
        var getMediaProjectionCount = 0
        var lastResultCode: Int = 0
        var lastResultData: Intent? = null
        var shouldThrowSecurityException = false
        var shouldReturnNull = false
        var startForegroundWasCalledBeforeGet = false

        val fakeHandle = FakeMediaProjectionHandle()

        override fun getMediaProjection(resultCode: Int, resultData: Intent): MediaProjectionHandle? {
            getMediaProjectionCount++
            lastResultCode = resultCode
            lastResultData = resultData

            // Android 14+ Requirement Verification:
            // startForeground MUST have been called before getMediaProjection is invoked!
            startForegroundWasCalledBeforeGet = (foregroundDelegate.startForegroundCount > 0)

            if (shouldThrowSecurityException) {
                throw SecurityException("Media projections require a foreground service of type ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")
            }

            if (shouldReturnNull) {
                return null
            }

            return fakeHandle
        }
    }

    private class FakeLockHandle : LockHandle {
        override var isHeld: Boolean = false
        var acquireCount: Int = 0
        var releaseCount: Int = 0

        override fun acquire() {
            acquireCount++
            isHeld = true
        }

        override fun release() {
            releaseCount++
            isHeld = false
        }

        override fun setReferenceCounted(refCounted: Boolean) {}
    }

    private class FakeLockFactory : LockFactory {
        val fakeMulticast = FakeLockHandle()
        val fakeWifi = FakeLockHandle()
        val fakeWake = FakeLockHandle()

        override fun createMulticastLock(tag: String): LockHandle = fakeMulticast
        override fun createWifiLock(mode: Int, tag: String): LockHandle = fakeWifi
        override fun createWakeLock(levelAndFlags: Int, tag: String): LockHandle = fakeWake
    }

    private lateinit var service: RoomBeatCaptureService
    private lateinit var foregroundDelegate: FakeForegroundDelegate
    private lateinit var projectionProvider: FakeMediaProjectionProvider
    private lateinit var lockFactory: FakeLockFactory
    private lateinit var powerLockManager: PowerLockManager

    @Before
    fun setUp() {
        service = RoomBeatCaptureService()
        foregroundDelegate = FakeForegroundDelegate()
        projectionProvider = FakeMediaProjectionProvider(foregroundDelegate)
        lockFactory = FakeLockFactory()
        powerLockManager = PowerLockManager(lockFactory = lockFactory)

        service.foregroundDelegate = foregroundDelegate
        service.mediaProjectionProvider = projectionProvider
        service.powerLockManager = powerLockManager
    }

    @Test
    fun testServiceConstants() {
        assertEquals("com.roombeat.app.action.START_CAPTURE", RoomBeatCaptureService.ACTION_START_CAPTURE)
        assertEquals("com.roombeat.app.action.STOP_CAPTURE", RoomBeatCaptureService.ACTION_STOP_CAPTURE)
        assertEquals("com.roombeat.app.extra.RESULT_CODE", RoomBeatCaptureService.EXTRA_RESULT_CODE)
        assertEquals("com.roombeat.app.extra.RESULT_DATA", RoomBeatCaptureService.EXTRA_RESULT_DATA)
        assertEquals("roombeat_audio_capture_channel", RoomBeatCaptureService.CHANNEL_ID)
        assertEquals("RoomBeat Audio Capture & Streaming", RoomBeatCaptureService.CHANNEL_NAME)
        assertEquals(1001, RoomBeatCaptureService.NOTIFICATION_ID)
    }

    @Test
    fun testStartCapture_StrictAndroid14Order_And_SuccessfulLifecycle() {
        val testIntent = Intent()
        service.startCapture(Activity.RESULT_OK, testIntent)

        // 1. Android 14+ Order Check: startForeground was called before getMediaProjection
        assertTrue("startForeground must be called BEFORE getMediaProjection on Android 14+", projectionProvider.startForegroundWasCalledBeforeGet)
        assertEquals(1, foregroundDelegate.startForegroundCount)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, foregroundDelegate.lastForegroundType)

        // 2. Power and Wi-Fi locks acquired
        assertEquals(1, lockFactory.fakeMulticast.acquireCount)
        assertTrue(lockFactory.fakeMulticast.isHeld)
        assertEquals(1, lockFactory.fakeWifi.acquireCount)
        assertTrue(lockFactory.fakeWifi.isHeld)
        assertEquals(1, lockFactory.fakeWake.acquireCount)
        assertTrue(lockFactory.fakeWake.isHeld)

        // 3. MediaProjection token acquired and callback registered
        assertEquals(1, projectionProvider.getMediaProjectionCount)
        assertEquals(Activity.RESULT_OK, projectionProvider.lastResultCode)
        assertNotNull(projectionProvider.fakeHandle.registeredCallback)

        // 4. Service State is Active
        assertTrue(service.isCapturing)
        assertNotNull(service.activeProjectionHandle)
        assertEquals(CaptureServiceState.Active, service.captureState.value)
    }

    @Test
    fun testStopCapture_ReleasesHandle_And_Locks_And_StopsForeground() {
        val testIntent = Intent()
        service.startCapture(Activity.RESULT_OK, testIntent)
        assertTrue(service.isCapturing)

        service.stopCapture()

        // 1. Handle stopped and callback unregistered
        assertEquals(1, projectionProvider.fakeHandle.stopCount)
        assertEquals(1, projectionProvider.fakeHandle.unregisterCallbackCount)
        assertNull(service.activeProjectionHandle)
        assertNull(service.activeCallback)

        // 2. Locks released
        assertEquals(1, lockFactory.fakeMulticast.releaseCount)
        assertFalse(lockFactory.fakeMulticast.isHeld)
        assertEquals(1, lockFactory.fakeWifi.releaseCount)
        assertFalse(lockFactory.fakeWifi.isHeld)
        assertEquals(1, lockFactory.fakeWake.releaseCount)
        assertFalse(lockFactory.fakeWake.isHeld)

        // 3. Foreground stopped
        assertEquals(1, foregroundDelegate.stopForegroundCount)

        // 4. Service State is Stopped
        assertFalse(service.isCapturing)
        assertEquals(CaptureServiceState.Stopped, service.captureState.value)
    }

    @Test
    fun testSystemCallbackOnStop_TriggersTeardown() {
        val testIntent = Intent()
        service.startCapture(Activity.RESULT_OK, testIntent)
        assertTrue(service.isCapturing)

        val callback = projectionProvider.fakeHandle.registeredCallback
        assertNotNull(callback)

        // System fires onStop()
        callback?.onStop()

        // Service cleanly cleans up and stops
        assertFalse(service.isCapturing)
        assertNull(service.activeProjectionHandle)
        assertEquals(1, lockFactory.fakeMulticast.releaseCount)
        assertEquals(1, foregroundDelegate.stopForegroundCount)
        assertEquals(CaptureServiceState.Stopped, service.captureState.value)
    }

    @Test
    fun testSecurityExceptionHandling_CatchesGracefullyAndTransitionsToError() {
        projectionProvider.shouldThrowSecurityException = true
        val testIntent = Intent()

        // Should not throw uncaught exception
        service.startCapture(Activity.RESULT_OK, testIntent)

        // Verifies transition to Error state
        assertTrue(service.captureState.value is CaptureServiceState.Error)
        val errorState = service.captureState.value as CaptureServiceState.Error
        assertTrue(errorState.message.contains("SecurityException"))

        // Verifies cleanup occurred
        assertFalse(service.isCapturing)
        assertNull(service.activeProjectionHandle)
        assertEquals(1, lockFactory.fakeMulticast.releaseCount)
        assertEquals(1, foregroundDelegate.stopForegroundCount)
    }

    @Test
    fun testNullMediaProjection_TransitionsToError() {
        projectionProvider.shouldReturnNull = true
        val testIntent = Intent()

        service.startCapture(Activity.RESULT_OK, testIntent)

        assertTrue(service.captureState.value is CaptureServiceState.Error)
        val errorState = service.captureState.value as CaptureServiceState.Error
        assertTrue(errorState.message.contains("null"))

        assertFalse(service.isCapturing)
        assertEquals(1, foregroundDelegate.stopForegroundCount)
    }

    @Test
    fun testLocalBinder() {
        val binder = service.onBind(null)
        assertTrue(binder is RoomBeatCaptureService.LocalBinder)
        assertEquals(service, (binder as RoomBeatCaptureService.LocalBinder).getService())
    }

    @Test
    fun testDeniedResultCode_TransitionsToError() {
        val testIntent = Intent()
        // User explicitly denied consent in dialog (RESULT_CANCELED)
        service.startCapture(Activity.RESULT_CANCELED, testIntent)

        assertTrue(service.captureState.value is CaptureServiceState.Error)
        val errorState = service.captureState.value as CaptureServiceState.Error
        assertTrue(errorState.message.contains("consent denied"))

        assertFalse(service.isCapturing)
        assertNull(service.activeProjectionHandle)
        assertEquals(1, foregroundDelegate.stopForegroundCount)
    }

    @Test
    fun testStartCapture_ReplacesActiveProjectionIfNewTokenProvided() {
        val intent1 = Intent()
        service.startCapture(Activity.RESULT_OK, intent1)
        assertTrue(service.isCapturing)
        assertEquals(1, projectionProvider.getMediaProjectionCount)

        val firstHandle = projectionProvider.fakeHandle

        // Second call with new valid token
        val intent2 = Intent()
        service.startCapture(Activity.RESULT_OK, intent2)

        assertTrue(service.isCapturing)
        // First handle was stopped
        assertEquals(1, firstHandle.stopCount)
        assertEquals(2, projectionProvider.getMediaProjectionCount)
    }

    @Test
    fun testStopCapture_IdempotentWhenAlreadyStopped() {
        // Calling stop when not capturing does not throw or double stop
        service.stopCapture()
        assertEquals(0, foregroundDelegate.stopForegroundCount)

        val testIntent = Intent()
        service.startCapture(Activity.RESULT_OK, testIntent)
        assertTrue(service.isCapturing)

        service.stopCapture()
        assertEquals(1, foregroundDelegate.stopForegroundCount)

        // Second stop call should be no-op
        service.stopCapture()
        assertEquals(1, foregroundDelegate.stopForegroundCount)
    }

    @Test
    fun testOnProjectionStoppedListener_InvokedOnCallback() {
        var listenerInvoked = false
        service.onProjectionStoppedListener = {
            listenerInvoked = true
        }

        val testIntent = Intent()
        service.startCapture(Activity.RESULT_OK, testIntent)

        val callback = projectionProvider.fakeHandle.registeredCallback
        assertNotNull(callback)

        callback?.onStop()
        assertTrue(listenerInvoked)
        assertFalse(service.isCapturing)
    }

    @Test
    fun testCallback_ContentResizeAndVisibilityMethodsDoNotThrow() {
        val testIntent = Intent()
        service.startCapture(Activity.RESULT_OK, testIntent)

        val callback = projectionProvider.fakeHandle.registeredCallback
        assertNotNull(callback)

        // API 34+ methods
        callback?.onCapturedContentResize(1920, 1080)
        callback?.onCapturedContentVisibilityChanged(true)
        callback?.onCapturedContentVisibilityChanged(false)
        assertTrue(service.isCapturing)
    }

    @Test
    fun testStartStandbyMode_WithoutResultData() {
        // Starting capture service in standby (e.g. before user launches capture)
        service.startCapture()

        assertTrue(service.isCapturing)
        assertTrue(service.isForegroundActive)
        assertNull(service.activeProjectionHandle)
        assertEquals(CaptureServiceState.Active, service.captureState.value)
    }
}
