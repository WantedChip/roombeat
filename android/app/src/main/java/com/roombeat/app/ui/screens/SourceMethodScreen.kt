package com.roombeat.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.roombeat.app.source.AudioTrackInfo
import com.roombeat.app.source.local.AudioMetadataExtractor
import com.roombeat.app.source.local.LocalFilePickerLauncher
import com.roombeat.app.source.local.rememberLocalAudioFilePicker
import com.roombeat.app.ui.components.BeaconStatus
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Master Source Selector Screen for RoomBeat (Sub-phase v0.5.0 / Roadmap §8 / Flow 5).
 *
 * Adheres strictly to the "Tactile Acoustic Industrial" design system:
 * - Chassis background: #0B0C0E
 * - Module rack enclosures: #13151A with 1px #262A35 milled borders
 * - Signal Orange action triggers (#FF5500) & Phosphor Green sync beacons (#00E599)
 * - Typographic hierarchy: Cabinet Grotesk, General Sans, JetBrains Mono
 *
 * Displays the 3 audio ingest modules:
 * - Module 01: [ DEVICE AUDIO STORAGE ] — Active in v0.5.0, SAF picker + metadata extraction.
 * - Module 02: [ SYSTEM APP CAPTURE ] — Standby for Phase v0.6 (MediaProjection capture).
 * - Module 03: [ SPOTIFY APP REMOTE ] — Standby for Phase v0.7 (Spotify SDK control sync).
 */
@Composable
fun SourceMethodScreen(
    onNavigateBack: () -> Unit,
    onProceedToPlayback: (AudioTrackInfo) -> Unit,
    modifier: Modifier = Modifier,
    initialTrackInfo: AudioTrackInfo? = null,
    metadataExtractor: AudioMetadataExtractor = remember { AudioMetadataExtractor.DEFAULT }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTrack by remember { mutableStateOf(initialTrackInfo) }
    var isExtracting by remember { mutableStateOf(false) }

    val filePicker = rememberLocalAudioFilePicker(
        onAudioFileSelected = { uri ->
            isExtracting = true
            coroutineScope.launch(Dispatchers.IO) {
                val trackInfo = metadataExtractor.extractFromUri(context, uri)
                withContext(Dispatchers.Main) {
                    selectedTrack = trackInfo
                    isExtracting = false
                }
            }
        }
    )

    SourceMethodContent(
        selectedTrack = selectedTrack,
        isExtracting = isExtracting,
        onNavigateBack = onNavigateBack,
        onLaunchFilePicker = { filePicker.launch() },
        onProceedToPlayback = onProceedToPlayback,
        modifier = modifier
    )
}

/**
 * Pure stateless content presentation for [SourceMethodScreen], facilitating Compose previews and testing.
 */
@Composable
fun SourceMethodContent(
    selectedTrack: AudioTrackInfo?,
    isExtracting: Boolean,
    onNavigateBack: () -> Unit,
    onLaunchFilePicker: () -> Unit,
    onProceedToPlayback: (AudioTrackInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("source_method_screen"),
        color = ChassisBase
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Pinned Hardware Top Navigation Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TactileKeycapButton(
                    onClick = onNavigateBack,
                    variant = TactileButtonVariant.SURFACE,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("source_cancel_button")
                ) {
                    Text(
                        text = "CANCEL / LOBBY",
                        style = RoomBeatTheme.typography.labelSm,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "AUDIO SOURCE SELECT",
                        style = RoomBeatTheme.typography.headingMd,
                        color = TextBone
                    )
                    Text(
                        text = "[ AUDIO INGEST RACK · 3 CHANNELS ]",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                }

                StatusBeacon(
                    status = BeaconStatus.LOCKED,
                    label = "ONLINE"
                )
            }

            // 2. Central Scrollable Rack of Ingest Modules
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // MODULE 01: DEVICE AUDIO STORAGE (ACTIVE)
                item {
                    DeviceAudioStorageModule(
                        selectedTrack = selectedTrack,
                        isExtracting = isExtracting,
                        onLaunchFilePicker = onLaunchFilePicker,
                        onProceedToPlayback = onProceedToPlayback
                    )
                }

                // MODULE 02: SYSTEM APP CAPTURE (STANDBY - PHASE v0.6)
                item {
                    SystemAppCaptureModule()
                }

                // MODULE 03: SPOTIFY APP REMOTE (STANDBY - PHASE v0.7)
                item {
                    SpotifyAppRemoteModule()
                }
            }

            // 3. Bottom Hardware Telemetry Strip
            RecessedPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CHANNELS: 1 ACTIVE · 2 STANDBY",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                    Text(
                        text = "MASTER PIPELINE: 48kHz / 20ms OPUS",
                        style = RoomBeatTheme.typography.codeXs,
                        color = SyncGreen
                    )
                }
            }
        }
    }
}

/**
 * Module 01: [ DEVICE AUDIO STORAGE ]
 */
@Composable
private fun DeviceAudioStorageModule(
    selectedTrack: AudioTrackInfo?,
    isExtracting: Boolean,
    onLaunchFilePicker: () -> Unit,
    onProceedToPlayback: (AudioTrackInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("module_01_card"),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, if (selectedTrack != null) BorderActive else BorderMilled)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row: Index + Title + Status Beacon
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
                        text = "01 / 03",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                    Text(
                        text = "DEVICE AUDIO STORAGE",
                        style = RoomBeatTheme.typography.labelSm,
                        color = TextBone,
                        fontWeight = FontWeight.Bold
                    )
                }

                StatusBeacon(
                    status = BeaconStatus.LOCKED,
                    label = if (selectedTrack != null) "LOADED" else "READY"
                )
            }

            // Divider Line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderMilled)
            )

            Text(
                text = "Direct bit-accurate local audio file streaming via Storage Access Framework (SAF).",
                style = RoomBeatTheme.typography.bodyMd,
                color = TextMuted
            )

            if (selectedTrack == null) {
                // Empty state: instructions & file picker trigger
                RecessedPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "SUPPORTED FORMATS: MP3 · FLAC · WAV · M4A · AAC · OGG",
                            style = RoomBeatTheme.typography.codeXs,
                            color = TextDim
                        )
                        Text(
                            text = "PERSISTENT ACCESS: SAF READ_URI_PERMISSION",
                            style = RoomBeatTheme.typography.codeXs,
                            color = SyncGreen
                        )
                    }
                }

                TactileKeycapButton(
                    onClick = onLaunchFilePicker,
                    variant = TactileButtonVariant.PRIMARY,
                    enabled = !isExtracting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("module_01_select_file_button")
                ) {
                    Text(
                        text = if (isExtracting) "READING METADATA..." else "[ SELECT AUDIO FILE ]"
                    )
                }
            } else {
                // Loaded track state: Track card + telemetry badges + action triggers
                TrackMetadataCard(
                    track = selectedTrack,
                    modifier = Modifier.testTag("module_01_track_info")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TactileKeycapButton(
                        onClick = onLaunchFilePicker,
                        variant = TactileButtonVariant.SURFACE,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "CHANGE FILE")
                    }

                    TactileKeycapButton(
                        onClick = { onProceedToPlayback(selectedTrack) },
                        variant = TactileButtonVariant.PRIMARY,
                        modifier = Modifier
                            .weight(1.6f)
                            .testTag("module_01_proceed_button")
                    ) {
                        Text(text = "[ PROCEED TO PLAYBACK ]")
                    }
                }
            }
        }
    }
}

/**
 * Tactile metadata preview card showing embedded artwork, title, artist, and acoustic telemetry.
 */
@Composable
private fun TrackMetadataCard(
    track: AudioTrackInfo,
    modifier: Modifier = Modifier
) {
    RecessedPanel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Artwork Box (or tactile fallback badge)
                val artworkBitmap = remember(track.artworkBytes) {
                    track.artworkBytes?.let { bytes ->
                        try {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }

                if (artworkBitmap != null) {
                    Image(
                        bitmap = artworkBitmap,
                        contentDescription = "Embedded Track Artwork",
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, BorderMilled, RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(SurfaceElevated, RoundedCornerShape(4.dp))
                            .border(1.dp, BorderMilled, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = track.formatBadge,
                            style = RoomBeatTheme.typography.codeMd,
                            color = SignalOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Track Title & Artist
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = track.displayName,
                        style = RoomBeatTheme.typography.headingMd,
                        color = TextBone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = track.artist ?: "Unknown Artist",
                        style = RoomBeatTheme.typography.bodyMd,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!track.album.isNullOrBlank()) {
                        Text(
                            text = track.album,
                            style = RoomBeatTheme.typography.labelSm,
                            color = TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Acoustic Telemetry Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HardwareTagBadge(
                    label = track.formatBadge,
                    highlight = true
                )

                HardwareTagBadge(
                    label = track.formattedDuration
                )

                HardwareTagBadge(
                    label = track.formattedSampleRate
                )

                HardwareTagBadge(
                    label = track.formattedChannels
                )

                if (track.fileSizeBytes > 0) {
                    HardwareTagBadge(
                        label = track.formattedFileSize
                    )
                }
            }
        }
    }
}

/**
 * Compact hardware parameter chip tag.
 */
@Composable
private fun HardwareTagBadge(
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(3.dp),
        color = if (highlight) SignalOrange.copy(alpha = 0.15f) else SurfaceElevated,
        border = BorderStroke(1.dp, if (highlight) SignalOrange else BorderMilled)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = RoomBeatTheme.typography.codeXs,
            color = if (highlight) SignalOrange else TextBone,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Module 02: [ SYSTEM APP CAPTURE ] (Standby - Phase v0.6)
 */
@Composable
private fun SystemAppCaptureModule(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("module_02_card")
            .alpha(0.6f),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, BorderMilled)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                        text = "02 / 03",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                    Text(
                        text = "SYSTEM APP CAPTURE",
                        style = RoomBeatTheme.typography.labelSm,
                        color = TextBone,
                        fontWeight = FontWeight.Bold
                    )
                }

                StatusBeacon(
                    status = BeaconStatus.IDLE,
                    label = "STANDBY"
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderMilled)
            )

            Text(
                text = "Internal audio stream capture from YouTube, SoundCloud, or media apps via AudioPlaybackCaptureConfiguration & MediaProjection.",
                style = RoomBeatTheme.typography.bodyMd,
                color = TextMuted
            )

            RecessedPanel(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "CAPTURE ENGINE: AudioRecord (48kHz Stereo PCM)",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                    Text(
                        text = "STANDBY STATUS: SCHEDULED FOR SUB-PHASE v0.6",
                        style = RoomBeatTheme.typography.codeXs,
                        color = SyncAmber
                    )
                }
            }

            TactileKeycapButton(
                onClick = {},
                enabled = false,
                variant = TactileButtonVariant.SURFACE,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "[ STANDBY · PHASE v0.6 ]")
            }
        }
    }
}

/**
 * Module 03: [ SPOTIFY APP REMOTE ] (Standby - Phase v0.7)
 */
@Composable
private fun SpotifyAppRemoteModule(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("module_03_card")
            .alpha(0.6f),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, BorderMilled)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                        text = "03 / 03",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                    Text(
                        text = "SPOTIFY APP REMOTE",
                        style = RoomBeatTheme.typography.labelSm,
                        color = TextBone,
                        fontWeight = FontWeight.Bold
                    )
                }

                StatusBeacon(
                    status = BeaconStatus.IDLE,
                    label = "STANDBY"
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderMilled)
            )

            Text(
                text = "Direct remote playback & command sync with Spotify client via Spotify App Remote SDK.",
                style = RoomBeatTheme.typography.bodyMd,
                color = TextMuted
            )

            RecessedPanel(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "SYNC PROTOCOL: SPOTIFY_CMD & PlaybackState Tracker",
                        style = RoomBeatTheme.typography.codeXs,
                        color = TextDim
                    )
                    Text(
                        text = "STANDBY STATUS: SCHEDULED FOR SUB-PHASE v0.7",
                        style = RoomBeatTheme.typography.codeXs,
                        color = SyncAmber
                    )
                }
            }

            TactileKeycapButton(
                onClick = {},
                enabled = false,
                variant = TactileButtonVariant.SURFACE,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "[ STANDBY · PHASE v0.7 ]")
            }
        }
    }
}

// ---------------- Previews ----------------

@Preview(name = "Source Method Screen - Idle State", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SourceMethodScreenIdlePreview() {
    RoomBeatTheme {
        SourceMethodContent(
            selectedTrack = null,
            isExtracting = false,
            onNavigateBack = {},
            onLaunchFilePicker = {},
            onProceedToPlayback = {}
        )
    }
}

@Preview(name = "Source Method Screen - Track Loaded", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SourceMethodScreenLoadedPreview() {
    RoomBeatTheme {
        SourceMethodContent(
            selectedTrack = AudioTrackInfo(
                uri = "content://com.android.providers.media.documents/document/audio%3A1042",
                title = "Resonance Horizon",
                artist = "Modeselektor",
                album = "Extended Play Vol. 4",
                durationMs = 234000L,
                sampleRate = 48000,
                channelCount = 2,
                mimeType = "audio/flac",
                fileSizeBytes = 28450123L
            ),
            isExtracting = false,
            onNavigateBack = {},
            onLaunchFilePicker = {},
            onProceedToPlayback = {}
        )
    }
}
