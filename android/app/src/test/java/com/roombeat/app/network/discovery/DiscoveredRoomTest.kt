package com.roombeat.app.network.discovery

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredRoomTest {

    @Test
    fun `test DiscoveredRoom default values and properties`() {
        val now = System.currentTimeMillis()
        val room = DiscoveredRoom(
            sessionId = "sess-1234",
            serviceName = "RoomBeat-sess-1234",
            hostAddress = "192.168.1.50",
            port = 8080,
            hostDeviceModel = "Google Pixel 8",
            attributes = mapOf("version" to "0.2.2", "env" to "test"),
            discoveredAtMs = now
        )

        assertEquals("sess-1234", room.sessionId)
        assertEquals("RoomBeat-sess-1234", room.serviceName)
        assertEquals("192.168.1.50", room.hostAddress)
        assertEquals(8080, room.port)
        assertEquals("Google Pixel 8", room.hostDeviceModel)
        assertEquals("192.168.1.50:8080", room.endpoint)
        assertEquals("0.2.2", room.version)
        assertEquals(now, room.discoveredAtMs)
        assertTrue(room.isValid)
    }

    @Test
    fun `test DiscoveredRoom validity checks`() {
        val validRoom = DiscoveredRoom(
            sessionId = "sess-1",
            serviceName = "RoomBeat-1",
            hostAddress = "10.0.0.1",
            port = 8080
        )
        assertTrue(validRoom.isValid)

        val emptySession = validRoom.copy(sessionId = "   ")
        assertFalse(emptySession.isValid)

        val emptyHost = validRoom.copy(hostAddress = "")
        assertFalse(emptyHost.isValid)

        val invalidPortZero = validRoom.copy(port = 0)
        assertFalse(invalidPortZero.isValid)

        val invalidPortNegative = validRoom.copy(port = -1)
        assertFalse(invalidPortNegative.isValid)

        val invalidPortTooHigh = validRoom.copy(port = 65536)
        assertFalse(invalidPortTooHigh.isValid)
    }

    @Test
    fun `test DiscoveredRoom version fallback when absent`() {
        val room = DiscoveredRoom(
            sessionId = "sess-1",
            serviceName = "RoomBeat-1",
            hostAddress = "10.0.0.1",
            port = 8080,
            attributes = emptyMap()
        )
        assertEquals("", room.version)
    }

    @Test
    fun `test DiscoveredRoom serialization roundtrip`() {
        val room = DiscoveredRoom(
            sessionId = "session-abc",
            serviceName = "RoomBeat-session-abc",
            hostAddress = "192.168.43.1",
            port = 8082,
            hostDeviceModel = "Samsung Galaxy S24",
            attributes = mapOf("sessionId" to "session-abc", "version" to "0.2.2"),
            discoveredAtMs = 1700000000000L
        )

        val json = Json { prettyPrint = false }
        val encoded = json.encodeToString(DiscoveredRoom.serializer(), room)
        val decoded = json.decodeFromString(DiscoveredRoom.serializer(), encoded)

        assertEquals(room, decoded)
        assertEquals("192.168.43.1:8082", decoded.endpoint)
        assertEquals("0.2.2", decoded.version)
    }
}
