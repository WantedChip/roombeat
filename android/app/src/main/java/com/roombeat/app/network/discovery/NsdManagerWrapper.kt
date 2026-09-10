package com.roombeat.app.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Normalized data representation of an mDNS network service.
 */
data class NsdServiceRecord(
    val serviceName: String,
    val serviceType: String,
    val hostAddress: String? = null,
    val port: Int = 0,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Callbacks for mDNS host service registration events.
 */
interface NsdRegistrationCallback {
    fun onServiceRegistered(registeredService: NsdServiceRecord)
    fun onRegistrationFailed(service: NsdServiceRecord, errorCode: Int)
    fun onServiceUnregistered(service: NsdServiceRecord)
    fun onUnregistrationFailed(service: NsdServiceRecord, errorCode: Int)
}

/**
 * Callbacks for mDNS client service discovery events.
 */
interface NsdDiscoveryCallback {
    fun onDiscoveryStarted(serviceType: String)
    fun onDiscoveryStartFailed(serviceType: String, errorCode: Int)
    fun onDiscoveryStopped(serviceType: String)
    fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)
    fun onServiceFound(service: NsdServiceRecord)
    fun onServiceLost(service: NsdServiceRecord)
}

/**
 * Callbacks for mDNS client service resolution events.
 */
interface NsdResolveCallback {
    fun onServiceResolved(resolvedService: NsdServiceRecord)
    fun onResolveFailed(service: NsdServiceRecord, errorCode: Int)
}

/**
 * Core abstraction over Android's [NsdManager] to enable decoupled, thread-safe
 * network discovery and 100% JVM host testing.
 */
interface NsdManagerWrapper {
    fun registerService(
        record: NsdServiceRecord,
        callback: NsdRegistrationCallback
    ): Boolean

    fun unregisterService(
        callback: NsdRegistrationCallback
    ): Boolean

    fun discoverServices(
        serviceType: String,
        callback: NsdDiscoveryCallback
    ): Boolean

    fun stopServiceDiscovery(
        callback: NsdDiscoveryCallback
    ): Boolean

    fun resolveService(
        record: NsdServiceRecord,
        callback: NsdResolveCallback
    )
}

/**
 * Production implementation of [NsdManagerWrapper] delegating to Android's platform [NsdManager].
 *
 * Implements a thread-safe serialized resolution queue to prevent Android's known
 * [NsdManager.FAILURE_ALREADY_ACTIVE] (errorCode 3) when resolving multiple services.
 */
class AndroidNsdManagerWrapper(
    context: Context
) : NsdManagerWrapper {

    companion object {
        private const val TAG = "AndroidNsdWrapper"
    }

    private val appContext = context.applicationContext ?: context
    private val nsdManager: NsdManager? = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val activeRegistrations = ConcurrentHashMap<NsdRegistrationCallback, NsdManager.RegistrationListener>()
    private val activeDiscoveries = ConcurrentHashMap<NsdDiscoveryCallback, NsdManager.DiscoveryListener>()

    // Serialized resolution queue to prevent concurrent resolution collisions
    private val resolveQueue = ConcurrentLinkedQueue<Pair<NsdServiceRecord, NsdResolveCallback>>()
    private val isResolving = AtomicBoolean(false)

    override fun registerService(
        record: NsdServiceRecord,
        callback: NsdRegistrationCallback
    ): Boolean {
        val manager = nsdManager ?: run {
            Log.e(TAG, "NsdManager unavailable on this system")
            callback.onRegistrationFailed(record, -1)
            return false
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = record.serviceName
            serviceType = record.serviceType
            port = record.port
            record.attributes.forEach { (key, value) ->
                setAttribute(key, value)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                val registeredRecord = NsdServiceRecord(
                    serviceName = registeredInfo.serviceName,
                    serviceType = registeredInfo.serviceType,
                    port = registeredInfo.port,
                    attributes = record.attributes
                )
                Log.d(TAG, "Host service registered: ${registeredRecord.serviceName}")
                callback.onServiceRegistered(registeredRecord)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                activeRegistrations.remove(callback)
                Log.e(TAG, "Host registration failed for ${info.serviceName}: $errorCode")
                callback.onRegistrationFailed(record, errorCode)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                activeRegistrations.remove(callback)
                Log.d(TAG, "Host service unregistered: ${info.serviceName}")
                callback.onServiceUnregistered(record)
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                activeRegistrations.remove(callback)
                Log.e(TAG, "Host unregistration failed for ${info.serviceName}: $errorCode")
                callback.onUnregistrationFailed(record, errorCode)
            }
        }

        activeRegistrations[callback] = listener

        return try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception during registerService: ${e.message}", e)
            activeRegistrations.remove(callback)
            callback.onRegistrationFailed(record, -1)
            false
        }
    }

    override fun unregisterService(callback: NsdRegistrationCallback): Boolean {
        val manager = nsdManager ?: return false
        val listener = activeRegistrations.remove(callback) ?: return true

        return try {
            manager.unregisterService(listener)
            true
        } catch (e: IllegalArgumentException) {
            // Listener was already unregistered or not found
            Log.w(TAG, "Listener already unregistered: ${e.message}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception during unregisterService: ${e.message}", e)
            false
        }
    }

    override fun discoverServices(
        serviceType: String,
        callback: NsdDiscoveryCallback
    ): Boolean {
        val manager = nsdManager ?: run {
            Log.e(TAG, "NsdManager unavailable on this system")
            callback.onDiscoveryStartFailed(serviceType, -1)
            return false
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started for: $regType")
                callback.onDiscoveryStarted(regType)
            }

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                activeDiscoveries.remove(callback)
                Log.e(TAG, "Start discovery failed for $type: $errorCode")
                callback.onDiscoveryStartFailed(type, errorCode)
            }

            override fun onDiscoveryStopped(type: String) {
                activeDiscoveries.remove(callback)
                Log.d(TAG, "Service discovery stopped for: $type")
                callback.onDiscoveryStopped(type)
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                activeDiscoveries.remove(callback)
                Log.e(TAG, "Stop discovery failed for $type: $errorCode")
                callback.onStopDiscoveryFailed(type, errorCode)
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                val record = NsdServiceRecord(
                    serviceName = info.serviceName,
                    serviceType = info.serviceType
                )
                callback.onServiceFound(record)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val record = NsdServiceRecord(
                    serviceName = info.serviceName,
                    serviceType = info.serviceType
                )
                callback.onServiceLost(record)
            }
        }

        activeDiscoveries[callback] = listener

        return try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception during discoverServices: ${e.message}", e)
            activeDiscoveries.remove(callback)
            callback.onDiscoveryStartFailed(serviceType, -1)
            false
        }
    }

    override fun stopServiceDiscovery(callback: NsdDiscoveryCallback): Boolean {
        resolveQueue.clear()
        val manager = nsdManager ?: return false
        val listener = activeDiscoveries.remove(callback) ?: return true

        return try {
            manager.stopServiceDiscovery(listener)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Discovery listener already stopped: ${e.message}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception during stopServiceDiscovery: ${e.message}", e)
            false
        }
    }

    override fun resolveService(
        record: NsdServiceRecord,
        callback: NsdResolveCallback
    ) {
        resolveQueue.add(Pair(record, callback))
        processNextResolve()
    }

    @Suppress("DEPRECATION")
    private fun processNextResolve() {
        if (!isResolving.compareAndSet(false, true)) {
            // Already processing a resolution item
            return
        }

        val nextItem = resolveQueue.poll()
        if (nextItem == null) {
            isResolving.set(false)
            return
        }

        val (record, callback) = nextItem
        val manager = nsdManager
        if (manager == null) {
            isResolving.set(false)
            callback.onResolveFailed(record, -1)
            processNextResolve()
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = record.serviceName
            serviceType = record.serviceType
        }

        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host = resolvedInfo.host?.hostAddress
                val port = resolvedInfo.port
                val attributes = convertAttributes(resolvedInfo.attributes)

                val resolvedRecord = NsdServiceRecord(
                    serviceName = resolvedInfo.serviceName,
                    serviceType = resolvedInfo.serviceType,
                    hostAddress = host,
                    port = port,
                    attributes = attributes
                )
                callback.onServiceResolved(resolvedRecord)

                isResolving.set(false)
                processNextResolve()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                callback.onResolveFailed(record, errorCode)

                isResolving.set(false)
                processNextResolve()
            }
        }

        try {
            manager.resolveService(serviceInfo, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling resolveService: ${e.message}", e)
            callback.onResolveFailed(record, -1)
            isResolving.set(false)
            processNextResolve()
        }
    }

    private fun convertAttributes(rawAttributes: Map<String, ByteArray>?): Map<String, String> {
        if (rawAttributes.isNullOrEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for ((key, bytes) in rawAttributes) {
            result[key] = try {
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
        return result
    }
}
