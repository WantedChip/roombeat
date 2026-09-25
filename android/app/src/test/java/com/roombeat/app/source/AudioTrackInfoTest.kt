package com.roombeat.app.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackInfoTest {

    @Test
    fun formattedDuration_formatsStandardDurationsCorrectly() {
        val track0 = AudioTrackInfo(uri = "uri", title = "T", durationMs = 0L)
        assertEquals("0:00", track0.formattedDuration)

        val trackNegative = AudioTrackInfo(uri = "uri", title = "T", durationMs = -1000L)
        assertEquals("0:00", trackNegative.formattedDuration)

        val track5s = AudioTrackInfo(uri = "uri", title = "T", durationMs = 5000L)
        assertEquals("0:05", track5s.formattedDuration)

        val track1m5s = AudioTrackInfo(uri = "uri", title = "T", durationMs = 65000L)
        assertEquals("1:05", track1m5s.formattedDuration)

        val track3m45s = AudioTrackInfo(uri = "uri", title = "T", durationMs = 225000L)
        assertEquals("3:45", track3m45s.formattedDuration)

        val track59m59s = AudioTrackInfo(uri = "uri", title = "T", durationMs = 3599000L)
        assertEquals("59:59", track59m59s.formattedDuration)

        val track1h = AudioTrackInfo(uri = "uri", title = "T", durationMs = 3600000L)
        assertEquals("1:00:00", track1h.formattedDuration)

        val track1h2m15s = AudioTrackInfo(uri = "uri", title = "T", durationMs = 3735000L)
        assertEquals("1:02:15", track1h2m15s.formattedDuration)
    }

    @Test
    fun formattedSampleRate_formatsSampleRatesCorrectly() {
        assertEquals("48.0 kHz", AudioTrackInfo(uri = "uri", title = "T", sampleRate = 48000).formattedSampleRate)
        assertEquals("44.1 kHz", AudioTrackInfo(uri = "uri", title = "T", sampleRate = 44100).formattedSampleRate)
        assertEquals("96.0 kHz", AudioTrackInfo(uri = "uri", title = "T", sampleRate = 96000).formattedSampleRate)
        assertEquals("192.0 kHz", AudioTrackInfo(uri = "uri", title = "T", sampleRate = 192000).formattedSampleRate)
        assertEquals("Unknown", AudioTrackInfo(uri = "uri", title = "T", sampleRate = 0).formattedSampleRate)
        assertEquals("Unknown", AudioTrackInfo(uri = "uri", title = "T", sampleRate = -48000).formattedSampleRate)
    }

    @Test
    fun formattedFileSize_formatsBytesCorrectly() {
        assertEquals("0 B", AudioTrackInfo(uri = "uri", title = "T", fileSizeBytes = 0L).formattedFileSize)
        assertEquals("0 B", AudioTrackInfo(uri = "uri", title = "T", fileSizeBytes = -50L).formattedFileSize)
        assertEquals("500 B", AudioTrackInfo(uri = "uri", title = "T", fileSizeBytes = 500L).formattedFileSize)
        assertEquals("1.0 KB", AudioTrackInfo(uri = "uri", title = "T", fileSizeBytes = 1024L).formattedFileSize)
        assertEquals("850.0 KB", AudioTrackInfo(uri = "uri", title = "T", fileSizeBytes = 870400L).formattedFileSize)
        assertEquals("4.2 MB", AudioTrackInfo(uri = "uri", title = "T", fileSizeBytes = 4404019L).formattedFileSize)
        assertEquals("1.0 GB", AudioTrackInfo(uri = "uri", title = "T", fileSizeBytes = 1073741824L).formattedFileSize)
    }

    @Test
    fun formattedChannels_formatsStereoMonoMultiCorrectly() {
        assertEquals("Mono", AudioTrackInfo(uri = "uri", title = "T", channelCount = 1).formattedChannels)
        assertEquals("Stereo", AudioTrackInfo(uri = "uri", title = "T", channelCount = 2).formattedChannels)
        assertEquals("6 ch", AudioTrackInfo(uri = "uri", title = "T", channelCount = 6).formattedChannels)
        assertEquals("Unknown", AudioTrackInfo(uri = "uri", title = "T", channelCount = 0).formattedChannels)
    }

    @Test
    fun formatBadge_resolvesStandardMimeTypesAndExtensions() {
        assertEquals("MP3", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/mpeg").formatBadge)
        assertEquals("MP3", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/mp3").formatBadge)
        assertEquals("FLAC", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/flac").formatBadge)
        assertEquals("WAV", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/wav").formatBadge)
        assertEquals("WAV", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/x-wav").formatBadge)
        assertEquals("M4A", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/mp4").formatBadge)
        assertEquals("M4A", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/x-m4a").formatBadge)
        assertEquals("AAC", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/aac").formatBadge)
        assertEquals("OGG", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/ogg").formatBadge)
        assertEquals("OPUS", AudioTrackInfo(uri = "uri", title = "T", mimeType = "audio/opus").formatBadge)

        // Fallback from URI extension
        assertEquals("FLAC", AudioTrackInfo(uri = "content://media/music.flac", title = "T", mimeType = "application/octet-stream").formatBadge)
        assertEquals("WAV", AudioTrackInfo(uri = "content://media/sample.wav", title = "T", mimeType = "application/octet-stream").formatBadge)

        // Fallback from title extension
        assertEquals("MP3", AudioTrackInfo(uri = "content://media/123", title = "sound.mp3", mimeType = "application/octet-stream").formatBadge)
    }

    @Test
    fun displayName_returnsTitleOrFallback() {
        assertEquals("Solaris", AudioTrackInfo(uri = "uri", title = "Solaris").displayName)
        assertEquals("Solaris", AudioTrackInfo(uri = "uri", title = "  Solaris  ").displayName)
        assertEquals("Unknown Track", AudioTrackInfo(uri = "uri", title = "").displayName)
        assertEquals("Unknown Track", AudioTrackInfo(uri = "uri", title = "   ").displayName)
    }

    @Test
    fun hasArtwork_reflectsBytePresence() {
        val noArt = AudioTrackInfo(uri = "uri", title = "T", artworkBytes = null)
        assertFalse(noArt.hasArtwork)

        val emptyArt = AudioTrackInfo(uri = "uri", title = "T", artworkBytes = byteArrayOf())
        assertFalse(emptyArt.hasArtwork)

        val validArt = AudioTrackInfo(uri = "uri", title = "T", artworkBytes = byteArrayOf(1, 2, 3))
        assertTrue(validArt.hasArtwork)
    }

    @Test
    fun equalsAndHashCode_handlesByteArrayContentEquality() {
        val bytes1 = byteArrayOf(10, 20, 30)
        val bytes2 = byteArrayOf(10, 20, 30)
        val bytes3 = byteArrayOf(10, 20, 31)

        val trackA = AudioTrackInfo(uri = "uri1", title = "Song", artworkBytes = bytes1)
        val trackB = AudioTrackInfo(uri = "uri1", title = "Song", artworkBytes = bytes2)
        val trackC = AudioTrackInfo(uri = "uri1", title = "Song", artworkBytes = bytes3)

        assertEquals(trackA, trackB)
        assertEquals(trackA.hashCode(), trackB.hashCode())

        assertNotEquals(trackA, trackC)
    }
}
