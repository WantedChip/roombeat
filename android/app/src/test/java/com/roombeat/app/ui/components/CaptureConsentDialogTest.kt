package com.roombeat.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureConsentDialogTest {

    @Test
    fun testCaptureConsentDialogModeEnumValues() {
        val modes = CaptureConsentDialogMode.values()
        assertEquals(2, modes.size)
        assertNotNull(CaptureConsentDialogMode.valueOf("EXPLANATION"))
        assertNotNull(CaptureConsentDialogMode.valueOf("REJECTED"))
    }

    @Test
    fun testCaptureConsentDialogModeProperties() {
        val explanation = CaptureConsentDialogMode.EXPLANATION
        val rejected = CaptureConsentDialogMode.REJECTED

        assertEquals("EXPLANATION", explanation.name)
        assertEquals("REJECTED", rejected.name)
    }

    @Test
    fun testCaptureConsentDialogState_DefaultInitialization() {
        val state = CaptureConsentDialogState()
        assertFalse(state.isOpen)
        assertEquals(CaptureConsentDialogMode.EXPLANATION, state.mode)
    }

    @Test
    fun testCaptureConsentDialogState_CustomInitialization() {
        val state = CaptureConsentDialogState(initialOpen = true, initialMode = CaptureConsentDialogMode.REJECTED)
        assertTrue(state.isOpen)
        assertEquals(CaptureConsentDialogMode.REJECTED, state.mode)
    }

    @Test
    fun testCaptureConsentDialogState_ShowExplanation() {
        val state = CaptureConsentDialogState(initialOpen = false, initialMode = CaptureConsentDialogMode.REJECTED)
        state.showExplanation()

        assertTrue(state.isOpen)
        assertEquals(CaptureConsentDialogMode.EXPLANATION, state.mode)
    }

    @Test
    fun testCaptureConsentDialogState_ShowRejected() {
        val state = CaptureConsentDialogState(initialOpen = false, initialMode = CaptureConsentDialogMode.EXPLANATION)
        state.showRejected()

        assertTrue(state.isOpen)
        assertEquals(CaptureConsentDialogMode.REJECTED, state.mode)
    }

    @Test
    fun testCaptureConsentDialogState_Dismiss() {
        val state = CaptureConsentDialogState(initialOpen = true, initialMode = CaptureConsentDialogMode.EXPLANATION)
        state.dismiss()

        assertFalse(state.isOpen)
        assertEquals(CaptureConsentDialogMode.EXPLANATION, state.mode)
    }

    @Test
    fun testCaptureConsentDialogState_LifecycleSequence() {
        val state = CaptureConsentDialogState()

        // 1. Initial closed
        assertFalse(state.isOpen)

        // 2. Open explanation before system prompt
        state.showExplanation()
        assertTrue(state.isOpen)
        assertEquals(CaptureConsentDialogMode.EXPLANATION, state.mode)

        // 3. User cancels system prompt -> show rejected recovery mode
        state.showRejected()
        assertTrue(state.isOpen)
        assertEquals(CaptureConsentDialogMode.REJECTED, state.mode)

        // 4. User cancels out of recovery
        state.dismiss()
        assertFalse(state.isOpen)

        // 5. User taps capture again -> show explanation
        state.showExplanation()
        assertTrue(state.isOpen)
        assertEquals(CaptureConsentDialogMode.EXPLANATION, state.mode)
    }
}
