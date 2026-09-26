package com.roombeat.app.session

import com.roombeat.app.audio.AudioEngineBridge
import com.roombeat.app.audio.AudioEngineState
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.network.client.ClientSocketConnection
import com.roombeat.app.network.discovery.NsdClientDiscovery
import com.roombeat.app.network.discovery.NsdHostService
import com.roombeat.app.network.discovery.NsdManagerWrapper
import com.roombeat.app.network.discovery.NsdRegistrationCallback
import com.roombeat.app.network.discovery.NsdServiceRecord
import com.roombeat.app.network.multicast.MulticastBroadcaster
import com.roombeat.app.network.multicast.MulticastReceiver
import com.roombeat.app.network.multicast.MulticastSocketWrapper
import com.roombeat.app.network.server.HostSessionServer
import com.roombeat.app.protocol.PacketSerializer
import com.roombeat.app.protocol.RoomBeatPacket
import com.roombeat.app.sync.SystemMonotonicClock
import com.roombeat.app.system.LockFactory
import com.roombeat.app.system.LockHandle
import com.roombeat.app.system.PowerLockManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.roombeat.app.network.multicast.MulticastDatagramPacket

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTeardownManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private var currentTimeMs = 1_000_000L

    // Fake Locks
    private class FakeLock : LockHandle {
        override var isHeld: Boolean = false
        override fun acquire() { isHeld = true }
        override fun release() { isHeld = false }
        override fun setReferenceCounted(refCounted: Boolean) {}
    }

    private class FakeLockFactory : LockFactory {
        val multicast = FakeLock()
        val wifi = FakeLock()
        val wake = FakeLock()

        override fun createMulticastLock(tag: String) = multicast
        override fun createWifiLock(mode: Int, tag: String) = wifi
        override fun createWakeLock(levelAndFlags: Int, tag: String) = wake
    }

    // Fake Native Audio Engine Bridge
    private class FakeAudioEngineBridge : AudioEngineBridge {
        var isStreamRunning = false
        var isEngineInitialized = false
        var masterGainDb = 0.0f
        var isMutedState = false

        override fun initEngine(): Int {
            isEngineInitialized = true
            return 0
        }

        override fun startStream(): Int {
            isStreamRunning = true
            return 0
        }

        override fun stopStream(): Int {
            isStreamRunning = false
            return 0
        }

        override fun getAudioLatencyMillis(): Int = 10

        override fun teardownEngine(): Int {
            isStreamRunning = false
            isEngineInitialized = false
            return 0
        }

        override fun setMasterVolume(volumeDb: Float) {
            masterGainDb = volumeDb
        }

        override fun setMuted(isMuted: Boolean) {
            isMutedState = isMuted
        }
    }

    // Fake Multicast Socket Wrapper
    private class FakeMulticastSocketWrapper : MulticastSocketWrapper {
        override var isClosed = false
        val sentPackets = mutableListOf<ByteArray>()

        override fun joinGroup(multicastAddress: String, port: Int) {}
        override fun leaveGroup(multicastAddress: String, port: Int) {}
        override fun send(data: ByteArray, targetAddress: String, port: Int) {
            sentPackets.add(data)
        }
        override fun receive(bufferSize: Int, timeoutMs: Int): MulticastDatagramPacket? = null
        override fun close() {
            isClosed = true
        }
    }

    // Fake Transport
    private class FakeSessionTransport : PeerSessionTransport {
        val sentPackets = mutableMapOf<String, MutableList<RoomBeatPacket>>()
        val broadcastPackets = mutableListOf<RoomBeatPacket>()
        val disconnectedPeers = mutableListOf<String>()

        override fun sendToPeer(peerId: String, packet: RoomBeatPacket): Boolean {
            sentPackets.getOrPut(peerId) { mutableListOf() }.add(packet)
            return true
        }

        override fun broadcastToAll(packet: RoomBeatPacket): Int {
            broadcastPackets.add(packet)
            return 1
        }

        override fun disconnectPeer(peerId: String, reason: String?) {
            disconnectedPeers.add(peerId)
        }
    }

    // Fake NSD Wrapper
    private class FakeNsdWrapper : NsdManagerWrapper {
        var isRegistered = false
        var isDiscovering = false
        var registeredService: NsdServiceRecord? = null

        override fun registerService(serviceRecord: NsdServiceRecord, listener: NsdRegistrationCallback): Boolean {
            isRegistered = true
            registeredService = serviceRecord
            listener.onServiceRegistered(serviceRecord)
            return true
        }

        override fun unregisterService(listener: NsdRegistrationCallback): Boolean {
            isRegistered = false
            registeredService?.let { listener.onServiceUnregistered(it) }
            return true
        }

        override fun discoverServices(serviceType: String, listener: com.roombeat.app.network.discovery.NsdDiscoveryCallback): Boolean {
            isDiscovering = true
            listener.onDiscoveryStarted(serviceType)
            return true
        }

        override fun stopServiceDiscovery(listener: com.roombeat.app.network.discovery.NsdDiscoveryCallback): Boolean {
            isDiscovering = false
            listener.onDiscoveryStopped(NsdHostService.SERVICE_TYPE)
            return true
        }

        override fun resolveService(serviceRecord: NsdServiceRecord, listener: com.roombeat.app.network.discovery.NsdResolveCallback) {}
    }

    private lateinit var lockFactory: FakeLockFactory
    private lateinit var powerLockManager: PowerLockManager
    private lateinit var audioBridge: FakeAudioEngineBridge
    private lateinit var audioEngine: NativeAudioEngine
    private lateinit var volumeCoordinator: VolumeCoordinator
    private lateinit var sessionTransport: FakeSessionTransport
    private lateinit var sessionManager: PeerSessionManager
    private lateinit var playbackCoordinator: PlaybackCoordinator
    private lateinit var jitterBuffer: AudioJitterBuffer
    private lateinit var nsdWrapper: FakeNsdWrapper
    private lateinit var nsdHostService: NsdHostService
    private lateinit var nsdDiscovery: NsdClientDiscovery
    private lateinit var broadcasterSocket: FakeMulticastSocketWrapper
    private lateinit var receiverSocket: FakeMulticastSocketWrapper
    private lateinit var broadcaster: MulticastBroadcaster
    private lateinit var receiver: MulticastReceiver
    private lateinit var teardownManager: SessionTeardownManager

    private var foregroundStopped = false
    private var mediaProjectionReleased = false

    @Before
    fun setUp() {
        lockFactory = FakeLockFactory()
        powerLockManager = PowerLockManager(lockFactory = lockFactory)
        powerLockManager.acquireAll()

        audioBridge = FakeAudioEngineBridge()
        audioEngine = NativeAudioEngine(customBridge = audioBridge)
        audioEngine.initEngine()
        audioEngine.startStream()

        volumeCoordinator = VolumeCoordinator()
        volumeCoordinator.setMasterVolume(1.0f)

        sessionTransport = FakeSessionTransport()
        sessionManager = PeerSessionManager(
            isHost = true,
            sessionId = "test-session-123",
            roomPin = "123456",
            transport = sessionTransport,
            autoStartHeartbeat = false
        )
        sessionManager.registerPeer(
            PeerNode(id = "peer-1", name = "Pixel 8", ip = "192.168.1.101", volume = 1.0f)
        )

        jitterBuffer = AudioJitterBuffer(capacityFrames = 32)
        playbackCoordinator = PlaybackCoordinator(
            isHost = true,
            clock = SystemMonotonicClock,
            jitterBuffer = jitterBuffer,
            transport = sessionTransport,
            sessionManager = sessionManager
        )

        nsdWrapper = FakeNsdWrapper()
        nsdHostService = NsdHostService(nsdWrapper = nsdWrapper)
        nsdHostService.registerService(port = 8080, sessionId = "test-session-123")

        nsdDiscovery = NsdClientDiscovery(nsdWrapper = nsdWrapper)
        nsdDiscovery.startDiscovery()

        broadcasterSocket = FakeMulticastSocketWrapper()
        broadcaster = MulticastBroadcaster(socketWrapper = broadcasterSocket)

        receiverSocket = FakeMulticastSocketWrapper()
        receiver = MulticastReceiver(socketWrapper = receiverSocket)

        foregroundStopped = false
        mediaProjectionReleased = false

        teardownManager = SessionTeardownManager(
            isHost = true,
            localDeviceId = "host_rig",
            powerLockManager = powerLockManager,
            audioEngine = audioEngine,
            playbackCoordinator = playbackCoordinator,
            volumeCoordinator = volumeCoordinator,
            sessionManager = sessionManager,
            nsdHostService = nsdHostService,
            nsdClientDiscovery = nsdDiscovery,
            multicastBroadcaster = broadcaster,
            multicastReceiver = receiver,
            onStopForegroundService = { foregroundStopped = true },
            onReleaseMediaProjection = { mediaProjectionReleased = true },
            scope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            timeProvider = { currentTimeMs },
            autoStartWatchdog = false
        )
    }

    // ========================================================================
    // Host Teardown Flow Tests
    // ========================================================================

    @Test
    fun testHostTeardown_BroadcastsSessionEndAndReleasesAllResources() = runTest(testDispatcher) {
        assertTrue(powerLockManager.areAllLocksHeld)
        assertTrue(audioBridge.isStreamRunning)
        assertEquals(1, sessionManager.getConnectedPeerCount())
        assertTrue(nsdWrapper.isRegistered)

        val teardownJob = teardownManager.teardownHostSession(reason = "Session Completed", fadeDurationMs = 50L)
        advanceTimeBy(60L)
        runCurrent()

        assertTrue(teardownJob)

        // 1. Packet broadcast verified
        val endPackets = sessionTransport.broadcastPackets.filterIsInstance<RoomBeatPacket.SessionEnd>()
        assertEquals(1, endPackets.size)
        assertEquals("Session Completed", endPackets[0].reason)

        // 2. Audio faded out & muted
        assertEquals(0.0f, volumeCoordinator.masterGain.value, 0.001f)
        assertEquals(-100.0f, audioBridge.masterGainDb, 0.001f)
        assertTrue(audioBridge.isMutedState)

        // 3. Audio stream stopped
        assertFalse(audioBridge.isStreamRunning)
        assertFalse(audioBridge.isEngineInitialized)

        // 4. Power locks released
        assertFalse(powerLockManager.isMulticastLockHeld)
        assertFalse(powerLockManager.isWifiLockHeld)
        assertFalse(powerLockManager.isWakeLockHeld)

        // 5. NSD unregistered & sockets closed
        assertFalse(nsdWrapper.isRegistered)
        assertTrue(broadcasterSocket.isClosed)

        // 6. Foreground service & MediaProjection stopped
        assertTrue(foregroundStopped)
        assertTrue(mediaProjectionReleased)

        // 7. Peers cleared
        assertEquals(0, sessionManager.getConnectedPeerCount())
        assertEquals(TeardownPhase.COMPLETED, teardownManager.teardownPhase.value)
    }

    @Test
    fun testAudioFadeOut_ExecutesCleanGradualRampDown() = runTest(testDispatcher) {
        val gainSteps = mutableListOf<Float>()
        volumeCoordinator.setMasterVolume(1.0f)

        teardownManager.fadeOutAudio(
            durationMs = 50L,
            steps = 5,
            onStepGain = { gainSteps.add(it) }
        )
        advanceTimeBy(60L)
        runCurrent()

        // 5 steps should strictly decrease
        assertEquals(5, gainSteps.size)
        for (i in 0 until gainSteps.size - 1) {
            assertTrue("Gain should decrease monotonically: ${gainSteps[i]} -> ${gainSteps[i + 1]}", gainSteps[i] > gainSteps[i + 1])
        }
        assertEquals(0.0f, gainSteps.last(), 0.001f)
        assertEquals(0.0f, volumeCoordinator.masterGain.value, 0.001f)
        assertEquals(-100.0f, audioBridge.masterGainDb, 0.001f)
    }

    @Test
    fun testTeardownPhaseProgression_TracksStateAccurately() = runTest(testDispatcher) {
        assertEquals(TeardownPhase.IDLE, teardownManager.teardownPhase.value)

        teardownManager.teardownHostSession(fadeDurationMs = 10L)
        advanceTimeBy(20L)
        runCurrent()

        assertEquals(TeardownPhase.COMPLETED, teardownManager.teardownPhase.value)

        // Idempotent secondary call
        val secondCall = teardownManager.teardownHostSession()
        assertTrue(secondCall)
        assertEquals(TeardownPhase.COMPLETED, teardownManager.teardownPhase.value)
    }

    @Test
    fun testResetForNewSession_AllowsImmediateSessionRestart() = runTest(testDispatcher) {
        teardownManager.teardownHostSession(fadeDurationMs = 10L)
        advanceTimeBy(20L)
        runCurrent()
        assertEquals(TeardownPhase.COMPLETED, teardownManager.teardownPhase.value)

        teardownManager.resetForNewSession()
        assertEquals(TeardownPhase.IDLE, teardownManager.teardownPhase.value)
        assertEquals(RecoveryState.Idle, teardownManager.recoveryState.value)
    }

    // ========================================================================
    // Peer Leave Flow Tests
    // ========================================================================

    @Test
    fun testPeerLeave_SendsRoomLeaveAndReleasesLocalResources() = runTest(testDispatcher) {
        teardownManager.isHost = false
        teardownManager.localDeviceId = "peer-device-99"

        val leaveResult = teardownManager.leaveSessionAsPeer(deviceId = "peer-device-99", fadeDurationMs = 20L)
        advanceTimeBy(30L)
        runCurrent()

        assertTrue(leaveResult)
        assertFalse(audioBridge.isStreamRunning)
        assertFalse(powerLockManager.areAllLocksHeld)
        assertFalse(nsdWrapper.isDiscovering)
        assertTrue(receiverSocket.isClosed)
        assertEquals(TeardownPhase.COMPLETED, teardownManager.teardownPhase.value)
    }

    @Test
    fun testOnHostSessionEnded_TriggersCleanPeerTeardown() = runTest(testDispatcher) {
        teardownManager.isHost = false
        teardownManager.localDeviceId = "peer-device-42"

        teardownManager.onHostSessionEnded(reason = "Host left the room", fadeDurationMs = 10L)
        advanceTimeBy(20L)
        runCurrent()

        assertEquals(TeardownPhase.COMPLETED, teardownManager.teardownPhase.value)
        assertFalse(audioBridge.isStreamRunning)
        assertFalse(powerLockManager.isMulticastLockHeld)
    }

    // ========================================================================
    // Unexpected Host Loss & Recovery Engine Tests
    // ========================================================================

    @Test
    fun testHostLossDetection_TriggersAudioFadeOutAndHostLostState() = runTest(testDispatcher) {
        teardownManager.isHost = false
        teardownManager.notifyPacketReceived(timestampMs = 1_000_000L)

        // 1. Packet received within 2s -> healthy
        currentTimeMs = 1_002_000L
        assertFalse(teardownManager.checkHostLiveness())
        assertEquals(RecoveryState.Idle, teardownManager.recoveryState.value)

        // 2. Timeout exceeded (> 3s without packet)
        currentTimeMs = 1_003_500L
        val timedOut = teardownManager.checkHostLiveness()
        advanceTimeBy(60L)
        runCurrent()

        assertTrue(timedOut)
        val state = teardownManager.recoveryState.value
        assertTrue(state is RecoveryState.HostLost)
        state as RecoveryState.HostLost
        assertEquals(1, state.attemptCount)
        assertEquals(3, state.maxAttempts)
        assertEquals(3500L, state.elapsedSinceLastPacketMs)

        // Verifies clean 50ms fade-out occurred
        assertEquals(0.0f, volumeCoordinator.masterGain.value, 0.001f)
    }

    @Test
    fun testRetryReconnect_SuccessFlow() = runTest(testDispatcher) {
        teardownManager.isHost = false
        teardownManager.handleHostLoss(attemptCount = 1, maxAttempts = 3)

        var connectAttempted = false
        val success = teardownManager.retryReconnect(reconnectAction = {
            connectAttempted = true
            true
        })

        assertTrue(connectAttempted)
        assertTrue(success)
        assertEquals(RecoveryState.Connected, teardownManager.recoveryState.value)
        assertEquals(1.0f, volumeCoordinator.masterGain.value, 0.001f)
    }

    @Test
    fun testRetryReconnect_FailureEscalationToMaxAttempts() = runTest(testDispatcher) {
        teardownManager.isHost = false
        teardownManager.handleHostLoss(attemptCount = 1, maxAttempts = 3)

        // Attempt 1 fails -> transitions to HostLost attempt 2
        val attempt1 = teardownManager.retryReconnect(reconnectAction = { false })
        assertFalse(attempt1)
        var state = teardownManager.recoveryState.value
        assertTrue(state is RecoveryState.HostLost)
        assertEquals(2, (state as RecoveryState.HostLost).attemptCount)

        // Attempt 2 fails -> transitions to HostLost attempt 3
        val attempt2 = teardownManager.retryReconnect(reconnectAction = { false })
        assertFalse(attempt2)
        state = teardownManager.recoveryState.value
        assertTrue(state is RecoveryState.HostLost)
        assertEquals(3, (state as RecoveryState.HostLost).attemptCount)

        // Attempt 3 fails -> transitions to RecoveryFailed
        val attempt3 = teardownManager.retryReconnect(reconnectAction = { false })
        assertFalse(attempt3)
        state = teardownManager.recoveryState.value
        assertTrue(state is RecoveryState.RecoveryFailed)
        assertTrue((state as RecoveryState.RecoveryFailed).reason.contains("3 attempts"))
    }

    @Test
    fun testReturnToLobby_TearsDownResourcesAndSetsState() = runTest(testDispatcher) {
        teardownManager.isHost = false
        teardownManager.localDeviceId = "peer-node-1"
        teardownManager.handleHostLoss(attemptCount = 1, maxAttempts = 3)

        val returned = teardownManager.returnToLobby()
        advanceTimeBy(10L)
        runCurrent()

        assertTrue(returned)
        assertEquals(RecoveryState.ReturnedToLobby, teardownManager.recoveryState.value)
        assertEquals(TeardownPhase.COMPLETED, teardownManager.teardownPhase.value)
        assertFalse(audioBridge.isStreamRunning)
    }

    @Test
    fun testDismissRecovery() = runTest(testDispatcher) {
        teardownManager.handleHostLoss(attemptCount = 1, maxAttempts = 3)
        teardownManager.dismissRecovery()
        assertEquals(RecoveryState.Idle, teardownManager.recoveryState.value)
    }

    @Test
    fun testWatchdogLifecycle_StopsCleanlyWithoutHangs() = runTest(testDispatcher) {
        teardownManager.isHost = false
        teardownManager.startWatchdog(intervalMs = 1000L)
        advanceTimeBy(1500L)
        runCurrent()

        teardownManager.stopWatchdog()
        teardownManager.close()
    }
}
