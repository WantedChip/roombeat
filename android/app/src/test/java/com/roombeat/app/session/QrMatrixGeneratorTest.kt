package com.roombeat.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrMatrixGeneratorTest {

    @Test
    fun testPayloadSerializationRoundtrip() {
        val original = QrSessionPayload(
            code = "849201",
            host = "192.168.1.105",
            port = 8080,
            session = "session-test-alpha-99"
        )

        val json = QrMatrixGenerator.encodeSessionPayload(original)
        assertNotNull(json)
        assertTrue(json.contains("\"code\":\"849201\""))
        assertTrue(json.contains("\"host\":\"192.168.1.105\""))
        assertTrue(json.contains("\"port\":8080"))
        assertTrue(json.contains("\"session\":\"session-test-alpha-99\""))

        val parsed = QrMatrixGenerator.parseSessionPayload(json)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun testParseSessionPayloadValidationSafeguards() {
        // Null / blank
        assertNull(QrMatrixGenerator.parseSessionPayload(null))
        assertNull(QrMatrixGenerator.parseSessionPayload(""))
        assertNull(QrMatrixGenerator.parseSessionPayload("   "))

        // Malformed JSON
        assertNull(QrMatrixGenerator.parseSessionPayload("{not valid json}"))
        assertNull(QrMatrixGenerator.parseSessionPayload("42"))

        // Invalid PIN code (<6 digits or non-digits)
        assertNull(
            QrMatrixGenerator.parseSessionPayload(
                """{"code":"123","host":"192.168.1.5","port":8080,"session":"s1"}"""
            )
        )
        assertNull(
            QrMatrixGenerator.parseSessionPayload(
                """{"code":"12345a","host":"192.168.1.5","port":8080,"session":"s1"}"""
            )
        )

        // Invalid blank host
        assertNull(
            QrMatrixGenerator.parseSessionPayload(
                """{"code":"123456","host":"","port":8080,"session":"s1"}"""
            )
        )

        // Invalid port bounds (0 or >65535)
        assertNull(
            QrMatrixGenerator.parseSessionPayload(
                """{"code":"123456","host":"192.168.1.5","port":0,"session":"s1"}"""
            )
        )
        assertNull(
            QrMatrixGenerator.parseSessionPayload(
                """{"code":"123456","host":"192.168.1.5","port":70000,"session":"s1"}"""
            )
        )

        // Blank session id
        assertNull(
            QrMatrixGenerator.parseSessionPayload(
                """{"code":"123456","host":"192.168.1.5","port":8080,"session":""}"""
            )
        )

        // Missing required field
        assertNull(
            QrMatrixGenerator.parseSessionPayload(
                """{"code":"123456","host":"192.168.1.5","port":8080}"""
            )
        )

        // Forward compatibility: unknown extra fields safely ignored
        val withExtra = """{"code":"654321","host":"10.0.0.4","port":8088,"session":"s2","extra_field":"ignored_data"}"""
        val parsedWithExtra = QrMatrixGenerator.parseSessionPayload(withExtra)
        assertNotNull(parsedWithExtra)
        assertEquals("654321", parsedWithExtra?.code)
        assertEquals("10.0.0.4", parsedWithExtra?.host)
        assertEquals(8088, parsedWithExtra?.port)
        assertEquals("s2", parsedWithExtra?.session)
    }

    @Test
    fun testEncodeToBitMatrixProducesValidMatrix() {
        val payload = QrSessionPayload(
            code = "123456",
            host = "192.168.0.1",
            port = 8080,
            session = "sess-01"
        )
        val json = QrMatrixGenerator.encodeSessionPayload(payload)
        val matrix = QrMatrixGenerator.encodeToBitMatrix(json, width = 256, height = 256)

        assertNotNull(matrix)
        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)

        // Verify there is a mix of dark and light modules
        var trueCount = 0
        var falseCount = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) trueCount++ else falseCount++
            }
        }

        assertTrue("Matrix should contain black modules", trueCount > 0)
        assertTrue("Matrix should contain white modules", falseCount > 0)
    }

    @Test
    fun testEncodeToBitMatrixInvalidInputsThrow() {
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrixGenerator.encodeToBitMatrix("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrixGenerator.encodeToBitMatrix("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrixGenerator.encodeToBitMatrix("test", width = 0, height = 256)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrixGenerator.encodeToBitMatrix("test", width = 256, height = -10)
        }
    }

    @Test
    fun testGenerateMatrixProducesValidBooleanArray() {
        val payload = QrSessionPayload(
            code = "998877",
            host = "192.168.4.1",
            port = 8081,
            session = "sess-gamma"
        )

        val matrix = QrMatrixGenerator.generateMatrix(payload, size = 128)
        assertEquals(128, matrix.size)

        var darkModules = 0
        var lightModules = 0

        for (row in matrix) {
            assertEquals(128, row.size)
            for (module in row) {
                if (module) darkModules++ else lightModules++
            }
        }

        assertTrue("2D boolean matrix must have dark modules", darkModules > 0)
        assertTrue("2D boolean matrix must have light modules", lightModules > 0)
    }

    @Test
    fun testMatrixSizeConsistency() {
        val testSizes = listOf(64, 128, 256)
        for (size in testSizes) {
            val matrix = QrMatrixGenerator.generateMatrix("ROOMBEAT:TEST:$size", size = size)
            assertEquals(size, matrix.size)
            assertEquals(size, matrix[0].size)
        }
    }
}
