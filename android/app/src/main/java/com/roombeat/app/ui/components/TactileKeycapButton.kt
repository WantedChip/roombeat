package com.roombeat.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SignalOrange
import com.roombeat.app.ui.theme.SurfaceElevated
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone

/**
 * Styling variants for [TactileKeycapButton].
 */
enum class TactileButtonVariant {
    PRIMARY,
    SURFACE,
    DESTRUCTIVE
}

/**
 * Precision hardware-grade keycap button adhering to the Tactile Acoustic Industrial design system.
 * Features:
 * - 4px or 6px machined radius (strictly no rounded-full pills).
 * - Instant physical key depression (1.5px translateY) with deterministic timing (100ms press / 120ms release).
 * - Instant haptic actuation using CLOCK_TICK / TextHandleMove.
 * - Dynamic border color snap on press.
 */
@Composable
fun TactileKeycapButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: TactileButtonVariant = TactileButtonVariant.PRIMARY,
    enabled: Boolean = true,
    cornerRadius: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    // Immediate non-elastic physical depression: 100ms down, 120ms rebound
    val translationY by animateDpAsState(
        targetValue = if (isPressed && enabled) 1.5.dp else 0.dp,
        animationSpec = if (isPressed) {
            tween(durationMillis = 100, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 120, easing = FastOutSlowInEasing)
        },
        label = "KeycapDepression"
    )

    // Hardware colors per variant
    val (containerColor, contentColor, borderColor) = when (variant) {
        TactileButtonVariant.PRIMARY -> Triple(
            SignalOrange,
            ChassisBase,
            if (isPressed) BorderActive else SignalOrange
        )
        TactileButtonVariant.SURFACE -> Triple(
            SurfaceElevated,
            TextBone,
            if (isPressed) SignalOrange else BorderMilled
        )
        TactileButtonVariant.DESTRUCTIVE -> Triple(
            Color(0xFF2A1015),
            SyncRed,
            if (isPressed) SyncRed else Color(0xFF6B1D28)
        )
    }

    Surface(
        modifier = modifier
            .graphicsLayer { this.translationY = translationY.toPx() }
            .alpha(if (enabled) 1.0f else 0.4f)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Custom physical depression replaces generic ripple
                enabled = enabled,
                role = Role.Button
            ) {
                try {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } catch (_: Throwable) {
                    // Safe fallback for test/mock environments
                }
                try {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } catch (_: Throwable) {
                    // Safe fallback
                }
                onClick()
            },
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            ProvideTextStyle(
                value = RoomBeatTheme.typography.labelSm.copy(
                    color = contentColor,
                    fontWeight = FontWeight.Bold
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
    }
}

/**
 * Text overload for [TactileKeycapButton].
 */
@Composable
fun TactileKeycapButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: TactileButtonVariant = TactileButtonVariant.PRIMARY,
    enabled: Boolean = true,
    cornerRadius: Dp = 6.dp
) {
    TactileKeycapButton(
        onClick = onClick,
        modifier = modifier,
        variant = variant,
        enabled = enabled,
        cornerRadius = cornerRadius
    ) {
        Text(text = text)
    }
}
