package com.roombeat.app.source.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthStateTest {

    @Test
    fun testDisconnectedState() {
        val state: SpotifyAuthState = SpotifyAuthState.Disconnected
        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
        assertFalse(state.isError)
        assertNull(state.errorTypeOrNull)
    }

    @Test
    fun testConnectingState() {
        val now = 1_000_000L
        val state = SpotifyAuthState.Connecting(startedAtMs = now)
        assertEquals(now, state.startedAtMs)
        assertFalse(state.isConnected)
        assertTrue(state.isConnecting)
        assertFalse(state.isError)
        assertNull(state.errorTypeOrNull)
    }

    @Test
    fun testConnectedState() {
        val now = 2_000_000L
        val state = SpotifyAuthState.Connected(connectedAtMs = now)
        assertEquals(now, state.connectedAtMs)
        assertTrue(state.isConnected)
        assertFalse(state.isConnecting)
        assertFalse(state.isError)
        assertNull(state.errorTypeOrNull)
    }

    @Test
    fun testErrorAppNotInstalled() {
        val state = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.APP_NOT_INSTALLED,
            message = "Spotify app not found"
        )
        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
        assertTrue(state.isError)
        assertEquals(SpotifyAuthErrorType.APP_NOT_INSTALLED, state.errorTypeOrNull)
        assertTrue(state.isAppMissing)
        assertFalse(state.isAuthDenied)
        assertFalse(state.isNotLoggedIn)
        assertFalse(state.isTimeout)
        assertFalse(state.isRecoverableWithRetry)
    }

    @Test
    fun testErrorNotLoggedIn() {
        val state = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.NOT_LOGGED_IN,
            message = "User not logged into Spotify"
        )
        assertTrue(state.isNotLoggedIn)
        assertFalse(state.isAppMissing)
        assertFalse(state.isAuthDenied)
        assertTrue(state.isRecoverableWithRetry)
    }

    @Test
    fun testErrorUserNotAuthorized() {
        val state = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.USER_NOT_AUTHORIZED,
            message = "User cancelled auth"
        )
        assertTrue(state.isAuthDenied)
        assertFalse(state.isAppMissing)
        assertFalse(state.isNotLoggedIn)
        assertTrue(state.isRecoverableWithRetry)
    }

    @Test
    fun testErrorAuthenticationFailed() {
        val cause = RuntimeException("Signature mismatch")
        val state = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.AUTHENTICATION_FAILED,
            message = "Auth failed",
            cause = cause
        )
        assertTrue(state.isAuthDenied)
        assertEquals(cause, state.cause)
        assertTrue(state.isRecoverableWithRetry)
    }

    @Test
    fun testErrorTimeout() {
        val state = SpotifyAuthState.Error(
            errorType = SpotifyAuthErrorType.TIMEOUT,
            message = "Connection timed out"
        )
        assertTrue(state.isTimeout)
        assertFalse(state.isAppMissing)
        assertTrue(state.isRecoverableWithRetry)
    }

    @Test
    fun testAllErrorTypesValues() {
        val expected = listOf(
            SpotifyAuthErrorType.APP_NOT_INSTALLED,
            SpotifyAuthErrorType.NOT_LOGGED_IN,
            SpotifyAuthErrorType.USER_NOT_AUTHORIZED,
            SpotifyAuthErrorType.AUTHENTICATION_FAILED,
            SpotifyAuthErrorType.OFFLINE_MODE,
            SpotifyAuthErrorType.CONNECTION_TERMINATED,
            SpotifyAuthErrorType.TIMEOUT,
            SpotifyAuthErrorType.UNKNOWN
        )
        assertEquals(expected.size, SpotifyAuthErrorType.values().size)
    }
}
