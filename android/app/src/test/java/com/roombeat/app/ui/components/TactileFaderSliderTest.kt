package com.roombeat.app.ui.components

import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.VolumeCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TactileFaderSliderTest {

    @Test
    fun testDetentConstants() {
        assertEquals(1.0f, FaderDetents.UNITY_GAIN_LINEAR, 0.0001f)
        assertEquals(0.0f, FaderDetents.MUTE_GAIN_LINEAR, 0.0001f)
        assertEquals(0.045f, FaderDetents.SNAP_THRESHOLD_UNITY, 0.0001f)
        assertEquals(0.035f, FaderDetents.SNAP_THRESHOLD_MUTE, 0.0001f)
        assertEquals(2.0f, FaderDetents.MAX_GAIN_LINEAR, 0.0001f)
    }

    @Test
    fun testUnityGainMagneticSnapping() {
        // Values within unity threshold should snap to 1.0f
        val nearUnityBelow = 0.98f
        val isSnappedBelow = abs(nearUnityBelow - FaderDetents.UNITY_GAIN_LINEAR) <= FaderDetents.SNAP_THRESHOLD_UNITY
        assertTrue(isSnappedBelow)

        val nearUnityAbove = 1.03f
        val isSnappedAbove = abs(nearUnityAbove - FaderDetents.UNITY_GAIN_LINEAR) <= FaderDetents.SNAP_THRESHOLD_UNITY
        assertTrue(isSnappedAbove)

        // Values outside threshold should not snap
        val farBelow = 0.85f
        val isNotSnapped = abs(farBelow - FaderDetents.UNITY_GAIN_LINEAR) <= FaderDetents.SNAP_THRESHOLD_UNITY
        assertFalse(isNotSnapped)
    }

    @Test
    fun testMuteGainMagneticSnapping() {
        // Values within mute threshold should snap to 0.0f
        val nearMute = 0.02f
        val isSnappedMute = nearMute <= FaderDetents.SNAP_THRESHOLD_MUTE
        assertTrue(isSnappedMute)

        // Values above threshold should not snap to mute
        val aboveMute = 0.08f
        val isNotMute = aboveMute <= FaderDetents.SNAP_THRESHOLD_MUTE
        assertFalse(isNotMute)
    }

    @Test
    fun testLinearGainToDbStringFormatting() {
        assertEquals("0.0 dB", PeerNode.volumeToDbString(1.0f))
        assertEquals("+6.0 dB", PeerNode.volumeToDbString(2.0f))
        assertEquals("-6.0 dB", PeerNode.volumeToDbString(0.5f))
        assertEquals("-12.0 dB", PeerNode.volumeToDbString(0.25f))
        assertEquals("-inf dB", PeerNode.volumeToDbString(0.0f))
    }

    @Test
    fun testLinearToDbConversionMath() {
        assertEquals(0.0f, VolumeCoordinator.linearToDb(1.0f), 0.001f)
        assertEquals(6.0206f, VolumeCoordinator.linearToDb(2.0f), 0.01f)
        assertEquals(-6.0206f, VolumeCoordinator.linearToDb(0.5f), 0.01f)
        assertEquals(-12.0412f, VolumeCoordinator.linearToDb(0.25f), 0.01f)
        assertEquals(Float.NEGATIVE_INFINITY, VolumeCoordinator.linearToDb(0.0f), 0.001f)
    }

    @Test
    fun testTactileFaderTestTags() {
        assertEquals("tactile_fader_container", TactileFaderSliderTags.CONTAINER)
        assertEquals("tactile_fader_slider", TactileFaderSliderTags.SLIDER)
        assertEquals("tactile_fader_track", TactileFaderSliderTags.TRACK)
        assertEquals("tactile_fader_thumb", TactileFaderSliderTags.THUMB)
        assertEquals("tactile_fader_gain_readout", TactileFaderSliderTags.GAIN_READOUT)
        assertEquals("tactile_fader_snap_0db", TactileFaderSliderTags.SNAP_0DB)
        assertEquals("tactile_fader_markings_row", TactileFaderSliderTags.MARKINGS_ROW)
        assertEquals("tactile_fader_mark_plus_6", TactileFaderSliderTags.MARKING_PLUS_6)
        assertEquals("tactile_fader_mark_0", TactileFaderSliderTags.MARKING_0)
        assertEquals("tactile_fader_mark_minus_6", TactileFaderSliderTags.MARKING_MINUS_6)
        assertEquals("tactile_fader_mark_minus_12", TactileFaderSliderTags.MARKING_MINUS_12)
        assertEquals("tactile_fader_mark_minus_inf", TactileFaderSliderTags.MARKING_MINUS_INF)
    }
}
