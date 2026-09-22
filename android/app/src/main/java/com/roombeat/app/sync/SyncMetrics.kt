package com.roombeat.app.sync

import com.roombeat.app.protocol.RoomBeatPacket
import java.util.Locale

/**
 * Peer synchronization telemetry metrics model calculated from NTP probe samples.
 *
 * Captures the statistical clock offset (theta), round-trip time (RTT), and network jitter
 * standard deviation (sigma_jitter) across host-peer calibration exchanges.
 *
 * All microsecond metrics provide millisecond accessors for UI telemetry HUDs and protocol messages.
 *
 * @property peerId Identifier of the peer node.
 * @property offsetMicros Estimated clock offset in microseconds (positive: peer ahead of host).
 * @property rttMicros Estimated round-trip time in microseconds.
 * @property jitterMicros Network jitter standard deviation in microseconds.
 * @property rttJitterMicros Sample standard deviation of round-trip times in microseconds.
 * @property offsetJitterMicros Sample standard deviation of clock offsets in microseconds.
 * @property totalSamples Total number of probe samples evaluated.
 * @property acceptedSamples Number of accepted probe samples after outlier rejection.
 * @property rejectedSamples Number of discarded outlier/congested samples.
 * @property calculatedAtMicros Monotonic timestamp in microseconds when metrics were computed.
 */
data class SyncMetrics(
    val peerId: String,
    val offsetMicros: Double,
    val rttMicros: Double,
    val jitterMicros: Double,
    val rttJitterMicros: Double = jitterMicros,
    val offsetJitterMicros: Double = jitterMicros,
    val totalSamples: Int,
    val acceptedSamples: Int,
    val rejectedSamples: Int,
    val calculatedAtMicros: Long = 0L
) {

    /**
     * Estimated clock offset in milliseconds (positive: peer clock is ahead of host clock).
     */
    val offsetMs: Double
        get() = offsetMicros / 1000.0

    /**
     * Estimated round-trip network transit time in milliseconds.
     */
    val rttMs: Double
        get() = rttMicros / 1000.0

    /**
     * Network jitter standard deviation in milliseconds.
     */
    val jitterMs: Double
        get() = jitterMicros / 1000.0

    /**
     * Round-trip time jitter standard deviation in milliseconds.
     */
    val rttJitterMs: Double
        get() = rttJitterMicros / 1000.0

    /**
     * Clock offset jitter standard deviation in milliseconds.
     */
    val offsetJitterMs: Double
        get() = offsetJitterMicros / 1000.0

    /**
     * Checks if the clock offset is within the specified tolerance threshold.
     *
     * @param maxOffsetMs Maximum absolute offset in milliseconds (default: 1.0ms).
     * @return true if |offsetMs| < maxOffsetMs.
     */
    fun isWithinTolerance(maxOffsetMs: Double = DEFAULT_MAX_OFFSET_MS): Boolean =
        kotlin.math.abs(offsetMs) < maxOffsetMs

    /**
     * True if the peer clock is synchronized with sub-millisecond precision (< 1.0ms)
     * and at least one sample was successfully accepted.
     */
    val isSynchronized: Boolean
        get() = acceptedSamples > 0 && isWithinTolerance(DEFAULT_MAX_OFFSET_MS)

    /**
     * True if network jitter is within acceptable limits (<= 5.0ms) for stable audio playback.
     */
    val hasAcceptableJitter: Boolean
        get() = jitterMs <= DEFAULT_MAX_JITTER_MS

    /**
     * Formatted telemetry readout string for debug logging and the active playback HUD.
     * Example: "OFFSET: +0.245ms | RTT: 3.120ms | JITTER: ±0.150ms [40/50 samples, 10 rejected]"
     */
    val formattedTelemetry: String
        get() = String.format(
            Locale.US,
            "OFFSET: %+.3fms | RTT: %.3fms | JITTER: ±%.3fms [%d/%d samples, %d rejected]",
            offsetMs,
            rttMs,
            jitterMs,
            acceptedSamples,
            totalSamples,
            rejectedSamples
        )

    /**
     * Compact telemetry string for channel strip cards and status chips.
     * Example: "±0.15ms jitter · 3.1ms RTT"
     */
    val formattedCompact: String
        get() = String.format(
            Locale.US,
            "±%.2fms jitter · %.1fms RTT",
            jitterMs,
            rttMs
        )

    /**
     * Packages metrics into a protocol [RoomBeatPacket.CalibResult] message for broadcast to the peer.
     */
    fun toCalibResult(): RoomBeatPacket.CalibResult = RoomBeatPacket.CalibResult(
        offsetMs = offsetMs,
        rttMs = rttMs,
        jitterMs = jitterMs
    )

    companion object {
        /**
         * Standard sub-millisecond synchronization threshold (1.0ms per Roadmap §11).
         */
        const val DEFAULT_MAX_OFFSET_MS = 1.0

        /**
         * Default acceptable network jitter threshold for LAN Wi-Fi (5.0ms).
         */
        const val DEFAULT_MAX_JITTER_MS = 5.0

        /**
         * Empty placeholder metrics instance for uncalibrated peers.
         */
        val EMPTY = SyncMetrics(
            peerId = "",
            offsetMicros = 0.0,
            rttMicros = 0.0,
            jitterMicros = 0.0,
            rttJitterMicros = 0.0,
            offsetJitterMicros = 0.0,
            totalSamples = 0,
            acceptedSamples = 0,
            rejectedSamples = 0,
            calculatedAtMicros = 0L
        )

        /**
         * Convenience factory creating [SyncMetrics] from millisecond parameters.
         */
        fun fromMs(
            peerId: String,
            offsetMs: Double,
            rttMs: Double,
            jitterMs: Double,
            totalSamples: Int,
            acceptedSamples: Int,
            rejectedSamples: Int,
            rttJitterMs: Double = jitterMs,
            offsetJitterMs: Double = jitterMs,
            calculatedAtMicros: Long = 0L
        ): SyncMetrics = SyncMetrics(
            peerId = peerId,
            offsetMicros = offsetMs * 1000.0,
            rttMicros = rttMs * 1000.0,
            jitterMicros = jitterMs * 1000.0,
            rttJitterMicros = rttJitterMs * 1000.0,
            offsetJitterMicros = offsetJitterMs * 1000.0,
            totalSamples = totalSamples,
            acceptedSamples = acceptedSamples,
            rejectedSamples = rejectedSamples,
            calculatedAtMicros = calculatedAtMicros
        )
    }
}
