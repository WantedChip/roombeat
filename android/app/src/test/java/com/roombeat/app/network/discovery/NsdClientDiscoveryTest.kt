package com.roombeat.app.network.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NsdClientDiscoveryTest {

    private lateinit var fakeWrapper: FakeNsdManagerWrapper
    private lateinit var clientDiscovery: NsdClientDiscovery

    @Before
    fun setUp() {
        fakeWrapper = FakeNsdManagerWrapper()
        clientDiscovery = NsdClientDiscovery(nsdWrapper = fakeWrapper)
    }

    @Test
    fun `initial state is IDLE and room list is empty`() {
        assertEquals(ClientDiscoveryState.IDLE, clientDiscovery.currentState)
        assertEquals(ClientDiscoveryState.IDLE, clientDiscovery.state.value)
        assertTrue(clientDiscovery.discoveredRooms.value.isEmpty())
        assertFalse(fakeWrapper.isDiscoveryListenerHeld)
    }

    @Test
    fun `start discovery initiates scanning and transitions state to DISCOVERING`() {
        val started = clientDiscovery.startDiscovery()

        assertTrue(started)
        assertEquals(ClientDiscoveryState.DISCOVERING, clientDiscovery.currentState)
        assertEquals(NsdClientDiscovery.SERVICE_TYPE, fakeWrapper.activeDiscoveryType)
        assertTrue(fakeWrapper.isDiscoveryListenerHeld)
    }

    @Test
    fun `start discovery is idempotent when already discovering`() {
        assertTrue(clientDiscovery.startDiscovery())
        assertEquals(1, fakeWrapper.discoverCallCount)

        // Second call should return true immediately without creating duplicate listeners
        assertTrue(clientDiscovery.startDiscovery())
        assertEquals(1, fakeWrapper.discoverCallCount)
        assertEquals(ClientDiscoveryState.DISCOVERING, clientDiscovery.currentState)
    }

    @Test
    fun `discovery start failure transitions state to FAILED`() {
        fakeWrapper.shouldFailDiscoveryStart = true
        fakeWrapper.discoveryStartErrorCode = 4

        val started = clientDiscovery.startDiscovery()
        assertFalse(started)
        assertEquals(ClientDiscoveryState.FAILED, clientDiscovery.currentState)
        assertFalse(fakeWrapper.isDiscoveryListenerHeld)
    }

    @Test
    fun `discovered service is resolved and emitted as DiscoveredRoom`() {
        assertTrue(clientDiscovery.startDiscovery())

        val uncuratedRecord = NsdServiceRecord(
            serviceName = "RoomBeat-host1",
            serviceType = NsdClientDiscovery.SERVICE_TYPE,
            attributes = mapOf(
                "sessionId" to "sess-abc",
                "hostDeviceModel" to "Google Pixel 7",
                "version" to "0.2.2"
            )
        )

        // Simulate discovery event
        fakeWrapper.simulateServiceFound(uncuratedRecord)

        // Assert service was enqueued for resolution
        assertEquals(1, fakeWrapper.resolveCallCount)

        // Assert room emitted in StateFlow
        val rooms = clientDiscovery.discoveredRooms.value
        assertEquals(1, rooms.size)
        val room = rooms.first()
        assertEquals("sess-abc", room.sessionId)
        assertEquals("RoomBeat-host1", room.serviceName)
        assertEquals("192.168.1.100", room.hostAddress)
        assertEquals(8080, room.port)
        assertEquals("Google Pixel 7", room.hostDeviceModel)
        assertEquals("0.2.2", room.version)
        assertTrue(room.isValid)
    }

    @Test
    fun `discovered service with missing attributes falls back safely`() {
        assertTrue(clientDiscovery.startDiscovery())

        val minimalRecord = NsdServiceRecord(
            serviceName = "RoomBeat-minimal",
            serviceType = NsdClientDiscovery.SERVICE_TYPE,
            attributes = emptyMap()
        )

        fakeWrapper.simulateServiceFound(minimalRecord)

        val rooms = clientDiscovery.discoveredRooms.value
        assertEquals(1, rooms.size)
        val room = rooms.first()
        // Falls back to serviceName when sessionId attribute is missing
        assertEquals("RoomBeat-minimal", room.sessionId)
        assertEquals("RoomBeat-minimal", room.serviceName)
        assertEquals("", room.hostDeviceModel)
        assertEquals("", room.version)
    }

    @Test
    fun `service loss removes room from discovered rooms flow`() {
        assertTrue(clientDiscovery.startDiscovery())

        val service1 = NsdServiceRecord(
            serviceName = "RoomBeat-1",
            serviceType = NsdClientDiscovery.SERVICE_TYPE,
            attributes = mapOf("sessionId" to "s1")
        )
        val service2 = NsdServiceRecord(
            serviceName = "RoomBeat-2",
            serviceType = NsdClientDiscovery.SERVICE_TYPE,
            attributes = mapOf("sessionId" to "s2")
        )

        fakeWrapper.simulateServiceFound(service1)
        fakeWrapper.simulateServiceFound(service2)

        assertEquals(2, clientDiscovery.discoveredRooms.value.size)

        // Simulate service loss for service 1
        fakeWrapper.simulateServiceLost(service1)

        val roomsAfterLoss = clientDiscovery.discoveredRooms.value
        assertEquals(1, roomsAfterLoss.size)
        assertEquals("s2", roomsAfterLoss.first().sessionId)

        // Simulate service loss for service 2
        fakeWrapper.simulateServiceLost(service2)
        assertTrue(clientDiscovery.discoveredRooms.value.isEmpty())
    }

    @Test
    fun `ignores non-RoomBeat mDNS services`() {
        assertTrue(clientDiscovery.startDiscovery())

        val foreignRecord = NsdServiceRecord(
            serviceName = "Printer-Office",
            serviceType = "_ipp._tcp"
        )
        fakeWrapper.simulateServiceFound(foreignRecord)

        assertEquals(0, fakeWrapper.resolveCallCount)
        assertTrue(clientDiscovery.discoveredRooms.value.isEmpty())
    }

    @Test
    fun `clean discovery stop releases NSD listener and prevents leaks`() {
        // Stopping when idle is safe and returns true
        assertTrue(clientDiscovery.stopDiscovery())
        assertEquals(ClientDiscoveryState.STOPPED, clientDiscovery.currentState)
        assertFalse(fakeWrapper.isDiscoveryListenerHeld)

        // Start discovery
        assertTrue(clientDiscovery.startDiscovery())
        assertTrue(fakeWrapper.isDiscoveryListenerHeld)

        // Stop discovery
        assertTrue(clientDiscovery.stopDiscovery())
        assertEquals(ClientDiscoveryState.STOPPED, clientDiscovery.currentState)
        assertFalse(fakeWrapper.isDiscoveryListenerHeld)

        // Redundant stop call is idempotent
        assertTrue(clientDiscovery.stopDiscovery())
        assertEquals(ClientDiscoveryState.STOPPED, clientDiscovery.currentState)
        assertFalse(fakeWrapper.isDiscoveryListenerHeld)
    }

    @Test
    fun `rapid start and stop cycles maintain state consistency and zero leaks`() {
        for (i in 1..20) {
            val startOk = clientDiscovery.startDiscovery()
            assertTrue("Cycle $i start failed", startOk)
            assertEquals(ClientDiscoveryState.DISCOVERING, clientDiscovery.currentState)
            assertTrue("Cycle $i listener not held", fakeWrapper.isDiscoveryListenerHeld)

            val stopOk = clientDiscovery.stopDiscovery()
            assertTrue("Cycle $i stop failed", stopOk)
            assertEquals(ClientDiscoveryState.STOPPED, clientDiscovery.currentState)
            assertFalse("Cycle $i leaked discovery listener", fakeWrapper.isDiscoveryListenerHeld)
        }
    }

    @Test
    fun `close stops discovery and clears discovered rooms`() {
        clientDiscovery.startDiscovery()
        val record = NsdServiceRecord(
            serviceName = "RoomBeat-close",
            serviceType = NsdClientDiscovery.SERVICE_TYPE,
            attributes = mapOf("sessionId" to "close-1")
        )
        fakeWrapper.simulateServiceFound(record)
        assertEquals(1, clientDiscovery.discoveredRooms.value.size)
        assertTrue(fakeWrapper.isDiscoveryListenerHeld)

        clientDiscovery.close()
        assertEquals(ClientDiscoveryState.STOPPED, clientDiscovery.currentState)
        assertTrue(clientDiscovery.discoveredRooms.value.isEmpty())
        assertFalse(fakeWrapper.isDiscoveryListenerHeld)
    }

    @Test
    fun `service resolution failure does not crash discovery or corrupt room list`() {
        fakeWrapper.shouldFailResolve = true
        fakeWrapper.resolveErrorCode = 9

        assertTrue(clientDiscovery.startDiscovery())

        val failingRecord = NsdServiceRecord(
            serviceName = "RoomBeat-broken",
            serviceType = NsdClientDiscovery.SERVICE_TYPE
        )
        fakeWrapper.simulateServiceFound(failingRecord)

        // Resolve was attempted but failed, so room list remains empty without crashing
        assertEquals(1, fakeWrapper.resolveCallCount)
        assertTrue(clientDiscovery.discoveredRooms.value.isEmpty())
        assertEquals(ClientDiscoveryState.DISCOVERING, clientDiscovery.currentState)
    }
}
