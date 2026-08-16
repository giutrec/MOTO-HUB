package io.motohub.android.tbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxVpnDiagnosticsTest {

    private fun routing(
        capturesDefaultRoute: Boolean = false,
        capturesDash: Boolean = false,
        interfaceName: String? = "tun0"
    ) = TBoxVpnDiagnostics.VpnRouting(
        interfaceName = interfaceName,
        capturesDefaultRoute = capturesDefaultRoute,
        capturesDash = capturesDash
    )

    @Test
    fun detectsAndroidPermissionErrorsThroughNestedCauses() {
        val error = IllegalStateException(
            "socket setup failed",
            SecurityException("connect failed: Operation not permitted")
        )

        assertTrue(TBoxVpnDiagnostics.isVpnBindBlocked(error))
    }

    @Test
    fun doesNotClassifyOrdinaryTimeoutAsVpnFailureWithoutActiveVpn() {
        val error = java.net.SocketTimeoutException("connection timed out")

        assertFalse(TBoxVpnDiagnostics.isVpnBindBlocked(error))
        assertNull(TBoxVpnDiagnostics.userFacingMessage(error, routing = null))
    }

    /**
     * The regression this whole class exists for: a rider running Tailscale without an exit node
     * was told a VPN was blocking the dash, because one merely existed. A tunnel that claims
     * neither the default route nor the dash's address is a bystander.
     */
    @Test
    fun doesNotBlameAVpnThatDoesNotClaimTheRouteToTheDash() {
        val error = IllegalStateException("Android cannot bind MOTO-HUB to the T-Box network.")

        assertNull(TBoxVpnDiagnostics.userFacingMessage(error, routing()))
    }

    @Test
    fun blamesAFullTunnelVpn() {
        val message = TBoxVpnDiagnostics.userFacingMessage(
            error = IllegalStateException("Android cannot bind MOTO-HUB to the T-Box network."),
            routing = routing(capturesDefaultRoute = true)
        )

        assertNotNull(message)
        assertTrue(TBoxVpnDiagnostics.isVpnRoutingMessage(message))
        assertTrue(message!!.contains("tun0"))
    }

    /** A client with LAN access off claims the private ranges without claiming everything. */
    @Test
    fun blamesAVpnThatClaimsOnlyTheDashSubnet() {
        val message = TBoxVpnDiagnostics.userFacingMessage(
            error = IllegalStateException("Android cannot bind MOTO-HUB to the T-Box network."),
            routing = routing(capturesDash = true)
        )

        assertNotNull(message)
        assertTrue(TBoxVpnDiagnostics.isVpnRoutingMessage(message))
    }

    /**
     * Lockdown produces a permission error rather than a route we can read, so the error alone
     * still has to be enough - with or without a routing snapshot to go with it.
     */
    @Test
    fun blamesAVpnOnPermissionEvidenceEvenWithoutReadableRoutes() {
        val error = IllegalStateException("bind failed: EPERM (Operation not permitted)")

        assertNotNull(TBoxVpnDiagnostics.userFacingMessage(error, routing = null))
    }

    @Test
    fun doesNotMistakeAnUnrelatedMessageForAVpnDiagnosis() {
        assertFalse(TBoxVpnDiagnostics.isVpnRoutingMessage(null))
        assertFalse(TBoxVpnDiagnostics.isVpnRoutingMessage("T-Box not found: no route to host"))
    }
}
