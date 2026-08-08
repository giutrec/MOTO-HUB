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
}
