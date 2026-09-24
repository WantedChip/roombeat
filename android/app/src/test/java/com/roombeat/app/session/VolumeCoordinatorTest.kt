package com.roombeat.app.session

import com.roombeat.app.audio.AudioEngineBridge
import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.protocol.RoomBeatPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class VolumeCoordinatorTest {

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

    private lateinit var bridge: TestAudioEngineBridge
    private lateinit var engine: NativeAudioEngine
    private lateinit var coordinator: VolumeCoordinator

    @Before
    fun setUp() {
        bridge = TestAudioEngineBridge()
        engine = NativeAudioEngine(bridge)
        coordinator = VolumeCoordinator(
            localDeviceId = "device_test_1",
            audioEngine = engine
        )
    }

    // ========================================================================
    // 1. Decibel <-> Linear Mathematical Conversion Tests
    // ========================================================================

    @Test
    fun dbToLinear_unityGain_returnsOne() {
        assertEquals(1.0f, VolumeCoordinator.dbToLinear(0.0f), 0.0001f)
    }

    @Test
    fun dbToLinear_plusSixDb_returnsApproxTwo() {
        val linear = VolumeCoordinator.dbToLinear(6.0f)
        assertEquals(1.99526f, linear, 0.01f)
    }

    @Test
    fun dbToLinear_minusSixDb_returnsApproxHalf() {
        val linear = VolumeCoordinator.dbToLinear(-6.0206f)
        assertEquals(0.5f, linear, 0.01f)
    }

    @Test
    fun dbToLinear_minusTwentyDb_returnsPointOne() {
        val linear = VolumeCoordinator.dbToLinear(-20.0f)
        assertEquals(0.1f, linear, 0.001f)
    }

    @Test
    fun dbToLinear_belowMinusSixtyDb_snapsToMuteZero() {
        assertEquals(0.0f, VolumeCoordinator.dbToLinear(-60.0f), 0.0001f)
        assertEquals(0.0f, VolumeCoordinator.dbToLinear(-70.0f), 0.0001f)
        assertEquals(0.0f, VolumeCoordinator.dbToLinear(-100.0f), 0.0001f)
    }

    @Test
    fun dbToLinear_nan_returnsZero() {
        assertEquals(0.0f, VolumeCoordinator.dbToLinear(Float.NaN), 0.0001f)
    }

    @Test
    fun linearToDb_unityGain_returnsZeroDb() {
        assertEquals(0.0f, VolumeCoordinator.linearToDb(1.0f), 0.0001f)
    }

    @Test
    fun linearToDb_twoLinear_returnsApproxPlusSixDb() {
        val db = VolumeCoordinator.linearToDb(2.0f)
        assertEquals(6.0206f, db, 0.01f)
    }

    @Test
    fun linearToDb_halfLinear_returnsApproxMinusSixDb() {
        val db = VolumeCoordinator.linearToDb(0.5f)
        assertEquals(-6.0206f, db, 0.01f)
    }

    @Test
    fun linearToDb_zeroOrThreshold_returnsNegativeInfinity() {
        assertEquals(Float.NEGATIVE_INFINITY, VolumeCoordinator.linearToDb(0.0f))
        assertEquals(Float.NEGATIVE_INFINITY, VolumeCoordinator.linearToDb(0.00005f))
        assertEquals(Float.NEGATIVE_INFINITY, VolumeCoordinator.linearToDb(-1.0f))
        assertEquals(Float.NEGATIVE_INFINITY, VolumeCoordinator.linearToDb(Float.NaN))
    }

    @Test
    fun roundTripConversions_preserveValuesAccurately() {
        val testDbs = floatArrayOf(-40.0f, -20.0f, -12.0f, -6.0f, -3.0f, 0.0f, 3.0f, 6.0f)
        for (db in testDbs) {
            val linear = VolumeCoordinator.dbToLinear(db)
            val convertedBack = VolumeCoordinator.linearToDb(linear)
            assertEquals(db, convertedBack, 0.01f)
        }
    }

    // ========================================================================
    // 2. Channel Volume & Fader Adjustment Tests
    // ========================================================================

    @Test
    fun setLocalVolumeDb_updatesGainAndNativeAudioEngine() {
        coordinator.setLocalVolumeDb(-6.0f)
        assertEquals(-6.0f, coordinator.localVolumeDb.value, 0.01f)
        assertEquals(0.501f, coordinator.localGain.value, 0.01f)
        assertEquals(-6.0f, bridge.channelVolumeDb, 0.01f)
    }

    @Test
    fun setLocalGain_updatesDbAndNativeAudioEngine() {
        coordinator.setLocalGain(0.5f)
        assertEquals(0.5f, coordinator.localGain.value, 0.001f)
        assertEquals(-6.02f, coordinator.localVolumeDb.value, 0.02f)
        assertEquals(-6.02f, bridge.channelVolumeDb, 0.02f)
    }

    @Test
    fun setLocalVolumeDb_clampedWithinBounds() {
        coordinator.setLocalVolumeDb(-100.0f)
        assertEquals(-60.0f, coordinator.localVolumeDb.value, 0.01f)
        assertEquals(0.0f, coordinator.localGain.value, 0.0001f)

        coordinator.setLocalVolumeDb(20.0f)
        assertEquals(VolumeCoordinator.MAX_VOLUME_DB, coordinator.localVolumeDb.value, 0.01f)
        assertEquals(2.0f, coordinator.localGain.value, 0.01f)
    }

    // ========================================================================
    // 3. Master Volume & Fader Adjustment Tests
    // ========================================================================

    @Test
    fun setMasterVolumeDb_updatesGainAndNativeAudioEngine() {
        coordinator.setMasterVolumeDb(-12.0f)
        assertEquals(-12.0f, coordinator.masterVolumeDb.value, 0.01f)
        assertEquals(0.251f, coordinator.masterGain.value, 0.01f)
        assertEquals(-12.0f, bridge.masterVolumeDb, 0.01f)
    }

    @Test
    fun setMasterGain_updatesDbAndNativeAudioEngine() {
        coordinator.setMasterGain(2.0f)
        assertEquals(2.0f, coordinator.masterGain.value, 0.001f)
        assertEquals(6.02f, coordinator.masterVolumeDb.value, 0.02f)
        assertEquals(6.02f, bridge.masterVolumeDb, 0.02f)
    }

    // ========================================================================
    // 4. Double-Tap Snap to Unity Gain Tests
    // ========================================================================

    @Test
    fun snapChannelToUnityGain_resetsToZeroDb() {
        coordinator.setLocalVolumeDb(-18.0f)
        assertEquals(-18.0f, coordinator.localVolumeDb.value, 0.01f)

        coordinator.snapChannelToUnityGain()
        assertEquals(0.0f, coordinator.localVolumeDb.value, 0.001f)
        assertEquals(1.0f, coordinator.localGain.value, 0.001f)
        assertEquals(0.0f, bridge.channelVolumeDb, 0.001f)
    }

    @Test
    fun snapMasterToUnityGain_resetsToZeroDb() {
        coordinator.setMasterVolumeDb(4.5f)
        assertEquals(4.5f, coordinator.masterVolumeDb.value, 0.01f)

        coordinator.snapMasterToUnityGain()
        assertEquals(0.0f, coordinator.masterVolumeDb.value, 0.001f)
        assertEquals(1.0f, coordinator.masterGain.value, 0.001f)
        assertEquals(0.0f, bridge.masterVolumeDb, 0.001f)
    }

    // ========================================================================
    // 5. Effective Gain & Mute Multiplication Tests
    // ========================================================================

    @Test
    fun effectiveGain_isProductOfChannelAndMasterGain() {
        coordinator.setLocalGain(0.5f)
        coordinator.setMasterGain(0.8f)

        assertEquals(0.4f, coordinator.effectiveGain.value, 0.001f)
        assertEquals(VolumeCoordinator.linearToDb(0.4f), coordinator.effectiveVolumeDb.value, 0.01f)
    }

    @Test
    fun mute_clampsEffectiveGainToZeroAndRestoresOnUnmute() {
        coordinator.setLocalGain(0.8f)
        coordinator.setMasterGain(1.0f)
        assertEquals(0.8f, coordinator.effectiveGain.value, 0.001f)

        coordinator.setLocalMuted(true)
        assertTrue(coordinator.isLocalMuted.value)
        assertEquals(0.0f, coordinator.effectiveGain.value, 0.0001f)
        assertTrue(bridge.mutedState)

        coordinator.setLocalMuted(false)
        assertFalse(coordinator.isLocalMuted.value)
        assertEquals(0.8f, coordinator.effectiveGain.value, 0.001f)
        assertFalse(bridge.mutedState)
    }

    @Test
    fun toggleLocalMute_alternatesMuteState() {
        assertFalse(coordinator.isLocalMuted.value)
        coordinator.toggleLocalMute()
        assertTrue(coordinator.isLocalMuted.value)
        coordinator.toggleLocalMute()
        assertFalse(coordinator.isLocalMuted.value)
    }

    // ========================================================================
    // 6. Packet Handling & Multi-Device Coordination Tests
    // ========================================================================

    @Test
    fun handleIncomingPacket_sessionVolumeTargetingThisDevice_updatesLocalGain() {
        val packet = RoomBeatPacket.SessionVolume(
            deviceId = "device_test_1",
            volumeLevel = 0.75f
        )
        val handled = coordinator.handleIncomingPacket(packet)
        assertTrue(handled)
        assertEquals(0.75f, coordinator.localGain.value, 0.001f)
        assertEquals(VolumeCoordinator.linearToDb(0.75f), bridge.channelVolumeDb, 0.01f)
    }

    @Test
    fun handleIncomingPacket_sessionVolumeTargetingOtherDevice_ignoredForLocalEngine() {
        val packet = RoomBeatPacket.SessionVolume(
            deviceId = "device_other_99",
            volumeLevel = 0.25f
        )
        val handled = coordinator.handleIncomingPacket(packet)
        assertTrue(handled)
        // Local device volume remains unity (1.0f)
        assertEquals(1.0f, coordinator.localGain.value, 0.001f)
    }

    @Test
    fun handleIncomingPacket_sessionMasterVolume_updatesMasterAcrossDevices() {
        val packet = RoomBeatPacket.SessionMasterVolume(
            masterVolume = 1.5f
        )
        val handled = coordinator.handleIncomingPacket(packet)
        assertTrue(handled)
        assertEquals(1.5f, coordinator.masterGain.value, 0.001f)
        assertEquals(VolumeCoordinator.linearToDb(1.5f), bridge.masterVolumeDb, 0.01f)
    }

    @Test
    fun handleIncomingPacket_unrelatedPacket_returnsFalse() {
        val packet = RoomBeatPacket.PeerPing(timestampMs = 12345L)
        assertFalse(coordinator.handleIncomingPacket(packet))
    }

    // ========================================================================
    // 7. PeerSessionManager Integration Tests
    // ========================================================================

    @Test
    fun sessionManager_listenerEvents_triggerVolumeCoordinatorUpdates() {
        val sessionManager = PeerSessionManager(
            isHost = true,
            sessionId = "test_session",
            roomPin = "123456"
        )
        coordinator.attachSessionManager(sessionManager)

        // Master volume change from session manager triggers coordinator
        sessionManager.setMasterVolume(0.6f)
        assertEquals(0.6f, coordinator.masterGain.value, 0.001f)
        assertEquals(VolumeCoordinator.linearToDb(0.6f), bridge.masterVolumeDb, 0.01f)

        sessionManager.close()
    }

    @Test
    fun rapidVolumeChanges_remainSmoothAndBounded() {
        // Simulate rapid dragging of the volume slider across 100 updates
        for (i in 0..100) {
            val db = -60.0f + (i * 0.66f)
            coordinator.setLocalVolumeDb(db)
            assertTrue(coordinator.localGain.value in 0.0f..2.0f)
            assertTrue(coordinator.effectiveGain.value in 0.0f..2.0f)
        }
    }
}
