package com.roombeat.app.ui.viewmodel

import com.roombeat.app.network.multicast.FakeMulticastSocketWrapper
import com.roombeat.app.network.multicast.MulticastHealthProbe
import com.roombeat.app.network.multicast.MulticastHealthStatus
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.session.PeerConnectionState
import com.roombeat.app.session.PeerNode
import com.roombeat.app.sync.CalibrationProbeEngine
import com.roombeat.app.sync.CalibrationTransport
import com.roombeat.app.sync.FakeMonotonicClock
import com.roombeat.app.source.spotify.FakeSpotifyConnector
import com.roombeat.app.source.spotify.SpotifyAuthState
import com.roombeat.app.source.spotify.SpotifyRemoteManager
import com.roombeat.app.sync.ProbeSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakeClock = FakeMonotonicClock(initialNanos = 100_000_000_000L)
    private lateinit var fakeSocket: FakeMulticastSocketWrapper
    private lateinit var multicastProbe: MulticastHealthProbe
    private lateinit var probeEngine: CalibrationProbeEngine
    private lateinit var viewModel: CalibrationViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeSocket = FakeMulticastSocketWrapper()
        multicastProbe = MulticastHealthProbe(
            sessionId = "sess-calib-test",
            deviceId = "host-test",
            socketWrapper = fakeSocket,
            ioDispatcher = testDispatcher,
            timeProvider = { fakeClock.nowMillis() }
        )
        probeEngine = CalibrationProbeEngine(
            isHost = true,
            clock = fakeClock,
            transport = CalibrationTransport { _, _ -> true },
            defaultDispatcher = testDispatcher,
            probeCount = 10,
            probeIntervalMs = 50L,
            probeTimeoutMs = 150L
        )
        viewModel = CalibrationViewModel(
            initialEngine = probeEngine,
            initialMulticastProbe = multicastProbe,
            clock = fakeClock,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            totalDurationSeconds = 5,
            syncToleranceMs = 2.0
        )
    }

    @After
    fun tearDown() {
        probeEngine.close()
        multicastProbe.close()
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialUiState() {
        val state = viewModel.uiState.value
        assertTrue(state.isHost)
        assertEquals(CalibrationStatus.IDLE, state.status)
        assertEquals(5, state.totalDurationSeconds)
        assertEquals(5, state.countdownRemainingSeconds)
        assertEquals(0.0f, state.calibrationProgress, 0.001f)
        assertEquals(0, state.currentProbeSequence)
        assertTrue(state.peers.isEmpty())
        assertFalse(state.isSyncLocked)
        assertNull(state.syncLockStamp)
        assertFalse(state.canProceedToAudioSource)
        assertFalse(state.isMulticastBlocked)
        assertFalse(state.isRouterWarningVisible)
    }

    @Test
    fun testInitAsHostAndClient() {
        viewModel.initAsHost(
            sessionId = "sess-host-xyz",
            initialPeers = listOf(
                PeerNode("peer-1", "Pixel 8 Pro", "192.168.1.101"),
                PeerNode("peer-2", "Galaxy S24", "192.168.1.102")
            )
        )

        var state = viewModel.uiState.value
        assertTrue(state.isHost)
        assertEquals("sess-host-xyz", state.sessionId)
        assertEquals(2, state.peers.size)
        assertEquals("peer-1", state.peers[0].peerId)
        assertEquals("Pixel 8 Pro", state.peers[0].deviceName)
        assertEquals("peer-2", state.peers[1].peerId)
        assertEquals("Galaxy S24", state.peers[1].deviceName)
        assertFalse(state.canProceedToAudioSource)

        viewModel.initAsClient(
            sessionId = "sess-client-xyz",
            hostName = "Studio Master"
        )

        state = viewModel.uiState.value
        assertFalse(state.isHost)
        assertEquals("sess-client-xyz", state.sessionId)
        assertEquals(CalibrationStatus.CALIBRATING, state.status)
        assertFalse(state.canProceedToAudioSource)
    }

    @Test
    fun testCalibrationStartCountdownAndTelemetryUpdates() = runTest(testDispatcher) {
        val peer = PeerCalibrationUiModel("peer-alpha", "Pixel 7")
        viewModel.addOrUpdatePeer(peer)

        viewModel.startCalibration(listOf("peer-alpha"))

        var state = viewModel.uiState.value
        assertEquals(CalibrationStatus.CALIBRATING, state.status)
        assertEquals(5, state.countdownRemainingSeconds)
        assertFalse(state.canProceedToAudioSource)

        // Advance 1 second
        advanceTimeBy(1001L)
        state = viewModel.uiState.value
        assertEquals(4, state.countdownRemainingSeconds)

        // Advance another 2 seconds
        advanceTimeBy(2001L)
        state = viewModel.uiState.value
        assertEquals(2, state.countdownRemainingSeconds)

        // Finish countdown
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(0, state.countdownRemainingSeconds)
    }

    @Test
    fun testTransitionToSyncLockedWhenOffsetCriteriaMet() = runTest(testDispatcher) {
        viewModel.initAsHost(
            sessionId = "sess-lock-test",
            initialPeers = listOf(
                PeerNode("peer-1", "Pixel 8", "192.168.1.10")
            )
        )

        // Update peer metrics with sub-millisecond offset (0.3ms < 2.0ms tolerance)
        viewModel.updatePeerMetrics(
            peerId = "peer-1",
            offsetMs = 0.3,
            rttMs = 4.2,
            jitterMs = 0.15,
            packetLossPercent = 0.0,
            samplesReceived = 20,
            totalProbes = 20
        )

        viewModel.evaluateSyncQuality()

        val state = viewModel.uiState.value
        assertEquals(CalibrationStatus.SYNC_LOCKED, state.status)
        assertTrue(state.isSyncLocked)
        assertNotNull(state.syncLockStamp)
        assertTrue(state.syncLockStamp!!.contains("ACOUSTIC SYNC LOCKED"))
        assertTrue(state.syncLockStamp!!.contains("0.3ms"))
        assertTrue(state.canProceedToAudioSource)
        assertTrue(viewModel.proceedToAudioSource())
        assertTrue(state.peers[0].isLocked)
    }

    @Test
    fun testNavigationGatingCanProceedToAudioSource() {
        viewModel.initAsHost(sessionId = "sess-gate-test")

        // 1. Idle state -> Cannot proceed
        assertFalse(viewModel.uiState.value.canProceedToAudioSource)
        assertFalse(viewModel.proceedToAudioSource())

        // 2. Multicast blocked state -> Cannot proceed
        viewModel.setMulticastBlocked(true)
        assertFalse(viewModel.uiState.value.canProceedToAudioSource)
        assertFalse(viewModel.proceedToAudioSource())

        // 3. Clear multicast block and transition to SYNC_LOCKED
        viewModel.setMulticastBlocked(false)
        viewModel.evaluateSyncQuality()
        assertTrue(viewModel.uiState.value.canProceedToAudioSource)
        assertTrue(viewModel.proceedToAudioSource())

        // 4. In client mode -> Navigation is gated to Host only
        viewModel.initAsClient(sessionId = "sess-client-gate")
        viewModel.evaluateSyncQuality()
        assertFalse(viewModel.uiState.value.canProceedToAudioSource)
        assertFalse(viewModel.proceedToAudioSource())
    }

    @Test
    fun testTransitionToMulticastBlockedWhenMulticastFails() = runTest(testDispatcher) {
        viewModel.initAsHost(sessionId = "sess-mc-fail")

        // Report from client indicating multicast is blocked (< 80% reception or isBlocked = true)
        val blockedReport = RoomBeatPacket.MulticastProbeReport(
            deviceId = "client-isolated",
            sessionId = "sess-mc-fail",
            receivedCount = 2,
            totalSent = 10,
            receptionRate = 0.20,
            isBlocked = true
        )

        viewModel.handleIncomingPacket("client-isolated", blockedReport)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CalibrationStatus.MULTICAST_BLOCKED, state.status)
        assertTrue(state.isMulticastBlocked)
        assertFalse(state.isSyncLocked)
        assertFalse(state.canProceedToAudioSource)
        assertFalse(viewModel.proceedToAudioSource())
    }

    @Test
    fun testPeerLifecycleJoinLeaveUpdateDuringCalibration() {
        viewModel.initAsHost(sessionId = "sess-peer-lifecycle")
        assertTrue(viewModel.uiState.value.peers.isEmpty())

        // 1. Peer Joins
        val peer1 = PeerCalibrationUiModel(
            peerId = "dev-1",
            deviceName = "Phone 1",
            offsetMs = 0.2,
            rttMs = 3.0,
            jitterMs = 0.1
        )
        viewModel.addOrUpdatePeer(peer1)
        assertEquals(1, viewModel.uiState.value.peers.size)
        assertEquals("Phone 1", viewModel.uiState.value.peers[0].deviceName)
        assertEquals(0.2, viewModel.uiState.value.averageOffsetMs, 0.001)

        // 2. Second Peer Joins
        val peer2 = PeerCalibrationUiModel(
            peerId = "dev-2",
            deviceName = "Phone 2",
            offsetMs = 0.4,
            rttMs = 5.0,
            jitterMs = 0.3
        )
        viewModel.addOrUpdatePeer(peer2)
        assertEquals(2, viewModel.uiState.value.peers.size)
        assertEquals(0.3, viewModel.uiState.value.averageOffsetMs, 0.001)
        assertEquals(4.0, viewModel.uiState.value.averageRttMs, 0.001)

        // 3. Peer Updates Metrics
        viewModel.updatePeerMetrics(
            peerId = "dev-1",
            offsetMs = 0.1,
            rttMs = 2.8,
            jitterMs = 0.05
        )
        assertEquals(0.25, viewModel.uiState.value.averageOffsetMs, 0.001)

        // 4. Peer Leaves
        viewModel.removePeer("dev-2")
        assertEquals(1, viewModel.uiState.value.peers.size)
        assertEquals("dev-1", viewModel.uiState.value.peers[0].peerId)
        assertEquals(0.1, viewModel.uiState.value.averageOffsetMs, 0.001)
    }

    @Test
    fun testClientModeHandlesIncomingCalibProbeAndEcho() {
        viewModel.initAsClient(
            sessionId = "sess-client-probe",
            hostName = "Main Rig"
        )
        assertFalse(viewModel.uiState.value.isHost)

        // Handle incoming CalibProbe from host
        val probe = RoomBeatPacket.CalibProbe(t0 = 55_000L)
        val handled = viewModel.handleIncomingPacket("host-1", probe)
        assertTrue(handled)
        assertEquals(55_000L, viewModel.uiState.value.lastPulseTimestamp)

        // Handle incoming CalibResult from host
        val result = RoomBeatPacket.CalibResult(
            offsetMs = 0.42,
            rttMs = 3.8,
            jitterMs = 0.18
        )
        val resultHandled = viewModel.handleIncomingPacket("host-1", result)
        assertTrue(resultHandled)

        val state = viewModel.uiState.value
        assertEquals(CalibrationStatus.SYNC_LOCKED, state.status)
        assertTrue(state.isSyncLocked)
        assertEquals(0.42, state.averageOffsetMs, 0.001)
        assertNotNull(state.syncLockStamp)
        assertTrue(state.syncLockStamp!!.contains("0.4ms"))
    }

    @Test
    fun testCalibrationFailsWhenOffsetExceedsTolerance() {
        viewModel.initAsHost(
            sessionId = "sess-fail-tolerance",
            initialPeers = listOf(
                PeerNode("peer-lag", "Slow Device", "192.168.1.50")
            )
        )

        // Set offset = 4.5ms (> 2.0ms tolerance)
        viewModel.updatePeerMetrics(
            peerId = "peer-lag",
            offsetMs = 4.5,
            rttMs = 45.0,
            jitterMs = 6.2,
            packetLossPercent = 10.0
        )

        viewModel.evaluateSyncQuality()

        val state = viewModel.uiState.value
        assertEquals(CalibrationStatus.FAILED, state.status)
        assertFalse(state.isSyncLocked)
        assertFalse(state.canProceedToAudioSource)
        assertNull(state.syncLockStamp)
    }

    @Test
    fun testRouterWarningModalToggle() {
        assertFalse(viewModel.uiState.value.isRouterWarningVisible)

        viewModel.showRouterWarning(true)
        assertTrue(viewModel.uiState.value.isRouterWarningVisible)

        viewModel.showRouterWarning(false)
        assertFalse(viewModel.uiState.value.isRouterWarningVisible)
    }

    @Test
    fun testSpotifyPreWarmInitiatedDuringCalibrationWhenSourceIsSpotify() = runTest(testDispatcher) {
        val fakeConnector = FakeSpotifyConnector(isInstalled = true, autoRespondConnected = true)
        val spotifyManager = SpotifyRemoteManager(
            clientId = "test-client",
            connector = fakeConnector,
            dispatcher = testDispatcher
        )

        viewModel.initAsHost("sess-spotify-test")
        viewModel.bindSpotifyRemote(spotifyManager, isSpotifySource = true)

        assertTrue(viewModel.isSpotifySource)
        assertFalse(spotifyManager.isConnected)
        assertFalse(viewModel.uiState.value.isSpotifyWarmed)

        viewModel.startCalibration()
        advanceUntilIdle()

        assertTrue(spotifyManager.isConnected)
        assertTrue(viewModel.uiState.value.isSpotifyWarmed)
        assertEquals(1, fakeConnector.connectCallCount)

        spotifyManager.close()
    }

    @Test
    fun testSpotifyPreWarmNotInitiatedWhenSourceIsNotSpotify() = runTest(testDispatcher) {
        val fakeConnector = FakeSpotifyConnector(isInstalled = true, autoRespondConnected = true)
        val spotifyManager = SpotifyRemoteManager(
            clientId = "test-client",
            connector = fakeConnector,
            dispatcher = testDispatcher
        )

        viewModel.initAsHost("sess-no-spotify")
        viewModel.bindSpotifyRemote(spotifyManager, isSpotifySource = false)

        assertFalse(viewModel.isSpotifySource)

        viewModel.startCalibration()
        advanceUntilIdle()

        assertFalse(spotifyManager.isConnected)
        assertFalse(viewModel.uiState.value.isSpotifyWarmed)
        assertEquals(0, fakeConnector.connectCallCount)

        spotifyManager.close()
    }
}
