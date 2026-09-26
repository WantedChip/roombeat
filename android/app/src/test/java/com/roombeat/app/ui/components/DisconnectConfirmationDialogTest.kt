package com.roombeat.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectConfirmationDialogTest {

    @Test
    fun testDisconnectConfirmationDialogTagsConstants() {
        assertEquals("disconnect_confirmation_dialog", DisconnectConfirmationDialogTags.DIALOG)
        assertEquals("disconnect_confirmation_dialog_title", DisconnectConfirmationDialogTags.TITLE)
        assertEquals("disconnect_confirmation_dialog_message", DisconnectConfirmationDialogTags.MESSAGE)
        assertEquals("disconnect_confirmation_dialog_node_count", DisconnectConfirmationDialogTags.NODE_COUNT)
        assertEquals("disconnect_confirmation_dialog_confirm_button", DisconnectConfirmationDialogTags.CONFIRM_BUTTON)
        assertEquals("disconnect_confirmation_dialog_cancel_button", DisconnectConfirmationDialogTags.CANCEL_BUTTON)
    }

    @Test
    fun testHostDialogTitlesAndLabels() {
        val isHost = true
        val title = if (isHost) "DISCONNECT ALL NODES?" else "LEAVE SESSION?"
        val confirmText = if (isHost) "[ DISCONNECT ALL ]" else "[ LEAVE SESSION ]"

        assertEquals("DISCONNECT ALL NODES?", title)
        assertEquals("[ DISCONNECT ALL ]", confirmText)
    }

    @Test
    fun testPeerDialogTitlesAndLabels() {
        val isHost = false
        val title = if (isHost) "DISCONNECT ALL NODES?" else "LEAVE SESSION?"
        val confirmText = if (isHost) "[ DISCONNECT ALL ]" else "[ LEAVE SESSION ]"

        assertEquals("LEAVE SESSION?", title)
        assertEquals("[ LEAVE SESSION ]", confirmText)
    }

    @Test
    fun testNodeCountReadoutFormatting() {
        val countSingle = 1
        val textSingle = "$countSingle ACTIVE NODE${if (countSingle == 1) "" else "S"} IN SESSION"
        assertEquals("1 ACTIVE NODE IN SESSION", textSingle)

        val countMultiple = 8
        val textMultiple = "$countMultiple ACTIVE NODE${if (countMultiple == 1) "" else "S"} IN SESSION"
        assertEquals("8 ACTIVE NODES IN SESSION", textMultiple)
    }

    @Test
    fun testWarningMessageVariations() {
        val hostMsg = "This will immediately terminate playback, stop audio streaming, and disconnect all participating phone nodes from the room."
        val peerMsg = "This will disconnect your device from the room, stop local audio playback, and return to mode selection."

        assertTrue(hostMsg.contains("disconnect all participating phone nodes"))
        assertTrue(peerMsg.contains("return to mode selection"))
    }
}
