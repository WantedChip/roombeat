package com.roombeat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextMuted

/**
 * Operating mode for [CaptureConsentDialog].
 */
enum class CaptureConsentDialogMode {
    /**
     * Pre-capture explanation mode before opening the Android system MediaProjection dialog.
     * Explains the API 34+ requirement that consent is per-session and non-reusable.
     */
    EXPLANATION,

    /**
     * Recovery mode shown when the user cancels or denies the system consent dialog.
     * Provides clear context on why capture failed and actionable recovery options (retry or cancel).
     */
    REJECTED
}

/**
 * State holder for managing [CaptureConsentDialog] visibility and active operating mode.
 */
class CaptureConsentDialogState(
    initialOpen: Boolean = false,
    initialMode: CaptureConsentDialogMode = CaptureConsentDialogMode.EXPLANATION
) {
    var isOpen by mutableStateOf(initialOpen)
    var mode by mutableStateOf(initialMode)

    fun showExplanation() {
        mode = CaptureConsentDialogMode.EXPLANATION
        isOpen = true
    }

    fun showRejected() {
        mode = CaptureConsentDialogMode.REJECTED
        isOpen = true
    }

    fun dismiss() {
        isOpen = false
    }
}

/**
 * Creates and remembers a [CaptureConsentDialogState] instance across recompositions.
 */
@Composable
fun rememberCaptureConsentDialogState(
    initialOpen: Boolean = false,
    initialMode: CaptureConsentDialogMode = CaptureConsentDialogMode.EXPLANATION
): CaptureConsentDialogState {
    return remember { CaptureConsentDialogState(initialOpen, initialMode) }
}

/**
 * Tactile Acoustic Industrial modal dialog explaining Android's MediaProjection consent requirements.
 *
 * Requirements:
 * - Conforms strictly to the "Tactile Acoustic Industrial" design system (#0B0C0E chassis, #13151A surface,
 *   #262A35 milled borders, #FF5500 Signal Orange triggers, #FFB800 Amber / #FF334B Red beacons).
 * - Informs the user that Android 14+ (API 34+) requires confirming the capture consent dialog
 *   for each new streaming session (non-reusable tokens).
 * - Provides graceful rejection handling with clear actionable recovery options.
 */
@Composable
fun CaptureConsentDialog(
    isOpen: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    mode: CaptureConsentDialogMode = CaptureConsentDialogMode.EXPLANATION,
    onSwitchToLocalPlayback: (() -> Unit)? = null
) {
    if (!isOpen) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfacePanel,
            border = BorderStroke(1.dp, if (mode == CaptureConsentDialogMode.REJECTED) SyncRed else BorderActive),
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("capture_consent_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: Modal Title & Diagnostic Beacon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (mode == CaptureConsentDialogMode.EXPLANATION) {
                            "SYSTEM AUDIO CAPTURE"
                        } else {
                            "AUTHORIZATION DENIED"
                        },
                        style = RoomBeatTheme.typography.headingMd,
                        color = TextBone,
                        modifier = Modifier.testTag("capture_consent_title")
                    )

                    StatusBeacon(
                        status = if (mode == CaptureConsentDialogMode.EXPLANATION) BeaconStatus.WARNING else BeaconStatus.ERROR,
                        label = if (mode == CaptureConsentDialogMode.EXPLANATION) "API 34+" else "REJECTED"
                    )
                }

                // Milled Seam Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(BorderMilled)
                )

                // Body Description & Technical Inset
                if (mode == CaptureConsentDialogMode.EXPLANATION) {
                    Text(
                        text = "RoomBeat captures internal playback audio from streaming apps (such as YouTube, VLC, or SoundCloud) and broadcasts it synchronously across all participating phones via UDP multicast.",
                        style = RoomBeatTheme.typography.bodyMd,
                        color = TextMuted,
                        modifier = Modifier.testTag("capture_consent_explanation_text")
                    )

                    // Technical Recessed Panel: Android 14+ Lifecycle
                    RecessedPanel(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.testTag("capture_consent_technical_note")
                        ) {
                            Text(
                                text = "[ ANDROID 14+ SECURITY LIFECYCLE ]",
                                style = RoomBeatTheme.typography.codeXs,
                                color = SyncAmber,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Under Android 14+ security policies, system capture tokens are single-use and cannot be cached. Android requires confirming the capture consent dialog for each new streaming session.",
                                style = RoomBeatTheme.typography.codeXs,
                                color = TextMuted
                            )
                            Text(
                                text = "PRIVACY SAFEGUARD: Only audio playback is captured. Screen display, camera, microphone, and personal notifications are NEVER recorded or transmitted.",
                                style = RoomBeatTheme.typography.codeXs,
                                color = SyncGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // REJECTED mode
                    Text(
                        text = "The Android system capture prompt was dismissed or denied. RoomBeat cannot ingest or broadcast internal application audio without this system authorization.",
                        style = RoomBeatTheme.typography.bodyMd,
                        color = TextMuted,
                        modifier = Modifier.testTag("capture_consent_explanation_text")
                    )

                    RecessedPanel(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.testTag("capture_consent_technical_note")
                        ) {
                            Text(
                                text = "[ RECOVERY OPTIONS ]",
                                style = RoomBeatTheme.typography.codeXs,
                                color = SyncRed,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "1. Tap 'RETRY AUTHORIZATION' to reopen Android's capture dialog and select 'Start now'.",
                                style = RoomBeatTheme.typography.codeXs,
                                color = TextBone
                            )
                            Text(
                                text = "2. Alternatively, switch to local audio storage (Module 01) if you prefer streaming offline music files.",
                                style = RoomBeatTheme.typography.codeXs,
                                color = TextMuted
                            )
                        }
                    }
                }

                // Action Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TactileKeycapButton(
                        text = if (mode == CaptureConsentDialogMode.EXPLANATION) {
                            "[ PROCEED TO SYSTEM PROMPT ]"
                        } else {
                            "[ RETRY AUTHORIZATION ]"
                        },
                        onClick = onConfirm,
                        variant = TactileButtonVariant.PRIMARY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("capture_consent_confirm_button")
                    )

                    if (mode == CaptureConsentDialogMode.REJECTED && onSwitchToLocalPlayback != null) {
                        TactileKeycapButton(
                            text = "[ USE LOCAL STORAGE (MODULE 01) ]",
                            onClick = onSwitchToLocalPlayback,
                            variant = TactileButtonVariant.SURFACE,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("capture_consent_switch_storage_button")
                        )
                    }

                    TactileKeycapButton(
                        text = "[ CANCEL ]",
                        onClick = onDismiss,
                        variant = TactileButtonVariant.SURFACE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("capture_consent_cancel_button")
                    )
                }
            }
        }
    }
}

// ==========================================
// Previews
// ==========================================

@Preview(name = "Capture Consent Dialog - Explanation", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
fun CaptureConsentDialogExplanationPreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CaptureConsentDialog(
                isOpen = true,
                mode = CaptureConsentDialogMode.EXPLANATION,
                onConfirm = {},
                onDismiss = {}
            )
        }
    }
}

@Preview(name = "Capture Consent Dialog - Rejected", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
fun CaptureConsentDialogRejectedPreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CaptureConsentDialog(
                isOpen = true,
                mode = CaptureConsentDialogMode.REJECTED,
                onConfirm = {},
                onDismiss = {},
                onSwitchToLocalPlayback = {}
            )
        }
    }
}
