package com.roombeat.app.session

import java.security.SecureRandom
import java.util.Locale

/**
 * Cryptographically unpredictable 6-digit numeric PIN generator adhering to Roadmap §8.
 *
 * Provides:
 * - Unpredictable 6-digit PIN generation in range [000000..999999] using [SecureRandom].
 * - Monospaced formatting helpers (e.g. splitting into "123 456" for high-readability displays).
 * - Strict 6-digit numeric validation helper.
 */
class PinGenerator(
    private val random: SecureRandom = SecureRandom()
) {

    /**
     * Generates an unpredictable 6-digit numeric PIN string formatted with leading zeros.
     * Guaranteed to be within "000000".."999999" (1,000,000 combinations).
     */
    fun generatePin(): String {
        val pinInt = random.nextInt(PIN_BOUND)
        return formatPinNumber(pinInt)
    }

    companion object {
        const val PIN_LENGTH = 6
        const val PIN_BOUND = 1_000_000

        private val defaultInstance = PinGenerator()

        /**
         * Convenience singleton generator using default [SecureRandom].
         */
        fun generate(): String = defaultInstance.generatePin()

        /**
         * Formats an integer into a zero-padded 6-digit string.
         */
        fun formatPinNumber(number: Int): String {
            require(number in 0 until PIN_BOUND) {
                "PIN number out of range [0, 999999]: $number"
            }
            return String.format(Locale.US, "%06d", number)
        }

        /**
         * Formats a 6-digit PIN string with a tactile space delimiter in the middle
         * (e.g. "849201" -> "849 201") for optimal legibility in telemetry displays and keypads.
         * If the input is not 6 digits, returns the raw input unchanged.
         */
        fun formatForDisplay(code: String): String {
            val clean = code.trim()
            return if (clean.length == PIN_LENGTH) {
                "${clean.substring(0, 3)} ${clean.substring(3, 6)}"
            } else {
                clean
            }
        }

        /**
         * Validates whether a candidate string is a valid 6-digit numeric PIN.
         * Returns true if and only if the string contains exactly 6 ASCII decimal digits ('0'..'9').
         */
        fun isValidPin(code: String?): Boolean {
            if (code == null || code.length != PIN_LENGTH) return false
            return code.all { it in '0'..'9' }
        }
    }
}
