package com.roombeat.app.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roombeat.app.ui.components.BeaconStatus
import com.roombeat.app.ui.components.CalibrationOscilloscope
import com.roombeat.app.ui.components.HotspotFallbackBanner
import com.roombeat.app.ui.components.RecessedPanel
import com.roombeat.app.ui.components.RouterWarningDialog
import com.roombeat.app.ui.components.StatusBeacon
import com.roombeat.app.ui.components.TactileButtonVariant
import com.roombeat.app.ui.components.TactileKeycapButton
import com.roombeat.app.ui.components.TelemetryReadout
import com.roombeat.app.ui.components.TelemetrySize
import com.roombeat.app.ui.components.launchHotspotSettings
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SignalOrange
import com.roombeat.app.ui.theme.SurfaceElevated
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted
import com.roombeat.app.ui.viewmodel.CalibrationStatus
import com.roombeat.app.ui.viewmodel.CalibrationUiState
import com.roombeat.app.ui.viewmodel.CalibrationViewModel
import com.roombeat.app.ui.viewmodel.PeerCalibrationUiModel
import java.util.Locale

/**
 * Interactive Acoustic Calibration Screen for RoomBeat (Sub-phase v0.3.3 / Flow 6).
 *
 * Features:
 * - Real-time custom Canvas-drawn oscilloscope radar sweep ([CalibrationOscilloscope]).
 * - Monospaced countdown telemetry in JetBrains Mono.
 * - Precision sync-lock determination (< 2.0ms clock offset tolerance).
 * - High-contrast phosphor green (#00E599) sync-lock stamp box.
 * - Tactile haptic pulse confirmation on lock transition.
 * - Gated high-voltage Signal Orange [ PROCEED TO AUDIO SOURCE ] button.
 * - Router AP Isolation fallback guidance ([HotspotFallbackBanner] & [RouterWarningDialog]).
 * - Reduced motion support (disabling rotation in favor of static waveform telemetry).
 */
@Composable
fun CalibrationScreen(
    viewModel: CalibrationViewModel,
    onProceedToAudioSource: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    // Trigger physical tactile haptic feedback when sync locks
    LaunchedEffect(uiState.isSyncLocked) {
        if (uiState.isSyncLocked) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } catch (_: Throwable) {}
            try {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (_: Throwable) {}
        }
    }

    CalibrationScreenContent(
        uiState = uiState,
        onStartCalibration = { viewModel.startCalibration() },
        onRecalibrate = { viewModel.recalibrate() },
        onProceedToAudioSource = {
            if (viewModel.proceedToAudioSource()) {
                onProceedToAudioSource()
            }
        },
        onNavigateBack = onNavigateBack,
        onOpenHotspotSettings = { launchHotspotSettings(context) },
        onShowRouterWarning = { viewModel.showRouterWarning(true) },
        onDismissRouterWarning = { viewModel.showRouterWarning(false) },
        modifier = modifier
    )
}

/**
 * Stateless content composable for [CalibrationScreen], supporting Compose previews.
 */
@Composable
fun CalibrationScreenContent(
    uiState: CalibrationUiState,
    onStartCalibration: () -> Unit,
    onRecalibrate: () -> Unit,
    onProceedToAudioSource: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onShowRouterWarning: () -> Unit,
    onDismissRouterWarning: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ChassisBase,
        modifier = modifier
            .fillMaxSize()
            .testTag("calibration_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Hardware Navigation Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TactileKeycapButton(
                    onClick = onNavigateBack,
                    variant = TactileButtonVariant.SURFACE,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("calibration_cancel_button")
                ) {
                    Text(
                        text = "CANCEL",
                        style = RoomBeatTheme.typography.labelSm,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ACOUSTIC CALIBRATION",
                        style = RoomBeatTheme.typography.headingMd,
                        color = TextBone
                    )
                    Text(
                        text = if (uiState.isHost) "[ HOST RIG ORCHESTRATOR ]" else "[ CLIENT NODE STANDBY ]",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                }

                StatusBeacon(
                    status = when (uiState.status) {
                        CalibrationStatus.SYNC_LOCKED -> BeaconStatus.LOCKED
                        CalibrationStatus.CALIBRATING -> BeaconStatus.CALIBRATING
                        CalibrationStatus.MULTICAST_BLOCKED -> BeaconStatus.WARNING
                        CalibrationStatus.FAILED -> BeaconStatus.ERROR
                        CalibrationStatus.IDLE -> BeaconStatus.IDLE
                    }
                )
            }

            // 2. Central Calibration Console (Oscilloscope & Live Telemetry)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Hotspot Fallback Banner when Multicast is Blocked
                if (uiState.isMulticastBlocked) {
                    HotspotFallbackBanner(
                        onOpenHotspotSettings = onOpenHotspotSettings,
                        onShowDetails = onShowRouterWarning,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Radar Sweep Oscilloscope Canvas
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CalibrationOscilloscope(
                        isSyncLocked = uiState.isSyncLocked,
                        isMulticastBlocked = uiState.isMulticastBlocked,
                        offsetMs = uiState.averageOffsetMs,
                        jitterMs = uiState.averageJitterMs,
                        rttMs = uiState.averageRttMs,
                        progress = uiState.calibrationProgress,
                        rippleTrigger = uiState.lastPulseTimestamp
                    )
                }

                // Countdown & Sequence Telemetry Readout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.status == CalibrationStatus.CALIBRATING) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "CALIBRATING... %02ds  ·  BURST: %d/%d PROBES",
                                uiState.countdownRemainingSeconds,
                                uiState.currentProbeSequence,
                                uiState.totalExpectedProbes
                            ),
                            style = RoomBeatTheme.typography.codeMd,
                            color = SyncAmber,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    } else if (uiState.isSyncLocked) {
                        Text(
                            text = "CALIBRATION BURST COMPLETE · ACCURACY < 2.0ms",
                            style = RoomBeatTheme.typography.codeMd,
                            color = SyncGreen,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    } else if (uiState.isMulticastBlocked) {
                        Text(
                            text = "MULTICAST BEACON BLOCKED BY LOCAL ROUTER",
                            style = RoomBeatTheme.typography.codeMd,
                            color = SyncRed,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = uiState.statusMessage,
                            style = RoomBeatTheme.typography.codeMd,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Sync-Lock Stamp Box
                AnimatedVisibility(
                    visible = uiState.isSyncLocked,
                    enter = fadeIn(tween(150, easing = FastOutSlowInEasing)) + slideInVertically(),
                    exit = fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SurfaceRecessed,
                        border = BorderStroke(2.dp, SyncGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_lock_stamp")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SyncGreen.copy(alpha = 0.08f))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusBeacon(status = BeaconStatus.LOCKED, size = 10.dp)
                                Text(
                                    text = uiState.syncLockStamp ?: "ACOUSTIC SYNC LOCKED — OFFSET: ±0.2ms",
                                    style = RoomBeatTheme.typography.codeMd,
                                    color = SyncGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "NTP JITTER FILTER: OPTIMAL · MULTICAST INTEGRITY: 100%",
                                style = RoomBeatTheme.typography.codeXs,
                                color = TextDim
                            )
                        }
                    }
                }

                // Recessed Master Telemetry Strip
                RecessedPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("calibration_telemetry_strip"),
                    cornerRadius = 6.dp,
                    contentPadding = PaddingValues(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TelemetryReadout(
                            label = "AVG OFFSET",
                            value = uiState.formattedAverageOffset,
                            size = TelemetrySize.MEDIUM,
                            telemetryColor = if (uiState.isSyncLocked) SyncGreen else TextBone
                        )
                        TelemetryReadout(
                            label = "MAX JITTER",
                            value = uiState.formattedAverageJitter,
                            size = TelemetrySize.MEDIUM,
                            telemetryColor = SyncAmber
                        )
                        TelemetryReadout(
                            label = "RTT",
                            value = uiState.formattedAverageRtt,
                            size = TelemetrySize.MEDIUM,
                            telemetryColor = TextBone
                        )
                        TelemetryReadout(
                            label = "PACKET LOSS",
                            value = uiState.formattedAverageLoss,
                            size = TelemetrySize.MEDIUM,
                            telemetryColor = if (uiState.averagePacketLossPercent > 5.0) SyncRed else TextBone
                        )
                    }
                }

                // Peer Sync Status List
                if (uiState.peers.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("calibration_peers_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(uiState.peers, key = { it.peerId }) { peer ->
                            PeerCalibrationCard(peer = peer)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(SurfacePanel, RoundedCornerShape(6.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.isHost) "[ HOST NODE READY — 0 CLIENT PEERS CONNECTED ]" else "[ LISTENING FOR HOST CALIBRATION BURST ]",
                            style = RoomBeatTheme.typography.codeXs,
                            color = TextDim,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 3. Sticky Bottom Action Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.isHost) {
                    // Host Action: Proceed to Audio Source Selection
                    TactileKeycapButton(
                        onClick = onProceedToAudioSource,
                        enabled = uiState.canProceedToAudioSource,
                        variant = TactileButtonVariant.PRIMARY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("proceed_to_source_button")
                    ) {
                        Text(
                            text = if (uiState.isSyncLocked) "[ PROCEED TO AUDIO SOURCE ]" else "[ CALIBRATING... SYNC LOCKED REQUIRED ]",
                            style = RoomBeatTheme.typography.bodyMd,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Optional Re-Calibrate / Retry Action
                    if (!uiState.isSyncLocked && uiState.status != CalibrationStatus.CALIBRATING) {
                        TactileKeycapButton(
                            onClick = onStartCalibration,
                            variant = TactileButtonVariant.SURFACE,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("recalibrate_button")
                        ) {
                            Text(
                                text = "CALIBRATE ACOUSTIC RIG",
                                style = RoomBeatTheme.typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = SignalOrange
                            )
                        }
                    } else if (uiState.isSyncLocked) {
                        TactileKeycapButton(
                            onClick = onRecalibrate,
                            variant = TactileButtonVariant.SURFACE,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("recalibrate_button")
                        ) {
                            Text(
                                text = "RE-VERIFY SYNC BURST",
                                style = RoomBeatTheme.typography.labelSm,
                                fontWeight = FontWeight.Normal,
                                color = TextMuted
                            )
                        }
                    }
                } else {
                    // Client Standby Notice
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SurfacePanel,
                        border = BorderStroke(1.dp, BorderMilled),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            StatusBeacon(
                                status = if (uiState.isSyncLocked) BeaconStatus.LOCKED else BeaconStatus.CALIBRATING,
                                size = 8.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isSyncLocked) {
                                    "SYNC LOCKED · WAITING FOR HOST TO START PLAYBACK"
                                } else {
                                    "CALIBRATING TIMELINE WITH HOST RIG..."
                                },
                                style = RoomBeatTheme.typography.codeXs,
                                color = if (uiState.isSyncLocked) SyncGreen else TextBone
                            )
                        }
                    }
                }
            }
        }

        // Router Network Warning Modal Dialog
        if (uiState.isRouterWarningVisible) {
            RouterWarningDialog(
                onDismiss = onDismissRouterWarning,
                onProceed = onDismissRouterWarning,
                onOpenHotspotSettings = onOpenHotspotSettings,
                jitterMs = uiState.averageJitterMs,
                packetLossRate = uiState.averagePacketLossPercent / 100.0
            )
        }
    }
}

/**
 * Individual peer card rendered in the calibration list.
 */
@Composable
private fun PeerCalibrationCard(
    peer: PeerCalibrationUiModel,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = SurfaceElevated,
        border = BorderStroke(1.dp, if (peer.isLocked) BorderActive else BorderMilled),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBeacon(
                    status = if (peer.isLocked) BeaconStatus.LOCKED else BeaconStatus.CALIBRATING,
                    size = 6.dp
                )
                Column {
                    Text(
                        text = peer.deviceName,
                        style = RoomBeatTheme.typography.bodyMd,
                        color = TextBone,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "ID: ${peer.peerId.take(8)}",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "OFFSET: ${peer.formattedOffset}",
                        style = RoomBeatTheme.typography.codeXs,
                        color = if (peer.isLocked) SyncGreen else TextBone,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "JITTER: ${peer.formattedJitter}  RTT: ${peer.formattedRtt}",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextMuted
                    )
                }

                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = if (peer.isLocked) SyncGreen.copy(alpha = 0.15f) else SyncAmber.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (peer.isLocked) "LOCKED" else "SYNCING",
                        style = RoomBeatTheme.typography.codeXs,
                        color = if (peer.isLocked) SyncGreen else SyncAmber,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// =============================================================================
// Previews
// =============================================================================

@Preview(name = "Active Calibration Screen", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun CalibrationScreenActivePreview() {
    RoomBeatTheme {
        CalibrationScreenContent(
            uiState = CalibrationUiState(
                isHost = true,
                status = CalibrationStatus.CALIBRATING,
                countdownRemainingSeconds = 4,
                totalDurationSeconds = 5,
                calibrationProgress = 0.60f,
                currentProbeSequence = 30,
                averageOffsetMs = 0.42,
                averageJitterMs = 0.28,
                averageRttMs = 3.8,
                averagePacketLossPercent = 0.0,
                peers = listOf(
                    PeerCalibrationUiModel(
                        peerId = "peer-pixel-7",
                        deviceName = "Pixel 7 Pro",
                        offsetMs = 0.24,
                        rttMs = 3.5,
                        jitterMs = 0.15,
                        isLocked = true
                    ),
                    PeerCalibrationUiModel(
                        peerId = "peer-galaxy-s23",
                        deviceName = "Galaxy S23",
                        offsetMs = 0.60,
                        rttMs = 4.1,
                        jitterMs = 0.41,
                        isLocked = false
                    )
                )
            ),
            onStartCalibration = {},
            onRecalibrate = {},
            onProceedToAudioSource = {},
            onNavigateBack = {},
            onOpenHotspotSettings = {},
            onShowRouterWarning = {},
            onDismissRouterWarning = {}
        )
    }
}

@Preview(name = "Sync Locked Calibration Screen", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun CalibrationScreenLockedPreview() {
    RoomBeatTheme {
        CalibrationScreenContent(
            uiState = CalibrationUiState(
                isHost = true,
                status = CalibrationStatus.SYNC_LOCKED,
                isSyncLocked = true,
                canProceedToAudioSource = true,
                syncLockStamp = "ACOUSTIC SYNC LOCKED — OFFSET: ±0.2ms",
                averageOffsetMs = 0.18,
                maxOffsetMs = 0.24,
                averageJitterMs = 0.12,
                averageRttMs = 3.2,
                averagePacketLossPercent = 0.0,
                peers = listOf(
                    PeerCalibrationUiModel(
                        peerId = "peer-pixel-7",
                        deviceName = "Pixel 7 Pro",
                        offsetMs = 0.12,
                        rttMs = 3.1,
                        jitterMs = 0.10,
                        isLocked = true
                    ),
                    PeerCalibrationUiModel(
                        peerId = "peer-galaxy-s23",
                        deviceName = "Galaxy S23",
                        offsetMs = 0.24,
                        rttMs = 3.3,
                        jitterMs = 0.14,
                        isLocked = true
                    )
                )
            ),
            onStartCalibration = {},
            onRecalibrate = {},
            onProceedToAudioSource = {},
            onNavigateBack = {},
            onOpenHotspotSettings = {},
            onShowRouterWarning = {},
            onDismissRouterWarning = {}
        )
    }
}

@Preview(name = "Multicast Blocked Calibration Screen", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun CalibrationScreenBlockedPreview() {
    RoomBeatTheme {
        CalibrationScreenContent(
            uiState = CalibrationUiState(
                isHost = true,
                status = CalibrationStatus.MULTICAST_BLOCKED,
                isMulticastBlocked = true,
                canProceedToAudioSource = false,
                averageOffsetMs = 3.8,
                averageJitterMs = 8.5,
                averageRttMs = 34.0,
                averagePacketLossPercent = 40.0
            ),
            onStartCalibration = {},
            onRecalibrate = {},
            onProceedToAudioSource = {},
            onNavigateBack = {},
            onOpenHotspotSettings = {},
            onShowRouterWarning = {},
            onDismissRouterWarning = {}
        )
    }
}
