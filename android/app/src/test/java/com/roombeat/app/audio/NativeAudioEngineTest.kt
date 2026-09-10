package com.roombeat.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeAudioEngineTest {

    private class FakeAudioEngineBridge(
        var initReturnCode: Int = NativeAudioEngine.SUCCESS,
        var startReturnCode: Int = NativeAudioEngine.SUCCESS,
        var stopReturnCode: Int = NativeAudioEngine.SUCCESS,
        var teardownReturnCode: Int = NativeAudioEngine.SUCCESS,
        var latencyValue: Int = 15,
        var throwExceptionOnStart: Boolean = false
    ) : AudioEngineBridge {
        var initCallCount = 0
        var startCallCount = 0
        var stopCallCount = 0
        var teardownCallCount = 0
        var latencyCallCount = 0

        override fun initEngine(): Int {
            initCallCount++
            return initReturnCode
        }

        override fun startStream(): Int {
            startCallCount++
            if (throwExceptionOnStart) {
                throw RuntimeException("Simulated native stream fault")
            }
            return startReturnCode
        }

        override fun stopStream(): Int {
            stopCallCount++
            return stopReturnCode
        }

        override fun getAudioLatencyMillis(): Int {
            latencyCallCount++
            return latencyValue
        }

        override fun teardownEngine(): Int {
            teardownCallCount++
            return teardownReturnCode
        }
    }

    private lateinit var fakeBridge: FakeAudioEngineBridge
    private lateinit var engineWithBridge: NativeAudioEngine
    private lateinit var defaultHostEngine: NativeAudioEngine

    @Before
    fun setUp() {
        fakeBridge = FakeAudioEngineBridge()
        engineWithBridge = NativeAudioEngine(fakeBridge)
        defaultHostEngine = NativeAudioEngine()
    }

    // --- Host JVM Fallback & Safety Tests (No Native Library Loaded) ---

    @Test
    fun hostJvmEnvironment_gracefullyHandlesUnloadedLibrary() {
        // In JVM host tests, native shared library cannot be loaded
        assertFalse(defaultHostEngine.isLibraryAvailable)
        assertEquals(AudioEngineState.UNINITIALIZED, defaultHostEngine.state)
        assertNull(defaultHostEngine.lastError)
    }

    @Test
    fun hostJvmEnvironment_initReturnsErrorWithoutCrashing() {
        val result = defaultHostEngine.initEngineWithResult()
        assertTrue(result.isError)
        val error = result as AudioEngineResult.Error
        assertEquals(NativeAudioEngine.ERROR_LIBRARY_NOT_LOADED, error.code)
        assertEquals(AudioEngineState.ERROR, defaultHostEngine.state)
        assertNotNull(defaultHostEngine.lastError)
        assertFalse(defaultHostEngine.initEngine())
    }

    @Test
    fun hostJvmEnvironment_getAudioLatencyMillisReturnsSafeZero() {
        val latency = defaultHostEngine.getAudioLatencyMillis()
        assertEquals(0, latency)
    }

    @Test
    fun hostJvmEnvironment_teardownResetsStateCleanly() {
        defaultHostEngine.initEngine()
        assertEquals(AudioEngineState.ERROR, defaultHostEngine.state)

        val teardownSuccess = defaultHostEngine.teardownEngine()
        assertTrue(teardownSuccess)
        assertEquals(AudioEngineState.UNINITIALIZED, defaultHostEngine.state)
        assertNull(defaultHostEngine.lastError)
    }

    // --- State Management & Lifecycle with Bridge ---

    @Test
    fun initialState_isUninitialized() {
        assertEquals(AudioEngineState.UNINITIALIZED, engineWithBridge.state)
        assertNull(engineWithBridge.lastError)
        assertTrue(engineWithBridge.isLibraryAvailable)
    }

    @Test
    fun initEngine_transitionsToInitialized() {
        val success = engineWithBridge.initEngine()
        assertTrue(success)
        assertEquals(AudioEngineState.INITIALIZED, engineWithBridge.state)
        assertEquals(1, fakeBridge.initCallCount)
        assertNull(engineWithBridge.lastError)
    }

    @Test
    fun initEngine_whenAlreadyInitialized_isIdempotent() {
        engineWithBridge.initEngine()
        val secondCall = engineWithBridge.initEngine()
        assertTrue(secondCall)
        assertEquals(1, fakeBridge.initCallCount)
        assertEquals(AudioEngineState.INITIALIZED, engineWithBridge.state)
    }

    @Test
    fun startStream_transitionsToStreaming() {
        engineWithBridge.initEngine()
        val started = engineWithBridge.startStream()
        assertTrue(started)
        assertEquals(AudioEngineState.STREAMING, engineWithBridge.state)
        assertEquals(1, fakeBridge.startCallCount)
    }

    @Test
    fun startStream_whenUninitialized_autoInitializesFirst() {
        val started = engineWithBridge.startStream()
        assertTrue(started)
        assertEquals(1, fakeBridge.initCallCount)
        assertEquals(1, fakeBridge.startCallCount)
        assertEquals(AudioEngineState.STREAMING, engineWithBridge.state)
    }

    @Test
    fun startStream_whenAlreadyStreaming_isIdempotent() {
        engineWithBridge.startStream()
        val secondCall = engineWithBridge.startStream()
        assertTrue(secondCall)
        assertEquals(1, fakeBridge.startCallCount)
        assertEquals(AudioEngineState.STREAMING, engineWithBridge.state)
    }

    @Test
    fun stopStream_transitionsToStopped() {
        engineWithBridge.startStream()
        val stopped = engineWithBridge.stopStream()
        assertTrue(stopped)
        assertEquals(AudioEngineState.STOPPED, engineWithBridge.state)
        assertEquals(1, fakeBridge.stopCallCount)
    }

    @Test
    fun stopStream_whenNotStreaming_isIdempotent() {
        engineWithBridge.initEngine()
        val stopped = engineWithBridge.stopStream()
        assertTrue(stopped)
        assertEquals(AudioEngineState.STOPPED, engineWithBridge.state)
        assertEquals(0, fakeBridge.stopCallCount)
    }

    @Test
    fun getAudioLatencyMillis_queriesBridgeAndClampsNegatives() {
        fakeBridge.latencyValue = 24
        assertEquals(24, engineWithBridge.getAudioLatencyMillis())

        fakeBridge.latencyValue = -5
        assertEquals(0, engineWithBridge.getAudioLatencyMillis())
    }

    @Test
    fun teardownEngine_resetsStateToUninitialized() {
        engineWithBridge.startStream()
        val tornDown = engineWithBridge.teardownEngine()
        assertTrue(tornDown)
        assertEquals(AudioEngineState.UNINITIALIZED, engineWithBridge.state)
        assertEquals(1, fakeBridge.teardownCallCount)
        assertNull(engineWithBridge.lastError)
    }

    // --- Error Handling & Fault Tolerance ---

    @Test
    fun initEngine_failureTransitionsToError() {
        fakeBridge.initReturnCode = NativeAudioEngine.ERROR_INVALID_STATE
        val success = engineWithBridge.initEngine()
        assertFalse(success)
        assertEquals(AudioEngineState.ERROR, engineWithBridge.state)
        assertNotNull(engineWithBridge.lastError)
    }

    @Test
    fun startStream_failureTransitionsToError() {
        fakeBridge.startReturnCode = NativeAudioEngine.ERROR_STREAM_START_FAILED
        val success = engineWithBridge.startStream()
        assertFalse(success)
        assertEquals(AudioEngineState.ERROR, engineWithBridge.state)
        assertNotNull(engineWithBridge.lastError)
    }

    @Test
    fun startStream_exceptionInBridgeTransitionsToErrorSafely() {
        fakeBridge.throwExceptionOnStart = true
        val success = engineWithBridge.startStream()
        assertFalse(success)
        assertEquals(AudioEngineState.ERROR, engineWithBridge.state)
        assertTrue(engineWithBridge.lastError!!.contains("Simulated native stream fault"))
    }

    // --- Static Companion Object API Verification ---

    @Test
    fun companionApi_returnsValidStateAndLatency() {
        val latency = NativeAudioEngine.getAudioLatencyMillis()
        assertTrue("Latency must be a non-negative integer", latency >= 0)
        assertNotNull(NativeAudioEngine.state)
    }
}
