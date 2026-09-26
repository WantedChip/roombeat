package com.roombeat.app.ui.viewmodel

import com.roombeat.app.audio.AudioEngineBridge
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.PeerSessionManager
import com.roombeat.app.session.PeerSessionTransport
import com.roombeat.app.session.VolumeCoordinator
import com.roombeat.app.ui.screens.ActivePlaybackHudTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivePlaybackHudViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

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

    private class FakeTransport : PeerSessionTransport {
        val kickedPeers = mutableListOf<String>()

        override fun sendToPeer(peerId: String, packet: com.roombeat.app.protocol.RoomBeatPacket): Boolean = true
        override fun broadcastToAll(packet: com.roombeat.app.protocol.RoomBeatPacket): Int = 1
        override fun disconnectPeer(peerId: String, reason: String?) {
            kickedPeers.add(peerId)
        }
    }

    private lateinit var bridge: TestAudioEngineBridge
    private lateinit var engine: NativeAudioEngine
    private lateinit var sessionManager: PeerSessionManager
    private lateinit var volumeCoordinator: VolumeCoordinator
    private lateinit var viewModel: ActivePlaybackHudViewModel
    private lateinit var transport: FakeTransport

    private val hostId = "host_rig"
    private val peer1Id = "peer_one"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        bridge = TestAudioEngineBridge()
        engine = NativeAudioEngine(bridge)
        transport = FakeTransport()

        sessionManager = PeerSessionManager(
            isHost = true,
            sessionId = "sess_live_123",
            transport = transport
        )
        sessionManager.registerPeer(PeerNode(id = peer1Id, name = "Pixel 8", ip = "192.168.1.101", volume = 1.0f))

        volumeCoordinator = VolumeCoordinator(
            localDeviceId = hostId,
            audioEngine = engine,
            sessionManager = sessionManager
        )

        viewModel = ActivePlaybackHudViewModel(
            volumeCoordinator = volumeCoordinator,
            sessionManager = sessionManager,
            autoStartTelemetry = false
        )
    }

    @After
    fun tearDown() {
        viewModel.stopTelemetryLoop()
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() {
        val state = viewModel.uiState.value
        assertTrue(state.isHost)
        assertEquals("sess_live_123", state.sessionId)
        assertEquals(1, state.peers.size)
        assertEquals(1.0f, state.masterVolume, 0.001f)
        assertFalse(state.isEndSessionDialogVisible)
    }

    @Test
    fun testTelemetryTickerUpdatesSmoothly() {
        viewModel.updateTelemetryTick()
        val telemetry = viewModel.uiState.value.streamTelemetry
        assertEquals(2, telemetry.peerCount) // 1 host + 1 peer
        assertTrue(telemetry.isLocked)
        assertEquals("ACTIVE", telemetry.connectionStatus)
    }

    @Test
    fun testChannelVolumeAdjustment() = runTest(testDispatcher) {
        viewModel.setDeviceVolume(peer1Id, 1.4f)
        advanceUntilIdle()

        assertEquals(1.4f, volumeCoordinator.getDeviceVolume(peer1Id), 0.001f)
        val peer = sessionManager.getPeer(peer1Id)
        assertEquals(1.4f, peer?.volume ?: 0f, 0.001f)
    }

    @Test
    fun testMasterVolumeAdjustment() = runTest(testDispatcher) {
        viewModel.setMasterVolume(0.75f)
        advanceUntilIdle()

        assertEquals(0.75f, volumeCoordinator.masterGain.value, 0.001f)
        assertEquals(0.75f, viewModel.uiState.value.masterVolume, 0.001f)
    }

    @Test
    fun testMuteToggle() = runTest(testDispatcher) {
        assertFalse(volumeCoordinator.isDeviceMuted(hostId))
        viewModel.toggleMute(hostId)
        advanceUntilIdle()

        assertTrue(volumeCoordinator.isDeviceMuted(hostId))
    }

    @Test
    fun testSoloToggle() = runTest(testDispatcher) {
        assertTrue(viewModel.uiState.value.soloedDevices.isEmpty())
        viewModel.toggleSolo(peer1Id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.soloedDevices.contains(peer1Id))
        assertTrue(volumeCoordinator.isDeviceMuted(hostId))
        assertFalse(volumeCoordinator.isDeviceMuted(peer1Id))

        viewModel.toggleSolo(peer1Id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.soloedDevices.isEmpty())
        assertFalse(volumeCoordinator.isDeviceMuted(hostId))
        assertFalse(volumeCoordinator.isDeviceMuted(peer1Id))
    }

    @Test
    fun testSnapVolume() = runTest(testDispatcher) {
        viewModel.setDeviceVolume(hostId, 1.8f)
        assertEquals(1.8f, volumeCoordinator.localGain.value, 0.001f)

        viewModel.snapDeviceVolume(hostId)
        assertEquals(1.0f, volumeCoordinator.localGain.value, 0.001f)

        viewModel.setMasterVolume(0.4f)
        assertEquals(0.4f, volumeCoordinator.masterGain.value, 0.001f)

        viewModel.snapMasterVolume()
        assertEquals(1.0f, volumeCoordinator.masterGain.value, 0.001f)
    }

    @Test
    fun testEndSessionDialogVisibilityAndTeardown() {
        assertFalse(viewModel.uiState.value.isEndSessionDialogVisible)
        viewModel.setEndSessionDialogVisible(true)
        assertTrue(viewModel.uiState.value.isEndSessionDialogVisible)

        viewModel.endSession()
        assertFalse(viewModel.uiState.value.isEndSessionDialogVisible)
        assertTrue(sessionManager.peers.value.isEmpty())
    }

    @Test
    fun testKickPeer() {
        assertEquals(1, sessionManager.peers.value.size)
        viewModel.kickPeer(peer1Id)
        assertEquals(0, sessionManager.peers.value.size)
    }

    @Test
    fun testHudTagsConstants() {
        assertEquals("active_playback_hud_screen", ActivePlaybackHudTags.SCREEN)
        assertEquals("active_playback_hud_top_bar", ActivePlaybackHudTags.TOP_BAR)
        assertEquals("active_playback_hud_vu_meter_section", ActivePlaybackHudTags.VU_METER_SECTION)
        assertEquals("active_playback_hud_channel_strips_section", ActivePlaybackHudTags.CHANNEL_STRIPS_SECTION)
        assertEquals("active_playback_channel_card_", ActivePlaybackHudTags.CHANNEL_CARD_PREFIX)
        assertEquals("active_playback_hud_bottom_dock", ActivePlaybackHudTags.BOTTOM_DOCK)
        assertEquals("active_playback_hud_master_fader", ActivePlaybackHudTags.MASTER_FADER)
        assertEquals("active_playback_hud_transport_bar", ActivePlaybackHudTags.TRANSPORT_BAR)
        assertEquals("active_playback_hud_end_session_button", ActivePlaybackHudTags.END_SESSION_BUTTON)
        assertEquals("active_playback_hud_end_session_dialog", ActivePlaybackHudTags.END_SESSION_DIALOG)
        assertEquals("active_playback_hud_confirm_end_button", ActivePlaybackHudTags.CONFIRM_END_BUTTON)
        assertEquals("active_playback_hud_cancel_end_button", ActivePlaybackHudTags.CANCEL_END_BUTTON)
    }

    @Test
    fun testTelemetryLoopLifecycle() = runTest(testDispatcher) {
        viewModel.startTelemetryLoop()
        advanceTimeBy(ActivePlaybackHudViewModel.TELEMETRY_REFRESH_INTERVAL_MS)
        runCurrent()
        viewModel.stopTelemetryLoop()
        val telemetry = viewModel.uiState.value.streamTelemetry
        assertEquals(2, telemetry.peerCount)
        assertTrue(telemetry.isLocked)
    }
}
