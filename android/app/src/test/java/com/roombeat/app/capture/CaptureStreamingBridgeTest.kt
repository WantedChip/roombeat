package com.roombeat.app.capture

import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.network.multicast.AudioFraming
import com.roombeat.app.network.multicast.FakeMulticastSocketWrapper
import com.roombeat.app.network.multicast.MulticastBroadcaster
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.sync.FakeMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaptureStreamingBridgeTest {

    private class FakeCaptureOpusEncoder : CaptureOpusEncoder {
        var encodeCallCount = 0
        var lastPcmInput: ShortArray? = null
        var shouldThrowOnEncode = false
        var returnNullOnEncode = false
        var cannedPayload: ByteArray = byteArrayOf(0x4F, 0x50, 0x55, 0x53, 0x01, 0x02, 0x03, 0x04)
        var isClosed = false

        override fun encode(pcmData: ShortArray, frameSize: Int): ByteArray? {
            encodeCallCount++
            lastPcmInput = pcmData
            if (shouldThrowOnEncode) {
                throw IllegalStateException("Fake encoder failure")
            }
            if (returnNullOnEncode) {
                return null
            }
            return cannedPayload.copyOf()
        }

        override fun encode(pcmData: ShortArray, frameSize: Int, outputBuffer: ByteArray): Int {
            encodeCallCount++
            lastPcmInput = pcmData
            if (shouldThrowOnEncode) {
                throw IllegalStateException("Fake encoder failure")
            }
            if (returnNullOnEncode) {
                return -1
            }
            cannedPayload.copyInto(outputBuffer)
            return cannedPayload.size
        }

        override fun close() {
            isClosed = true
        }
    }

    private class FakeCaptureBroadcaster : CaptureBroadcaster {
        data class SentChunk(
            val seq: Long,
            val presentationTimeUs: Long,
            val data: ByteArray
        )

        val sentChunks = mutableListOf<SentChunk>()
        var shouldFailSend = false
        var shouldThrowOnSend = false
        var closeCallCount = 0

        override fun sendAudioChunk(
            seq: Long,
            targetPresentationTimeUs: Long,
            opusData: ByteArray,
            offset: Int,
            length: Int
        ): Boolean {
            if (shouldThrowOnSend) {
                throw RuntimeException("Fake socket error")
            }
            if (shouldFailSend) {
                return false
            }
            val slice = opusData.copyOfRange(offset, offset + length)
            sentChunks.add(SentChunk(seq, targetPresentationTimeUs, slice))
            return true
        }

        override fun close() {
            closeCallCount++
        }
    }

    private class FakeCaptureLocalBuffer : CaptureLocalBuffer {
        data class PushedPacket(
            val seq: Long,
            val presentationTimeUs: Long,
            val data: ByteArray
        )

        val pushedPackets = mutableListOf<PushedPacket>()
        var shouldFailPush = false
        var shouldThrowOnPush = false

        override fun pushPacket(
            sequenceNumber: Long,
            presentationTimeUs: Long,
            payload: ByteArray,
            offset: Int,
            length: Int
        ): Boolean {
            if (shouldThrowOnPush) {
                throw RuntimeException("Fake buffer error")
            }
            if (shouldFailPush) {
                return false
            }
            val slice = payload.copyOfRange(offset, offset + length)
            pushedPackets.add(PushedPacket(sequenceNumber, presentationTimeUs, slice))
            return true
        }
    }

    private lateinit var fakeEncoder: FakeCaptureOpusEncoder
    private lateinit var fakeBroadcaster: FakeCaptureBroadcaster
    private lateinit var fakeLocalBuffer: FakeCaptureLocalBuffer
    private lateinit var fakeClock: FakeMonotonicClock
    private lateinit var bridge: CaptureStreamingBridge

    @Before
    fun setUp() {
        fakeEncoder = FakeCaptureOpusEncoder()
        fakeBroadcaster = FakeCaptureBroadcaster()
        fakeLocalBuffer = FakeCaptureLocalBuffer()
        fakeClock = FakeMonotonicClock(initialNanos = 1_000_000_000L) // 1 second (1,000,000 µs)
        bridge = CaptureStreamingBridge(
            config = StreamingBridgeConfig(
                lanJitterBufferMicros = 120_000L,
                suppressSilence = true,
                incrementSeqOnSilence = true,
                feedLocalOnSilence = false,
                initialSeq = 0L
            ),
            encoder = fakeEncoder,
            broadcaster = fakeBroadcaster,
            localBuffer = fakeLocalBuffer,
            clock = fakeClock
        )
    }

    @After
    fun tearDown() {
        bridge.close()
    }

    private fun createDummyPcmFrame(isSilent: Boolean = false, rmsDbfs: Double = -20.0): CapturedAudioFrame {
        val pcm = ShortArray(1920) { (it % 100).toShort() }
        return CapturedAudioFrame(
            pcmData = pcm,
            sampleRate = 48000,
            channels = 2,
            timestampNs = fakeClock.nowNanos(),
            isSilent = isSilent,
            rmsDbfs = rmsDbfs
        )
    }

    @Test
    fun initialState_isIdle_andMetricsAreZero() {
        assertEquals(StreamingBridgeState.IDLE, bridge.state.value)
        assertFalse(bridge.isStreaming)

        val stats = bridge.stats
        assertEquals(0L, stats.totalFramesProcessed)
        assertEquals(0L, stats.packetsSent)
        assertEquals(0L, stats.bytesSent)
        assertEquals(0L, stats.silenceFramesSuppressed)
        assertEquals(0L, stats.activeFramesSent)
        assertEquals(0L, stats.localFramesPushed)
        assertEquals(0L, stats.encodingErrors)
        assertEquals(0L, stats.broadcastErrors)
        assertEquals(-1L, stats.lastSequenceNumber)
        assertEquals(0L, stats.lastPresentationTimeUs)
        assertEquals(0.0, stats.avgEncodingTimeMicros, 0.001)
    }

    @Test
    fun start_transitionsToRunning() {
        var stateNotified: StreamingBridgeState? = null
        bridge.onStateChanged = { stateNotified = it }

        assertTrue(bridge.start())
        assertEquals(StreamingBridgeState.RUNNING, bridge.state.value)
        assertTrue(bridge.isStreaming)
        assertEquals(StreamingBridgeState.RUNNING, stateNotified)

        // Idempotent start
        assertTrue(bridge.start())
        assertEquals(StreamingBridgeState.RUNNING, bridge.state.value)
    }

    @Test
    fun pause_and_resume_lifecycleTransitions() {
        bridge.start()
        assertEquals(StreamingBridgeState.RUNNING, bridge.state.value)

        bridge.pause()
        assertEquals(StreamingBridgeState.PAUSED, bridge.state.value)
        assertFalse(bridge.isStreaming)

        // When paused, frames are ignored
        val frame = createDummyPcmFrame()
        bridge.processFrame(frame)
        assertEquals(0L, bridge.stats.totalFramesProcessed)
        assertEquals(0, fakeBroadcaster.sentChunks.size)

        bridge.resume()
        assertEquals(StreamingBridgeState.RUNNING, bridge.state.value)
        assertTrue(bridge.isStreaming)

        bridge.processFrame(frame)
        assertEquals(1L, bridge.stats.totalFramesProcessed)
        assertEquals(1, fakeBroadcaster.sentChunks.size)
    }

    @Test
    fun stop_transitionsToStopped_andDetaches() {
        bridge.start()
        bridge.stop()
        assertEquals(StreamingBridgeState.STOPPED, bridge.state.value)
        assertFalse(bridge.isStreaming)

        // Frames are ignored when stopped
        bridge.processFrame(createDummyPcmFrame())
        assertEquals(0L, bridge.stats.totalFramesProcessed)
    }

    @Test
    fun processFrame_whenNotRunning_isIgnored() {
        assertEquals(StreamingBridgeState.IDLE, bridge.state.value)
        bridge.processFrame(createDummyPcmFrame())

        assertEquals(0L, bridge.stats.totalFramesProcessed)
        assertEquals(0, fakeBroadcaster.sentChunks.size)
        assertEquals(0, fakeLocalBuffer.pushedPackets.size)
        assertEquals(0, fakeEncoder.encodeCallCount)
    }

    @Test
    fun processFrame_activeAudio_encodesTimestampsAndBroadcasts() {
        bridge.start()

        // Clock is at 1,000,000 µs (1s)
        val frame1 = createDummyPcmFrame(isSilent = false)
        bridge.processFrame(frame1)

        assertEquals(1L, bridge.stats.totalFramesProcessed)
        assertEquals(1L, bridge.stats.packetsSent)
        assertEquals(fakeEncoder.cannedPayload.size.toLong(), bridge.stats.bytesSent)
        assertEquals(1L, bridge.stats.activeFramesSent)
        assertEquals(1L, bridge.stats.localFramesPushed)
        assertEquals(0L, bridge.stats.silenceFramesSuppressed)
        assertEquals(0L, bridge.stats.lastSequenceNumber)

        // T_presentation = 1,000,000 + 120,000 = 1,120,000 µs
        val expectedPresentationUs = 1_000_000L + 120_000L
        assertEquals(expectedPresentationUs, bridge.stats.lastPresentationTimeUs)

        // Verify broadcast packet
        assertEquals(1, fakeBroadcaster.sentChunks.size)
        val chunk1 = fakeBroadcaster.sentChunks[0]
        assertEquals(0L, chunk1.seq)
        assertEquals(expectedPresentationUs, chunk1.presentationTimeUs)
        assertArrayEquals(fakeEncoder.cannedPayload, chunk1.data)

        // Verify local buffer packet
        assertEquals(1, fakeLocalBuffer.pushedPackets.size)
        val local1 = fakeLocalBuffer.pushedPackets[0]
        assertEquals(0L, local1.seq)
        assertEquals(expectedPresentationUs, local1.presentationTimeUs)
        assertArrayEquals(fakeEncoder.cannedPayload, local1.data)

        // Advance clock by 20ms (20,000 µs)
        fakeClock.advanceMicros(20_000L)
        val frame2 = createDummyPcmFrame(isSilent = false)
        bridge.processFrame(frame2)

        assertEquals(2L, bridge.stats.totalFramesProcessed)
        assertEquals(2L, bridge.stats.packetsSent)
        assertEquals(1L, bridge.stats.lastSequenceNumber)

        val expectedPresentation2Us = 1_020_000L + 120_000L
        assertEquals(expectedPresentation2Us, bridge.stats.lastPresentationTimeUs)

        assertEquals(2, fakeBroadcaster.sentChunks.size)
        val chunk2 = fakeBroadcaster.sentChunks[1]
        assertEquals(1L, chunk2.seq)
        assertEquals(expectedPresentation2Us, chunk2.presentationTimeUs)
    }

    @Test
    fun processFrame_silenceSuppression_suppressesMulticastAndAdvancesSeq() {
        bridge.start()

        // 1. Send active frame (seq 0)
        bridge.processFrame(createDummyPcmFrame(isSilent = false))
        assertEquals(1L, bridge.stats.packetsSent)
        assertEquals(0L, bridge.stats.lastSequenceNumber)
        assertEquals(1, fakeBroadcaster.sentChunks.size)
        assertEquals(1, fakeLocalBuffer.pushedPackets.size)

        // 2. Send 2 silent frames (seq 1, 2)
        fakeClock.advanceMicros(20_000L)
        bridge.processFrame(createDummyPcmFrame(isSilent = true, rmsDbfs = -90.0))

        fakeClock.advanceMicros(20_000L)
        bridge.processFrame(createDummyPcmFrame(isSilent = true, rmsDbfs = -90.0))

        assertEquals(3L, bridge.stats.totalFramesProcessed)
        assertEquals(1L, bridge.stats.packetsSent) // Still only 1 packet sent
        assertEquals(2L, bridge.stats.silenceFramesSuppressed) // 2 suppressed
        assertEquals(2L, bridge.stats.lastSequenceNumber) // Sequence advanced to 2
        assertEquals(1, fakeBroadcaster.sentChunks.size) // No multicast sent for silent frames
        assertEquals(1, fakeLocalBuffer.pushedPackets.size) // No local feed by default

        // 3. Audio resumes (seq 3)
        fakeClock.advanceMicros(20_000L)
        bridge.processFrame(createDummyPcmFrame(isSilent = false))

        assertEquals(4L, bridge.stats.totalFramesProcessed)
        assertEquals(2L, bridge.stats.packetsSent)
        assertEquals(3L, bridge.stats.lastSequenceNumber)
        assertEquals(2, fakeBroadcaster.sentChunks.size)

        // Verify the newly broadcast packet has seq 3, preserving continuity
        val chunkResume = fakeBroadcaster.sentChunks[1]
        assertEquals(3L, chunkResume.seq)
    }

    @Test
    fun processFrame_silenceSuppression_withFeedLocalOnSilence() {
        val customBridge = CaptureStreamingBridge(
            config = StreamingBridgeConfig(
                suppressSilence = true,
                incrementSeqOnSilence = true,
                feedLocalOnSilence = true
            ),
            encoder = fakeEncoder,
            broadcaster = fakeBroadcaster,
            localBuffer = fakeLocalBuffer,
            clock = fakeClock
        )
        customBridge.start()

        customBridge.processFrame(createDummyPcmFrame(isSilent = true))

        assertEquals(1L, customBridge.stats.totalFramesProcessed)
        assertEquals(1L, customBridge.stats.silenceFramesSuppressed)
        assertEquals(0L, customBridge.stats.packetsSent) // Multicast suppressed
        assertEquals(1L, customBridge.stats.localFramesPushed) // Local buffer fed!
        assertEquals(0, fakeBroadcaster.sentChunks.size)
        assertEquals(1, fakeLocalBuffer.pushedPackets.size)

        customBridge.close()
    }

    @Test
    fun processFrame_silenceSuppression_disabled_broadcastsSilence() {
        val nonSuppressingBridge = CaptureStreamingBridge(
            config = StreamingBridgeConfig(
                suppressSilence = false
            ),
            encoder = fakeEncoder,
            broadcaster = fakeBroadcaster,
            localBuffer = fakeLocalBuffer,
            clock = fakeClock
        )
        nonSuppressingBridge.start()

        nonSuppressingBridge.processFrame(createDummyPcmFrame(isSilent = true))

        assertEquals(1L, nonSuppressingBridge.stats.totalFramesProcessed)
        assertEquals(0L, nonSuppressingBridge.stats.silenceFramesSuppressed)
        assertEquals(1L, nonSuppressingBridge.stats.packetsSent) // Transmitted anyway
        assertEquals(1, fakeBroadcaster.sentChunks.size)

        nonSuppressingBridge.close()
    }

    @Test
    fun processFrame_encoderError_handledGracefully() {
        bridge.start()
        fakeEncoder.shouldThrowOnEncode = true

        var errorMsg: String? = null
        var errorCause: Throwable? = null
        bridge.onError = { msg, cause ->
            errorMsg = msg
            errorCause = cause
        }

        bridge.processFrame(createDummyPcmFrame(isSilent = false))

        assertEquals(1L, bridge.stats.totalFramesProcessed)
        assertEquals(1L, bridge.stats.encodingErrors)
        assertEquals(0L, bridge.stats.packetsSent)
        assertNotNull(errorMsg)
        assertNotNull(errorCause)

        // Test return null on encode
        fakeEncoder.shouldThrowOnEncode = false
        fakeEncoder.returnNullOnEncode = true
        errorMsg = null

        bridge.processFrame(createDummyPcmFrame(isSilent = false))
        assertEquals(2L, bridge.stats.totalFramesProcessed)
        assertEquals(2L, bridge.stats.encodingErrors)
        assertNotNull(errorMsg)
    }

    @Test
    fun processFrame_broadcastError_handledGracefully() {
        bridge.start()
        fakeBroadcaster.shouldFailSend = true

        bridge.processFrame(createDummyPcmFrame(isSilent = false))

        assertEquals(1L, bridge.stats.totalFramesProcessed)
        assertEquals(1L, bridge.stats.broadcastErrors)
        assertEquals(0L, bridge.stats.packetsSent)
        // Local buffer is still pushed despite broadcast socket failure
        assertEquals(1L, bridge.stats.localFramesPushed)

        // Throwing broadcast exception is contained
        fakeBroadcaster.shouldFailSend = false
        fakeBroadcaster.shouldThrowOnSend = true

        bridge.processFrame(createDummyPcmFrame(isSilent = false))
        assertEquals(2L, bridge.stats.totalFramesProcessed)
        assertEquals(2L, bridge.stats.broadcastErrors)
    }

    @Test
    fun processFrame_localBufferError_handledGracefully() {
        bridge.start()
        fakeLocalBuffer.shouldThrowOnPush = true

        bridge.processFrame(createDummyPcmFrame(isSilent = false))

        assertEquals(1L, bridge.stats.totalFramesProcessed)
        assertEquals(1L, bridge.stats.packetsSent)
        assertEquals(0L, bridge.stats.localFramesPushed)
    }

    @Test
    fun attachCaptureEngine_callbackDispatchesFrames() {
        bridge.start()

        val engine = SystemAudioCaptureEngine()
        bridge.attachCaptureEngine(engine)

        assertNotNull(engine.onAudioFrameCaptured)

        // Simulate frame from engine
        val frame = createDummyPcmFrame()
        engine.onAudioFrameCaptured?.invoke(frame)

        assertEquals(1L, bridge.stats.totalFramesProcessed)
        assertEquals(1L, bridge.stats.packetsSent)

        bridge.detachCaptureEngine()
        assertNull(engine.onAudioFrameCaptured)
    }

    @Test
    fun attachCaptureEngineFlow_coroutineFlowDispatchesFrames() = runBlocking {
        bridge.start()

        val engine = SystemAudioCaptureEngine()
        val scope = CoroutineScope(Dispatchers.Default)

        bridge.attachCaptureEngineFlow(engine, scope)

        val flowField = SystemAudioCaptureEngine::class.java.getDeclaredField("_audioFrames").apply {
            isAccessible = true
        }
        val sharedFlow = flowField.get(engine) as kotlinx.coroutines.flow.MutableSharedFlow<CapturedAudioFrame>

        // Ensure the collector coroutine has subscribed before emitting
        sharedFlow.subscriptionCount.first { it > 0 }

        // Emit frame to active subscriber
        val frame = createDummyPcmFrame()
        sharedFlow.tryEmit(frame)

        // Allow coroutine time to process
        var count = 0
        while (bridge.stats.totalFramesProcessed == 0L && count++ < 100) {
            kotlinx.coroutines.delay(10)
        }

        assertEquals(1L, bridge.stats.totalFramesProcessed)
        assertEquals(1L, bridge.stats.packetsSent)

        bridge.detachCaptureEngine()
        scope.cancel()
    }

    @Test
    fun resetStats_clearsCountersAndUpdatesInitialSeq() {
        bridge.start()
        bridge.processFrame(createDummyPcmFrame(isSilent = false))
        bridge.processFrame(createDummyPcmFrame(isSilent = true))

        assertEquals(2L, bridge.stats.totalFramesProcessed)
        assertEquals(1L, bridge.stats.packetsSent)
        assertEquals(1L, bridge.stats.silenceFramesSuppressed)

        bridge.resetStats(newInitialSeq = 100L)

        val stats = bridge.stats
        assertEquals(0L, stats.totalFramesProcessed)
        assertEquals(0L, stats.packetsSent)
        assertEquals(0L, stats.bytesSent)
        assertEquals(0L, stats.silenceFramesSuppressed)
        assertEquals(0L, stats.activeFramesSent)
        assertEquals(0L, stats.localFramesPushed)
        assertEquals(0L, stats.encodingErrors)
        assertEquals(0L, stats.broadcastErrors)
        assertEquals(-1L, stats.lastSequenceNumber)
        assertEquals(0L, stats.lastPresentationTimeUs)
        assertEquals(0.0, stats.avgEncodingTimeMicros, 0.001)

        // Next frame should use newInitialSeq
        bridge.processFrame(createDummyPcmFrame(isSilent = false))
        assertEquals(100L, bridge.stats.lastSequenceNumber)
    }

    @Test
    fun secondaryConstructors_instantiateCleanly() {
        val fakeSocket1 = FakeMulticastSocketWrapper()
        val broadcaster1 = MulticastBroadcaster(socketWrapper = fakeSocket1)
        val jitterBuffer = AudioJitterBuffer()

        // 1. Broadcaster + JitterBuffer constructor
        val bridge1 = CaptureStreamingBridge(broadcaster1, jitterBuffer, clock = fakeClock)
        assertNotNull(bridge1.broadcaster)
        assertNotNull(bridge1.localBuffer)
        assertTrue(bridge1.start())

        bridge1.processFrame(createDummyPcmFrame(isSilent = false))
        assertEquals(1L, bridge1.stats.totalFramesProcessed)
        assertEquals(1L, bridge1.stats.packetsSent)
        assertEquals(1L, bridge1.stats.localFramesPushed)
        bridge1.close()
        jitterBuffer.close()

        // 2. Broadcaster + NativeAudioEngine constructor
        val fakeSocket2 = FakeMulticastSocketWrapper()
        val broadcaster2 = MulticastBroadcaster(socketWrapper = fakeSocket2)
        val engine = NativeAudioEngine()
        val bridge2 = CaptureStreamingBridge(broadcaster2, engine, clock = fakeClock)
        assertNotNull(bridge2.broadcaster)
        assertNotNull(bridge2.localBuffer)
        assertTrue(bridge2.start())

        bridge2.processFrame(createDummyPcmFrame(isSilent = false))
        assertEquals(1L, bridge2.stats.totalFramesProcessed)
        assertEquals(1L, bridge2.stats.packetsSent)
        bridge2.close()
    }

    @Test
    fun close_isIdempotent_andClosesResources() {
        bridge.start()
        assertFalse(fakeEncoder.isClosed)
        assertEquals(0, fakeBroadcaster.closeCallCount)

        bridge.close()
        assertTrue(fakeEncoder.isClosed)
        assertEquals(1, fakeBroadcaster.closeCallCount)
        assertEquals(StreamingBridgeState.STOPPED, bridge.state.value)

        // Double close is safe
        bridge.close()
        assertEquals(1, fakeBroadcaster.closeCallCount)
    }

    @Test
    fun onStateChanged_listenerReceivesTransitions() {
        val transitions = mutableListOf<StreamingBridgeState>()
        bridge.onStateChanged = { transitions.add(it) }

        bridge.start()
        bridge.pause()
        bridge.resume()
        bridge.stop()

        assertEquals(
            listOf(
                StreamingBridgeState.RUNNING,
                StreamingBridgeState.PAUSED,
                StreamingBridgeState.RUNNING,
                StreamingBridgeState.STOPPED
            ),
            transitions
        )
    }
}
