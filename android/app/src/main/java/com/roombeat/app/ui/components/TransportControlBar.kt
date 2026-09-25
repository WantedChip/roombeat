package com.roombeat.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roombeat.app.session.TransportState
import com.roombeat.app.session.TransportStatus
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
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted

/**
 * UI Test tags for the [TransportControlBar].
 */
object TransportControlBarTags {
    const val CONTAINER = "transport_control_bar_container"
    const val TRACK_TITLE = "transport_track_title"
    const val TRACK_ARTIST = "transport_track_artist"
    const val STATUS_BADGE = "transport_status_badge"
    const val PROGRESS_SLIDER = "transport_progress_slider"
    const val PROGRESS_BAR = "transport_progress_bar"
    const val TIME_READOUT = "transport_time_readout"
    const val PLAY_PAUSE_BUTTON = "transport_play_pause_button"
    const val STOP_BUTTON = "transport_stop_button"
    const val SEEK_BACKWARD_BUTTON = "transport_seek_backward_button"
    const val SEEK_FORWARD_BUTTON = "transport_seek_forward_button"
    const val CLIENT_SYNC_BADGE = "transport_client_sync_badge"
}

/**
 * Formats a duration in milliseconds to `mm:ss` format.
 */
fun formatDurationMs(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

/**
 * Hardware-grade Transport Control Bar component adhering strictly to the
 * Tactile Acoustic Industrial design system.
 *
 * Displays track metadata, real-time sync status badges, duration readouts,
 * interactive progress scrubber (host mode) or synchronized progress bar (client mode),
 * and tactile keycap transport controls.
 *
 * @param state Current [TransportState] snapshot.
 * @param onPlay Callback triggered when Play is pressed (host only).
 * @param onPause Callback triggered when Pause is pressed (host only).
 * @param onSeek Callback triggered when seeking to a new position in milliseconds (host only).
 * @param onStop Callback triggered when Stop is pressed (host only).
 * @param modifier Optional Compose modifier.
 */
@Composable
fun TransportControlBar(
    state: TransportState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (positionMs: Long) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TransportControlBarTags.CONTAINER),
        color = SurfacePanel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderMilled)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ================================================================
            // Row 1: Track Metadata & Status Badge
            // ================================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = state.trackTitle.ifEmpty { "NO TRACK LOADED" },
                        fontFamily = CabinetGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextBone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TransportControlBarTags.TRACK_TITLE)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = state.artist.ifEmpty { "UNKNOWN ARTIST" },
                        fontFamily = GeneralSansFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TransportControlBarTags.TRACK_ARTIST)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Status Badge
                PlaybackStatusBadge(
                    status = state.status,
                    isHost = state.isHost,
                    modifier = Modifier.testTag(TransportControlBarTags.STATUS_BADGE)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ================================================================
            // Row 2: Progress Scrubber / Seekbar & Time Readouts
            // ================================================================
            val durationMs = state.durationMs.coerceAtLeast(0L)
            val currentPosMs = state.currentPositionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))

            var isUserDragging by remember { mutableStateOf(false) }
            var dragPositionRatio by remember { mutableFloatStateOf(0f) }

            val progressRatio = if (durationMs > 0L) {
                if (isUserDragging) dragPositionRatio else currentPosMs.toFloat() / durationMs.toFloat()
            } else {
                0f
            }.coerceIn(0f, 1f)

            if (state.isHost) {
                // Interactive Scrubber for Session Host
                Slider(
                    value = progressRatio,
                    onValueChange = { newRatio ->
                        isUserDragging = true
                        dragPositionRatio = newRatio
                    },
                    onValueChangeFinished = {
                        isUserDragging = false
                        val targetMs = (dragPositionRatio * durationMs).toLong()
                        onSeek(targetMs)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = SignalOrange,
                        activeTrackColor = SignalOrange,
                        inactiveTrackColor = SurfaceRecessed,
                        disabledThumbColor = TextDim,
                        disabledActiveTrackColor = TextDim,
                        disabledInactiveTrackColor = SurfaceRecessed
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag(TransportControlBarTags.PROGRESS_SLIDER)
                )
            } else {
                // Non-interactive synchronized progress bar for Client / Peer nodes
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceRecessed)
                        .border(1.dp, BorderMilled, RoundedCornerShape(4.dp))
                        .testTag(TransportControlBarTags.PROGRESS_BAR)
                ) {
                    LinearProgressIndicator(
                        progress = { progressRatio },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = SyncGreen,
                        trackColor = Color.Transparent
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Time Readout: mm:ss / mm:ss formatted in JetBrains Mono
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displayPosMs = if (isUserDragging) {
                    (dragPositionRatio * durationMs).toLong()
                } else {
                    currentPosMs
                }

                Text(
                    text = "${formatDurationMs(displayPosMs)} / ${formatDurationMs(durationMs)}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = TextBone,
                    modifier = Modifier.testTag(TransportControlBarTags.TIME_READOUT)
                )

                if (!state.isHost) {
                    Text(
                        text = "HOST SYNCED",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = SyncGreen,
                        letterSpacing = 0.08.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ================================================================
            // Row 3: Tactile Hardware Keycap Transport Controls
            // ================================================================
            if (state.isHost) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play/Pause Keycap Button
                    val isPlaying = state.isPlaying
                    TactileKeycapButton(
                        onClick = {
                            if (isPlaying) {
                                onPause()
                            } else {
                                onPlay()
                            }
                        },
                        variant = TactileButtonVariant.PRIMARY,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag(TransportControlBarTags.PLAY_PAUSE_BUTTON)
                    ) {
                        Text(
                            text = if (isPlaying) "[ PAUSE ]" else "[ PLAY ]",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 0.04.sp
                        )
                    }

                    // Seek -10s
                    TactileKeycapButton(
                        onClick = {
                            val newPos = (state.currentPositionMs - 10_000L).coerceAtLeast(0L)
                            onSeek(newPos)
                        },
                        variant = TactileButtonVariant.SURFACE,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TransportControlBarTags.SEEK_BACKWARD_BUTTON)
                    ) {
                        Text(
                            text = "-10s",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }

                    // Seek +10s
                    TactileKeycapButton(
                        onClick = {
                            val newPos = (state.currentPositionMs + 10_000L).coerceAtMost(durationMs)
                            onSeek(newPos)
                        },
                        variant = TactileButtonVariant.SURFACE,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TransportControlBarTags.SEEK_FORWARD_BUTTON)
                    ) {
                        Text(
                            text = "+10s",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }

                    // Stop Keycap Button
                    TactileKeycapButton(
                        onClick = onStop,
                        variant = TactileButtonVariant.DESTRUCTIVE,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TransportControlBarTags.STOP_BUTTON)
                    ) {
                        Text(
                            text = "STOP",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                // Client / Peer Node Display Banner
                Surface(
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, BorderMilled),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TransportControlBarTags.CLIENT_SYNC_BADGE)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(SyncGreen)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CONTROLLED BY SESSION HOST",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = SyncGreen,
                            letterSpacing = 0.06.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tactical badge displaying the current playback and sync status.
 */
@Composable
fun PlaybackStatusBadge(
    status: TransportStatus,
    isHost: Boolean,
    modifier: Modifier = Modifier
) {
    val (label, accentColor) = when {
        !isHost && (status == TransportStatus.PLAYING || status == TransportStatus.SYNCED_TO_HOST) -> {
            "SYNCED TO HOST" to SyncGreen
        }
        status == TransportStatus.PLAYING -> {
            "PLAYING" to SyncGreen
        }
        status == TransportStatus.PAUSED -> {
            "PAUSED" to SyncAmber
        }
        status == TransportStatus.BUFFERING -> {
            "BUFFERING" to SyncAmber
        }
        status == TransportStatus.STOPPED -> {
            "STOPPED" to TextDim
        }
        else -> {
            "IDLE" to TextDim
        }
    }

    Surface(
        color = SurfaceRecessed,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, BorderMilled),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = accentColor,
                letterSpacing = 0.06.sp
            )
        }
    }
}

// ============================================================================
// Compose Previews
// ============================================================================

@Preview(name = "Host Playing", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewTransportControlBarHostPlaying() {
    RoomBeatTheme {
        TransportControlBar(
            state = TransportState(
                status = TransportStatus.PLAYING,
                currentPositionMs = 84_000L,
                durationMs = 215_000L,
                trackTitle = "Midnight Resonance - NDK Mix",
                artist = "RoomBeat Sound Collective",
                isHost = true
            ),
            onPlay = {},
            onPause = {},
            onSeek = {},
            onStop = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "Host Paused", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewTransportControlBarHostPaused() {
    RoomBeatTheme {
        TransportControlBar(
            state = TransportState(
                status = TransportStatus.PAUSED,
                currentPositionMs = 45_000L,
                durationMs = 180_000L,
                trackTitle = "Modular Drift",
                artist = "Elektron Syntakt",
                isHost = true
            ),
            onPlay = {},
            onPause = {},
            onSeek = {},
            onStop = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "Client Synced", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewTransportControlBarClientSynced() {
    RoomBeatTheme {
        TransportControlBar(
            state = TransportState(
                status = TransportStatus.SYNCED_TO_HOST,
                currentPositionMs = 112_000L,
                durationMs = 240_000L,
                trackTitle = "Midnight Resonance - NDK Mix",
                artist = "RoomBeat Sound Collective",
                isHost = false
            ),
            onPlay = {},
            onPause = {},
            onSeek = {},
            onStop = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
