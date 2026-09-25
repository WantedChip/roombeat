package com.roombeat.app.source.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.roombeat.app.source.AudioTrackInfo

/**
 * Abstraction for retrieving metadata via [MediaMetadataRetriever] or a test stub.
 * Ensures deterministic unit testing on headless JVM environments without native media framework dependencies.
 */
interface MetadataRetrieverFacade : AutoCloseable {
    fun setDataSource(context: Context, uri: Uri)
    fun extractMetadata(keyCode: Int): String?
    fun getEmbeddedPicture(): ByteArray?
    override fun close()
}

/**
 * Default production implementation delegating to [android.media.MediaMetadataRetriever].
 */
class AndroidMetadataRetrieverFacade : MetadataRetrieverFacade {
    private val retriever = MediaMetadataRetriever()

    override fun setDataSource(context: Context, uri: Uri) {
        retriever.setDataSource(context, uri)
    }

    override fun extractMetadata(keyCode: Int): String? {
        return retriever.extractMetadata(keyCode)
    }

    override fun getEmbeddedPicture(): ByteArray? {
        return retriever.embeddedPicture
    }

    override fun close() {
        try {
            retriever.release()
        } catch (_: Throwable) {
            // Ignore release failure
        }
    }
}

/**
 * Abstraction for ContentResolver queries (display name, file size, MIME type)
 * for test isolation.
 */
interface ContentQueryFacade {
    fun queryDisplayNameAndSize(context: Context, uri: Uri): Pair<String?, Long?>
    fun getMimeType(context: Context, uri: Uri): String?
}

/**
 * Default production ContentQueryFacade querying Android's [android.content.ContentResolver].
 */
class AndroidContentQueryFacade : ContentQueryFacade {
    override fun queryDisplayNameAndSize(context: Context, uri: Uri): Pair<String?, Long?> {
        var displayName: String? = null
        var fileSize: Long? = null

        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1 && !it.isNull(nameIdx)) {
                        displayName = it.getString(nameIdx)
                    }
                    val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1 && !it.isNull(sizeIdx)) {
                        fileSize = it.getLong(sizeIdx)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(AudioMetadataExtractor.TAG, "Failed to query ContentResolver for $uri: ${e.message}")
        }

        return Pair(displayName, fileSize)
    }

    override fun getMimeType(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Extracts comprehensive audio track metadata from SAF content URIs.
 *
 * Capabilities:
 * - Reads ID3/format tags (title, artist, album, duration, sample rate, channels, bitrate, MIME type).
 * - Extracts embedded album artwork byte arrays.
 * - Extracts display name and file size from [android.provider.OpenableColumns] via ContentResolver.
 * - Resolves missing or blank titles with sanitized filename fallbacks (stripping file extensions).
 * - Handles corrupt or unreadable files gracefully with safe default fallbacks.
 */
class AudioMetadataExtractor(
    private val retrieverFactory: () -> MetadataRetrieverFacade = { AndroidMetadataRetrieverFacade() },
    private val contentQueryFacade: ContentQueryFacade = AndroidContentQueryFacade()
) {
    companion object {
        const val TAG = "AudioMetadataExtractor"
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_CHANNEL_COUNT = 2

        val DEFAULT: AudioMetadataExtractor by lazy { AudioMetadataExtractor() }

        fun extract(context: Context, uri: Uri): AudioTrackInfo =
            DEFAULT.extractFromUri(context, uri)

        fun extract(context: Context, uriString: String): AudioTrackInfo =
            DEFAULT.extractFromUri(context, Uri.parse(uriString))

        /**
         * Strips the file extension from a filename or path segment.
         * E.g. "song_track.mp3" -> "song_track"
         * E.g. "my.sound.track.flac" -> "my.sound.track"
         * E.g. "no_extension" -> "no_extension"
         * E.g. ".hidden_file" -> ".hidden_file"
         */
        fun stripExtension(fileName: String): String {
            val trimmed = fileName.trim()
            if (trimmed.isEmpty()) return ""
            val lastDot = trimmed.lastIndexOf('.')
            // If dot is at start (hidden file like .nomedia) or not found, return as is
            if (lastDot <= 0) return trimmed
            return trimmed.substring(0, lastDot)
        }
    }

    /**
     * Extracts an [AudioTrackInfo] instance from the given content [uri].
     */
    fun extractFromUri(context: Context, uri: Uri): AudioTrackInfo {
        // 1. Query display name and size from ContentResolver
        val (contentDisplayName, contentSize) = contentQueryFacade.queryDisplayNameAndSize(context, uri)
        val contentMimeType = contentQueryFacade.getMimeType(context, uri)

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs: Long = 0L
        var sampleRate: Int = DEFAULT_SAMPLE_RATE
        var channelCount: Int = DEFAULT_CHANNEL_COUNT
        var mimeType: String = contentMimeType ?: "audio/mpeg"
        var artworkBytes: ByteArray? = null

        // 2. Extract embedded tags via MetadataRetrieverFacade
        try {
            retrieverFactory().use { retriever ->
                retriever.setDataSource(context, uri)

                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()

                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    if (it > 0) durationMs = it
                }

                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let {
                    if (it > 0) sampleRate = it
                }

                // Check channel count if provided (fallback 2)
                val channelsMeta = retriever.extractMetadata(31 /* METADATA_KEY_VIDEO_OR_AUDIO_CHANNELS */)
                channelsMeta?.toIntOrNull()?.let {
                    if (it > 0) channelCount = it
                }

                val metaMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.trim()
                if (!metaMime.isNullOrEmpty()) {
                    mimeType = metaMime
                }

                artworkBytes = retriever.getEmbeddedPicture()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "MediaMetadataRetriever extraction failed for $uri: ${e.message}")
        }

        // 3. Resolve title fallback if missing or blank
        val resolvedTitle = if (!title.isNullOrBlank()) {
            title!!
        } else if (!contentDisplayName.isNullOrBlank()) {
            stripExtension(contentDisplayName!!)
        } else {
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrBlank()) {
                stripExtension(lastSegment)
            } else {
                "Unknown Audio Track"
            }
        }

        // 4. Resolve artist and album empty strings to null
        val resolvedArtist = if (!artist.isNullOrBlank()) artist else null
        val resolvedAlbum = if (!album.isNullOrBlank()) album else null

        return AudioTrackInfo(
            uri = uri.toString(),
            title = resolvedTitle,
            artist = resolvedArtist,
            album = resolvedAlbum,
            durationMs = durationMs,
            sampleRate = sampleRate,
            channelCount = channelCount,
            mimeType = mimeType,
            fileSizeBytes = contentSize ?: 0L,
            artworkBytes = artworkBytes
        )
    }
}
