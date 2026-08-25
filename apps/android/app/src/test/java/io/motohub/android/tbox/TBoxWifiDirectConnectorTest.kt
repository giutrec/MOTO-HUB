// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxWifiDirectConnectorTest {
    @Test
    fun `matches the profile group name ignoring case and quotes`() {
        assertTrue(
            TBoxWifiDirectConnector.groupNameMatchesProfile("DIRECT-CL-C450-1234", "DIRECT-CL-C450-1234")
        )
        assertTrue(
            TBoxWifiDirectConnector.groupNameMatchesProfile("direct-cl-c450-1234", "\"DIRECT-CL-C450-1234\"")
        )
        assertTrue(
            TBoxWifiDirectConnector.groupNameMatchesProfile(" DIRECT-AB12 ", "DIRECT-AB12")
        )
    }

    @Test
    fun `rejects a formed group that belongs to another device`() {
        assertFalse(
            TBoxWifiDirectConnector.groupNameMatchesProfile("DIRECT-tv-LivingRoom", "DIRECT-CL-C450-1234")
        )
        assertFalse(
            TBoxWifiDirectConnector.groupNameMatchesProfile("DIRECT-XY99-otherbike", "DIRECT-CL-C450-1234")
        )
    }

    @Test
    fun `accepts an unverifiable group name rather than breaking working joins`() {
        assertTrue(TBoxWifiDirectConnector.groupNameMatchesProfile(null, "DIRECT-CL-C450-1234"))
        assertTrue(TBoxWifiDirectConnector.groupNameMatchesProfile("", "DIRECT-CL-C450-1234"))
        assertTrue(TBoxWifiDirectConnector.groupNameMatchesProfile("  ", "DIRECT-CL-C450-1234"))
    }

    @Test
    fun `recovers the dash peer name from the group ssid`() {
        // The SSID riders actually reported from the field.
        assertEquals(
            "CFMOTO-EF7198",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-go-CFMOTO-EF7198")
        )
        assertEquals(
            "CL-C450-1234",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("\"DIRECT-XY-CL-C450-1234\" ")
        )
        assertEquals(
            "LivingRoom",
            TBoxWifiDirectConnector.peerNameFromGroupSsid("direct-tv-LivingRoom")
        )
    }

    @Test
    fun `falls back to a credential join when the ssid is not a DIRECT group name`() {
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("MotoHubAP"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-AB12"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT--"))
        assertNull(TBoxWifiDirectConnector.peerNameFromGroupSsid("DIRECT-go-"))
    }

    @Test
    fun `looks for the peer named inside a group ssid`() {
        assertEquals(
            "CFMOTO-EF7198",
            TBoxWifiDirectConnector.expectedPeerName("DIRECT-go-CFMOTO-EF7198")
        )
    }

    @Test
    fun `treats a non-group ssid as the peer name itself`() {
        // A rider's Voge: Android's own Wi-Fi Direct screen lists the dash as the device
        // "VOGE-5G-4474", and that same string is all the rider ever gets to enter. Deriving
        // nothing from it used to abandon discovery, which left no way in at all - the
        // credentials join cannot express a name without the DIRECT- prefix.
        assertEquals("VOGE-5G-4474", TBoxWifiDirectConnector.expectedPeerName("VOGE-5G-4474"))
        assertEquals("VOGE-5G-4474", TBoxWifiDirectConnector.expectedPeerName(" \"VOGE-5G-4474\" "))
        // A DIRECT- name that carries no device part is still all we have to search for.
        assertEquals("DIRECT-ee", TBoxWifiDirectConnector.expectedPeerName("DIRECT-ee"))
    }

    @Test
    fun `retries a refused join for as long as the budget can hold another round`() {
        // The shape the field log shows: a refused round comes back in about 2.5s, and the
        // whole-join budget is 35s. Four rounds is what that buys, and the rider gets one real
        // 30s attempt instead of a 2.5s one repeated by the watchdog.
        val budget = 35_000L
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(2_500L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(11_000L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(19_500L, budget))
        assertTrue(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(26_000L, budget))
    }

    @Test
    fun `stops retrying while there is still time to report why`() {
        // The retry that would not fit is the one that matters: started anyway, it is cut off by
        // the 35s timeout and the rider is told the dash never formed a group - a statement about
        // the motorcycle, when the phone is the one refusing.
        val budget = 35_000L
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(26_001L, budget))
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(34_000L, budget))
        assertFalse(TBoxWifiDirectConnector.shouldSettleAndRetryJoin(60_000L, budget))
    }

    @Test
    fun `a budget too small for one settled round refuses the very first retry`() {
        assertFalse(
            TBoxWifiDirectConnector.shouldSettleAndRetryJoin(
                elapsedMillis = 0L,
                budgetMillis = 5_000L,
                settleMillis = 6_000L,
                roundCostMillis = 3_000L
            )
        )
    }
}
