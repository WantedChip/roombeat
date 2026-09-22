package com.roombeat.app.ui.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted

/**
 * Action string for opening Android Tethering and Portable Hotspot settings.
 * Note: While referenced conceptually as `Settings.ACTION_TETHER_SETTINGS`, the underlying
 * Android platform intent action string is `"android.settings.TETHER_SETTINGS"`.
 */
const val ACTION_TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"

/**
 * Safely launches Android Tethering & Portable Hotspot settings with fallback intents.
 */
fun launchHotspotSettings(context: Context) {
    val intents = listOf(
        Intent(ACTION_TETHER_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Continue to fallback intent
        }
    }
}

/**
 * Actionable Compose guidance card adhering to the "Tactile Acoustic Industrial" design system.
 * Displayed when local router AP isolation or IGMP snooping drops UDP multicast sync packets.
 *
 * Requirements:
 * - Machined obsidian (#13151A), milled border (#262A35), amber warning accent (#FFB800),
 *   signal orange button (#FF5500), General Sans / JetBrains Mono typography.
 * - Displays clear copy: "Local Wi-Fi router is blocking direct sync — Please turn on Host Portable Hotspot for instant connection".
 * - Keycap button to open Android system tethering settings (Settings.ACTION_TETHER_SETTINGS).
 * - Includes Compose @Preview and test tags for Compose testing.
 */
@Composable
fun HotspotFallbackBanner(
    modifier: Modifier = Modifier,
    onOpenHotspotSettings: (() -> Unit)? = null,
    onShowDetails: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, BorderMilled),
        modifier = modifier
            .fillMaxWidth()
            .testTag("hotspot_fallback_banner")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Warning Beacon Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(SyncAmber, RoundedCornerShape(2.dp))
                    )
                    Text(
                        text = "ROUTER AP ISOLATION DETECTED",
                        style = RoomBeatTheme.typography.labelSm,
                        color = SyncAmber,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "[ MULTICAST BLOCKED ]",
                    style = RoomBeatTheme.typography.codeXs,
                    color = TextDim
                )
            }

            // Primary Copy
            Text(
                text = "Local Wi-Fi router is blocking direct sync — Please turn on Host Portable Hotspot for instant connection",
                style = RoomBeatTheme.typography.bodyMd,
                color = TextBone
            )

            // Recessed Technical Readout
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = SurfaceRecessed,
                border = BorderStroke(1.dp, BorderMilled),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STATUS: MULTICAST_BLOCKED · RX < 80%",
                        style = RoomBeatTheme.typography.codeXs,
                        color = SyncAmber
                    )
                    Text(
                        text = "AIRTIME: CONGESTED",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextMuted
                    )
                }
            }

            // Action Trigger Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TactileKeycapButton(
                    text = "ENABLE HOTSPOT",
                    onClick = {
                        if (onOpenHotspotSettings != null) {
                            onOpenHotspotSettings()
                        } else {
                            launchHotspotSettings(context)
                        }
                    },
                    variant = TactileButtonVariant.PRIMARY,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("hotspot_settings_button")
                )

                if (onShowDetails != null) {
                    TactileKeycapButton(
                        text = "[ WHY HOTSPOT? ]",
                        onClick = onShowDetails,
                        variant = TactileButtonVariant.SURFACE,
                        modifier = Modifier.testTag("hotspot_details_button")
                    )
                }

                if (onDismiss != null) {
                    TactileKeycapButton(
                        text = "[ DISMISS ]",
                        onClick = onDismiss,
                        variant = TactileButtonVariant.SURFACE,
                        modifier = Modifier.testTag("hotspot_dismiss_button")
                    )
                }
            }
        }
    }
}

// ==========================================
// Previews
// ==========================================

@Preview(name = "Hotspot Fallback Banner - Dark", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
fun HotspotFallbackBannerPreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HotspotFallbackBanner(
                onOpenHotspotSettings = {},
                onShowDetails = {},
                onDismiss = {}
            )
        }
    }
}
