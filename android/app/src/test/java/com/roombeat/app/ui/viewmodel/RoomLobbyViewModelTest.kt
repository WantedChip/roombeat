package com.roombeat.app.ui.viewmodel

import com.roombeat.app.session.PeerConnectionState
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.PeerSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomLobbyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: RoomLobbyViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = RoomLobbyViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialUiState() {
        val state = viewModel.uiState.value
        assertTrue(state.isHost)
        assertFalse(state.isConnected)
        assertFalse(state.isQrModalVisible)
        assertEquals(1.0f, state.masterVolume, 0.001f)
        assertTrue(state.peers.isEmpty())
        assertFalse(state.canProceed)
    }

    @Test
    fun testInitAsHostEmitsExpectedState() = runTest(testDispatcher) {
        viewModel.initAsHost(
            pin = "849201",
            ip = "192.168.1.100",
            port = 8080,
            sessionId = "sess-host-alpha",
            hostName = "Studio Rig"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isHost)
        assertTrue(state.isConnected)
        assertEquals("849201", state.roomPin)
        assertEquals("192.168.1.100", state.localIp)
        assertEquals(8080, state.port)
        assertEquals("sess-host-alpha", state.sessionId)
        assertEquals("Studio Rig", state.hostName)
        assertNotNull(state.qrPayload)
        assertEquals("849201", state.qrPayload?.code)
        assertEquals("192.168.1.100", state.qrPayload?.host)
        assertNotNull(state.qrMatrix)
        assertTrue(state.canProceed)
    }

    @Test
    fun testInitAsClientEmitsExpectedState() = runTest(testDispatcher) {
        viewModel.initAsClient(
            hostIp = "192.168.1.105",
            port = 8081,
            pin = "654321",
            sessionId = "sess-client-beta",
            hostName = "Master Console"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isHost)
        assertTrue(state.isConnected)
        assertEquals("654321", state.roomPin)
        assertEquals("192.168.1.105", state.localIp)
        assertEquals(8081, state.port)
        assertEquals("sess-client-beta", state.sessionId)
        assertEquals("Master Console", state.hostName)
        assertFalse(state.canProceed)
    }

    @Test
    fun testQrModalVisibilityToggle() {
        assertFalse(viewModel.uiState.value.isQrModalVisible)

        viewModel.setQrModalVisible(true)
        assertTrue(viewModel.uiState.value.isQrModalVisible)

        viewModel.setQrModalVisible(false)
        assertFalse(viewModel.uiState.value.isQrModalVisible)
    }

    @Test
    fun testPeerMuteToggleUpdatesState() = runTest(testDispatcher) {
        val manager = PeerSessionManager(
            isHost = true,
            sessionId = "sess-1",
            roomPin = "123456",
            scope = this
        )
        val peer = PeerNode(
            id = "p-1",
            name = "Device 1",
            ip = "192.168.1.5",
            isMuted = false
        )
        manager.registerPeer(peer)

        viewModel.initAsHost(
            pin = "123456",
            ip = "192.168.1.1",
            port = 8080,
            sessionId = "sess-1",
            manager = manager
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.peers.size)
        assertFalse(viewModel.uiState.value.peers.first().isMuted)

        viewModel.togglePeerMute("p-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.peers.first().isMuted)

        viewModel.togglePeerMute("p-1")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.peers.first().isMuted)
    }

    @Test
    fun testPeerVolumeAdjustmentUpdatesState() = runTest(testDispatcher) {
        val manager = PeerSessionManager(
            isHost = true,
            sessionId = "sess-vol",
            roomPin = "112233",
            scope = this
        )
        val peer = PeerNode(
            id = "p-vol",
            name = "Fader Node",
            ip = "192.168.1.6",
            volume = 1.0f
        )
        manager.registerPeer(peer)

        viewModel.initAsHost(
            pin = "112233",
            ip = "192.168.1.1",
            port = 8080,
            sessionId = "sess-vol",
            manager = manager
        )
        advanceUntilIdle()

        viewModel.setPeerVolume("p-vol", 0.65f)
        advanceUntilIdle()
        assertEquals(0.65f, viewModel.uiState.value.peers.first().volume, 0.001f)

        // Test clamping upper bound
        viewModel.setPeerVolume("p-vol", 3.0f)
        advanceUntilIdle()
        assertEquals(2.0f, viewModel.uiState.value.peers.first().volume, 0.001f)
    }

    @Test
    fun testMasterVolumeAdjustmentUpdatesState() = runTest(testDispatcher) {
        val manager = PeerSessionManager(
            isHost = true,
            sessionId = "sess-mv",
            roomPin = "334455",
            scope = this
        )
        viewModel.initAsHost(
            pin = "334455",
            ip = "192.168.1.1",
            port = 8080,
            sessionId = "sess-mv",
            manager = manager
        )
        advanceUntilIdle()

        viewModel.setMasterVolume(0.42f)
        advanceUntilIdle()
        assertEquals(0.42f, viewModel.uiState.value.masterVolume, 0.001f)
    }

    @Test
    fun testKickPeerRemovesFromActiveList() = runTest(testDispatcher) {
        val manager = PeerSessionManager(
            isHost = true,
            sessionId = "sess-kick",
            roomPin = "556677",
            scope = this
        )
        manager.registerPeer(PeerNode(id = "p-bad", name = "Bad Node", ip = "10.0.0.9"))
        manager.registerPeer(PeerNode(id = "p-good", name = "Good Node", ip = "10.0.0.10"))

        viewModel.initAsHost(
            pin = "556677",
            ip = "10.0.0.1",
            port = 8080,
            sessionId = "sess-kick",
            manager = manager
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.peers.size)

        viewModel.kickPeer("p-bad")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.peers.size)
        assertEquals("p-good", viewModel.uiState.value.peers.first().id)
    }

    @Test
    fun testProceedToSourceSelectionConditions() {
        // Not connected
        assertFalse(viewModel.proceedToSourceSelection())

        // Host connected
        viewModel.initAsHost("123456", "192.168.1.1", 8080, "s1")
        assertTrue(viewModel.proceedToSourceSelection())

        // Client connected
        viewModel.initAsClient("192.168.1.1", 8080, "123456", "s1")
        assertFalse(viewModel.proceedToSourceSelection())
    }

    @Test
    fun testLeaveRoomTerminatesSession() = runTest(testDispatcher) {
        viewModel.initAsHost("123456", "192.168.1.1", 8080, "s1")
        assertTrue(viewModel.uiState.value.isConnected)

        viewModel.leaveRoom()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConnected)
        assertTrue(viewModel.uiState.value.peers.isEmpty())
        assertEquals("SESSION TERMINATED", viewModel.uiState.value.currentStatusText)
    }
}
