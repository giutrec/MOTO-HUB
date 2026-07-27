package io.motohub.android.tbox

import org.junit.Assert.assertFalse
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
}
