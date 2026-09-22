package com.roombeat.app.network.multicast

import android.util.Log
import com.roombeat.app.protocol.PacketSerializer
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.protocol.RoomBeatPacket.MulticastProbeReport
import com.roombeat.app.protocol.RoomBeatPacket.MulticastTestBeacon
import com.roombeat.app.system.LockHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Socket datagram representation decoupling platform UDP sockets from JVM unit tests.
 */
data class MulticastDatagramPacket(
    val data: ByteArray,
    val address: String,
    val port: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MulticastDatagramPacket
        if (!data.contentEquals(other.data)) return false
        if (address != other.address) return false
        if (port != other.port) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + port
        return result
    }
}

/**
 * Clean transport/socket abstraction allowing multicast probe tests to run 100% headlessly
 * on the JVM without requiring physical Wi-Fi hardware, Android permissions, or socket binding.
 */
interface MulticastSocketWrapper : AutoCloseable {
    val isClosed: Boolean
    fun joinGroup(multicastAddress: String, port: Int)
    fun leaveGroup(multicastAddress: String, port: Int)
    fun send(data: ByteArray, targetAddress: String, port: Int)
    fun receive(bufferSize: Int = 1024, timeoutMs: Int = 200): MulticastDatagramPacket?
    override fun close()
}

/**
 * Production implementation of [MulticastSocketWrapper] backed by standard [java.net.MulticastSocket].
 */
class RealMulticastSocketWrapper(
    private val boundPort: Int? = null
) : MulticastSocketWrapper {

    companion object {
        private const val TAG = "RealMulticastSocket"
    }

    private var socket: MulticastSocket? = null
    private val closed = AtomicBoolean(false)

    override val isClosed: Boolean get() = closed.get() || socket?.isClosed == true

    private fun getOrCreateSocket(): MulticastSocket {
        synchronized(this) {
            val existing = socket
            if (existing != null && !existing.isClosed) {
                return existing
            }
            val newSocket = if (boundPort != null && boundPort > 0) {
                MulticastSocket(boundPort)
            } else {
                MulticastSocket()
            }
            newSocket.timeToLive = 2 // Local LAN subnet scope
            socket = newSocket
            return newSocket
        }
    }

    override fun joinGroup(multicastAddress: String, port: Int) {
        if (isClosed) return
        try {
            val s = getOrCreateSocket()
            val group = InetAddress.getByName(multicastAddress)
            s.joinGroup(group)
            Log.d(TAG, "Joined multicast group $multicastAddress:$port")
        } catch (e: Exception) {
            Log.w(TAG, "Error joining multicast group $multicastAddress: ${e.message}")
            throw e
        }
    }

    override fun leaveGroup(multicastAddress: String, port: Int) {
        val s = socket ?: return
        if (s.isClosed) return
        try {
            val group = InetAddress.getByName(multicastAddress)
            s.leaveGroup(group)
            Log.d(TAG, "Left multicast group $multicastAddress:$port")
        } catch (e: Exception) {
            Log.w(TAG, "Error leaving multicast group $multicastAddress: ${e.message}")
        }
    }

    override fun send(data: ByteArray, targetAddress: String, port: Int) {
        if (isClosed) throw IOException("Socket is closed")
        try {
            val s = getOrCreateSocket()
            val group = InetAddress.getByName(targetAddress)
            val packet = DatagramPacket(data, data.size, group, port)
            s.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "Error sending multicast packet to $targetAddress:$port: ${e.message}")
            throw e
        }
    }

    override fun receive(bufferSize: Int, timeoutMs: Int): MulticastDatagramPacket? {
        if (isClosed) return null
        return try {
            val s = getOrCreateSocket()
            s.soTimeout = timeoutMs
            val buffer = ByteArray(bufferSize)
            val packet = DatagramPacket(buffer, buffer.size)
            s.receive(packet)
            MulticastDatagramPacket(
                data = buffer.copyOf(packet.length),
                address = packet.address?.hostAddress ?: "",
                port = packet.port
            )
        } catch (_: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            if (!isClosed) {
                Log.w(TAG, "Receive error: ${e.message}")
            }
            null
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket?.close()
            } catch (_: Exception) {}
            socket = null
        }
    }
}

/**
 * Deterministic in-memory test double of [MulticastSocketWrapper] for JVM testing.
 */
class FakeMulticastSocketWrapper : MulticastSocketWrapper {
    private val closed = AtomicBoolean(false)
    val joinedGroups = CopyOnWriteArrayList<String>()
    val sentPackets = CopyOnWriteArrayList<MulticastDatagramPacket>()
    val incomingQueue = LinkedBlockingQueue<MulticastDatagramPacket>()

    var linkedPeer: FakeMulticastSocketWrapper? = null
    var dropFilter: ((seq: Int) -> Boolean)? = null

    override val isClosed: Boolean get() = closed.get()

    override fun joinGroup(multicastAddress: String, port: Int) {
        if (!isClosed) {
            joinedGroups.add("$multicastAddress:$port")
        }
    }

    override fun leaveGroup(multicastAddress: String, port: Int) {
        joinedGroups.remove("$multicastAddress:$port")
    }

    override fun send(data: ByteArray, targetAddress: String, port: Int) {
        if (isClosed) throw IOException("Socket is closed")
        val packet = MulticastDatagramPacket(data, targetAddress, port)
        sentPackets.add(packet)

        // Route to linked peer if connected
        val peer = linkedPeer
        if (peer != null && !peer.isClosed) {
            val beacon = decodeBeacon(data)
            if (beacon != null && dropFilter?.invoke(beacon.seq) == true) {
                // Drop packet intentionally for test simulation
                return
            }
            peer.incomingQueue.offer(packet)
        }
    }

    override fun receive(bufferSize: Int, timeoutMs: Int): MulticastDatagramPacket? {
        if (isClosed) return null
        return try {
            incomingQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }
    }

    fun enqueuePacket(packet: MulticastDatagramPacket) {
        if (!isClosed) {
            incomingQueue.offer(packet)
        }
    }

    fun enqueueBeacon(
        beacon: MulticastTestBeacon,
        address: String = MulticastHealthProbe.DEFAULT_MULTICAST_ADDR,
        port: Int = MulticastHealthProbe.DEFAULT_MULTICAST_PORT
    ) {
        val bytes = MulticastHealthProbe.encodeBeacon(beacon)
        enqueuePacket(MulticastDatagramPacket(bytes, address, port))
    }

    fun link(other: FakeMulticastSocketWrapper) {
        this.linkedPeer = other
        other.linkedPeer = this
    }

    private fun decodeBeacon(bytes: ByteArray): MulticastTestBeacon? =
        MulticastHealthProbe.decodeBeacon(bytes)

    override fun close() {
        closed.set(true)
        incomingQueue.clear()
    }
}

/**
 * Health assessment status of local multicast delivery.
 */
enum class MulticastHealthStatus {
    IDLE,
    PROBING,
    HEALTHY, // Equivalent to PASSED
    BLOCKED, // Equivalent to MULTICAST_BLOCKED
    ERROR;

    val isPassed: Boolean get() = this == HEALTHY
    val isBlocked: Boolean get() = this == BLOCKED

    companion object {
        val PASSED = HEALTHY
        val MULTICAST_BLOCKED = BLOCKED
    }
}

/**
 * Diagnostic state model tracking active or completed multicast probe execution.
 */
data class MulticastProbeState(
    val status: MulticastHealthStatus = MulticastHealthStatus.IDLE,
    val probesSent: Int = 0,
    val probesReceived: Int = 0,
    val totalProbes: Int = MulticastHealthProbe.DEFAULT_PROBE_COUNT,
    val receptionRate: Double = 0.0,
    val failureReason: String? = null
)

/**
 * Result model summarizing a completed multicast probe burst.
 */
data class MulticastProbeResult(
    val status: MulticastHealthStatus,
    val totalSent: Int,
    val receivedCount: Int,
    val receptionRate: Double,
    val durationMs: Long,
    val details: String = ""
) {
    val isPassed: Boolean get() = status == MulticastHealthStatus.HEALTHY
    val isBlocked: Boolean get() = status == MulticastHealthStatus.BLOCKED
}

/**
 * 2-Second UDP Multicast Transmission and Reception Probe Engine.
 *
 * Detects router AP Isolation, IGMP snooping packet filtering, and airtime dropouts
 * prior to synchronized audio streaming by executing a tagged UDP multicast test beacon burst.
 *
 * Requirements satisfied:
 * 1. Broadcasts 10 tagged UDP multicast test packets (`MULTICAST_TEST_BEACON` payload with sequence number,
 *    room/session ID, timestamp) on the room's multicast IP/port during calibration over a ~2-second window.
 * 2. Listens on client nodes for test packets and reports reception count back to the host.
 * 3. Evaluates reception health: If reception rate is < 80% (dropped/blocked by router or AP isolation),
 *    transitions health state to `MULTICAST_BLOCKED`; if >= 80% (e.g. 100%), health status transitions to `PASSED` / `HEALTHY`.
 * 4. Provides a clean transport/socket abstraction (`MulticastSocketWrapper`) allowing 100% headless JVM unit tests.
 * 5. Includes Android `WifiManager.MulticastLock` acquire/release scaffolding via [LockHandle].
 */
class MulticastHealthProbe(
    val sessionId: String,
    val deviceId: String = "host",
    val multicastAddress: String = DEFAULT_MULTICAST_ADDR,
    val multicastPort: Int = DEFAULT_MULTICAST_PORT,
    val totalProbeCount: Int = DEFAULT_PROBE_COUNT,
    val probeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val passThresholdRate: Double = PASS_THRESHOLD_RATE,
    socketWrapper: MulticastSocketWrapper? = null,
    val lockHandle: LockHandle? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : AutoCloseable {

    companion object {
        const val DEFAULT_MULTICAST_ADDR = "239.255.42.99"
        const val DEFAULT_MULTICAST_PORT = 50042
        const val DEFAULT_PROBE_COUNT = 10
        const val DEFAULT_PROBE_INTERVAL_MS = 180L // 10 probes * 180ms = 1.8s, within 2s window
        const val DEFAULT_TIMEOUT_MS = 2500L
        const val PASS_THRESHOLD_RATE = 0.80 // 80% threshold: >=80% passed, <80% blocked

        /**
         * Serializes a [MulticastTestBeacon] into UTF-8 JSON bytes ready for DatagramPacket transmission.
         */
        fun encodeBeacon(beacon: MulticastTestBeacon): ByteArray {
            return PacketSerializer.serializeToBytes(beacon)
        }

        /**
         * Deserializes raw datagram bytes into a [MulticastTestBeacon], returning `null` on corruption.
         */
        fun decodeBeacon(data: ByteArray): MulticastTestBeacon? {
            val packet = PacketSerializer.deserialize(data)
            return packet as? MulticastTestBeacon
        }

        /**
         * Evaluates whether a reception rate meets the pass threshold (>= 80%).
         */
        fun evaluateRate(
            receptionRate: Double,
            threshold: Double = PASS_THRESHOLD_RATE
        ): MulticastHealthStatus {
            return if (receptionRate >= threshold) {
                MulticastHealthStatus.PASSED
            } else {
                MulticastHealthStatus.BLOCKED
            }
        }

        /**
         * Evaluates reception count out of total expected packets.
         */
        fun evaluateCount(
            receivedCount: Int,
            totalSent: Int,
            threshold: Double = PASS_THRESHOLD_RATE
        ): MulticastHealthStatus {
            if (totalSent <= 0) return MulticastHealthStatus.BLOCKED
            val rate = receivedCount.toDouble() / totalSent
            return evaluateRate(rate, threshold)
        }
    }

    private val socket: MulticastSocketWrapper = socketWrapper ?: RealMulticastSocketWrapper(multicastPort)
    private val isExternalSocket = socketWrapper != null

    private val _state = MutableStateFlow(
        MulticastProbeState(
            status = MulticastHealthStatus.IDLE,
            totalProbes = totalProbeCount
        )
    )
    val state: StateFlow<MulticastProbeState> = _state.asStateFlow()

    private val _healthStatus = MutableStateFlow(MulticastHealthStatus.IDLE)
    val healthStatus: StateFlow<MulticastHealthStatus> = _healthStatus.asStateFlow()

    private fun updateState(newState: MulticastProbeState) {
        _state.value = newState
        _healthStatus.value = newState.status
    }

    private val clientReports = ConcurrentHashMap<String, MulticastProbeReport>()

    /**
     * Acquires Android's [WifiManager.MulticastLock] if provided.
     * Prevents Wi-Fi chip power-saving filters from dropping multicast packets.
     */
    fun acquireLock(): Boolean {
        return lockHandle?.let {
            if (!it.isHeld) {
                it.acquire()
                true
            } else {
                true
            }
        } ?: false
    }

    /**
     * Safely releases the [WifiManager.MulticastLock].
     */
    fun releaseLock(): Boolean {
        return lockHandle?.let {
            if (it.isHeld) {
                it.release()
                true
            } else {
                false
            }
        } ?: false
    }

    /**
     * Resets the probe state back to [MulticastHealthStatus.IDLE].
     */
    fun reset() {
        clientReports.clear()
        updateState(
            MulticastProbeState(
                status = MulticastHealthStatus.IDLE,
                totalProbes = totalProbeCount
            )
        )
    }

    /**
     * Broadcasts a burst of [totalProbeCount] tagged UDP multicast test packets over ~2 seconds.
     *
     * @param onPacketSent Optional callback invoked after each packet transmission with its sequence number.
     * @return Number of packets transmitted.
     */
    suspend fun broadcastProbeBurst(
        onPacketSent: ((seq: Int) -> Unit)? = null
    ): Int = withContext(ioDispatcher) {
        acquireLock()
        updateState(
            _state.value.copy(
                status = MulticastHealthStatus.PROBING,
                probesSent = 0,
                probesReceived = 0,
                receptionRate = 0.0,
                failureReason = null
            )
        )

        var sentCount = 0
        try {
            for (seq in 1..totalProbeCount) {
                val beacon = MulticastTestBeacon(
                    seq = seq,
                    sessionId = sessionId,
                    timestampMs = timeProvider(),
                    totalBurst = totalProbeCount
                )
                val payload = encodeBeacon(beacon)
                socket.send(payload, multicastAddress, multicastPort)
                sentCount++
                updateState(_state.value.copy(probesSent = sentCount))
                onPacketSent?.invoke(seq)

                if (seq < totalProbeCount) {
                    delay(probeIntervalMs)
                }
            }
        } catch (e: Exception) {
            updateState(
                _state.value.copy(
                    status = MulticastHealthStatus.ERROR,
                    failureReason = "Broadcast failed: ${e.message}"
                )
            )
            throw e
        }
        sentCount
    }

    /**
     * Listens on client nodes for test packets over a reception window,
     * calculates reception rate, transitions state, and produces a [MulticastProbeReport].
     *
     * @param expectedCount Total expected test beacon count.
     * @param listenDurationMs Maximum listen duration before finalizing the probe.
     * @param onBeaconReceived Callback invoked each time a valid beacon is received.
     * @return [MulticastProbeReport] containing reception metrics.
     */
    suspend fun listenForProbeBurst(
        expectedCount: Int = totalProbeCount,
        listenDurationMs: Long = timeoutMs,
        onBeaconReceived: ((MulticastTestBeacon) -> Unit)? = null
    ): MulticastProbeReport = withContext(ioDispatcher) {
        acquireLock()
        updateState(
            _state.value.copy(
                status = MulticastHealthStatus.PROBING,
                probesReceived = 0,
                totalProbes = expectedCount,
                receptionRate = 0.0,
                failureReason = null
            )
        )

        val receivedSeqs = mutableSetOf<Int>()
        val startTime = timeProvider()
        val deadline = startTime + listenDurationMs

        try {
            socket.joinGroup(multicastAddress, multicastPort)

            while (timeProvider() < deadline && receivedSeqs.size < expectedCount) {
                val remainingMs = (deadline - timeProvider()).coerceAtLeast(10L).toInt()
                val packetTimeout = remainingMs.coerceAtMost(200)

                val packet = socket.receive(bufferSize = 1024, timeoutMs = packetTimeout)
                if (packet != null) {
                    val beacon = decodeBeacon(packet.data)
                    if (beacon != null && beacon.sessionId == this@MulticastHealthProbe.sessionId) {
                        if (receivedSeqs.add(beacon.seq)) {
                            onBeaconReceived?.invoke(beacon)
                            val currentRate = receivedSeqs.size.toDouble() / expectedCount
                            updateState(
                                _state.value.copy(
                                    probesReceived = receivedSeqs.size,
                                    receptionRate = currentRate
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            try {
                socket.leaveGroup(multicastAddress, multicastPort)
            } catch (_: Exception) {}
        }

        val receivedCount = receivedSeqs.size
        val receptionRate = if (expectedCount > 0) receivedCount.toDouble() / expectedCount else 0.0
        val isPassed = receptionRate >= passThresholdRate
        val finalStatus = if (isPassed) MulticastHealthStatus.PASSED else MulticastHealthStatus.BLOCKED

        val failureReason = if (!isPassed) {
            "Multicast reception ${(receptionRate * 100).toInt()}% is below 80% threshold ($receivedCount/$expectedCount received)"
        } else {
            null
        }

        updateState(
            _state.value.copy(
                status = finalStatus,
                probesReceived = receivedCount,
                totalProbes = expectedCount,
                receptionRate = receptionRate,
                failureReason = failureReason
            )
        )

        MulticastProbeReport(
            deviceId = deviceId,
            sessionId = sessionId,
            receivedCount = receivedCount,
            totalSent = expectedCount,
            receptionRate = receptionRate,
            isBlocked = !isPassed
        )
    }

    /**
     * Evaluates a reception report from a client node, updating local health state.
     * If reception rate is < 80%, transitions health state to [MulticastHealthStatus.BLOCKED].
     * If >= 80%, transitions health state to [MulticastHealthStatus.PASSED].
     */
    fun evaluateClientReport(report: MulticastProbeReport): MulticastHealthStatus {
        clientReports[report.deviceId] = report
        val isPassed = report.receptionRate >= passThresholdRate && !report.isBlocked
        val newStatus = if (isPassed) MulticastHealthStatus.PASSED else MulticastHealthStatus.BLOCKED
        val reason = if (!isPassed) {
            "Client '${report.deviceId}' reported ${(report.receptionRate * 100).toInt()}% reception (${report.receivedCount}/${report.totalSent})"
        } else {
            null
        }

        updateState(
            _state.value.copy(
                status = newStatus,
                probesReceived = report.receivedCount,
                totalProbes = report.totalSent,
                receptionRate = report.receptionRate,
                failureReason = reason
            )
        )
        return newStatus
    }

    /**
     * Evaluates multiple client reception reports (e.g. from all connected room peers).
     * If ANY client is blocked (< 80%), overall health status is marked [MulticastHealthStatus.BLOCKED]
     * because router AP isolation or dropouts compromise room-wide acoustic sync.
     */
    fun evaluateMultipleReports(reports: List<MulticastProbeReport>): MulticastHealthStatus {
        if (reports.isEmpty()) {
            val status = MulticastHealthStatus.BLOCKED
            updateState(
                _state.value.copy(
                    status = status,
                    failureReason = "No client probe reports received within timeout"
                )
            )
            return status
        }

        reports.forEach { clientReports[it.deviceId] = it }
        val anyBlocked = reports.any { it.receptionRate < passThresholdRate || it.isBlocked }
        val status = if (!anyBlocked) MulticastHealthStatus.PASSED else MulticastHealthStatus.BLOCKED
        val minRate = reports.minOf { it.receptionRate }
        val totalReceived = reports.sumOf { it.receivedCount }
        val totalExpected = reports.sumOf { it.totalSent }

        val reason = if (anyBlocked) {
            val failing = reports.filter { it.receptionRate < passThresholdRate || it.isBlocked }
            "Router AP isolation detected: ${failing.size}/${reports.size} clients received < 80% packets"
        } else {
            null
        }

        updateState(
            _state.value.copy(
                status = status,
                probesReceived = totalReceived,
                totalProbes = totalExpected,
                receptionRate = minRate,
                failureReason = reason
            )
        )
        return status
    }

    override fun close() {
        releaseLock()
        if (!isExternalSocket) {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}
