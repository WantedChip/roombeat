package com.roombeat.app.sync

import com.roombeat.app.protocol.RoomBeatPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProbeSample] 4-timestamp model, RTT, and clock offset calculations.
 */
class ProbeSampleTest {

    @Test
    fun testSymmetricNetworkDelayZeroOffset() {
        // Host dispatch T0 = 1000µs (1.0ms)
        // Outbound transit = 5000µs (5.0ms) -> Peer arrival T1 = 6000µs (6.0ms)
        // Peer processing = 1000µs (1.0ms) -> Peer echo departure T2 = 7000µs (7.0ms)
        // Inbound transit = 5000µs (5.0ms) -> Host echo receipt T3 = 12000µs (12.0ms)
        val sample = ProbeSample(
            sequenceNumber = 1L,
            peerId = "peer-alpha",
            t0 = 1_000L,
            t1 = 6_000L,
            t2 = 7_000L,
            t3 = 12_000L
        )

        // Host elapsed: 12000 - 1000 = 11000µs (11.0ms)
        assertEquals(11_000L, sample.hostElapsedMicros)
        assertEquals(11.0, sample.hostElapsedMs, 0.001)

        // Peer turnaround: 7000 - 6000 = 1000µs (1.0ms)
        assertEquals(1_000L, sample.peerProcessingMicros)
        assertEquals(1.0, sample.peerProcessingMs, 0.001)

        // RTT = (T3 - T0) - (T2 - T1) = 11000 - 1000 = 10000µs (10.0ms)
        assertEquals(10_000L, sample.roundTripTimeMicros)
        assertEquals(10.0, sample.roundTripTimeMs, 0.001)

        // Clock offset = ((T1 - T0) + (T2 - T3)) / 2 = ((6000 - 1000) + (7000 - 12000)) / 2
        //              = (5000 - 5000) / 2 = 0.0µs
        assertEquals(0.0, sample.clockOffsetMicros, 0.001)
        assertEquals(0.0, sample.clockOffsetMs, 0.001)

        assertTrue(sample.isValid)
        assertTrue(sample.isStrictlyChronological)
    }

    @Test
    fun testPeerClockAheadPositiveOffset() {
        // Outbound transit = 4000µs (4.0ms), Inbound transit = 4000µs (4.0ms), RTT = 8.0ms
        // Peer turnaround = 2000µs (2.0ms)
        // Peer clock is 15.0ms (15000µs) ahead of Host clock
        // T0 = 10_000µs
        // T1 = 10_000 + 4_000 (transit) + 15_000 (offset) = 29_000µs
        // T2 = 29_000 + 2_000 (turnaround) = 31_000µs
        // T3 = 10_000 + 4_000 (outbound) + 2_000 (peer) + 4_000 (inbound) = 20_000µs
        val sample = ProbeSample(
            sequenceNumber = 42L,
            peerId = "peer-beta",
            t0 = 10_000L,
            t1 = 29_000L,
            t2 = 31_000L,
            t3 = 20_000L
        )

        // RTT = (20000 - 10000) - (31000 - 29000) = 10000 - 2000 = 8000µs = 8.0ms
        assertEquals(8_000L, sample.roundTripTimeMicros)
        assertEquals(8.0, sample.roundTripTimeMs, 0.001)

        // Offset = ((29000 - 10000) + (31000 - 20000)) / 2 = (19000 + 11000) / 2 = 30000 / 2 = +15000µs = +15.0ms
        assertEquals(15_000.0, sample.clockOffsetMicros, 0.001)
        assertEquals(15.0, sample.clockOffsetMs, 0.001)

        assertTrue(sample.isValid)
        // T2 (31000) > T3 (20000) because peer clock is ahead, so strictly chronological on raw values is false
        assertFalse(sample.isStrictlyChronological)
    }

    @Test
    fun testPeerClockBehindNegativeOffset() {
        // Outbound transit = 6000µs (6.0ms), Inbound transit = 6000µs (6.0ms), RTT = 12.0ms
        // Peer turnaround = 1500µs (1.5ms)
        // Peer clock is 10.0ms (10000µs) behind Host clock
        // T0 = 50_000µs
        // T1 = 50_000 + 6_000 - 10_000 = 46_000µs
        // T2 = 46_000 + 1_500 = 47_500µs
        // T3 = 50_000 + 6_000 + 1_500 + 6_000 = 63_500µs
        val sample = ProbeSample(
            sequenceNumber = 5L,
            peerId = "peer-gamma",
            t0 = 50_000L,
            t1 = 46_000L,
            t2 = 47_500L,
            t3 = 63_500L
        )

        // RTT = (63500 - 50000) - (47500 - 46000) = 13500 - 1500 = 12000µs = 12.0ms
        assertEquals(12_000L, sample.roundTripTimeMicros)
        assertEquals(12.0, sample.roundTripTimeMs, 0.001)

        // Offset = ((46000 - 50000) + (47500 - 63500)) / 2 = (-4000 + -16000) / 2 = -20000 / 2 = -10000µs = -10.0ms
        assertEquals(-10_000.0, sample.clockOffsetMicros, 0.001)
        assertEquals(-10.0, sample.clockOffsetMs, 0.001)

        assertTrue(sample.isValid)
        // T1 (46000) < T0 (50000) because peer clock is behind
        assertFalse(sample.isStrictlyChronological)
    }

    @Test
    fun testNegativeRttClampedToZero() {
        // In rare timer jitter edge cases where peer processing time is recorded larger than host elapsed time
        val sample = ProbeSample(
            sequenceNumber = 10L,
            peerId = "peer-jitter",
            t0 = 1_000L,
            t1 = 2_000L,
            t2 = 5_000L, // Peer turnaround = 3000µs
            t3 = 3_000L  // Host elapsed = 2000µs -> raw RTT = 2000 - 3000 = -1000µs
        )

        assertEquals(0L, sample.roundTripTimeMicros)
        assertEquals(0.0, sample.roundTripTimeMs, 0.001)
    }

    @Test
    fun testValidityCausalityChecks() {
        // Valid case: T3 >= T0 and T2 >= T1
        val validSample = ProbeSample(1L, "p1", t0 = 100L, t1 = 200L, t2 = 250L, t3 = 400L)
        assertTrue(validSample.isValid)

        // Invalid: T3 < T0 (time went backward on Host)
        val invalidHostSample = ProbeSample(2L, "p1", t0 = 500L, t1 = 200L, t2 = 250L, t3 = 400L)
        assertFalse(invalidHostSample.isValid)

        // Invalid: T2 < T1 (time went backward on Peer)
        val invalidPeerSample = ProbeSample(3L, "p1", t0 = 100L, t1 = 300L, t2 = 250L, t3 = 400L)
        assertFalse(invalidPeerSample.isValid)
    }

    @Test
    fun testConversionsAndFactoryMethods() {
        // From nanos factory
        val sample = ProbeSample.fromNanos(
            sequenceNumber = 7L,
            peerId = "p2",
            t0Nanos = 1_000_000_000L,
            t1Nanos = 1_005_000_000L,
            t2Nanos = 1_006_000_000L,
            t3Nanos = 1_011_000_000L
        )

        assertEquals(1_000_000L, sample.t0)
        assertEquals(1_005_000L, sample.t1)
        assertEquals(1_006_000L, sample.t2)
        assertEquals(1_011_000L, sample.t3)

        assertEquals(1_000_000_000L, sample.t0Nanos)
        assertEquals(1_005_000_000L, sample.t1Nanos)
        assertEquals(1_006_000_000L, sample.t2Nanos)
        assertEquals(1_011_000_000L, sample.t3Nanos)

        assertEquals(1_000L, sample.t0Millis)
        assertEquals(1_005L, sample.t1Millis)
        assertEquals(1_006L, sample.t2Millis)
        assertEquals(1_011L, sample.t3Millis)

        // From CalibEcho packet factory
        val echo = RoomBeatPacket.CalibEcho(t0 = 2_000L, t1 = 2_010L, t2 = 2_012L)
        val fromEchoSample = ProbeSample.fromEcho(
            sequenceNumber = 8L,
            peerId = "p3",
            echo = echo,
            t3 = 2_020L
        )

        assertEquals(8L, fromEchoSample.sequenceNumber)
        assertEquals("p3", fromEchoSample.peerId)
        assertEquals(2_000L, fromEchoSample.t0)
        assertEquals(2_010L, fromEchoSample.t1)
        assertEquals(2_012L, fromEchoSample.t2)
        assertEquals(2_020L, fromEchoSample.t3)
    }
}
