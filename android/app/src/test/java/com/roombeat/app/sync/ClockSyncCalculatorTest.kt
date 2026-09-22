package com.roombeat.app.sync

import com.roombeat.app.protocol.RoomBeatPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Comprehensive JUnit 4 test suite for [ClockSyncCalculator] and [SyncMetrics].
 *
 * Validates:
 * 1. NTP core equations for RTT and clock offset.
 * 2. Sub-millisecond (< 1.0ms) clock offset accuracy on simulated LAN networks.
 * 3. Outlier rejection reliably filtering out 50-100ms artificial Wi-Fi latency spikes.
 * 4. Positive and negative clock skew estimation.
 * 5. Sample standard deviation jitter against known mathematical distributions.
 * 6. Edge cases: empty samples, single sample, identical RTTs, high packet loss, invalid samples.
 * 7. Moving averages (SMA, EMA), cluster extraction, and protocol CalibResult packaging.
 */
class ClockSyncCalculatorTest {

    private fun createSample(
        seq: Long,
        peerId: String = "peer-01",
        t0Micros: Long,
        transitForwardMicros: Long,
        peerTurnaroundMicros: Long,
        transitReverseMicros: Long,
        peerClockOffsetMicros: Long
    ): ProbeSample {
        // T0: Host dispatch
        val t0 = t0Micros
        // T1: Peer arrival = T0 + transitForward + peerOffset
        val t1 = t0 + transitForwardMicros + peerClockOffsetMicros
        // T2: Peer departure = T1 + turnaround
        val t2 = t1 + peerTurnaroundMicros
        // T3: Host receipt = (T2 - peerOffset) + transitReverse = T0 + transitForward + turnaround + transitReverse
        val t3 = (t2 - peerClockOffsetMicros) + transitReverseMicros

        return ProbeSample(
            sequenceNumber = seq,
            peerId = peerId,
            t0 = t0,
            t1 = t1,
            t2 = t2,
            t3 = t3
        )
    }

    // ==========================================
    // 1. Core NTP Equations
    // ==========================================

    @Test
    fun testNtpCoreFormulas() {
        // T0 = 1000µs, T1 = 4500µs, T2 = 5000µs, T3 = 8500µs
        // Forward: 1000 -> 4500 (delta = 3500)
        // Reverse: 5000 -> 8500 (delta = 3500)
        // Offset: ((4500 - 1000) + (5000 - 8500)) / 2 = (3500 - 3500) / 2 = 0µs
        // RTT: (8500 - 1000) - (5000 - 4500) = 7500 - 500 = 7000µs (7.0ms)
        val offset = ClockSyncCalculator.calculateSampleOffsetMicros(1000L, 4500L, 5000L, 8500L)
        val offsetMs = ClockSyncCalculator.calculateSampleOffsetMs(1000L, 4500L, 5000L, 8500L)
        val rtt = ClockSyncCalculator.calculateSampleRttMicros(1000L, 4500L, 5000L, 8500L)
        val rttMs = ClockSyncCalculator.calculateSampleRttMs(1000L, 4500L, 5000L, 8500L)

        assertEquals(0.0, offset, 0.001)
        assertEquals(0.0, offsetMs, 0.000001)
        assertEquals(7000L, rtt)
        assertEquals(7.0, rttMs, 0.000001)
    }

    // ==========================================
    // 2. Sub-Millisecond Accuracy on LAN Network
    // ==========================================

    @Test
    fun testSubMillisecondAccuracyOnLanNetwork() {
        // True peer clock offset = +0.350 ms (+350 µs), well within the 1.0ms sync threshold
        val trueOffsetMicros = 350L
        val sampleCount = 50
        val samples = mutableListOf<ProbeSample>()

        // Simulate LAN network: baseline 2.5ms RTT with slight jitter +/- 0.3ms
        for (i in 0 until sampleCount) {
            val forwardJitter = ((i * 7) % 300) - 150 // -150 to +150 µs
            val reverseJitter = ((i * 13) % 300) - 150
            val forward = 1250L + forwardJitter
            val reverse = 1250L + reverseJitter
            val turnaround = 400L

            samples.add(
                createSample(
                    seq = i.toLong(),
                    t0Micros = 1_000_000L + (i * 60_000L),
                    transitForwardMicros = forward,
                    peerTurnaroundMicros = turnaround,
                    transitReverseMicros = reverse,
                    peerClockOffsetMicros = trueOffsetMicros
                )
            )
        }

        val metrics = ClockSyncCalculator.calculateSyncMetrics(samples, peerId = "peer-lan")

        // Estimation must achieve < 1.0 ms accuracy (Definition of Done)
        val offsetErrorMs = abs(metrics.offsetMs - 0.350)
        assertTrue("Offset estimation error ($offsetErrorMs ms) must be < 1.0ms", offsetErrorMs < 1.0)
        assertTrue("Sub-millisecond LAN accuracy should be within 0.2ms", offsetErrorMs < 0.2)

        assertTrue("Metrics must be marked synchronized (<1.0ms offset)", metrics.isSynchronized)
        assertTrue("Metrics must be within 1.0ms tolerance", metrics.isWithinTolerance(1.0))
        assertTrue("Jitter must be acceptable on LAN", metrics.hasAcceptableJitter)
        assertEquals(50, metrics.totalSamples)
        assertEquals(40, metrics.acceptedSamples) // 50 - (20% of 50 = 10)
        assertEquals(10, metrics.rejectedSamples)
    }

    @Test
    fun testArbitraryClockOffsetLargeSkew() {
        // Peer clock offset = +14.250 ms before calibration
        val trueOffsetMicros = 14_250L
        val sampleCount = 30
        val samples = (0 until sampleCount).map { i ->
            createSample(
                seq = i.toLong(),
                t0Micros = 2_000_000L + (i * 60_000L),
                transitForwardMicros = 1500L,
                peerTurnaroundMicros = 300L,
                transitReverseMicros = 1500L,
                peerClockOffsetMicros = trueOffsetMicros
            )
        }

        val metrics = ClockSyncCalculator.calculateSyncMetrics(samples, peerId = "peer-skew")
        val errorMs = abs(metrics.offsetMs - 14.250)
        assertTrue("Estimation error ($errorMs ms) must be < 0.1ms", errorMs < 0.1)
        assertFalse("Uncalibrated offset >1ms is not marked synchronized", metrics.isSynchronized)
        assertTrue("Offset is within 15.0ms tolerance", metrics.isWithinTolerance(15.0))
        assertFalse("Offset is not within 1.0ms tolerance", metrics.isWithinTolerance(1.0))
    }

    // ==========================================
    // 3. Outlier Filtering of Wi-Fi Spikes
    // ==========================================

    @Test
    fun testOutlierRejectionOfWifiSpikes() {
        // True peer offset: +8.0 ms (+8,000 µs)
        val trueOffsetMicros = 8_000L
        val samples = mutableListOf<ProbeSample>()

        // 40 clean samples with ~3.0ms RTT (1.5ms forward, 1.5ms reverse)
        for (i in 0 until 40) {
            val jitter = ((i * 5) % 100) - 50 // +/- 50µs
            samples.add(
                createSample(
                    seq = i.toLong(),
                    t0Micros = 10_000_000L + (i * 50_000L),
                    transitForwardMicros = 1500L + jitter,
                    peerTurnaroundMicros = 500L,
                    transitReverseMicros = 1500L + jitter,
                    peerClockOffsetMicros = trueOffsetMicros
                )
            )
        }

        // 10 artificial Wi-Fi congestion spike samples: 50ms - 80ms RTT
        // Wi-Fi asymmetric queueing heavily skews unfiltered raw offset!
        for (i in 40 until 50) {
            val spikeDelayMicros = 50_000L + ((i - 40) * 3_000L) // 50ms to 77ms spike
            samples.add(
                createSample(
                    seq = i.toLong(),
                    t0Micros = 10_000_000L + (i * 50_000L),
                    transitForwardMicros = spikeDelayMicros,
                    peerTurnaroundMicros = 500L,
                    transitReverseMicros = 1500L,
                    peerClockOffsetMicros = trueOffsetMicros
                )
            )
        }

        // 1. Verify outlier filter identifies and removes the 10 spikes
        val filterResult = ClockSyncCalculator.filterOutliers(samples, discardRatio = 0.20)
        assertEquals(40, filterResult.acceptedSamples.size)
        assertEquals(10, filterResult.rejectedSamples.size)

        // Verify all 10 rejected samples were indeed the spike samples (RTT >= 50ms)
        for (rejected in filterResult.rejectedSamples) {
            assertTrue("Rejected sample should have high RTT", rejected.roundTripTimeMs >= 50.0)
        }

        // 2. Verify calculateSyncMetrics calculates accurate offset immune to spikes
        val metrics = ClockSyncCalculator.calculateSyncMetrics(samples)
        val errorMs = abs(metrics.offsetMs - 8.0)
        assertTrue("Offset error ($errorMs ms) must be < 0.1ms after outlier rejection", errorMs < 0.1)
        assertEquals(10, metrics.rejectedSamples)
        assertEquals(40, metrics.acceptedSamples)
        assertEquals(50, metrics.totalSamples)
    }

    // ==========================================
    // 4. Positive and Negative Clock Skew
    // ==========================================

    @Test
    fun testPositiveClockSkew() {
        // Peer clock is ahead by +250.0 ms
        val peerOffsetMicros = 250_000L
        val samples = (0 until 20).map { i ->
            createSample(
                seq = i.toLong(),
                t0Micros = 5_000_000L + (i * 60_000L),
                transitForwardMicros = 2000L,
                peerTurnaroundMicros = 300L,
                transitReverseMicros = 2000L,
                peerClockOffsetMicros = peerOffsetMicros
            )
        }

        val metrics = ClockSyncCalculator.calculateSyncMetrics(samples)
        assertEquals(250.0, metrics.offsetMs, 0.05)
        assertEquals(250_000.0, metrics.offsetMicros, 50.0)
        assertEquals(4.0, metrics.rttMs, 0.05)
    }

    @Test
    fun testNegativeClockSkew() {
        // Peer clock is behind by -180.0 ms
        val peerOffsetMicros = -180_000L
        val samples = (0 until 20).map { i ->
            createSample(
                seq = i.toLong(),
                t0Micros = 5_000_000L + (i * 60_000L),
                transitForwardMicros = 2500L,
                peerTurnaroundMicros = 400L,
                transitReverseMicros = 2500L,
                peerClockOffsetMicros = peerOffsetMicros
            )
        }

        val metrics = ClockSyncCalculator.calculateSyncMetrics(samples)
        assertEquals(-180.0, metrics.offsetMs, 0.05)
        assertEquals(-180_000.0, metrics.offsetMicros, 50.0)
        assertEquals(5.0, metrics.rttMs, 0.05)
    }

    // ==========================================
    // 5. Standard Deviation Jitter Calculations
    // ==========================================

    @Test
    fun testSampleStandardDeviationKnownDistributions() {
        // Known distribution 1: [2, 4, 4, 4, 5, 5, 7, 9]
        // N = 8, Mean = 5.0
        // Sum of squared diffs: (2-5)^2 + 3*(4-5)^2 + 2*(5-5)^2 + (7-5)^2 + (9-5)^2 = 9 + 3 + 0 + 4 + 16 = 32
        // Sample variance = 32 / (8 - 1) = 32 / 7 ≈ 4.57142857
        // Sample std dev = sqrt(32 / 7) ≈ 2.138089935
        val values1 = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        val expectedStdDev1 = sqrt(32.0 / 7.0)
        val actualStdDev1 = ClockSyncCalculator.calculateSampleStandardDeviation(values1)
        assertEquals(expectedStdDev1, actualStdDev1, 1e-6)

        // Population variance = 32 / 8 = 4.0, Population std dev = 2.0
        val actualPopStdDev1 = ClockSyncCalculator.calculatePopulationStandardDeviation(values1)
        assertEquals(2.0, actualPopStdDev1, 1e-6)

        // Known distribution 2: [10, 12, 23, 23, 16, 23, 21, 16]
        // N = 8, Mean = 18.0
        // Sum of squared diffs: 64 + 36 + 25 + 25 + 4 + 25 + 9 + 4 = 192
        // Sample variance = 192 / 7 ≈ 27.4285714
        // Sample std dev = sqrt(192 / 7) ≈ 5.23722937
        val values2 = listOf(10.0, 12.0, 23.0, 23.0, 16.0, 23.0, 21.0, 16.0)
        val expectedStdDev2 = sqrt(192.0 / 7.0)
        val actualStdDev2 = ClockSyncCalculator.calculateSampleStandardDeviation(values2)
        assertEquals(expectedStdDev2, actualStdDev2, 1e-6)

        // Identical distribution: [5.0, 5.0, 5.0, 5.0] -> Std Dev = 0.0
        val identical = listOf(5.0, 5.0, 5.0, 5.0)
        assertEquals(0.0, ClockSyncCalculator.calculateSampleStandardDeviation(identical), 1e-9)
        assertEquals(0.0, ClockSyncCalculator.calculatePopulationStandardDeviation(identical), 1e-9)
    }

    // ==========================================
    // 6. Edge Cases
    // ==========================================

    @Test
    fun testEdgeCaseEmptySamples() {
        val metrics = ClockSyncCalculator.calculateSyncMetrics(emptyList(), peerId = "peer-empty")
        assertEquals("peer-empty", metrics.peerId)
        assertEquals(0.0, metrics.offsetMicros, 0.0)
        assertEquals(0.0, metrics.offsetMs, 0.0)
        assertEquals(0.0, metrics.rttMicros, 0.0)
        assertEquals(0.0, metrics.rttMs, 0.0)
        assertEquals(0.0, metrics.jitterMicros, 0.0)
        assertEquals(0.0, metrics.jitterMs, 0.0)
        assertEquals(0, metrics.totalSamples)
        assertEquals(0, metrics.acceptedSamples)
        assertEquals(0, metrics.rejectedSamples)
        assertFalse(metrics.isSynchronized)
    }

    @Test
    fun testEdgeCaseSingleSample() {
        val single = createSample(
            seq = 0L,
            t0Micros = 1000L,
            transitForwardMicros = 2000L,
            peerTurnaroundMicros = 300L,
            transitReverseMicros = 2000L,
            peerClockOffsetMicros = 5000L
        )

        val metrics = ClockSyncCalculator.calculateSyncMetrics(listOf(single), peerId = "peer-single")
        assertEquals(1, metrics.totalSamples)
        assertEquals(1, metrics.acceptedSamples)
        assertEquals(0, metrics.rejectedSamples)
        assertEquals(5000.0, metrics.offsetMicros, 0.001)
        assertEquals(5.0, metrics.offsetMs, 0.001)
        assertEquals(4000.0, metrics.rttMicros, 0.001)
        assertEquals(4.0, metrics.rttMs, 0.001)
        assertEquals(0.0, metrics.jitterMicros, 0.0) // Single sample has 0 jitter
        assertEquals(0.0, metrics.jitterMs, 0.0)
    }

    @Test
    fun testEdgeCaseIdenticalRtts() {
        val samples = (0 until 10).map { i ->
            createSample(
                seq = i.toLong(),
                t0Micros = 1000L + (i * 1000L),
                transitForwardMicros = 3000L,
                peerTurnaroundMicros = 500L,
                transitReverseMicros = 3000L,
                peerClockOffsetMicros = 2500L
            )
        }

        val metrics = ClockSyncCalculator.calculateSyncMetrics(samples)
        assertEquals(10, metrics.totalSamples)
        assertEquals(8, metrics.acceptedSamples) // 10 - 2 (20%)
        assertEquals(2, metrics.rejectedSamples)
        assertEquals(2.5, metrics.offsetMs, 0.001)
        assertEquals(6.0, metrics.rttMs, 0.001)
        assertEquals(0.0, metrics.jitterMs, 0.001) // Perfect zero jitter
    }

    @Test
    fun testEdgeCaseHighPacketLoss() {
        // High packet loss: only 4 samples gathered from 50 attempts
        // Samples < 5 -> no 20% discard applied (minSamplesForDiscard = 5)
        val samples = (0 until 4).map { i ->
            createSample(
                seq = i.toLong(),
                t0Micros = 1000L + (i * 10_000L),
                transitForwardMicros = 2000L,
                peerTurnaroundMicros = 400L,
                transitReverseMicros = 2000L,
                peerClockOffsetMicros = 1000L
            )
        }

        val metrics = ClockSyncCalculator.calculateSyncMetrics(samples)
        assertEquals(4, metrics.totalSamples)
        assertEquals(4, metrics.acceptedSamples)
        assertEquals(0, metrics.rejectedSamples)
        assertEquals(1.0, metrics.offsetMs, 0.001)
        assertEquals(4.0, metrics.rttMs, 0.001)
    }

    @Test
    fun testEdgeCaseInvalidSamplesFiltering() {
        // Sample with T3 < T0 (time machine violation)
        val invalidSample = ProbeSample(
            sequenceNumber = 1L,
            peerId = "peer-bad",
            t0 = 5000L,
            t1 = 6000L,
            t2 = 7000L,
            t3 = 4000L // T3 < T0
        )
        assertFalse(invalidSample.isValid)

        val validSample = createSample(
            seq = 2L,
            t0Micros = 1000L,
            transitForwardMicros = 2000L,
            peerTurnaroundMicros = 500L,
            transitReverseMicros = 2000L,
            peerClockOffsetMicros = 0L
        )
        assertTrue(validSample.isValid)

        val filterResult = ClockSyncCalculator.filterOutliers(listOf(invalidSample, validSample))
        assertEquals(1, filterResult.acceptedSamples.size)
        assertEquals(1, filterResult.rejectedSamples.size)
        assertEquals(2L, filterResult.acceptedSamples.first().sequenceNumber)
        assertEquals(1L, filterResult.rejectedSamples.first().sequenceNumber)
    }

    // ==========================================
    // 7. Moving Averages & Smoothing
    // ==========================================

    @Test
    fun testMovingAverageAndExponentialMovingAverage() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)

        // Simple moving average (window = 3)
        // i=0: [10] -> 10.0
        // i=1: [10, 20] -> 15.0
        // i=2: [10, 20, 30] -> 20.0
        // i=3: [20, 30, 40] -> 30.0
        // i=4: [30, 40, 50] -> 40.0
        val sma = ClockSyncCalculator.calculateMovingAverage(values, windowSize = 3)
        assertEquals(listOf(10.0, 15.0, 20.0, 30.0, 40.0), sma)

        // Exponential moving average (alpha = 0.5)
        // i=0: 10.0
        // i=1: 0.5 * 20 + 0.5 * 10 = 15.0
        // i=2: 0.5 * 30 + 0.5 * 15 = 22.5
        val ema = ClockSyncCalculator.calculateExponentialMovingAverage(listOf(10.0, 20.0, 30.0), alpha = 0.5)
        assertEquals(listOf(10.0, 15.0, 22.5), ema)
    }

    // ==========================================
    // 8. Cluster Extraction Tests
    // ==========================================

    @Test
    fun testClusterExtraction() {
        val samples = (1..10).map { i ->
            createSample(
                seq = i.toLong(),
                t0Micros = i * 10_000L,
                transitForwardMicros = (i * 1000L), // RTT = 2 * i ms
                peerTurnaroundMicros = 200L,
                transitReverseMicros = (i * 1000L),
                peerClockOffsetMicros = 500L
            )
        }

        // Ratio cluster: top 50% lowest RTT (5 samples)
        val cluster = ClockSyncCalculator.extractLowestRttCluster(samples, clusterRatio = 0.50)
        assertEquals(5, cluster.size)
        assertEquals(1L, cluster.first().sequenceNumber)
        assertEquals(5L, cluster.last().sequenceNumber)

        // Proximity cluster: samples within 2.5x of min RTT (min RTT = 2000µs, max = 5000µs)
        val proximityCluster = ClockSyncCalculator.extractLowestRttProximityCluster(samples, toleranceFactor = 2.5)
        assertEquals(2, proximityCluster.size) // RTT=2000µs and RTT=4000µs
    }

    // ==========================================
    // 9. Protocol Packaging & Telemetry
    // ==========================================

    @Test
    fun testCalibResultPackaging() {
        val samples = (0 until 10).map { i ->
            createSample(
                seq = i.toLong(),
                t0Micros = 1000L + (i * 1000L),
                transitForwardMicros = 2000L,
                peerTurnaroundMicros = 400L,
                transitReverseMicros = 2000L,
                peerClockOffsetMicros = 3250L
            )
        }

        val calibResult = ClockSyncCalculator.createCalibResult(samples, peerId = "peer-calib")
        assertEquals(3.25, calibResult.offsetMs, 0.001)
        assertEquals(4.0, calibResult.rttMs, 0.001)
        assertEquals(0.0, calibResult.jitterMs, 0.001)
        assertEquals(RoomBeatPacket.TYPE_CALIB_RESULT, calibResult.packetType)

        val metrics = ClockSyncCalculator.calculateSyncMetrics(samples, peerId = "peer-calib")
        val exportedPacket = metrics.toCalibResult()
        assertEquals(calibResult, exportedPacket)
    }

    @Test
    fun testFormattedTelemetryReadouts() {
        val metrics = SyncMetrics.fromMs(
            peerId = "phone-pixel",
            offsetMs = 0.245,
            rttMs = 3.120,
            jitterMs = 0.150,
            totalSamples = 50,
            acceptedSamples = 40,
            rejectedSamples = 10
        )

        assertNotNull(metrics.formattedTelemetry)
        assertTrue(metrics.formattedTelemetry.contains("+0.245ms"))
        assertTrue(metrics.formattedTelemetry.contains("3.120ms"))
        assertTrue(metrics.formattedTelemetry.contains("±0.150ms"))
        assertTrue(metrics.formattedTelemetry.contains("40/50 samples"))
        assertTrue(metrics.formattedTelemetry.contains("10 rejected"))

        assertNotNull(metrics.formattedCompact)
        assertTrue(metrics.formattedCompact.contains("±0.15ms jitter"))
        assertTrue(metrics.formattedCompact.contains("3.1ms RTT"))
    }
}
