package com.roombeat.app.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Enumeration

/**
 * Helper to detect local network interfaces and extract the active IPv4 address
 * for Wi-Fi or Hotspot operation in RoomBeat.
 */
object NetworkInterfaceHelper {

    const val DEFAULT_LOOPBACK_IP = "127.0.0.1"
    const val DEFAULT_SERVER_PORT = 8080
    val PORT_FALLBACK_RANGE = 8080..8089

    /**
     * Information about a discovered network address.
     */
    data class NetworkAddressInfo(
        val interfaceName: String,
        val ipAddress: String,
        val isHotspot: Boolean,
        val isWifi: Boolean
    )

    /**
     * Pure representation of a network interface to support testability.
     */
    data class DiscoveredInterface(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val addresses: List<InetAddress>
    )

    // Regex patterns for mobile Hotspot / SoftAP interfaces (ap0, softap0, swlan0, p2p0, etc.)
    private val HOTSPOT_REGEX = Regex("^(ap|softap|swlan|p2p|wigig|rndis).*$", RegexOption.IGNORE_CASE)

    // Regex patterns for mobile Wi-Fi client interfaces (wlan0, tiwlan0, etc.)
    private val WIFI_REGEX = Regex("^(wlan|tiwlan).*$", RegexOption.IGNORE_CASE)

    /**
     * Checks if an interface name is a known hotspot/AP interface.
     */
    fun isHotspotInterface(name: String): Boolean {
        val lower = name.lowercase()
        return HOTSPOT_REGEX.matches(lower) || lower.contains("softap") || lower.startsWith("ap")
    }

    /**
     * Checks if an interface name is a known Wi-Fi client interface.
     */
    fun isWifiInterface(name: String): Boolean {
        val lower = name.lowercase()
        return (WIFI_REGEX.matches(lower) || lower.startsWith("wlan")) && !isHotspotInterface(name)
    }

    /**
     * Validates whether an [InetAddress] is a usable IPv4 address:
     * - Must be an IPv4 address
     * - Must NOT be loopback (127.0.0.0/8)
     * - Must NOT be any-local (0.0.0.0)
     */
    fun isUsableIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        if (address.isLoopbackAddress) return false
        if (address.isAnyLocalAddress) return false
        val host = address.hostAddress ?: return false
        if (host.startsWith("127.")) return false
        if (host == "0.0.0.0") return false
        return true
    }

    /**
     * Checks if an address is an IPv4 link-local address (169.254.0.0/16 APIPA).
     */
    fun isLinkLocalIpv4(address: InetAddress): Boolean {
        return address.isLinkLocalAddress || (address.hostAddress?.startsWith("169.254.") == true)
    }

    /**
     * Resolves the best active IPv4 address among a list of discovered interfaces.
     * Priority:
     * 1. Active Hotspot interface with valid non-link-local IPv4
     * 2. Active Wi-Fi interface with valid non-link-local IPv4
     * 3. Any other active non-loopback interface with valid non-link-local IPv4
     * 4. Any active interface with link-local IPv4
     * 5. null (caller may fallback to loopback)
     */
    fun findBestHostAddress(interfaces: List<DiscoveredInterface>): NetworkAddressInfo? {
        val candidates = mutableListOf<NetworkAddressInfo>()

        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue

            val isHotspot = isHotspotInterface(iface.name)
            val isWifi = isWifiInterface(iface.name)

            for (addr in iface.addresses) {
                if (isUsableIpv4(addr)) {
                    val hostAddress = addr.hostAddress ?: continue
                    candidates.add(
                        NetworkAddressInfo(
                            interfaceName = iface.name,
                            ipAddress = hostAddress,
                            isHotspot = isHotspot,
                            isWifi = isWifi
                        )
                    )
                }
            }
        }

        if (candidates.isEmpty()) return null

        // 1. Hotspot non-link-local
        val hotspotNonLinkLocal = candidates.firstOrNull { it.isHotspot && !it.ipAddress.startsWith("169.254.") }
        if (hotspotNonLinkLocal != null) return hotspotNonLinkLocal

        // 2. Wi-Fi non-link-local
        val wifiNonLinkLocal = candidates.firstOrNull { it.isWifi && !it.ipAddress.startsWith("169.254.") }
        if (wifiNonLinkLocal != null) return wifiNonLinkLocal

        // 3. Other non-link-local
        val otherNonLinkLocal = candidates.firstOrNull { !it.ipAddress.startsWith("169.254.") }
        if (otherNonLinkLocal != null) return otherNonLinkLocal

        // 4. Fallback to any candidate (even link-local)
        return candidates.first()
    }

    /**
     * Reads system network interfaces and returns them as a pure data list.
     */
    fun collectSystemInterfaces(): List<DiscoveredInterface> {
        val result = mutableListOf<DiscoveredInterface>()
        try {
            val enumeration: Enumeration<NetworkInterface>? = NetworkInterface.getNetworkInterfaces()
            if (enumeration != null) {
                for (iface in Collections.list(enumeration)) {
                    val isUp = try { iface.isUp } catch (_: Exception) { true }
                    val isLoopback = try { iface.isLoopback } catch (_: Exception) { false }
                    val addrs = Collections.list(iface.inetAddresses)
                    result.add(
                        DiscoveredInterface(
                            name = iface.name,
                            isUp = isUp,
                            isLoopback = isLoopback,
                            addresses = addrs
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Defensive handling against system security or I/O exceptions
        }
        return result
    }

    /**
     * Gets the best active network address info on the device.
     */
    fun getActiveNetworkInfo(): NetworkAddressInfo? {
        return findBestHostAddress(collectSystemInterfaces())
    }

    /**
     * Resolves the best local IPv4 address string to bind or advertise,
     * falling back to [DEFAULT_LOOPBACK_IP] if no external interface is found.
     */
    fun resolveBestHostAddress(): String {
        return getActiveNetworkInfo()?.ipAddress ?: DEFAULT_LOOPBACK_IP
    }
}
