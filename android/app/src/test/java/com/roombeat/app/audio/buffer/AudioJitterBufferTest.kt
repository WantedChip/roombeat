package com.roombeat.app.audio.buffer

import com.roombeat.app.audio.AudioEngineBridge
import com.roombeat.app.audio.NativeAudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for AudioJitterBuffer verifying jitter absorption, out-of-order packet reordering,
 * Opus Packet Loss Concealment (PLC), smooth fade-out/fade-in transitions, and late packet dropping.
 */
class AudioJitterBufferTest {

    private fun createSyntheticFrame(seq: Long, value: Float = 0.5f): FloatArray {
        val pcm = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        for (i in 0 until JitterBufferConstants.SAMPLES_PER_CHANNEL) {
            pcm[i * 2] = value
            pcm[i * 2 + 1] = value
        }
        return pcm
    }

    private fun createSyntheticOpusPayload(seq: Long): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = (seq and 0xFF).toByte()
        bytes[1] = ((seq shr 8) and 0xFF).toByte()
        return bytes
    }

    @Test
    fun testInitialConstantsAndCreation() {
        assertEquals(48000, JitterBufferConstants.SAMPLE_RATE)
        assertEquals(2, JitterBufferConstants.CHANNELS)
        assertEquals(20, JitterBufferConstants.FRAME_DURATION_MS)
        assertEquals(960, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        assertEquals(1920, JitterBufferConstants.INTERLEAVED_SAMPLES)
        assertEquals(120, JitterBufferConstants.DEFAULT_TARGET_DEPTH_MS)
        assertEquals(64, JitterBufferConstants.DEFAULT_CAPACITY_FRAMES)
        assertEquals(3, JitterBufferConstants.MAX_PLC_CONSECUTIVE_FRAMES)

        val buffer = AudioJitterBuffer(targetDepthMs = 120, capacityFrames = 64)
        assertEquals(120, buffer.targetDepthMs)
        assertEquals(0, buffer.queuedFrames)

        val stats = buffer.getStats()
        assertEquals(0L, stats.totalPacketsReceived)
        assertEquals(0L, stats.packetsPlayed)
        assertEquals(0L, stats.plcCount)
        assertEquals(0L, stats.latePacketsDropped)
        assertEquals(0L, stats.underrunCount)
        buffer.close()
    }

    @Test
    fun testJitterBufferAbsorbs120msArrivalJitter() {
        // 120ms target depth requires 6 frames of 20ms
        val buffer = AudioJitterBuffer(targetDepthMs = 120)

        // Push 5 frames (100ms) - not enough to satisfy 120ms jitter target depth
        for (i in 0L until 5L) {
            val pushed = buffer.pushDecodedFrame(i, 0L, createSyntheticFrame(i, 0.4f))
            assertTrue("Frame $i should be accepted", pushed)
        }
        assertEquals(5, buffer.queuedFrames)

        // Try pulling audio: should output silence while buffering
        val output = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        buffer.pullFrames(output, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        for (sample in output) {
            assertEquals("Should output silence while buffering", 0.0f, sample, 0.0001f)
        }

        // Push the 6th frame (120ms target reached!)
        val pushed6 = buffer.pushDecodedFrame(5L, 0L, createSyntheticFrame(5L, 0.4f))
        assertTrue("6th frame should be accepted", pushed6)
        assertEquals(6, buffer.queuedFrames)

        // Now pull frames: playback starts!
        val rendered = buffer.pullFrames(output, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        assertEquals(JitterBufferConstants.SAMPLES_PER_CHANNEL, rendered)

        val stats = buffer.getStats()
        assertEquals(1L, stats.packetsPlayed)
        buffer.close()
    }

    @Test
    fun testOutOfOrderPacketReordering() {
        // Target depth = 20ms (1 frame) so it starts immediately once 1 frame is buffered
        val buffer = AudioJitterBuffer(targetDepthMs = 20)

        // Push packets in deliberately scrambled order: seq 3, 1, 0, 2
        buffer.pushDecodedFrame(3L, 0L, createSyntheticFrame(3L, 0.3f))
        buffer.pushDecodedFrame(1L, 0L, createSyntheticFrame(1L, 0.1f))
        buffer.pushDecodedFrame(0L, 0L, createSyntheticFrame(0L, 0.0f))
        buffer.pushDecodedFrame(2L, 0L, createSyntheticFrame(2L, 0.2f))

        assertEquals(4, buffer.queuedFrames)

        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)

        // Pull frame 0
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        // Check middle sample (away from fade in/out edges)
        val sample0 = out[JitterBufferConstants.SAMPLES_PER_CHANNEL]
        assertEquals(0.0f, sample0, 0.05f)

        // Pull frame 1
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        val sample1 = out[JitterBufferConstants.SAMPLES_PER_CHANNEL]
        assertEquals(0.1f, sample1, 0.05f)

        // Pull frame 2
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        val sample2 = out[JitterBufferConstants.SAMPLES_PER_CHANNEL]
        assertEquals(0.2f, sample2, 0.05f)

        // Pull frame 3
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        val sample3 = out[JitterBufferConstants.SAMPLES_PER_CHANNEL]
        assertEquals(0.3f, sample3, 0.05f)

        val stats = buffer.getStats()
        assertEquals(4L, stats.packetsPlayed)
        assertEquals(0L, stats.latePacketsDropped)
        assertEquals(0L, stats.duplicatePacketsDropped)
        buffer.close()
    }

    @Test
    fun testMissingSequenceNumberTriggersPlc() {
        val buffer = AudioJitterBuffer(targetDepthMs = 20)

        // Push seq 0, then skip seq 1 (missing/lost packet), then push seq 2
        buffer.pushDecodedFrame(0L, 0L, createSyntheticFrame(0L, 0.5f))
        buffer.pushDecodedFrame(2L, 0L, createSyntheticFrame(2L, 0.5f))

        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)

        // Render frame 0 (seq 0)
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        assertEquals(0L, buffer.getStats().plcCount)

        // Render next frame: seq 1 is MISSING! Should trigger PLC
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        val statsAfterPlc = buffer.getStats()
        assertEquals("Missing seq 1 must trigger PLC", 1L, statsAfterPlc.plcCount)

        // Render next frame: seq 2 should now play
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        val statsAfterSeq2 = buffer.getStats()
        assertEquals(3L, statsAfterSeq2.packetsPlayed)
        assertEquals(1L, statsAfterSeq2.plcCount)

        buffer.close()
    }

    @Test
    fun testBufferUnderflowAppliesSmoothFadeOut() {
        val buffer = AudioJitterBuffer(targetDepthMs = 20)

        // Push only frame 0
        buffer.pushDecodedFrame(0L, 0L, createSyntheticFrame(0L, 0.8f))

        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)

        // Frame 0 plays
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)

        // Missing 1: PLC frame 1
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        // Missing 2: PLC frame 2
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        // Missing 3: PLC frame 3 with 20ms cosine fade-out
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)

        // Verify that the end of frame 3 faded out to 0
        val lastSampleL = out[JitterBufferConstants.INTERLEAVED_SAMPLES - 2]
        val lastSampleR = out[JitterBufferConstants.INTERLEAVED_SAMPLES - 1]
        assertEquals("End of faded frame must decay to 0", 0.0f, lastSampleL, 0.05f)
        assertEquals("End of faded frame must decay to 0", 0.0f, lastSampleR, 0.05f)

        // Frame 4: PLC tolerance exceeded -> silence & underrun
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        for (sample in out) {
            assertEquals("Post-tolerance must be complete silence", 0.0f, sample, 0.0001f)
        }

        val stats = buffer.getStats()
        assertTrue("Underrun count must be recorded", stats.underrunCount > 0)
        buffer.close()
    }

    @Test
    fun testResumingPacketsApplySmoothFadeIn() {
        val buffer = AudioJitterBuffer(targetDepthMs = 20)

        // Frame 0
        buffer.pushDecodedFrame(0L, 0L, createSyntheticFrame(0L, 0.8f))
        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)

        // Drain until underflow / silence
        for (i in 0 until 5) {
            buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        }

        // Packets resume at seq 10 with high amplitude
        buffer.pushDecodedFrame(10L, 0L, createSyntheticFrame(10L, 1.0f))

        // Pull frame: first resuming frame must fade in smoothly from 0.0
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)

        val firstSample = out[0]
        val midSample = out[JitterBufferConstants.INTERLEAVED_SAMPLES / 2]
        val lastSample = out[JitterBufferConstants.INTERLEAVED_SAMPLES - 2]

        assertTrue("First sample of fade-in must start near 0: $firstSample", abs(firstSample) < 0.1f)
        assertTrue("Mid sample should have intermediate gain: $midSample", midSample in 0.3f..0.8f)
        assertTrue("Last sample should approach full amplitude: $lastSample", lastSample > 0.85f)

        buffer.close()
    }

    @Test
    fun testLatePacketsAreDropped() {
        val buffer = AudioJitterBuffer(targetDepthMs = 20)

        // Push seq 0 and seq 1
        buffer.pushDecodedFrame(0L, 0L, createSyntheticFrame(0L, 0.5f))
        buffer.pushDecodedFrame(1L, 0L, createSyntheticFrame(1L, 0.5f))

        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        // Play seq 0
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        // Play seq 1
        buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)

        // Try pushing seq 0 again (already played and elapsed!)
        val accepted = buffer.pushDecodedFrame(0L, 0L, createSyntheticFrame(0L, 0.5f))
        assertFalse("Late packet whose deadline elapsed must be rejected", accepted)

        val stats = buffer.getStats()
        assertEquals("Late packet drop must be recorded", 1L, stats.latePacketsDropped)
        buffer.close()
    }

    @Test
    fun testDuplicatePacketsAreDropped() {
        val buffer = AudioJitterBuffer(targetDepthMs = 40)

        val first = buffer.pushDecodedFrame(10L, 0L, createSyntheticFrame(10L, 0.5f))
        assertTrue("First insertion of seq 10 must succeed", first)

        val second = buffer.pushDecodedFrame(10L, 0L, createSyntheticFrame(10L, 0.5f))
        assertFalse("Duplicate insertion of seq 10 must be rejected", second)

        val stats = buffer.getStats()
        assertEquals(1L, stats.duplicatePacketsDropped)
        buffer.close()
    }

    @Test
    fun testPushOpusPacket() {
        val buffer = AudioJitterBuffer(targetDepthMs = 20)
        val payload = createSyntheticOpusPayload(1L)
        val pushed = buffer.pushPacket(1L, 0L, payload)
        assertTrue("Opus packet push should succeed", pushed)
        assertEquals(1, buffer.queuedFrames)

        val out = FloatArray(JitterBufferConstants.INTERLEAVED_SAMPLES)
        val rendered = buffer.pullFrames(out, JitterBufferConstants.SAMPLES_PER_CHANNEL)
        assertEquals(JitterBufferConstants.SAMPLES_PER_CHANNEL, rendered)
        buffer.close()
    }

    @Test
    fun testTargetDepthMsConfiguration() {
        val buffer = AudioJitterBuffer(targetDepthMs = 120)
        assertEquals(120, buffer.targetDepthMs)

        buffer.targetDepthMs = 80
        assertEquals(80, buffer.targetDepthMs)

        buffer.targetDepthMs = 10 // Clamp to minimum 20ms
        assertEquals(20, buffer.targetDepthMs)
        buffer.close()
    }

    @Test
    fun testBufferReset() {
        val buffer = AudioJitterBuffer(targetDepthMs = 20)
        buffer.pushDecodedFrame(1L, 0L, createSyntheticFrame(1L, 0.5f))
        assertEquals(1, buffer.queuedFrames)

        buffer.reset()
        assertEquals(0, buffer.queuedFrames)
        buffer.close()
    }

    @Test
    fun testDoubleCloseSafety() {
        val buffer = AudioJitterBuffer()
        buffer.close()
        buffer.close() // Must not throw
    }

    @Test
    fun testOperationsOnClosedBufferThrow() {
        val buffer = AudioJitterBuffer()
        buffer.close()

        try {
            buffer.pushDecodedFrame(0L, 0L, createSyntheticFrame(0L))
            fail("Expected IllegalStateException on closed buffer")
        } catch (_: IllegalStateException) {}

        try {
            buffer.pullFrames(FloatArray(1920), 960)
            fail("Expected IllegalStateException on closed buffer")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun testSubFrameBurstRendering() {
        // Tests rendering in AAudio typical burst sizes (e.g. 192 frames)
        val buffer = AudioJitterBuffer(targetDepthMs = 20)
        buffer.pushDecodedFrame(0L, 0L, createSyntheticFrame(0L, 0.4f))

        val burstSize = 192
        val burstOut = FloatArray(burstSize * JitterBufferConstants.CHANNELS)

        var totalFramesRendered = 0
        for (i in 0 until 5) { // 5 bursts * 192 = 960 frames (1 full 20ms frame)
            val rendered = buffer.pullFrames(burstOut, burstSize)
            assertEquals(burstSize, rendered)
            totalFramesRendered += rendered
        }
        assertEquals(960, totalFramesRendered)
        buffer.close()
    }

    @Test
    fun testAttachToNativeEngine() {
        val buffer = AudioJitterBuffer()
        val attached = buffer.attachToEngine()
        assertTrue("attachToEngine should succeed", attached)

        // On host JVM without native library, default engine returns false safely
        val defaultAttached = NativeAudioEngine.attachJitterBuffer(buffer)
        assertFalse("Host JVM fallback without bridge returns false", defaultAttached)

        // With an engine bridge injected, attachJitterBuffer delegates to bridge and succeeds
        val engineWithBridge = NativeAudioEngine(object : AudioEngineBridge {
            override fun initEngine(): Int = 0
            override fun startStream(): Int = 0
            override fun stopStream(): Int = 0
            override fun getAudioLatencyMillis(): Int = 0
            override fun teardownEngine(): Int = 0
            override fun attachJitterBuffer(handle: Long): Boolean = handle == buffer.handle
        })
        assertTrue("attachJitterBuffer with bridge should succeed", engineWithBridge.attachJitterBuffer(buffer))
        buffer.close()
    }
}
