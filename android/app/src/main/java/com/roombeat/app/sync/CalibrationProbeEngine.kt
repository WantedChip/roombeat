package com.roombeat.app.sync

import com.roombeat.app.protocol.RoomBeatPacket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Non-blocking transport abstraction for calibration probe packet delivery.
 * Decouples [CalibrationProbeEngine] from raw sockets/WebSockets for clean JVM testing.
 */
fun interface CalibrationTransport {
    /**
     * Non-blocking send of a calibration packet to [peerId].
     * @return true if successfully dispatched/buffered to the transport layer.
     */
    fun sendPacket(peerId: String, packet: RoomBeatPacket): Boolean
}

/**
 * Listener interface for calibration events, progress, and telemetry updates.
 */
interface CalibrationProbeListener {
    fun onProbeDispatched(peerId: String, sequenceNumber: Long, t0: Long) {}
    fun onSampleReceived(sample: ProbeSample) {}
    fun onProbeLost(peerId: String, sequenceNumber: Long, t0: Long) {}
    fun onProbeEchoed(senderId: String, t0: Long, t1: Long, t2: Long) {}
    fun onBurstProgress(peerId: String, completed: Int, total: Int) {}
    fun onBurstCompleted(result: CalibrationBurstResult) {}
}

/**
 * Final result of a completed calibration burst against a peer node.
 */
data class CalibrationBurstResult(
    val peerId: String,
    val totalProbesSent: Int,
    val successfulSamples: List<ProbeSample>,
    val lostProbes: Int,
    val durationMs: Long
) {
    /**
     * Fraction of dropped or timed-out probe packets (0.0 to 1.0).
     */
    val packetLossRate: Double
        get() = if (totalProbesSent == 0) 0.0 else lostProbes.toDouble() / totalProbesSent.toDouble()

    /**
     * Percentage of dropped or timed-out probe packets (0.0% to 100.0%).
     */
    val packetLossPercent: Double
        get() = packetLossRate * 100.0

    /**
     * True if at least one valid sample was received.
     */
    val isSuccessful: Boolean
        get() = successfulSamples.isNotEmpty()
}

/**
 * Accumulated calibration telemetry statistics for a specific peer.
 */
data class PeerCalibrationStats(
    val peerId: String,
    val totalProbesSent: Int,
    val samplesReceived: Int,
    val probesLost: Int,
    val samples: List<ProbeSample>
) {
    val packetLossRate: Double
        get() = if (totalProbesSent == 0) 0.0 else probesLost.toDouble() / totalProbesSent.toDouble()

    val packetLossPercent: Double
        get() = packetLossRate * 100.0
}

/**
 * Internal tracking data for a dispatched probe awaiting an echo.
 */
internal data class PendingProbe(
    val sequenceNumber: Long,
    val peerId: String,
    val t0: Long,
    val dispatchedAtMs: Long
)

/**
 * Low-latency 4-timestamp NTP calibration probe exchange engine.
 *
 * Coordinates high-frequency bursts of probe packets (default: 50 probes over 3 seconds,
 * 60ms interval) from host to peers to accurately measure network round-trip times,
 * jitter, and clock offsets without wall-clock drift.
 *
 * Supports both Host orchestration (burst dispatch and sample collection) and Peer
 * response (immediate CALIB_ECHO generation upon receiving CALIB_PROBE).
 */
class CalibrationProbeEngine(
    val isHost: Boolean,
    val clock: MonotonicClock = SystemMonotonicClock,
    var transport: CalibrationTransport? = null,
    var listener: CalibrationProbeListener? = null,
    var logger: ((String) -> Unit)? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val scope: CoroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob()),
    val probeCount: Int = DEFAULT_PROBE_COUNT,
    val probeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    val probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS
) : AutoCloseable {

    companion object {
        const val DEFAULT_PROBE_COUNT = 50
        const val DEFAULT_PROBE_INTERVAL_MS = 60L // 50 * 60ms = 3.0s total burst duration
        const val DEFAULT_PROBE_TIMEOUT_MS = 300L  // 300ms per-probe timeout
    }

    // Pending probes awaiting echo, keyed by peerId -> (t0 -> PendingProbe)
    private val pendingProbes = ConcurrentHashMap<String, ConcurrentHashMap<Long, PendingProbe>>()

    // Successfully gathered samples per peer
    private val peerSamples = ConcurrentHashMap<String, CopyOnWriteArrayList<ProbeSample>>()

    // Counters per peer
    private val peerSentCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val peerLostCounters = ConcurrentHashMap<String, AtomicInteger>()

    // Strictly increasing T0 tracking across all dispatched probes
    private var lastT0: Long = 0L
    private val t0Lock = Any()

    /**
     * Obtains a strictly increasing T0 timestamp in microseconds.
     */
    private fun nextT0(): Long = synchronized(t0Lock) {
        val now = clock.nowMicros()
        val t0 = if (now <= lastT0) lastT0 + 1L else now
        lastT0 = t0
        t0
    }

    /**
     * Executes a calibration probe burst against [peerId] synchronously within a coroutine.
     * Dispatches [probeCount] probes spaced by [probeIntervalMs], collects echoes,
     * monitors timeouts, and returns [CalibrationBurstResult].
     */
    suspend fun runCalibrationBurst(peerId: String): CalibrationBurstResult = coroutineScope {
        val startTimeMs = clock.nowMillis()
        var sentCount = 0
        val targetCount = probeCount

        val peerPending = pendingProbes.computeIfAbsent(peerId) { ConcurrentHashMap() }
        val sentCounter = peerSentCounters.computeIfAbsent(peerId) { AtomicInteger(0) }
        val lostCounter = peerLostCounters.computeIfAbsent(peerId) { AtomicInteger(0) }

        for (seq in 0 until targetCount) {
            if (!coroutineContext.isActive) break

            // 1. Sweep expired pending probes before dispatching the next probe
            sweepExpiredProbes(peerId)

            // 2. Prepare probe and record in-flight tracking
            val t0 = nextT0()
            val pending = PendingProbe(
                sequenceNumber = seq.toLong(),
                peerId = peerId,
                t0 = t0,
                dispatchedAtMs = clock.nowMillis()
            )
            peerPending[t0] = pending
            sentCounter.incrementAndGet()
            sentCount++

            val probe = RoomBeatPacket.CalibProbe(t0 = t0)
            val sent = transport?.sendPacket(peerId, probe) ?: false
            if (!sent) {
                logger?.invoke("Probe #$seq to $peerId: transport dispatch returned false")
            }
            listener?.onProbeDispatched(peerId, seq.toLong(), t0)
            listener?.onBurstProgress(peerId, sentCount, targetCount)

            // 3. Delay before next probe (if not last)
            if (seq < targetCount - 1 && probeIntervalMs > 0L) {
                delay(probeIntervalMs)
            }
        }

        // 4. Await in-flight echoes until probeTimeoutMs elapses after the last dispatch
        var waitedMs = 0L
        while (coroutineContext.isActive && peerPending.isNotEmpty() && waitedMs < probeTimeoutMs) {
            val stepWait = minOf(15L, probeTimeoutMs - waitedMs)
            delay(stepWait)
            waitedMs += stepWait
        }

        // 5. Any remaining pending probes are declared lost due to timeout
        val timedOut = sweepAllRemainingProbes(peerId)
        for (lost in timedOut) {
            lostCounter.incrementAndGet()
            logger?.invoke("Probe #${lost.sequenceNumber} to $peerId timed out (T0=${lost.t0}µs)")
            listener?.onProbeLost(peerId, lost.sequenceNumber, lost.t0)
        }

        val endTimeMs = clock.nowMillis()
        val samples = getSamplesForPeer(peerId)
        val lostCount = lostCounter.get()

        val result = CalibrationBurstResult(
            peerId = peerId,
            totalProbesSent = sentCount,
            successfulSamples = samples,
            lostProbes = lostCount,
            durationMs = endTimeMs - startTimeMs
        )
        listener?.onBurstCompleted(result)
        result
    }

    /**
     * Executes calibration probe bursts concurrently against multiple [peerIds].
     */
    suspend fun runCalibrationBurstAll(peerIds: Collection<String>): Map<String, CalibrationBurstResult> =
        coroutineScope {
            peerIds.map { peerId ->
                async { runCalibrationBurst(peerId) }
            }.awaitAll().associateBy { it.peerId }
        }

    /**
     * Launches an asynchronous calibration probe burst against [peerId] as a coroutine [Job].
     */
    fun startCalibrationBurst(
        peerId: String,
        onComplete: ((CalibrationBurstResult) -> Unit)? = null
    ): Job {
        return scope.launch {
            val result = runCalibrationBurst(peerId)
            onComplete?.invoke(result)
        }
    }

    /**
     * Central message processor for calibration-related packets.
     *
     * - When receiving [RoomBeatPacket.CalibProbe] (Peer role):
     *   Records arrival T1, departure T2, and immediately returns [RoomBeatPacket.CalibEcho].
     *
     * - When receiving [RoomBeatPacket.CalibEcho] (Host role):
     *   Records receipt T3, pairs with pending probe, and constructs [ProbeSample].
     *
     * @return true if the packet was handled by the calibration engine.
     */
    fun handleIncomingPacket(senderId: String, packet: RoomBeatPacket): Boolean {
        return when (packet) {
            is RoomBeatPacket.CalibProbe -> {
                handleCalibProbe(senderId, packet)
            }
            is RoomBeatPacket.CalibEcho -> {
                val t3 = clock.nowMicros()
                handleCalibEcho(senderId, packet, t3)
            }
            else -> false
        }
    }

    /**
     * Peer response handler: receives CALIB_PROBE, records T1 & T2, sends CALIB_ECHO.
     */
    private fun handleCalibProbe(senderId: String, probe: RoomBeatPacket.CalibProbe): Boolean {
        val t1 = clock.nowMicros()
        val t2 = clock.nowMicros() // Departure time (guaranteed >= t1)
        val departureT2 = maxOf(t1, t2)

        val echo = RoomBeatPacket.CalibEcho(
            t0 = probe.t0,
            t1 = t1,
            t2 = departureT2
        )
        val sent = transport?.sendPacket(senderId, echo) ?: false
        listener?.onProbeEchoed(senderId, probe.t0, t1, departureT2)
        return sent
    }

    /**
     * Host receipt handler: receives CALIB_ECHO, constructs ProbeSample, updates stats.
     */
    private fun handleCalibEcho(senderId: String, echo: RoomBeatPacket.CalibEcho, t3: Long): Boolean {
        val peerPending = pendingProbes[senderId] ?: return false
        val pending = peerPending.remove(echo.t0) ?: return false

        val sample = ProbeSample(
            sequenceNumber = pending.sequenceNumber,
            peerId = senderId,
            t0 = echo.t0,
            t1 = echo.t1,
            t2 = echo.t2,
            t3 = maxOf(echo.t0, t3)
        )

        peerSamples.computeIfAbsent(senderId) { CopyOnWriteArrayList() }.add(sample)

        val ordered = sample.isStrictlyChronological
        val orderingTag = if (ordered) "T0<=T1<=T2<=T3: PASS" else "T0<=T3 & T1<=T2: VALID"

        logger?.invoke(
            String.format(
                Locale.US,
                "Calibration Echo [%s] #%d | T0=%dµs, T1=%dµs, T2=%dµs, T3=%dµs | RTT=%.3fms, Offset=%.3fms | %s",
                senderId,
                sample.sequenceNumber,
                sample.t0,
                sample.t1,
                sample.t2,
                sample.t3,
                sample.roundTripTimeMs,
                sample.clockOffsetMs,
                orderingTag
            )
        )

        listener?.onSampleReceived(sample)
        return true
    }

    /**
     * Sweeps and removes pending probes for [peerId] that exceeded [probeTimeoutMs].
     */
    private fun sweepExpiredProbes(peerId: String) {
        val peerPending = pendingProbes[peerId] ?: return
        val nowMs = clock.nowMillis()
        val expired = peerPending.values.filter { nowMs - it.dispatchedAtMs >= probeTimeoutMs }
        if (expired.isNotEmpty()) {
            val lostCounter = peerLostCounters.computeIfAbsent(peerId) { AtomicInteger(0) }
            for (probe in expired) {
                if (peerPending.remove(probe.t0) != null) {
                    lostCounter.incrementAndGet()
                    logger?.invoke("Probe #${probe.sequenceNumber} to $peerId timed out (expired)")
                    listener?.onProbeLost(peerId, probe.sequenceNumber, probe.t0)
                }
            }
        }
    }

    /**
     * Sweeps all remaining pending probes for [peerId] when the burst concludes.
     */
    private fun sweepAllRemainingProbes(peerId: String): List<PendingProbe> {
        val peerPending = pendingProbes[peerId] ?: return emptyList()
        val remaining = peerPending.values.toList()
        peerPending.clear()
        return remaining
    }

    /**
     * Returns an immutable copy of all gathered [ProbeSample]s for [peerId].
     */
    fun getSamplesForPeer(peerId: String): List<ProbeSample> {
        return peerSamples[peerId]?.toList() ?: emptyList()
    }

    /**
     * Returns accumulated statistics for [peerId].
     */
    fun getPeerStats(peerId: String): PeerCalibrationStats {
        val sent = peerSentCounters[peerId]?.get() ?: 0
        val lost = peerLostCounters[peerId]?.get() ?: 0
        val samples = getSamplesForPeer(peerId)
        return PeerCalibrationStats(
            peerId = peerId,
            totalProbesSent = sent,
            samplesReceived = samples.size,
            probesLost = lost,
            samples = samples
        )
    }

    /**
     * Returns accumulated statistics for all known peers.
     */
    fun getAllPeerStats(): Map<String, PeerCalibrationStats> {
        val allPeers = mutableSetOf<String>().apply {
            addAll(peerSentCounters.keys)
            addAll(peerSamples.keys)
            addAll(pendingProbes.keys)
        }
        return allPeers.associateWith { getPeerStats(it) }
    }

    /**
     * Clears all state and history for [peerId].
     */
    fun clearPeer(peerId: String) {
        pendingProbes.remove(peerId)
        peerSamples.remove(peerId)
        peerSentCounters.remove(peerId)
        peerLostCounters.remove(peerId)
    }

    /**
     * Clears all engine state across all peers.
     */
    fun reset() {
        pendingProbes.clear()
        peerSamples.clear()
        peerSentCounters.clear()
        peerLostCounters.clear()
    }

    /**
     * Closes the engine and cancels active coroutine work.
     */
    override fun close() {
        reset()
        scope.cancel()
    }
}
