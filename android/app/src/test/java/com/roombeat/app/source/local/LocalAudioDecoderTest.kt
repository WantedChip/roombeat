package com.roombeat.app.source.local

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.net.Uri
import com.roombeat.app.audio.codec.DefaultOpusCodecBridge
import com.roombeat.app.audio.codec.OpusCodec
import com.roombeat.app.audio.codec.OpusConstants
import com.roombeat.app.audio.codec.OpusEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class LocalAudioDecoderTest {

    private class FakeContext : ContextWrapper(null)

    /**
     * Fake MediaExtractor providing synthetic encoded frames and supporting seeking.
     */
    private class FakeMediaExtractor(
        val format: AudioTrackFormat = AudioTrackFormat(
            mimeType = "audio/mpeg",
            sampleRate = 44100,
            channelCount = 2,
            durationUs = 1_000_000L
        ),
        val totalPackets: Int = 10
    ) : MediaExtractorFacade {
        var isClosed = false
        var selectedTrack = -1
        var currentPacket = 0
        var currentSampleTimeUs = 0L
        var lastSeekTimeUs: Long? = null

        override fun setDataSource(context: Context, uri: Uri) {}

        override fun getTrackCount(): Int = 1

        override fun getTrackFormat(index: Int): AudioTrackFormat = format

        override fun selectTrack(index: Int) {
            selectedTrack = index
        }

        override fun readSampleData(byteBuffer: ByteBuffer, offset: Int): Int {
            if (currentPacket >= totalPackets) return -1
            byteBuffer.position(offset)
            // Put dummy encoded payload
            byteBuffer.put("FAKE_AUDIO".toByteArray())
            return 10
        }

        override fun getSampleTime(): Long = currentSampleTimeUs

        override fun getSampleFlags(): Int = 1

        override fun advance(): Boolean {
            currentPacket++
            currentSampleTimeUs += 20_000L
            return currentPacket < totalPackets
        }

        override fun seekTo(timeUs: Long, mode: Int) {
            lastSeekTimeUs = timeUs
            currentSampleTimeUs = timeUs
            currentPacket = (timeUs / 20_000L).toInt().coerceIn(0, totalPackets)
        }

        override fun close() {
            isClosed = true
        }
    }

    /**
     * Fake MediaCodec generating synthetic PCM audio samples when fed input.
     */
    private class FakeMediaCodec(
        val outputSampleRate: Int = 44100,
        val outputChannels: Int = 2,
        val samplesPerOutputBuffer: Int = 1000
    ) : MediaCodecFacade {
        var isStarted = false
        var isFlushed = false
        var isClosed = false
        var formatConfigured: AudioTrackFormat? = null

        private val inputBuffers = Array(4) { ByteBuffer.allocate(4096) }
        private val outputBuffers = Array(4) { ByteBuffer.allocate(8192) }
        private var queuedFrames = 0
        private var inputEosSeen = false
        private var outputEosEmitted = false
        private var currentOutPresentationTimeUs = 0L

        override fun configure(format: AudioTrackFormat) {
            this.formatConfigured = format
        }

        override fun start() {
            isStarted = true
        }

        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            return if (!inputEosSeen) 0 else MediaCodecStatus.INFO_TRY_AGAIN_LATER
        }

        override fun getInputBuffer(index: Int): ByteBuffer? = inputBuffers[index]

        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            if ((flags and CodecBufferInfo.BUFFER_FLAG_END_OF_STREAM) != 0) {
                inputEosSeen = true
            }
            queuedFrames++
        }

        override fun dequeueOutputBuffer(info: CodecBufferInfo, timeoutUs: Long): Int {
            if (outputEosEmitted) return MediaCodecStatus.INFO_TRY_AGAIN_LATER

            if (queuedFrames > 0) {
                queuedFrames--
                val outBuf = outputBuffers[0]
                outBuf.clear()
                outBuf.order(ByteOrder.LITTLE_ENDIAN)

                // Write synthetic PCM16 stereo samples (e.g. constant amplitude)
                val totalShorts = samplesPerOutputBuffer
                for (i in 0 until totalShorts) {
                    outBuf.putShort(1000.toShort())
                }

                info.offset = 0
                info.size = totalShorts * 2
                info.presentationTimeUs = currentOutPresentationTimeUs
                currentOutPresentationTimeUs += 20_000L
                info.flags = 0

                if (inputEosSeen && queuedFrames == 0) {
                    info.flags = CodecBufferInfo.BUFFER_FLAG_END_OF_STREAM
                    outputEosEmitted = true
                }

                return 0
            }

            if (inputEosSeen && !outputEosEmitted) {
                info.offset = 0
                info.size = 0
                info.flags = CodecBufferInfo.BUFFER_FLAG_END_OF_STREAM
                outputEosEmitted = true
                return 0
            }

            return MediaCodecStatus.INFO_TRY_AGAIN_LATER
        }

        override fun getOutputBuffer(index: Int): ByteBuffer? = outputBuffers[index]

        override fun getOutputFormat(): AudioTrackFormat {
            return AudioTrackFormat(
                mimeType = "audio/raw",
                sampleRate = outputSampleRate,
                channelCount = outputChannels
            )
        }

        override fun releaseOutputBuffer(index: Int, render: Boolean) {}

        override fun flush() {
            isFlushed = true
            queuedFrames = 0
            inputEosSeen = false
            outputEosEmitted = false
        }

        override fun stop() {
            isStarted = false
        }

        override fun close() {
            isClosed = true
        }
    }

    @Test
    fun prepare_initializesExtractorAndCodecSuccessfully() {
        val fakeExtractor = FakeMediaExtractor()
        val fakeCodec = FakeMediaCodec()
        val context = FakeContext()
        val uri = TestUri("content://media/external/audio/media/100")

        val decoder = LocalAudioDecoder(
            context = context,
            uri = uri,
            extractorFactory = { fakeExtractor },
            codecFactory = { fakeCodec },
            opusEncoderFactory = { OpusEncoder(bridge = DefaultOpusCodecBridge.INSTANCE) }
        )

        val prepared = decoder.prepare()
        assertTrue(prepared)
        assertEquals(DecoderState.PREPARING, decoder.state.value)
        assertEquals(1_000_000L, decoder.durationUs.value)
        assertEquals(0, fakeExtractor.selectedTrack)
        assertTrue(fakeCodec.isStarted)

        decoder.close()
        assertTrue(fakeExtractor.isClosed)
        assertTrue(fakeCodec.isClosed)
    }

    @Test
    fun start_decodesAndEmits20msOpusFramesUntilCompleted() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fakeExtractor = FakeMediaExtractor(totalPackets = 5)
        val fakeCodec = FakeMediaCodec(outputSampleRate = 44100, outputChannels = 2, samplesPerOutputBuffer = 1000)
        val context = FakeContext()
        val uri = TestUri("content://media/external/audio/media/100")

        val receivedFrames = CopyOnWriteArrayList<DecodedOpusFrame>()
        var completionCalled = false

        val decoder = LocalAudioDecoder(
            context = context,
            uri = uri,
            extractorFactory = { fakeExtractor },
            codecFactory = { fakeCodec },
            opusEncoderFactory = { OpusEncoder(bridge = DefaultOpusCodecBridge.INSTANCE) },
            ioDispatcher = testDispatcher,
            paceToRealtime = false
        )

        decoder.onFrameListener = { frame ->
            receivedFrames.add(frame)
        }
        decoder.onCompletionListener = {
            completionCalled = true
        }

        decoder.start()
        advanceUntilIdle()

        assertTrue("Should have received at least 1 Opus frame", receivedFrames.size > 0)
        // Verify sequence continuity
        for (i in receivedFrames.indices) {
            assertEquals(i.toLong(), receivedFrames[i].seq)
            assertEquals(OpusConstants.FRAME_SIZE_SAMPLES, receivedFrames[i].sampleCountPerChannel)
            assertTrue("Opus payload should not be empty", receivedFrames[i].opusData.isNotEmpty())
        }

        assertEquals(DecoderState.COMPLETED, decoder.state.value)
        assertTrue("Completion callback should have been called", completionCalled)

        decoder.close()
    }

    @Test
    fun seekTo_updatesPositionAndFlushesPipeline() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fakeExtractor = FakeMediaExtractor(totalPackets = 20)
        val fakeCodec = FakeMediaCodec()
        val context = FakeContext()
        val uri = TestUri("content://media/external/audio/media/100")

        val decoder = LocalAudioDecoder(
            context = context,
            uri = uri,
            extractorFactory = { fakeExtractor },
            codecFactory = { fakeCodec },
            opusEncoderFactory = { OpusEncoder(bridge = DefaultOpusCodecBridge.INSTANCE) },
            ioDispatcher = testDispatcher
        )

        decoder.prepare()
        decoder.seekTo(400_000L)
        assertEquals(400_000L, decoder.positionUs.value)

        decoder.start()
        advanceUntilIdle()

        assertEquals(400_000L, fakeExtractor.lastSeekTimeUs)
        assertTrue(fakeCodec.isFlushed)

        decoder.close()
    }

    @Test
    fun pauseAndResume_lifecycleTransitionsCleanly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val fakeExtractor = FakeMediaExtractor(totalPackets = 10)
        val fakeCodec = FakeMediaCodec()
        val context = FakeContext()
        val uri = TestUri("content://media/external/audio/media/100")

        val decoder = LocalAudioDecoder(
            context = context,
            uri = uri,
            extractorFactory = { fakeExtractor },
            codecFactory = { fakeCodec },
            opusEncoderFactory = { OpusEncoder(bridge = DefaultOpusCodecBridge.INSTANCE) },
            ioDispatcher = testDispatcher
        )

        decoder.start()
        assertEquals(DecoderState.PLAYING, decoder.state.value)

        decoder.pause()
        assertEquals(DecoderState.PAUSED, decoder.state.value)

        decoder.resume()
        assertEquals(DecoderState.PLAYING, decoder.state.value)

        decoder.stop()
        assertEquals(DecoderState.STOPPED, decoder.state.value)

        decoder.close()
    }

    @Test
    fun memorySafety_steadyFootprintDuringContinuousDecoding() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        // Simulate decoding a long 100-packet audio file
        val fakeExtractor = FakeMediaExtractor(totalPackets = 100)
        val fakeCodec = FakeMediaCodec(outputSampleRate = 48000, outputChannels = 2, samplesPerOutputBuffer = 1920)
        val context = FakeContext()
        val uri = TestUri("content://media/external/audio/media/100")

        var frameCount = 0
        val decoder = LocalAudioDecoder(
            context = context,
            uri = uri,
            extractorFactory = { fakeExtractor },
            codecFactory = { fakeCodec },
            opusEncoderFactory = { OpusEncoder(bridge = DefaultOpusCodecBridge.INSTANCE) },
            ioDispatcher = testDispatcher
        )

        decoder.onFrameListener = { frameCount++ }

        val runtime = Runtime.getRuntime()
        System.gc()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()

        decoder.start()
        advanceUntilIdle()

        System.gc()
        val memAfter = runtime.totalMemory() - runtime.freeMemory()
        val memDeltaMb = (memAfter - memBefore) / (1024.0 * 1024.0)

        assertTrue("Should have decoded 100 frames", frameCount >= 100)
        assertTrue("Memory growth during continuous decoding ($memDeltaMb MB) must remain steady under 30MB", memDeltaMb < 30.0)

        decoder.close()
    }
}
