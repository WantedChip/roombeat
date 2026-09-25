package com.roombeat.app.ui.components

import com.roombeat.app.audio.AudioRmsAnalyzer
import com.roombeat.app.audio.ChannelLevels
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiChannelVuMeterTest {

    @Test
    fun testSegmentColorClassification() {
        fun classifySegmentColor(dbfs: Float) = when {
            dbfs > AudioRmsAnalyzer.CLIP_THRESHOLD_DBFS -> SyncRed
            dbfs >= AudioRmsAnalyzer.GREEN_AMBER_THRESHOLD_DBFS -> SyncAmber
            else -> SyncGreen
        }

        // Below -6 dBFS: Phosphor Green (#00E599)
        assertEquals(SyncGreen, classifySegmentColor(-60.0f))
        assertEquals(SyncGreen, classifySegmentColor(-24.0f))
        assertEquals(SyncGreen, classifySegmentColor(-12.0f))
        assertEquals(SyncGreen, classifySegmentColor(-6.1f))

        // From -6 dBFS to 0 dBFS: Amber (#FFB800)
        assertEquals(SyncAmber, classifySegmentColor(-6.0f))
        assertEquals(SyncAmber, classifySegmentColor(-3.2f))
        assertEquals(SyncAmber, classifySegmentColor(-0.1f))
        assertEquals(SyncAmber, classifySegmentColor(0.0f))

        // Above 0 dBFS: Red (#FF334B)
        assertEquals(SyncRed, classifySegmentColor(0.1f))
        assertEquals(SyncRed, classifySegmentColor(1.5f))
        assertEquals(SyncRed, classifySegmentColor(3.0f))
    }

    @Test
    fun testSegmentIndexMapping24Segments() {
        val segmentCount = 24
        val minDbfs = AudioRmsAnalyzer.MIN_DBFS // -60 dBFS
        val maxDbfs = AudioRmsAnalyzer.MAX_DBFS // +3 dBFS

        for (k in 0 until segmentCount) {
            val segNorm = (k + 0.5f) / segmentCount
            val segDbfs = minDbfs + segNorm * (maxDbfs - minDbfs)

            assertTrue(segDbfs >= minDbfs)
            assertTrue(segDbfs <= maxDbfs)

            // Bottom segments must be green
            if (segDbfs < AudioRmsAnalyzer.GREEN_AMBER_THRESHOLD_DBFS) {
                assertTrue(segDbfs < -6.0f)
            }

            // Top segment must be in clip / red region (+3 dBFS range)
            if (k == segmentCount - 1) {
                assertTrue(segDbfs > 0.0f)
            }
        }
    }

    @Test
    fun testReducedMotionNumericReadouts() {
        val ch1 = ChannelLevels(
            channelId = "local_host",
            name = "Host",
            leftRmsDbfs = -3.2f,
            rightRmsDbfs = -3.2f,
            isClipping = false
        )

        assertEquals("-3.2 dB", ch1.formattedDbfs)
        assertFalse(ch1.isClipping)

        val ch2 = ChannelLevels(
            channelId = "peer_clip",
            name = "Pixel 8",
            leftRmsDbfs = 1.2f,
            rightRmsDbfs = 0.5f,
            isClipping = true
        )

        assertEquals("1.2 dB", ch2.formattedDbfs)
        assertTrue(ch2.isClipping)
    }

    @Test
    fun testMultiChannelSynchronizedActivityMatrix() {
        val devices = listOf(
            ChannelLevels(channelId = "host", name = "Host (Pixel 8)", leftRmsDbfs = -4.2f, rightRmsDbfs = -3.8f),
            ChannelLevels(channelId = "peer1", name = "Galaxy S24", leftRmsDbfs = -4.5f, rightRmsDbfs = -4.0f),
            ChannelLevels(channelId = "peer2", name = "OnePlus 12", leftRmsDbfs = -4.1f, rightRmsDbfs = -3.9f),
            ChannelLevels(channelId = "peer3", name = "Pixel 7a", leftRmsDbfs = -4.3f, rightRmsDbfs = -4.2f)
        )

        assertEquals(4, devices.size)

        // Verify all channels exhibit synchronized acoustic activity around -4 dB
        devices.forEach { dev ->
            assertTrue(dev.maxRmsDbfs in -5.0f..-3.5f)
            assertEquals(dev.formattedDbfs.takeLast(2), "dB")
        }
    }
}
