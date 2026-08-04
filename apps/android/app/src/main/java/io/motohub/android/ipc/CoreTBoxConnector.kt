// CORE-only: runs the actual T-Box connection (Wi-Fi join + EasyConn discovery via the GPL
// hudlib transport) and installs the session, so a companion app (PRO) can trigger it over AIDL
// without containing any of this GPL code itself. Mirrors HubViewModel.connectAndDiscover()'s
// flow (UI-free). When the flavor split lands, this file moves to the core-only source set
// alongside RideDaemonTransport.
package io.motohub.android.ipc

import android.content.Context
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.SelectingTBoxTransport
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxNetworkConnector
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry

/** Establishes and tears down a T-Box session on behalf of an AIDL caller. */
class CoreTBoxConnector(private val context: Context) {

    private val networkConnector = TBoxNetworkConnector(context)
    private val transport = SelectingTBoxTransport(context)
    private val capabilityStore = TBoxCapabilityStore(context)

    suspend fun connect(profile: MotorcycleProfile): Boolean {
        // A session CORE started for itself outlives the activity on purpose - a projection has to
        // survive the screen going away - and a companion app asking to connect in that moment used
        // to build a SECOND TBoxNetworkConnector beside it. Two exclusive WifiNetworkSpecifier
        // requests for the same SSID do not queue, they fight: each grant drops the other's network.
        // Field log 2026-07-31 (OnePlus CPH2653, EASYCONN_5G-F3116E): the rider left an Android Auto
        // session running, started the Ride Dashboard from the companion app, and got networks
        // 202 through 207 granted and lost within a second each, one dashboard frame, a broken pipe,
        // then eleven rejoin attempts refused by Android in 2-10ms before it gave up 3.5 minutes
        // later. Refusing here costs that rider one clear sentence instead.
        val holder = TBoxSessionRegistry.current()
        val consumers = TBoxSessionRegistry.activeConsumers()
        if (holder != null && holder.networkConnector !== networkConnector && consumers.isNotEmpty()) {
            ProjectionEventLog.error(
                "IPC_TBOX",
                "AIDL connect refused: MOTO-HUB Core is already using this dash for $consumers. " +
                    "Two connectors would compete for the same Wi-Fi association and drop each " +
                    "other's network. Stop that session first, then connect again."
            )
            return false
        }
        val connected = TBoxLinkResolver.connect(context, networkConnector, profile)
        val link = connected.getOrElse {
            ProjectionEventLog.error("IPC_TBOX", "AIDL connect: T-Box network connection failed.", it)
            return false
        }
        ProjectionEventLog.record("IPC_TBOX", "AIDL connect: T-Box link established (${link.label}).")
        transport.configureProtocolProfile(
            TBoxModelProfile.resolve(
                profile.modelId,
                null,
                ProfileOverride.byKey(profile.profileOverrideKey)
            )
        )
        val discovered = transport.discover(link, profile.modelId)
        val host = discovered.getOrElse {
            ProjectionEventLog.error("IPC_TBOX", "AIDL connect: EasyConn discovery failed.", it)
            transport.stop()
            link.disconnect()
            networkConnector.disconnect()
            TBoxSessionRegistry.clear()
            return false
        }
        capabilityStore.recordDiscovery(profile, host)
        TBoxSessionRegistry.install(
            TBoxSessionHandle(transport, host, networkConnector, profile, link)
        )
        ProjectionEventLog.record("IPC_TBOX", "AIDL connect: session installed; READY.")
        return true
    }

    /**
     * Cleanup for a cancelled connect(): unlike [disconnect] (which looks up the *registry's*
     * active handle), this tears down THIS connector's own transport/networkConnector directly —
     * needed because cancellation can land before TBoxSessionRegistry.install() ever ran, when
     * the registry wouldn't yet reference this attempt's (possibly half-open) link.
     */
    suspend fun cancel() {
        transport.stop()
        networkConnector.disconnect()
        TBoxSessionRegistry.clear()
    }

    suspend fun disconnect() = disconnectActiveSession()

    companion object {
        /**
         * Tears down whatever session the registry holds, whoever established it.
         *
         * Instance-independent by nature - it reads the registry, not this object - so it is
         * exposed here rather than forcing a caller to build a connector just to reach it.
         * Constructing one had a cost that was not obvious: every throwaway connector brought its
         * own [TBoxNetworkConnector][io.motohub.android.tbox.TBoxNetworkConnector] and its own
         * exclusive Wi-Fi request, so a disconnect could leave behind exactly the orphan it was
         * supposed to be clearing up.
         */
        suspend fun disconnectActiveSession() {
            val handle = TBoxSessionRegistry.current() ?: return
            handle.transport.stop()
            handle.networkConnector.disconnect()
            TBoxSessionRegistry.clear(handle)
        }
    }
}

/** Builds a MotorcycleProfile from an AIDL connect request (the caller owns these credentials). */
internal fun MotorcycleConnectRequest.toProfile(): MotorcycleProfile = MotorcycleProfile(
    ssid = ssid,
    password = password,
    id = id,
    modelId = modelId,
    displayName = displayName,
    profileOverrideKey = profileOverrideKey,
    connectionMode = runCatching { TBoxConnectionMode.valueOf(connectionMode) }
        .getOrDefault(TBoxConnectionMode.AUTO)
)
