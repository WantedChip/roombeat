package com.roombeat.app.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.PeerSessionManager
import com.roombeat.app.session.QrMatrixGenerator
import com.roombeat.app.session.QrSessionPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the Room Lobby screen.
 */
data class RoomLobbyUiState(
    val isHost: Boolean = true,
    val roomPin: String = "",
    val qrPayload: QrSessionPayload? = null,
    val qrBitmap: Bitmap? = null,
    val qrMatrix: Array<BooleanArray>? = null,
    val peers: List<PeerNode> = emptyList(),
    val masterVolume: Float = 1.0f,
    val isConnected: Boolean = false,
    val isQrModalVisible: Boolean = false,
    val currentStatusText: String = "LOBBY READY",
    val hostName: String = "Host Rig",
    val localIp: String = "127.0.0.1",
    val port: Int = 8080,
    val sessionId: String = "",
    val canProceed: Boolean = false
) {
    val connectedPeerCount: Int
        get() = peers.count { !it.isDisconnectedOrReconnecting }

    val formattedMasterVolumeDb: String
        get() = PeerNode.volumeToDbString(masterVolume)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RoomLobbyUiState

        if (isHost != other.isHost) return false
        if (roomPin != other.roomPin) return false
        if (qrPayload != other.qrPayload) return false
        if (qrBitmap != other.qrBitmap) return false
        if (peers != other.peers) return false
        if (masterVolume != other.masterVolume) return false
        if (isConnected != other.isConnected) return false
        if (isQrModalVisible != other.isQrModalVisible) return false
        if (currentStatusText != other.currentStatusText) return false
        if (hostName != other.hostName) return false
        if (localIp != other.localIp) return false
        if (port != other.port) return false
        if (sessionId != other.sessionId) return false
        if (canProceed != other.canProceed) return false
        if (qrMatrix != null) {
            if (other.qrMatrix == null) return false
            if (!qrMatrix.contentDeepEquals(other.qrMatrix)) return false
        } else if (other.qrMatrix != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isHost.hashCode()
        result = 31 * result + roomPin.hashCode()
        result = 31 * result + (qrPayload?.hashCode() ?: 0)
        result = 31 * result + (qrBitmap?.hashCode() ?: 0)
        result = 31 * result + peers.hashCode()
        result = 31 * result + masterVolume.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + isQrModalVisible.hashCode()
        result = 31 * result + currentStatusText.hashCode()
        result = 31 * result + hostName.hashCode()
        result = 31 * result + localIp.hashCode()
        result = 31 * result + port
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + canProceed.hashCode()
        result = 31 * result + (qrMatrix?.contentDeepHashCode() ?: 0)
        return result
    }
}

/**
 * ViewModel managing Room Lobby state, peer node telemetry, volume balancing,
 * QR modal toggling, and source selection navigation gate.
 */
class RoomLobbyViewModel(
    initialSessionManager: PeerSessionManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomLobbyUiState())
    val uiState: StateFlow<RoomLobbyUiState> = _uiState.asStateFlow()

    var sessionManager: PeerSessionManager? = initialSessionManager
        private set

    init {
        if (initialSessionManager != null) {
            bindSessionManager(initialSessionManager)
        }
    }

    /**
     * Initializes the lobby as the Host device.
     */
    fun initAsHost(
        pin: String,
        ip: String,
        port: Int,
        sessionId: String,
        hostName: String = "Host Rig",
        manager: PeerSessionManager? = null
    ) {
        val payload = QrSessionPayload(
            code = pin,
            host = ip,
            port = port,
            session = sessionId
        )

        val matrix = QrMatrixGenerator.generateMatrix(payload)
        val bitmap = runCatching { QrMatrixGenerator.generateBitmap(payload) }.getOrNull()

        val resolvedManager = manager ?: sessionManager ?: PeerSessionManager(
            isHost = true,
            sessionId = sessionId,
            roomPin = pin,
            scope = viewModelScope
        )
        this.sessionManager = resolvedManager
        bindSessionManager(resolvedManager)

        _uiState.update { current ->
            current.copy(
                isHost = true,
                roomPin = pin,
                localIp = ip,
                port = port,
                sessionId = sessionId,
                hostName = hostName,
                qrPayload = payload,
                qrMatrix = matrix,
                qrBitmap = bitmap,
                isConnected = true,
                currentStatusText = "HOSTING SESSION · WAITING FOR PEERS",
                canProceed = true
            )
        }
    }

    /**
     * Initializes the lobby as a Client device connected to a host.
     */
    fun initAsClient(
        hostIp: String,
        port: Int,
        pin: String,
        sessionId: String,
        hostName: String = "Host Rig",
        manager: PeerSessionManager? = null
    ) {
        val resolvedManager = manager ?: sessionManager ?: PeerSessionManager(
            isHost = false,
            sessionId = sessionId,
            roomPin = pin,
            scope = viewModelScope
        )
        this.sessionManager = resolvedManager
        bindSessionManager(resolvedManager)

        _uiState.update { current ->
            current.copy(
                isHost = false,
                localIp = hostIp,
                port = port,
                sessionId = sessionId,
                roomPin = pin,
                hostName = hostName,
                isConnected = true,
                currentStatusText = "CONNECTED TO $hostName · STANDBY",
                canProceed = false
            )
        }
    }

    /**
     * Binds state collectors from the session manager to the UI state.
     */
    fun bindSessionManager(manager: PeerSessionManager) {
        this.sessionManager = manager
        viewModelScope.launch {
            manager.peers.collect { peerList ->
                _uiState.update { current ->
                    current.copy(peers = peerList)
                }
            }
        }
        viewModelScope.launch {
            manager.masterVolume.collect { volume ->
                _uiState.update { current ->
                    current.copy(masterVolume = volume)
                }
            }
        }
    }

    /**
     * Toggles mute on an individual peer channel.
     */
    fun togglePeerMute(peerId: String) {
        sessionManager?.togglePeerMute(peerId) ?: run {
            _uiState.update { current ->
                current.copy(
                    peers = current.peers.map {
                        if (it.id == peerId) it.copy(isMuted = !it.isMuted) else it
                    }
                )
            }
        }
    }

    /**
     * Updates channel volume for an individual peer node.
     */
    fun setPeerVolume(peerId: String, volume: Float) {
        val clamped = volume.coerceIn(0.0f, 2.0f)
        sessionManager?.setPeerVolume(peerId, clamped) ?: run {
            _uiState.update { current ->
                current.copy(
                    peers = current.peers.map {
                        if (it.id == peerId) it.copy(volume = clamped) else it
                    }
                )
            }
        }
    }

    /**
     * Sets master output volume across the entire rig.
     */
    fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 2.0f)
        sessionManager?.setMasterVolume(clamped) ?: run {
            _uiState.update { current ->
                current.copy(masterVolume = clamped)
            }
        }
    }

    /**
     * Toggles visibility of the high-contrast QR code modal dialog.
     */
    fun setQrModalVisible(visible: Boolean) {
        _uiState.update { current ->
            current.copy(isQrModalVisible = visible)
        }
    }

    /**
     * Kicks a peer from the room.
     */
    fun kickPeer(peerId: String) {
        sessionManager?.kickPeer(peerId) ?: run {
            _uiState.update { current ->
                current.copy(peers = current.peers.filterNot { it.id == peerId })
            }
        }
    }

    /**
     * Host action to proceed from lobby to audio source selection (Flow 5).
     * @return true if valid to proceed, false otherwise.
     */
    fun proceedToSourceSelection(): Boolean {
        return _uiState.value.isHost && _uiState.value.isConnected
    }

    /**
     * Gracefully leaves or terminates the active session.
     */
    fun leaveRoom() {
        sessionManager?.leaveRoom()
        _uiState.update { current ->
            current.copy(
                isConnected = false,
                currentStatusText = "SESSION TERMINATED",
                peers = emptyList()
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        sessionManager?.stopHeartbeat()
    }
}
