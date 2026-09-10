package com.roombeat.app.network.discovery

import kotlinx.serialization.Serializable

/**
 * Represents a RoomBeat host room discovered via local network mDNS service broadcast.
 *
 * @property sessionId Unique identifier for the RoomBeat session.
 * @property serviceName The mDNS service instance name.
 * @property hostAddress IPv4 address of the host device.
 * @property port Active TCP control plane socket port on the host.
 * @property hostDeviceModel Human-readable device manufacturer and model.
 * @property attributes Raw TXT record key-value pairs advertised by the host.
 * @property discoveredAtMs Timestamp (in ms) when the service was discovered.
 */
@Serializable
data class DiscoveredRoom(
    val sessionId: String,
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
    val hostDeviceModel: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val discoveredAtMs: Long = System.currentTimeMillis()
) {
    /**
     * Endpoint formatted as "host:port".
     */
    val endpoint: String
        get() = "$hostAddress:$port"

    /**
     * Protocol or app version reported by the host, or empty string if not advertised.
     */
    val version: String
        get() = attributes["version"] ?: ""

    /**
     * Verifies if this discovered room has valid connectivity coordinates.
     */
    val isValid: Boolean
        get() = sessionId.isNotBlank() && hostAddress.isNotBlank() && port in 1..65535
}
