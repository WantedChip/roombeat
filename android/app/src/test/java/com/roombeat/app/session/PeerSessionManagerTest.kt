package com.roombeat.app.session

import com.roombeat.app.protocol.RoomBeatPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PeerSessionManagerTest {

    private class FakePeerSessionTransport : PeerSessionTransport {
        val sentPackets = CopyOnWriteArrayList<Pair<String, RoomBeatPacket>>()
        val broadcastPackets = CopyOnWriteArrayList<RoomBeatPacket>()
        val disconnectedPeers = CopyOnWriteArrayList<Pair<String, String?>>()

        override fun sendToPeer(peerId: String, packet: RoomBeatPacket): Boolean {
            sentPackets.add(peerId to packet)
            return true
        }

        override fun broadcastToAll(packet: RoomBeatPacket): Int {
            broadcastPackets.add(packet)
            return 1
        }

        override fun disconnectPeer(peerId: String, reason: String?) {
            disconnectedPeers.add(peerId to reason)
        }

        fun clear() {
            sentPackets.clear()
            broadcastPackets.clear()
            disconnectedPeers.clear()
        }
    }

    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var transport: FakePeerSessionTransport
    private var simulatedTime: Long = 10_000_000L

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        transport = FakePeerSessionTransport()
        simulatedTime = 10_000_000L
    }

    private fun createHostManager(
        pin: String = "849201",
        sessionId: String = "session-host-1",
        maxPeers: Int = 8,
        reconnectTimeoutMs: Long = PeerSessionManager.DEFAULT_RECONNECT_TIMEOUT_MS,
        disconnectTimeoutMs: Long = PeerSessionManager.DEFAULT_DISCONNECT_TIMEOUT_MS
    ): PeerSessionManager {
        return PeerSessionManager(
            isHost = true,
            sessionId = sessionId,
            roomPin = pin,
            maxPeers = maxPeers,
            reconnectTimeoutMs = reconnectTimeoutMs,
            disconnectTimeoutMs = disconnectTimeoutMs,
            transport = transport,
            scope = testScope,
            timeProvider = { simulatedTime }
        )
    }

    @Test
    fun testRoomJoinValidPinReturnsAck() {
        val manager = createHostManager(pin = "123456", sessionId = "sess-01")

        val joinPacket = RoomBeatPacket.RoomJoin(code = "123456")
        val response = manager.handleIncomingPacket(
            packet = joinPacket,
            senderId = "peer-01",
            senderAddress = "192.168.1.50",
            senderName = "Pixel 8"
        )

        assertNotNull(response)
        assertTrue(response is RoomBeatPacket.RoomJoinAck)
        val ack = response as RoomBeatPacket.RoomJoinAck
        assertEquals("sess-01", ack.sessionId)
        assertEquals("peer-01", ack.clientId)
        assertEquals(PeerSessionManager.DEFAULT_MULTICAST_ADDR, ack.multicastAddr)
        assertEquals(PeerSessionManager.DEFAULT_MULTICAST_PORT, ack.multicastPort)

        assertEquals(1, manager.getConnectedPeerCount())
        val peer = manager.getPeer("peer-01")
        assertNotNull(peer)
        assertEquals("Pixel 8", peer?.name)
        assertEquals("192.168.1.50", peer?.ip)
        assertEquals(PeerConnectionState.CONNECTED, peer?.state)
    }

    @Test
    fun testRoomJoinInvalidPinReturnsErr() {
        val manager = createHostManager(pin = "849201")

        val joinPacket = RoomBeatPacket.RoomJoin(code = "000000")
        val response = manager.handleIncomingPacket(
            packet = joinPacket,
            senderId = "peer-bad-pin",
            senderAddress = "192.168.1.55"
        )

        assertNotNull(response)
        assertTrue(response is RoomBeatPacket.RoomJoinErr)
        val err = response as RoomBeatPacket.RoomJoinErr
        assertEquals(RoomJoinErrorCodes.ERR_INVALID_PIN, err.errorCode)
        assertEquals(0, manager.getConnectedPeerCount())
    }

    @Test
    fun testRoomJoinQrSessionMismatchReturnsErr() {
        val manager = createHostManager(pin = "849201", sessionId = "correct-session")

        val joinQrPacket = RoomBeatPacket.RoomJoinQr(
            code = "849201",
            sessionId = "wrong-session"
        )
        val response = manager.handleIncomingPacket(
            packet = joinQrPacket,
            senderId = "peer-qr",
            senderAddress = "192.168.1.60"
        )

        assertNotNull(response)
        assertTrue(response is RoomBeatPacket.RoomJoinErr)
        val err = response as RoomBeatPacket.RoomJoinErr
        assertEquals(RoomJoinErrorCodes.ERR_SESSION_MISMATCH, err.errorCode)
        assertEquals(0, manager.getConnectedPeerCount())
    }

    @Test
    fun testRoomJoinQrValidSessionAndPinReturnsAck() {
        val manager = createHostManager(pin = "849201", sessionId = "valid-session")

        val joinQrPacket = RoomBeatPacket.RoomJoinQr(
            code = "849201",
            sessionId = "valid-session"
        )
        val response = manager.handleIncomingPacket(
            packet = joinQrPacket,
            senderId = "peer-qr-ok",
            senderAddress = "192.168.1.61",
            senderName = "Galaxy S24"
        )

        assertNotNull(response)
        assertTrue(response is RoomBeatPacket.RoomJoinAck)
        assertEquals(1, manager.getConnectedPeerCount())
    }

    @Test
    fun testRoomCapacityLimitReturnsRoomFullError() {
        val manager = createHostManager(pin = "111222", maxPeers = 2)

        // Join first peer
        val p1 = manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("111222"), "peer-1")
        assertTrue(p1 is RoomBeatPacket.RoomJoinAck)

        // Join second peer
        val p2 = manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("111222"), "peer-2")
        assertTrue(p2 is RoomBeatPacket.RoomJoinAck)

        assertEquals(2, manager.getConnectedPeerCount())

        // Third peer attempts to join
        val p3 = manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("111222"), "peer-3")
        assertTrue(p3 is RoomBeatPacket.RoomJoinErr)
        val err = p3 as RoomBeatPacket.RoomJoinErr
        assertEquals(RoomJoinErrorCodes.ERR_ROOM_FULL, err.errorCode)
        assertEquals(2, manager.getConnectedPeerCount())
    }

    @Test
    fun testPeerPingPongRttCalculation() {
        val manager = createHostManager()

        // Register a peer
        manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("849201"), "peer-rtt", "192.168.1.10")

        // Peer sends ping at t0 = 10_000_000
        val ping = RoomBeatPacket.PeerPing(timestampMs = simulatedTime)
        val pong = manager.handleIncomingPacket(ping, "peer-rtt")
        assertTrue(pong is RoomBeatPacket.PeerPong)
        assertEquals(simulatedTime, (pong as RoomBeatPacket.PeerPong).timestampMs)

        // Simulate host receives pong 12ms later
        simulatedTime += 12L
        manager.handleIncomingPacket(RoomBeatPacket.PeerPong(timestampMs = 10_000_000L), "peer-rtt")

        val peer = manager.getPeer("peer-rtt")
        assertNotNull(peer)
        assertEquals(12.0, peer?.rttMs ?: 0.0, 0.001)
        assertEquals(PeerConnectionState.CONNECTED, peer?.state)
    }

    @Test
    fun testDisconnectTimeoutTransitionsToReconnectingAndEviction() {
        val manager = createHostManager(
            reconnectTimeoutMs = 3000L,
            disconnectTimeoutMs = 8000L
        )

        manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("849201"), "peer-timeout", "192.168.1.20")
        assertEquals(PeerConnectionState.CONNECTED, manager.getPeer("peer-timeout")?.state)

        // Advance simulated time by 3500ms (exceeds reconnect timeout of 3000ms)
        simulatedTime += 3500L
        manager.performHeartbeatTick()

        val peerAfter3s = manager.getPeer("peer-timeout")
        assertNotNull(peerAfter3s)
        assertEquals(PeerConnectionState.RECONNECTING, peerAfter3s?.state)
        assertEquals(1, peerAfter3s?.reconnectAttempt)

        // Advance simulated time to 8500ms total from start (exceeds disconnect timeout of 8000ms)
        simulatedTime += 5000L
        manager.performHeartbeatTick()

        // Clean eviction after 8s timeout
        assertNull(manager.getPeer("peer-timeout"))
        assertEquals(0, manager.getConnectedPeerCount())
        assertTrue(transport.disconnectedPeers.any { it.first == "peer-timeout" })
    }

    @Test
    fun testReconnectingPeerRestoresToConnectedOnActivity() {
        val manager = createHostManager(
            reconnectTimeoutMs = 3000L,
            disconnectTimeoutMs = 8000L
        )

        manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("849201"), "peer-rec", "192.168.1.30")

        // Drop connection past 3s
        simulatedTime += 3500L
        manager.performHeartbeatTick()
        assertEquals(PeerConnectionState.RECONNECTING, manager.getPeer("peer-rec")?.state)

        // Peer sends a Pong
        manager.handleIncomingPacket(RoomBeatPacket.PeerPong(timestampMs = simulatedTime), "peer-rec")

        val restoredPeer = manager.getPeer("peer-rec")
        assertNotNull(restoredPeer)
        assertEquals(PeerConnectionState.CONNECTED, restoredPeer?.state)
        assertEquals(0, restoredPeer?.reconnectAttempt)
    }

    @Test
    fun testPerDeviceVolumeBalancingAndMute() {
        val manager = createHostManager()
        manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("849201"), "peer-vol", "192.168.1.40")

        // Set channel volume
        manager.setPeerVolume("peer-vol", 0.75f)
        assertEquals(0.75f, manager.getPeer("peer-vol")?.volume ?: 0f, 0.001f)
        val sentVol = transport.sentPackets.lastOrNull { it.first == "peer-vol" }?.second
        assertTrue(sentVol is RoomBeatPacket.SessionVolume)
        assertEquals(0.75f, (sentVol as RoomBeatPacket.SessionVolume).volumeLevel, 0.001f)

        // Toggle mute
        manager.togglePeerMute("peer-vol")
        val mutedPeer = manager.getPeer("peer-vol")
        assertTrue(mutedPeer?.isMuted == true)

        // Toggle mute off
        manager.togglePeerMute("peer-vol")
        val unmutedPeer = manager.getPeer("peer-vol")
        assertFalse(unmutedPeer?.isMuted == true)
    }

    @Test
    fun testMasterVolumeControlBroadcasts() {
        val manager = createHostManager()
        manager.setMasterVolume(0.85f)

        assertEquals(0.85f, manager.masterVolume.value, 0.001f)
        val lastBroadcast = transport.broadcastPackets.lastOrNull()
        assertTrue(lastBroadcast is RoomBeatPacket.SessionMasterVolume)
        assertEquals(0.85f, (lastBroadcast as RoomBeatPacket.SessionMasterVolume).masterVolume, 0.001f)
    }

    @Test
    fun testRoomLeaveRemovesPeerGracefully() {
        val manager = createHostManager()
        manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("849201"), "peer-leave", "192.168.1.70")
        assertEquals(1, manager.getConnectedPeerCount())

        manager.handleIncomingPacket(RoomBeatPacket.RoomLeave("peer-leave"), "peer-leave")
        assertEquals(0, manager.getConnectedPeerCount())
        assertNull(manager.getPeer("peer-leave"))
    }

    @Test
    fun testKickPeerRemovesAndNotifies() {
        val manager = createHostManager()
        manager.handleIncomingPacket(RoomBeatPacket.RoomJoin("849201"), "peer-kick", "192.168.1.80")
        assertEquals(1, manager.getConnectedPeerCount())

        manager.kickPeer("peer-kick", "Bad signal")
        assertEquals(0, manager.getConnectedPeerCount())
        assertTrue(transport.disconnectedPeers.any { it.first == "peer-kick" })
    }

    @Test
    fun testEightPeerConcurrencyStress() {
        val manager = createHostManager(pin = "999888", maxPeers = 16)
        val peerCount = 8
        val executor = Executors.newFixedThreadPool(peerCount)
        val latch = CountDownLatch(peerCount)

        for (i in 1..peerCount) {
            val peerId = "concurrent-peer-$i"
            val ip = "192.168.1.${100 + i}"
            executor.submit {
                try {
                    val resp = manager.handleIncomingPacket(
                        RoomBeatPacket.RoomJoin("999888"),
                        peerId,
                        ip,
                        "Node $i"
                    )
                    assertTrue(resp is RoomBeatPacket.RoomJoinAck)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("All 8 concurrent joins must complete within timeout", completed)
        assertEquals(8, manager.getConnectedPeerCount())

        // Verify all 8 peers are tracked without collisions
        val currentPeers = manager.peers.value
        assertEquals(8, currentPeers.size)
        val ids = currentPeers.map { it.id }.toSet()
        assertEquals(8, ids.size)

        // Send simultaneous pings from all 8 peers
        for (peer in currentPeers) {
            manager.handleIncomingPacket(RoomBeatPacket.PeerPong(simulatedTime - 5), peer.id)
        }

        // Verify RTT updated on all 8 peers
        for (peer in manager.peers.value) {
            assertEquals(5.0, peer.rttMs, 0.001)
        }
    }
}
