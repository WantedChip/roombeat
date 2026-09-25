package com.roombeat.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.roombeat.app.source.spotify.SpotifyAuthErrorType
import com.roombeat.app.source.spotify.SpotifyAuthState
import com.roombeat.app.source.spotify.SpotifyConstants
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SignalOrange
import com.roombeat.app.ui.theme.SurfaceElevated
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted

/**
 * State holder for managing [SpotifyGuidanceDialog] visibility and active error state.
 */
class SpotifyGuidanceDialogState(
    initialVisible: Boolean = false,
    initialError: SpotifyAuthState.Error? = null
) {
    var isVisible by mutableStateOf(initialVisible)
    var currentError by mutableStateOf(initialError)

    fun showError(error: SpotifyAuthState.Error) {
        currentError = error
        isVisible = true
    }

    fun dismiss() {
        isVisible = false
    }
}

@Composable
fun rememberSpotifyGuidanceDialogState(
    initialVisible: Boolean = false,
    initialError: SpotifyAuthState.Error? = null
): SpotifyGuidanceDialogState {
    return remember { SpotifyGuidanceDialogState(initialVisible, initialError) }
}

/**
 * Tactical hardware guidance dialog displayed when Spotify is missing or authorization fails
 * (Roadmap §8 / Flow 5 / Sub-phase v0.7.0).
 *
 * Adheres strictly to the "Tactile Acoustic Industrial" design system:
 * - Chassis surface panel: #13151A
 * - Precision milled border: #262A35 / Focused active border: #3E4454
 * - Signal Orange action triggers (#FF5500) and Amber warning beacons (#FFB800)
 * - Typographic hierarchy: Cabinet Grotesk, General Sans, JetBrains Mono
 */
@Composable
fun SpotifyGuidanceDialog(
    error: SpotifyAuthState.Error,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onSelectSystemCapture: (() -> Unit)? = null,
    onInstallViaPlayStore: (() -> Unit)? = null,
    onOpenSpotifyApp: (() -> Unit)? = null
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
                .testTag("spotify_guidance_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Hardware Header Strip: Monogram + Title + Beacon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "[ SPOTIFY REMOTE · MODULE 03 ]",
                            style = RoomBeatTheme.typography.codeXs,
                            color = TextDim
                        )
                        Text(
                            text = getDialogTitle(error.errorType),
                            style = RoomBeatTheme.typography.headingMd,
                            color = TextBone,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("spotify_guidance_title")
                        )
                    }

                    StatusBeacon(
                        status = when (error.errorType) {
                            SpotifyAuthErrorType.APP_NOT_INSTALLED -> BeaconStatus.WARNING
                            SpotifyAuthErrorType.NOT_LOGGED_IN -> BeaconStatus.WARNING
                            else -> BeaconStatus.ERROR
                        },
                        label = when (error.errorType) {
                            SpotifyAuthErrorType.APP_NOT_INSTALLED -> "APP MISSING"
                            SpotifyAuthErrorType.NOT_LOGGED_IN -> "NOT LOGGED IN"
                            SpotifyAuthErrorType.USER_NOT_AUTHORIZED -> "AUTH DENIED"
                            SpotifyAuthErrorType.AUTHENTICATION_FAILED -> "AUTH FAILED"
                            SpotifyAuthErrorType.TIMEOUT -> "TIMEOUT"
                            else -> "FAULT"
                        }
                    )
                }

                // 2. Milled Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(BorderMilled)
                )

                // 3. Recessed Error Diagnostic Telemetry Panel
                RecessedPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ERROR CODE: ${error.errorType.name}",
                                style = RoomBeatTheme.typography.codeXs,
                                color = if (error.isAppMissing || error.isNotLoggedIn) SyncAmber else SyncRed,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "IPC: DISCONNECTED",
                                style = RoomBeatTheme.typography.codeXs,
                                color = TextDim
                            )
                        }

                        Text(
                            text = error.message,
                            style = RoomBeatTheme.typography.bodyMd,
                            color = TextBone
                        )

                        Text(
                            text = getGuidanceDescription(error.errorType),
                            style = RoomBeatTheme.typography.labelSm,
                            color = TextMuted
                        )
                    }
                }

                // 4. Action Keycaps
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (error.errorType) {
                        SpotifyAuthErrorType.APP_NOT_INSTALLED -> {
                            // Primary: Install Spotify via Play Store
                            TactileKeycapButton(
                                onClick = {
                                    if (onInstallViaPlayStore != null) {
                                        onInstallViaPlayStore()
                                    } else {
                                        launchSpotifyPlayStore(context)
                                    }
                                },
                                variant = TactileButtonVariant.PRIMARY,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("spotify_guidance_install_button")
                            ) {
                                Text(text = "[ INSTALL SPOTIFY APP ]")
                            }

                            // Fallback: Switch to System App Capture
                            if (onSelectSystemCapture != null) {
                                TactileKeycapButton(
                                    onClick = onSelectSystemCapture,
                                    variant = TactileButtonVariant.SURFACE,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_guidance_switch_capture_button")
                                ) {
                                    Text(text = "SWITCH TO SYSTEM CAPTURE (MODULE 02)")
                                }
                            }
                        }

                        SpotifyAuthErrorType.NOT_LOGGED_IN -> {
                            // Primary: Open Spotify App to Log In
                            TactileKeycapButton(
                                onClick = {
                                    if (onOpenSpotifyApp != null) {
                                        onOpenSpotifyApp()
                                    } else {
                                        launchSpotifyApp(context)
                                    }
                                },
                                variant = TactileButtonVariant.PRIMARY,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("spotify_guidance_open_app_button")
                            ) {
                                Text(text = "[ OPEN SPOTIFY APP ]")
                            }

                            // Secondary: Retry Connection
                            if (onRetry != null) {
                                TactileKeycapButton(
                                    onClick = onRetry,
                                    variant = TactileButtonVariant.SURFACE,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_guidance_retry_button")
                                ) {
                                    Text(text = "RETRY IPC CONNECTION")
                                }
                            }
                        }

                        else -> {
                            // Primary: Retry Authorization / IPC Handshake
                            if (onRetry != null) {
                                TactileKeycapButton(
                                    onClick = onRetry,
                                    variant = TactileButtonVariant.PRIMARY,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_guidance_retry_button")
                                ) {
                                    Text(text = "[ RETRY SPOTIFY CONNECTION ]")
                                }
                            }
                        }
                    }

                    // Dismiss Button
                    TactileKeycapButton(
                        onClick = onDismiss,
                        variant = TactileButtonVariant.SURFACE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spotify_guidance_dismiss_button")
                    ) {
                        Text(text = "DISMISS")
                    }
                }
            }
        }
    }
}

private fun getDialogTitle(errorType: SpotifyAuthErrorType): String {
    return when (errorType) {
        SpotifyAuthErrorType.APP_NOT_INSTALLED -> "SPOTIFY APP REQUIRED"
        SpotifyAuthErrorType.NOT_LOGGED_IN -> "SPOTIFY LOGIN REQUIRED"
        SpotifyAuthErrorType.USER_NOT_AUTHORIZED -> "SPOTIFY AUTH REJECTED"
        SpotifyAuthErrorType.AUTHENTICATION_FAILED -> "AUTH HANDSHAKE FAILED"
        SpotifyAuthErrorType.OFFLINE_MODE -> "SPOTIFY IS OFFLINE"
        SpotifyAuthErrorType.TIMEOUT -> "CONNECTION TIMED OUT"
        else -> "SPOTIFY CONNECTION FAILED"
    }
}

private fun getGuidanceDescription(errorType: SpotifyAuthErrorType): String {
    return when (errorType) {
        SpotifyAuthErrorType.APP_NOT_INSTALLED ->
            "Spotify App Remote requires the official Spotify client installed on this device. Install from Google Play or capture external audio using System App Capture (Module 02)."
        SpotifyAuthErrorType.NOT_LOGGED_IN ->
            "Spotify is installed, but no active session was detected. Launch Spotify and log into your account before connecting to RoomBeat."
        SpotifyAuthErrorType.USER_NOT_AUTHORIZED ->
            "RoomBeat requires authorization to control Spotify playback across room devices. Please accept the authorization prompt when prompted."
        SpotifyAuthErrorType.AUTHENTICATION_FAILED ->
            "Authentication failed between RoomBeat and the Spotify service. Verify your network connection and retry."
        SpotifyAuthErrorType.OFFLINE_MODE ->
            "The local Spotify app is currently set to Offline Mode. Please disable Offline Mode in Spotify settings to sync playback."
        SpotifyAuthErrorType.TIMEOUT ->
            "The IPC handshake timed out while waiting for a response from the Spotify service. Ensure Spotify is not frozen or battery-restricted."
        else ->
            "Could not connect to Spotify App Remote service. Ensure Spotify is running in the background and retry."
    }
}

/**
 * Deep-link intent launcher to open Spotify on Google Play Store.
 */
fun launchSpotifyPlayStore(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SpotifyConstants.PLAY_STORE_URI)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SpotifyConstants.PLAY_STORE_WEB_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(webIntent)
        } catch (_: Exception) {}
    }
}

/**
 * Launches the installed Spotify app directly.
 */
fun launchSpotifyApp(context: Context) {
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SpotifyConstants.SPOTIFY_PACKAGE_NAME)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            launchSpotifyPlayStore(context)
        }
    } catch (_: Exception) {
        launchSpotifyPlayStore(context)
    }
}

// ---------------- Previews ----------------

@Preview(name = "Spotify Guidance Dialog - App Missing", showBackground = true)
@Composable
fun SpotifyGuidanceDialogAppMissingPreview() {
    RoomBeatTheme {
        SpotifyGuidanceDialog(
            error = SpotifyAuthState.Error(
                errorType = SpotifyAuthErrorType.APP_NOT_INSTALLED,
                message = "Could not find Spotify application on device."
            ),
            onDismiss = {},
            onRetry = {},
            onSelectSystemCapture = {}
        )
    }
}

@Preview(name = "Spotify Guidance Dialog - Auth Denied", showBackground = true)
@Composable
fun SpotifyGuidanceDialogAuthDeniedPreview() {
    RoomBeatTheme {
        SpotifyGuidanceDialog(
            error = SpotifyAuthState.Error(
                errorType = SpotifyAuthErrorType.USER_NOT_AUTHORIZED,
                message = "User cancelled or denied authorization request."
            ),
            onDismiss = {},
            onRetry = {}
        )
    }
}
