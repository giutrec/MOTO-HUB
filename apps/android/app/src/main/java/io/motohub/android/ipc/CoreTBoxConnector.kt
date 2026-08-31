// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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
import io.motohub.android.tbox.FormedP2pGroup
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.SelectingTBoxTransport
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxLinkResolver
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxProtocolMemory
import io.motohub.android.tbox.TBoxNetworkConnectors
import io.motohub.android.tbox.TBoxSessionHandle
import io.motohub.android.tbox.TBoxSessionRegistry

/**
 * Why the last AIDL connect failed, kept where the bridge can read it after [CoreTBoxConnector.connect]
 * has already answered its bare boolean.
 *
 * Process-wide rather than per-connector because the connector that failed may already have been
 * released by the time the caller asks - and because there is only ever one connect in flight
 * (CoreTBoxConnectors hands out one connector per SSID and the bridge blocks on it).
 */
internal object CoreConnectFailureRecord {
    @Volatile
    private var reason: String? = null

    @Volatile
    private var stage: Int = IpcBridgeContract.CONNECT_STAGE_UNKNOWN

    fun clear() {
        reason = null
        stage = IpcBridgeContract.CONNECT_STAGE_UNKNOWN
    }

    fun record(stage: Int, reason: String?) {
        this.stage = stage
        this.reason = reason?.takeIf { it.isNotBlank() }
    }

    fun reason(): String? = reason

    fun stage(): Int = stage
}

/** Establishes and tears down a T-Box session on behalf of an AIDL caller. */
class CoreTBoxConnector(private val context: Context) {

    // The process's one shared connector (see TBoxNetworkConnectors): an AIDL connect beside a
    // UI-established session used to put a second exclusive Wi-Fi request on the air.
    private val networkConnector = TBoxNetworkConnectors.shared(context)
    private val transport = SelectingTBoxTransport(context)
    private val capabilityStore = TBoxCapabilityStore(context)
    private var installed = false

    /**
     * Whether an AIDL retry for [ssid] can keep using this connector instead of being handed a
     * fresh one. Only true before this connector has ever installed a session: once one is live,
     * calling [connect] again would re-run EasyConn discovery underneath an already-streaming
     * session, which nothing downstream expects. A connector that never got that far - still
     * mid Wi-Fi join, or one whose join already failed - is exactly what a retry should keep
     * using, so the WifiNetworkSpecifier hunt it holds does not get torn down and restarted.
     */
    fun isReusableFor(ssid: String): Boolean = !installed && networkConnector.isHuntingFor(ssid)

    /**
     * @param formedGroup set when the caller has already formed the Wi-Fi Direct group and is
     *   handing it over with its addresses; this process then adopts it instead of attempting a
     *   join the framework refuses a backgrounded Core anyway.
     */
    suspend fun connect(
        profile: MotorcycleProfile,
        formedGroup: FormedP2pGroup? = null
    ): Boolean {
        // A session CORE started for itself outlives the activity on purpose - a projection has to
        // survive the screen going away - and a companion app asking to connect in that moment used
        // to build a SECOND TBoxNetworkConnector beside it. Two exclusive WifiNetworkSpecifier
        // requests for the same SSID do not queue, they fight: each grant drops the other's network.
        // Field log 2026-07-31 (OnePlus CPH2653, EASYCONN_5G-F3116E): the rider left an Android Auto
        // session running, started the Ride Dashboard from the companion app, and got networks
        // 202 through 207 granted and lost within a second each, one dashboard frame, a broken pipe,
        // then eleven rejoin attempts refused by Android in 2-10ms before it gave up 3.5 minutes
        // later. Refusing here costs that rider one clear sentence instead.
        CoreConnectFailureRecord.clear()
        val holder = TBoxSessionRegistry.current()
        val consumers = TBoxSessionRegistry.activeConsumers()
        if (holder != null && holder.networkConnector !== networkConnector && consumers.isNotEmpty()) {
            val refusal = "MOTO-HUB Core is already using this dash for $consumers. Stop that " +
                "session first, then connect again."
            ProjectionEventLog.error(
                "IPC_TBOX",
                "AIDL connect refused: MOTO-HUB Core is already using this dash for $consumers. " +
                    "Two connectors would compete for the same Wi-Fi association and drop each " +
                    "other's network. Stop that session first, then connect again."
            )
            CoreConnectFailureRecord.record(IpcBridgeContract.CONNECT_STAGE_REFUSED, refusal)
            return false
        }
        TBoxNetworkConnectors.acquire(context, AIDL_NETWORK_OWNER)
        val connected = TBoxLinkResolver.connect(context, networkConnector, profile, formedGroup)
        val link = connected.getOrElse {
            // The lease is kept on a network failure: the specifier request deliberately
            // outlives its timeout (v1.1.17) and the next AIDL retry joins that hunt.
            ProjectionEventLog.error("IPC_TBOX", "AIDL connect: T-Box network connection failed.", it)
            CoreConnectFailureRecord.record(
                IpcBridgeContract.CONNECT_STAGE_NETWORK,
                it.message ?: "The phone could not reach the motorcycle's network."
            )
            return false
        }
        ProjectionEventLog.record("IPC_TBOX", "AIDL connect: T-Box link established (${link.label}).")
        val requestedOverride = ProfileOverride.byKey(profile.profileOverrideKey)
        ProjectionEventLog.record(
            "PROFILE",
            "AIDL connect: resolving protocol profile: modelId=${profile.modelId ?: "none"}, " +
                "override=${requestedOverride.key}, connectionMode=${profile.connectionMode}."
        )
        // A dash whose family we already learned is routed straight there. Discovery can answer
        // this, but only by letting EasyConn fail first, which costs two 15s NSD windows and the
        // wake probes before anything else is tried - the difference a rider sees between pinning
        // the profile by hand and leaving it on Auto. Only ever a shortcut: a pinned override wins,
        // and only non-EasyConn families are ever remembered.
        val learnedProfile = if (requestedOverride == ProfileOverride.AUTO) {
            TBoxProtocolMemory(context).learnedFamily(profile.ssid)
                ?.let { family -> TBoxModelProfile.entries.firstOrNull { it.transportFamily == family } }
        } else {
            null
        }
        val resolvedProfile = learnedProfile ?: TBoxModelProfile.resolve(profile.modelId, null, requestedOverride)
        learnedProfile?.let {
            ProjectionEventLog.record(
                "PROFILE",
                "This motorcycle was already seen speaking ${it.transportFamily}; going straight " +
                    "to that transport instead of letting EasyConn discovery time out first."
            )
        }
        transport.configureProtocolProfile(resolvedProfile, profile)
        val discovered = transport.discover(link, profile.modelId)
        val host = discovered.getOrElse {
            // Named after the transport that actually ran. Saying "EasyConn" whatever the family
            // was is not cosmetic: it is the first line a reader meets in a failing log, and on a
            // ThinkerRide or Yunmo bike it sends them looking for a fault in a stack that never
            // executed. Two of us lost the opening minutes of case 2e3b10d2 to exactly that.
            ProjectionEventLog.error(
                "IPC_TBOX",
                "AIDL connect: ${resolvedProfile.transportFamily} discovery failed.",
                it
            )
            // A dash that never answers because the packets never left the phone is not a
            // discovery problem, and saying "the dash did not answer" sends the rider to the
            // bike. When the process binding was refused with a VPN demonstrably holding the
            // route to the dash, that is the failure - reported as a network one, because it is.
            val routingDiagnosis = networkConnector.vpnRoutingDiagnosis()
            if (routingDiagnosis != null) {
                ProjectionEventLog.record(
                    "IPC_TBOX",
                    "AIDL connect: discovery had no route to the dash - the process binding was " +
                        "refused earlier and a VPN holds that route."
                )
                CoreConnectFailureRecord.record(IpcBridgeContract.CONNECT_STAGE_NETWORK, routingDiagnosis)
            } else {
                CoreConnectFailureRecord.record(
                    IpcBridgeContract.CONNECT_STAGE_DISCOVERY,
                    it.message ?: "The motorcycle did not answer on its own network."
                )
            }
            transport.stop()
            link.disconnect()
            TBoxSessionRegistry.clear()
            TBoxNetworkConnectors.release(AIDL_NETWORK_OWNER)
            return false
        }
        // Record what discovery settled on, so the next ride skips the slow path. Read off the
        // switch itself and not off activeProtocolProfile, which now also carries a pin: what is
        // worth remembering is what the DASH answered unasked, never what the rider tried.
        transport.discoverySwitchedProfile?.let { discoveredProfile ->
            TBoxProtocolMemory(context).remember(profile.ssid, discoveredProfile.transportFamily)
        }
        capabilityStore.recordDiscovery(profile, host)
        TBoxSessionRegistry.install(
            TBoxSessionHandle(transport, host, networkConnector, profile, link)
        )
        installed = true
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
        TBoxSessionRegistry.clear()
        TBoxNetworkConnectors.release(AIDL_NETWORK_OWNER)
    }

    suspend fun disconnect() = disconnectActiveSession()

    companion object {
        /** The AIDL bridge's name in [TBoxNetworkConnectors]' interest ledger. */
        private const val AIDL_NETWORK_OWNER = "aidl-bridge"

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
            // No direct connector teardown: clear() releases the session's own lease in the
            // shared-connector ledger, and the bridge's lease goes with CoreTBoxConnectors.clear()
            // (its sole caller pairs the two). The network drops when the last of them is gone -
            // never out from under a lease the UI still holds.
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
