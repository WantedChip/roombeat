package com.roombeat.app.ui.components

import com.roombeat.app.source.spotify.SpotifyAuthErrorType
import com.roombeat.app.source.spotify.SpotifyAuthState
import com.roombeat.app.source.spotify.SpotifyConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyGuidanceDialogTest {

    @Test
    fun testDialogStateInitialDefaults() {
        val state = SpotifyGuidanceDialogState()
        assertFalse(state.isVisible)
        assertNull(state.currentError)
    }

    @Test
    fun testDialogStateShowErrorAndDismiss() {
        val state = SpotifyGuidanceDialogState()
        val error = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.APP_NOT_INSTALLED,
            message = "Spotify app not installed"
        )

        state.showError(error)
        assertTrue(state.isVisible)
        assertEquals(error, state.currentError)

        state.dismiss()
        assertFalse(state.isVisible)
        assertEquals(error, state.currentError) // Error preserved for post-dismiss inspection
    }

    @Test
    fun testSpotifyConstantsValues() {
        assertEquals("com.spotify.music", SpotifyConstants.SPOTIFY_PACKAGE_NAME)
        assertEquals("roombeat-android-client", SpotifyConstants.DEFAULT_CLIENT_ID)
        assertEquals("roombeat://spotify-callback", SpotifyConstants.DEFAULT_REDIRECT_URI)
        assertEquals("market://details?id=com.spotify.music", SpotifyConstants.PLAY_STORE_URI)
        assertTrue(SpotifyConstants.PLAY_STORE_WEB_URL.contains("play.google.com"))
        assertTrue(SpotifyConstants.DEFAULT_CONNECT_TIMEOUT_MS > 0L)
    }

    @Test
    fun testErrorTypeProperties() {
        val appNotInstalled = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.APP_NOT_INSTALLED,
            message = "App missing"
        )
        assertTrue(appNotInstalled.isAppMissing)
        assertFalse(appNotInstalled.isAuthDenied)
        assertFalse(appNotInstalled.isNotLoggedIn)
        assertFalse(appNotInstalled.isRecoverableWithRetry)

        val notLoggedIn = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.NOT_LOGGED_IN,
            message = "Login required"
        )
        assertFalse(notLoggedIn.isAppMissing)
        assertTrue(notLoggedIn.isNotLoggedIn)
        assertTrue(notLoggedIn.isRecoverableWithRetry)

        val userDenied = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.USER_NOT_AUTHORIZED,
            message = "Auth cancelled"
        )
        assertTrue(userDenied.isAuthDenied)
        assertTrue(userDenied.isRecoverableWithRetry)

        val timeout = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.TIMEOUT,
            message = "Timeout"
        )
        assertTrue(timeout.isTimeout)
        assertTrue(timeout.isRecoverableWithRetry)
    }
}
