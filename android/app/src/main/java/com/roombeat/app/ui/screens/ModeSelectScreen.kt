package com.roombeat.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.roombeat.app.ui.components.BeaconStatus
import com.roombeat.app.ui.components.RecessedPanel
import com.roombeat.app.ui.components.StatusBeacon
import com.roombeat.app.ui.components.TactileButtonVariant
import com.roombeat.app.ui.components.TactileKeycapButton
import com.roombeat.app.ui.components.TelemetryReadout
import com.roombeat.app.ui.components.TelemetrySize
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted

/**
 * Mode Selection Screen (Flow 1 / Sub-phase v0.1.3).
 * Prominently presents the active local LAN/Hotspot audio engine (v1.0)
 * while clearly displaying the deferred WAN Relay module as coming beyond v1.
 */
@Composable
fun ModeSelectScreen(
    onCreateRoom: () -> Unit = {},
    onJoinRoom: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChassisBase)
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top Bar / Telemetry Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "ROOMBEAT",
                    style = RoomBeatTheme.typography.displayLg,
                    color = TextBone
                )
                Text(
                    text = "[ v1.0-LAN ARCHITECTURE ]",
                    style = RoomBeatTheme.typography.codeXs,
                    color = TextDim
                )
            }

            StatusBeacon(
                status = BeaconStatus.LOCKED,
                label = "READY · 0.0ms"
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Active Card: LAN / HOTSPOT MODE (ACTIVE v1)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = SurfacePanel,
            border = BorderStroke(1.dp, BorderActive)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[ ACTIVE v1.0 ]",
                        style = RoomBeatTheme.typography.labelSm,
                        color = SyncGreen,
                        fontWeight = FontWeight.Bold
                    )

                    TelemetryReadout(
                        value = "MULTICAST UDP",
                        size = TelemetrySize.SMALL,
                        telemetryColor = SyncGreen
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "LAN / HOTSPOT MODE",
                        style = RoomBeatTheme.typography.headingMd,
                        color = TextBone
                    )
                    Text(
                        text = "Turn a room of phones into one massive speaker system over local Wi-Fi or portable hotspot. Zero cloud relay, zero tracking, sub-millisecond acoustic sync.",
                        style = RoomBeatTheme.typography.bodyMd,
                        color = TextMuted
                    )
                }

                // Tactile Keycap Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TactileKeycapButton(
                        text = "CREATE ROOM",
                        onClick = onCreateRoom,
                        variant = TactileButtonVariant.PRIMARY,
                        modifier = Modifier.weight(1f)
                    )

                    TactileKeycapButton(
                        text = "JOIN ROOM",
                        onClick = onJoinRoom,
                        variant = TactileButtonVariant.SURFACE,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Deferred Card: WAN RELAY (COMING BEYOND v1)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.55f),
            shape = RoundedCornerShape(8.dp),
            color = SurfacePanel,
            border = BorderStroke(1.dp, BorderMilled)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[ DEFERRED BEYOND v1 ]",
                        style = RoomBeatTheme.typography.labelSm,
                        color = TextDim,
                        fontWeight = FontWeight.Bold
                    )

                    TelemetryReadout(
                        value = "COMING SOON",
                        size = TelemetrySize.SMALL,
                        telemetryColor = TextDim
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "WAN RELAY",
                        style = RoomBeatTheme.typography.headingMd,
                        color = TextDim
                    )
                    Text(
                        text = "Cross-network synchronization across distant locations via cloud relay infrastructure and NAT traversal. Deferred to post-v1 architecture.",
                        style = RoomBeatTheme.typography.bodyMd,
                        color = TextDim
                    )
                }

                TactileKeycapButton(
                    text = "DEFERRED (POST v1)",
                    onClick = {},
                    enabled = false,
                    variant = TactileButtonVariant.SURFACE,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f, fill = false))

        // Hardware Telemetry Readout Strip
        RecessedPanel(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryReadout(
                    label = "HOT PATH",
                    value = "NDK OBOE C++",
                    size = TelemetrySize.SMALL,
                    telemetryColor = SyncGreen
                )

                TelemetryReadout(
                    label = "FRAME",
                    value = "20ms OPUS",
                    size = TelemetrySize.SMALL,
                    telemetryColor = TextBone
                )

                TelemetryReadout(
                    label = "COMPLIANCE",
                    value = "API 30–37",
                    size = TelemetrySize.SMALL,
                    telemetryColor = TextMuted
                )
            }
        }
    }
}

@Preview(name = "Mode Select Screen - Dark", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
fun ModeSelectScreenPreview() {
    RoomBeatTheme {
        ModeSelectScreen()
    }
}
