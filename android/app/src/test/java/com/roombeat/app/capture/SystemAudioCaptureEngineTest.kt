package com.roombeat.app.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.projection.MediaProjection
import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SystemAudioCaptureEngineTest {

    private class FakeMediaProjectionHandle(
        override val rawProjection: MediaProjection? = null
    ) : MediaProjectionHandle {
        var isStopped = false

        override fun registerCallback(callback: MediaProjection.Callback, handler: Handler?) {}
        override fun unregisterCallback(callback: MediaProjection.Callback) {}
        override fun stop() {
            isStopped = true
        }
    }

    private class FakeAudioRecordFacade(
        override val state: Int = AudioRecordFacade.STATE_INITIALIZED,
        override var recordingState: Int = AudioRecordFacade.RECORDSTATE_STOPPED,
        override val sampleRate: Int = 48000,
        override val channelCount: Int = 2,
        override val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
        override val bufferSizeInFrames: Int = 3840
    ) : AudioRecordFacade {

        var startRecordingCount = 0
        var stopCount = 0
        var releaseCount = 0
        var shouldThrowOnStart = false
        var failRecordingStateOnStart = false

        // Audio simulation
        var framesQueue = CopyOnWriteArrayList<ShortArray>()
        var errorToReturnOnRead: Int? = null
        var partialReadChunkSize: Int? = null
        var readDelayMs: Long = 0L

        private val readCallCount = AtomicInteger(0)

        override fun startRecording() {
            startRecordingCount++
            if (shouldThrowOnStart) {
                throw IllegalStateException("Mock AudioRecord startRecording failed")
            }
            if (!failRecordingStateOnStart) {
                recordingState = AudioRecordFacade.RECORDSTATE_RECORDING
            }
        }

        override fun stop() {
            stopCount++
            recordingState = AudioRecordFacade.RECORDSTATE_STOPPED
        }

        override fun release() {
            releaseCount++
        }

        override fun read(
            audioData: ShortArray,
            offsetInShorts: Int,
            sizeInShorts: Int,
            readMode: Int
        ): Int {
            readCallCount.incrementAndGet()
            if (readDelayMs > 0) {
                try {
                    Thread.sleep(readDelayMs)
                } catch (e: InterruptedException) {
                    return AudioRecordFacade.ERROR_INVALID_OPERATION
                }
            }

            errorToReturnOnRead?.let { return it }

            if (framesQueue.isEmpty()) {
                // Return zero or block briefly to avoid spinning 100%
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    return AudioRecordFacade.ERROR_INVALID_OPERATION
                }
                return 0
            }

            val frame = framesQueue[0]
            val chunkSize = partialReadChunkSize ?: sizeInShorts
            val toCopy = minOf(chunkSize, sizeInShorts, frame.size)

            System.arraycopy(frame, 0, audioData, offsetInShorts, toCopy)

            if (toCopy == frame.size) {
                framesQueue.removeAt(0)
            } else {
                // Slice remainder
                val remainder = frame.copyOfRange(toCopy, frame.size)
                framesQueue[0] = remainder
            }

            return toCopy
        }
    }

    private class FakeAudioRecordFactory(
        var facadeToReturn: FakeAudioRecordFacade = FakeAudioRecordFacade()
    ) : AudioRecordFactory {
        var createCount = 0
        var lastMediaProjection: MediaProjection? = null
        var lastConfig: CaptureConfig? = null
        var shouldThrowOnCreate = false

        override fun createAudioRecord(
            mediaProjection: MediaProjection?,
            config: CaptureConfig
        ): AudioRecordFacade {
            createCount++
            lastMediaProjection = mediaProjection
            lastConfig = config
            if (shouldThrowOnCreate) {
                throw SecurityException("Mock factory creation failed")
            }
            return facadeToReturn
        }
    }

    @Test
    fun testCaptureConfig_Defaults() {
        val config = CaptureConfig()
        assertEquals(48000, config.sampleRate)
        assertEquals(AudioFormat.CHANNEL_IN_STEREO, config.channelMask)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, config.audioEncoding)
        assertEquals(960, config.frameSizePerChannel)
        assertEquals(2, config.channels)
        assertEquals(1920, config.frameSamples)
        assertEquals(3840, config.frameBytes)
        assertEquals(20L, config.frameDurationMs)
        assertEquals(
            listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN),
            config.matchingUsages
        )
    }

    @Test
    fun testCapturedAudioFrame_PropertiesAndEquality() {
        val data1 = ShortArray(1920) { 100 }
        val data2 = ShortArray(1920) { 100 }
        val data3 = ShortArray(1920) { 200 }

        val frame1 = CapturedAudioFrame(pcmData = data1, timestampNs = 12345L, isSilent = false, rmsDbfs = -20.0)
        val frame2 = CapturedAudioFrame(pcmData = data2, timestampNs = 12345L, isSilent = false, rmsDbfs = -20.0)
        val frame3 = CapturedAudioFrame(pcmData = data3, timestampNs = 12345L, isSilent = false, rmsDbfs = -20.0)

        assertEquals(960, frame1.frameSizePerChannel)
        assertEquals(20.0, frame1.durationMs, 0.001)
        assertEquals(frame1, frame2)
        assertEquals(frame1.hashCode(), frame2.hashCode())
        assertFalse(frame1 == frame3)
    }

    @Test
    fun testSuccessfulCapture_EmitsFramesToCallbackAndFlow() = runBlocking {
        val fakeFacade = FakeAudioRecordFacade()
        // Provide 3 frames of audio (each 1920 shorts)
        for (i in 1..3) {
            fakeFacade.framesQueue.add(ShortArray(1920) { (it * 10).toShort() })
        }

        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(
            audioRecordFactory = fakeFactory
        )

        val capturedFrames = CopyOnWriteArrayList<CapturedAudioFrame>()
        val latch = CountDownLatch(3)

        engine.onAudioFrameCaptured = { frame ->
            capturedFrames.add(frame)
            latch.countDown()
        }

        val started = engine.startCapture(mediaProjection = null)
        assertTrue("Capture should start successfully", started)
        assertTrue(engine.isCapturing)
        assertEquals(CaptureEngineState.Capturing, engine.captureState.value)
        assertEquals(1, fakeFactory.createCount)
        assertEquals(1, fakeFacade.startRecordingCount)

        val completed = latch.await(2, TimeUnit.SECONDS)
        assertTrue("Should have captured 3 frames", completed)
        assertEquals(3, capturedFrames.size)

        // Verify chunk size on each frame
        for (frame in capturedFrames) {
            assertEquals(1920, frame.pcmData.size)
            assertEquals(48000, frame.sampleRate)
            assertEquals(2, frame.channels)
        }

        assertEquals(3L, engine.totalFramesCaptured)

        engine.stopCapture()
        assertFalse(engine.isCapturing)
        assertEquals(CaptureEngineState.Stopped, engine.captureState.value)
        assertEquals(1, fakeFacade.stopCount)
        assertEquals(1, fakeFacade.releaseCount)
    }

    @Test
    fun testFrameChunking_Exact1920Samples() = runBlocking {
        val fakeFacade = FakeAudioRecordFacade()
        fakeFacade.framesQueue.add(ShortArray(1920) { 100 })

        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        val latch = CountDownLatch(1)
        var receivedFrame: CapturedAudioFrame? = null

        engine.onAudioFrameCaptured = { frame ->
            receivedFrame = frame
            latch.countDown()
        }

        engine.startCapture()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(receivedFrame)
        assertEquals(1920, receivedFrame!!.pcmData.size)
        assertEquals(960, receivedFrame!!.frameSizePerChannel)

        engine.stopCapture()
    }

    @Test
    fun testSilenceDetectorIntegration_FlagsSilentAndActiveFrames() = runBlocking {
        val fakeFacade = FakeAudioRecordFacade()
        // Frame 1: silent (all zeros)
        fakeFacade.framesQueue.add(ShortArray(1920) { 0 })
        // Frame 2: loud audio (sine amplitude 10,000)
        fakeFacade.framesQueue.add(ShortArray(1920) { 10000 })

        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        val frames = CopyOnWriteArrayList<CapturedAudioFrame>()
        val latch = CountDownLatch(2)

        engine.onAudioFrameCaptured = { frame ->
            frames.add(frame)
            latch.countDown()
        }

        engine.startCapture()
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertEquals(2, frames.size)
        assertTrue("Frame 1 should be silent", frames[0].isSilent)
        assertFalse("Frame 2 should be active", frames[1].isSilent)

        assertEquals(1L, engine.totalSilentFrames)
        assertEquals(1L, engine.totalActiveFrames)

        engine.stopCapture()
    }

    @Test
    fun testStartCapture_WithMediaProjectionHandle() {
        val fakeHandle = FakeMediaProjectionHandle(rawProjection = null)
        val fakeFacade = FakeAudioRecordFacade()
        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        val started = engine.startCapture(fakeHandle)
        assertTrue(started)
        assertTrue(engine.isCapturing)
        assertEquals(1, fakeFactory.createCount)

        engine.stopCapture()
    }

    @Test
    fun testStartCapture_FailsWhenRecordUninitialized() {
        val uninitFacade = FakeAudioRecordFacade(state = AudioRecordFacade.STATE_UNINITIALIZED)
        val fakeFactory = FakeAudioRecordFactory(uninitFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        var errorReported: String? = null
        engine.onError = { message, _ -> errorReported = message }

        val started = engine.startCapture()
        assertFalse("Should fail if record is uninitialized", started)
        assertFalse(engine.isCapturing)
        assertTrue(engine.captureState.value is CaptureEngineState.Error)
        assertNotNull(errorReported)
        assertEquals(1, uninitFacade.releaseCount)
    }

    @Test
    fun testStartCapture_FailsWhenStartRecordingThrows() {
        val fakeFacade = FakeAudioRecordFacade().apply { shouldThrowOnStart = true }
        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        var errorReported: String? = null
        engine.onError = { message, _ -> errorReported = message }

        val started = engine.startCapture()
        assertFalse("Should fail if startRecording throws", started)
        assertFalse(engine.isCapturing)
        assertTrue(engine.captureState.value is CaptureEngineState.Error)
        assertNotNull(errorReported)
        assertEquals(1, fakeFacade.releaseCount)
    }

    @Test
    fun testStartCapture_FailsWhenRecordingStateNotRecording() {
        val fakeFacade = FakeAudioRecordFacade().apply { failRecordingStateOnStart = true }
        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        var errorReported: String? = null
        engine.onError = { message, _ -> errorReported = message }

        val started = engine.startCapture()
        assertFalse(started)
        assertFalse(engine.isCapturing)
        assertTrue(engine.captureState.value is CaptureEngineState.Error)
        assertEquals(1, fakeFacade.stopCount)
        assertEquals(1, fakeFacade.releaseCount)
    }

    @Test
    fun testStartCapture_FailsWhenFactoryThrows() {
        val fakeFactory = FakeAudioRecordFactory().apply { shouldThrowOnCreate = true }
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        val started = engine.startCapture()
        assertFalse(started)
        assertTrue(engine.captureState.value is CaptureEngineState.Error)
    }

    @Test
    fun testReadLoop_HandlesPartialReads() = runBlocking {
        val fakeFacade = FakeAudioRecordFacade().apply {
            // Read returns 480 shorts at a time, requiring 4 reads to assemble 1920
            partialReadChunkSize = 480
        }
        fakeFacade.framesQueue.add(ShortArray(1920) { 50 })

        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        val latch = CountDownLatch(1)
        var receivedFrame: CapturedAudioFrame? = null

        engine.onAudioFrameCaptured = { frame ->
            receivedFrame = frame
            latch.countDown()
        }

        engine.startCapture()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(receivedFrame)
        assertEquals(1920, receivedFrame!!.pcmData.size)

        engine.stopCapture()
    }

    @Test
    fun testReadLoop_FatalErrorHandling() = runBlocking {
        val fakeFacade = FakeAudioRecordFacade().apply {
            errorToReturnOnRead = AudioRecordFacade.ERROR_DEAD_OBJECT
        }
        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        val latch = CountDownLatch(1)
        var reportedError: String? = null

        engine.onError = { message, _ ->
            reportedError = message
            latch.countDown()
        }

        engine.startCapture()
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertFalse(engine.isCapturing)
        assertTrue(engine.captureState.value is CaptureEngineState.Error)
        assertNotNull(reportedError)
        assertTrue(engine.totalReadErrors > 0)

        engine.stopCapture()
    }

    @Test
    fun testStopCapture_LifecycleAndIdempotence() {
        val fakeFacade = FakeAudioRecordFacade()
        val fakeFactory = FakeAudioRecordFactory(fakeFacade)
        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)

        engine.startCapture()
        assertTrue(engine.isCapturing)

        engine.stopCapture()
        assertFalse(engine.isCapturing)
        assertEquals(CaptureEngineState.Stopped, engine.captureState.value)
        assertEquals(1, fakeFacade.stopCount)
        assertEquals(1, fakeFacade.releaseCount)

        // Idempotent secondary call
        engine.stopCapture()
        assertEquals(1, fakeFacade.stopCount)
        assertEquals(1, fakeFacade.releaseCount)
    }

    @Test
    fun testRestartCapture() {
        val fakeFacade1 = FakeAudioRecordFacade()
        val fakeFacade2 = FakeAudioRecordFacade()

        var callCount = 0
        val fakeFactory = object : AudioRecordFactory {
            override fun createAudioRecord(
                mediaProjection: MediaProjection?,
                config: CaptureConfig
            ): AudioRecordFacade {
                callCount++
                return if (callCount == 1) fakeFacade1 else fakeFacade2
            }
        }

        val engine = SystemAudioCaptureEngine(audioRecordFactory = fakeFactory)
        engine.startCapture()
        assertTrue(engine.isCapturing)
        assertEquals(1, fakeFacade1.startRecordingCount)

        val restarted = engine.restartCapture()
        assertTrue(restarted)
        assertTrue(engine.isCapturing)
        assertEquals(1, fakeFacade1.stopCount)
        assertEquals(1, fakeFacade1.releaseCount)
        assertEquals(1, fakeFacade2.startRecordingCount)

        engine.stopCapture()
    }

    @Test
    fun testResetMetrics() {
        val engine = SystemAudioCaptureEngine()
        engine.resetMetrics()
        assertEquals(0L, engine.totalFramesCaptured)
        assertEquals(0L, engine.totalSilentFrames)
        assertEquals(0L, engine.totalActiveFrames)
        assertEquals(0L, engine.totalReadErrors)
    }
}
