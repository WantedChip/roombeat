package com.roombeat.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.roombeat.app.session.PeerConnectionState
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.PinGenerator
import com.roombeat.app.session.QrMatrixGenerator
import com.roombeat.app.session.QrSessionPayload
import com.roombeat.app.ui.components.BeaconStatus
import com.roombeat.app.ui.components.ChannelStripCard
import com.roombeat.app.ui.components.RecessedPanel
import com.roombeat.app.ui.components.StatusBeacon
import com.roombeat.app.ui.components.TactileButtonVariant
import com.roombeat.app.ui.components.TactileKeycapButton
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
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted
import com.roombeat.app.ui.viewmodel.RoomLobbyUiState
import com.roombeat.app.ui.viewmodel.RoomLobbyViewModel
import java.util.Locale

/**
 * Room Lobby screen for Host and Client devices (Sub-phase v0.2.4 / Flows 2 & 4).
 * Features:
 * - Header with large monospaced 6-digit room PIN display and [ SHOW QR ] trigger.
 * - Fullscreen QR modal displaying session coordinates and high-contrast matrix.
 * - Dynamic Channel Strip Matrix displaying real-time telemetry and per-device faders.
 * - Sticky bottom Master Control Strip with [ PROCEED TO AUDIO SOURCE ] action for Host.
 * - Client standby mode waiting on host source selection.
 */
@Composable
fun RoomLobbyScreen(
    viewModel: RoomLobbyViewModel,
    onProceedToSource: () -> Unit = {},
    onLeaveRoom: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    RoomLobbyContent(
        uiState = uiState,
        onShowQrModal = { viewModel.setQrModalVisible(true) },
        onDismissQrModal = { viewModel.setQrModalVisible(false) },
        onVolumeChange = { peerId, vol -> viewModel.setPeerVolume(peerId, vol) },
        onMuteToggle = { peerId -> viewModel.togglePeerMute(peerId) },
        onKickPeer = { peerId -> viewModel.kickPeer(peerId) },
        onMasterVolumeChange = { vol -> viewModel.setMasterVolume(vol) },
        onProceedToSource = {
            if (viewModel.proceedToSourceSelection()) {
                onProceedToSource()
            }
        },
        onLeaveRoom = {
            viewModel.leaveRoom()
            onLeaveRoom()
        },
        modifier = modifier
    )
}

@Composable
fun RoomLobbyContent(
    uiState: RoomLobbyUiState,
    onShowQrModal: () -> Unit = {},
    onDismissQrModal: () -> Unit = {},
    onVolumeChange: (String, Float) -> Unit = { _, _ -> },
    onMuteToggle: (String) -> Unit = {},
    onKickPeer: (String) -> Unit = {},
    onMasterVolumeChange: (Float) -> Unit = {},
    onProceedToSource: () -> Unit = {},
    onLeaveRoom: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ChassisBase)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (uiState.isHost) 96.dp else 72.dp)
        ) {
            // Screen Top Header
            RoomLobbyHeader(
                uiState = uiState,
                onShowQr = onShowQrModal,
                onLeaveRoom = onLeaveRoom
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Channel Strip Matrix
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Host Self-Channel or Client Status Banner
                if (uiState.isHost) {
                    item {
                        HostInfoCard(uiState = uiState)
                    }
                } else {
                    item {
                        ClientStatusCard(uiState = uiState)
                    }
                }

                // Section Label: Active Peer Nodes
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (uiState.isHost) {
                                "CONNECTED PEER NODES (${uiState.connectedPeerCount})"
                            } else {
                                "RIG NODES IN SESSION (${uiState.connectedPeerCount})"
                            },
                            style = RoomBeatTheme.typography.labelSm,
                            color = TextDim
                        )

                        Text(
                            text = "[ 48kHz · STEREO ]",
                            style = RoomBeatTheme.typography.codeXs,
                            color = TextDim
                        )
                    }
                }

                // List of peer channel cards
                if (uiState.peers.isEmpty()) {
                    item {
                        EmptyPeersCard(isHost = uiState.isHost)
                    }
                } else {
                    itemsIndexed(
                        items = uiState.peers,
                        key = { _, peer -> peer.id }
                    ) { index, peer ->
                        val channelIndexFormatted = String.format(Locale.US, "CH %02d", index + 1)
                        ChannelStripCard(
                            peer = peer,
                            channelTag = channelIndexFormatted,
                            isHostControl = uiState.isHost,
                            onVolumeChange = { vol -> onVolumeChange(peer.id, vol) },
                            onMuteToggle = { onMuteToggle(peer.id) },
                            onKick = if (uiState.isHost) { { onKickPeer(peer.id) } } else null
                        )
                    }
                }
            }
        }

        // Sticky Bottom Master Control Dock
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color = SurfaceElevated,
            border = BorderStroke(1.dp, BorderMilled)
        ) {
            if (uiState.isHost) {
                HostMasterControlDock(
                    uiState = uiState,
                    onMasterVolumeChange = onMasterVolumeChange,
                    onProceedToSource = onProceedToSource
                )
            } else {
                ClientStandbyDock(uiState = uiState)
            }
        }

        // High-Contrast QR Code Dialog Modal
        if (uiState.isQrModalVisible) {
            QrCodeDialog(
                uiState = uiState,
                onDismiss = onDismissQrModal
            )
        }
    }
}

/**
 * Top Header displaying Room Title, 6-digit PIN in code-xl, and QR action button.
 */
@Composable
private fun RoomLobbyHeader(
    uiState: RoomLobbyUiState,
    onShowQr: () -> Unit,
    onLeaveRoom: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfacePanel)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // App Wordmark and Telemetry Beacon Row
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
                    text = if (uiState.isHost) {
                        "[ HOST LOBBY · ${uiState.localIp}:${uiState.port} ]"
                    } else {
                        "[ CLIENT CONSOLE · ${uiState.localIp} ]"
                    },
                    style = RoomBeatTheme.typography.codeXs,
                    color = TextDim
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBeacon(
                    status = if (uiState.isConnected) BeaconStatus.LOCKED else BeaconStatus.WARNING,
                    label = if (uiState.isHost) "HOST READY" else "JOINED"
                )

                TactileKeycapButton(
                    onClick = onLeaveRoom,
                    variant = TactileButtonVariant.SURFACE,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    cornerRadius = 4.dp
                ) {
                    Text("LEAVE")
                }
            }
        }

        // Room PIN Display Box & SHOW QR Trigger
        RecessedPanel(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "ROOM JOIN PIN",
                        style = RoomBeatTheme.typography.labelSm,
                        color = TextDim
                    )
                    Text(
                        text = PinGenerator.formatForDisplay(uiState.roomPin.ifBlank { "------" }),
                        style = RoomBeatTheme.typography.codeXl,
                        color = TextBone,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (uiState.isHost) {
                    TactileKeycapButton(
                        onClick = onShowQr,
                        variant = TactileButtonVariant.PRIMARY,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("[ SHOW QR ]")
                    }
                }
            }
        }
    }
}

/**
 * Host Self Information Card showing local device telemetry.
 */
@Composable
private fun HostInfoCard(uiState: RoomLobbyUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, BorderMilled)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HOST (THIS DEVICE)",
                    style = RoomBeatTheme.typography.labelSm,
                    color = SignalOrange,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${uiState.localIp}:${uiState.port}",
                    style = RoomBeatTheme.typography.codeXs,
                    color = TextDim
                )
            }

            Text(
                text = uiState.hostName,
                style = RoomBeatTheme.typography.headingMd,
                color = TextBone
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STATUS: BROADCASTING mDNS & TCP",
                    style = RoomBeatTheme.typography.codeXs,
                    color = SyncGreen
                )

                Text(
                    text = "LATENCY: ±0.0ms (MASTER CLOCK)",
                    style = RoomBeatTheme.typography.codeXs,
                    color = SyncGreen
                )
            }
        }
    }
}

/**
 * Client Status Card showing connection status to host.
 */
@Composable
private fun ClientStatusCard(uiState: RoomLobbyUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, BorderActive)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CLIENT STATUS",
                    style = RoomBeatTheme.typography.labelSm,
                    color = SyncGreen,
                    fontWeight = FontWeight.Bold
                )

                StatusBeacon(
                    status = BeaconStatus.LOCKED,
                    label = "SYNC LOCKED"
                )
            }

            Text(
                text = "CONNECTED TO ${uiState.hostName.uppercase(Locale.US)}",
                style = RoomBeatTheme.typography.headingMd,
                color = TextBone
            )

            Text(
                text = "WAITING FOR HOST TO SELECT SOURCE...",
                style = RoomBeatTheme.typography.codeMd,
                color = SyncAmber,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Placeholder card displayed when no client devices have joined yet.
 */
@Composable
private fun EmptyPeersCard(isHost: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, BorderMilled)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusBeacon(
                status = BeaconStatus.CALIBRATING,
                size = 10.dp
            )

            Text(
                text = if (isHost) {
                    "NO PEERS CONNECTED YET"
                } else {
                    "AWAITING OTHER RIG PEERS"
                },
                style = RoomBeatTheme.typography.headingMd,
                color = TextBone,
                textAlign = TextAlign.Center
            )

            Text(
                text = if (isHost) {
                    "Have friends tap [ JOIN ROOM ] on the same Wi-Fi/Hotspot\nand enter the 6-digit PIN or scan the QR code."
                } else {
                    "Connected to the host sound rig. Waiting for more nodes to join."
                },
                style = RoomBeatTheme.typography.bodyMd,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Host Master Control Strip pinned at the bottom of the screen.
 */
@Composable
private fun HostMasterControlDock(
    uiState: RoomLobbyUiState,
    onMasterVolumeChange: (Float) -> Unit,
    onProceedToSource: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Master Volume Header Row
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
                    text = "MASTER GAIN:",
                    style = RoomBeatTheme.typography.labelSm,
                    color = TextDim
                )
                Text(
                    text = uiState.formattedMasterVolumeDb,
                    style = RoomBeatTheme.typography.codeMd,
                    color = TextBone,
                    fontWeight = FontWeight.Bold
                )
            }

            StatusBeacon(
                status = if (uiState.connectedPeerCount > 0) BeaconStatus.LOCKED else BeaconStatus.IDLE,
                label = "${uiState.connectedPeerCount} PEERS ACTIVE"
            )
        }

        // Master Gain Slider
        Slider(
            value = uiState.masterVolume,
            onValueChange = onMasterVolumeChange,
            valueRange = 0.0f..1.5f,
            colors = SliderDefaults.colors(
                thumbColor = SignalOrange,
                activeTrackColor = SignalOrange,
                inactiveTrackColor = SurfaceRecessed,
                disabledThumbColor = TextDim,
                disabledActiveTrackColor = BorderMilled
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // Proceed to Source Selection Button (Signal Orange)
        TactileKeycapButton(
            onClick = onProceedToSource,
            variant = TactileButtonVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Text("PROCEED TO AUDIO SOURCE  →")
        }
    }
}

/**
 * Client Standby Dock pinned at the bottom of the screen.
 */
@Composable
private fun ClientStandbyDock(uiState: RoomLobbyUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "CLIENT RIG READY",
                style = RoomBeatTheme.typography.labelSm,
                color = SyncGreen,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "WAITING FOR HOST...",
                style = RoomBeatTheme.typography.codeXs,
                color = TextMuted
            )
        }

        StatusBeacon(
            status = BeaconStatus.LOCKED,
            label = "STANDBY"
        )
    }
}

/**
 * High-Contrast QR Code Dialog Modal.
 */
@Composable
private fun QrCodeDialog(
    uiState: RoomLobbyUiState,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfacePanel,
            border = BorderStroke(1.dp, BorderActive),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Modal Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ROOM QR CODE",
                        style = RoomBeatTheme.typography.headingMd,
                        color = TextBone
                    )

                    Text(
                        text = "[ SCAN TO JOIN ]",
                        style = RoomBeatTheme.typography.codeXs,
                        color = SignalOrange
                    )
                }

                // High-Contrast QR Matrix Display
                RecessedPanel(
                    cornerRadius = 6.dp,
                    backgroundColor = Color.White,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    val bitmap = uiState.qrBitmap
                    val matrix = uiState.qrMatrix

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Room QR Code",
                            modifier = Modifier.size(200.dp)
                        )
                    } else if (matrix != null) {
                        // Canvas boolean matrix fallback (JVM / test / headless compatible)
                        Canvas(modifier = Modifier.size(200.dp)) {
                            val rows = matrix.size
                            val cols = matrix[0].size
                            val cellWidth = size.width / cols
                            val cellHeight = size.height / rows

                            drawRect(color = Color.White)
                            for (y in 0 until rows) {
                                for (x in 0 until cols) {
                                    if (matrix[y][x]) {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = Offset(x * cellWidth, y * cellHeight),
                                            size = Size(cellWidth, cellHeight)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "QR CODE MATRIX",
                                style = RoomBeatTheme.typography.codeMd,
                                color = Color.Black
                            )
                        }
                    }
                }

                // Telemetry metadata box
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "ROOM PIN",
                        style = RoomBeatTheme.typography.labelSm,
                        color = TextDim
                    )
                    Text(
                        text = PinGenerator.formatForDisplay(uiState.roomPin),
                        style = RoomBeatTheme.typography.codeXl,
                        color = TextBone,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ENDPOINT: ${uiState.localIp}:${uiState.port}",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextMuted
                    )
                }

                // Close Button
                TactileKeycapButton(
                    text = "[ CLOSE ]",
                    onClick = onDismiss,
                    variant = TactileButtonVariant.SURFACE,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ==========================================
// Previews
// ==========================================

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
fun RoomLobbyScreenHostPreview() {
    RoomBeatTheme {
        val samplePeers = listOf(
            PeerNode(
                id = "peer-1",
                name = "Pixel 8 Pro",
                ip = "192.168.1.105",
                rttMs = 0.8,
                offsetMs = 0.2,
                volume = 1.0f,
                state = PeerConnectionState.CONNECTED
            ),
            PeerNode(
                id = "peer-2",
                name = "Galaxy S24",
                ip = "192.168.1.109",
                rttMs = 1.4,
                offsetMs = -0.4,
                volume = 0.85f,
                state = PeerConnectionState.CONNECTED
            ),
            PeerNode(
                id = "peer-3",
                name = "OnePlus 12",
                ip = "192.168.1.112",
                rttMs = 28.5,
                offsetMs = 1.1,
                volume = 1.0f,
                state = PeerConnectionState.RECONNECTING,
                reconnectAttempt = 1
            )
        )

        RoomLobbyContent(
            uiState = RoomLobbyUiState(
                isHost = true,
                roomPin = "849201",
                hostName = "Main Rig (Host)",
                localIp = "192.168.1.100",
                port = 8080,
                peers = samplePeers,
                masterVolume = 1.0f,
                isConnected = true
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
fun RoomLobbyScreenClientPreview() {
    RoomBeatTheme {
        RoomLobbyContent(
            uiState = RoomLobbyUiState(
                isHost = false,
                roomPin = "849201",
                hostName = "Living Room Rig",
                localIp = "192.168.1.100",
                port = 8080,
                peers = emptyList(),
                masterVolume = 1.0f,
                isConnected = true
            )
        )
    }
}
