package com.roombeat.app.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaProjectionHelperTest {

    private class FakeContext : ContextWrapper(null)

    @After
    fun tearDown() {
        MediaProjectionHelper.resetIntentFactory()
    }

    private class FakeScreenCaptureIntentFactory(
        var intentToReturn: Intent = Intent(),
        var shouldThrow: Boolean = false
    ) : ScreenCaptureIntentFactory {
        var createCount = 0
        var lastContext: Context? = null

        override fun createScreenCaptureIntent(context: Context): Intent {
            createCount++
            lastContext = context
            if (shouldThrow) {
                throw IllegalStateException("MediaProjectionManager is not available on this device")
            }
            return intentToReturn
        }
    }

    private class FakeMediaProjectionHandle(
        override val rawProjection: MediaProjection? = null
    ) : MediaProjectionHandle {
        var registeredCallback: MediaProjection.Callback? = null
        var lastHandler: Handler? = null
        var unregisterCount = 0
        var stopCount = 0

        override fun registerCallback(callback: MediaProjection.Callback, handler: Handler?) {
            registeredCallback = callback
            lastHandler = handler
        }

        override fun unregisterCallback(callback: MediaProjection.Callback) {
            unregisterCount++
            if (registeredCallback == callback) {
                registeredCallback = null
            }
        }

        override fun stop() {
            stopCount++
        }
    }

    private class FakeMediaProjectionProvider : MediaProjectionProvider {
        var lastResultCode: Int = 0
        var lastResultData: Intent? = null
        var handleToReturn: MediaProjectionHandle? = FakeMediaProjectionHandle()

        override fun getMediaProjection(resultCode: Int, resultData: Intent): MediaProjectionHandle? {
            lastResultCode = resultCode
            lastResultData = resultData
            return handleToReturn
        }
    }

    @Test
    fun testCaptureConsentResult_Granted() {
        val intent = Intent()
        val result = CaptureConsentResult.Granted(Activity.RESULT_OK, intent)

        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals(intent, result.data)
    }

    @Test
    fun testCaptureConsentResult_Denied() {
        val resultDefault = CaptureConsentResult.Denied()
        assertEquals(Activity.RESULT_CANCELED, resultDefault.resultCode)

        val resultCustom = CaptureConsentResult.Denied(123)
        assertEquals(123, resultCustom.resultCode)
    }

    @Test
    fun testCaptureConsentResult_Error() {
        val exception = IllegalStateException("Service unavailable")
        val result = CaptureConsentResult.Error("Failed to initialize capture", exception)

        assertEquals("Failed to initialize capture", result.message)
        assertEquals(exception, result.cause)
    }

    @Test
    fun testMediaProjectionContract_createIntent_WithFactory() {
        val expectedIntent = Intent()
        val fakeFactory = FakeScreenCaptureIntentFactory(expectedIntent)
        val contract = MediaProjectionContract(intentFactory = fakeFactory)

        val context = FakeContext()
        val created = contract.createIntent(context, Unit)
        assertEquals(1, fakeFactory.createCount)
        assertSame(expectedIntent, created)
        assertSame(context, fakeFactory.lastContext)
    }

    @Test
    fun testMediaProjectionContract_createIntent_FactoryThrows() {
        val fakeFactory = FakeScreenCaptureIntentFactory(shouldThrow = true)
        val contract = MediaProjectionContract(intentFactory = fakeFactory)

        try {
            contract.createIntent(FakeContext(), Unit)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("MediaProjectionManager") == true)
        }
    }

    @Test
    fun testMediaProjectionContract_parseResult_Success() {
        val contract = MediaProjectionContract()
        val intent = Intent()

        val parsed = contract.parseResult(Activity.RESULT_OK, intent)
        assertTrue(parsed is CaptureConsentResult.Granted)

        val granted = parsed as CaptureConsentResult.Granted
        assertEquals(Activity.RESULT_OK, granted.resultCode)
        assertEquals(intent, granted.data)
    }

    @Test
    fun testMediaProjectionContract_parseResult_Canceled() {
        val contract = MediaProjectionContract()

        val parsedNull = contract.parseResult(Activity.RESULT_CANCELED, null)
        assertTrue(parsedNull is CaptureConsentResult.Denied)
        assertEquals(Activity.RESULT_CANCELED, (parsedNull as CaptureConsentResult.Denied).resultCode)

        val parsedWithIntent = contract.parseResult(Activity.RESULT_CANCELED, Intent())
        assertTrue(parsedWithIntent is CaptureConsentResult.Denied)
        assertEquals(Activity.RESULT_CANCELED, (parsedWithIntent as CaptureConsentResult.Denied).resultCode)
    }

    @Test
    fun testMediaProjectionContract_parseResult_NullIntentWithResultOk() {
        val contract = MediaProjectionContract()

        // If resultCode is OK but intent is null, it cannot be granted
        val parsed = contract.parseResult(Activity.RESULT_OK, null)
        assertTrue(parsed is CaptureConsentResult.Denied)
        assertEquals(Activity.RESULT_OK, (parsed as CaptureConsentResult.Denied).resultCode)
    }

    @Test
    fun testMediaProjectionHelper_createCaptureIntent_CustomFactory() {
        val expectedIntent = Intent()
        val fakeFactory = FakeScreenCaptureIntentFactory(expectedIntent)
        MediaProjectionHelper.intentFactory = fakeFactory

        val context = FakeContext()
        val intent = MediaProjectionHelper.createCaptureIntent(context)

        assertEquals(1, fakeFactory.createCount)
        assertSame(expectedIntent, intent)
        assertSame(context, fakeFactory.lastContext)
    }

    @Test
    fun testMediaProjectionHelper_parseConsentResult() {
        val intent = Intent()

        val granted = MediaProjectionHelper.parseConsentResult(Activity.RESULT_OK, intent)
        assertTrue(granted is CaptureConsentResult.Granted)
        assertEquals(intent, (granted as CaptureConsentResult.Granted).data)

        val denied = MediaProjectionHelper.parseConsentResult(Activity.RESULT_CANCELED, null)
        assertTrue(denied is CaptureConsentResult.Denied)
        assertEquals(Activity.RESULT_CANCELED, (denied as CaptureConsentResult.Denied).resultCode)
    }

    @Test
    fun testMediaProjectionHelper_isConsentGranted() {
        val intent = Intent()
        assertTrue(MediaProjectionHelper.isConsentGranted(Activity.RESULT_OK, intent))
        assertFalse(MediaProjectionHelper.isConsentGranted(Activity.RESULT_CANCELED, intent))
        assertFalse(MediaProjectionHelper.isConsentGranted(Activity.RESULT_OK, null))
        assertFalse(MediaProjectionHelper.isConsentGranted(Activity.RESULT_CANCELED, null))
    }

    @Test
    fun testMediaProjectionHelper_extractMediaProjection() {
        val provider = FakeMediaProjectionProvider()
        val intent = Intent()

        val handle = MediaProjectionHelper.extractMediaProjection(provider, Activity.RESULT_OK, intent)
        assertNotNull(handle)
        assertEquals(Activity.RESULT_OK, provider.lastResultCode)
        assertSame(intent, provider.lastResultData)

        // When provider returns null
        provider.handleToReturn = null
        val nullHandle = MediaProjectionHelper.extractMediaProjection(provider, Activity.RESULT_OK, intent)
        assertNull(nullHandle)
    }

    @Test
    fun testFakeMediaProjectionHandleLifecycle() {
        val handle = FakeMediaProjectionHandle()
        val callback = object : MediaProjection.Callback() {}

        handle.registerCallback(callback, null)
        assertSame(callback, handle.registeredCallback)

        handle.unregisterCallback(callback)
        assertNull(handle.registeredCallback)
        assertEquals(1, handle.unregisterCount)

        handle.stop()
        assertEquals(1, handle.stopCount)
    }

    @Test
    fun testMediaProjectionHelper_sealedClassExhaustiveness() {
        val results: List<CaptureConsentResult> = listOf(
            CaptureConsentResult.Granted(Activity.RESULT_OK, Intent()),
            CaptureConsentResult.Denied(),
            CaptureConsentResult.Error("Error")
        )

        for (res in results) {
            val label = when (res) {
                is CaptureConsentResult.Granted -> "GRANTED"
                is CaptureConsentResult.Denied -> "DENIED"
                is CaptureConsentResult.Error -> "ERROR"
            }
            assertNotNull(label)
        }
    }
}
