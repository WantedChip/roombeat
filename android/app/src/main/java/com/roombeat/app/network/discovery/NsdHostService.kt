package com.roombeat.app.network.discovery

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle states of mDNS host service registration.
 */
enum class HostRegistrationState {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    FAILED
}

/**
 * Host mDNS service registration wrapper using Android's [NsdManager] (`_roombeat._tcp`).
 *
 * Publishes session metadata via TXT record attributes:
 * - [sessionId]: Unique session identifier.
 * - [hostDeviceModel]: Device model string (e.g., "Google Pixel 8").
 * - [version]: Protocol/App version string.
 *
 * Provides thread-safe state tracking and clean, idempotent [unregisterService]
 * lifecycle safeguards preventing listener leaks on session stop or app backgrounding.
 */
class NsdHostService(
    context: Context? = null,
    nsdWrapper: NsdManagerWrapper? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "NsdHostService"

        /**
         * mDNS service type for RoomBeat control plane discovery.
         */
        const val SERVICE_TYPE = "_roombeat._tcp"

        /**
         * Default protocol version.
         */
        const val DEFAULT_VERSION = "0.2.2"

        // TXT record attribute keys
        const val ATTRIBUTE_SESSION_ID = "sessionId"
        const val ATTRIBUTE_HOST_MODEL = "hostDeviceModel"
        const val ATTRIBUTE_VERSION = "version"

        /**
         * Safe default device model resolver working across Android runtime and JVM test stubs.
         */
        fun defaultDeviceModel(): String {
            return try {
                val manufacturer = Build.MANUFACTURER.orEmpty()
                val model = Build.MODEL.orEmpty()
                if (manufacturer.isBlank() && model.isBlank()) {
                    "RoomBeat Host"
                } else {
                    "$manufacturer $model".trim()
                }
            } catch (_: Throwable) {
                "RoomBeat Host"
            }
        }
    }

    private val lock = Any()
    private val wrapper: NsdManagerWrapper? = nsdWrapper ?: context?.let { AndroidNsdManagerWrapper(it) }

    private val _state = MutableStateFlow(HostRegistrationState.UNREGISTERED)

    /**
     * Observable state of the host registration.
     */
    val state: StateFlow<HostRegistrationState> = _state.asStateFlow()

    /**
     * Current synchronous registration state snapshot.
     */
    val currentState: HostRegistrationState
        get() = _state.value

    /**
     * The actual service instance name assigned by the mDNS daemon.
     * May differ from requested name if local network name conflict resolution occurred.
     */
    var registeredServiceName: String? = null
        private set

    /**
     * Last error description if in [HostRegistrationState.FAILED].
     */
    var lastError: String? = null
        private set

    /**
     * Last error code from NsdManager if registration failed.
     */
    var lastErrorCode: Int? = null
        private set

    private val registrationCallback = object : NsdRegistrationCallback {
        override fun onServiceRegistered(registeredService: NsdServiceRecord) {
            synchronized(lock) {
                registeredServiceName = registeredService.serviceName
                _state.value = HostRegistrationState.REGISTERED
                lastError = null
                lastErrorCode = null
                Log.i(TAG, "Successfully registered mDNS host service: ${registeredService.serviceName} on port ${registeredService.port}")
            }
        }

        override fun onRegistrationFailed(service: NsdServiceRecord, errorCode: Int) {
            synchronized(lock) {
                _state.value = HostRegistrationState.FAILED
                lastErrorCode = errorCode
                lastError = "NSD service registration failed for ${service.serviceName} (error code: $errorCode)"
                Log.e(TAG, lastError ?: "Registration failed")
            }
        }

        override fun onServiceUnregistered(service: NsdServiceRecord) {
            synchronized(lock) {
                registeredServiceName = null
                _state.value = HostRegistrationState.UNREGISTERED
                Log.i(TAG, "Successfully unregistered mDNS host service: ${service.serviceName}")
            }
        }

        override fun onUnregistrationFailed(service: NsdServiceRecord, errorCode: Int) {
            synchronized(lock) {
                _state.value = HostRegistrationState.FAILED
                lastErrorCode = errorCode
                lastError = "NSD service unregistration failed for ${service.serviceName} (error code: $errorCode)"
                Log.e(TAG, lastError ?: "Unregistration failed")
            }
        }
    }

    /**
     * Registers the RoomBeat mDNS service with session metadata on the local network.
     *
     * @param port The active TCP control socket port on the host.
     * @param sessionId The active room session ID.
     * @param hostDeviceModel Human-readable device model for peer UI display.
     * @param version Protocol version advertised to peers.
     * @param serviceName Desired service instance name (defaults to "RoomBeat-$sessionId").
     * @param customAttributes Additional key-value pairs to advertise in TXT records.
     * @return true if registration was initiated, false otherwise.
     */
    fun registerService(
        port: Int,
        sessionId: String,
        hostDeviceModel: String = defaultDeviceModel(),
        version: String = DEFAULT_VERSION,
        serviceName: String = "RoomBeat-$sessionId",
        customAttributes: Map<String, String> = emptyMap()
    ): Boolean = synchronized(lock) {
        if (_state.value == HostRegistrationState.REGISTERED || _state.value == HostRegistrationState.REGISTERING) {
            Log.w(TAG, "Service is already registered or registering")
            return false
        }

        val nsd = wrapper ?: run {
            val msg = "NsdManagerWrapper unavailable"
            Log.e(TAG, msg)
            _state.value = HostRegistrationState.FAILED
            lastError = msg
            return false
        }

        _state.value = HostRegistrationState.REGISTERING
        lastError = null
        lastErrorCode = null

        val attributes = buildMap {
            put(ATTRIBUTE_SESSION_ID, sessionId)
            put(ATTRIBUTE_HOST_MODEL, hostDeviceModel)
            put(ATTRIBUTE_VERSION, version)
            putAll(customAttributes)
        }

        val record = NsdServiceRecord(
            serviceName = serviceName,
            serviceType = SERVICE_TYPE,
            port = port,
            attributes = attributes
        )

        return try {
            val success = nsd.registerService(record, registrationCallback)
            if (!success) {
                _state.value = HostRegistrationState.FAILED
                lastError = "Failed to start service registration"
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception initiating service registration: ${e.message}", e)
            _state.value = HostRegistrationState.FAILED
            lastError = e.message
            false
        }
    }

    /**
     * Cleanly and idempotently unregisters the active mDNS host service.
     * Safe to invoke multiple times, on session stop, or during app backgrounding.
     *
     * @return true if unregistered or already unregistered, false on error.
     */
    fun unregisterService(): Boolean = synchronized(lock) {
        if (_state.value == HostRegistrationState.UNREGISTERED) {
            return true
        }

        val nsd = wrapper ?: run {
            _state.value = HostRegistrationState.UNREGISTERED
            registeredServiceName = null
            return true
        }

        val success = try {
            nsd.unregisterService(registrationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception unregistering service: ${e.message}", e)
            false
        }

        _state.value = HostRegistrationState.UNREGISTERED
        registeredServiceName = null
        return success
    }

    /**
     * Ensures clean unregistration when closed.
     */
    override fun close() {
        unregisterService()
    }
}
