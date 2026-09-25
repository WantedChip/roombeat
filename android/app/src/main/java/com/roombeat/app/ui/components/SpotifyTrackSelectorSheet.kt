package com.roombeat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.roombeat.app.source.spotify.SAMPLE_SPOTIFY_TRACKS
import com.roombeat.app.source.spotify.SpotifyTrackItem
import com.roombeat.app.source.spotify.SpotifyUriParser
import com.roombeat.app.source.spotify.SpotifyUriResult
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.CabinetGroteskFontFamily
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.GeneralSansFontFamily
import com.roombeat.app.ui.theme.JetBrainsMonoFontFamily
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

/**
 * State holder for managing [SpotifyTrackSelectorSheet] visibility, URI input, validation, and sample tracks.
 */
class SpotifyTrackSelectorSheetState(
    initialOpen: Boolean = false,
    initialUri: String = ""
) {
    var isOpen by mutableStateOf(initialOpen)
    var inputUri by mutableStateOf(initialUri)
    var isPremiumWarningVisible by mutableStateOf(false)
    var premiumWarningMessage by mutableStateOf<String?>(null)
    var recentTracks by mutableStateOf(SAMPLE_SPOTIFY_TRACKS)

    val parsedResult: SpotifyUriResult
        get() = SpotifyUriParser.parse(inputUri)

    val isValid: Boolean
        get() = parsedResult is SpotifyUriResult.Success

    val canonicalUri: String?
        get() = (parsedResult as? SpotifyUriResult.Success)?.canonicalUri

    fun open() {
        isOpen = true
    }

    fun dismiss() {
        isOpen = false
    }

    fun setInput(text: String) {
        inputUri = text
    }

    fun clearInput() {
        inputUri = ""
    }

    fun selectTrack(track: SpotifyTrackItem) {
        inputUri = track.uri
    }

    fun showPremiumWarning(message: String? = null) {
        premiumWarningMessage = message ?: "Spotify Premium is required for on-demand track selection and multi-device synchronization."
        isPremiumWarningVisible = true
    }

    fun dismissPremiumWarning() {
        isPremiumWarningVisible = false
        premiumWarningMessage = null
    }
}

/**
 * Creates and remembers a [SpotifyTrackSelectorSheetState] instance.
 */
@Composable
fun rememberSpotifyTrackSelectorSheetState(
    initialOpen: Boolean = false,
    initialUri: String = ""
): SpotifyTrackSelectorSheetState {
    return remember { SpotifyTrackSelectorSheetState(initialOpen, initialUri) }
}

/**
 * Tactile Acoustic Industrial bottom sheet allowing the host to enter a Spotify URI/URL,
 * validate formats, browse curated sample tracks, and dispatch synchronized play (Sub-phase v0.7.1).
 *
 * Adheres strictly to the RoomBeat design system:
 * - Deep obsidian chassis (#0B0C0E) and module surface panel (#13151A)
 * - Precision milled border (#262A35) and active border (#3E4454)
 * - High-voltage Signal Orange action triggers (#FF5500) with sharp 4px/6px corners (no generic pills)
 * - Typographic hierarchy: Cabinet Grotesk, General Sans, JetBrains Mono
 * - Phosphor Green (#00E599) sync/valid indicators and Amber (#FFB800) Non-Premium warning banner
 */
@Composable
fun SpotifyTrackSelectorSheet(
    isOpen: Boolean,
    inputUri: String,
    onInputChanged: (String) -> Unit,
    onPlayTriggered: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isSpotifyConnected: Boolean = true,
    isPremiumWarningVisible: Boolean = false,
    premiumWarningMessage: String? = null,
    onDismissPremiumWarning: (() -> Unit)? = null,
    onSwitchToSystemCapture: (() -> Unit)? = null,
    sampleTracks: List<SpotifyTrackItem> = SAMPLE_SPOTIFY_TRACKS
) {
    if (!isOpen) return

    val parsedResult = remember(inputUri) { SpotifyUriParser.parse(inputUri) }
    val isValid = parsedResult is SpotifyUriResult.Success
    val canonicalUri = (parsedResult as? SpotifyUriResult.Success)?.canonicalUri

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // Scrim background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ChassisBase.copy(alpha = 0.85f))
                .clickable { onDismiss() }
                .testTag("spotify_track_selector_scrim"),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Sheet Panel Container
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {} // Prevent click-through
                    .testTag("spotify_track_selector_sheet"),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = ChassisBase,
                border = BorderStroke(1.dp, BorderMilled)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Drag Handle / Milled Seam
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 44.dp, height = 4.dp)
                            .background(BorderMilled, RoundedCornerShape(2.dp))
                    )

                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatusBeacon(
                                status = if (isSpotifyConnected) BeaconStatus.LOCKED else BeaconStatus.ERROR
                            )
                            Column {
                                Text(
                                    text = "SPOTIFY CONTENT DISPATCHER",
                                    color = TextBone,
                                    fontFamily = CabinetGroteskFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.015).sp
                                )
                                Text(
                                    text = "MODULE 03 · SYNCHRONIZED TRACK INGEST",
                                    color = TextDim,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.02.sp
                                )
                            }
                        }

                        // Close Keycap
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SurfacePanel,
                            border = BorderStroke(1.dp, BorderMilled),
                            modifier = Modifier
                                .clickable { onDismiss() }
                                .testTag("spotify_sheet_close_button")
                        ) {
                            Text(
                                text = "✕",
                                color = TextBone,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Non-Premium Warning Card (if detected)
                    if (isPremiumWarningVisible) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SurfaceRecessed,
                            border = BorderStroke(1.dp, SyncAmber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spotify_premium_warning_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    StatusBeacon(status = BeaconStatus.WARNING)
                                    Text(
                                        text = "SPOTIFY PREMIUM RESTRICTION",
                                        color = SyncAmber,
                                        fontFamily = CabinetGroteskFontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = premiumWarningMessage
                                        ?: "Spotify Free accounts restrict on-demand track selection and remote seeking. Use Spotify Premium or switch to System App Audio Capture.",
                                    color = TextMuted,
                                    fontFamily = GeneralSansFontFamily,
                                    lineHeight = 18.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (onSwitchToSystemCapture != null) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = SignalOrange,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSwitchToSystemCapture() }
                                                .testTag("spotify_switch_to_capture_button")
                                        ) {
                                            Text(
                                                text = "USE SYSTEM CAPTURE",
                                                color = ChassisBase,
                                                fontFamily = GeneralSansFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = SurfaceElevated,
                                        border = BorderStroke(1.dp, BorderMilled),
                                        modifier = Modifier
                                            .clickable { onDismissPremiumWarning?.invoke() }
                                            .testTag("spotify_dismiss_premium_warning_button")
                                    ) {
                                        Text(
                                            text = "DISMISS",
                                            color = TextBone,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // URI / Link Input Section
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "TRACK URI OR WEB LINK",
                            color = TextMuted,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.04.sp
                        )

                        // Recessed Input Box
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SurfaceRecessed,
                            border = BorderStroke(
                                1.dp,
                                when {
                                    inputUri.isEmpty() -> BorderMilled
                                    isValid -> SyncGreen
                                    else -> SyncRed
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (inputUri.isEmpty()) {
                                        Text(
                                            text = "spotify:track:... or open.spotify.com/track/...",
                                            color = TextDim,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    BasicTextField(
                                        value = inputUri,
                                        onValueChange = onInputChanged,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            color = TextBone,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        cursorBrush = SolidColor(SignalOrange),
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("spotify_uri_input")
                                    )
                                }

                                if (inputUri.isNotEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(3.dp),
                                        color = SurfaceElevated,
                                        border = BorderStroke(1.dp, BorderMilled),
                                        modifier = Modifier
                                            .clickable { onInputChanged("") }
                                            .testTag("spotify_clear_input_button")
                                    ) {
                                        Text(
                                            text = "CLR",
                                            color = TextDim,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Validation Status Chip
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when {
                                inputUri.isEmpty() -> {
                                    Text(
                                        text = "STATUS: WAITING FOR URI",
                                        color = TextDim,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        letterSpacing = 0.02.sp
                                    )
                                }
                                isValid -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "●",
                                            color = SyncGreen,
                                            fontFamily = JetBrainsMonoFontFamily
                                        )
                                        Text(
                                            text = "VALID TRACK URI",
                                            color = SyncGreen,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.02.sp
                                        )
                                    }
                                }
                                else -> {
                                    val reason = (parsedResult as? SpotifyUriResult.Invalid)?.reason ?: "Invalid format"
                                    Text(
                                        text = "⚠ $reason",
                                        color = SyncRed,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        letterSpacing = 0.02.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // High-Voltage Dispatch Button
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isValid) SignalOrange else SurfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            if (isValid) SignalOrange else BorderMilled
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isValid) {
                                if (canonicalUri != null) {
                                    onPlayTriggered(canonicalUri)
                                }
                            }
                            .testTag("spotify_dispatch_play_button")
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "DISPATCH SYNCHRONIZED PLAY",
                                color = if (isValid) ChassisBase else TextDim,
                                fontFamily = CabinetGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.04.sp
                            )
                            Text(
                                text = "LEAD TIME: 400ms · MONOTONIC PRESENTATION CLOCK",
                                color = if (isValid) ChassisBase.copy(alpha = 0.75f) else TextDim,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Curated Sample Tracks Section
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "CURATED SAMPLE TRACKS (INSTANT TEST)",
                            color = TextMuted,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.04.sp
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .testTag("spotify_sample_tracks_list"),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            items(sampleTracks) { track ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SurfacePanel,
                                    border = BorderStroke(
                                        1.dp,
                                        if (inputUri == track.uri) BorderActive else BorderMilled
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onInputChanged(track.uri) }
                                        .testTag("spotify_sample_track_${track.title.replace(" ", "_")}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = track.title,
                                                color = TextBone,
                                                fontFamily = GeneralSansFontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = track.artist,
                                                color = TextMuted,
                                                fontFamily = GeneralSansFontFamily,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = if (inputUri == track.uri) SignalOrange else SurfaceElevated,
                                            border = BorderStroke(1.dp, BorderMilled),
                                            modifier = Modifier.clickable {
                                                onInputChanged(track.uri)
                                            }
                                        ) {
                                            Text(
                                                text = if (inputUri == track.uri) "CUE" else "SELECT",
                                                color = if (inputUri == track.uri) ChassisBase else TextBone,
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
