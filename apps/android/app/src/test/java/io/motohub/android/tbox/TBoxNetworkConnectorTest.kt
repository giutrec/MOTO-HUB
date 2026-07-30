package io.motohub.android.tbox

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxNetworkConnectorTest {
    @Test
    fun acceptsUsableIpv4AddressesAcrossTBoxDhcpSubnets() {
        assertTrue(isUsableTBoxIpv4Address(InetAddress.getByName("192.168.0.23")))
        assertTrue(isUsableTBoxIpv4Address(InetAddress.getByName("192.168.43.91")))
        assertTrue(isUsableTBoxIpv4Address(InetAddress.getByName("10.42.0.8")))
        assertTrue(isUsableTBoxIpv4Address(InetAddress.getByName("172.20.10.4")))
    }

    @Test
    fun rejectsAddressesThatCannotCarryTheEasyConnIpv4Session() {
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("0.0.0.0")))
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("127.0.0.1")))
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("169.254.12.4")))
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("224.0.0.251")))
        assertFalse(isUsableTBoxIpv4Address(InetAddress.getByName("fe80::1")))
    }

    @Test
    fun decodesEasyConnTouchFrame() {
        val payload = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 2.toShort())
            putShort(2, 645.toShort())
            putShort(4, 217.toShort())
            putShort(6, 1.toShort())
        }.array()

        assertEquals(TBoxEvent.Touch(action = 0, pointerId = 1, x = 645, y = 217), decodeTBoxTouch(payload))
    }

    @Test
    fun rejectsUnknownOrTruncatedTouchFrames() {
        assertNull(decodeTBoxTouch(byteArrayOf(1, 2, 3)))
        val unknown = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 99.toShort())
        }.array()
        assertNull(decodeTBoxTouch(unknown))
    }

    @Test
    fun prefersSameSubnetGatewayForEasyConnPeer() {
        val peer = deriveTBoxPeerIpv4(
            gateways = listOf(InetAddress.getByName("192.168.49.1")),
            dnsServers = emptyList(),
            localAddresses = listOf(InetAddress.getByName("192.168.49.37") to 24)
        )

        assertEquals("192.168.49.1", peer?.hostAddress)
    }

    @Test
    fun derivesWifiDirectGroupOwnerForAnyUsableIpv4Prefix() {
        val peer = deriveTBoxPeerIpv4(
            gateways = emptyList(),
            dnsServers = emptyList(),
            localAddresses = listOf(InetAddress.getByName("192.168.49.37") to 16)
        )

        assertEquals("192.168.0.1", peer?.hostAddress)
        assertEquals(
            "192.168.49.1",
            deriveTBoxPeerIpv4(
                gateways = emptyList(),
                dnsServers = emptyList(),
                localAddresses = listOf(InetAddress.getByName("192.168.49.37") to 24)
            )?.hostAddress
        )
    }

    @Test
    fun rejectsOffSubnetDnsAndLocalGroupOwner() {
        assertNull(
            deriveTBoxPeerIpv4(
                gateways = emptyList(),
                dnsServers = listOf(InetAddress.getByName("8.8.8.8")),
                localAddresses = listOf(InetAddress.getByName("192.168.49.1") to 24)
            )
        )
    }

    @Test
    fun acceptsOnlyNumericIpv4TxtValues() {
        assertEquals("192.168.49.1", parseIpv4Literal(" 192.168.49.1 "))
        assertNull(parseIpv4Literal("192.168.49.999"))
        assertNull(parseIpv4Literal("fe80::1"))
        assertNull(parseIpv4Literal("bike.local"))
    }

    @Test
    fun rejectsLoopbackTxtValuesForEasyConnDiscovery() {
        assertEquals("192.168.49.1", parseUsableEasyConnIpv4Literal("192.168.49.1"))
        assertNull(parseUsableEasyConnIpv4Literal("127.0.0.1"))
        assertNull(parseUsableEasyConnIpv4Literal("0.0.0.0"))
        assertNull(parseUsableEasyConnIpv4Literal("224.0.0.251"))
    }

    @Test
    fun namesTheBandOfAScannedAccessPoint() {
        // The band is what tells a "VOGE-5G-..." style SSID apart from a dash that really is on
        // 5GHz, and a 5GHz-only dash is one the phone may be unable to join at all.
        assertEquals("2.4GHz", bandName(2412))
        assertEquals("2.4GHz", bandName(2484))
        assertEquals("5GHz", bandName(5180))
        assertEquals("5GHz", bandName(5885))
        assertEquals("6GHz", bandName(6115))
    }

    @Test
    fun namesWpa3SeparatelyBecauseTheSpecifierCannotOfferIt() {
        // Only ever setWpa2Passphrase is offered, so an SAE-only dash is unjoinable and the log has
        // to be able to say so instead of blaming the dash for not broadcasting.
        assertEquals("WPA3/SAE", securityName("[ESS][SAE][MFPR][MFPC]"))
        assertEquals("WPA3/SAE+WPA2", securityName("[ESS][RSN-SAE+PSK-CCMP][MFPC]"))
        assertEquals("WPA2", securityName("[ESS][WPA2-PSK-CCMP][RSN-PSK-CCMP]"))
        assertEquals("WEP", securityName("[ESS][WEP]"))
    }

    @Test
    fun reportsAnUnsecuredOrUnreadableApHonestly() {
        assertEquals("open or unrecognised ([ESS]", securityName("[ESS]").substringBefore(')'))
        assertEquals("not reported", securityName(null))
        assertEquals("not reported", securityName(""))
    }

    @Test
    fun refusesToGuessABandItDoesNotRecognise() {
        // Better an explicit unknown in a rider's log than a confident wrong band.
        assertEquals("an unknown band", bandName(0))
        assertEquals("an unknown band", bandName(-1))
        assertEquals("an unknown band", bandName(900))
        assertEquals("an unknown band", bandName(5910))
    }
}
