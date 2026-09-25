package com.roombeat.app.source

import android.content.Context
import android.content.ContextWrapper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.TestUri
import com.roombeat.app.source.local.AudioMetadataExtractor
import com.roombeat.app.source.local.ContentQueryFacade
import com.roombeat.app.source.local.MetadataRetrieverFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMetadataExtractorTest {

    private class FakeContext : ContextWrapper(null)

    private class FakeMetadataRetriever(
        private val metadataMap: Map<Int, String> = emptyMap(),
        private val pictureBytes: ByteArray? = null,
        private val shouldThrowOnSetData: Boolean = false
    ) : MetadataRetrieverFacade {
        var isClosed: Boolean = false
        var wasDataSourceSet: Boolean = false

        override fun setDataSource(context: Context, uri: Uri) {
            if (shouldThrowOnSetData) {
                throw IllegalArgumentException("Corrupted audio data source")
            }
            wasDataSourceSet = true
        }

        override fun extractMetadata(keyCode: Int): String? = metadataMap[keyCode]

        override fun getEmbeddedPicture(): ByteArray? = pictureBytes

        override fun close() {
            isClosed = true
        }
    }

    private class FakeContentQuery(
        private val displayName: String? = null,
        private val fileSize: Long? = null,
        private val mimeType: String? = null
    ) : ContentQueryFacade {
        override fun queryDisplayNameAndSize(context: Context, uri: Uri): Pair<String?, Long?> =
            Pair(displayName, fileSize)

        override fun getMimeType(context: Context, uri: Uri): String? = mimeType
    }

    @Test
    fun stripExtension_removesVariousExtensionsCorrectly() {
        assertEquals("song_track", AudioMetadataExtractor.stripExtension("song_track.mp3"))
        assertEquals("my.sound.track", AudioMetadataExtractor.stripExtension("my.sound.track.flac"))
        assertEquals("audio_file", AudioMetadataExtractor.stripExtension("audio_file.wav"))
        assertEquals("recording", AudioMetadataExtractor.stripExtension("recording.m4a"))
        assertEquals("archive.tar", AudioMetadataExtractor.stripExtension("archive.tar.gz"))
        assertEquals("no_extension", AudioMetadataExtractor.stripExtension("no_extension"))
        assertEquals(".hidden_file", AudioMetadataExtractor.stripExtension(".hidden_file"))
        assertEquals("", AudioMetadataExtractor.stripExtension(""))
        assertEquals("", AudioMetadataExtractor.stripExtension("   "))
    }

    @Test
    fun extractFromUri_extractsCompleteMetadataSuccessfully() {
        val fakeRetriever = FakeMetadataRetriever(
            metadataMap = mapOf(
                MediaMetadataRetriever.METADATA_KEY_TITLE to "Hyperion",
                MediaMetadataRetriever.METADATA_KEY_ARTIST to "Gesaffelstein",
                MediaMetadataRetriever.METADATA_KEY_ALBUM to "Aleph",
                MediaMetadataRetriever.METADATA_KEY_DURATION to "248000",
                MediaMetadataRetriever.METADATA_KEY_SAMPLERATE to "48000",
                31 to "2", // channels
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE to "audio/flac"
            ),
            pictureBytes = byteArrayOf(1, 2, 3, 4)
        )
        val fakeContent = FakeContentQuery(
            displayName = "01_Hyperion.flac",
            fileSize = 15200300L,
            mimeType = "audio/flac"
        )
        val extractor = AudioMetadataExtractor(
            retrieverFactory = { fakeRetriever },
            contentQueryFacade = fakeContent
        )

        val uri = TestUri("content://com.android.providers.media/audio/100", "01_Hyperion.flac")
        val track = extractor.extractFromUri(FakeContext(), uri)

        assertEquals("Hyperion", track.title)
        assertEquals("Gesaffelstein", track.artist)
        assertEquals("Aleph", track.album)
        assertEquals(248000L, track.durationMs)
        assertEquals("4:08", track.formattedDuration)
        assertEquals(48000, track.sampleRate)
        assertEquals("48.0 kHz", track.formattedSampleRate)
        assertEquals(2, track.channelCount)
        assertEquals("Stereo", track.formattedChannels)
        assertEquals("audio/flac", track.mimeType)
        assertEquals("FLAC", track.formatBadge)
        assertEquals(15200300L, track.fileSizeBytes)
        assertTrue(track.hasArtwork)
        assertNotNull(track.artworkBytes)
        assertTrue(fakeRetriever.isClosed)
    }

    @Test
    fun extractFromUri_fallsBackToContentResolverFilenameWhenTitleMissing() {
        val fakeRetriever = FakeMetadataRetriever(
            metadataMap = mapOf(
                MediaMetadataRetriever.METADATA_KEY_DURATION to "180000"
            )
        )
        val fakeContent = FakeContentQuery(
            displayName = "midnight_drive.mp3",
            fileSize = 5400000L,
            mimeType = "audio/mpeg"
        )
        val extractor = AudioMetadataExtractor(
            retrieverFactory = { fakeRetriever },
            contentQueryFacade = fakeContent
        )

        val uri = TestUri("content://com.android.providers.media/audio/200", "midnight_drive.mp3")
        val track = extractor.extractFromUri(FakeContext(), uri)

        assertEquals("midnight_drive", track.title)
        assertEquals("midnight_drive", track.displayName)
        assertNull(track.artist)
        assertNull(track.album)
        assertEquals(180000L, track.durationMs)
        assertEquals(48000, track.sampleRate)
        assertEquals("MP3", track.formatBadge)
    }

    @Test
    fun extractFromUri_fallsBackToUriSegmentWhenContentDisplayNameMissing() {
        val fakeRetriever = FakeMetadataRetriever(
            metadataMap = emptyMap()
        )
        val fakeContent = FakeContentQuery(
            displayName = null,
            fileSize = null,
            mimeType = null
        )
        val extractor = AudioMetadataExtractor(
            retrieverFactory = { fakeRetriever },
            contentQueryFacade = fakeContent
        )

        val uri = TestUri("content://com.android.providers.media/audio/ambient_track.wav", "ambient_track.wav")
        val track = extractor.extractFromUri(FakeContext(), uri)

        assertEquals("ambient_track", track.title)
        assertEquals(0L, track.durationMs)
        assertEquals("0:00", track.formattedDuration)
        assertEquals(48000, track.sampleRate)
        assertEquals(2, track.channelCount)
    }

    @Test
    fun extractFromUri_handlesBlankWhitespaceTagsCleanly() {
        val fakeRetriever = FakeMetadataRetriever(
            metadataMap = mapOf(
                MediaMetadataRetriever.METADATA_KEY_TITLE to "   ",
                MediaMetadataRetriever.METADATA_KEY_ARTIST to "   ",
                MediaMetadataRetriever.METADATA_KEY_ALBUM to ""
            )
        )
        val fakeContent = FakeContentQuery(
            displayName = "clean_name.flac"
        )
        val extractor = AudioMetadataExtractor(
            retrieverFactory = { fakeRetriever },
            contentQueryFacade = fakeContent
        )

        val uri = TestUri("content://com.android.providers.media/audio/300")
        val track = extractor.extractFromUri(FakeContext(), uri)

        assertEquals("clean_name", track.title)
        assertNull(track.artist)
        assertNull(track.album)
    }

    @Test
    fun extractFromUri_handlesCorruptFileGracefullyWithoutThrowing() {
        val fakeRetriever = FakeMetadataRetriever(
            shouldThrowOnSetData = true
        )
        val fakeContent = FakeContentQuery(
            displayName = "corrupted_audio.wav",
            fileSize = 1024L,
            mimeType = "audio/wav"
        )
        val extractor = AudioMetadataExtractor(
            retrieverFactory = { fakeRetriever },
            contentQueryFacade = fakeContent
        )

        val uri = TestUri("content://com.android.providers.media/audio/999", "corrupted_audio.wav")
        val track = extractor.extractFromUri(FakeContext(), uri)

        assertEquals("corrupted_audio", track.title)
        assertEquals(0L, track.durationMs)
        assertEquals(48000, track.sampleRate)
        assertEquals(2, track.channelCount)
        assertEquals("audio/wav", track.mimeType)
        assertEquals("WAV", track.formatBadge)
    }
}
