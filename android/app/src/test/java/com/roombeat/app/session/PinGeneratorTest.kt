package com.roombeat.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PinGeneratorTest {

    @Test
    fun testGeneratePinProducesValidSixDigitString() {
        val pin = PinGenerator.generate()
        assertNotNull(pin)
        assertEquals(6, pin.length)
        assertTrue("PIN must contain only digits: $pin", pin.all { it in '0'..'9' })
        assertTrue("PIN must be considered valid", PinGenerator.isValidPin(pin))
    }

    @Test
    fun testLeadingZeroPadding() {
        assertEquals("000000", PinGenerator.formatPinNumber(0))
        assertEquals("000001", PinGenerator.formatPinNumber(1))
        assertEquals("000042", PinGenerator.formatPinNumber(42))
        assertEquals("000999", PinGenerator.formatPinNumber(999))
        assertEquals("099999", PinGenerator.formatPinNumber(99999))
        assertEquals("999999", PinGenerator.formatPinNumber(999999))
    }

    @Test
    fun testFormatPinNumberOutOfRangeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            PinGenerator.formatPinNumber(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PinGenerator.formatPinNumber(1_000_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PinGenerator.formatPinNumber(9999999)
        }
    }

    @Test
    fun testIsValidPinValidationLogic() {
        // Valid 6-digit combinations
        assertTrue(PinGenerator.isValidPin("000000"))
        assertTrue(PinGenerator.isValidPin("123456"))
        assertTrue(PinGenerator.isValidPin("999999"))
        assertTrue(PinGenerator.isValidPin("084920"))

        // Invalid inputs
        assertFalse(PinGenerator.isValidPin(null))
        assertFalse(PinGenerator.isValidPin(""))
        assertFalse(PinGenerator.isValidPin(" "))
        assertFalse(PinGenerator.isValidPin("12345"))     // 5 digits
        assertFalse(PinGenerator.isValidPin("1234567"))   // 7 digits
        assertFalse(PinGenerator.isValidPin("12345a"))   // non-digit character
        assertFalse(PinGenerator.isValidPin("12 456"))   // space embedded
        assertFalse(PinGenerator.isValidPin("abcdef"))   // all letters
        assertFalse(PinGenerator.isValidPin("-12345"))   // negative sign
        assertFalse(PinGenerator.isValidPin("+12345"))   // positive sign
    }

    @Test
    fun testFormatForDisplay() {
        assertEquals("123 456", PinGenerator.formatForDisplay("123456"))
        assertEquals("000 000", PinGenerator.formatForDisplay("000000"))
        assertEquals("849 201", PinGenerator.formatForDisplay("849201"))
        assertEquals("849 201", PinGenerator.formatForDisplay("  849201  ")) // Trimmed

        // Unchanged if not 6 characters
        assertEquals("12345", PinGenerator.formatForDisplay("12345"))
        assertEquals("1234567", PinGenerator.formatForDisplay("1234567"))
        assertEquals("", PinGenerator.formatForDisplay(""))
    }

    @Test
    fun testUniformDistributionAndHighEntropyInShortBursts() {
        val sampleSize = 1_000
        val generatedPins = mutableListOf<String>()
        val distinctPins = mutableSetOf<String>()

        val firstDigits = mutableSetOf<Char>()

        for (i in 0 until sampleSize) {
            val pin = PinGenerator.generate()
            assertEquals(6, pin.length)
            assertTrue(PinGenerator.isValidPin(pin))
            generatedPins.add(pin)
            distinctPins.add(pin)
            firstDigits.add(pin[0])
        }

        // In a space of 1,000,000, drawing 1,000 items has collision probability ~0.39,
        // so almost all (>990) should be unique
        assertTrue(
            "Expected at least 990 unique PINs out of 1,000, got ${distinctPins.size}",
            distinctPins.size >= 990
        )

        // All 10 leading digits (0..9) should be represented across 1,000 samples
        assertTrue(
            "Expected high digit diversity across first digits, got ${firstDigits.size}",
            firstDigits.size >= 9
        )
    }

    @Test
    fun testDeterministicSecureRandomInjection() {
        // Mocking PRNG by providing a SecureRandom instance with fixed seed
        val seededRandom = object : SecureRandom() {
            private var counter = 0
            override fun nextInt(bound: Int): Int {
                return (counter++ * 12345) % bound
            }
        }

        val generator = PinGenerator(seededRandom)
        assertEquals("000000", generator.generatePin())
        assertEquals("012345", generator.generatePin())
        assertEquals("024690", generator.generatePin())
    }
}
