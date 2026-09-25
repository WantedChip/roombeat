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

        var writtenFloatFrames = 0
        var writtenShortFrames = 0
        var availableFramesValue = 0
        var clearBufferCallCount = 0
        var underrunCountValue = 0L

        override fun writeAudioFrames(audioData: FloatArray, numFrames: Int): Int {
            writtenFloatFrames += numFrames
            return numFrames
        }

        override fun writePcm16Frames(audioData: ShortArray, numFrames: Int): Int {
            writtenShortFrames += numFrames
            return numFrames
        }

        override fun getAvailableFrames(): Int {
            return availableFramesValue
        }

        override fun clearBuffer() {
            clearBufferCallCount++
            availableFramesValue = 0
        }

        override fun getUnderrunCount(): Long {
            return underrunCountValue
        }

        var lastAttachedHandle: Long = -1L
        override fun attachJitterBuffer(handle: Long): Boolean {
            lastAttachedHandle = handle
            return true
        }

        var pushedChunksCount = 0
        var lastPushedSeq: Long = -1L
        var lastPushedPresentationTime: Long = -1L
        var lastPushedDataSize: Int = 0

        override fun pushAudioChunk(
            seq: Long,
            presentationTimeUs: Long,
            opusData: ByteArray,
            offset: Int,
            length: Int
        ): Boolean {
            pushedChunksCount++
            lastPushedSeq = seq
            lastPushedPresentationTime = presentationTimeUs
            lastPushedDataSize = length
            return true
        }

        var lastChannelVolume: Float = 0.0f
        var setChannelVolumeCallCount = 0
        override fun setChannelVolume(volumeDb: Float) {
            setChannelVolumeCallCount++
            lastChannelVolume = volumeDb
        }

        var lastMasterVolume: Float = 0.0f
        var setMasterVolumeCallCount = 0
        override fun setMasterVolume(volumeDb: Float) {
            setMasterVolumeCallCount++
            lastMasterVolume = volumeDb
        }

        var lastIsMuted: Boolean = false
        var setMutedCallCount = 0
        override fun setMuted(isMuted: Boolean) {
            setMutedCallCount++
            lastIsMuted = isMuted
        }

        var encodeFrameCallCount = 0
        var lastEncodedPcmSize = 0
        var encodeFrameReturnValue = 100
        override fun encodeFrame(pcmData: ShortArray, outputBuffer: ByteArray): Int {
            encodeFrameCallCount++
            lastEncodedPcmSize = pcmData.size
            if (outputBuffer.size >= 2) {
                outputBuffer[0] = 0x4F
                outputBuffer[1] = 0x50
            }
            return encodeFrameReturnValue
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

    // --- Audio Buffer & Underrun Operations ---

    @Test
    fun writeAudioFrames_delegatesToBridge() {
        val floatData = FloatArray(960 * 2) { 0.5f }
        val written = engineWithBridge.writeAudioFrames(floatData, 960)
        assertEquals(960, written)
        assertEquals(960, fakeBridge.writtenFloatFrames)
    }

    @Test
    fun writePcm16Frames_delegatesToBridge() {
        val shortData = ShortArray(480 * 2) { 1000 }
        val written = engineWithBridge.writePcm16Frames(shortData, 480)
        assertEquals(480, written)
        assertEquals(480, fakeBridge.writtenShortFrames)
    }

    @Test
    fun getAvailableFrames_queriesBridge() {
        fakeBridge.availableFramesValue = 1920
        assertEquals(1920, engineWithBridge.getAvailableFrames())
    }

    @Test
    fun clearBuffer_delegatesToBridge() {
        fakeBridge.availableFramesValue = 960
        assertEquals(960, engineWithBridge.getAvailableFrames())
        engineWithBridge.clearBuffer()
        assertEquals(1, fakeBridge.clearBufferCallCount)
        assertEquals(0, engineWithBridge.getAvailableFrames())
    }

    @Test
    fun getUnderrunCount_queriesBridge() {
        fakeBridge.underrunCountValue = 480L
        assertEquals(480L, engineWithBridge.getUnderrunCount())
    }

    @Test
    fun hostJvmEnvironment_bufferOperationsReturnSafeDefaults() {
        val floatData = FloatArray(100)
        val shortData = ShortArray(100)
        assertEquals(0, defaultHostEngine.writeAudioFrames(floatData, 50))
        assertEquals(0, defaultHostEngine.writePcm16Frames(shortData, 50))
        assertEquals(0, defaultHostEngine.getAvailableFrames())
        assertEquals(0L, defaultHostEngine.getUnderrunCount())
        defaultHostEngine.clearBuffer() // should not throw
    }

    @Test
    fun companionApi_bufferOperationsExecuteSafely() {
        val floatData = FloatArray(100)
        val shortData = ShortArray(100)
        assertEquals(0, NativeAudioEngine.writeAudioFrames(floatData, 50))
        assertEquals(0, NativeAudioEngine.writePcm16Frames(shortData, 50))
        assertEquals(0, NativeAudioEngine.getAvailableFrames())
        assertEquals(0L, NativeAudioEngine.getUnderrunCount())
        NativeAudioEngine.clearBuffer()
    }

    @Test
    fun attachJitterBuffer_delegatesToBridge() {
        val buffer = com.roombeat.app.audio.buffer.AudioJitterBuffer()
        val result = engineWithBridge.attachJitterBuffer(buffer)
        assertTrue(result)
        assertEquals(buffer.handle, fakeBridge.lastAttachedHandle)
        buffer.close()
    }

    @Test
    fun attachJitterBuffer_nullBufferPassesZeroHandle() {
        val result = engineWithBridge.attachJitterBuffer(null)
        assertTrue(result)
        assertEquals(0L, fakeBridge.lastAttachedHandle)
    }

    @Test
    fun hostJvmEnvironment_attachJitterBufferReturnsFalse() {
        val buffer = com.roombeat.app.audio.buffer.AudioJitterBuffer()
        assertFalse(defaultHostEngine.attachJitterBuffer(buffer))
        assertFalse(NativeAudioEngine.attachJitterBuffer(buffer))
        buffer.close()
    }

    @Test
    fun pushAudioChunk_delegatesToBridge() {
        val dummyData = byteArrayOf(1, 2, 3, 4)
        val result = engineWithBridge.pushAudioChunk(101L, 202020L, dummyData)
        assertTrue(result)
        assertEquals(1, fakeBridge.pushedChunksCount)
        assertEquals(101L, fakeBridge.lastPushedSeq)
        assertEquals(202020L, fakeBridge.lastPushedPresentationTime)
        assertEquals(4, fakeBridge.lastPushedDataSize)
    }

    @Test
    fun hostJvmEnvironment_pushAudioChunkWithoutBridgeReturnsFalse() {
        val dummyData = byteArrayOf(1, 2, 3, 4)
        assertFalse(defaultHostEngine.pushAudioChunk(101L, 202020L, dummyData))
        assertFalse(NativeAudioEngine.pushAudioChunk(101L, 202020L, dummyData))
    }

    @Test
    fun setChannelVolume_delegatesToBridgeAndUpdatesState() {
        engineWithBridge.setChannelVolume(-6.0f)
        assertEquals(-6.0f, engineWithBridge.channelVolumeDb, 0.001f)
        assertEquals(1, fakeBridge.setChannelVolumeCallCount)
        assertEquals(-6.0f, fakeBridge.lastChannelVolume, 0.001f)

        // Test without bridge (headless JVM)
        defaultHostEngine.setChannelVolume(3.0f)
        assertEquals(3.0f, defaultHostEngine.channelVolumeDb, 0.001f)
    }

    @Test
    fun setMasterVolume_delegatesToBridgeAndUpdatesState() {
        engineWithBridge.setMasterVolume(2.5f)
        assertEquals(2.5f, engineWithBridge.masterVolumeDb, 0.001f)
        assertEquals(1, fakeBridge.setMasterVolumeCallCount)
        assertEquals(2.5f, fakeBridge.lastMasterVolume, 0.001f)

        defaultHostEngine.setMasterVolume(-12.0f)
        assertEquals(-12.0f, defaultHostEngine.masterVolumeDb, 0.001f)
    }

    @Test
    fun setMuted_delegatesToBridgeAndUpdatesState() {
        assertFalse(engineWithBridge.isMuted)
        engineWithBridge.setMuted(true)
        assertTrue(engineWithBridge.isMuted)
        assertEquals(1, fakeBridge.setMutedCallCount)
        assertTrue(fakeBridge.lastIsMuted)

        engineWithBridge.setMuted(false)
        assertFalse(engineWithBridge.isMuted)
        assertEquals(2, fakeBridge.setMutedCallCount)
        assertFalse(fakeBridge.lastIsMuted)
    }

    @Test
    fun companion_volumeMethods_delegateToDefaultInstance() {
        NativeAudioEngine.setChannelVolume(-3.0f)
        assertEquals(-3.0f, NativeAudioEngine.channelVolumeDb, 0.001f)

        NativeAudioEngine.setMasterVolume(1.0f)
        assertEquals(1.0f, NativeAudioEngine.masterVolumeDb, 0.001f)

        NativeAudioEngine.setMuted(true)
        assertTrue(NativeAudioEngine.isMuted)
        NativeAudioEngine.setMuted(false)
        assertFalse(NativeAudioEngine.isMuted)
    }

    @Test
    fun encodeFrame_delegatesToBridgeWhenPresent() {
        val pcm = ShortArray(1920) { (it % 100).toShort() }
        val out = ByteArray(4000)

        val bytes = engineWithBridge.encodeFrame(pcm, out)
        assertEquals(100, bytes)
        assertEquals(1, fakeBridge.encodeFrameCallCount)
        assertEquals(1920, fakeBridge.lastEncodedPcmSize)
        assertEquals(0x4F.toByte(), out[0])
        assertEquals(0x50.toByte(), out[1])

        val allocatedBytes = engineWithBridge.encodeFrame(pcm)
        assertNotNull(allocatedBytes)
        assertEquals(100, allocatedBytes!!.size)
    }

    @Test
    fun encodeFrame_fallbackToJvmMockWhenNoBridge() {
        val pcm = ShortArray(1920) { (it % 100).toShort() }
        val out = ByteArray(4000)

        val bytes = defaultHostEngine.encodeFrame(pcm, out)
        assertTrue(bytes > 0)
        // Verify mock opus header 'OPUS'
        assertEquals(0x4F.toByte(), out[0]) // 'O'
        assertEquals(0x50.toByte(), out[1]) // 'P'
        assertEquals(0x55.toByte(), out[2]) // 'U'
        assertEquals(0x53.toByte(), out[3]) // 'S'

        val allocatedBytes = defaultHostEngine.encodeFrame(pcm)
        assertNotNull(allocatedBytes)
        assertTrue(allocatedBytes!!.size > 0)
        assertEquals(0x4F.toByte(), allocatedBytes[0])
    }

    @Test
    fun companion_encodeFrame_delegatesToDefaultInstance() {
        val pcm = ShortArray(1920) { (it % 100).toShort() }
        val out = ByteArray(4000)

        val bytes = NativeAudioEngine.encodeFrame(pcm, out)
        assertTrue(bytes > 0)

        val allocated = NativeAudioEngine.encodeFrame(pcm)
        assertNotNull(allocated)
        assertTrue(allocated!!.size > 0)
    }
}
