package com.roombeat.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class NetworkInterfaceHelperTest {

    @Test
    fun testIsHotspotInterface() {
        assertTrue(NetworkInterfaceHelper.isHotspotInterface("ap0"))
        assertTrue(NetworkInterfaceHelper.isHotspotInterface("ap1"))
        assertTrue(NetworkInterfaceHelper.isHotspotInterface("softap0"))
        assertTrue(NetworkInterfaceHelper.isHotspotInterface("swlan0"))
        assertTrue(NetworkInterfaceHelper.isHotspotInterface("p2p-wlan0-0"))
        assertTrue(NetworkInterfaceHelper.isHotspotInterface("rndis0"))

        assertFalse(NetworkInterfaceHelper.isHotspotInterface("wlan0"))
        assertFalse(NetworkInterfaceHelper.isHotspotInterface("eth0"))
        assertFalse(NetworkInterfaceHelper.isHotspotInterface("lo"))
    }

    @Test
    fun testIsWifiInterface() {
        assertTrue(NetworkInterfaceHelper.isWifiInterface("wlan0"))
        assertTrue(NetworkInterfaceHelper.isWifiInterface("wlan1"))
        assertTrue(NetworkInterfaceHelper.isWifiInterface("tiwlan0"))

        assertFalse(NetworkInterfaceHelper.isWifiInterface("eth0"))
        assertFalse(NetworkInterfaceHelper.isWifiInterface("ap0"))
        assertFalse(NetworkInterfaceHelper.isWifiInterface("softap0"))
        assertFalse(NetworkInterfaceHelper.isWifiInterface("lo"))
    }

    @Test
    fun testIsUsableIpv4() {
        val validIp = InetAddress.getByName("192.168.1.100")
        assertTrue(NetworkInterfaceHelper.isUsableIpv4(validIp))

        val loopback = InetAddress.getByName("127.0.0.1")
        assertFalse(NetworkInterfaceHelper.isUsableIpv4(loopback))

        val anyLocal = InetAddress.getByName("0.0.0.0")
        assertFalse(NetworkInterfaceHelper.isUsableIpv4(anyLocal))

        val ipv6 = InetAddress.getByName("fe80::1")
        assertFalse(NetworkInterfaceHelper.isUsableIpv4(ipv6))
    }

    @Test
    fun testIsLinkLocalIpv4() {
        val linkLocal = InetAddress.getByName("169.254.10.20")
        assertTrue(NetworkInterfaceHelper.isLinkLocalIpv4(linkLocal))

        val normalIp = InetAddress.getByName("192.168.1.5")
        assertFalse(NetworkInterfaceHelper.isLinkLocalIpv4(normalIp))
    }

    @Test
    fun testFindBestHostAddressPrioritizesHotspotOverWifi() {
        val interfaces = listOf(
            NetworkInterfaceHelper.DiscoveredInterface(
                name = "lo",
                isUp = true,
                isLoopback = true,
                addresses = listOf(InetAddress.getByName("127.0.0.1"))
            ),
            NetworkInterfaceHelper.DiscoveredInterface(
                name = "wlan0",
                isUp = true,
                isLoopback = false,
                addresses = listOf(InetAddress.getByName("192.168.1.150"))
            ),
            NetworkInterfaceHelper.DiscoveredInterface(
                name = "ap0",
                isUp = true,
                isLoopback = false,
                addresses = listOf(InetAddress.getByName("192.168.43.1"))
            )
        )

        val best = NetworkInterfaceHelper.findBestHostAddress(interfaces)
        assertNotNull(best)
        assertEquals("ap0", best!!.interfaceName)
        assertEquals("192.168.43.1", best.ipAddress)
        assertTrue(best.isHotspot)
        assertFalse(best.isWifi)
    }

    @Test
    fun testFindBestHostAddressSelectsWifiWhenNoHotspot() {
        val interfaces = listOf(
            NetworkInterfaceHelper.DiscoveredInterface(
                name = "eth0",
                isUp = true,
                isLoopback = false,
                addresses = listOf(InetAddress.getByName("10.0.0.50"))
            ),
            NetworkInterfaceHelper.DiscoveredInterface(
                name = "wlan0",
                isUp = true,
                isLoopback = false,
                addresses = listOf(InetAddress.getByName("192.168.1.200"))
            )
        )

        val best = NetworkInterfaceHelper.findBestHostAddress(interfaces)
        assertNotNull(best)
        assertEquals("wlan0", best!!.interfaceName)
        assertEquals("192.168.1.200", best.ipAddress)
        assertTrue(best.isWifi)
        assertFalse(best.isHotspot)
    }

    @Test
    fun testFindBestHostAddressFiltersLoopbackAndDownInterfaces() {
        val interfaces = listOf(
            NetworkInterfaceHelper.DiscoveredInterface(
                name = "lo",
                isUp = true,
                isLoopback = true,
                addresses = listOf(InetAddress.getByName("127.0.0.1"))
            ),
            NetworkInterfaceHelper.DiscoveredInterface(
                name = "wlan0",
                isUp = false, // DOWN
                isLoopback = false,
                addresses = listOf(InetAddress.getByName("192.168.1.200"))
            )
        )

        val best = NetworkInterfaceHelper.findBestHostAddress(interfaces)
        assertNull(best)
    }

    @Test
    fun testFindBestHostAddressFiltersIpv6Only() {
        val interfaces = listOf(
            NetworkInterfaceHelper.DiscoveredInterface(
                name = "wlan0",
                isUp = true,
                isLoopback = false,
                addresses = listOf(
                    InetAddress.getByName("fe80::20c:29ff:fe4a:3c05"),
                    InetAddress.getByName("2001:db8::1")
                )
            )
        )

        val best = NetworkInterfaceHelper.findBestHostAddress(interfaces)
        assertNull(best)
    }

    @Test
    fun testResolveBestHostAddressFallback() {
        val address = NetworkInterfaceHelper.resolveBestHostAddress()
        assertNotNull(address)
        assertTrue(address.isNotEmpty())
        // Should be either a detected IPv4 address or 127.0.0.1
        assertTrue(address.contains("."))
    }
}
