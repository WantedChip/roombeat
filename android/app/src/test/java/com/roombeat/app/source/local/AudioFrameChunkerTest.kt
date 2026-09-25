package com.roombeat.app.source.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameChunkerTest {

    @Test
    fun chunkShort_exactMultipleOfFrameSize_slicesDirectly() {
        val chunker = AudioFrameChunker()
        assertEquals(1920, chunker.frameSamples)

        // 3 exact 20ms frames: 3 * 1920 = 5760 samples
        val input = ShortArray(5760) { i -> (i % 100).toShort() }
        val frames = chunker.chunkShort(input)

        assertEquals(3, frames.size)
        assertEquals(1920, frames[0].size)
        assertEquals(1920, frames[1].size)
        assertEquals(1920, frames[2].size)
        assertEquals(0, chunker.currentShortRemainderCount)

        // Verify content integrity
        assertEquals(0.toShort(), frames[0][0])
        assertEquals(1.toShort(), frames[0][1])
        assertEquals(99.toShort(), frames[0][99])
        assertEquals((1920 % 100).toShort(), frames[1][0])
    }

    @Test
    fun chunkShort_unevenChunks_accumulatesRemainderAcrossCalls() {
        val chunker = AudioFrameChunker()

        // Push 1000 samples -> 0 frames, 1000 remainder
        val chunk1 = ShortArray(1000) { 1 }
        val frames1 = chunker.chunkShort(chunk1)
        assertEquals(0, frames1.size)
        assertEquals(1000, chunker.currentShortRemainderCount)

        // Push 1000 samples -> 1 frame (1000 + 920 = 1920), 80 remainder
        val chunk2 = ShortArray(1000) { 2 }
        val frames2 = chunker.chunkShort(chunk2)
        assertEquals(1, frames2.size)
        assertEquals(1920, frames2[0].size)
        assertEquals(1.toShort(), frames2[0][0])      // from chunk1
        assertEquals(1.toShort(), frames2[0][999])    // from chunk1
        assertEquals(2.toShort(), frames2[0][1000])   // from chunk2
        assertEquals(80, chunker.currentShortRemainderCount)

        // Push 1840 samples -> completes the 80 remainder (80 + 1840 = 1920) -> 1 frame, 0 remainder
        val chunk3 = ShortArray(1840) { 3 }
        val frames3 = chunker.chunkShort(chunk3)
        assertEquals(1, frames3.size)
        assertEquals(1920, frames3[0].size)
        assertEquals(2.toShort(), frames3[0][0])      // from chunk2 remainder
        assertEquals(3.toShort(), frames3[0][80])     // from chunk3
        assertEquals(0, chunker.currentShortRemainderCount)
    }

    @Test
    fun flushShort_withZeroPadding_emitsPaddedFrame() {
        val chunker = AudioFrameChunker()

        // Push 500 samples
        val input = ShortArray(500) { 42 }
        val frames = chunker.chunkShort(input)
        assertEquals(0, frames.size)
        assertEquals(500, chunker.currentShortRemainderCount)

        // Flush with zeroPad = true
        val flushed = chunker.flushShort(zeroPad = true)
        assertEquals(1, flushed.size)
        assertEquals(1920, flushed[0].size)
        assertEquals(42.toShort(), flushed[0][0])
        assertEquals(42.toShort(), flushed[0][499])
        assertEquals(0.toShort(), flushed[0][500])
        assertEquals(0.toShort(), flushed[0][1919])
        assertEquals(0, chunker.currentShortRemainderCount)
    }

    @Test
    fun flushShort_withoutZeroPadding_discardsRemainder() {
        val chunker = AudioFrameChunker()

        val input = ShortArray(500) { 42 }
        chunker.chunkShort(input)
        assertEquals(500, chunker.currentShortRemainderCount)

        // Flush with zeroPad = false
        val flushed = chunker.flushShort(zeroPad = false)
        assertEquals(0, flushed.size)
        assertEquals(0, chunker.currentShortRemainderCount)
    }

    @Test
    fun chunkFloat_pushAndFlush_worksCorrectly() {
        val chunker = AudioFrameChunker()

        // Push 2000 float samples: 1 frame of 1920, remainder of 80
        val input = FloatArray(2000) { 0.75f }
        val frames = chunker.chunkFloat(input)

        assertEquals(1, frames.size)
        assertEquals(1920, frames[0].size)
        assertEquals(80, chunker.currentFloatRemainderCount)

        // Flush float
        val flushed = chunker.flushFloat(zeroPad = true)
        assertEquals(1, flushed.size)
        assertEquals(1920, flushed[0].size)
        assertEquals(0.75f, flushed[0][0], 0.0001f)
        assertEquals(0.75f, flushed[0][79], 0.0001f)
        assertEquals(0.0f, flushed[0][80], 0.0001f)
        assertEquals(0.0f, flushed[0][1919], 0.0001f)
        assertEquals(0, chunker.currentFloatRemainderCount)
    }

    @Test
    fun reset_clearsBothRemainders() {
        val chunker = AudioFrameChunker()

        chunker.chunkShort(ShortArray(100) { 1 })
        chunker.chunkFloat(FloatArray(200) { 1.0f })
        assertEquals(100, chunker.currentShortRemainderCount)
        assertEquals(200, chunker.currentFloatRemainderCount)

        chunker.reset()
        assertEquals(0, chunker.currentShortRemainderCount)
        assertEquals(0, chunker.currentFloatRemainderCount)
    }

    @Test
    fun pushShort_streamingCallbackDeliversFramesDirectly() {
        val chunker = AudioFrameChunker()
        var emittedFrames = 0
        var totalEmittedSamples = 0

        chunker.pushShort(ShortArray(4000) { 10 }) { frame ->
            emittedFrames++
            totalEmittedSamples += frame.size
            assertEquals(1920, frame.size)
        }

        // 4000 samples -> 2 frames (3840), remainder 160
        assertEquals(2, emittedFrames)
        assertEquals(3840, totalEmittedSamples)
        assertEquals(160, chunker.currentShortRemainderCount)
    }
}
