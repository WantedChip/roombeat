package com.roombeat.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.roombeat.app.R

val CabinetGroteskFontFamily = FontFamily(
    Font(R.font.cabinet_grotesk_bold, FontWeight.Bold),
    Font(R.font.cabinet_grotesk_extrabold, FontWeight.ExtraBold)
)

val GeneralSansFontFamily = FontFamily(
    Font(R.font.general_sans_regular, FontWeight.Normal),
    Font(R.font.general_sans_medium, FontWeight.Medium),
    Font(R.font.general_sans_semibold, FontWeight.SemiBold)
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

/**
 * RoomBeat typography tokens defined by the Tactile Acoustic Industrial design system.
 */
@Immutable
data class RoomBeatTypography(
    val display2xl: TextStyle = TextStyle(
        fontFamily = CabinetGroteskFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 56.sp,
        lineHeight = 58.8.sp,
        letterSpacing = (-0.03).em
    ),
    val displayXl: TextStyle = TextStyle(
        fontFamily = CabinetGroteskFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.025).em
    ),
    val displayLg: TextStyle = TextStyle(
        fontFamily = CabinetGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 33.6.sp,
        letterSpacing = (-0.02).em
    ),
    val headingMd: TextStyle = TextStyle(
        fontFamily = CabinetGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.015).em
    ),
    val bodyLg: TextStyle = TextStyle(
        fontFamily = GeneralSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.5.sp,
        letterSpacing = (-0.01).em
    ),
    val bodyMd: TextStyle = TextStyle(
        fontFamily = GeneralSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.3.sp,
        letterSpacing = 0.sp
    ),
    val labelSm: TextStyle = TextStyle(
        fontFamily = GeneralSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.8.sp,
        letterSpacing = 0.04.em
    ),
    val codeXl: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.12.em
    ),
    val codeMd: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.2.sp,
        letterSpacing = 0.sp
    ),
    val codeXs: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.2.sp,
        letterSpacing = 0.02.em
    )
)

val LocalRoomBeatTypography = staticCompositionLocalOf { RoomBeatTypography() }

val RoomBeatMaterialTypography = Typography(
    displayLarge = RoomBeatTypography().display2xl,
    displayMedium = RoomBeatTypography().displayXl,
    displaySmall = RoomBeatTypography().displayLg,
    headlineMedium = RoomBeatTypography().headingMd,
    bodyLarge = RoomBeatTypography().bodyLg,
    bodyMedium = RoomBeatTypography().bodyMd,
    labelSmall = RoomBeatTypography().labelSm
)
