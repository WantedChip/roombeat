package com.roombeat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.TextBone

/**
 * Inset hardware cavity container conforming to the Tactile Acoustic Industrial design system.
 * Features an obsidian recessed surface (#07080A) bounded by a 1px precision milled border (#262A35).
 */
@Composable
fun RecessedPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
    borderColor: Color = BorderMilled,
    backgroundColor: Color = SurfaceRecessed,
    contentColor: Color = TextBone,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = backgroundColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}
