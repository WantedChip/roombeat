package com.roombeat.app.network.discovery

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle states of mDNS client discovery scanner.
 */
enum class ClientDiscoveryState {
    IDLE,
    DISCOVERING,
    STOPPED,
    FAILED
}

/**
 * Client mDNS discovery scanner using Android's [NsdManager] (`_roombeat._tcp`).
 *
 * Scans for active RoomBeat host sessions on the local network, resolves host IPv4
 * addresses, ports, and TXT record attributes into [DiscoveredRoom] instances, and
 * publishes the live list as an observable [StateFlow].
 *
 * Implements resilient lifecycle safeguards preventing listener leaks during rapid
 * Wi-Fi connect/disconnect cycles or screen navigation.
 */
class NsdClientDiscovery(
    context: Context? = null,
    nsdWrapper: NsdManagerWrapper? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "NsdClientDiscovery"

        /**
         * mDNS service type to scan for.
         */
        const val SERVICE_TYPE = "_roombeat._tcp"
    }

    private val lock = Any()
    private val wrapper: NsdManagerWrapper? = nsdWrapper ?: context?.let { AndroidNsdManagerWrapper(it) }

    private val _state = MutableStateFlow(ClientDiscoveryState.IDLE)

    /**
     * Observable lifecycle state of the discovery scanner.
     */
    val state: StateFlow<ClientDiscoveryState> = _state.asStateFlow()

    /**
     * Current synchronous discovery state snapshot.
     */
    val currentState: ClientDiscoveryState
        get() = _state.value

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())

    /**
     * Observable stream of discovered active RoomBeat sessions on the local subnet.
     */
    val discoveredRooms: StateFlow<List<DiscoveredRoom>> = _discoveredRooms.asStateFlow()

    /**
     * Last error description if discovery failed.
     */
    var lastError: String? = null
        private set

    /**
     * Last error code if discovery failed.
     */
    var lastErrorCode: Int? = null
        private set

    // Internal thread-safe cache of active rooms keyed by service instance name
    private val activeRooms = ConcurrentHashMap<String, DiscoveredRoom>()

    private val discoveryCallback = object : NsdDiscoveryCallback {
        override fun onDiscoveryStarted(serviceType: String) {
            synchronized(lock) {
                _state.value = ClientDiscoveryState.DISCOVERING
                lastError = null
                lastErrorCode = null
                Log.i(TAG, "Service discovery actively scanning for: $serviceType")
            }
        }

        override fun onDiscoveryStartFailed(serviceType: String, errorCode: Int) {
            synchronized(lock) {
                _state.value = ClientDiscoveryState.FAILED
                lastErrorCode = errorCode
                lastError = "Failed to start discovery for $serviceType (code: $errorCode)"
                Log.e(TAG, lastError ?: "Discovery start failed")
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            synchronized(lock) {
                _state.value = ClientDiscoveryState.STOPPED
                Log.i(TAG, "Service discovery stopped for: $serviceType")
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(lock) {
                _state.value = ClientDiscoveryState.FAILED
                lastErrorCode = errorCode
                lastError = "Failed to stop discovery for $serviceType (code: $errorCode)"
                Log.e(TAG, lastError ?: "Discovery stop failed")
            }
        }

        override fun onServiceFound(service: NsdServiceRecord) {
            Log.d(TAG, "Service found on network: ${service.serviceName} (${service.serviceType})")
            if (isRoomBeatService(service)) {
                resolveDiscoveredService(service)
            }
        }

        override fun onServiceLost(service: NsdServiceRecord) {
            Log.d(TAG, "Service lost from network: ${service.serviceName}")
            removeService(service.serviceName)
        }
    }

    /**
     * Starts scanning for RoomBeat host sessions on the local Wi-Fi / Hotspot.
     *
     * @return true if discovery was initiated or already running, false on error.
     */
    fun startDiscovery(): Boolean = synchronized(lock) {
        if (_state.value == ClientDiscoveryState.DISCOVERING) {
            Log.i(TAG, "Discovery is already active")
            return true
        }

        val nsd = wrapper ?: run {
            val msg = "NsdManagerWrapper unavailable"
            Log.e(TAG, msg)
            _state.value = ClientDiscoveryState.FAILED
            lastError = msg
            return false
        }

        // Reset previous room cache
        activeRooms.clear()
        _discoveredRooms.value = emptyList()
        lastError = null
        lastErrorCode = null

        val success = try {
            nsd.discoverServices(SERVICE_TYPE, discoveryCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting service discovery: ${e.message}", e)
            false
        }

        if (success) {
            _state.value = ClientDiscoveryState.DISCOVERING
        } else {
            _state.value = ClientDiscoveryState.FAILED
            lastError = "Failed to initiate mDNS discovery"
        }

        return success
    }

    /**
     * Stops the active discovery scanner cleanly and idempotently.
     * Guaranteed safe to call repeatedly or when already stopped.
     *
     * @return true if stopped or already stopped, false on error.
     */
    fun stopDiscovery(): Boolean = synchronized(lock) {
        if (_state.value != ClientDiscoveryState.DISCOVERING) {
            _state.value = ClientDiscoveryState.STOPPED
            return true
        }

        val nsd = wrapper ?: run {
            _state.value = ClientDiscoveryState.STOPPED
            return true
        }

        val success = try {
            nsd.stopServiceDiscovery(discoveryCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping discovery: ${e.message}", e)
            false
        }

        _state.value = ClientDiscoveryState.STOPPED
        return success
    }

    /**
     * Clears all cached discovered rooms.
     */
    fun clearDiscoveredRooms() {
        activeRooms.clear()
        _discoveredRooms.value = emptyList()
    }

    private fun isRoomBeatService(service: NsdServiceRecord): Boolean {
        return service.serviceType.contains("roombeat", ignoreCase = true) ||
            service.serviceName.contains("RoomBeat", ignoreCase = true)
    }

    private fun resolveDiscoveredService(service: NsdServiceRecord) {
        // If host address and port are already populated (e.g. in test doubles), register directly
        if (!service.hostAddress.isNullOrBlank() && service.port > 0) {
            addOrUpdateRoom(service)
            return
        }

        val nsd = wrapper ?: return
        nsd.resolveService(service, object : NsdResolveCallback {
            override fun onServiceResolved(resolvedService: NsdServiceRecord) {
                addOrUpdateRoom(resolvedService)
            }

            override fun onResolveFailed(service: NsdServiceRecord, errorCode: Int) {
                Log.w(TAG, "Failed to resolve service ${service.serviceName} (code: $errorCode)")
            }
        })
    }

    private fun addOrUpdateRoom(service: NsdServiceRecord) {
        val sessionId = service.attributes[NsdHostService.ATTRIBUTE_SESSION_ID]
            ?.ifBlank { null }
            ?: service.serviceName

        val hostModel = service.attributes[NsdHostService.ATTRIBUTE_HOST_MODEL].orEmpty()
        val hostAddress = service.hostAddress.orEmpty()
        val port = service.port

        val room = DiscoveredRoom(
            sessionId = sessionId,
            serviceName = service.serviceName,
            hostAddress = hostAddress,
            port = port,
            hostDeviceModel = hostModel,
            attributes = service.attributes,
            discoveredAtMs = System.currentTimeMillis()
        )

        activeRooms[service.serviceName] = room
        publishRooms()
        Log.i(TAG, "Discovered room registered: ${room.serviceName} (${room.endpoint}, session=${room.sessionId})")
    }

    private fun removeService(serviceName: String) {
        val removed = activeRooms.remove(serviceName)
        if (removed != null) {
            publishRooms()
            Log.i(TAG, "Discovered room removed: $serviceName")
        }
    }

    private fun publishRooms() {
        _discoveredRooms.value = activeRooms.values.sortedByDescending { it.discoveredAtMs }
    }

    /**
     * Closes the discovery scanner, stopping active scans and clearing room lists.
     */
    override fun close() {
        stopDiscovery()
        clearDiscoveredRooms()
    }
}
