package com.roombeat.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemeTokensTest {

    @Test
    fun testMasterHexColorTokens() {
        assertEquals(0xFF0B0C0E.toInt(), ChassisBase.toArgb())
        assertEquals(0xFF13151A.toInt(), SurfacePanel.toArgb())
        assertEquals(0xFF1A1D24.toInt(), SurfaceElevated.toArgb())
        assertEquals(0xFF07080A.toInt(), SurfaceRecessed.toArgb())
        assertEquals(0xFF262A35.toInt(), BorderMilled.toArgb())
        assertEquals(0xFF3E4454.toInt(), BorderActive.toArgb())
        assertEquals(0xFFF2F4F8.toInt(), TextBone.toArgb())
        assertEquals(0xFF9DA5B4.toInt(), TextMuted.toArgb())
        assertEquals(0xFF5D6475.toInt(), TextDim.toArgb())
        assertEquals(0xFFFF5500.toInt(), SignalOrange.toArgb())
        assertEquals(0x26FF5500.toInt(), SignalOrangeGlow.toArgb())
        assertEquals(0xFF00E599.toInt(), SyncGreen.toArgb())
        assertEquals(0xFFFFB800.toInt(), SyncAmber.toArgb())
        assertEquals(0xFFFF334B.toInt(), SyncRed.toArgb())
        assertEquals(0xFF1DB954.toInt(), SpotifyGreen.toArgb())
        assertEquals(0xFF00D2FF.toInt(), NetworkCyan.toArgb())
    }

    @Test
    fun testRoomBeatColorsDefaults() {
        val colors = RoomBeatColors()
        assertEquals(ChassisBase, colors.chassisBase)
        assertEquals(SurfacePanel, colors.surfacePanel)
        assertEquals(SurfaceElevated, colors.surfaceElevated)
        assertEquals(SurfaceRecessed, colors.surfaceRecessed)
        assertEquals(BorderMilled, colors.borderMilled)
        assertEquals(BorderActive, colors.borderActive)
        assertEquals(TextBone, colors.textBone)
        assertEquals(TextMuted, colors.textMuted)
        assertEquals(TextDim, colors.textDim)
        assertEquals(SignalOrange, colors.signalOrange)
        assertEquals(SignalOrangeGlow, colors.signalOrangeGlow)
        assertEquals(SyncGreen, colors.syncGreen)
        assertEquals(SyncAmber, colors.syncAmber)
        assertEquals(SyncRed, colors.syncRed)
        assertEquals(SpotifyGreen, colors.spotifyGreen)
        assertEquals(NetworkCyan, colors.networkCyan)
    }

    @Test
    fun testDarkColorSchemeMapping() {
        val scheme = RoomBeatDarkColorScheme
        assertEquals(SignalOrange, scheme.primary)
        assertEquals(ChassisBase, scheme.onPrimary)
        assertEquals(SurfaceElevated, scheme.primaryContainer)
        assertEquals(SignalOrange, scheme.onPrimaryContainer)
        assertEquals(SyncGreen, scheme.secondary)
        assertEquals(ChassisBase, scheme.onSecondary)
        assertEquals(ChassisBase, scheme.background)
        assertEquals(TextBone, scheme.onBackground)
        assertEquals(SurfacePanel, scheme.surface)
        assertEquals(TextBone, scheme.onSurface)
        assertEquals(BorderMilled, scheme.outline)
        assertEquals(BorderActive, scheme.outlineVariant)
        assertEquals(SyncRed, scheme.error)
        assertEquals(TextBone, scheme.onError)
    }

    @Test
    fun testWcagContrastTextBoneOnChassisBase() {
        val contrast = calculateContrastRatio(TextBone, ChassisBase)
        // Design system guarantees >17.5:1 (WCAG AAA requires 7:1)
        assertTrue("Contrast ratio $contrast must be >= 17.5:1", contrast >= 17.5)
    }

    @Test
    fun testWcagContrastTextMutedOnSurfacePanel() {
        val contrast = calculateContrastRatio(TextMuted, SurfacePanel)
        // Design system specifies ~6.3:1 (WCAG AA requires 4.5:1)
        assertTrue("Contrast ratio $contrast must be >= 4.5:1 (WCAG AA)", contrast >= 4.5)
    }

    @Test
    fun testWcagContrastSyncGreenOnChassisBase() {
        val contrast = calculateContrastRatio(SyncGreen, ChassisBase)
        // Design system specifies ~12.4:1 (WCAG AAA requires 7:1)
        assertTrue("Contrast ratio $contrast must be >= 7.0:1 (WCAG AAA)", contrast >= 7.0)
    }

    @Test
    fun testWcagContrastSignalOrangeOnChassisBase() {
        val contrast = calculateContrastRatio(SignalOrange, ChassisBase)
        // Design system specifies ~6.0:1 vs base (WCAG AA for large/bold requires 3.0:1)
        assertTrue("Contrast ratio $contrast must be >= 4.5:1", contrast >= 4.5)
    }

    /**
     * WCAG 2.2 Relative Luminance and Contrast Ratio calculation.
     */
    private fun calculateRelativeLuminance(color: Color): Double {
        fun channelLuminance(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = channelLuminance(color.red)
        val g = channelLuminance(color.green)
        val b = channelLuminance(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun calculateContrastRatio(foreground: Color, background: Color): Double {
        val l1 = calculateRelativeLuminance(foreground)
        val l2 = calculateRelativeLuminance(background)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
