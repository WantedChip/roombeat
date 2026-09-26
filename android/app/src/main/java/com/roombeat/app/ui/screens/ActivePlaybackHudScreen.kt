package com.roombeat.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.roombeat.app.audio.ChannelLevels
import com.roombeat.app.session.PeerConnectionState
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.TransportState
import com.roombeat.app.session.TransportStatus
import com.roombeat.app.ui.components.BeaconStatus
import com.roombeat.app.ui.components.MultiChannelVuMeter
import com.roombeat.app.ui.components.StatusBeacon
import com.roombeat.app.ui.components.StreamTelemetry
import com.roombeat.app.ui.components.TactileButtonVariant
import com.roombeat.app.ui.components.TactileFaderSlider
import com.roombeat.app.ui.components.TactileKeycapButton
import com.roombeat.app.ui.components.TelemetryHeaderBadge
import com.roombeat.app.ui.components.TransportControlBar
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.CabinetGroteskFontFamily
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.GeneralSansFontFamily
import com.roombeat.app.ui.theme.JetBrainsMonoFontFamily
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
import com.roombeat.app.ui.viewmodel.ActivePlaybackHudUiState
import com.roombeat.app.ui.viewmodel.ActivePlaybackHudViewModel
import java.util.Locale

/**
 * UI Test tags for [ActivePlaybackHudScreen].
 */
object ActivePlaybackHudTags {
    const val SCREEN = "active_playback_hud_screen"
    const val TOP_BAR = "active_playback_hud_top_bar"
    const val VU_METER_SECTION = "active_playback_hud_vu_meter_section"
    const val CHANNEL_STRIPS_SECTION = "active_playback_hud_channel_strips_section"
    const val CHANNEL_CARD_PREFIX = "active_playback_channel_card_"
    const val BOTTOM_DOCK = "active_playback_hud_bottom_dock"
    const val MASTER_FADER = "active_playback_hud_master_fader"
    const val TRANSPORT_BAR = "active_playback_hud_transport_bar"
    const val END_SESSION_BUTTON = "active_playback_hud_end_session_button"
    const val END_SESSION_DIALOG = "active_playback_hud_end_session_dialog"
    const val CONFIRM_END_BUTTON = "active_playback_hud_confirm_end_button"
    const val CANCEL_END_BUTTON = "active_playback_hud_cancel_end_button"
}

/**
 * Master Active Playback HUD screen composable wired to [ActivePlaybackHudViewModel].
 *
 * Implements sub-phase v0.8.2:
 * 1. Top Bar: [TelemetryHeaderBadge] displaying live stream telemetry, codec badge,
 *    room title, peer count, and phosphor sync indicator.
 * 2. Center Console: [MultiChannelVuMeter] displaying all participating phone nodes with IEC 60268-10 ballistics.
 * 3. Channel Strip Controls: Channel cards per connected device with [TactileFaderSlider] (magnetic 0dB/-inf detents),
 *    tactile Mute button, Solo button, device monogram (`[HOST]`, `[P1]`, etc.), and connection health beacon.
 * 4. Bottom Dock: Master Volume Fader, Play/Pause/Seek transport bar, and high-visibility `[ END SESSION ]` button.
 * 5. Hardware aesthetics: strictly adheres to Tactile Acoustic Industrial design system.
 */
@Composable
fun ActivePlaybackHudScreen(
    viewModel: ActivePlaybackHudViewModel,
    onSessionEnded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    ActivePlaybackHudContent(
        uiState = uiState,
        onVolumeChange = { deviceId, vol -> viewModel.setDeviceVolume(deviceId, vol) },
        onMuteToggle = { deviceId -> viewModel.toggleMute(deviceId) },
        onSoloToggle = { deviceId -> viewModel.toggleSolo(deviceId) },
        onMasterVolumeChange = { vol -> viewModel.setMasterVolume(vol) },
        onPlay = { viewModel.onPlay() },
        onPause = { viewModel.onPause() },
        onSeek = { posMs -> viewModel.onSeek(posMs) },
        onStop = { viewModel.onStop() },
        onKickPeer = { peerId -> viewModel.kickPeer(peerId) },
        onShowEndSessionDialog = { viewModel.setEndSessionDialogVisible(true) },
        onDismissEndSessionDialog = { viewModel.setEndSessionDialogVisible(false) },
        onConfirmEndSession = {
            viewModel.endSession()
            onSessionEnded()
        },
        modifier = modifier
    )
}

/**
 * Pure state-driven content implementation of the Active Playback HUD.
 */
@Composable
fun ActivePlaybackHudContent(
    uiState: ActivePlaybackHudUiState,
    onVolumeChange: (String, Float) -> Unit = { _, _ -> },
    onMuteToggle: (String) -> Unit = {},
    onSoloToggle: (String) -> Unit = {},
    onMasterVolumeChange: (Float) -> Unit = {},
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onStop: () -> Unit = {},
    onKickPeer: (String) -> Unit = {},
    onShowEndSessionDialog: () -> Unit = {},
    onDismissEndSessionDialog: () -> Unit = {},
    onConfirmEndSession: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ChassisBase)
            .testTag(ActivePlaybackHudTags.SCREEN)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ================================================================
            // 1. TOP BAR: Real-Time Stream Telemetry Badge
            // ================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ActivePlaybackHudTags.TOP_BAR)
            ) {
                TelemetryHeaderBadge(
                    telemetry = uiState.streamTelemetry
                )
            }

            // Scrollable Center Rack Console: VU Peak Meter + Channel Strips
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                // ============================================================
                // 2. CENTER CONSOLE: Multi-Channel VU Peak Meter
                // ============================================================
                item {
                    val channels = if (uiState.channelLevels.isNotEmpty()) {
                        uiState.channelLevels
                    } else {
                        // Fallback channels derived from host + connected peers
                        buildList {
                            add(ChannelLevels(channelId = uiState.localDeviceId, name = "Host"))
                            uiState.peers.forEachIndexed { idx, peer ->
                                add(ChannelLevels(channelId = peer.id, name = "P${idx + 1}"))
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ActivePlaybackHudTags.VU_METER_SECTION)
                    ) {
                        MultiChannelVuMeter(
                            channels = channels,
                            isReducedMotion = uiState.isReducedMotion,
                            meterHeight = 160.dp,
                            title = "REALTIME AUDIO MATRIX · IEC 60268-10"
                        )
                    }
                }

                // Section divider label
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CHANNEL STRIP MATRIX (${uiState.totalDeviceCount} NODES)",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TextDim,
                            letterSpacing = 0.06.sp
                        )

                        if (uiState.soloedDevices.isNotEmpty()) {
                            Surface(
                                color = SurfaceRecessed,
                                shape = RoundedCornerShape(3.dp),
                                border = BorderStroke(1.dp, SyncAmber)
                            ) {
                                Text(
                                    text = "[ SOLO ACTIVE: ${uiState.soloedDevices.size} ]",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = SyncAmber,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // ============================================================
                // 3. CHANNEL STRIP CONTROLS: Host Rig Channel Card
                // ============================================================
                if (uiState.isHost) {
                    item {
                        val isHostSoloed = uiState.soloedDevices.contains(uiState.localDeviceId)
                        val isHostMuted = (uiState.soloedDevices.isNotEmpty() && !isHostSoloed)

                        HudChannelCard(
                            monogram = "[HOST]",
                            deviceName = "Host Rig (Local)",
                            telemetryText = "MASTER NODE · 0.0ms",
                            beaconStatus = BeaconStatus.LOCKED,
                            volume = 1.0f,
                            isMuted = isHostMuted,
                            isSoloed = isHostSoloed,
                            onVolumeChange = { vol -> onVolumeChange(uiState.localDeviceId, vol) },
                            onMuteToggle = { onMuteToggle(uiState.localDeviceId) },
                            onSoloToggle = { onSoloToggle(uiState.localDeviceId) },
                            onKick = null,
                            modifier = Modifier.testTag("${ActivePlaybackHudTags.CHANNEL_CARD_PREFIX}host")
                        )
                    }
                }

                // Connected Peer Channel Cards
                itemsIndexed(
                    items = uiState.peers,
                    key = { _, peer -> peer.id }
                ) { index, peer ->
                    val isPeerSoloed = uiState.soloedDevices.contains(peer.id)
                    val isPeerMuted = peer.isMuted || (uiState.soloedDevices.isNotEmpty() && !isPeerSoloed)
                    val monogram = "[P${index + 1}]"

                    val beaconStatus = when {
                        peer.state == PeerConnectionState.RECONNECTING -> BeaconStatus.WARNING
                        peer.state == PeerConnectionState.DISCONNECTED -> BeaconStatus.ERROR
                        peer.rttMs > 25.0 -> BeaconStatus.WARNING
                        else -> BeaconStatus.LOCKED
                    }

                    val telemetryText = when (peer.state) {
                        PeerConnectionState.RECONNECTING -> "RETRY ${peer.reconnectAttempt}/${peer.maxReconnectAttempts}"
                        PeerConnectionState.DISCONNECTED -> "DISCONNECTED"
                        PeerConnectionState.CONNECTED -> String.format(Locale.US, "±%.1fms · 0.0%% loss", peer.rttMs)
                    }

                    HudChannelCard(
                        monogram = monogram,
                        deviceName = peer.name,
                        telemetryText = telemetryText,
                        beaconStatus = beaconStatus,
                        volume = peer.volume,
                        isMuted = isPeerMuted,
                        isSoloed = isPeerSoloed,
                        isEnabled = peer.state == PeerConnectionState.CONNECTED,
                        isDisconnected = peer.isDisconnectedOrReconnecting,
                        onVolumeChange = { vol -> onVolumeChange(peer.id, vol) },
                        onMuteToggle = { onMuteToggle(peer.id) },
                        onSoloToggle = { onSoloToggle(peer.id) },
                        onKick = if (uiState.isHost) {
                            { onKickPeer(peer.id) }
                        } else null,
                        modifier = Modifier.testTag("${ActivePlaybackHudTags.CHANNEL_CARD_PREFIX}${peer.id}")
                    )
                }
            }

            // ================================================================
            // 4. BOTTOM DOCK: Master Gain Fader, Transport Bar & End Session
            // ================================================================
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ActivePlaybackHudTags.BOTTOM_DOCK),
                color = SurfacePanel,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderMilled)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Master Output Volume Fader
                    TactileFaderSlider(
                        value = uiState.masterVolume,
                        onValueChange = onMasterVolumeChange,
                        label = "MASTER OUTPUT GAIN",
                        showMarkings = true,
                        showSnapChip = true,
                        modifier = Modifier.testTag(ActivePlaybackHudTags.MASTER_FADER)
                    )

                    // Synchronized Transport Control Bar
                    TransportControlBar(
                        state = uiState.transportState,
                        onPlay = onPlay,
                        onPause = onPause,
                        onSeek = onSeek,
                        onStop = onStop,
                        modifier = Modifier.testTag(ActivePlaybackHudTags.TRANSPORT_BAR)
                    )

                    // High-Visibility End Session Keycap Button
                    TactileKeycapButton(
                        onClick = onShowEndSessionDialog,
                        variant = TactileButtonVariant.DESTRUCTIVE,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ActivePlaybackHudTags.END_SESSION_BUTTON)
                    ) {
                        Text(
                            text = if (uiState.isHost) "[ END SESSION · DISCONNECT ALL ]" else "[ LEAVE SESSION ]",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 0.06.sp
                        )
                    }
                }
            }
        }

        // ====================================================================
        // Confirmation Modal: End Active Session
        // ====================================================================
        if (uiState.isEndSessionDialogVisible) {
            Dialog(onDismissRequest = onDismissEndSessionDialog) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfacePanel,
                    border = BorderStroke(1.dp, BorderActive),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ActivePlaybackHudTags.END_SESSION_DIALOG)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (uiState.isHost) "END ACTIVE SESSION?" else "LEAVE SESSION?",
                            fontFamily = CabinetGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = TextBone
                        )

                        Text(
                            text = if (uiState.isHost) {
                                "This will immediately terminate playback, stop audio streaming, and disconnect all ${uiState.totalDeviceCount} participating phone nodes."
                            } else {
                                "This will disconnect your device from the room and return to mode selection."
                            },
                            fontFamily = GeneralSansFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                            color = TextMuted,
                            lineHeight = 20.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TactileKeycapButton(
                                onClick = onDismissEndSessionDialog,
                                variant = TactileButtonVariant.SURFACE,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(ActivePlaybackHudTags.CANCEL_END_BUTTON)
                            ) {
                                Text("[ CANCEL ]")
                            }

                            TactileKeycapButton(
                                onClick = onConfirmEndSession,
                                variant = TactileButtonVariant.DESTRUCTIVE,
                                modifier = Modifier
                                    .weight(1.3f)
                                    .testTag(ActivePlaybackHudTags.CONFIRM_END_BUTTON)
                            ) {
                                Text(if (uiState.isHost) "[ CONFIRM END ]" else "[ CONFIRM LEAVE ]")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated hardware channel strip card for the Active HUD screen.
 * Features:
 * - Device monogram: `[HOST]`, `[P1]`, `[P2]`, etc.
 * - Device model name and live telemetry badge.
 * - Tactile Mute and Solo keycap buttons with active status styling.
 * - [TactileFaderSlider] with magnetic 0dB / -inf detent snapping.
 */
@Composable
fun HudChannelCard(
    monogram: String,
    deviceName: String,
    telemetryText: String,
    beaconStatus: BeaconStatus,
    volume: Float,
    isMuted: Boolean,
    isSoloed: Boolean,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    isDisconnected: Boolean = false,
    onVolumeChange: (Float) -> Unit = {},
    onMuteToggle: () -> Unit = {},
    onSoloToggle: () -> Unit = {},
    onKick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isDisconnected) 0.40f else 1.0f),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(
            1.dp,
            when {
                isSoloed -> SyncAmber
                isMuted -> BorderMilled
                else -> BorderMilled
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Monogram, Name, and Telemetry Beacon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Device Monogram Badge: [HOST], [P1], etc.
                    Surface(
                        color = SurfaceRecessed,
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(1.dp, BorderActive)
                    ) {
                        Text(
                            text = monogram,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = if (monogram == "[HOST]") SignalOrange else SyncGreen,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = deviceName,
                        fontFamily = CabinetGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = TextBone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Telemetry status readout & beacon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = telemetryText,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = when (beaconStatus) {
                            BeaconStatus.LOCKED -> SyncGreen
                            BeaconStatus.WARNING -> SyncAmber
                            BeaconStatus.ERROR -> SyncRed
                            else -> TextDim
                        }
                    )

                    StatusBeacon(
                        status = beaconStatus,
                        size = 6.dp
                    )

                    if (onKick != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        TactileKeycapButton(
                            onClick = onKick,
                            variant = TactileButtonVariant.DESTRUCTIVE,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            cornerRadius = 3.dp
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Controls Row: Mute Keycap, Solo Keycap, and Fader
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Solo Button
                TactileKeycapButton(
                    onClick = onSoloToggle,
                    variant = if (isSoloed) TactileButtonVariant.PRIMARY else TactileButtonVariant.SURFACE,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    cornerRadius = 4.dp
                ) {
                    Text(
                        text = if (isSoloed) "[ SOLOED ]" else "[ SOLO ]",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (isSoloed) TextBone else TextMuted
                    )
                }

                // Mute Button
                TactileKeycapButton(
                    onClick = onMuteToggle,
                    variant = if (isMuted) TactileButtonVariant.DESTRUCTIVE else TactileButtonVariant.SURFACE,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    cornerRadius = 4.dp
                ) {
                    Text(
                        text = if (isMuted) "[ MUTED ]" else "[ MUTE ]",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            // Tactile Fader with 0dB and -inf magnetic detents
            TactileFaderSlider(
                value = volume,
                onValueChange = onVolumeChange,
                isMuted = isMuted,
                isEnabled = isEnabled,
                showMarkings = true,
                showSnapChip = true,
                label = "CHANNEL GAIN"
            )
        }
    }
}

// ============================================================================
// Compose Previews
// ============================================================================

@Preview(name = "Active Playback HUD Host", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewActivePlaybackHudHost() {
    RoomBeatTheme {
        ActivePlaybackHudContent(
            uiState = ActivePlaybackHudUiState(
                roomName = "STUDIO RACK A",
                isHost = true,
                streamTelemetry = StreamTelemetry(
                    roomName = "STUDIO RACK A",
                    peerCount = 4,
                    driftMs = 0.2,
                    packetLossPercent = 0.0
                ),
                peers = listOf(
                    PeerNode(id = "p1", name = "Pixel 8 Pro", ip = "192.168.1.101", rttMs = 1.2, volume = 1.0f),
                    PeerNode(id = "p2", name = "Galaxy S24", ip = "192.168.1.102", rttMs = 2.4, volume = 0.8f),
                    PeerNode(id = "p3", name = "OnePlus 12", ip = "192.168.1.103", rttMs = 0.8, volume = 1.2f)
                ),
                transportState = TransportState(
                    status = TransportStatus.PLAYING,
                    trackTitle = "Midnight Resonance - NDK Mix",
                    artist = "RoomBeat Sound Collective",
                    currentPositionMs = 84_000L,
                    durationMs = 215_000L,
                    isHost = true
                )
            )
        )
    }
}
