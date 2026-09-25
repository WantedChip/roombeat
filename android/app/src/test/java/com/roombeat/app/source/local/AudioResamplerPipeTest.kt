package com.roombeat.app.source.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AudioResamplerPipeTest {

    @Test
    fun resample_48kHzStereo_identityPassthrough() {
        val pipe = AudioResamplerPipe(inputSampleRate = 48000, inputChannels = 2)
        assertTrue(pipe.isPassthrough)

        val input = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f)
        val output = pipe.resample(input)

        assertArrayEquals(input, output, 0.0001f)
    }

    @Test
    fun resample_48kHzMono_duplicatesToStereo() {
        val pipe = AudioResamplerPipe(inputSampleRate = 48000, inputChannels = 1)

        val input = floatArrayOf(0.1f, -0.2f, 0.5f)
        val output = pipe.resample(input)

        assertEquals(6, output.size) // 3 frames * 2 channels
        assertEquals(0.1f, output[0], 0.0001f) // L
        assertEquals(0.1f, output[1], 0.0001f) // R
        assertEquals(-0.2f, output[2], 0.0001f) // L
        assertEquals(-0.2f, output[3], 0.0001f) // R
        assertEquals(0.5f, output[4], 0.0001f) // L
        assertEquals(0.5f, output[5], 0.0001f) // R
    }

    @Test
    fun resample_44_1kHzTo48kHz_exactSampleRatio() {
        val pipe = AudioResamplerPipe(inputSampleRate = 44100, inputChannels = 2)

        // 1 second of audio at 44.1kHz stereo = 44100 frames = 88200 samples
        val inputFrames = 44100
        val input = FloatArray(inputFrames * 2) { i ->
            val frame = i / 2
            val channel = i % 2
            (sin(2.0 * Math.PI * 440.0 * frame / 44100.0) * (if (channel == 0) 0.5 else 0.8)).toFloat()
        }

        val output = pipe.resample(input)
        val outFrames = output.size / 2

        // At 48kHz, 1 second is 48000 frames (+- 1 boundary sample)
        assertTrue("Output frames ($outFrames) should be approximately 48000 frames", abs(outFrames - 48000) <= 2)
    }

    @Test
    fun resample_acrossChunkBoundaries_noClicksOrDiscontinuities() {
        val pipe = AudioResamplerPipe(inputSampleRate = 44100, inputChannels = 2)

        // Continuous sine wave broken into 3 uneven chunks
        val totalInFrames = 44100
        val chunk1Frames = 14700
        val chunk2Frames = 15000
        val chunk3Frames = totalInFrames - chunk1Frames - chunk2Frames

        val makeSineChunk = { startFrame: Int, count: Int ->
            FloatArray(count * 2) { i ->
                val f = startFrame + (i / 2)
                (sin(2.0 * Math.PI * 200.0 * f / 44100.0)).toFloat()
            }
        }

        val c1 = makeSineChunk(0, chunk1Frames)
        val c2 = makeSineChunk(chunk1Frames, chunk2Frames)
        val c3 = makeSineChunk(chunk1Frames + chunk2Frames, chunk3Frames)

        val out1 = pipe.resample(c1)
        val out2 = pipe.resample(c2)
        val out3 = pipe.resample(c3)

        val combinedOutput = FloatArray(out1.size + out2.size + out3.size)
        System.arraycopy(out1, 0, combinedOutput, 0, out1.size)
        System.arraycopy(out2, 0, combinedOutput, out1.size, out2.size)
        System.arraycopy(out3, 0, combinedOutput, out1.size + out2.size, out3.size)

        // Check continuity across the boundary between out1 and out2
        val boundaryIndex = out1.size
        val sampleBeforeL = combinedOutput[boundaryIndex - 2]
        val sampleAfterL = combinedOutput[boundaryIndex]
        val deltaL = abs(sampleAfterL - sampleBeforeL)

        // For a 200 Hz sine wave at 48kHz, the maximum delta between adjacent samples is ~0.026
        assertTrue("Boundary jump ($deltaL) should be small and continuous without click", deltaL < 0.1f)

        // Check continuity across the boundary between out2 and out3
        val boundary2Index = out1.size + out2.size
        val sample2BeforeL = combinedOutput[boundary2Index - 2]
        val sample2AfterL = combinedOutput[boundary2Index]
        val delta2L = abs(sample2AfterL - sample2BeforeL)
        assertTrue("Boundary 2 jump ($delta2L) should be small and continuous without click", delta2L < 0.1f)
    }

    @Test
    fun resample_shortArrayPcm16_convertsCorrectly() {
        val pipe = AudioResamplerPipe(inputSampleRate = 48000, inputChannels = 1)

        val input = shortArrayOf(1000, -2000, 3000)
        val output = pipe.resample(input)

        assertEquals(6, output.size)
        assertEquals(1000.toShort(), output[0])
        assertEquals(1000.toShort(), output[1])
        assertEquals((-2000).toShort(), output[2])
        assertEquals((-2000).toShort(), output[3])
        assertEquals(3000.toShort(), output[4])
        assertEquals(3000.toShort(), output[5])
    }

    @Test
    fun resample_96kHzDownsampleTo48kHz_producesHalfFrames() {
        val pipe = AudioResamplerPipe(inputSampleRate = 96000, inputChannels = 2)

        // 96000 frames in (1 second) -> ~48000 frames out
        val inFrames = 9600
        val input = FloatArray(inFrames * 2) { 0.5f }
        val output = pipe.resample(input)
        val outFrames = output.size / 2

        assertTrue("Downsampled 96k to 48k frames ($outFrames) should be ~4800", abs(outFrames - 4800) <= 2)
    }

    @Test
    fun resample_22050HzUpsampleTo48kHz() {
        val pipe = AudioResamplerPipe(inputSampleRate = 22050, inputChannels = 1)

        val inFrames = 2205
        val input = FloatArray(inFrames) { 0.25f }
        val output = pipe.resample(input)
        val outFrames = output.size / 2

        // 2205 / 22050 * 48000 = 4800 frames
        assertTrue("Upsampled 22.05k to 48k frames ($outFrames) should be ~4800", abs(outFrames - 4800) <= 2)
    }

    @Test
    fun reconfigure_resetsStateCleanly() {
        val pipe = AudioResamplerPipe(inputSampleRate = 44100, inputChannels = 1)
        pipe.resample(floatArrayOf(0.1f, 0.2f))

        pipe.configure(48000, 2)
        assertTrue(pipe.isPassthrough)
        val output = pipe.resample(floatArrayOf(0.5f, -0.5f))
        assertEquals(2, output.size)
        assertEquals(0.5f, output[0], 0.0001f)
        assertEquals(-0.5f, output[1], 0.0001f)
    }
}
