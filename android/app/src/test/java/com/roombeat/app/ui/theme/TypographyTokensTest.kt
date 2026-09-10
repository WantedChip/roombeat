package com.roombeat.app.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyTokensTest {

    private val typography = RoomBeatTypography()

    @Test
    fun testDisplay2xlTokens() {
        assertEquals(56.sp, typography.display2xl.fontSize)
        assertEquals(58.8.sp, typography.display2xl.lineHeight)
        assertEquals((-0.03).em, typography.display2xl.letterSpacing)
        assertEquals(FontWeight.ExtraBold, typography.display2xl.fontWeight)
    }

    @Test
    fun testDisplayXlTokens() {
        assertEquals(40.sp, typography.displayXl.fontSize)
        assertEquals(44.sp, typography.displayXl.lineHeight)
        assertEquals((-0.025).em, typography.displayXl.letterSpacing)
        assertEquals(FontWeight.ExtraBold, typography.displayXl.fontWeight)
    }

    @Test
    fun testDisplayLgTokens() {
        assertEquals(28.sp, typography.displayLg.fontSize)
        assertEquals(33.6.sp, typography.displayLg.lineHeight)
        assertEquals((-0.02).em, typography.displayLg.letterSpacing)
        assertEquals(FontWeight.Bold, typography.displayLg.fontWeight)
    }

    @Test
    fun testHeadingMdTokens() {
        assertEquals(20.sp, typography.headingMd.fontSize)
        assertEquals(26.sp, typography.headingMd.lineHeight)
        assertEquals((-0.015).em, typography.headingMd.letterSpacing)
        assertEquals(FontWeight.Bold, typography.headingMd.fontWeight)
    }

    @Test
    fun testBodyLgTokens() {
        assertEquals(17.sp, typography.bodyLg.fontSize)
        assertEquals(25.5.sp, typography.bodyLg.lineHeight)
        assertEquals((-0.01).em, typography.bodyLg.letterSpacing)
        assertEquals(FontWeight.Normal, typography.bodyLg.fontWeight)
    }

    @Test
    fun testBodyMdTokens() {
        assertEquals(14.sp, typography.bodyMd.fontSize)
        assertEquals(20.3.sp, typography.bodyMd.lineHeight)
        assertEquals(0.sp, typography.bodyMd.letterSpacing)
        assertEquals(FontWeight.Normal, typography.bodyMd.fontWeight)
    }

    @Test
    fun testLabelSmTokens() {
        assertEquals(12.sp, typography.labelSm.fontSize)
        assertEquals(16.8.sp, typography.labelSm.lineHeight)
        assertEquals(0.04.em, typography.labelSm.letterSpacing)
        assertEquals(FontWeight.SemiBold, typography.labelSm.fontWeight)
    }

    @Test
    fun testCodeXlTokens() {
        assertEquals(36.sp, typography.codeXl.fontSize)
        assertEquals(36.sp, typography.codeXl.lineHeight)
        assertEquals(0.12.em, typography.codeXl.letterSpacing)
        assertEquals(FontWeight.Bold, typography.codeXl.fontWeight)
    }

    @Test
    fun testCodeMdTokens() {
        assertEquals(14.sp, typography.codeMd.fontSize)
        assertEquals(18.2.sp, typography.codeMd.lineHeight)
        assertEquals(0.sp, typography.codeMd.letterSpacing)
        assertEquals(FontWeight.Medium, typography.codeMd.fontWeight)
    }

    @Test
    fun testCodeXsTokens() {
        assertEquals(11.sp, typography.codeXs.fontSize)
        assertEquals(13.2.sp, typography.codeXs.lineHeight)
        assertEquals(0.02.em, typography.codeXs.letterSpacing)
        assertEquals(FontWeight.Medium, typography.codeXs.fontWeight)
    }

    @Test
    fun testMaterialTypographyMapping() {
        assertEquals(typography.display2xl, RoomBeatMaterialTypography.displayLarge)
        assertEquals(typography.displayXl, RoomBeatMaterialTypography.displayMedium)
        assertEquals(typography.displayLg, RoomBeatMaterialTypography.displaySmall)
        assertEquals(typography.headingMd, RoomBeatMaterialTypography.headlineMedium)
        assertEquals(typography.bodyLg, RoomBeatMaterialTypography.bodyLarge)
        assertEquals(typography.bodyMd, RoomBeatMaterialTypography.bodyMedium)
        assertEquals(typography.labelSm, RoomBeatMaterialTypography.labelSmall)
    }
}
