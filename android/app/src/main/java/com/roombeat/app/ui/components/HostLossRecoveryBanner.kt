package com.roombeat.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.CabinetGroteskFontFamily
import com.roombeat.app.ui.theme.GeneralSansFontFamily
import com.roombeat.app.ui.theme.JetBrainsMonoFontFamily
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextMuted

/**
 * UI Test tags for [HostLossRecoveryBanner].
 */
object HostLossRecoveryBannerTags {
    const val BANNER = "host_loss_recovery_banner"
    const val TITLE = "host_loss_recovery_banner_title"
    const val STATUS = "host_loss_recovery_banner_status"
    const val ATTEMPT_COUNT = "host_loss_recovery_banner_attempt_count"
    const val RETRY_BUTTON = "host_loss_recovery_banner_retry_button"
    const val RETURN_BUTTON = "host_loss_recovery_banner_return_button"
}

/**
 * High-visibility recovery banner displayed when a peer detects sudden host severance.
 *
 * Implements sub-phase v0.8.3:
 * - Alerts user: "Host connection lost — Attempt Reconnect or Return to Lobby".
 * - Shows connection attempt counter (`[ ATTEMPT 1 / 3 ]`).
 * - Informs that audio was cleanly faded out (50ms) without speaker clicks.
 * - Provides immediate tactile recovery triggers: [ RETRY CONNECTION ] and [ RETURN TO LOBBY ].
 *
 * @param attemptCount Current reconnection attempt number.
 * @param maxAttempts Maximum allowed reconnection attempts before permanent failure.
 * @param isReconnecting True if an active socket reconnection handshake is underway.
 * @param errorMessage Optional failure reason description.
 * @param onRetry Callback triggered when tapping retry.
 * @param onReturnToLobby Callback triggered when returning to lobby / mode select.
 */
@Composable
fun HostLossRecoveryBanner(
    attemptCount: Int = 1,
    maxAttempts: Int = 3,
    isReconnecting: Boolean = false,
    errorMessage: String? = null,
    onRetry: () -> Unit,
    onReturnToLobby: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "HostLossBeaconTransition")
    val beaconAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BeaconPulseAlpha"
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, SyncAmber),
        modifier = modifier
            .fillMaxWidth()
            .testTag(HostLossRecoveryBannerTags.BANNER)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Beacon, Title, and Attempt Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pulsing Amber Warning Beacon
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(if (isReconnecting) beaconAlpha else 1.0f)
                        .background(SyncAmber, CircleShape)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Title
                Text(
                    text = "HOST CONNECTION LOST",
                    fontFamily = CabinetGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextBone,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(HostLossRecoveryBannerTags.TITLE)
                )

                // Attempt Badge
                Box(
                    modifier = Modifier
                        .background(SurfaceRecessed, RoundedCornerShape(4.dp))
                        .border(BorderStroke(1.dp, BorderMilled), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag(HostLossRecoveryBannerTags.ATTEMPT_COUNT)
                ) {
                    Text(
                        text = if (isReconnecting) {
                            "RECONNECTING..."
                        } else {
                            "ATTEMPT $attemptCount / $maxAttempts"
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = SyncAmber,
                        letterSpacing = 0.04.sp
                    )
                }
            }

            // Description / Status
            Text(
                text = errorMessage
                    ?: "Host connection lost — Attempt Reconnect or Return to Lobby. Audio faded out cleanly (50ms) to prevent clicks/pops.",
                fontFamily = GeneralSansFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 18.sp,
                modifier = Modifier.testTag(HostLossRecoveryBannerTags.STATUS)
            )

            // Tactile Action Triggers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Return to Lobby Button
                TactileKeycapButton(
                    onClick = onReturnToLobby,
                    variant = TactileButtonVariant.SURFACE,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(HostLossRecoveryBannerTags.RETURN_BUTTON)
                ) {
                    Text(
                        text = "[ RETURN TO LOBBY ]",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.04.sp
                    )
                }

                // Retry Connection Button
                TactileKeycapButton(
                    onClick = onRetry,
                    enabled = !isReconnecting,
                    variant = TactileButtonVariant.PRIMARY,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(HostLossRecoveryBannerTags.RETRY_BUTTON)
                ) {
                    Text(
                        text = if (isReconnecting) "[ RECONNECTING... ]" else "[ RETRY CONNECTION ]",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.04.sp
                    )
                }
            }
        }
    }
}

@Preview(name = "Host Loss Recovery Banner Normal", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewHostLossRecoveryBannerNormal() {
    RoomBeatTheme {
        HostLossRecoveryBanner(
            attemptCount = 1,
            maxAttempts = 3,
            isReconnecting = false,
            onRetry = {},
            onReturnToLobby = {}
        )
    }
}

@Preview(name = "Host Loss Recovery Banner Reconnecting", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewHostLossRecoveryBannerReconnecting() {
    RoomBeatTheme {
        HostLossRecoveryBanner(
            attemptCount = 2,
            maxAttempts = 3,
            isReconnecting = true,
            onRetry = {},
            onReturnToLobby = {}
        )
    }
}
