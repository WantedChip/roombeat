package com.roombeat.app.network.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NsdHostServiceTest {

    private lateinit var fakeWrapper: FakeNsdManagerWrapper
    private lateinit var hostService: NsdHostService

    @Before
    fun setUp() {
        fakeWrapper = FakeNsdManagerWrapper()
        hostService = NsdHostService(nsdWrapper = fakeWrapper)
    }

    @Test
    fun `initial state is UNREGISTERED`() {
        assertEquals(HostRegistrationState.UNREGISTERED, hostService.currentState)
        assertEquals(HostRegistrationState.UNREGISTERED, hostService.state.value)
        assertNull(hostService.registeredServiceName)
        assertNull(hostService.lastError)
        assertNull(hostService.lastErrorCode)
        assertFalse(fakeWrapper.isRegistrationListenerHeld)
    }

    @Test
    fun `successful service registration publishes TXT record attributes`() {
        val registered = hostService.registerService(
            port = 8080,
            sessionId = "room-xyz",
            hostDeviceModel = "Pixel 8 Pro",
            version = "0.2.2",
            serviceName = "RoomBeat-room-xyz",
            customAttributes = mapOf("stage" to "prod")
        )

        assertTrue(registered)
        assertEquals(HostRegistrationState.REGISTERED, hostService.currentState)
        assertEquals("RoomBeat-room-xyz", hostService.registeredServiceName)
        assertTrue(fakeWrapper.isRegistrationListenerHeld)

        val record = fakeWrapper.registeredRecord
        assertNotNull(record)
        assertEquals("RoomBeat-room-xyz", record?.serviceName)
        assertEquals(NsdHostService.SERVICE_TYPE, record?.serviceType)
        assertEquals(8080, record?.port)
        assertEquals("room-xyz", record?.attributes?.get("sessionId"))
        assertEquals("Pixel 8 Pro", record?.attributes?.get("hostDeviceModel"))
        assertEquals("0.2.2", record?.attributes?.get("version"))
        assertEquals("prod", record?.attributes?.get("stage"))
    }

    @Test
    fun `service registration handles mDNS name collision rename`() {
        fakeWrapper.autoRegisterSuccess = false
        val registered = hostService.registerService(
            port = 8080,
            sessionId = "collision-test",
            serviceName = "RoomBeat-collision"
        )
        assertTrue(registered)
        assertEquals(HostRegistrationState.REGISTERING, hostService.currentState)

        // Simulate mDNS daemon renaming the service to resolve conflict
        val collisionRenamedRecord = NsdServiceRecord(
            serviceName = "RoomBeat-collision (1)",
            serviceType = NsdHostService.SERVICE_TYPE,
            port = 8080
        )
        fakeWrapper.simulateServiceRegistered(collisionRenamedRecord)

        assertEquals(HostRegistrationState.REGISTERED, hostService.currentState)
        assertEquals("RoomBeat-collision (1)", hostService.registeredServiceName)
    }

    @Test
    fun `registration failure transitions state to FAILED with error details`() {
        fakeWrapper.autoRegisterSuccess = false
        val started = hostService.registerService(
            port = 8080,
            sessionId = "fail-session"
        )
        assertTrue(started)
        assertEquals(HostRegistrationState.REGISTERING, hostService.currentState)

        fakeWrapper.simulateRegistrationFailed(errorCode = 2)

        assertEquals(HostRegistrationState.FAILED, hostService.currentState)
        assertEquals(2, hostService.lastErrorCode)
        assertNotNull(hostService.lastError)
        assertTrue(hostService.lastError!!.contains("2"))
        assertFalse(fakeWrapper.isRegistrationListenerHeld)
    }

    @Test
    fun `immediate registration initiation failure transitions state to FAILED`() {
        fakeWrapper.shouldFailRegistration = true
        fakeWrapper.registrationErrorCode = 5

        val started = hostService.registerService(
            port = 8080,
            sessionId = "fail-immediate"
        )
        assertFalse(started)
        assertEquals(HostRegistrationState.FAILED, hostService.currentState)
        assertFalse(fakeWrapper.isRegistrationListenerHeld)
    }

    @Test
    fun `cannot register again while already registered or registering`() {
        assertTrue(hostService.registerService(port = 8080, sessionId = "session-1"))
        assertEquals(HostRegistrationState.REGISTERED, hostService.currentState)

        // Attempt second registration while registered
        val secondAttempt = hostService.registerService(port = 8081, sessionId = "session-2")
        assertFalse(secondAttempt)
        assertEquals(HostRegistrationState.REGISTERED, hostService.currentState)
        assertEquals("RoomBeat-session-1", hostService.registeredServiceName)
    }

    @Test
    fun `clean and idempotent unregistration releases listener without leaks`() {
        // Unregistering when already unregistered is safe and returns true
        assertTrue(hostService.unregisterService())
        assertEquals(HostRegistrationState.UNREGISTERED, hostService.currentState)
        assertFalse(fakeWrapper.isRegistrationListenerHeld)

        // Register
        assertTrue(hostService.registerService(port = 8080, sessionId = "leak-test"))
        assertEquals(HostRegistrationState.REGISTERED, hostService.currentState)
        assertTrue(fakeWrapper.isRegistrationListenerHeld)

        // Unregister
        assertTrue(hostService.unregisterService())
        assertEquals(HostRegistrationState.UNREGISTERED, hostService.currentState)
        assertNull(hostService.registeredServiceName)
        assertFalse(fakeWrapper.isRegistrationListenerHeld)

        // Redundant unregistration call should be idempotent and not crash
        assertTrue(hostService.unregisterService())
        assertEquals(HostRegistrationState.UNREGISTERED, hostService.currentState)
        assertFalse(fakeWrapper.isRegistrationListenerHeld)
    }

    @Test
    fun `rapid register and unregister cycles maintain state integrity and zero leaks`() {
        for (i in 1..20) {
            val regSuccess = hostService.registerService(
                port = 8080 + i,
                sessionId = "rapid-$i"
            )
            assertTrue("Cycle $i register failed", regSuccess)
            assertEquals(HostRegistrationState.REGISTERED, hostService.currentState)
            assertTrue(fakeWrapper.isRegistrationListenerHeld)

            val unregSuccess = hostService.unregisterService()
            assertTrue("Cycle $i unregister failed", unregSuccess)
            assertEquals(HostRegistrationState.UNREGISTERED, hostService.currentState)
            assertFalse("Cycle $i leaked registration listener", fakeWrapper.isRegistrationListenerHeld)
        }
    }

    @Test
    fun `close unregisters service cleanly`() {
        hostService.registerService(port = 8080, sessionId = "close-test")
        assertTrue(fakeWrapper.isRegistrationListenerHeld)

        hostService.close()
        assertEquals(HostRegistrationState.UNREGISTERED, hostService.currentState)
        assertFalse(fakeWrapper.isRegistrationListenerHeld)
    }
}
