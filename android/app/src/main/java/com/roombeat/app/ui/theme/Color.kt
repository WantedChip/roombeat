package com.roombeat.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Master Color Hex Tokens (Tactile Acoustic Industrial)
val ChassisBase = Color(0xFF0B0C0E)
val SurfacePanel = Color(0xFF13151A)
val SurfaceElevated = Color(0xFF1A1D24)
val SurfaceRecessed = Color(0xFF07080A)
val BorderMilled = Color(0xFF262A35)
val BorderActive = Color(0xFF3E4454)
val TextBone = Color(0xFFF2F4F8)
val TextMuted = Color(0xFF9DA5B4)
val TextDim = Color(0xFF5D6475)
val SignalOrange = Color(0xFFFF5500)
val SignalOrangeGlow = Color(0x26FF5500)
val SyncGreen = Color(0xFF00E599)
val SyncAmber = Color(0xFFFFB800)
val SyncRed = Color(0xFFFF334B)
val SpotifyGreen = Color(0xFF1DB954)
val NetworkCyan = Color(0xFF00D2FF)

/**
 * Immutable palette containing all RoomBeat design tokens.
 */
@Immutable
data class RoomBeatColors(
    val chassisBase: Color = ChassisBase,
    val surfacePanel: Color = SurfacePanel,
    val surfaceElevated: Color = SurfaceElevated,
    val surfaceRecessed: Color = SurfaceRecessed,
    val borderMilled: Color = BorderMilled,
    val borderActive: Color = BorderActive,
    val textBone: Color = TextBone,
    val textMuted: Color = TextMuted,
    val textDim: Color = TextDim,
    val signalOrange: Color = SignalOrange,
    val signalOrangeGlow: Color = SignalOrangeGlow,
    val syncGreen: Color = SyncGreen,
    val syncAmber: Color = SyncAmber,
    val syncRed: Color = SyncRed,
    val spotifyGreen: Color = SpotifyGreen,
    val networkCyan: Color = NetworkCyan
)

val LocalRoomBeatColors = staticCompositionLocalOf { RoomBeatColors() }

/**
 * Material 3 Dark ColorScheme customized to conform to the RoomBeat hardware aesthetic.
 */
val RoomBeatDarkColorScheme = darkColorScheme(
    primary = SignalOrange,
    onPrimary = ChassisBase,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = SignalOrange,
    secondary = SyncGreen,
    onSecondary = ChassisBase,
    secondaryContainer = SurfacePanel,
    onSecondaryContainer = SyncGreen,
    tertiary = NetworkCyan,
    onTertiary = ChassisBase,
    background = ChassisBase,
    onBackground = TextBone,
    surface = SurfacePanel,
    onSurface = TextBone,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    surfaceContainer = SurfacePanel,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerLowest = SurfaceRecessed,
    outline = BorderMilled,
    outlineVariant = BorderActive,
    error = SyncRed,
    onError = TextBone
)
