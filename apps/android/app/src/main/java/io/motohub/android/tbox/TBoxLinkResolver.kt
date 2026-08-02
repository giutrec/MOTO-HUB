package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode

/**
 * Single decision point for how to reach a T-Box. A profile can explicitly select Auto, AP, or
 * Wi-Fi Direct, which is essential for dashboards that act as P2P Group Owners without exposing
 * a conventional access point.
 */
object TBoxLinkResolver {

    suspend fun connect(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile
    ): Result<TBoxLink> =
        if (profile.connectionMode == TBoxConnectionMode.PHONE_HOTSPOT) {
            hostedLink()
        } else if (usesWifiDirect(profile)) {
            ProjectionEventLog.record(
                "NETWORK",
                "Connecting to ${profile.ssid} through Wi-Fi Direct (${profile.connectionMode})."
            )
            TBoxWifiDirectConnector(context).connect(profile).map { it }
        } else {
            ProjectionEventLog.record(
                "NETWORK",
                "Connecting to ${profile.ssid} through the Wi-Fi access-point transport (${profile.connectionMode})."
            )
            networkConnector.connect(profile).map { TBoxLink.Infrastructure(it) }
        }

    /** Recovery variant: reuse a still-alive infrastructure network before reconnecting. */
    suspend fun reacquire(
        context: Context,
        networkConnector: TBoxNetworkConnector,
        profile: MotorcycleProfile,
        awaitNetworkMillis: Long
    ): TBoxLink {
        if (usesWifiDirect(profile)) {
            // A P2P group has no ConnectivityManager-visible network to await; rejoin directly.
            return TBoxWifiDirectConnector(context).connect(profile).getOrThrow()
        }
        val network = networkConnector.currentNetwork()
            ?: networkConnector.awaitNetworkAvailable(awaitNetworkMillis)
            ?: networkConnector.connect(profile).getOrThrow()
        return TBoxLink.Infrastructure(network)
    }

    /**
     * Public so a caller can find out which transport a profile will take *before* asking for it.
     * PRO needs this: it has to form the Wi-Fi Direct group in its own process before handing the
     * connect to CORE (Android only grants a P2P join to a caller with a visible activity), while
     * the access-point join still happens inside CORE. The two paths also differ in their
     * permission gate, so PRO has to know which one it is about to trigger.
     */
    fun usesWifiDirect(profile: MotorcycleProfile): Boolean = when (profile.connectionMode) {
        TBoxConnectionMode.WIFI_DIRECT -> true
        TBoxConnectionMode.ACCESS_POINT, TBoxConnectionMode.PHONE_HOTSPOT -> false
        TBoxConnectionMode.AUTO -> TBoxWifiDirectConnector.isWifiDirectSsid(profile.ssid)
    }

    /**
     * There is nothing to connect *to* in hotspot mode - the rider has already turned tethering on
     * by hand, because Android does not let an app create a hotspot with the SSID and password the
     * dash dictates. All this does is find the interface hosting it, so discovery knows which
     * subnet the dash is sitting on.
     *
     * Failing with a rider-readable message matters more here than anywhere else: "no hotspot
     * found" is something they can act on immediately, and it is by far the likeliest mistake.
     */
    private fun hostedLink(): Result<TBoxLink> {
        val subnets = TBoxHotspotScan.tetheringSubnets(TBoxHotspotScan.snapshotInterfaces())
        val subnet = subnets.firstOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    "This motorcycle expects your phone to host the network, but no hotspot is " +
                        "running. Turn on the Android hotspot with the exact Ssid and Password " +
                        "the dash is showing, then connect again."
                )
            )
        ProjectionEventLog.record(
            "NETWORK",
            "Phone-hosted transport: dash expected on ${subnet.localAddress.hostAddress}/" +
                "${subnet.prefixLength} via ${subnet.interfaceName}" +
                if (subnets.size > 1) " (${subnets.size} candidate interfaces)." else "."
        )
        return Result.success(TBoxLink.PhoneHotspot(subnet))
    }
}
