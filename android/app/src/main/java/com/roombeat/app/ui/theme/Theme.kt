package com.roombeat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Accessor for RoomBeat custom design tokens (colors and typography).
 */
object RoomBeatTheme {
    val colors: RoomBeatColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRoomBeatColors.current

    val typography: RoomBeatTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalRoomBeatTypography.current
}

/**
 * RoomBeat Theme wrapper applying the Tactile Acoustic Industrial design system.
 * Configures the dark color scheme, typography scale, and chassis base background.
 */
@Composable
fun RoomBeatTheme(
    content: @Composable () -> Unit
) {
    val colors = RoomBeatColors()
    val typography = RoomBeatTypography()

    CompositionLocalProvider(
        LocalRoomBeatColors provides colors,
        LocalRoomBeatTypography provides typography
    ) {
        MaterialTheme(
            colorScheme = RoomBeatDarkColorScheme,
            typography = RoomBeatMaterialTypography
        ) {
            Surface(
                color = colors.chassisBase,
                content = content
            )
        }
    }
}
