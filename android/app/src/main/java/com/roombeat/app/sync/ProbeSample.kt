package com.roombeat.app.sync

import com.roombeat.app.protocol.RoomBeatPacket

/**
 * 4-timestamp NTP probe exchange sample model capturing the timeline of a single
 * round-trip exchange between the Host and a Peer node.
 *
 * All timestamps are recorded in microseconds from their respective device's [MonotonicClock]:
 * - [t0]: Host dispatch time (recorded on Host)
 * - [t1]: Peer arrival time (recorded on Peer)
 * - [t2]: Peer echo departure time (recorded on Peer)
 * - [t3]: Host echo receipt time (recorded on Host)
 *
 * Math reference (NTP RFC 5905):
 * - Round-trip time (RTT): (T3 - T0) - (T2 - T1)
 * - Peer clock offset: ((T1 - T0) + (T2 - T3)) / 2
 *   (positive offset indicates Peer clock is ahead of Host clock: T_peer = T_host + offset)
 */
data class ProbeSample(
    val sequenceNumber: Long,
    val peerId: String,
    val t0: Long,
    val t1: Long,
    val t2: Long,
    val t3: Long
) {

    /**
     * Total round-trip network transit time in microseconds: (T3 - T0) - (T2 - T1).
     * Represents pure network flight time, excluding peer internal turnaround delay.
     * Clamped to 0 to safeguard against negative values caused by microsecond timer quantization.
     */
    val roundTripTimeMicros: Long
        get() = maxOf(0L, (t3 - t0) - (t2 - t1))

    /**
     * Total round-trip network transit time in milliseconds.
     */
    val roundTripTimeMs: Double
        get() = roundTripTimeMicros / 1000.0

    /**
     * Peer clock offset relative to Host in microseconds: ((T1 - T0) + (T2 - T3)) / 2.
     * A positive offset means the Peer clock is ahead of the Host clock.
     */
    val clockOffsetMicros: Double
        get() = ((t1 - t0).toDouble() + (t2 - t3).toDouble()) / 2.0

    /**
     * Peer clock offset relative to Host in milliseconds.
     */
    val clockOffsetMs: Double
        get() = clockOffsetMicros / 1000.0

    /**
     * Total elapsed time on the Host between probe dispatch and echo receipt in microseconds: (T3 - T0).
     */
    val hostElapsedMicros: Long
        get() = t3 - t0

    /**
     * Total elapsed time on the Host in milliseconds.
     */
    val hostElapsedMs: Double
        get() = hostElapsedMicros / 1000.0

    /**
     * Processing / turnaround time on the Peer between arrival and departure in microseconds: (T2 - T1).
     */
    val peerProcessingMicros: Long
        get() = t2 - t1

    /**
     * Processing / turnaround time on the Peer in milliseconds.
     */
    val peerProcessingMs: Double
        get() = peerProcessingMicros / 1000.0

    /**
     * Verifies whether timestamps obey local monotonic causality:
     * T3 >= T0 on Host and T2 >= T1 on Peer.
     */
    val isValid: Boolean
        get() = t3 >= t0 && t2 >= t1

    /**
     * Verifies whether timestamps are strictly chronological across both devices
     * (T0 <= T1 <= T2 <= T3), which holds when both devices share a common reference
     * timeline or have negligible clock offset relative to one-way latency.
     */
    val isStrictlyChronological: Boolean
        get() = t0 <= t1 && t1 <= t2 && t2 <= t3

    // Nanosecond convenience conversions
    val t0Nanos: Long get() = t0 * 1_000L
    val t1Nanos: Long get() = t1 * 1_000L
    val t2Nanos: Long get() = t2 * 1_000L
    val t3Nanos: Long get() = t3 * 1_000L

    // Millisecond convenience conversions
    val t0Millis: Long get() = t0 / 1_000L
    val t1Millis: Long get() = t1 / 1_000L
    val t2Millis: Long get() = t2 / 1_000L
    val t3Millis: Long get() = t3 / 1_000L

    companion object {

        /**
         * Construct a [ProbeSample] from nanosecond timestamps, automatically converting
         * to microsecond resolution.
         */
        fun fromNanos(
            sequenceNumber: Long,
            peerId: String,
            t0Nanos: Long,
            t1Nanos: Long,
            t2Nanos: Long,
            t3Nanos: Long
        ): ProbeSample = ProbeSample(
            sequenceNumber = sequenceNumber,
            peerId = peerId,
            t0 = t0Nanos / 1_000L,
            t1 = t1Nanos / 1_000L,
            t2 = t2Nanos / 1_000L,
            t3 = t3Nanos / 1_000L
        )

        /**
         * Construct a [ProbeSample] from a received [RoomBeatPacket.CalibEcho] and the local host
         * receipt timestamp [t3] in microseconds.
         */
        fun fromEcho(
            sequenceNumber: Long,
            peerId: String,
            echo: RoomBeatPacket.CalibEcho,
            t3: Long
        ): ProbeSample = ProbeSample(
            sequenceNumber = sequenceNumber,
            peerId = peerId,
            t0 = echo.t0,
            t1 = echo.t1,
            t2 = echo.t2,
            t3 = t3
        )
    }
}
