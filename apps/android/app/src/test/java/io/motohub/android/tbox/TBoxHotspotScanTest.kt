package io.motohub.android.tbox

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxHotspotScanTest {

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address

    private fun snapshot(
        name: String,
        address: String,
        prefixLength: Int = 24,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        isPointToPoint: Boolean = false
    ) = TBoxHotspotScan.InterfaceSnapshot(
        name = name,
        isUp = isUp,
        isLoopback = isLoopback,
        isPointToPoint = isPointToPoint,
        addresses = listOf(ipv4(address) as InetAddress to prefixLength)
    )

    @Test
    fun findsTheTetheringSubnetAndIgnoresTheNetworkThePhoneIsUsing() {
        // The realistic shape: the rider's phone is on their home Wi-Fi AND hosting the hotspot
        // the dash joins. Sweeping the home subnet would be slow and would probe other people's
        // devices, so the joined network's own address is excluded by the caller.
        val subnets = TBoxHotspotScan.tetheringSubnets(
            interfaces = listOf(
                snapshot("lo", "127.0.0.1", isLoopback = true),
                snapshot("wlan0", "192.168.1.34"),
                snapshot("ap0", "192.168.43.1")
            ),
            excluding = setOf(ipv4("192.168.1.34"))
        )

        assertEquals(1, subnets.size)
        assertEquals(ipv4("192.168.43.1"), subnets.first().localAddress)
        assertEquals("ap0", subnets.first().interfaceName)
    }

    @Test
    fun prefersTheInterfaceThatLooksLikeAHostedNetwork() {
        // Unknown names are still accepted - vendors rename these - but ranked last, so the
        // likeliest candidate is swept first rather than after 254 wasted connects.
        val subnets = TBoxHotspotScan.tetheringSubnets(
            listOf(
                snapshot("eth7", "10.5.5.1"),
                snapshot("swlan0", "192.168.43.1")
            )
        )

        assertEquals(listOf("swlan0", "eth7"), subnets.map { it.interfaceName })
    }

    @Test
    fun rejectsInterfacesThatCannotBeAHostedNetwork() {
        val subnets = TBoxHotspotScan.tetheringSubnets(
            listOf(
                snapshot("ap0", "192.168.43.1", isUp = false),
                snapshot("wlan1", "169.254.4.4"),
                snapshot("rmnet0", "100.65.1.20", isPointToPoint = true),
                // Routable address: that is the carrier's, never a hotspot.
                snapshot("eth0", "93.184.216.34"),
                // Wider than a /24 would be thousands of addresses; not sweepable.
                snapshot("ap1", "10.0.0.1", prefixLength = 16)
            )
        )

        assertTrue(subnets.map { it.interfaceName }.toString(), subnets.isEmpty())
    }

    @Test
    fun enumeratesEveryHostExceptTheNetworkBroadcastAndThePhoneItself() {
        val hosts = TBoxHotspotScan.candidateHosts(
            TBoxHotspotScan.Subnet(ipv4("192.168.43.1"), prefixLength = 24, interfaceName = "ap0")
        )

        assertEquals(253, hosts.size)
        assertTrue(ipv4("192.168.43.0") !in hosts)
        assertTrue(ipv4("192.168.43.255") !in hosts)
        assertTrue(ipv4("192.168.43.1") !in hosts)
        assertTrue(ipv4("192.168.43.2") in hosts)
        assertTrue(ipv4("192.168.43.254") in hosts)
    }

    @Test
    fun triesAddressesNearestThePhoneFirst() {
        // Android's DHCP pool starts just above the gateway, so the dash is almost always in the
        // first handful. Sweeping in numeric order would find it just as reliably but far slower.
        val hosts = TBoxHotspotScan.candidateHosts(
            TBoxHotspotScan.Subnet(ipv4("192.168.43.1"), prefixLength = 24, interfaceName = "ap0")
        )

        assertEquals(ipv4("192.168.43.2"), hosts.first())
        assertTrue(hosts.take(6).contains(ipv4("192.168.43.3")))
        assertTrue(hosts.indexOf(ipv4("192.168.43.200")) > 100)
    }

    @Test
    fun handlesASmallSubnetWithoutRunningOffTheEnd() {
        val hosts = TBoxHotspotScan.candidateHosts(
            TBoxHotspotScan.Subnet(ipv4("192.168.49.1"), prefixLength = 29, interfaceName = "ap0")
        )

        // /29 is 8 addresses: minus network, broadcast and the phone itself.
        assertEquals(5, hosts.size)
        assertTrue(hosts.all { it.hostAddress!!.startsWith("192.168.49.") })
    }
}
