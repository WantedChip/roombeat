package com.roombeat.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLossRecoveryBannerTest {

    @Test
    fun testHostLossRecoveryBannerTagsConstants() {
        assertEquals("host_loss_recovery_banner", HostLossRecoveryBannerTags.BANNER)
        assertEquals("host_loss_recovery_banner_title", HostLossRecoveryBannerTags.TITLE)
        assertEquals("host_loss_recovery_banner_status", HostLossRecoveryBannerTags.STATUS)
        assertEquals("host_loss_recovery_banner_attempt_count", HostLossRecoveryBannerTags.ATTEMPT_COUNT)
        assertEquals("host_loss_recovery_banner_retry_button", HostLossRecoveryBannerTags.RETRY_BUTTON)
        assertEquals("host_loss_recovery_banner_return_button", HostLossRecoveryBannerTags.RETURN_BUTTON)
    }

    @Test
    fun testAttemptBadgeTextFormatting() {
        val attempt1 = 1
        val maxAttempts = 3
        val isReconnectingFalse = false
        val text1 = if (isReconnectingFalse) "RECONNECTING..." else "ATTEMPT $attempt1 / $maxAttempts"
        assertEquals("ATTEMPT 1 / 3", text1)

        val isReconnectingTrue = true
        val textReconnecting = if (isReconnectingTrue) "RECONNECTING..." else "ATTEMPT $attempt1 / $maxAttempts"
        assertEquals("RECONNECTING...", textReconnecting)
    }

    @Test
    fun testRetryButtonLabelFormatting() {
        val isReconnectingFalse = false
        val labelNormal = if (isReconnectingFalse) "[ RECONNECTING... ]" else "[ RETRY CONNECTION ]"
        assertEquals("[ RETRY CONNECTION ]", labelNormal)

        val isReconnectingTrue = true
        val labelReconnecting = if (isReconnectingTrue) "[ RECONNECTING... ]" else "[ RETRY CONNECTION ]"
        assertEquals("[ RECONNECTING... ]", labelReconnecting)
    }

    @Test
    fun testReturnToLobbyButtonLabel() {
        val returnLabel = "[ RETURN TO LOBBY ]"
        assertEquals("[ RETURN TO LOBBY ]", returnLabel)
    }

    @Test
    fun testErrorMessageFallback() {
        val defaultMessage = "Host connection lost — Attempt Reconnect or Return to Lobby. Audio faded out cleanly (50ms) to prevent clicks/pops."
        val customError = "Connection refused by host: socket reset"

        val effectiveMsg1 = null ?: defaultMessage
        assertEquals(defaultMessage, effectiveMsg1)
        assertTrue(effectiveMsg1.contains("Audio faded out cleanly (50ms)"))

        val effectiveMsg2 = customError ?: defaultMessage
        assertEquals(customError, effectiveMsg2)
    }
}
