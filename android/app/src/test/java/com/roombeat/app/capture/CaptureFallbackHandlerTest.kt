package com.roombeat.app.capture

import com.roombeat.app.source.capture.InstalledMediaApp
import com.roombeat.app.source.capture.MediaAppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureFallbackHandlerTest {

    private class FakeClock {
        var currentTimeMs: Long = 100_000L

        fun advance(millis: Long) {
            currentTimeMs += millis
        }
    }

    private lateinit var clock: FakeClock
    private lateinit var handler: CaptureFallbackHandler
    private lateinit var testApp: InstalledMediaApp

    @Before
    fun setUp() {
        clock = FakeClock()
        handler = CaptureFallbackHandler(
            silenceTimeoutMs = 6000L,
            silenceThresholdDbfs = -50.0,
            timeProvider = { clock.currentTimeMs }
        )
        testApp = InstalledMediaApp(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            category = MediaAppCategory.VIDEO,
            isKnownOptOutApp = false
        )
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(FallbackState.Idle, handler.state.value)
        assertNull(handler.launchedApp)
    }

    @Test
    fun testOnAppLaunched_TransitionsToMonitoring() {
        handler.onAppLaunched(testApp)

        val state = handler.state.value
        assertTrue(state is FallbackState.Monitoring)
        val monitoring = state as FallbackState.Monitoring
        assertEquals("YouTube", monitoring.launchedApp.appName)
        assertEquals(0L, monitoring.elapsedSilenceMs)
        assertEquals(6000L, monitoring.timeoutMs)
        assertEquals(testApp, handler.launchedApp)
    }

    @Test
    fun testSilentFramesWithinTimeout_RemainInMonitoring() {
        handler.onAppLaunched(testApp)

        // Advance 2 seconds into silence
        clock.advance(2000L)
        handler.onFrameProcessed(isSilent = true, rmsDbfs = -90.0)

        val state1 = handler.state.value
        assertTrue(state1 is FallbackState.Monitoring)
        assertEquals(2000L, (state1 as FallbackState.Monitoring).elapsedSilenceMs)

        // Advance another 2 seconds (total 4s, still below 6s threshold)
        clock.advance(2000L)
        handler.onFrameProcessed(isSilent = true, rmsDbfs = -100.0)

        val state2 = handler.state.value
        assertTrue(state2 is FallbackState.Monitoring)
        assertEquals(4000L, (state2 as FallbackState.Monitoring).elapsedSilenceMs)
    }

    @Test
    fun testProlongedSilence_TriggersOptOutDetectedAndCallback() {
        var triggeredEvent: FallbackEvent.SilenceWarningTriggered? = null
        handler.onFallbackTriggered = { triggeredEvent = it }

        handler.onAppLaunched(testApp)

        // Advance 6.5 seconds with silence frames (exceeds 6.0s timeout)
        clock.advance(6500L)
        handler.onFrameProcessed(isSilent = true, rmsDbfs = -120.0)

        val state = handler.state.value
        assertTrue(state is FallbackState.OptOutDetected)
        val optOut = state as FallbackState.OptOutDetected
        assertEquals(testApp, optOut.launchedApp)
        assertEquals(6500L, optOut.durationSilentMs)

        // Verify callback was invoked
        assertNotNull(triggeredEvent)
        assertEquals(testApp, triggeredEvent?.app)
        assertEquals(6500L, triggeredEvent?.durationSilentMs)
        assertTrue(triggeredEvent?.guidanceMessage?.contains("FLAG_NO_SYSTEM_CAPTURE") == true)
    }

    @Test
    fun testAudioArrivalDuringMonitoring_TransitionsToActiveAudio() {
        var recoveredEvent: FallbackEvent.AudioStreamRecovered? = null
        handler.onStreamRecovered = { recoveredEvent = it }

        handler.onAppLaunched(testApp)
        clock.advance(2000L)

        // Frame with loud audio arrives (-18.0 dBFS > -50.0 dBFS)
        handler.onFrameProcessed(isSilent = false, rmsDbfs = -18.0)

        val state = handler.state.value
        assertTrue(state is FallbackState.ActiveAudio)
        val active = state as FallbackState.ActiveAudio
        assertEquals(testApp, active.launchedApp)
        assertEquals(-18.0, active.rmsDbfs, 0.001)

        assertNotNull(recoveredEvent)
        assertEquals(testApp, recoveredEvent?.app)
        assertEquals(-18.0, recoveredEvent?.rmsDbfs ?: 0.0, 0.001)
    }

    @Test
    fun testAudioArrivalAfterOptOut_RecoversCapturePipeline() {
        var recoveredEvent: FallbackEvent.AudioStreamRecovered? = null
        handler.onStreamRecovered = { recoveredEvent = it }

        handler.onAppLaunched(testApp)

        // Trigger opt-out warning after 7s silence
        clock.advance(7000L)
        handler.onFrameProcessed(isSilent = true, rmsDbfs = -120.0)
        assertTrue(handler.state.value is FallbackState.OptOutDetected)

        // User unpauses player or switches track; active audio arrives
        clock.advance(1000L)
        handler.onFrameProcessed(isSilent = false, rmsDbfs = -12.5)

        val state = handler.state.value
        assertTrue(state is FallbackState.ActiveAudio)
        assertEquals(testApp, (state as FallbackState.ActiveAudio).launchedApp)

        assertNotNull(recoveredEvent)
        assertEquals(testApp, recoveredEvent?.app)
        assertEquals(-12.5, recoveredEvent?.rmsDbfs ?: 0.0, 0.001)
    }

    @Test
    fun testDismissWarning_ReturnsToIdle() {
        handler.onAppLaunched(testApp)
        clock.advance(7000L)
        handler.onFrameProcessed(isSilent = true)
        assertTrue(handler.state.value is FallbackState.OptOutDetected)

        handler.dismissWarning()
        assertEquals(FallbackState.Idle, handler.state.value)
    }

    @Test
    fun testReset_ClearsActiveAppAndReturnsToIdle() {
        handler.onAppLaunched(testApp)
        clock.advance(2000L)
        handler.onFrameProcessed(isSilent = false, rmsDbfs = -10.0)
        assertEquals(testApp, handler.launchedApp)

        handler.reset()
        assertEquals(FallbackState.Idle, handler.state.value)
        assertNull(handler.launchedApp)
    }

    @Test
    fun testCapturedAudioFrame_Integration() {
        handler.onAppLaunched(testApp)

        val silentPcm = ShortArray(1920)
        val silentFrame = CapturedAudioFrame(
            pcmData = silentPcm,
            isSilent = true,
            rmsDbfs = -120.0
        )

        clock.advance(1000L)
        handler.onAudioFrame(silentFrame)

        val state1 = handler.state.value
        assertTrue(state1 is FallbackState.Monitoring)
        assertEquals(1000L, (state1 as FallbackState.Monitoring).elapsedSilenceMs)

        // Now feed an active audio frame
        val activePcm = ShortArray(1920) { 10000 }
        val activeFrame = CapturedAudioFrame(
            pcmData = activePcm,
            isSilent = false,
            rmsDbfs = -10.3
        )
        clock.advance(500L)
        handler.onAudioFrame(activeFrame)

        val state2 = handler.state.value
        assertTrue(state2 is FallbackState.ActiveAudio)
        assertEquals(-10.3, (state2 as FallbackState.ActiveAudio).rmsDbfs, 0.001)
    }

    @Test
    fun testFallbackDestinationsEnum() {
        val destinations = FallbackDestination.values()
        assertEquals(2, destinations.size)
        assertEquals(FallbackDestination.LOCAL_STORAGE, FallbackDestination.valueOf("LOCAL_STORAGE"))
        assertEquals(FallbackDestination.SPOTIFY_REMOTE, FallbackDestination.valueOf("SPOTIFY_REMOTE"))
    }
}
