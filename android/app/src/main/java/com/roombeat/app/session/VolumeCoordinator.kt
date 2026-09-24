package com.roombeat.app.session

import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.protocol.RoomBeatPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * Volume coordinator managing per-device channel gain, master room gain,
 * dB <-> linear conversions, unity gain snapping, and synchronization with
 * [PeerSessionManager] and [NativeAudioEngine].
 */
class VolumeCoordinator(
    var localDeviceId: String = "",
    private val audioEngine: NativeAudioEngine = NativeAudioEngine(),
    var sessionManager: PeerSessionManager? = null
) : PeerSessionListener {

    companion object {
        const val MIN_VOLUME_DB = -60.0f
        const val MAX_VOLUME_DB = 6.0206f // +6 dB (~2.0 linear gain)
        const val UNITY_GAIN_DB = 0.0f
        const val UNITY_GAIN_LINEAR = 1.0f
        const val MUTE_LINEAR_THRESHOLD = 0.0001f
        const val MAX_LINEAR_GAIN = 2.0f

        /**
         * Converts a decibel value to a linear gain factor.
         * For dB <= -60.0 dB, snaps to 0.0 (mute).
         * For dB == 0.0 dB, snaps to 1.0 (unity).
         */
        fun dbToLinear(db: Float): Float {
            if (db.isNaN() || db <= MIN_VOLUME_DB) {
                return 0.0f
            }
            if (kotlin.math.abs(db) < 0.0001f) {
                return UNITY_GAIN_LINEAR
            }
            val clampedDb = db.coerceAtMost(MAX_VOLUME_DB)
            return 10.0.pow(clampedDb.toDouble() / 20.0).toFloat()
        }

        /**
         * Converts a linear gain factor to decibels.
         * For linear <= 0.0001, returns -infinity.
         * For linear == 1.0, snaps to 0.0 dB (unity).
         */
        fun linearToDb(linear: Float): Float {
            if (linear.isNaN() || linear <= MUTE_LINEAR_THRESHOLD) {
                return Float.NEGATIVE_INFINITY
            }
            if (kotlin.math.abs(linear - UNITY_GAIN_LINEAR) < 0.0001f) {
                return UNITY_GAIN_DB
            }
            return (20.0 * kotlin.math.log10(linear.toDouble())).toFloat()
        }
    }

    private val _localVolumeDb = MutableStateFlow(UNITY_GAIN_DB)
    val localVolumeDb: StateFlow<Float> = _localVolumeDb.asStateFlow()

    private val _localGain = MutableStateFlow(UNITY_GAIN_LINEAR)
    val localGain: StateFlow<Float> = _localGain.asStateFlow()

    private val _masterVolumeDb = MutableStateFlow(UNITY_GAIN_DB)
    val masterVolumeDb: StateFlow<Float> = _masterVolumeDb.asStateFlow()

    private val _masterGain = MutableStateFlow(UNITY_GAIN_LINEAR)
    val masterGain: StateFlow<Float> = _masterGain.asStateFlow()

    private val _isLocalMuted = MutableStateFlow(false)
    val isLocalMuted: StateFlow<Boolean> = _isLocalMuted.asStateFlow()

    private val _effectiveGain = MutableStateFlow(UNITY_GAIN_LINEAR)
    val effectiveGain: StateFlow<Float> = _effectiveGain.asStateFlow()

    private val _effectiveVolumeDb = MutableStateFlow(UNITY_GAIN_DB)
    val effectiveVolumeDb: StateFlow<Float> = _effectiveVolumeDb.asStateFlow()

    init {
        sessionManager?.let { attachSessionManager(it) }
    }

    /**
     * Attaches a [PeerSessionManager] to receive lifecycle and volume callbacks.
     */
    fun attachSessionManager(manager: PeerSessionManager) {
        this.sessionManager = manager
        val existingListener = manager.listener
        if (existingListener != null && existingListener !== this) {
            // Chain existing listener so neither is lost
            manager.listener = object : PeerSessionListener {
                override fun onPeerJoined(peer: PeerNode) {
                    existingListener.onPeerJoined(peer)
                    this@VolumeCoordinator.onPeerJoined(peer)
                }

                override fun onPeerUpdated(peer: PeerNode) {
                    existingListener.onPeerUpdated(peer)
                    this@VolumeCoordinator.onPeerUpdated(peer)
                }

                override fun onPeerLeft(peerId: String, reason: String?) {
                    existingListener.onPeerLeft(peerId, reason)
                    this@VolumeCoordinator.onPeerLeft(peerId, reason)
                }

                override fun onPeerEvicted(peerId: String, reason: String?) {
                    existingListener.onPeerEvicted(peerId, reason)
                    this@VolumeCoordinator.onPeerEvicted(peerId, reason)
                }

                override fun onMasterVolumeChanged(volume: Float) {
                    existingListener.onMasterVolumeChanged(volume)
                    this@VolumeCoordinator.onMasterVolumeChanged(volume)
                }

                override fun onSessionEnded() {
                    existingListener.onSessionEnded()
                    this@VolumeCoordinator.onSessionEnded()
                }
            }
        } else {
            manager.listener = this
        }
    }

    /**
     * Sets local device channel volume in decibels [-60.0 dB, +6.0 dB].
     */
    fun setLocalVolumeDb(volumeDb: Float) {
        val clampedDb = volumeDb.coerceIn(MIN_VOLUME_DB, MAX_VOLUME_DB)
        val linear = dbToLinear(clampedDb)
        _localVolumeDb.value = clampedDb
        _localGain.value = linear
        updateEffectiveGain()

        audioEngine.setChannelVolume(clampedDb)

        val manager = sessionManager
        if (manager != null && localDeviceId.isNotEmpty()) {
            if (manager.isHost) {
                manager.setPeerVolume(localDeviceId, linear)
            } else {
                manager.transport?.sendToPeer(
                    "host",
                    RoomBeatPacket.SessionVolume(deviceId = localDeviceId, volumeLevel = linear)
                )
            }
        }
    }

    /**
     * Sets local device channel volume as a linear gain factor [0.0, 2.0].
     */
    fun setLocalGain(linearGain: Float) {
        val clamped = linearGain.coerceIn(0.0f, MAX_LINEAR_GAIN)
        val db = linearToDb(clamped)
        _localGain.value = clamped
        _localVolumeDb.value = if (clamped <= MUTE_LINEAR_THRESHOLD) MIN_VOLUME_DB else db
        updateEffectiveGain()

        audioEngine.setChannelVolume(if (clamped <= MUTE_LINEAR_THRESHOLD) MIN_VOLUME_DB else db)

        val manager = sessionManager
        if (manager != null && localDeviceId.isNotEmpty()) {
            if (manager.isHost) {
                manager.setPeerVolume(localDeviceId, clamped)
            } else {
                manager.transport?.sendToPeer(
                    "host",
                    RoomBeatPacket.SessionVolume(deviceId = localDeviceId, volumeLevel = clamped)
                )
            }
        }
    }

    /**
     * Sets master volume across all participating devices in decibels [-60.0 dB, +6.0 dB].
     */
    fun setMasterVolumeDb(volumeDb: Float) {
        val clampedDb = volumeDb.coerceIn(MIN_VOLUME_DB, MAX_VOLUME_DB)
        val linear = dbToLinear(clampedDb)
        _masterVolumeDb.value = clampedDb
        _masterGain.value = linear
        updateEffectiveGain()

        audioEngine.setMasterVolume(clampedDb)

        sessionManager?.setMasterVolume(linear)
    }

    /**
     * Sets master volume across all participating devices as linear gain factor [0.0, 2.0].
     */
    fun setMasterGain(linearGain: Float) {
        val clamped = linearGain.coerceIn(0.0f, MAX_LINEAR_GAIN)
        val db = linearToDb(clamped)
        _masterGain.value = clamped
        _masterVolumeDb.value = if (clamped <= MUTE_LINEAR_THRESHOLD) MIN_VOLUME_DB else db
        updateEffectiveGain()

        audioEngine.setMasterVolume(if (clamped <= MUTE_LINEAR_THRESHOLD) MIN_VOLUME_DB else db)

        sessionManager?.setMasterVolume(clamped)
    }

    /**
     * Sets local mute state. When muted, local output is clamped to silence.
     */
    fun setLocalMuted(isMuted: Boolean) {
        _isLocalMuted.value = isMuted
        updateEffectiveGain()
        audioEngine.setMuted(isMuted)

        val manager = sessionManager
        if (manager != null && localDeviceId.isNotEmpty() && manager.isHost) {
            val peer = manager.peers.value.find { it.id == localDeviceId }
            if (peer != null && peer.isMuted != isMuted) {
                manager.togglePeerMute(localDeviceId)
            }
        }
    }

    /**
     * Toggles local mute state.
     */
    fun toggleLocalMute() {
        setLocalMuted(!_isLocalMuted.value)
    }

    /**
     * Double-tap action: Snaps local channel volume back to unity gain (0.0 dB, 1.0 linear).
     */
    fun snapChannelToUnityGain() {
        setLocalVolumeDb(UNITY_GAIN_DB)
    }

    /**
     * Double-tap action: Snaps master volume back to unity gain (0.0 dB, 1.0 linear).
     */
    fun snapMasterToUnityGain() {
        setMasterVolumeDb(UNITY_GAIN_DB)
    }

    /**
     * Handles an incoming protocol packet, updating local and master gains accordingly.
     * Returns true if the packet was recognized and applied.
     */
    fun handleIncomingPacket(packet: RoomBeatPacket): Boolean {
        return when (packet) {
            is RoomBeatPacket.SessionVolume -> {
                handleSessionVolume(packet)
                true
            }
            is RoomBeatPacket.SessionMasterVolume -> {
                handleSessionMasterVolume(packet)
                true
            }
            else -> false
        }
    }

    /**
     * Processes incoming [RoomBeatPacket.SessionVolume].
     * If the packet targets this device (or localDeviceId is unset), applies gain to local audio engine.
     */
    fun handleSessionVolume(packet: RoomBeatPacket.SessionVolume) {
        if (localDeviceId.isEmpty() || packet.deviceId == localDeviceId) {
            applyRemoteChannelGain(packet.volumeLevel)
        }
    }

    /**
     * Processes incoming [RoomBeatPacket.SessionMasterVolume].
     * Scales master gain across this device and syncs local audio engine.
     */
    fun handleSessionMasterVolume(packet: RoomBeatPacket.SessionMasterVolume) {
        applyRemoteMasterGain(packet.masterVolume)
    }

    private fun applyRemoteChannelGain(linearGain: Float) {
        val clamped = linearGain.coerceIn(0.0f, MAX_LINEAR_GAIN)
        val db = linearToDb(clamped)
        _localGain.value = clamped
        _localVolumeDb.value = if (clamped <= MUTE_LINEAR_THRESHOLD) MIN_VOLUME_DB else db
        updateEffectiveGain()
        audioEngine.setChannelVolume(if (clamped <= MUTE_LINEAR_THRESHOLD) MIN_VOLUME_DB else db)
    }

    private fun applyRemoteMasterGain(linearGain: Float) {
        val clamped = linearGain.coerceIn(0.0f, MAX_LINEAR_GAIN)
        val db = linearToDb(clamped)
        _masterGain.value = clamped
        _masterVolumeDb.value = if (clamped <= MUTE_LINEAR_THRESHOLD) MIN_VOLUME_DB else db
        updateEffectiveGain()
        audioEngine.setMasterVolume(if (clamped <= MUTE_LINEAR_THRESHOLD) MIN_VOLUME_DB else db)
    }

    private fun updateEffectiveGain() {
        val ch = if (_isLocalMuted.value) 0.0f else _localGain.value
        val m = _masterGain.value
        val eff = ch * m
        _effectiveGain.value = eff
        _effectiveVolumeDb.value = linearToDb(eff)
    }

    // ==========================================
    // PeerSessionListener Callbacks
    // ==========================================

    override fun onMasterVolumeChanged(volume: Float) {
        applyRemoteMasterGain(volume)
    }

    override fun onPeerUpdated(peer: PeerNode) {
        if (peer.id == localDeviceId) {
            if (peer.isMuted != _isLocalMuted.value) {
                _isLocalMuted.value = peer.isMuted
                audioEngine.setMuted(peer.isMuted)
            }
            if (kotlin.math.abs(peer.volume - _localGain.value) > 0.001f) {
                applyRemoteChannelGain(peer.volume)
            } else {
                updateEffectiveGain()
            }
        }
    }
}
