package com.roombeat.app.session

import com.roombeat.app.audio.AudioEngineBridge
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.protocol.RoomBeatPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VolumeCoordinatorSoloTest {

    private class TestAudioEngineBridge : AudioEngineBridge {
        override fun initEngine(): Int = 0
        override fun startStream(): Int = 0
        override fun stopStream(): Int = 0
        override fun getAudioLatencyMillis(): Int = 10
        override fun teardownEngine(): Int = 0

        var channelVolumeDb: Float = 0.0f
        var masterVolumeDb: Float = 0.0f
        var mutedState: Boolean = false

        override fun setChannelVolume(volumeDb: Float) {
            channelVolumeDb = volumeDb
        }

        override fun setMasterVolume(volumeDb: Float) {
            masterVolumeDb = volumeDb
        }

        override fun setMuted(isMuted: Boolean) {
            this.mutedState = isMuted
        }
    }

    private class TestPeerSessionTransport : PeerSessionTransport {
        val sentPackets = mutableListOf<Pair<String, RoomBeatPacket>>()
        val broadcasts = mutableListOf<RoomBeatPacket>()

        override fun sendToPeer(peerId: String, packet: RoomBeatPacket): Boolean {
            sentPackets.add(peerId to packet)
            return true
        }

        override fun broadcastToAll(packet: RoomBeatPacket): Int {
            broadcasts.add(packet)
            return 1
        }

        override fun disconnectPeer(peerId: String, reason: String?) {}
    }

    private lateinit var bridge: TestAudioEngineBridge
    private lateinit var engine: NativeAudioEngine
    private lateinit var transport: TestPeerSessionTransport
    private lateinit var sessionManager: PeerSessionManager
    private lateinit var coordinator: VolumeCoordinator

    private val localId = "local_host"
    private val peer1Id = "peer_1"
    private val peer2Id = "peer_2"

    @Before
    fun setUp() {
        bridge = TestAudioEngineBridge()
        engine = NativeAudioEngine(bridge)
        transport = TestPeerSessionTransport()
        sessionManager = PeerSessionManager(
            isHost = true,
            sessionId = "test_session",
            transport = transport
        )

        sessionManager.registerPeer(PeerNode(id = peer1Id, name = "Pixel 8", ip = "192.168.1.101", volume = 1.0f))
        sessionManager.registerPeer(PeerNode(id = peer2Id, name = "Galaxy S24", ip = "192.168.1.102", volume = 0.8f))

        coordinator = VolumeCoordinator(
            localDeviceId = localId,
            audioEngine = engine,
            sessionManager = sessionManager
        )
    }

    @Test
    fun testSetDeviceVolumeForLocalDevice() {
        coordinator.setDeviceVolume(localId, 1.5f)
        assertEquals(1.5f, coordinator.localGain.value, 0.001f)
        assertEquals(1.5f, coordinator.getDeviceVolume(localId), 0.001f)
    }

    @Test
    fun testSetDeviceVolumeForPeerDevice() {
        coordinator.setDeviceVolume(peer1Id, 1.2f)
        val peer = sessionManager.getPeer(peer1Id)
        assertEquals(1.2f, peer?.volume ?: 0f, 0.001f)
        assertEquals(1.2f, coordinator.getDeviceVolume(peer1Id), 0.001f)

        // Verifies SessionVolume packet was sent to peer
        val packet = transport.sentPackets.lastOrNull { it.first == peer1Id }?.second as? RoomBeatPacket.SessionVolume
        assertEquals(1.2f, packet?.volumeLevel ?: 0f, 0.001f)
    }

    @Test
    fun testSetDeviceVolumeDb() {
        coordinator.setDeviceVolumeDb(localId, 0.0f)
        assertEquals(1.0f, coordinator.localGain.value, 0.001f)

        coordinator.setDeviceVolumeDb(peer1Id, 6.0206f)
        assertEquals(2.0f, coordinator.getDeviceVolume(peer1Id), 0.02f)
    }

    @Test
    fun testSetMasterVolume() {
        coordinator.setMasterVolume(0.5f)
        assertEquals(0.5f, coordinator.masterGain.value, 0.001f)
        assertEquals(0.5f, sessionManager.masterVolume.value, 0.001f)
    }

    @Test
    fun testToggleMuteLocalAndPeer() {
        assertFalse(coordinator.isDeviceMuted(localId))
        coordinator.toggleMute(localId)
        assertTrue(coordinator.isDeviceMuted(localId))
        assertTrue(bridge.mutedState)

        coordinator.toggleMute(localId)
        assertFalse(coordinator.isDeviceMuted(localId))
        assertFalse(bridge.mutedState)

        assertFalse(coordinator.isDeviceMuted(peer1Id))
        coordinator.toggleMute(peer1Id)
        assertTrue(coordinator.isDeviceMuted(peer1Id))
    }

    @Test
    fun testSingleChannelSoloWorkflow() {
        assertFalse(coordinator.isSoloed(localId))
        assertFalse(coordinator.isSoloed(peer1Id))
        assertFalse(coordinator.isSoloed(peer2Id))

        // Solo peer 1: local and peer 2 should become muted, peer 1 stays unmuted
        coordinator.toggleSolo(peer1Id)
        assertTrue(coordinator.isSoloed(peer1Id))
        assertFalse(coordinator.isSoloed(localId))
        assertFalse(coordinator.isSoloed(peer2Id))

        assertFalse(coordinator.isDeviceMuted(peer1Id))
        assertTrue(coordinator.isDeviceMuted(localId))
        assertTrue(coordinator.isDeviceMuted(peer2Id))

        // Unsolo peer 1: all devices should be restored to unmuted
        coordinator.toggleSolo(peer1Id)
        assertFalse(coordinator.isSoloed(peer1Id))
        assertTrue(coordinator.soloedDevices.value.isEmpty())

        assertFalse(coordinator.isDeviceMuted(localId))
        assertFalse(coordinator.isDeviceMuted(peer1Id))
        assertFalse(coordinator.isDeviceMuted(peer2Id))
    }

    @Test
    fun testMultiChannelSoloWorkflow() {
        // Solo local, then solo peer 1
        coordinator.toggleSolo(localId)
        assertTrue(coordinator.isSoloed(localId))
        assertFalse(coordinator.isDeviceMuted(localId))
        assertTrue(coordinator.isDeviceMuted(peer1Id))
        assertTrue(coordinator.isDeviceMuted(peer2Id))

        coordinator.toggleSolo(peer1Id)
        assertTrue(coordinator.isSoloed(localId))
        assertTrue(coordinator.isSoloed(peer1Id))
        assertFalse(coordinator.isDeviceMuted(localId))
        assertFalse(coordinator.isDeviceMuted(peer1Id))
        assertTrue(coordinator.isDeviceMuted(peer2Id))

        // Remove local from solo: only peer 1 remains soloed (local becomes muted)
        coordinator.toggleSolo(localId)
        assertFalse(coordinator.isSoloed(localId))
        assertTrue(coordinator.isSoloed(peer1Id))
        assertTrue(coordinator.isDeviceMuted(localId))
        assertFalse(coordinator.isDeviceMuted(peer1Id))
        assertTrue(coordinator.isDeviceMuted(peer2Id))

        // Remove peer 1: solo cleared, all restored to unmuted
        coordinator.toggleSolo(peer1Id)
        assertTrue(coordinator.soloedDevices.value.isEmpty())
        assertFalse(coordinator.isDeviceMuted(localId))
        assertFalse(coordinator.isDeviceMuted(peer1Id))
        assertFalse(coordinator.isDeviceMuted(peer2Id))
    }

    @Test
    fun testPreSoloMuteStatePreserved() {
        // Peer 2 was initially muted prior to any solo
        coordinator.setDeviceMuted(peer2Id, true)
        assertTrue(coordinator.isDeviceMuted(peer2Id))
        assertFalse(coordinator.isDeviceMuted(peer1Id))
        assertFalse(coordinator.isDeviceMuted(localId))

        // Engage solo on peer 1
        coordinator.toggleSolo(peer1Id)
        assertFalse(coordinator.isDeviceMuted(peer1Id))
        assertTrue(coordinator.isDeviceMuted(localId))
        assertTrue(coordinator.isDeviceMuted(peer2Id))

        // Disengage solo on peer 1
        coordinator.toggleSolo(peer1Id)

        // Peer 2 should still be muted, but local and peer 1 unmuted!
        assertFalse(coordinator.isDeviceMuted(localId))
        assertFalse(coordinator.isDeviceMuted(peer1Id))
        assertTrue(coordinator.isDeviceMuted(peer2Id))
    }
}
