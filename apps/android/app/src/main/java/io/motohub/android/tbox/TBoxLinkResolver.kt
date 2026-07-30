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
        if (usesWifiDirect(profile)) {
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
     * PRO needs this: the Wi-Fi Direct join runs in CORE's process and has a permission gate the
     * access-point path does not, so PRO has to know which one it is about to trigger.
     */
    fun usesWifiDirect(profile: MotorcycleProfile): Boolean = when (profile.connectionMode) {
        TBoxConnectionMode.WIFI_DIRECT -> true
        TBoxConnectionMode.ACCESS_POINT -> false
        TBoxConnectionMode.AUTO -> TBoxWifiDirectConnector.isWifiDirectSsid(profile.ssid)
    }
}
