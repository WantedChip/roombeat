package com.roombeat.app.sync

import com.roombeat.app.protocol.RoomBeatPacket
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Metric dimension used as primary jitter standard deviation in [SyncMetrics].
 */
enum class JitterMetric {
    /**
     * Standard deviation of round-trip transit times (network flight instability).
     */
    RTT,

    /**
     * Standard deviation of clock offset measurements (clock skew/phase instability).
     */
    OFFSET
}

/**
 * Result container for outlier filtering of probe samples.
 */
data class OutlierFilterResult(
    val acceptedSamples: List<ProbeSample>,
    val rejectedSamples: List<ProbeSample>
) {
    val totalSamplesCount: Int get() = acceptedSamples.size + rejectedSamples.size
    val rejectionRatio: Double
        get() = if (totalSamplesCount == 0) 0.0 else rejectedSamples.size.toDouble() / totalSamplesCount.toDouble()
}

/**
 * Statistical clock synchronization and network jitter calculation engine.
 *
 * Implements standard NTP (RFC 5905) arithmetic and Cristian's statistical filtering algorithm:
 * 1. NTP Offset Equation: theta = ((T1 - T0) + (T2 - T3)) / 2
 * 2. NTP Round-Trip Time Equation: RTT = (T3 - T0) - (T2 - T1)
 * 3. Outlier Rejection: Discards the top 20% highest-RTT samples (congested packets / Wi-Fi spikes)
 *    when sample count is sufficient (>= 5 samples).
 * 4. Cristian's Filter Principle: Samples with the lowest RTT experience the least queueing delay
 *    and asymmetry, yielding the most accurate clock offset estimate. Offset is estimated using
 *    the median of the lowest-RTT cluster.
 * 5. Sample Standard Deviation Jitter: sigma_jitter = sqrt((1 / (N - 1)) * sum((x_i - mean)^2)).
 */
object ClockSyncCalculator {

    /**
     * Default fraction of highest-RTT samples to discard as network congestion outliers (20%).
     */
    const val DEFAULT_OUTLIER_DISCARD_RATIO = 0.20

    /**
     * Default fraction of lowest-RTT samples from the accepted pool to form Cristian's cluster (50%).
     */
    const val DEFAULT_CLUSTER_RATIO = 0.50

    /**
     * Minimum sample count required before applying outlier rejection.
     */
    const val MIN_SAMPLES_FOR_DISCARD = 5

    // ==========================================
    // Core NTP Equations
    // ==========================================

    /**
     * Calculates peer clock offset relative to Host in microseconds: ((T1 - T0) + (T2 - T3)) / 2.
     * Positive offset indicates Peer clock is ahead of Host clock.
     */
    fun calculateSampleOffsetMicros(t0: Long, t1: Long, t2: Long, t3: Long): Double =
        ((t1 - t0).toDouble() + (t2 - t3).toDouble()) / 2.0

    /**
     * Calculates peer clock offset relative to Host in milliseconds.
     */
    fun calculateSampleOffsetMs(t0: Long, t1: Long, t2: Long, t3: Long): Double =
        calculateSampleOffsetMicros(t0, t1, t2, t3) / 1000.0

    /**
     * Calculates pure round-trip network transit time in microseconds: (T3 - T0) - (T2 - T1).
     * Excludes peer internal processing/turnaround delay and clamps negative values caused by timer noise.
     */
    fun calculateSampleRttMicros(t0: Long, t1: Long, t2: Long, t3: Long): Long =
        maxOf(0L, (t3 - t0) - (t2 - t1))

    /**
     * Calculates round-trip network transit time in milliseconds.
     */
    fun calculateSampleRttMs(t0: Long, t1: Long, t2: Long, t3: Long): Double =
        calculateSampleRttMicros(t0, t1, t2, t3) / 1000.0

    // ==========================================
    // Statistical Aggregations
    // ==========================================

    /**
     * Calculates arithmetic mean of a series of values. Returns 0.0 for empty collections.
     */
    fun calculateMean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values.average()
    }

    /**
     * Calculates statistical median of a series of values.
     * For odd sample sizes, returns the central element.
     * For even sample sizes, returns the average of the two central elements.
     */
    fun calculateMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val size = sorted.size
        val mid = size / 2
        return if (size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /**
     * Calculates Bessel-corrected sample standard deviation:
     * sigma = sqrt((1 / (N - 1)) * sum((x_i - mean)^2))
     * Returns 0.0 if sample count <= 1.
     */
    fun calculateSampleStandardDeviation(values: List<Double>): Double {
        if (values.size <= 1) return 0.0
        val mean = values.average()
        val sumSquaredDiffs = values.sumOf { (it - mean).pow(2) }
        return sqrt(sumSquaredDiffs / (values.size - 1))
    }

    /**
     * Calculates population standard deviation:
     * sigma = sqrt((1 / N) * sum((x_i - mean)^2))
     * Returns 0.0 if sample count == 0.
     */
    fun calculatePopulationStandardDeviation(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val sumSquaredDiffs = values.sumOf { (it - mean).pow(2) }
        return sqrt(sumSquaredDiffs / values.size)
    }

    /**
     * Computes Simple Moving Average (SMA) over a sliding window.
     * For the initial warm-up elements (index < windowSize), averages available prefix elements.
     */
    fun calculateMovingAverage(values: List<Double>, windowSize: Int = 5): List<Double> {
        if (values.isEmpty() || windowSize <= 0) return emptyList()
        val result = ArrayList<Double>(values.size)
        for (i in values.indices) {
            val start = maxOf(0, i - windowSize + 1)
            val window = values.subList(start, i + 1)
            result.add(window.average())
        }
        return result
    }

    /**
     * Computes Exponential Moving Average (EMA) with smoothing factor [alpha] in (0, 1].
     */
    fun calculateExponentialMovingAverage(values: List<Double>, alpha: Double = 0.2): List<Double> {
        if (values.isEmpty()) return emptyList()
        val clampedAlpha = alpha.coerceIn(0.01, 1.0)
        val result = ArrayList<Double>(values.size)
        var currentEma = values[0]
        result.add(currentEma)
        for (i in 1 until values.size) {
            currentEma = clampedAlpha * values[i] + (1.0 - clampedAlpha) * currentEma
            result.add(currentEma)
        }
        return result
    }

    // ==========================================
    // Outlier Filtering & Cristian's Clustering
    // ==========================================

    /**
     * Filters out invalid samples and discards the top [discardRatio] (default: 20%) highest-RTT
     * outlier samples when [samples] count is at least [minSamplesForDiscard] (default: 5).
     */
    fun filterOutliers(
        samples: List<ProbeSample>,
        discardRatio: Double = DEFAULT_OUTLIER_DISCARD_RATIO,
        minSamplesForDiscard: Int = MIN_SAMPLES_FOR_DISCARD
    ): OutlierFilterResult {
        if (samples.isEmpty()) {
            return OutlierFilterResult(emptyList(), emptyList())
        }

        // Separate causality-valid samples from corrupt/invalid samples
        val (validSamples, invalidSamples) = samples.partition { it.isValid }

        if (validSamples.size < minSamplesForDiscard) {
            return OutlierFilterResult(
                acceptedSamples = validSamples,
                rejectedSamples = invalidSamples
            )
        }

        // Sort ascending by RTT (lowest transit time first)
        val sortedByRtt = validSamples.sortedBy { it.roundTripTimeMicros }

        // Discard highest-RTT tail
        val clampedRatio = discardRatio.coerceIn(0.0, 0.9)
        val discardCount = (validSamples.size * clampedRatio).toInt()
        val keepCount = maxOf(1, validSamples.size - discardCount)

        val accepted = sortedByRtt.take(keepCount)
        val discarded = sortedByRtt.drop(keepCount)

        return OutlierFilterResult(
            acceptedSamples = accepted,
            rejectedSamples = invalidSamples + discarded
        )
    }

    /**
     * Extracts the lowest-RTT cluster from a collection of samples according to Cristian's algorithm.
     *
     * @param samples Filtered candidate samples.
     * @param clusterRatio Fraction of samples with lowest RTT to retain (default: 50%).
     */
    fun extractLowestRttCluster(
        samples: List<ProbeSample>,
        clusterRatio: Double = DEFAULT_CLUSTER_RATIO
    ): List<ProbeSample> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.roundTripTimeMicros }
        val clampedRatio = clusterRatio.coerceIn(0.01, 1.0)
        val clusterSize = maxOf(1, kotlin.math.round(sorted.size * clampedRatio).toInt())
        return sorted.take(clusterSize)
    }

    /**
     * Extracts lowest-RTT cluster by proximity to the minimum measured RTT.
     * Retains all samples whose RTT is within [toleranceFactor] (default: 1.5x) of the minimum RTT.
     */
    fun extractLowestRttProximityCluster(
        samples: List<ProbeSample>,
        toleranceFactor: Double = 1.5
    ): List<ProbeSample> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.roundTripTimeMicros }
        val minRtt = sorted.first().roundTripTimeMicros
        val maxAllowedRtt = (minRtt * maxOf(1.0, toleranceFactor)).toLong()
        return sorted.filter { it.roundTripTimeMicros <= maxAllowedRtt }
    }

    // ==========================================
    // Metrics Calculation & Packet Creation
    // ==========================================

    /**
     * Calculates comprehensive [SyncMetrics] from a collection of [ProbeSample]s using
     * Cristian's algorithm with outlier rejection.
     *
     * @param samples Raw probe samples gathered during calibration burst.
     * @param peerId Peer identifier (defaults to peerId of first sample if available).
     * @param outlierDiscardRatio Fraction of highest-RTT outliers to discard (default: 0.20 = 20%).
     * @param clusterRatio Fraction of lowest-RTT samples to form Cristian's cluster (default: 0.50 = 50%).
     * @param useMedianOffset If true, uses median of cluster; if false, uses mean.
     * @param jitterMetric Primary jitter dimension: RTT standard deviation or Offset standard deviation.
     * @param clock Monotonic clock for timestamping the metrics calculation.
     */
    fun calculateSyncMetrics(
        samples: List<ProbeSample>,
        peerId: String = samples.firstOrNull()?.peerId ?: "",
        outlierDiscardRatio: Double = DEFAULT_OUTLIER_DISCARD_RATIO,
        clusterRatio: Double = DEFAULT_CLUSTER_RATIO,
        useMedianOffset: Boolean = true,
        jitterMetric: JitterMetric = JitterMetric.RTT,
        clock: MonotonicClock = SystemMonotonicClock
    ): SyncMetrics {
        val totalCount = samples.size
        if (totalCount == 0) {
            return SyncMetrics.EMPTY.copy(
                peerId = peerId,
                calculatedAtMicros = clock.nowMicros()
            )
        }

        // 1. Outlier rejection
        val filterResult = filterOutliers(
            samples = samples,
            discardRatio = outlierDiscardRatio
        )

        val accepted = filterResult.acceptedSamples
        val rejected = filterResult.rejectedSamples

        if (accepted.isEmpty()) {
            return SyncMetrics(
                peerId = peerId,
                offsetMicros = 0.0,
                rttMicros = 0.0,
                jitterMicros = 0.0,
                rttJitterMicros = 0.0,
                offsetJitterMicros = 0.0,
                totalSamples = totalCount,
                acceptedSamples = 0,
                rejectedSamples = totalCount,
                calculatedAtMicros = clock.nowMicros()
            )
        }

        // 2. Cristian's filter: extract lowest-RTT cluster
        val cluster = extractLowestRttCluster(accepted, clusterRatio)

        // 3. Offset calculation from lowest-RTT cluster
        val clusterOffsets = cluster.map { it.clockOffsetMicros }
        val representativeOffset = if (useMedianOffset) {
            calculateMedian(clusterOffsets)
        } else {
            calculateMean(clusterOffsets)
        }

        // 4. Round-trip time calculation from lowest-RTT cluster
        val clusterRtts = cluster.map { it.roundTripTimeMicros.toDouble() }
        val representativeRtt = if (useMedianOffset) {
            calculateMedian(clusterRtts)
        } else {
            calculateMean(clusterRtts)
        }

        // 5. Sample standard deviation jitter across accepted samples
        val acceptedRtts = accepted.map { it.roundTripTimeMicros.toDouble() }
        val acceptedOffsets = accepted.map { it.clockOffsetMicros }

        val rttJitter = calculateSampleStandardDeviation(acceptedRtts)
        val offsetJitter = calculateSampleStandardDeviation(acceptedOffsets)

        val primaryJitter = when (jitterMetric) {
            JitterMetric.RTT -> rttJitter
            JitterMetric.OFFSET -> offsetJitter
        }

        return SyncMetrics(
            peerId = peerId,
            offsetMicros = representativeOffset,
            rttMicros = representativeRtt,
            jitterMicros = primaryJitter,
            rttJitterMicros = rttJitter,
            offsetJitterMicros = offsetJitter,
            totalSamples = totalCount,
            acceptedSamples = accepted.size,
            rejectedSamples = rejected.size,
            calculatedAtMicros = clock.nowMicros()
        )
    }

    /**
     * Convenience method to calculate [SyncMetrics] from a [CalibrationBurstResult].
     */
    fun calculateSyncMetrics(result: CalibrationBurstResult): SyncMetrics =
        calculateSyncMetrics(result.successfulSamples, result.peerId)

    /**
     * Convenience method to calculate [SyncMetrics] from accumulated [PeerCalibrationStats].
     */
    fun calculateSyncMetrics(stats: PeerCalibrationStats): SyncMetrics =
        calculateSyncMetrics(stats.samples, stats.peerId)

    /**
     * Helper method to calculate [SyncMetrics] and package directly into a [RoomBeatPacket.CalibResult].
     */
    fun createCalibResult(
        samples: List<ProbeSample>,
        peerId: String = samples.firstOrNull()?.peerId ?: ""
    ): RoomBeatPacket.CalibResult {
        val metrics = calculateSyncMetrics(samples, peerId)
        return metrics.toCalibResult()
    }
}
