package com.roombeat.app.session

import com.roombeat.app.protocol.RoomBeatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle connection state of a peer device in the room.
 */
enum class PeerConnectionState {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

/**
 * Representation of a connected peer device node in the room matrix.
 */
data class PeerNode(
    val id: String,
    val name: String,
    val ip: String,
    val rttMs: Double = 0.0,
    val offsetMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val isSoloed: Boolean = false,
    val state: PeerConnectionState = PeerConnectionState.CONNECTED,
    val reconnectAttempt: Int = 0,
    val maxReconnectAttempts: Int = 3,
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    val isDisconnectedOrReconnecting: Boolean
        get() = state != PeerConnectionState.CONNECTED

    val formattedRtt: String
        get() = String.format(Locale.US, "±%.1fms · 0.0%% loss", rttMs)

    val formattedVolumeDb: String
        get() = volumeToDbString(if (isMuted) 0.0f else volume)

    companion object {
        fun volumeToDbString(vol: Float): String {
            if (vol <= 0.0001f) return "-inf dB"
            val db = 20.0 * kotlin.math.log10(vol.toDouble())
            return if (db >= 0.05) {
                String.format(Locale.US, "+%.1f dB", db)
            } else {
                String.format(Locale.US, "%.1f dB", db)
            }
        }
    }
}

/**
 * Standard Room Join error codes.
 */
object RoomJoinErrorCodes {
    const val ERR_INVALID_PIN = "ERR_INVALID_PIN"
    const val ERR_ROOM_FULL = "ERR_ROOM_FULL"
    const val ERR_SESSION_MISMATCH = "ERR_SESSION_MISMATCH"
}

/**
 * Transport abstraction allowing [PeerSessionManager] to send packets and manage
 * connections without direct coupling to raw socket implementations.
 */
interface PeerSessionTransport {
    fun sendToPeer(peerId: String, packet: RoomBeatPacket): Boolean
    fun broadcastToAll(packet: RoomBeatPacket): Int
    fun disconnectPeer(peerId: String, reason: String? = null)
}

/**
 * Listener interface for peer session lifecycle events.
 */
interface PeerSessionListener {
    fun onPeerJoined(peer: PeerNode) {}
    fun onPeerUpdated(peer: PeerNode) {}
    fun onPeerLeft(peerId: String, reason: String?) {}
    fun onPeerEvicted(peerId: String, reason: String?) {}
    fun onMasterVolumeChanged(volume: Float) {}
    fun onSessionEnded() {}
}

/**
 * Session coordinator managing peer device lifecycle, heartbeat keepalives,
 * join approval flows, and volume balancing across up to 8+ concurrent devices.
 */
class PeerSessionManager(
    val isHost: Boolean,
    val sessionId: String,
    val roomPin: String = "",
    val multicastAddress: String = DEFAULT_MULTICAST_ADDR,
    val multicastPort: Int = DEFAULT_MULTICAST_PORT,
    val maxPeers: Int = DEFAULT_MAX_PEERS,
    val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    val reconnectTimeoutMs: Long = DEFAULT_RECONNECT_TIMEOUT_MS,
    val disconnectTimeoutMs: Long = DEFAULT_DISCONNECT_TIMEOUT_MS,
    var transport: PeerSessionTransport? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    val autoStartHeartbeat: Boolean = false
) : AutoCloseable {
    companion object {
        const val DEFAULT_MULTICAST_ADDR = "239.255.42.99"
        const val DEFAULT_MULTICAST_PORT = 50042
        const val DEFAULT_MAX_PEERS = 8
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 2000L
        const val DEFAULT_RECONNECT_TIMEOUT_MS = 3000L
        const val DEFAULT_DISCONNECT_TIMEOUT_MS = 8000L
    }

    private val _peers = MutableStateFlow<List<PeerNode>>(emptyList())
    val peers: StateFlow<List<PeerNode>> = _peers.asStateFlow()

    private val _masterVolume = MutableStateFlow(1.0f)
    val masterVolume: StateFlow<Float> = _masterVolume.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, PeerNode>()
    private val peerIdCounter = AtomicInteger(0)

    var listener: PeerSessionListener? = null
    private var heartbeatJob: Job? = null

    val isHeartbeatRunning: Boolean
        get() = heartbeatJob?.isActive == true

    init {
        if (isHost && autoStartHeartbeat) {
            startHeartbeat()
        }
    }

    /**
     * Handles an incoming protocol packet from a peer.
     *
     * @param packet Deserialized [RoomBeatPacket].
     * @param senderId Identifier of the sending peer/socket.
     * @param senderAddress Remote IP address.
     * @param senderName Optional display name for the device.
     * @return Any immediate response packet generated (e.g. ACK, ERR, PONG), or null.
     */
    fun handleIncomingPacket(
        packet: RoomBeatPacket,
        senderId: String,
        senderAddress: String = "127.0.0.1",
        senderName: String? = null
    ): RoomBeatPacket? {
        return when (packet) {
            is RoomBeatPacket.RoomJoin -> handleRoomJoin(packet, senderId, senderAddress, senderName)
            is RoomBeatPacket.RoomJoinQr -> handleRoomJoinQr(packet, senderId, senderAddress, senderName)
            is RoomBeatPacket.PeerPing -> handlePeerPing(packet, senderId)
            is RoomBeatPacket.PeerPong -> handlePeerPong(packet, senderId)
            is RoomBeatPacket.SessionVolume -> handleSessionVolume(packet)
            is RoomBeatPacket.SessionMasterVolume -> handleSessionMasterVolume(packet)
            is RoomBeatPacket.RoomLeave -> handleRoomLeave(packet)
            is RoomBeatPacket.CalibResult -> handleCalibResult(packet, senderId)
            else -> null
        }
    }

    private fun handleRoomJoin(
        packet: RoomBeatPacket.RoomJoin,
        senderId: String,
        senderAddress: String,
        senderName: String?
    ): RoomBeatPacket? {
        if (!isHost) return null

        if (!PinGenerator.isValidPin(packet.code) || packet.code != roomPin) {
            val err = RoomBeatPacket.RoomJoinErr(
                errorCode = RoomJoinErrorCodes.ERR_INVALID_PIN,
                message = "Invalid 6-digit room PIN code"
            )
            transport?.sendToPeer(senderId, err)
            return err
        }

        if (peerMap.size >= maxPeers) {
            val err = RoomBeatPacket.RoomJoinErr(
                errorCode = RoomJoinErrorCodes.ERR_ROOM_FULL,
                message = "Room has reached maximum capacity of $maxPeers devices"
            )
            transport?.sendToPeer(senderId, err)
            return err
        }

        val assignedName = senderName ?: "Device ${peerIdCounter.incrementAndGet()}"
        val newNode = PeerNode(
            id = senderId,
            name = assignedName,
            ip = senderAddress,
            rttMs = 0.0,
            offsetMs = 0.0,
            volume = 1.0f,
            isMuted = false,
            state = PeerConnectionState.CONNECTED,
            lastSeenMs = timeProvider()
        )

        peerMap[senderId] = newNode
        syncPeersFlow()
        listener?.onPeerJoined(newNode)

        val ack = RoomBeatPacket.RoomJoinAck(
            sessionId = sessionId,
            hostTimeMs = timeProvider(),
            clientId = senderId,
            multicastAddr = multicastAddress,
            multicastPort = multicastPort
        )
        transport?.sendToPeer(senderId, ack)
        return ack
    }

    private fun handleRoomJoinQr(
        packet: RoomBeatPacket.RoomJoinQr,
        senderId: String,
        senderAddress: String,
        senderName: String?
    ): RoomBeatPacket? {
        if (!isHost) return null

        if (packet.sessionId != sessionId) {
            val err = RoomBeatPacket.RoomJoinErr(
                errorCode = RoomJoinErrorCodes.ERR_SESSION_MISMATCH,
                message = "Session ID mismatch"
            )
            transport?.sendToPeer(senderId, err)
            return err
        }

        if (!PinGenerator.isValidPin(packet.code) || packet.code != roomPin) {
            val err = RoomBeatPacket.RoomJoinErr(
                errorCode = RoomJoinErrorCodes.ERR_INVALID_PIN,
                message = "Invalid 6-digit room PIN code"
            )
            transport?.sendToPeer(senderId, err)
            return err
        }

        if (peerMap.size >= maxPeers) {
            val err = RoomBeatPacket.RoomJoinErr(
                errorCode = RoomJoinErrorCodes.ERR_ROOM_FULL,
                message = "Room has reached maximum capacity of $maxPeers devices"
            )
            transport?.sendToPeer(senderId, err)
            return err
        }

        val assignedName = senderName ?: "Device ${peerIdCounter.incrementAndGet()}"
        val newNode = PeerNode(
            id = senderId,
            name = assignedName,
            ip = senderAddress,
            rttMs = 0.0,
            offsetMs = 0.0,
            volume = 1.0f,
            isMuted = false,
            state = PeerConnectionState.CONNECTED,
            lastSeenMs = timeProvider()
        )

        peerMap[senderId] = newNode
        syncPeersFlow()
        listener?.onPeerJoined(newNode)

        val ack = RoomBeatPacket.RoomJoinAck(
            sessionId = sessionId,
            hostTimeMs = timeProvider(),
            clientId = senderId,
            multicastAddr = multicastAddress,
            multicastPort = multicastPort
        )
        transport?.sendToPeer(senderId, ack)
        return ack
    }

    private fun handlePeerPing(packet: RoomBeatPacket.PeerPing, senderId: String): RoomBeatPacket {
        val now = timeProvider()
        val pong = RoomBeatPacket.PeerPong(timestampMs = packet.timestampMs)
        transport?.sendToPeer(senderId, pong)

        val existing = peerMap[senderId]
        if (existing != null) {
            val updated = existing.copy(
                lastSeenMs = now,
                state = PeerConnectionState.CONNECTED,
                reconnectAttempt = 0
            )
            peerMap[senderId] = updated
            syncPeersFlow()
            listener?.onPeerUpdated(updated)
        }
        return pong
    }

    private fun handlePeerPong(packet: RoomBeatPacket.PeerPong, senderId: String): RoomBeatPacket? {
        val now = timeProvider()
        val rtt = (now - packet.timestampMs).coerceAtLeast(0).toDouble()
        val existing = peerMap[senderId]
        if (existing != null) {
            val updated = existing.copy(
                rttMs = rtt,
                lastSeenMs = now,
                state = PeerConnectionState.CONNECTED,
                reconnectAttempt = 0
            )
            peerMap[senderId] = updated
            syncPeersFlow()
            listener?.onPeerUpdated(updated)
        }
        return null
    }

    private fun handleSessionVolume(packet: RoomBeatPacket.SessionVolume): RoomBeatPacket? {
        val existing = peerMap[packet.deviceId]
        if (existing != null) {
            val updated = existing.copy(volume = packet.volumeLevel.coerceIn(0.0f, 2.0f))
            peerMap[packet.deviceId] = updated
            syncPeersFlow()
            listener?.onPeerUpdated(updated)
        }
        return null
    }

    private fun handleSessionMasterVolume(packet: RoomBeatPacket.SessionMasterVolume): RoomBeatPacket? {
        val clamped = packet.masterVolume.coerceIn(0.0f, 2.0f)
        _masterVolume.value = clamped
        listener?.onMasterVolumeChanged(clamped)
        return null
    }

    private fun handleRoomLeave(packet: RoomBeatPacket.RoomLeave): RoomBeatPacket? {
        removePeer(packet.deviceId, reason = "Graceful departure via ROOM_LEAVE")
        return null
    }

    private fun handleCalibResult(packet: RoomBeatPacket.CalibResult, senderId: String): RoomBeatPacket? {
        val existing = peerMap[senderId]
        if (existing != null) {
            val updated = existing.copy(
                offsetMs = packet.offsetMs,
                rttMs = packet.rttMs,
                jitterMs = packet.jitterMs,
                lastSeenMs = timeProvider(),
                state = PeerConnectionState.CONNECTED
            )
            peerMap[senderId] = updated
            syncPeersFlow()
            listener?.onPeerUpdated(updated)
        }
        return null
    }

    /**
     * Starts the periodic heartbeat ping loop.
     */
    fun startHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                performHeartbeatTick()
            }
        }
    }

    /**
     * Stops the heartbeat loop.
     */
    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Performs a single heartbeat cycle. Can be called directly in tests to verify
     * timeout transitions deterministically without waiting on real coroutine delays.
     */
    fun performHeartbeatTick() {
        val now = timeProvider()

        if (isHost) {
            // Broadcast PEER_PING to all active peers
            transport?.broadcastToAll(RoomBeatPacket.PeerPing(timestampMs = now))

            // Check liveness of each tracked peer
            val evictedPeers = mutableListOf<String>()

            for ((peerId, peer) in peerMap) {
                val elapsed = now - peer.lastSeenMs
                when {
                    elapsed >= disconnectTimeoutMs -> {
                        // Drop peer after 8 seconds timeout
                        evictedPeers.add(peerId)
                    }
                    elapsed >= reconnectTimeoutMs -> {
                        // Transition to RECONNECTING with retry counter (1/3, 2/3, 3/3)
                        val retryStep = (((elapsed - reconnectTimeoutMs) / 1500L) + 1).coerceIn(1L, 3L).toInt()
                        peerMap[peerId] = peer.copy(
                            state = PeerConnectionState.RECONNECTING,
                            reconnectAttempt = retryStep
                        )
                    }
                    else -> {
                        if (peer.state != PeerConnectionState.CONNECTED) {
                            peerMap[peerId] = peer.copy(
                                state = PeerConnectionState.CONNECTED,
                                reconnectAttempt = 0
                            )
                        }
                    }
                }
            }

            for (id in evictedPeers) {
                peerMap.remove(id)
                transport?.disconnectPeer(id, "Heartbeat timeout (>${disconnectTimeoutMs}ms)")
                listener?.onPeerEvicted(id, "Heartbeat timeout")
            }

            syncPeersFlow()
        }
    }

    /**
     * Sets volume for an individual peer channel (0.0 to 2.0 range).
     */
    fun setPeerVolume(peerId: String, volume: Float) {
        val clamped = volume.coerceIn(0.0f, 2.0f)
        val peer = peerMap[peerId] ?: return
        val updated = peer.copy(volume = clamped)
        peerMap[peerId] = updated
        syncPeersFlow()

        transport?.sendToPeer(peerId, RoomBeatPacket.SessionVolume(deviceId = peerId, volumeLevel = clamped))
        listener?.onPeerUpdated(updated)
    }

    /**
     * Toggles mute state for an individual peer channel.
     */
    fun togglePeerMute(peerId: String) {
        val peer = peerMap[peerId] ?: return
        setPeerMuted(peerId, !peer.isMuted)
    }

    /**
     * Explicitly sets mute state for an individual peer channel.
     */
    fun setPeerMuted(peerId: String, isMuted: Boolean) {
        val peer = peerMap[peerId] ?: return
        val effectiveVolume = if (isMuted) 0.0f else peer.volume
        val updated = peer.copy(isMuted = isMuted)
        peerMap[peerId] = updated
        syncPeersFlow()

        transport?.sendToPeer(peerId, RoomBeatPacket.SessionVolume(deviceId = peerId, volumeLevel = effectiveVolume))
        listener?.onPeerUpdated(updated)
    }

    /**
     * Toggles solo state for an individual peer channel.
     */
    fun togglePeerSolo(peerId: String) {
        val peer = peerMap[peerId] ?: return
        setPeerSolo(peerId, !peer.isSoloed)
    }

    /**
     * Explicitly sets solo state for an individual peer channel.
     */
    fun setPeerSolo(peerId: String, isSoloed: Boolean) {
        val peer = peerMap[peerId] ?: return
        val updated = peer.copy(isSoloed = isSoloed)
        peerMap[peerId] = updated
        syncPeersFlow()
        listener?.onPeerUpdated(updated)
    }

    /**
     * Broadcasts a protocol packet to all connected peers in the session.
     */
    fun broadcast(packet: RoomBeatPacket): Int {
        return transport?.broadcastToAll(packet) ?: 0
    }

    /**
     * Sets master volume across all nodes and broadcasts [RoomBeatPacket.SessionMasterVolume].
     */
    fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 2.0f)
        _masterVolume.value = clamped
        transport?.broadcastToAll(RoomBeatPacket.SessionMasterVolume(masterVolume = clamped))
        listener?.onMasterVolumeChanged(clamped)
    }

    /**
     * Kicks a peer from the session and notifies the peer.
     */
    fun kickPeer(peerId: String, reason: String = "Kicked by host") {
        transport?.sendToPeer(peerId, RoomBeatPacket.RoomLeave(deviceId = peerId))
        transport?.disconnectPeer(peerId, reason)
        removePeer(peerId, reason)
    }

    /**
     * Removes a peer from active tracking.
     */
    fun removePeer(peerId: String, reason: String? = null) {
        val removed = peerMap.remove(peerId)
        if (removed != null) {
            syncPeersFlow()
            listener?.onPeerLeft(peerId, reason)
        }
    }

    /**
     * Manually registers or updates a peer node.
     */
    fun registerPeer(peer: PeerNode) {
        peerMap[peer.id] = peer
        syncPeersFlow()
        listener?.onPeerJoined(peer)
    }

    /**
     * Tears down the session, stops heartbeats, and clears peer tracking.
     */
    fun leaveRoom() {
        stopHeartbeat()
        peerMap.clear()
        syncPeersFlow()
        listener?.onSessionEnded()
    }

    override fun close() {
        leaveRoom()
    }

    fun getPeer(peerId: String): PeerNode? = peerMap[peerId]

    fun getConnectedPeerCount(): Int = peerMap.size

    private val flowLock = Any()

    private fun syncPeersFlow() = synchronized(flowLock) {
        _peers.value = peerMap.values.toList()
    }
}
