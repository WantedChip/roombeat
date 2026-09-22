package com.roombeat.app.network.multicast

import com.roombeat.app.protocol.PacketSerializer
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.protocol.RoomBeatPacket.MulticastProbeReport
import com.roombeat.app.protocol.RoomBeatPacket.MulticastTestBeacon
import com.roombeat.app.system.LockHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MulticastHealthProbeTest {

    private lateinit var testDispatcher: CoroutineDispatcher
    private lateinit var testScope: TestScope

    private class TestLockHandle : LockHandle {
        var isHeldState = false
        val acquireCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)

        override val isHeld: Boolean get() = isHeldState

        override fun acquire() {
            isHeldState = true
            acquireCount.incrementAndGet()
        }

        override fun release() {
            isHeldState = false
            releaseCount.incrementAndGet()
        }

        override fun setReferenceCounted(refCounted: Boolean) {}
    }

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
    }

    // =========================================================================
    // 1. Packet Encoding and Decoding Format (MULTICAST_TEST_BEACON & REPORT)
    // =========================================================================

    @Test
    fun testMulticastTestBeaconEncodingAndDecoding() {
        val original = MulticastTestBeacon(
            seq = 3,
            sessionId = "SESSION-ALPHA",
            timestampMs = 1726000000000L,
            totalBurst = 10
        )

        // Encode to raw bytes
        val bytes = MulticastHealthProbe.encodeBeacon(original)
        assertTrue("Encoded bytes must not be empty", bytes.isNotEmpty())

        val jsonString = String(bytes, Charsets.UTF_8)
        assertTrue(jsonString.contains("\"type\":\"MULTICAST_TEST_BEACON\""))
        assertTrue(jsonString.contains("\"seq\":3"))
        assertTrue(jsonString.contains("\"session_id\":\"SESSION-ALPHA\""))
        assertTrue(jsonString.contains("\"timestamp_ms\":1726000000000"))
        assertTrue(jsonString.contains("\"total_burst\":10"))

        // Decode from bytes
        val decoded = MulticastHealthProbe.decodeBeacon(bytes)
        assertNotNull("Decoded beacon must not be null", decoded)
        assertEquals(original.seq, decoded?.seq)
        assertEquals(original.sessionId, decoded?.sessionId)
        assertEquals(original.timestampMs, decoded?.timestampMs)
        assertEquals(original.totalBurst, decoded?.totalBurst)
        assertEquals(RoomBeatPacket.TYPE_MULTICAST_TEST_BEACON, decoded?.packetType)
    }

    @Test
    fun testMulticastTestBeaconCorruptedPayloadHandling() {
        val corruptedBytes = "{ invalid json payload".toByteArray(Charsets.UTF_8)
        val decoded = MulticastHealthProbe.decodeBeacon(corruptedBytes)
        assertNull("Corrupted payload should safely decode to null without crashing", decoded)

        val emptyBytes = ByteArray(0)
        val decodedEmpty = MulticastHealthProbe.decodeBeacon(emptyBytes)
        assertNull("Empty bytes should decode to null", decodedEmpty)
    }

    @Test
    fun testMulticastProbeReportSerialization() {
        val report = MulticastProbeReport(
            deviceId = "pixel-8-node",
            sessionId = "SESSION-ALPHA",
            receivedCount = 8,
            totalSent = 10,
            receptionRate = 0.80,
            isBlocked = false
        )

        val json = PacketSerializer.serialize(report)
        assertTrue(json.contains("\"type\":\"MULTICAST_PROBE_REPORT\""))
        assertTrue(json.contains("\"device_id\":\"pixel-8-node\""))
        assertTrue(json.contains("\"received_count\":8"))
        assertTrue(json.contains("\"total_sent\":10"))
        assertTrue(json.contains("\"reception_rate\":0.8"))
        assertTrue(json.contains("\"is_blocked\":false"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is MulticastProbeReport)
        assertEquals(report, deserialized)
    }

    // =========================================================================
    // 2. Threshold Evaluation (80% Rule)
    // =========================================================================

    @Test
    fun testEvaluateRateThresholds() {
        // 100% reception evaluates to PASSED
        assertEquals(MulticastHealthStatus.PASSED, MulticastHealthProbe.evaluateRate(1.0))

        // 90% reception evaluates to PASSED
        assertEquals(MulticastHealthStatus.PASSED, MulticastHealthProbe.evaluateRate(0.90))

        // Exactly 80% boundary evaluates to PASSED
        assertEquals(MulticastHealthStatus.PASSED, MulticastHealthProbe.evaluateRate(0.80))

        // 79.9% (< 80%) evaluates to BLOCKED
        assertEquals(MulticastHealthStatus.BLOCKED, MulticastHealthProbe.evaluateRate(0.799))

        // 50% evaluates to BLOCKED
        assertEquals(MulticastHealthStatus.BLOCKED, MulticastHealthProbe.evaluateRate(0.50))

        // 0% evaluates to BLOCKED
        assertEquals(MulticastHealthStatus.BLOCKED, MulticastHealthProbe.evaluateRate(0.0))
    }

    @Test
    fun testEvaluateCountThresholds() {
        // 10/10 -> PASSED
        assertEquals(MulticastHealthStatus.PASSED, MulticastHealthProbe.evaluateCount(10, 10))

        // 8/10 -> PASSED
        assertEquals(MulticastHealthStatus.PASSED, MulticastHealthProbe.evaluateCount(8, 10))

        // 7/10 -> BLOCKED (70% < 80%)
        assertEquals(MulticastHealthStatus.BLOCKED, MulticastHealthProbe.evaluateCount(7, 10))

        // 0/10 -> BLOCKED
        assertEquals(MulticastHealthStatus.BLOCKED, MulticastHealthProbe.evaluateCount(0, 10))

        // Invalid zero total -> BLOCKED
        assertEquals(MulticastHealthStatus.BLOCKED, MulticastHealthProbe.evaluateCount(0, 0))
    }

    // =========================================================================
    // 3. Broadcast Burst Execution
    // =========================================================================

    @Test
    fun testBroadcastProbeBurstSends10TaggedPackets() = testScope.runTest {
        val socket = FakeMulticastSocketWrapper()
        val probe = MulticastHealthProbe(
            sessionId = "TEST-SESSION-1",
            totalProbeCount = 10,
            probeIntervalMs = 50L,
            socketWrapper = socket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        val sentSeqs = mutableListOf<Int>()
        val sentCount = probe.broadcastProbeBurst(onPacketSent = { sentSeqs.add(it) })

        assertEquals(10, sentCount)
        assertEquals(10, socket.sentPackets.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), sentSeqs)

        // Verify each packet content
        for (i in 1..10) {
            val datagram = socket.sentPackets[i - 1]
            assertEquals(MulticastHealthProbe.DEFAULT_MULTICAST_ADDR, datagram.address)
            assertEquals(MulticastHealthProbe.DEFAULT_MULTICAST_PORT, datagram.port)

            val beacon = MulticastHealthProbe.decodeBeacon(datagram.data)
            assertNotNull(beacon)
            assertEquals(i, beacon?.seq)
            assertEquals("TEST-SESSION-1", beacon?.sessionId)
            assertEquals(10, beacon?.totalBurst)
        }
    }

    // =========================================================================
    // 4. Reception Health Evaluation (100% vs < 80% vs 0%)
    // =========================================================================

    @Test
    fun test100PercentReceptionEvaluatesToHealthyPassed() = testScope.runTest {
        val socket = FakeMulticastSocketWrapper()
        val probe = MulticastHealthProbe(
            sessionId = "SESSION-SYNC",
            deviceId = "peer-node-1",
            totalProbeCount = 10,
            timeoutMs = 1000L,
            socketWrapper = socket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        // Enqueue all 10 beacons into the socket
        for (seq in 1..10) {
            socket.enqueueBeacon(
                MulticastTestBeacon(
                    seq = seq,
                    sessionId = "SESSION-SYNC",
                    timestampMs = 1000L + seq * 10,
                    totalBurst = 10
                )
            )
        }

        val report = probe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 500L)

        assertEquals(10, report.receivedCount)
        assertEquals(10, report.totalSent)
        assertEquals(1.0, report.receptionRate, 0.001)
        assertFalse(report.isBlocked)
        assertEquals(MulticastHealthStatus.PASSED, probe.state.value.status)
        assertEquals(MulticastHealthStatus.HEALTHY, probe.healthStatus.value)
        assertNull(probe.state.value.failureReason)
    }

    @Test
    fun testLessThan80PercentReceptionEvaluatesToBlocked() = testScope.runTest {
        val socket = FakeMulticastSocketWrapper()
        val probe = MulticastHealthProbe(
            sessionId = "SESSION-AP-ISOLATION",
            deviceId = "peer-node-2",
            totalProbeCount = 10,
            timeoutMs = 1000L,
            socketWrapper = socket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        // Enqueue only 7 beacons (70% < 80% threshold -> Multicast blocked)
        for (seq in 1..7) {
            socket.enqueueBeacon(
                MulticastTestBeacon(
                    seq = seq,
                    sessionId = "SESSION-AP-ISOLATION",
                    timestampMs = 1000L + seq * 10,
                    totalBurst = 10
                )
            )
        }

        val report = probe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 100L)

        assertEquals(7, report.receivedCount)
        assertEquals(10, report.totalSent)
        assertEquals(0.70, report.receptionRate, 0.001)
        assertTrue(report.isBlocked)
        assertEquals(MulticastHealthStatus.BLOCKED, probe.state.value.status)
        assertEquals(MulticastHealthStatus.MULTICAST_BLOCKED, probe.healthStatus.value)
        assertNotNull(probe.state.value.failureReason)
        assertTrue(probe.state.value.failureReason?.contains("below 80%") == true)
    }

    @Test
    fun testExact80PercentReceptionEvaluatesToPassed() = testScope.runTest {
        val socket = FakeMulticastSocketWrapper()
        val probe = MulticastHealthProbe(
            sessionId = "SESSION-BOUNDARY",
            deviceId = "peer-node-3",
            totalProbeCount = 10,
            socketWrapper = socket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        // Enqueue exactly 8 beacons (80% boundary -> Passed)
        for (seq in 1..8) {
            socket.enqueueBeacon(
                MulticastTestBeacon(
                    seq = seq,
                    sessionId = "SESSION-BOUNDARY",
                    timestampMs = 1000L + seq * 10,
                    totalBurst = 10
                )
            )
        }

        val report = probe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 100L)

        assertEquals(8, report.receivedCount)
        assertEquals(0.80, report.receptionRate, 0.001)
        assertFalse(report.isBlocked)
        assertEquals(MulticastHealthStatus.PASSED, probe.state.value.status)
    }

    @Test
    fun testTimeoutHandlingWhenZeroPacketsReceived() = testScope.runTest {
        val socket = FakeMulticastSocketWrapper()
        val probe = MulticastHealthProbe(
            sessionId = "SESSION-BLOCKED-LAN",
            deviceId = "peer-node-4",
            totalProbeCount = 10,
            socketWrapper = socket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        // Zero packets delivered (AP isolation / IGMP blocking all multicast)
        val report = probe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 50L)

        assertEquals(0, report.receivedCount)
        assertEquals(10, report.totalSent)
        assertEquals(0.0, report.receptionRate, 0.001)
        assertTrue(report.isBlocked)
        assertEquals(MulticastHealthStatus.BLOCKED, probe.state.value.status)
        assertNotNull(probe.state.value.failureReason)
    }

    @Test
    fun testMismatchedSessionIdPacketsIgnored() = testScope.runTest {
        val socket = FakeMulticastSocketWrapper()
        val probe = MulticastHealthProbe(
            sessionId = "MY-SESSION",
            deviceId = "peer-node-5",
            totalProbeCount = 10,
            socketWrapper = socket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        // Enqueue 5 matching packets and 5 packets from an unrelated room session
        for (seq in 1..5) {
            socket.enqueueBeacon(
                MulticastTestBeacon(seq = seq, sessionId = "MY-SESSION", timestampMs = 1000L)
            )
            socket.enqueueBeacon(
                MulticastTestBeacon(seq = seq, sessionId = "FOREIGN-SESSION", timestampMs = 1000L)
            )
        }

        val report = probe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 100L)

        // Only the 5 matching packets must be counted
        assertEquals(5, report.receivedCount)
        assertEquals(0.50, report.receptionRate, 0.001)
        assertTrue(report.isBlocked)
    }

    @Test
    fun testDuplicateSequencePacketsDeduplicated() = testScope.runTest {
        val socket = FakeMulticastSocketWrapper()
        val probe = MulticastHealthProbe(
            sessionId = "DEDUP-SESSION",
            deviceId = "peer-node-6",
            totalProbeCount = 10,
            socketWrapper = socket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        // Enqueue sequence 1 three times (Wi-Fi retry storm simulation) and sequence 2 once
        socket.enqueueBeacon(MulticastTestBeacon(seq = 1, sessionId = "DEDUP-SESSION", timestampMs = 1000L))
        socket.enqueueBeacon(MulticastTestBeacon(seq = 1, sessionId = "DEDUP-SESSION", timestampMs = 1001L))
        socket.enqueueBeacon(MulticastTestBeacon(seq = 1, sessionId = "DEDUP-SESSION", timestampMs = 1002L))
        socket.enqueueBeacon(MulticastTestBeacon(seq = 2, sessionId = "DEDUP-SESSION", timestampMs = 1003L))

        val report = probe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 100L)

        // Distinct received count should be 2, not 4
        assertEquals(2, report.receivedCount)
    }

    // =========================================================================
    // 5. State Machine Transitions
    // =========================================================================

    @Test
    fun testStateMachineTransitions() = testScope.runTest {
        val socket = FakeMulticastSocketWrapper()
        val probe = MulticastHealthProbe(
            sessionId = "STATE-SESSION",
            socketWrapper = socket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        // Initial state must be IDLE
        assertEquals(MulticastHealthStatus.IDLE, probe.state.value.status)
        assertEquals(0, probe.state.value.probesSent)
        assertEquals(0, probe.state.value.probesReceived)

        // Enqueue 10 packets and run listener
        for (i in 1..10) {
            socket.enqueueBeacon(MulticastTestBeacon(seq = i, sessionId = "STATE-SESSION", timestampMs = 1000L))
        }

        probe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 100L)

        // After completion, must transition to PASSED
        assertEquals(MulticastHealthStatus.PASSED, probe.state.value.status)
        assertTrue(probe.state.value.status.isPassed)
        assertFalse(probe.state.value.status.isBlocked)

        // Reset probe back to IDLE
        probe.reset()
        assertEquals(MulticastHealthStatus.IDLE, probe.state.value.status)
        assertEquals(0, probe.state.value.probesReceived)
        assertNull(probe.state.value.failureReason)
    }

    // =========================================================================
    // 6. Host Evaluation of Client Reports
    // =========================================================================

    @Test
    fun testHostEvaluateClientReport() {
        val probe = MulticastHealthProbe(sessionId = "HOST-SESSION")

        val passingReport = MulticastProbeReport(
            deviceId = "client-pixel-8",
            sessionId = "HOST-SESSION",
            receivedCount = 9,
            totalSent = 10,
            receptionRate = 0.90,
            isBlocked = false
        )

        val passStatus = probe.evaluateClientReport(passingReport)
        assertEquals(MulticastHealthStatus.PASSED, passStatus)
        assertEquals(MulticastHealthStatus.PASSED, probe.state.value.status)
        assertNull(probe.state.value.failureReason)

        val blockedReport = MulticastProbeReport(
            deviceId = "client-galaxy-s24",
            sessionId = "HOST-SESSION",
            receivedCount = 6,
            totalSent = 10,
            receptionRate = 0.60,
            isBlocked = true
        )

        val blockedStatus = probe.evaluateClientReport(blockedReport)
        assertEquals(MulticastHealthStatus.BLOCKED, blockedStatus)
        assertEquals(MulticastHealthStatus.BLOCKED, probe.state.value.status)
        assertNotNull(probe.state.value.failureReason)
        assertTrue(probe.state.value.failureReason?.contains("client-galaxy-s24") == true)
    }

    @Test
    fun testHostEvaluateMultipleClientReports() {
        val probe = MulticastHealthProbe(sessionId = "MULTI-CLIENT-SESSION")

        val clientA = MulticastProbeReport(
            deviceId = "client-a",
            sessionId = "MULTI-CLIENT-SESSION",
            receivedCount = 10,
            totalSent = 10,
            receptionRate = 1.0,
            isBlocked = false
        )
        val clientB = MulticastProbeReport(
            deviceId = "client-b",
            sessionId = "MULTI-CLIENT-SESSION",
            receivedCount = 9,
            totalSent = 10,
            receptionRate = 0.9,
            isBlocked = false
        )

        // Both healthy -> PASSED
        val passStatus = probe.evaluateMultipleReports(listOf(clientA, clientB))
        assertEquals(MulticastHealthStatus.PASSED, passStatus)

        // Add client C which is dropped/blocked by router AP isolation (only 2/10 received)
        val clientC = MulticastProbeReport(
            deviceId = "client-c",
            sessionId = "MULTI-CLIENT-SESSION",
            receivedCount = 2,
            totalSent = 10,
            receptionRate = 0.2,
            isBlocked = true
        )

        // Any client failing marks overall room sync BLOCKED
        val blockedStatus = probe.evaluateMultipleReports(listOf(clientA, clientB, clientC))
        assertEquals(MulticastHealthStatus.BLOCKED, blockedStatus)
        assertTrue(probe.state.value.failureReason?.contains("Router AP isolation detected") == true)

        // Empty reports list marks BLOCKED
        val emptyStatus = probe.evaluateMultipleReports(emptyList())
        assertEquals(MulticastHealthStatus.BLOCKED, emptyStatus)
    }

    // =========================================================================
    // 7. MulticastLock Lifecycle Scaffolding
    // =========================================================================

    @Test
    fun testMulticastLockAcquisitionAndReleaseScaffolding() {
        val lock = TestLockHandle()
        val probe = MulticastHealthProbe(
            sessionId = "LOCK-SESSION",
            lockHandle = lock
        )

        assertFalse("Lock must not be held initially", lock.isHeld)

        val acquired = probe.acquireLock()
        assertTrue(acquired)
        assertTrue(lock.isHeld)
        assertEquals(1, lock.acquireCount.get())

        // Re-acquiring already held lock is idempotent
        val reacquired = probe.acquireLock()
        assertTrue(reacquired)
        assertEquals(1, lock.acquireCount.get())

        val released = probe.releaseLock()
        assertTrue(released)
        assertFalse(lock.isHeld)
        assertEquals(1, lock.releaseCount.get())

        // Closing probe releases held lock
        probe.acquireLock()
        assertTrue(lock.isHeld)
        probe.close()
        assertFalse(lock.isHeld)
    }

    // =========================================================================
    // 8. End-to-End Fake Socket Link Simulation
    // =========================================================================

    @Test
    fun testEndToEndHostBurstToClientListenerThroughLinkedSockets() = testScope.runTest {
        val hostSocket = FakeMulticastSocketWrapper()
        val clientSocket = FakeMulticastSocketWrapper()
        hostSocket.link(clientSocket)

        val hostProbe = MulticastHealthProbe(
            sessionId = "E2E-ROOM-42",
            totalProbeCount = 10,
            probeIntervalMs = 10L,
            socketWrapper = hostSocket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        val clientProbe = MulticastHealthProbe(
            sessionId = "E2E-ROOM-42",
            deviceId = "client-pixel",
            totalProbeCount = 10,
            socketWrapper = clientSocket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        // Simulate host sending 10 packets
        hostProbe.broadcastProbeBurst()

        // Client listens and receives all 10 packets routed through link
        val report = clientProbe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 500L)

        assertEquals(10, report.receivedCount)
        assertEquals(1.0, report.receptionRate, 0.001)
        assertEquals(MulticastHealthStatus.PASSED, clientProbe.state.value.status)

        // Host evaluates client's report
        val hostEval = hostProbe.evaluateClientReport(report)
        assertEquals(MulticastHealthStatus.PASSED, hostEval)
    }

    @Test
    fun testEndToEndSimulatedRouterPacketLossThroughLinkedSockets() = testScope.runTest {
        val hostSocket = FakeMulticastSocketWrapper()
        val clientSocket = FakeMulticastSocketWrapper()
        hostSocket.link(clientSocket)

        // Simulate router dropping 50% of multicast packets (odd sequences dropped)
        hostSocket.dropFilter = { seq -> seq % 2 != 0 }

        val hostProbe = MulticastHealthProbe(
            sessionId = "E2E-ROUTER-DROP",
            totalProbeCount = 10,
            probeIntervalMs = 10L,
            socketWrapper = hostSocket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        val clientProbe = MulticastHealthProbe(
            sessionId = "E2E-ROUTER-DROP",
            deviceId = "client-phone",
            totalProbeCount = 10,
            socketWrapper = clientSocket,
            ioDispatcher = testDispatcher,
            scope = testScope
        )

        hostProbe.broadcastProbeBurst()

        val report = clientProbe.listenForProbeBurst(expectedCount = 10, listenDurationMs = 500L)

        // Only even sequences (2, 4, 6, 8, 10) arrived = 5 packets
        assertEquals(5, report.receivedCount)
        assertEquals(0.50, report.receptionRate, 0.001)
        assertTrue(report.isBlocked)
        assertEquals(MulticastHealthStatus.BLOCKED, clientProbe.state.value.status)

        // Host receives the report and detects AP isolation / packet loss
        val hostEval = hostProbe.evaluateClientReport(report)
        assertEquals(MulticastHealthStatus.BLOCKED, hostEval)
    }
}
