package com.roombeat.app.sync

import com.roombeat.app.protocol.RoomBeatPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [CalibrationProbeEngine] verifying NTP probe bursts, 4-timestamp exchange,
 * packet loss tracking, timestamp ordering verification, and multi-peer concurrency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationProbeEngineTest {

    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
    }

    /**
     * Test transport double that can route packets directly between engines,
     * inject network latency, or drop specific packets to simulate packet loss.
     */
    private class TestLoopbackTransport(
        val dropPredicate: ((peerId: String, packet: RoomBeatPacket) -> Boolean)? = null,
        val onPacketSent: ((peerId: String, packet: RoomBeatPacket) -> Unit)? = null
    ) : CalibrationTransport {
        var targetEngine: CalibrationProbeEngine? = null
        val dispatchedPackets = CopyOnWriteArrayList<Pair<String, RoomBeatPacket>>()

        override fun sendPacket(peerId: String, packet: RoomBeatPacket): Boolean {
            dispatchedPackets.add(peerId to packet)
            onPacketSent?.invoke(peerId, packet)

            if (dropPredicate?.invoke(peerId, packet) == true) {
                // Packet dropped on simulated network
                return true
            }

            targetEngine?.handleIncomingPacket(peerId, packet)
            return true
        }
    }

    // =========================================================================
    // 1. Host and Peer 50 Probe Exchanges in Under 5 Seconds
    // =========================================================================

    @Test
    fun testFiftyProbeExchangesCompletesUnderFiveSeconds() = runTest(testDispatcher) {
        val clock = FakeMonotonicClock(initialNanos = 1_000_000_000L, autoIncrementNanos = 100_000L) // 100µs per call

        lateinit var hostEngine: CalibrationProbeEngine
        lateinit var peerEngine: CalibrationProbeEngine

        val hostTransport = TestLoopbackTransport()
        val peerTransport = TestLoopbackTransport()

        hostEngine = CalibrationProbeEngine(
            isHost = true,
            clock = clock,
            transport = hostTransport,
            probeCount = 50,
            probeIntervalMs = 0L, // Rapid zero-delay dispatch for instant deterministic test
            probeTimeoutMs = 200L
        )

        peerEngine = CalibrationProbeEngine(
            isHost = false,
            clock = clock,
            transport = peerTransport
        )

        // Wire loopback routing
        hostTransport.targetEngine = peerEngine
        peerTransport.targetEngine = hostEngine

        val startTime = System.currentTimeMillis()
        val result = hostEngine.runCalibrationBurst("peer-node-1")
        val elapsedRealMs = System.currentTimeMillis() - startTime

        // Verification 1: Completed under 5 seconds (in fact << 1 second)
        assertTrue("Burst duration must be under 5000ms: actual=$elapsedRealMs ms", elapsedRealMs < 5000L)

        // Verification 2: All 50 probes sent and received
        assertEquals(50, result.totalProbesSent)
        assertEquals(50, result.successfulSamples.size)
        assertEquals(0, result.lostProbes)
        assertEquals(0.0, result.packetLossRate, 0.001)
        assertTrue(result.isSuccessful)

        // Verification 3: Sequential sequence numbers 0..49
        for (i in 0 until 50) {
            val sample = result.successfulSamples[i]
            assertEquals(i.toLong(), sample.sequenceNumber)
            assertEquals("peer-node-1", sample.peerId)
            assertTrue("Sample #$i must be valid causality", sample.isValid)
            assertTrue("Sample #$i must be chronological", sample.isStrictlyChronological)
            assertTrue("T3 >= T0", sample.t3 >= sample.t0)
            assertTrue("T2 >= T1", sample.t2 >= sample.t1)
        }
    }

    // =========================================================================
    // 2. Packet Loss Tracking & Reporting Per Peer
    // =========================================================================

    @Test
    fun testPacketLossTrackingPartialDrop() = runTest(testDispatcher) {
        val clock = FakeMonotonicClock(initialNanos = 1_000_000_000L, autoIncrementNanos = 50_000L)

        val droppedCount = AtomicInteger(0)
        // Drop every 5th probe packet (10 probes dropped out of 50 = 20% loss)
        val hostTransport = TestLoopbackTransport(
            dropPredicate = { _, packet ->
                if (packet is RoomBeatPacket.CalibProbe) {
                    val drop = (droppedCount.getAndIncrement() % 5) == 0
                    drop
                } else false
            }
        )
        val peerTransport = TestLoopbackTransport()

        val hostEngine = CalibrationProbeEngine(
            isHost = true,
            clock = clock,
            transport = hostTransport,
            probeCount = 50,
            probeIntervalMs = 0L,
            probeTimeoutMs = 50L
        )

        val peerEngine = CalibrationProbeEngine(
            isHost = false,
            clock = clock,
            transport = peerTransport
        )

        hostTransport.targetEngine = peerEngine
        peerTransport.targetEngine = hostEngine

        val lostCallbackProbes = CopyOnWriteArrayList<Long>()
        hostEngine.listener = object : CalibrationProbeListener {
            override fun onProbeLost(peerId: String, sequenceNumber: Long, t0: Long) {
                lostCallbackProbes.add(sequenceNumber)
            }
        }

        val result = hostEngine.runCalibrationBurst("peer-loss-test")

        assertEquals(50, result.totalProbesSent)
        assertEquals(40, result.successfulSamples.size)
        assertEquals(10, result.lostProbes)
        assertEquals(0.20, result.packetLossRate, 0.001)
        assertEquals(20.0, result.packetLossPercent, 0.001)
        assertEquals(10, lostCallbackProbes.size)

        // Check peer stats aggregation
        val stats = hostEngine.getPeerStats("peer-loss-test")
        assertEquals(50, stats.totalProbesSent)
        assertEquals(40, stats.samplesReceived)
        assertEquals(10, stats.probesLost)
        assertEquals(0.20, stats.packetLossRate, 0.001)
    }

    @Test
    fun testPacketLossTrackingTotalFailure() = runTest(testDispatcher) {
        val clock = FakeMonotonicClock(initialNanos = 1_000_000_000L)

        // Drop 100% of packets (peer offline or unreachable)
        val hostTransport = TestLoopbackTransport(dropPredicate = { _, _ -> true })

        val hostEngine = CalibrationProbeEngine(
            isHost = true,
            clock = clock,
            transport = hostTransport,
            probeCount = 50,
            probeIntervalMs = 0L,
            probeTimeoutMs = 50L
        )

        val result = hostEngine.runCalibrationBurst("unreachable-peer")

        assertEquals(50, result.totalProbesSent)
        assertEquals(0, result.successfulSamples.size)
        assertEquals(50, result.lostProbes)
        assertEquals(1.0, result.packetLossRate, 0.001)
        assertEquals(100.0, result.packetLossPercent, 0.001)
        assertFalse(result.isSuccessful)
    }

    // =========================================================================
    // 3. Timestamp Ordering & RTT/Offset Calculations
    // =========================================================================

    @Test
    fun testTimestampOrderingAndCalculationsWithSimulatedOffset() = runTest(testDispatcher) {
        val hostClock = FakeMonotonicClock(initialNanos = 10_000_000_000L) // 10s
        val peerClock = FakeMonotonicClock(initialNanos = 10_015_000_000L) // Peer clock is ahead by 15.0ms (15,000,000ns)

        val hostEngine = CalibrationProbeEngine(isHost = true, clock = hostClock)
        val peerEngine = CalibrationProbeEngine(isHost = false, clock = peerClock)

        val logs = CopyOnWriteArrayList<String>()
        hostEngine.logger = { logs.add(it) }

        // Step 1: Host dispatches probe #0
        // T0 on host = 10_000_000 µs
        val t0 = hostClock.nowMicros()
        val probe = RoomBeatPacket.CalibProbe(t0 = t0)

        // Wire: Outbound latency = 4.0ms (4000µs)
        hostClock.advanceMicros(4_000L)
        peerClock.advanceMicros(4_000L)

        // Step 2: Peer receives probe and returns echo
        // T1 on peer = 10_015_000 + 4_000 = 10_019_000 µs
        // Peer processing delay = 1.0ms (1000µs)
        peerClock.advanceMicros(1_000L)
        hostClock.advanceMicros(1_000L)

        val echoPacket = RoomBeatPacket.CalibEcho(
            t0 = probe.t0,
            t1 = peerClock.nowMicros() - 1_000L,
            t2 = peerClock.nowMicros()
        )

        // Wire: Inbound latency = 4.0ms (4000µs)
        hostClock.advanceMicros(4_000L)
        peerClock.advanceMicros(4_000L)

        // Step 3: Host receives echo at T3
        // T3 on host = 10_000_000 + 4000 + 1000 + 4000 = 10_009_000 µs
        val t3 = hostClock.nowMicros()
        val sample = ProbeSample(
            sequenceNumber = 0L,
            peerId = "peer-offset",
            t0 = echoPacket.t0,
            t1 = echoPacket.t1,
            t2 = echoPacket.t2,
            t3 = t3
        )

        // RTT = (T3 - T0) - (T2 - T1) = (10009000 - 10000000) - (10020000 - 10019000) = 9000 - 1000 = 8000µs = 8.0ms
        assertEquals(8.0, sample.roundTripTimeMs, 0.001)

        // Offset = ((T1 - T0) + (T2 - T3)) / 2
        //        = ((10019000 - 10000000) + (10020000 - 10009000)) / 2
        //        = (19000 + 11000) / 2 = 30000 / 2 = +15000µs = +15.0ms
        assertEquals(15.0, sample.clockOffsetMs, 0.001)
        assertTrue(sample.isValid)
    }

    @Test
    fun testLoggingVerification() = runTest(testDispatcher) {
        val clock = FakeMonotonicClock(initialNanos = 1_000_000_000L, autoIncrementNanos = 10_000L)
        val logs = CopyOnWriteArrayList<String>()

        val transport = TestLoopbackTransport()
        val hostEngine = CalibrationProbeEngine(
            isHost = true,
            clock = clock,
            transport = transport,
            probeCount = 5,
            probeIntervalMs = 0L,
            logger = { logs.add(it) }
        )
        val peerEngine = CalibrationProbeEngine(isHost = false, clock = clock)
        transport.targetEngine = peerEngine

        // Set peer transport back to host
        val peerTransport = TestLoopbackTransport()
        peerEngine.transport = peerTransport
        peerTransport.targetEngine = hostEngine

        hostEngine.runCalibrationBurst("peer-logged")

        assertTrue("Logger must capture events", logs.isNotEmpty())
        val echoLog = logs.firstOrNull { it.contains("Calibration Echo") }
        assertNotNull("Must log calibration echo with timestamps", echoLog)
        assertTrue(echoLog!!.contains("T0="))
        assertTrue(echoLog.contains("T1="))
        assertTrue(echoLog.contains("T2="))
        assertTrue(echoLog.contains("T3="))
        assertTrue(echoLog.contains("RTT="))
        assertTrue(echoLog.contains("Offset="))
    }

    // =========================================================================
    // 4. Concurrent Multi-Peer Bursts
    // =========================================================================

    @Test
    fun testConcurrentMultiPeerCalibrationBursts() = runTest(testDispatcher) {
        val clock = FakeMonotonicClock(initialNanos = 1_000_000_000L, autoIncrementNanos = 10_000L)

        val peers = listOf("peer-node-A", "peer-node-B", "peer-node-C")
        val peerEngines = ConcurrentHashMap<String, CalibrationProbeEngine>()

        lateinit var hostEngine: CalibrationProbeEngine

        val hostTransport = CalibrationTransport { peerId, packet ->
            peerEngines[peerId]?.handleIncomingPacket(peerId, packet)
            true
        }

        hostEngine = CalibrationProbeEngine(
            isHost = true,
            clock = clock,
            transport = hostTransport,
            probeCount = 50,
            probeIntervalMs = 0L,
            probeTimeoutMs = 100L
        )

        for (peerId in peers) {
            val peerEngine = CalibrationProbeEngine(
                isHost = false,
                clock = clock,
                transport = { _, packet ->
                    hostEngine.handleIncomingPacket(peerId, packet)
                    true
                }
            )
            peerEngines[peerId] = peerEngine
        }

        val results = hostEngine.runCalibrationBurstAll(peers)

        assertEquals(3, results.size)
        for (peerId in peers) {
            val res = results[peerId]
            assertNotNull(res)
            assertEquals(50, res!!.totalProbesSent)
            assertEquals(50, res.successfulSamples.size)
            assertEquals(0, res.lostProbes)
            assertEquals(0.0, res.packetLossRate, 0.001)
        }

        val allStats = hostEngine.getAllPeerStats()
        assertEquals(3, allStats.size)
    }

    // =========================================================================
    // 5. Listener Callbacks & Lifecycle
    // =========================================================================

    @Test
    fun testListenerCallbacksTriggered() = runTest(testDispatcher) {
        val clock = FakeMonotonicClock(initialNanos = 1_000_000_000L, autoIncrementNanos = 10_000L)

        val dispatchedCount = AtomicInteger(0)
        val receivedCount = AtomicInteger(0)
        val progressCount = AtomicInteger(0)
        var completedResult: CalibrationBurstResult? = null

        val hostTransport = TestLoopbackTransport()
        val peerTransport = TestLoopbackTransport()

        val hostEngine = CalibrationProbeEngine(
            isHost = true,
            clock = clock,
            transport = hostTransport,
            probeCount = 10,
            probeIntervalMs = 0L,
            listener = object : CalibrationProbeListener {
                override fun onProbeDispatched(peerId: String, sequenceNumber: Long, t0: Long) {
                    dispatchedCount.incrementAndGet()
                }

                override fun onSampleReceived(sample: ProbeSample) {
                    receivedCount.incrementAndGet()
                }

                override fun onBurstProgress(peerId: String, completed: Int, total: Int) {
                    progressCount.incrementAndGet()
                }

                override fun onBurstCompleted(result: CalibrationBurstResult) {
                    completedResult = result
                }
            }
        )

        val peerEngine = CalibrationProbeEngine(isHost = false, clock = clock, transport = peerTransport)
        hostTransport.targetEngine = peerEngine
        peerTransport.targetEngine = hostEngine

        hostEngine.runCalibrationBurst("listener-peer")

        assertEquals(10, dispatchedCount.get())
        assertEquals(10, receivedCount.get())
        assertEquals(10, progressCount.get())
        assertNotNull(completedResult)
        assertEquals(10, completedResult!!.successfulSamples.size)
    }

    @Test
    fun testClearPeerAndReset() = runTest(testDispatcher) {
        val clock = FakeMonotonicClock(initialNanos = 1_000_000_000L, autoIncrementNanos = 10_000L)

        val hostTransport = TestLoopbackTransport()
        val peerTransport = TestLoopbackTransport()

        val hostEngine = CalibrationProbeEngine(
            isHost = true,
            clock = clock,
            transport = hostTransport,
            probeCount = 5,
            probeIntervalMs = 0L
        )

        val peerEngine = CalibrationProbeEngine(isHost = false, clock = clock, transport = peerTransport)
        hostTransport.targetEngine = peerEngine
        peerTransport.targetEngine = hostEngine

        hostEngine.runCalibrationBurst("peer-reset-1")
        assertEquals(5, hostEngine.getSamplesForPeer("peer-reset-1").size)

        hostEngine.clearPeer("peer-reset-1")
        assertEquals(0, hostEngine.getSamplesForPeer("peer-reset-1").size)

        hostEngine.runCalibrationBurst("peer-reset-2")
        assertEquals(5, hostEngine.getSamplesForPeer("peer-reset-2").size)

        hostEngine.reset()
        assertEquals(0, hostEngine.getSamplesForPeer("peer-reset-2").size)
        assertTrue(hostEngine.getAllPeerStats().isEmpty())

        hostEngine.close()
    }
}
