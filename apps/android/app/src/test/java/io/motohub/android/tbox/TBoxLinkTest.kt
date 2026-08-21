// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxLinkTest {
    private val bindIp = InetAddress.getByName("192.168.49.2") as Inet4Address
    private val gatewayIp = InetAddress.getByName("192.168.49.1") as Inet4Address

    @Test
    fun `disconnect releases the P2P group by default`() {
        var released = false
        val link = TBoxLink.WifiDirect(
            bindIp = bindIp,
            gatewayIp = gatewayIp,
            leaveGroup = { released = true }
        )

        link.disconnect()

        assertTrue("default behaviour must still release the group", released)
    }

    @Test
    fun `disconnecting a hosted link releases whatever created it`() {
        var released = false
        val link = TBoxLink.PhoneHotspot(
            subnet = TBoxHotspotScan.Subnet(
                localAddress = InetAddress.getByName("192.168.43.1") as Inet4Address,
                prefixLength = 24,
                interfaceName = "ap0"
            ),
            release = { released = true }
        )

        link.disconnect()

        assertTrue("a hotspot this app started must not outlive the session", released)
    }

    @Test
    fun `a hotspot the rider turned on by hand survives a disconnect`() {
        val link = TBoxLink.PhoneHotspot(
            subnet = TBoxHotspotScan.Subnet(
                localAddress = InetAddress.getByName("192.168.43.1") as Inet4Address,
                prefixLength = 24,
                interfaceName = "ap0"
            )
        )

        // Nothing to assert but that it does not throw: there is nothing this app may take away.
        link.disconnect()
    }

    @Test
    fun `disconnect skips releasing the group when told to keep it`() {
        var released = false
        val link = TBoxLink.WifiDirect(
            bindIp = bindIp,
            gatewayIp = gatewayIp,
            leaveGroup = { released = true },
            releaseGroupOnDisconnect = false
        )

        link.disconnect()

        assertFalse("opted-in riders must keep the P2P link across a disconnect", released)
    }
}
