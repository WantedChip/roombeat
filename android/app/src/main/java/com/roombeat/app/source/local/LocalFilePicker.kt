package com.roombeat.app.source.local

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Controller interface wrapping the SAF audio file picker launcher.
 */
interface LocalFilePickerLauncher {
    fun launch()
}

/**
 * Storage Access Framework (SAF) document picker contract wrapper and permission coordinator.
 *
 * Provides:
 * - Filter MIME types for standard audio formats (MP3, FLAC, WAV, M4A, AAC, OGG).
 * - Safe acquisition of persistable read URI permissions (`takePersistableUriPermission`).
 * - Compose integration helpers for `OpenDocument` and `GetContent` contracts.
 */
object LocalFilePicker {
    const val TAG = "LocalFilePicker"

    /**
     * Standard audio MIME types used to filter SAF file selection.
     */
    val SUPPORTED_MIME_TYPES = arrayOf(
        "audio/*",
        "audio/mpeg",
        "audio/flac",
        "audio/wav",
        "audio/mp4",
        "audio/x-m4a",
        "audio/aac",
        "audio/ogg"
    )

    /**
     * Safely takes a persistent read permission grant on the given [uri].
     *
     * In Android Storage Access Framework (SAF), taking persistable URI permission
     * ensures that RoomBeat retains read access across device reboots, service restarts,
     * or prolonged background playback sessions.
     *
     * @return true if permission was successfully taken, false if a [SecurityException]
     * or other exception occurred.
     */
    fun takePersistableReadPermission(
        contentResolver: ContentResolver,
        uri: Uri
    ): Boolean {
        return try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Log.d(TAG, "Persistable read URI permission granted for: $uri")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException taking persistable URI permission for $uri: ${e.message}")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "Unexpected error taking persistable URI permission for $uri: ${e.message}")
            false
        }
    }
}

/**
 * Compose helper to remember an SAF OpenDocument audio file picker launcher.
 *
 * Automatically takes persistable read URI permission upon file selection
 * and invokes [onAudioFileSelected] with the granted [Uri].
 *
 * @param onAudioFileSelected Invoked when a valid audio file [Uri] is selected.
 * @param mimeTypes Array of MIME types to filter picker display. Defaults to [LocalFilePicker.SUPPORTED_MIME_TYPES].
 */
@Composable
fun rememberLocalAudioFilePicker(
    onAudioFileSelected: (Uri) -> Unit,
    mimeTypes: Array<String> = LocalFilePicker.SUPPORTED_MIME_TYPES
): LocalFilePickerLauncher {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    val launcher: ManagedActivityResultLauncher<Array<String>, Uri?> =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                LocalFilePicker.takePersistableReadPermission(contentResolver, uri)
                onAudioFileSelected(uri)
            }
        }

    return remember(launcher, mimeTypes) {
        object : LocalFilePickerLauncher {
            override fun launch() {
                launcher.launch(mimeTypes)
            }
        }
    }
}

/**
 * Compose helper to remember an SAF GetContent audio file picker launcher (fallback contract).
 *
 * @param onAudioFileSelected Invoked when a valid audio file [Uri] is selected.
 * @param mimeType MIME type to filter picker display. Defaults to audio wildcard format.
 */
@Composable
fun rememberLocalAudioGetContentPicker(
    onAudioFileSelected: (Uri) -> Unit,
    mimeType: String = "audio/*"
): LocalFilePickerLauncher {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    val launcher: ManagedActivityResultLauncher<String, Uri?> =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                LocalFilePicker.takePersistableReadPermission(contentResolver, uri)
                onAudioFileSelected(uri)
            }
        }

    return remember(launcher, mimeType) {
        object : LocalFilePickerLauncher {
            override fun launch() {
                launcher.launch(mimeType)
            }
        }
    }
}
