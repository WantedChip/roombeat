package com.roombeat.app.stress

import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.session.PeerSessionTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Diagnostics telemetry snapshot summarizing network traffic and chaos injection events.
 */
data class ChaosNetworkTelemetry(
    val packetsSent: Long = 0L,
    val packetsDelivered: Long = 0L,
    val packetsDropped: Long = 0L,
    val jitterSpikesInjected: Long = 0L,
    val disconnectEvents: Long = 0L,
    val averageLatencyMs: Double = 0.0,
    val maxLatencyMs: Double = 0.0,
    val activePeerCount: Int = 0,
    val disconnectedPeerCount: Int = 0
)

/**
 * Report generated when a compound chaos scenario is triggered.
 */
data class ChaosScenarioReport(
    val disconnectedPeerIds: List<String>,
    val jitterSpikeMs: Long,
    val affectedPeerIds: List<String>,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Network transit delay and drop injection simulator for RoomBeat multi-device stress testing.
 *
 * Implements [PeerSessionTransport] to proxy and intercept control plane packets and audio chunks
 * between the Host device and 8+ client peer nodes.
 *
 * Capabilities:
 * 1. Simulates realistic local Wi-Fi / Hotspot network conditions (5-15ms baseline RTT + jitter).
 * 2. Injects synthetic Wi-Fi jitter spikes (e.g. 50ms) to stress scheduled jitter buffers.
 * 3. Simulates sudden, ungraceful peer node disconnections and packet drops.
 * 4. Tracks complete delivery and latency telemetry across all peers.
 */
class ChaosNetworkSimulator(
    var baselineLatencyMs: Long = 8L,
    var jitterRangeMs: Long = 4L,
    var packetLossRate: Double = 0.0,
    private val random: Random = Random(42)
) : PeerSessionTransport {

    // Registered peer packet receivers: peerId -> handler
    private val peerReceivers = ConcurrentHashMap<String, (RoomBeatPacket) -> Unit>()
    private var hostReceiver: ((String, RoomBeatPacket) -> Unit)? = null

    // Connection state per peer
    private val connectedPeers = ConcurrentHashMap.newKeySet<String>()
    private val disconnectedPeers = ConcurrentHashMap.newKeySet<String>()

    // Active synthetic jitter spikes: peerId -> extraDelayMs (or "all" for global)
    private val activeJitterSpikes = ConcurrentHashMap<String, Long>()

    // Telemetry counters
    private val _packetsSent = AtomicLong(0L)
    private val _packetsDelivered = AtomicLong(0L)
    private val _packetsDropped = AtomicLong(0L)
    private val _jitterSpikesInjected = AtomicLong(0L)
    private val _disconnectEvents = AtomicLong(0L)
    private val latencyHistory = ConcurrentLinkedQueue<Double>()

    /**
     * Registers a peer client node with the network simulator.
     */
    fun registerPeer(peerId: String, receiver: (RoomBeatPacket) -> Unit) {
        peerReceivers[peerId] = receiver
        connectedPeers.add(peerId)
        disconnectedPeers.remove(peerId)
    }

    /**
     * Registers the host node packet receiver.
     */
    fun registerHostReceiver(receiver: (String, RoomBeatPacket) -> Unit) {
        hostReceiver = receiver
    }

    /**
     * Unregisters a peer from the simulator.
     */
    fun unregisterPeer(peerId: String) {
        peerReceivers.remove(peerId)
        connectedPeers.remove(peerId)
        disconnectedPeers.remove(peerId)
        activeJitterSpikes.remove(peerId)
    }

    /**
     * Simulates sudden ungraceful disconnection of a peer (e.g. Wi-Fi drop or app kill).
     */
    override fun disconnectPeer(peerId: String, reason: String?) {
        if (connectedPeers.remove(peerId)) {
            disconnectedPeers.add(peerId)
            _disconnectEvents.incrementAndGet()
        }
    }

    /**
     * Reconnects a previously disconnected peer.
     */
    fun reconnectPeer(peerId: String) {
        if (peerReceivers.containsKey(peerId)) {
            disconnectedPeers.remove(peerId)
            connectedPeers.add(peerId)
        }
    }

    /**
     * Checks if a peer is currently connected and reachable.
     */
    fun isPeerConnected(peerId: String): Boolean = connectedPeers.contains(peerId)

    /**
     * Returns a snapshot of all currently connected peer IDs.
     */
    fun getConnectedPeers(): Set<String> = connectedPeers.toSet()

    /**
     * Returns a snapshot of all currently disconnected peer IDs.
     */
    fun getDisconnectedPeers(): Set<String> = disconnectedPeers.toSet()

    /**
     * Randomly disconnects [count] currently connected peers.
     * Returns the list of disconnected peer IDs.
     */
    fun severRandomPeers(count: Int = 2, targetCandidates: List<String>? = null): List<String> {
        val candidates = targetCandidates?.filter { connectedPeers.contains(it) }
            ?: connectedPeers.toList()
        val toSever = candidates.shuffled(random).take(count)
        for (id in toSever) {
            disconnectPeer(id, "Chaos injection: random peer severed")
        }
        return toSever
    }

    /**
     * Injects a synthetic Wi-Fi latency spike (default: 50ms) for a specific peer or globally.
     *
     * @param peerId Target peer ID, or null / "all" for all connected peers.
     * @param spikeMs Additional latency in milliseconds to add to transit time.
     */
    fun injectJitterSpike(peerId: String? = null, spikeMs: Long = 50L) {
        val target = peerId ?: "all"
        activeJitterSpikes[target] = spikeMs
        _jitterSpikesInjected.incrementAndGet()
    }

    /**
     * Clears all active synthetic jitter spikes.
     */
    fun clearJitterSpikes() {
        activeJitterSpikes.clear()
    }

    /**
     * Triggers a comprehensive chaos scenario:
     * 1. Randomly disconnects [disconnectedCount] peers (default: 2).
     * 2. Injects [jitterSpikeMs] synthetic Wi-Fi jitter spikes (default: 50ms) across remaining active peers.
     */
    fun triggerChaosScenario(
        disconnectedCount: Int = 2,
        jitterSpikeMs: Long = 50L,
        targetCandidates: List<String>? = null
    ): ChaosScenarioReport {
        val severed = severRandomPeers(disconnectedCount, targetCandidates)
        val remaining = connectedPeers.toList()
        for (peerId in remaining) {
            injectJitterSpike(peerId, jitterSpikeMs)
        }
        return ChaosScenarioReport(
            disconnectedPeerIds = severed,
            jitterSpikeMs = jitterSpikeMs,
            affectedPeerIds = remaining
        )
    }

    /**
     * Transmits a packet to a specific peer with simulated network delay, jitter, and drop probability.
     */
    override fun sendToPeer(peerId: String, packet: RoomBeatPacket): Boolean {
        _packetsSent.incrementAndGet()

        if (!connectedPeers.contains(peerId)) {
            _packetsDropped.incrementAndGet()
            return false
        }

        if (packetLossRate > 0.0 && random.nextDouble() < packetLossRate) {
            _packetsDropped.incrementAndGet()
            return false
        }

        val latency = calculateLatencyMs(peerId)
        recordLatency(latency)

        val receiver = peerReceivers[peerId]
        if (receiver != null) {
            receiver.invoke(packet)
            _packetsDelivered.incrementAndGet()
            return true
        } else {
            _packetsDropped.incrementAndGet()
            return false
        }
    }

    /**
     * Sends a packet from a client peer towards the host receiver.
     */
    fun sendToHost(senderId: String, packet: RoomBeatPacket): Boolean {
        _packetsSent.incrementAndGet()

        if (!connectedPeers.contains(senderId)) {
            _packetsDropped.incrementAndGet()
            return false
        }

        if (packetLossRate > 0.0 && random.nextDouble() < packetLossRate) {
            _packetsDropped.incrementAndGet()
            return false
        }

        val latency = calculateLatencyMs(senderId)
        recordLatency(latency)

        val host = hostReceiver
        if (host != null) {
            host.invoke(senderId, packet)
            _packetsDelivered.incrementAndGet()
            return true
        } else {
            _packetsDropped.incrementAndGet()
            return false
        }
    }

    /**
     * Broadcasts a packet to all currently connected peers.
     */
    override fun broadcastToAll(packet: RoomBeatPacket): Int {
        var deliveredCount = 0
        for (peerId in connectedPeers) {
            if (sendToPeer(peerId, packet)) {
                deliveredCount++
            }
        }
        return deliveredCount
    }

    /**
     * Routes an audio chunk to target peers, simulating UDP multicast with network jitter and drops.
     * Returns the count of peers that successfully received the audio chunk.
     */
    fun routeAudioChunk(
        seq: Long,
        presentationTimeUs: Long,
        opusData: ByteArray,
        targetPeerReceivers: Map<String, (Long, Long, ByteArray) -> Boolean>
    ): Int {
        var deliveredCount = 0
        for ((peerId, chunkHandler) in targetPeerReceivers) {
            _packetsSent.incrementAndGet()

            if (!connectedPeers.contains(peerId)) {
                _packetsDropped.incrementAndGet()
                continue
            }

            if (packetLossRate > 0.0 && random.nextDouble() < packetLossRate) {
                _packetsDropped.incrementAndGet()
                continue
            }

            val latency = calculateLatencyMs(peerId)
            recordLatency(latency)

            val accepted = chunkHandler.invoke(seq, presentationTimeUs, opusData)
            if (accepted) {
                _packetsDelivered.incrementAndGet()
                deliveredCount++
            } else {
                _packetsDropped.incrementAndGet()
            }
        }
        return deliveredCount
    }

    /**
     * Calculates transit latency for a packet directed to or from [peerId].
     */
    fun calculateLatencyMs(peerId: String): Double {
        val jitter = if (jitterRangeMs > 0) {
            (random.nextDouble() * 2.0 - 1.0) * jitterRangeMs
        } else {
            0.0
        }
        val spike = activeJitterSpikes[peerId] ?: activeJitterSpikes["all"] ?: 0L
        return maxOf(0.5, baselineLatencyMs.toDouble() + jitter + spike.toDouble())
    }

    private fun recordLatency(latencyMs: Double) {
        latencyHistory.add(latencyMs)
        // Keep a rolling window of recent samples to bound memory in long stress tests
        while (latencyHistory.size > 2000) {
            latencyHistory.poll()
        }
    }

    /**
     * Returns a comprehensive telemetry snapshot of network conditions and counters.
     */
    fun getTelemetry(): ChaosNetworkTelemetry {
        val latencies = latencyHistory.toList()
        val avgLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0
        val maxLatency = if (latencies.isNotEmpty()) latencies.maxOrNull() ?: 0.0 else 0.0

        return ChaosNetworkTelemetry(
            packetsSent = _packetsSent.get(),
            packetsDelivered = _packetsDelivered.get(),
            packetsDropped = _packetsDropped.get(),
            jitterSpikesInjected = _jitterSpikesInjected.get(),
            disconnectEvents = _disconnectEvents.get(),
            averageLatencyMs = avgLatency,
            maxLatencyMs = maxLatency,
            activePeerCount = connectedPeers.size,
            disconnectedPeerCount = disconnectedPeers.size
        )
    }

    /**
     * Resets all internal state and clears counters.
     */
    fun reset() {
        connectedPeers.clear()
        disconnectedPeers.clear()
        activeJitterSpikes.clear()
        _packetsSent.set(0L)
        _packetsDelivered.set(0L)
        _packetsDropped.set(0L)
        _jitterSpikesInjected.set(0L)
        _disconnectEvents.set(0L)
        latencyHistory.clear()
    }
}
