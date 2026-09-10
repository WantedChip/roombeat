package com.roombeat.app.network.discovery

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic in-memory test fake of [NsdManagerWrapper] for host JVM unit tests.
 *
 * Tracks listener states, simulation hooks for mDNS events, and assertions for zero listener leaks.
 */
class FakeNsdManagerWrapper : NsdManagerWrapper {

    var activeRegistrationCallback: NsdRegistrationCallback? = null
        private set

    var activeDiscoveryCallback: NsdDiscoveryCallback? = null
        private set

    var registeredRecord: NsdServiceRecord? = null
        private set

    var activeDiscoveryType: String? = null
        private set

    val resolvedRequests = CopyOnWriteArrayList<NsdServiceRecord>()

    var autoRegisterSuccess: Boolean = true
    var autoDiscoveryStartSuccess: Boolean = true
    var autoResolve: Boolean = true

    var shouldFailRegistration: Boolean = false
    var registrationErrorCode: Int = 1

    var shouldFailDiscoveryStart: Boolean = false
    var discoveryStartErrorCode: Int = 2

    var shouldFailResolve: Boolean = false
    var resolveErrorCode: Int = 3

    var registerCallCount = 0
    var unregisterCallCount = 0
    var discoverCallCount = 0
    var stopDiscoveryCallCount = 0
    var resolveCallCount = 0

    val isRegistrationListenerHeld: Boolean
        get() = activeRegistrationCallback != null

    val isDiscoveryListenerHeld: Boolean
        get() = activeDiscoveryCallback != null

    override fun registerService(
        record: NsdServiceRecord,
        callback: NsdRegistrationCallback
    ): Boolean {
        registerCallCount++
        registeredRecord = record
        activeRegistrationCallback = callback

        if (shouldFailRegistration) {
            callback.onRegistrationFailed(record, registrationErrorCode)
            activeRegistrationCallback = null
            return false
        }

        if (autoRegisterSuccess) {
            callback.onServiceRegistered(record)
        }
        return true
    }

    override fun unregisterService(callback: NsdRegistrationCallback): Boolean {
        unregisterCallCount++
        if (activeRegistrationCallback === callback) {
            val record = registeredRecord ?: NsdServiceRecord("unknown", NsdHostService.SERVICE_TYPE)
            callback.onServiceUnregistered(record)
            activeRegistrationCallback = null
            registeredRecord = null
            return true
        }
        return true
    }

    override fun discoverServices(
        serviceType: String,
        callback: NsdDiscoveryCallback
    ): Boolean {
        discoverCallCount++
        activeDiscoveryType = serviceType
        activeDiscoveryCallback = callback

        if (shouldFailDiscoveryStart) {
            callback.onDiscoveryStartFailed(serviceType, discoveryStartErrorCode)
            activeDiscoveryCallback = null
            return false
        }

        if (autoDiscoveryStartSuccess) {
            callback.onDiscoveryStarted(serviceType)
        }
        return true
    }

    override fun stopServiceDiscovery(callback: NsdDiscoveryCallback): Boolean {
        stopDiscoveryCallCount++
        if (activeDiscoveryCallback === callback) {
            val type = activeDiscoveryType ?: NsdClientDiscovery.SERVICE_TYPE
            callback.onDiscoveryStopped(type)
            activeDiscoveryCallback = null
            activeDiscoveryType = null
            return true
        }
        return true
    }

    override fun resolveService(
        record: NsdServiceRecord,
        callback: NsdResolveCallback
    ) {
        resolveCallCount++
        resolvedRequests.add(record)

        if (shouldFailResolve) {
            callback.onResolveFailed(record, resolveErrorCode)
            return
        }

        if (autoResolve) {
            val resolved = if (record.hostAddress == null || record.port == 0) {
                record.copy(
                    hostAddress = record.hostAddress ?: "192.168.1.100",
                    port = if (record.port > 0) record.port else 8080
                )
            } else {
                record
            }
            callback.onServiceResolved(resolved)
        }
    }

    // Manual simulation triggers for complex test scenarios

    fun simulateServiceFound(record: NsdServiceRecord) {
        activeDiscoveryCallback?.onServiceFound(record)
    }

    fun simulateServiceLost(record: NsdServiceRecord) {
        activeDiscoveryCallback?.onServiceLost(record)
    }

    fun simulateDiscoveryStopped(serviceType: String = NsdClientDiscovery.SERVICE_TYPE) {
        activeDiscoveryCallback?.onDiscoveryStopped(serviceType)
        activeDiscoveryCallback = null
    }

    fun simulateServiceRegistered(record: NsdServiceRecord) {
        activeRegistrationCallback?.onServiceRegistered(record)
    }

    fun simulateRegistrationFailed(errorCode: Int) {
        val record = registeredRecord ?: NsdServiceRecord("failed", NsdHostService.SERVICE_TYPE)
        activeRegistrationCallback?.onRegistrationFailed(record, errorCode)
        activeRegistrationCallback = null
    }
}
