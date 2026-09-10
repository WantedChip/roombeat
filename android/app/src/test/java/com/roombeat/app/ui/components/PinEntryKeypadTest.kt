package com.roombeat.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinEntryKeypadTest {

    @Test
    fun testInitialState() {
        val state = PinEntryState()
        assertEquals("", state.pin)
        assertFalse(state.isError)
        assertEquals(6, state.maxDigits)
    }

    @Test
    fun testAppendDigitsSequentially() {
        val state = PinEntryState()

        assertTrue(state.appendDigit('1'))
        assertEquals("1", state.pin)

        assertTrue(state.appendDigit('2'))
        assertEquals("12", state.pin)

        assertTrue(state.appendDigit('3'))
        assertEquals("123", state.pin)
    }

    @Test
    fun testNonDigitIgnored() {
        val state = PinEntryState()

        assertFalse(state.appendDigit('a'))
        assertFalse(state.appendDigit('Z'))
        assertFalse(state.appendDigit(' '))
        assertFalse(state.appendDigit('-'))
        assertFalse(state.appendDigit('.'))

        assertEquals("", state.pin)
    }

    @Test
    fun testFullCodeSubmission() {
        var completedPin: String? = null
        val state = PinEntryState(onPinComplete = { completedPin = it })

        state.appendDigit('8')
        state.appendDigit('4')
        state.appendDigit('9')
        state.appendDigit('2')
        state.appendDigit('0')
        assertEquals(null, completedPin)

        // 6th digit should trigger onPinComplete callback
        state.appendDigit('1')
        assertEquals("849201", state.pin)
        assertEquals("849201", completedPin)
    }

    @Test
    fun testBoundaryDigitsLimit() {
        val state = PinEntryState()
        for (i in 1..6) {
            assertTrue(state.appendDigit(('0' + i)))
        }
        assertEquals("123456", state.pin)

        // Attempting to add 7th digit must be rejected
        assertFalse(state.appendDigit('7'))
        assertEquals("123456", state.pin)
    }

    @Test
    fun testBackspaceRemovesLastDigit() {
        val state = PinEntryState(initialPin = "1234")
        assertEquals("1234", state.pin)

        assertTrue(state.backspace())
        assertEquals("123", state.pin)

        assertTrue(state.backspace())
        assertEquals("12", state.pin)

        assertTrue(state.backspace())
        assertEquals("1", state.pin)

        assertTrue(state.backspace())
        assertEquals("", state.pin)

        // Backspace on empty PIN returns false
        assertFalse(state.backspace())
        assertEquals("", state.pin)
    }

    @Test
    fun testClearRemovesAllDigits() {
        val state = PinEntryState(initialPin = "98765")
        assertEquals("98765", state.pin)

        assertTrue(state.clear())
        assertEquals("", state.pin)

        // Clear on already empty PIN returns false
        assertFalse(state.clear())
        assertEquals("", state.pin)
    }

    @Test
    fun testTriggerErrorClearsPinAndSetsErrorFlag() {
        val state = PinEntryState(initialPin = "5555")
        assertFalse(state.isError)

        state.triggerError(clearPinOnError = true)
        assertTrue(state.isError)
        assertEquals("", state.pin)

        state.resetError()
        assertFalse(state.isError)
    }

    @Test
    fun testTriggerErrorWithoutClearingPin() {
        val state = PinEntryState(initialPin = "123456")
        state.triggerError(clearPinOnError = false)
        assertTrue(state.isError)
        assertEquals("123456", state.pin)
    }

    @Test
    fun testAppendStringHelper() {
        val state = PinEntryState()
        assertTrue(state.append("7"))
        assertEquals("7", state.pin)

        // Multi-char string rejected
        assertFalse(state.append("89"))
        assertEquals("7", state.pin)

        // Empty string rejected
        assertFalse(state.append(""))
        assertEquals("7", state.pin)
    }
}
