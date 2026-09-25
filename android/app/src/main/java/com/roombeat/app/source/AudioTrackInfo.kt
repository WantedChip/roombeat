package com.roombeat.app.source

import java.util.Locale

/**
 * Data model representing an audio track selected via the Storage Access Framework (SAF)
 * or another source ingestion pipeline in RoomBeat.
 *
 * Adheres to RoomBeat standard audio specifications:
 * - Default sample rate: 48,000 Hz
 * - Default channel count: 2 (Stereo)
 */
data class AudioTrackInfo(
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2,
    val mimeType: String = "audio/mpeg",
    val fileSizeBytes: Long = 0L,
    val artworkBytes: ByteArray? = null
) {
    /**
     * Display name fallback: uses [title] if not blank; otherwise defaults to "Unknown Track".
     */
    val displayName: String
        get() = if (title.isNotBlank()) title.trim() else "Unknown Track"

    /**
     * Formats duration in "m:ss" (e.g. "3:45") or "h:mm:ss" (e.g. "1:02:15") format.
     * Returns "0:00" if [durationMs] <= 0.
     */
    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "0:00"
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }

    /**
     * Formatted sample rate string (e.g. "48.0 kHz", "44.1 kHz", "96.0 kHz").
     * Returns "Unknown" if [sampleRate] <= 0.
     */
    val formattedSampleRate: String
        get() {
            if (sampleRate <= 0) return "Unknown"
            val khz = sampleRate / 1000.0
            return String.format(Locale.US, "%.1f kHz", khz)
        }

    /**
     * Formatted file size string (e.g. "4.2 MB", "850.0 KB", "1024 B").
     * Returns "0 B" if [fileSizeBytes] <= 0.
     */
    val formattedFileSize: String
        get() {
            if (fileSizeBytes <= 0) return "0 B"
            val kb = fileSizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$fileSizeBytes B"
            }
        }

    /**
     * Human-readable channel count string ("Stereo", "Mono", or "X ch").
     */
    val formattedChannels: String
        get() = when (channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            else -> if (channelCount > 0) "$channelCount ch" else "Unknown"
        }

    /**
     * Short hardware-style format badge identifier (e.g. "MP3", "FLAC", "WAV", "M4A", "AAC", "OGG", "OPUS").
     */
    val formatBadge: String
        get() {
            val lowerMime = mimeType.lowercase(Locale.US)
            val lowerUri = uri.lowercase(Locale.US)
            val lowerTitle = title.lowercase(Locale.US)

            return when {
                lowerMime.contains("flac") || lowerUri.endsWith(".flac") || lowerTitle.endsWith(".flac") -> "FLAC"
                lowerMime.contains("wav") || lowerUri.endsWith(".wav") || lowerTitle.endsWith(".wav") -> "WAV"
                lowerMime.contains("mp4") || lowerMime.contains("m4a") || lowerUri.endsWith(".m4a") || lowerTitle.endsWith(".m4a") -> "M4A"
                lowerMime.contains("aac") || lowerUri.endsWith(".aac") || lowerTitle.endsWith(".aac") -> "AAC"
                lowerMime.contains("opus") || lowerUri.endsWith(".opus") || lowerTitle.endsWith(".opus") -> "OPUS"
                lowerMime.contains("ogg") || lowerUri.endsWith(".ogg") || lowerTitle.endsWith(".ogg") -> "OGG"
                lowerMime.contains("mpeg") || lowerMime.contains("mp3") || lowerUri.endsWith(".mp3") || lowerTitle.endsWith(".mp3") -> "MP3"
                else -> {
                    val subType = lowerMime.substringAfter("audio/").substringBefore(";").trim()
                    if (subType.isNotEmpty() && subType != lowerMime && subType.length in 2..5) {
                        subType.uppercase(Locale.US)
                    } else {
                        "AUDIO"
                    }
                }
            }
        }

    /**
     * True if embedded artwork is present and non-empty.
     */
    val hasArtwork: Boolean
        get() = artworkBytes != null && artworkBytes.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioTrackInfo

        if (uri != other.uri) return false
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (durationMs != other.durationMs) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (mimeType != other.mimeType) return false
        if (fileSizeBytes != other.fileSizeBytes) return false
        if (artworkBytes != null) {
            if (other.artworkBytes == null) return false
            if (!artworkBytes.contentEquals(other.artworkBytes)) return false
        } else if (other.artworkBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + fileSizeBytes.hashCode()
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        return result
    }
}
