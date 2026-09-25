package com.roombeat.app.source

import android.content.ContentResolver
import android.content.Intent
import android.net.TestUri
import android.net.Uri
import com.roombeat.app.source.local.LocalFilePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFilePickerTest {

    private class FakeContentResolver(
        private val onTakePermission: (Uri, Int) -> Unit = { _, _ -> }
    ) : ContentResolver(null) {
        override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
            onTakePermission(uri, modeFlags)
        }
    }

    @Test
    fun supportedMimeTypes_containsAllRequiredAudioFormats() {
        val types = LocalFilePicker.SUPPORTED_MIME_TYPES.toList()

        assertTrue("Must support audio/*", types.contains("audio/*"))
        assertTrue("Must support audio/mpeg", types.contains("audio/mpeg"))
        assertTrue("Must support audio/flac", types.contains("audio/flac"))
        assertTrue("Must support audio/wav", types.contains("audio/wav"))
        assertTrue("Must support audio/mp4", types.contains("audio/mp4"))
        assertTrue("Must support audio/x-m4a", types.contains("audio/x-m4a"))
        assertTrue("Must support audio/aac", types.contains("audio/aac"))
        assertTrue("Must support audio/ogg", types.contains("audio/ogg"))
    }

    @Test
    fun takePersistableReadPermission_successReturnsTrue() {
        var recordedUri: Uri? = null
        var recordedFlags: Int = 0

        val resolver = FakeContentResolver { uri, flags ->
            recordedUri = uri
            recordedFlags = flags
        }

        val testUri = TestUri("content://com.android.providers.media/audio/42")
        val success = LocalFilePicker.takePersistableReadPermission(resolver, testUri)

        assertTrue(success)
        assertEquals(testUri, recordedUri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, recordedFlags)
    }

    @Test
    fun takePersistableReadPermission_handlesSecurityExceptionGracefully() {
        val resolver = FakeContentResolver { _, _ ->
            throw SecurityException("No persistable permission grant for this URI")
        }

        val testUri = TestUri("content://com.android.providers.media/audio/42")
        val result = LocalFilePicker.takePersistableReadPermission(resolver, testUri)

        assertFalse(result)
    }

    @Test
    fun takePersistableReadPermission_handlesUnexpectedExceptionGracefully() {
        val resolver = FakeContentResolver { _, _ ->
            throw IllegalStateException("Unexpected provider error")
        }

        val testUri = TestUri("content://com.android.providers.media/audio/42")
        val result = LocalFilePicker.takePersistableReadPermission(resolver, testUri)

        assertFalse(result)
    }
}
