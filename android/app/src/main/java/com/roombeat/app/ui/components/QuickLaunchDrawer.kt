package com.roombeat.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.roombeat.app.source.capture.InstalledMediaApp
import com.roombeat.app.source.capture.InstalledMediaAppScanner
import com.roombeat.app.source.capture.MediaAppCategory
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.ChassisBase
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
 * State holder for managing [QuickLaunchDrawer] visibility, loaded apps, and selection.
 */
class QuickLaunchDrawerState(
    initialOpen: Boolean = false,
    val scanner: InstalledMediaAppScanner? = null
) {
    var isOpen by mutableStateOf(initialOpen)
    var installedApps by mutableStateOf<List<InstalledMediaApp>>(emptyList())
    var isLoading by mutableStateOf(false)
    var selectedApp by mutableStateOf<InstalledMediaApp?>(null)

    fun open() {
        isOpen = true
    }

    fun dismiss() {
        isOpen = false
    }

    fun scan(scannerInstance: InstalledMediaAppScanner? = scanner) {
        if (scannerInstance == null) return
        isLoading = true
        try {
            installedApps = scannerInstance.scanInstalledMediaApps()
        } finally {
            isLoading = false
        }
    }
}

/**
 * Creates and remembers a [QuickLaunchDrawerState] instance.
 */
@Composable
fun rememberQuickLaunchDrawerState(
    initialOpen: Boolean = false,
    scanner: InstalledMediaAppScanner? = null
): QuickLaunchDrawerState {
    val state = remember { QuickLaunchDrawerState(initialOpen, scanner) }
    LaunchedEffect(scanner) {
        if (scanner != null && state.installedApps.isEmpty()) {
            state.scan(scanner)
        }
    }
    return state
}

/**
 * Converts an Android [Drawable] to a Compose [ImageBitmap] safely.
 * Returns null if running in headless JVM or conversion fails.
 */
fun safeDrawableToImageBitmap(drawable: Drawable?): ImageBitmap? {
    if (drawable == null) return null
    return try {
        val bitmap = when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
        }
        bitmap?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

/**
 * Tactile Acoustic Industrial bottom sheet drawer presenting installed media players
 * (YouTube, VLC, SoundCloud, Spotify, local players) for quick-launch audio capture streaming.
 *
 * Requirements:
 * - Machined dark chassis (#0B0C0E), module surface (#13151A), milled borders (#262A35),
 *   Signal Orange action triggers (#FF5500), Cabinet Grotesk / General Sans / JetBrains Mono typography.
 * - Displays installed media apps in tactile tiles with icons and titles.
 * - Handles intent launching (`context.startActivity(launchIntent)`), keeping RoomBeat capture service
 *   running in the background.
 * - Displays empty state when no third-party media players are detected, with fallback to Local Storage.
 */
@Composable
fun QuickLaunchDrawer(
    isOpen: Boolean,
    apps: List<InstalledMediaApp>,
    onAppSelected: (InstalledMediaApp) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isCaptureActive: Boolean = false,
    onRescan: (() -> Unit)? = null,
    onNavigateToLocalStorage: (() -> Unit)? = null,
    launchActivityOnSelect: Boolean = true
) {
    if (!isOpen) return

    val context = LocalContext.current

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
                .testTag("quick_launch_drawer_scrim"),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Drawer Panel Container
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {} // Prevent click-through to scrim
                    .testTag("quick_launch_drawer"),
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
                            StatusBeacon(status = if (isCaptureActive) BeaconStatus.LOCKED else BeaconStatus.IDLE)
                            Column {
                                Text(
                                    text = "MEDIA PLAYER RACK",
                                    color = TextBone,
                                    fontWeight = FontWeight.Bold,
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = if (isCaptureActive) {
                                        "CAPTURE ENGINE // ACTIVE IN BACKGROUND"
                                    } else {
                                        "SELECT SOURCE // CAPTURE STANDBY"
                                    },
                                    color = if (isCaptureActive) SyncGreen else TextMuted,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // Close button
                        TactileKeycapButton(
                            onClick = onDismiss,
                            variant = TactileButtonVariant.SURFACE,
                            modifier = Modifier.testTag("quick_launch_close_btn"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "ESC",
                                color = TextDim,
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    // Milled divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BorderMilled)
                    )

                    // Content: Empty State or App List
                    if (apps.isEmpty()) {
                        QuickLaunchEmptyState(
                            onRescan = onRescan,
                            onNavigateToLocalStorage = onNavigateToLocalStorage
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(apps, key = { it.packageName }) { app ->
                                MediaAppTile(
                                    app = app,
                                    onClick = {
                                        onAppSelected(app)
                                        if (launchActivityOnSelect && app.launchIntent != null) {
                                            try {
                                                val intent = Intent(app.launchIntent).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Log.w("QuickLaunchDrawer", "Failed to launch ${app.packageName}: ${e.message}")
                                            }
                                        }
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    // Background Service Persistent HUD Telemetry Footer
                    RecessedPanel(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "DAEMON:",
                                color = SignalOrange,
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "RoomBeat capture service persists in background. Tap notification to return.",
                                color = TextMuted,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single tactile tile representing an installed media player.
 */
@Composable
fun MediaAppTile(
    app: InstalledMediaApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconBitmap = remember(app.icon) { safeDrawableToImageBitmap(app.icon) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("quick_launch_tile_${app.packageName}"),
        shape = RoundedCornerShape(6.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, BorderMilled),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // App Icon or Monogram Box
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(SurfaceRecessed, RoundedCornerShape(4.dp))
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = app.appName,
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    // Tactile Monogram
                    val monogram = when (app.category) {
                        MediaAppCategory.AUDIO -> "AU"
                        MediaAppCategory.VIDEO -> "VD"
                        MediaAppCategory.STREAMING -> "ST"
                        MediaAppCategory.OTHER -> "MP"
                    }
                    Text(
                        text = monogram,
                        color = SignalOrange,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                }
            }

            // App Name & Details
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = app.appName,
                    color = TextBone,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = app.category.name,
                        color = TextDim,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                    if (app.isKnownOptOutApp) {
                        Text(
                            text = "OPT-OUT WARN",
                            color = SyncAmber,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Tactile Trigger Arrow
            Text(
                text = "LAUNCH >",
                color = SignalOrange,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Empty state displayed when no third-party media players are discovered.
 */
@Composable
fun QuickLaunchEmptyState(
    onRescan: (() -> Unit)?,
    onNavigateToLocalStorage: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("quick_launch_empty_state"),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceRecessed,
        border = BorderStroke(1.dp, BorderMilled)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusBeacon(status = BeaconStatus.WARNING)
            Text(
                text = "NO MEDIA PLAYERS DETECTED",
                color = TextBone,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall
            )
            Text(
                text = "No compatible media player applications (YouTube, Spotify, VLC, SoundCloud) were detected on this device.\n\n" +
                        "Install a player or switch directly to Local Storage Audio (Module 01) to stream files.",
                color = TextMuted,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onRescan != null) {
                    TactileKeycapButton(
                        onClick = onRescan,
                        variant = TactileButtonVariant.SURFACE,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_launch_rescan_btn")
                    ) {
                        Text(text = "RESCAN")
                    }
                }

                if (onNavigateToLocalStorage != null) {
                    TactileKeycapButton(
                        onClick = onNavigateToLocalStorage,
                        variant = TactileButtonVariant.PRIMARY,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_launch_local_storage_btn")
                    ) {
                        Text(text = "LOCAL STORAGE")
                    }
                }
            }
        }
    }
}
