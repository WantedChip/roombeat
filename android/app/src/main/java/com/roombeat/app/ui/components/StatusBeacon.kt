package com.roombeat.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextDim

/**
 * Status state for [StatusBeacon].
 */
enum class BeaconStatus {
    LOCKED,
    CALIBRATING,
    WARNING,
    ERROR,
    IDLE
}

/**
 * Precision telemetry status beacon adhering to the Tactile Acoustic Industrial design system.
 * Emits telemetry-driven light pulses:
 * - [BeaconStatus.LOCKED]: Subtle 1500ms phosphor green breathing pulse (0.75 to 1.0 alpha).
 * - [BeaconStatus.CALIBRATING]: Rapid 200ms calibration pulse.
 * - [BeaconStatus.WARNING]: Static amber warning state.
 * - [BeaconStatus.ERROR]: Static sync red error/drop state.
 * - [BeaconStatus.IDLE]: Muted dim state.
 */
@Composable
fun StatusBeacon(
    status: BeaconStatus,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    label: String? = null,
    showHalo: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "BeaconPulseTransition")

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = when (status) {
            BeaconStatus.LOCKED -> 0.75f
            BeaconStatus.CALIBRATING -> 0.30f
            BeaconStatus.WARNING, BeaconStatus.ERROR -> 1.0f
            BeaconStatus.IDLE -> 0.50f
        },
        targetValue = 1.0f,
        animationSpec = when (status) {
            BeaconStatus.LOCKED -> infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
            BeaconStatus.CALIBRATING -> infiniteRepeatable(
                animation = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
            else -> infiniteRepeatable(
                animation = tween(durationMillis = 1000),
                repeatMode = RepeatMode.Reverse
            )
        },
        label = "BeaconPulseAlpha"
    )

    val beaconColor: Color = when (status) {
        BeaconStatus.LOCKED -> SyncGreen
        BeaconStatus.CALIBRATING -> SyncAmber
        BeaconStatus.WARNING -> SyncAmber
        BeaconStatus.ERROR -> SyncRed
        BeaconStatus.IDLE -> TextDim
    }

    val currentAlpha = when (status) {
        BeaconStatus.LOCKED, BeaconStatus.CALIBRATING -> pulseAlpha
        BeaconStatus.WARNING, BeaconStatus.ERROR -> 1.0f
        BeaconStatus.IDLE -> 0.5f
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(size * 2),
            contentAlignment = Alignment.Center
        ) {
            // Halo ring
            if (showHalo && status != BeaconStatus.IDLE) {
                Box(
                    modifier = Modifier
                        .size(size * 1.8f)
                        .alpha(currentAlpha * 0.25f)
                        .clip(CircleShape)
                        .background(beaconColor)
                )
            }

            // Core phosphor dot
            Box(
                modifier = Modifier
                    .size(size)
                    .alpha(currentAlpha)
                    .clip(CircleShape)
                    .background(beaconColor)
            )
        }

        if (label != null) {
            Text(
                text = label,
                style = RoomBeatTheme.typography.codeXs,
                color = when (status) {
                    BeaconStatus.LOCKED -> SyncGreen
                    BeaconStatus.CALIBRATING, BeaconStatus.WARNING -> SyncAmber
                    BeaconStatus.ERROR -> SyncRed
                    BeaconStatus.IDLE -> TextDim
                }
            )
        }
    }
}
