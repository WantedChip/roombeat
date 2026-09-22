package com.roombeat.app.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SurfaceElevated
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted

/**
 * Modal explanation dialog explaining high network jitter, router AP isolation,
 * and IGMP snooping multicast blocking, with step-by-step resolution advice.
 *
 * Adheres strictly to the "Tactile Acoustic Industrial" design system.
 */
@Composable
fun RouterWarningDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onProceed: (() -> Unit)? = null,
    onOpenHotspotSettings: (() -> Unit)? = null,
    jitterMs: Double? = null,
    packetLossRate: Double? = null
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfacePanel,
            border = BorderStroke(1.dp, BorderActive),
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("router_warning_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: Modal Title & Diagnostic Tag
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ROUTER NETWORK WARNING",
                        style = RoomBeatTheme.typography.headingMd,
                        color = TextBone
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(SyncAmber, RoundedCornerShape(1.dp))
                        )
                        Text(
                            text = "[ AP ISOLATION ]",
                            style = RoomBeatTheme.typography.codeXs,
                            color = SyncAmber
                        )
                    }
                }

                // Recessed Industrial Diagnostic Telemetry Box
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceRecessed,
                    border = BorderStroke(1.dp, BorderMilled),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "DIAGNOSTIC : MULTICAST_BLOCKED (PACKET LOSS >= 20%)",
                            style = RoomBeatTheme.typography.codeXs,
                            color = SyncAmber,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "DETECTION  : ROUTER CLIENT ISOLATION OR IGMP SNOOPING",
                            style = RoomBeatTheme.typography.codeXs,
                            color = TextMuted
                        )
                        if (jitterMs != null) {
                            Text(
                                text = "JITTER     : ${String.format(java.util.Locale.US, "%.2f", jitterMs)} ms (EXCEEDS 5.0ms TOLERANCE)",
                                style = RoomBeatTheme.typography.codeXs,
                                color = SyncAmber
                            )
                        }
                        if (packetLossRate != null) {
                            Text(
                                text = "LOSS RATE  : ${(packetLossRate * 100).toInt()}% MULTICAST TEST BEACONS DROPPED",
                                style = RoomBeatTheme.typography.codeXs,
                                color = TextMuted
                            )
                        }
                        Text(
                            text = "IMPACT     : DIRECT PEER UDP SYNC PACKETS CANNOT TRAVERSE LAN",
                            style = RoomBeatTheme.typography.codeXs,
                            color = TextDim
                        )
                    }
                }

                // Technical Explanation
                Text(
                    text = "Your local Wi-Fi router is blocking peer-to-peer UDP multicast packets, or dropping packets due to client AP isolation. RoomBeat requires direct device-to-device communication to achieve sub-millisecond acoustic clock synchronization.",
                    style = RoomBeatTheme.typography.bodyMd,
                    color = TextBone
                )

                // Step-by-Step Resolution Advice
                Text(
                    text = "RECOMMENDED RESOLUTIONS",
                    style = RoomBeatTheme.typography.labelSm,
                    color = TextDim
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ResolutionStepItem(
                        stepIndex = "01",
                        title = "TURN ON HOST PORTABLE HOTSPOT (RECOMMENDED)",
                        description = "Bypasses the router entirely. The host phone broadcasts a direct, zero-congestion local Wi-Fi bubble without needing mobile data.",
                        isRecommended = true
                    )

                    ResolutionStepItem(
                        stepIndex = "02",
                        title = "SWITCH TO 5GHz WI-FI BAND",
                        description = "Move all connected devices from congested 2.4GHz to 5GHz to eliminate airtime contention and packet queueing jitter.",
                        isRecommended = false
                    )

                    ResolutionStepItem(
                        stepIndex = "03",
                        title = "DISABLE 'AP ISOLATION' IN ROUTER SETTINGS",
                        description = "Open your router admin gateway, disable 'AP Isolation' or 'Client Isolation', and enable 'Multicast Forwarding / IGMP Snooping'.",
                        isRecommended = false
                    )
                }

                // Tactile Keycap Buttons
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TactileKeycapButton(
                        text = "OPEN HOTSPOT SETTINGS",
                        onClick = {
                            if (onOpenHotspotSettings != null) {
                                onOpenHotspotSettings()
                            } else {
                                launchHotspotSettings(context)
                            }
                        },
                        variant = TactileButtonVariant.PRIMARY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_hotspot_settings_button")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onProceed != null) {
                            TactileKeycapButton(
                                text = "[ PROCEED ANYWAY ]",
                                onClick = onProceed,
                                variant = TactileButtonVariant.SURFACE,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("dialog_proceed_button")
                            )
                        }

                        TactileKeycapButton(
                            text = "[ DISMISS ]",
                            onClick = onDismiss,
                            variant = TactileButtonVariant.SURFACE,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dialog_dismiss_button")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Resolution step item component within the router explanation modal.
 */
@Composable
private fun ResolutionStepItem(
    stepIndex: String,
    title: String,
    description: String,
    isRecommended: Boolean
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SurfaceElevated,
        border = BorderStroke(1.dp, if (isRecommended) SyncAmber else BorderMilled),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[$stepIndex]",
                    style = RoomBeatTheme.typography.codeXs,
                    color = if (isRecommended) SyncAmber else TextDim,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = title,
                    style = RoomBeatTheme.typography.labelSm,
                    color = if (isRecommended) SyncAmber else TextBone,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = description,
                style = RoomBeatTheme.typography.codeXs,
                color = TextMuted
            )
        }
    }
}

// ==========================================
// Previews
// ==========================================

@Preview(name = "Router Warning Dialog - Dark", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
fun RouterWarningDialogPreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            RouterWarningDialog(
                onDismiss = {},
                onProceed = {},
                onOpenHotspotSettings = {},
                jitterMs = 8.42,
                packetLossRate = 0.50
            )
        }
    }
}
