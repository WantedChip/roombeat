package com.roombeat.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roombeat.app.session.PeerConnectionState
import com.roombeat.app.session.PeerNode
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SignalOrange
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted
import java.util.Locale

/**
 * Modular hardware channel strip card adhering strictly to the Tactile Acoustic Industrial design system.
 * Features:
 * - Machined obsidian panel enclosure (`SurfacePanel` `#13151A` with 1px `BorderMilled` `#262A35`).
 * - Channel index / tag in `label-sm` `General Sans` uppercase.
 * - Device name in `heading-md` `Cabinet Grotesk`.
 * - Monospaced telemetry (`JetBrains Mono`): IP address, NTP RTT latency in phosphor green (`#00E599`) or amber (`#FFB800`).
 * - Live status beacon ([StatusBeacon]).
 * - Channel volume fader / slider with numeric dB readout and tactile Mute toggle button.
 * - Double-tap fader or snap chip to restore unity gain (`0.0 dB`).
 * - Smooth 40% opacity transition when peer drops with retry status text (`RECONNECTING 1/3`).
 */
@Composable
fun ChannelStripCard(
    peer: PeerNode,
    channelTag: String,
    modifier: Modifier = Modifier,
    isHostControl: Boolean = true,
    onVolumeChange: (Float) -> Unit = {},
    onMuteToggle: () -> Unit = {},
    onKick: (() -> Unit)? = null
) {
    // 40% opacity transition when peer is disconnected/reconnecting
    val cardAlpha by animateFloatAsState(
        targetValue = if (peer.isDisconnectedOrReconnecting) 0.40f else 1.0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "ChannelCardAlpha"
    )

    // Dynamic border color snap on warning/error states
    val borderColor by animateColorAsState(
        targetValue = when {
            peer.state == PeerConnectionState.RECONNECTING || peer.rttMs > 25.0 -> SyncAmber
            peer.state == PeerConnectionState.DISCONNECTED -> SyncRed
            else -> BorderMilled
        },
        animationSpec = tween(durationMillis = 300),
        label = "ChannelCardBorder"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row: Channel Tag & Connection Telemetry Beacon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Channel Tag (e.g. "CH 01" or "HOST CH 00")
                Text(
                    text = channelTag.uppercase(Locale.US),
                    style = RoomBeatTheme.typography.labelSm,
                    color = TextDim
                )

                // Real-time telemetry status & beacon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (peer.state) {
                        PeerConnectionState.RECONNECTING -> {
                            Text(
                                text = "RECONNECTING ${peer.reconnectAttempt}/${peer.maxReconnectAttempts}",
                                style = RoomBeatTheme.typography.codeXs,
                                color = SyncAmber,
                                fontWeight = FontWeight.Bold
                            )
                            StatusBeacon(
                                status = BeaconStatus.WARNING,
                                size = 6.dp
                            )
                        }
                        PeerConnectionState.DISCONNECTED -> {
                            Text(
                                text = "DISCONNECTED",
                                style = RoomBeatTheme.typography.codeXs,
                                color = SyncRed,
                                fontWeight = FontWeight.Bold
                            )
                            StatusBeacon(
                                status = BeaconStatus.ERROR,
                                size = 6.dp
                            )
                        }
                        PeerConnectionState.CONNECTED -> {
                            Text(
                                text = peer.ip,
                                style = RoomBeatTheme.typography.codeXs,
                                color = TextDim
                            )
                            Text(
                                text = "·",
                                style = RoomBeatTheme.typography.codeXs,
                                color = TextDim
                            )
                            Text(
                                text = String.format(Locale.US, "±%.1fms · 0.0%% loss", peer.rttMs),
                                style = RoomBeatTheme.typography.codeXs,
                                color = if (peer.rttMs > 25.0) SyncAmber else SyncGreen,
                                fontWeight = FontWeight.Medium
                            )
                            StatusBeacon(
                                status = if (peer.rttMs > 25.0) BeaconStatus.WARNING else BeaconStatus.LOCKED,
                                size = 6.dp
                            )
                        }
                    }
                }
            }

            // Middle Row: Device Name & Optional Eject Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = peer.name,
                    style = RoomBeatTheme.typography.headingMd,
                    color = TextBone
                )

                if (onKick != null && isHostControl) {
                    TactileKeycapButton(
                        onClick = onKick,
                        variant = TactileButtonVariant.DESTRUCTIVE,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        cornerRadius = 4.dp
                    ) {
                        Text("[ EJECT ]")
                    }
                }
            }

            // Fader Controls Strip
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Telemetry line above fader: dB Readout, Snap to Unity Gain, and Mute Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "LEVEL:",
                            style = RoomBeatTheme.typography.labelSm,
                            color = TextDim
                        )
                        Text(
                            text = if (peer.isMuted) "MUTED (-inf)" else peer.formattedVolumeDb,
                            style = RoomBeatTheme.typography.codeMd,
                            color = if (peer.isMuted) SyncRed else TextBone,
                            fontWeight = FontWeight.Bold
                        )

                        if (isHostControl && !peer.isMuted && peer.volume != 1.0f) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = SurfaceRecessed,
                                border = BorderStroke(1.dp, BorderMilled),
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures {
                                            onVolumeChange(1.0f)
                                        }
                                    }
                            ) {
                                Text(
                                    text = "SNAP 0dB",
                                    style = RoomBeatTheme.typography.codeXs,
                                    color = TextMuted,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (isHostControl) {
                        TactileKeycapButton(
                            onClick = onMuteToggle,
                            variant = if (peer.isMuted) TactileButtonVariant.DESTRUCTIVE else TactileButtonVariant.SURFACE,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            cornerRadius = 4.dp
                        ) {
                            Text(if (peer.isMuted) "[ MUTED ]" else "[ MUTE ]")
                        }
                    }
                }

                // Volume Fader Slider (Range: 0.0 to 1.5, unity gain 1.0 at 0 dB)
                // Supports double-tap to snap back to unity gain (0 dB) per interaction-flows.md
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(isHostControl) {
                            if (isHostControl) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        onVolumeChange(1.0f)
                                    }
                                )
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = if (peer.isMuted) 0.0f else peer.volume,
                        onValueChange = onVolumeChange,
                        valueRange = 0.0f..1.5f,
                        enabled = isHostControl && peer.state == PeerConnectionState.CONNECTED && !peer.isMuted,
                        colors = SliderDefaults.colors(
                            thumbColor = SignalOrange,
                            activeTrackColor = SignalOrange,
                            inactiveTrackColor = SurfaceRecessed,
                            disabledThumbColor = TextDim,
                            disabledActiveTrackColor = BorderMilled,
                            disabledInactiveTrackColor = SurfaceRecessed
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
