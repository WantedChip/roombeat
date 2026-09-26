package com.roombeat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.CabinetGroteskFontFamily
import com.roombeat.app.ui.theme.GeneralSansFontFamily
import com.roombeat.app.ui.theme.JetBrainsMonoFontFamily
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextMuted

/**
 * UI Test tags for [DisconnectConfirmationDialog].
 */
object DisconnectConfirmationDialogTags {
    const val DIALOG = "disconnect_confirmation_dialog"
    const val TITLE = "disconnect_confirmation_dialog_title"
    const val MESSAGE = "disconnect_confirmation_dialog_message"
    const val NODE_COUNT = "disconnect_confirmation_dialog_node_count"
    const val CONFIRM_BUTTON = "disconnect_confirmation_dialog_confirm_button"
    const val CANCEL_BUTTON = "disconnect_confirmation_dialog_cancel_button"
}

/**
 * High-contrast hardware confirmation modal for session teardown and peer departure.
 *
 * Adheres strictly to the Tactile Acoustic Industrial design system:
 * - Module Surface `#13151A`, milled border `#262A35` / `#3E4454`.
 * - Destruct trigger: `#FF334B` ([SyncRed]).
 * - Typography: [CabinetGroteskFontFamily] for headers, [GeneralSansFontFamily] for warning copy,
 *   and [JetBrainsMonoFontFamily] for keycaps and node counts.
 *
 * @param isHost Whether the local device is the session host (displays "Disconnect All Nodes?")
 *               or a peer (displays "Leave Session?").
 * @param nodeCount Total number of participating devices in the session.
 * @param onConfirm Callback executed when teardown is confirmed.
 * @param onDismiss Callback executed when teardown is cancelled.
 */
@Composable
fun DisconnectConfirmationDialog(
    isHost: Boolean,
    nodeCount: Int = 1,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dialogTag: String = DisconnectConfirmationDialogTags.DIALOG,
    confirmTag: String = DisconnectConfirmationDialogTags.CONFIRM_BUTTON,
    cancelTag: String = DisconnectConfirmationDialogTags.CANCEL_BUTTON
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfacePanel,
            border = BorderStroke(1.dp, BorderActive),
            modifier = modifier
                .fillMaxWidth()
                .testTag(dialogTag)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Title
                Text(
                    text = if (isHost) "DISCONNECT ALL NODES?" else "LEAVE SESSION?",
                    fontFamily = CabinetGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = TextBone,
                    modifier = Modifier.testTag(DisconnectConfirmationDialogTags.TITLE)
                )

                // Host node count readout badge
                if (isHost && nodeCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceRecessed, RoundedCornerShape(4.dp))
                            .border(BorderStroke(1.dp, BorderMilled), RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag(DisconnectConfirmationDialogTags.NODE_COUNT)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(if (nodeCount > 1) SyncGreen else SyncAmber, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$nodeCount ACTIVE NODE${if (nodeCount == 1) "" else "S"} IN SESSION",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextBone,
                                letterSpacing = 0.05.sp
                            )
                        }
                    }
                }

                // Warning Details Body
                Text(
                    text = if (isHost) {
                        "This will immediately terminate playback, stop audio streaming, and disconnect all participating phone nodes from the room."
                    } else {
                        "This will disconnect your device from the room, stop local audio playback, and return to mode selection."
                    },
                    fontFamily = GeneralSansFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = TextMuted,
                    lineHeight = 20.sp,
                    modifier = Modifier.testTag(DisconnectConfirmationDialogTags.MESSAGE)
                )

                // Tactile Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Cancel Button
                    TactileKeycapButton(
                        onClick = onDismiss,
                        variant = TactileButtonVariant.SURFACE,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(cancelTag)
                    ) {
                        Text(
                            text = "[ CANCEL ]",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 0.04.sp
                        )
                    }

                    // Confirm Disconnect Button (Destructive red trigger)
                    TactileKeycapButton(
                        onClick = onConfirm,
                        variant = TactileButtonVariant.DESTRUCTIVE,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(confirmTag)
                    ) {
                        Text(
                            text = if (isHost) "[ DISCONNECT ALL ]" else "[ LEAVE SESSION ]",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 0.04.sp
                        )
                    }
                }
            }
        }
    }
}


@Preview(name = "Disconnect Dialog Host Preview", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewDisconnectConfirmationDialogHost() {
    RoomBeatTheme {
        DisconnectConfirmationDialog(
            isHost = true,
            nodeCount = 4,
            onConfirm = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "Disconnect Dialog Peer Preview", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewDisconnectConfirmationDialogPeer() {
    RoomBeatTheme {
        DisconnectConfirmationDialog(
            isHost = false,
            nodeCount = 1,
            onConfirm = {},
            onDismiss = {}
        )
    }
}
