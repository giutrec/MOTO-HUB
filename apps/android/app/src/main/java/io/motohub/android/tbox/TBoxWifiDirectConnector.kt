package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Joins a CFMoto dash that runs as a **Wi-Fi Direct Group Owner** (SSID `DIRECT-...`, e.g.
 * CL-C450 / some "go" units) instead of a normal WPA2 access point. [TBoxNetworkConnector]'s
 * `WifiNetworkSpecifier` cannot associate to a P2P Group Owner as a proper client, so those
 * dashes need this path.
 *
 * Adapted from OpenCfMoto's `BikeWifiP2p`, with extra robustness: a peer-discovery kick before
 * connect (required on some devices), fast failure when P2P is off, an immediate check for a
 * pre-existing group, and a single-settle guard against duplicate connection broadcasts.
 *
 * Joins by credentials (`setNetworkName` + passphrase from the saved [MotorcycleProfile]) as a
 * legacy P2P client, then resolves:
 *  - the bike gateway (the Group Owner, always `192.168.49.1` by Android's P2P convention), and
 *  - the phone's own `192.168.49.x` address on the `p2p-*` interface.
 *
 * A P2P group produces no `ConnectivityManager.Network`; the caller binds its sockets to the
 * returned phone address instead (see [TBoxLink.WifiDirect]).
 */
class TBoxWifiDirectConnector(
    context: Context,
    private val log: (String) -> Unit = { ProjectionEventLog.record("WIFI_DIRECT", it) }
) {
    private val appContext = context.applicationContext

    /** True when the profile's SSID is a Wi-Fi Direct group name. */
    fun isWifiDirectProfile(profile: MotorcycleProfile): Boolean = isWifiDirectSsid(profile.ssid)

    suspend fun connect(profile: MotorcycleProfile): Result<TBoxLink.WifiDirect> =
        withContext(Dispatchers.IO) {
            val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return@withContext Result.failure(
                    IllegalStateException("This device has no Wi-Fi Direct (P2P) support.")
                )
            val channel = manager.initialize(appContext, Looper.getMainLooper(), null)
                ?: return@withContext Result.failure(
                    IllegalStateException("Wi-Fi Direct is unavailable (channel could not be initialized).")
                )
            var receiver: BroadcastReceiver? = null
            var handedOff = false
            // Settled exactly once: CompletableDeferred.complete() ignores every later call, so
            // racing connection-changed broadcasts (each spawning an async requestConnectionInfo)
            // cannot resume this twice.
            val outcome = CompletableDeferred<Result<TBoxLink.WifiDirect>>()
            try {
                val link = withTimeout(CONNECT_TIMEOUT_MS) {
                    receiver = registerReceiver(manager, channel, profile, outcome)
                    join(manager, channel, profile, outcome)
                    outcome.await()
                }.getOrThrow()
                handedOff = true
                Result.success(link)
            } catch (timeout: TimeoutCancellationException) {
                Result.failure(
                    IllegalStateException(
                        "No Wi-Fi Direct group formed within ${CONNECT_TIMEOUT_MS / 1000}s for ${profile.ssid}. " +
                            "Make sure the dash screen is on and, if the phone shows a Wi-Fi Direct invitation, accept it."
                    )
                )
            } catch (cancelled: CancellationException) {
                // A user cancel or scope teardown must stay a cancellation - turning it into a
                // Result.failure made the UI flash a spurious error after "Annulla".
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            } finally {
                receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
                // The group must outlive connect(): EasyConn discovery and the three reverse
                // sockets run over it. Removing it here made every successful P2P join look like
                // a dead dash a few milliseconds later. Failed/cancelled joins are still cleaned
                // up immediately; successful ones are released by TBoxSessionRegistry.clear(),
                // whose leaveGroup closure also closes the channel.
                if (!handedOff) removeGroup(manager, channel, closeChannelAfter = true)
            }
        }

    private fun registerReceiver(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>
    ): BroadcastReceiver {
        fun settle(result: Result<TBoxLink.WifiDirect>) {
            outcome.complete(result)
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled) {
                            settle(
                                Result.failure(
                                    IllegalStateException("Wi-Fi Direct is off; enable Wi-Fi and retry.")
                                )
                            )
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        checkForFormedGroup(manager, channel, profile, ::settle)
                    }
                }
            }
        }
        registerSystemReceiver(receiver, filter)

        // A leftover/persistent group may already be formed before this receiver saw any broadcast.
        checkForFormedGroup(manager, channel, profile, ::settle)
        return receiver
    }

    /**
     * Brings the P2P state machine into a state where `connect()` can succeed, then issues it.
     *
     * The order matters and mirrors what the OEM EasyConn app does, which is the counterpart the
     * dash was built for: discover the peer, stop discovery, clear a half-open invitation, and
     * only then connect. Firing `connect()` straight after `discoverPeers()` - which is what this
     * connector used to do - leaves a scan running and a stale invitation in place, and the
     * framework answers with a bare `ERROR` ("internal error") or forms no group at all.
     */
    private suspend fun join(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>
    ) {
        // A group that was already formed settles through the receiver; joining again would tear
        // down the link the caller is about to use.
        if (outcome.isCompleted) return
        val peer = discoverPeer(manager, channel, profile, outcome)
        if (outcome.isCompleted) return

        // Discovery and connect() contend for the same radio state machine; leaving the scan
        // running is a known way to get connect() rejected.
        awaitAction { listener -> manager.stopPeerDiscovery(channel, listener) }

        if (peer != null && peer.status == WifiP2pDevice.INVITED) {
            // A half-open invitation from an earlier attempt keeps failing every new connect()
            // until it is cancelled. EasyConn cancels and waits before retrying; so do we.
            log("Dash ${profile.ssid} still has a pending invitation; cancelling it first.")
            awaitAction { listener -> manager.cancelConnect(channel, listener) }
            delay(CANCEL_SETTLE_MS)
        }
        if (outcome.isCompleted) return

        connectWithRetry(manager, channel, profile, peer, outcome)
    }

    /**
     * Looks for the dash among the discovered P2P peers. A group named `DIRECT-xy-<name>` is
     * Android's own convention, so the dash's P2P device name is recoverable from the saved SSID
     * and no MAC address has to be stored in the profile. Returns null when the peer never shows
     * up, in which case the caller falls back to joining by credentials.
     */
    @SuppressLint("MissingPermission")
    private suspend fun discoverPeer(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>
    ): WifiP2pDevice? {
        val expectedName = peerNameFromGroupSsid(profile.ssid) ?: run {
            log("${profile.ssid} is not a DIRECT-xy-<name> group; joining by credentials.")
            return null
        }
        if (!awaitAction { listener -> manager.discoverPeers(channel, listener) }) {
            log("Wi-Fi Direct peer discovery could not start; joining by credentials instead.")
            return null
        }
        val deadline = System.nanoTime() + PEER_DISCOVERY_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline && !outcome.isCompleted) {
            val match = requestPeers(manager, channel).firstOrNull { peer ->
                peer.deviceName.equals(expectedName, ignoreCase = true)
            }
            if (match != null) {
                log(
                    "Found the dash as Wi-Fi Direct peer '${match.deviceName}' " +
                        "(${match.deviceAddress}), status ${statusName(match.status)}."
                )
                return match
            }
            delay(PEER_POLL_INTERVAL_MS)
        }
        log("The dash did not appear in Wi-Fi Direct discovery; joining ${profile.ssid} by credentials.")
        return null
    }

    /**
     * Issues `connect()`, retrying a transient `ERROR` once. The framework returns ERROR for
     * several recoverable states (a group still tearing down, a supplicant busy with the scan
     * that was just stopped); the OEM app effectively retries through its scan/connect cycle,
     * while this connector used to give the rider a hard failure on the first rejection.
     */
    private suspend fun connectWithRetry(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        peer: WifiP2pDevice?,
        outcome: CompletableDeferred<Result<TBoxLink.WifiDirect>>
    ) {
        val config = buildConfig(profile, peer) ?: run {
            // setNetworkName rejects non-"DIRECT-" names; build() rejects a bad passphrase length.
            outcome.complete(
                Result.failure(
                    IllegalStateException("Wi-Fi Direct join is not possible for ${profile.ssid}.")
                )
            )
            return
        }
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (outcome.isCompleted) return
            log(
                if (peer != null) "Joining the dash at ${peer.deviceAddress} (attempt ${attempt + 1})."
                else "Joining Wi-Fi Direct group ${profile.ssid} as a legacy client (attempt ${attempt + 1})."
            )
            when (val reason = issueConnect(manager, channel, config)) {
                null -> {
                    log("Wi-Fi Direct connect() accepted; waiting for the group to form.")
                    return
                }
                WifiP2pManager.BUSY -> {
                    // A stale group is being torn down; the connection-changed broadcast still fires.
                    log("Wi-Fi Direct connect() busy; waiting for the pending group.")
                    return
                }
                else -> {
                    if (attempt == CONNECT_ATTEMPTS - 1) {
                        outcome.complete(
                            Result.failure(
                                IllegalStateException("Wi-Fi Direct connect() failed: ${reasonName(reason)}.")
                            )
                        )
                        return
                    }
                    log("Wi-Fi Direct connect() failed (${reasonName(reason)}); retrying.")
                    delay(CONNECT_RETRY_DELAY_MS)
                }
            }
        }
    }

    /**
     * Prefers the peer-address form the dash's own companion app uses; falls back to the
     * credential join when discovery never surfaced the peer. Returns null once the failure has
     * been reported by the caller.
     */
    private fun buildConfig(profile: MotorcycleProfile, peer: WifiP2pDevice?): WifiP2pConfig? {
        if (peer != null) {
            return WifiP2pConfig().apply {
                deviceAddress = peer.deviceAddress
                // The dash must own the group: at 0 this phone asks to be the least likely
                // Group Owner, which is exactly the role split checkForFormedGroup enforces.
                groupOwnerIntent = 0
                wps.setup = when {
                    peer.wpsPbcSupported() -> WpsInfo.PBC
                    peer.wpsKeypadSupported() -> WpsInfo.KEYPAD
                    peer.wpsDisplaySupported() -> WpsInfo.DISPLAY
                    else -> wps.setup
                }
            }
        }
        return runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(profile.ssid)
                .setPassphrase(profile.password)
                .enablePersistentMode(false)
                .build()
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestPeers(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): Collection<WifiP2pDevice> = withTimeoutOrNull(FRAMEWORK_CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            runCatching {
                manager.requestPeers(channel) { peers ->
                    if (continuation.isActive) continuation.resume(peers.deviceList)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(emptyList()) }
        }
    } ?: emptyList()

    // Same permission gate as the rest of this connector: NEARBY_WIFI_DEVICES/ACCESS_FINE_LOCATION
    // are requested by the connection UI before any T-Box join starts.
    /** Returns null when the request was accepted, otherwise the framework's rejection reason. */
    @SuppressLint("MissingPermission")
    private suspend fun issueConnect(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig
    ): Int? {
        val answer = withTimeoutOrNull(CONNECT_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(CONNECT_ACCEPTED)
                    }
                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) continuation.resume(reason)
                    }
                }
                runCatching { manager.connect(channel, config, listener) }
                    .onFailure { if (continuation.isActive) continuation.resume(WifiP2pManager.ERROR) }
            }
        }
        // A silent framework is treated as a rejection, not as an acceptance: the caller then
        // retries rather than sitting out the whole join budget waiting for a group nobody asked for.
        return when (answer) {
            null -> WifiP2pManager.ERROR
            CONNECT_ACCEPTED -> null
            else -> answer
        }
    }

    /**
     * Runs a fire-and-forget framework call and waits for its ActionListener. Bounded, because a
     * few devices never answer at all and the rider should not sit through the whole join budget
     * waiting on a preparation step.
     */
    private suspend fun awaitAction(
        action: (WifiP2pManager.ActionListener) -> Unit
    ): Boolean = withTimeoutOrNull(FRAMEWORK_CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (continuation.isActive) continuation.resume(true) }
                override fun onFailure(reason: Int) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            runCatching { action(listener) }
                .onFailure { if (continuation.isActive) continuation.resume(false) }
        }
    } ?: false

    // NEARBY_WIFI_DEVICES/ACCESS_FINE_LOCATION are requested by the connection UI before any
    // T-Box join starts (same gate as the WifiNetworkSpecifier path).
    @SuppressLint("MissingPermission")
    private fun checkForFormedGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        settle: (Result<TBoxLink.WifiDirect>) -> Unit
    ) {
        manager.requestConnectionInfo(channel) { info ->
            if (info == null || !info.groupFormed) return@requestConnectionInfo
            val gateway = info.groupOwnerAddress as? Inet4Address
            if (gateway == null) {
                settle(Result.failure(IllegalStateException("Wi-Fi Direct group formed without an IPv4 group owner.")))
                return@requestConnectionInfo
            }
            if (info.isGroupOwner) {
                // The dash must be the GO: with the roles inverted, 192.168.49.1 is the phone
                // itself and every probe would just talk to the phone. Fail loudly; the caller's
                // cleanup removes the inverted group so the next attempt negotiates fresh.
                settle(
                    Result.failure(
                        IllegalStateException(
                            "The phone became the Wi-Fi Direct Group Owner instead of joining " +
                                "${profile.ssid}. The group was released; retry the connection " +
                                "with the dash screen on."
                        )
                    )
                )
                return@requestConnectionInfo
            }
            manager.requestGroupInfo(channel) { group ->
                // A formed group is not necessarily OUR group: a leftover/persistent group
                // toward another DIRECT- device (a different bike, a cast dongle) also reports
                // groupFormed. Joining it would make discovery fail with a misleading "dash did
                // not answer". Remove the stale group and keep waiting for the requested one.
                val groupName = group?.networkName
                if (!groupNameMatchesProfile(groupName, profile.ssid)) {
                    log(
                        "Ignoring formed Wi-Fi Direct group '$groupName': it is not " +
                            "${profile.ssid}. Removing the stale group and waiting for the join."
                    )
                    removeGroup(manager, channel, closeChannelAfter = false)
                    return@requestGroupInfo
                }
                resolveLocalAddress(
                    iface = group?.`interface`,
                    gateway = gateway,
                    leaveGroup = { removeGroup(manager, channel, closeChannelAfter = true) },
                    settle = settle
                )
            }
        }
    }

    private fun resolveLocalAddress(
        iface: String?,
        gateway: Inet4Address,
        leaveGroup: () -> Unit,
        settle: (Result<TBoxLink.WifiDirect>) -> Unit
    ) {
        // DHCP on the p2p link can lag the "group formed" event; poll off the main thread.
        Thread({
            val bindIp = pollLocalP2pIpv4(iface)
            if (bindIp == null) {
                settle(
                    Result.failure(
                        IllegalStateException(
                            "Wi-Fi Direct group formed but no usable 192.168.49.x address appeared on $iface."
                        )
                    )
                )
            } else {
                log("Wi-Fi Direct connected: phone=${bindIp.hostAddress}, dash(GO)=${gateway.hostAddress}.")
                settle(
                    Result.success(
                        TBoxLink.WifiDirect(
                            bindIp = bindIp,
                            gatewayIp = gateway,
                            leaveGroup = leaveGroup,
                            appContext = appContext
                        )
                    )
                )
            }
        }, "tbox-p2p-ip").apply { isDaemon = true }.start()
    }

    private fun pollLocalP2pIpv4(iface: String?): Inet4Address? {
        val deadline = System.nanoTime() + IP_POLL_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            localP2pIpv4(iface)?.let { return it }
            try {
                Thread.sleep(IP_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return localP2pIpv4(iface)
    }

    private fun localP2pIpv4(iface: String?): Inet4Address? = runCatching {
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            val nameMatches = iface == null || nic.name == iface || nic.name.startsWith("p2p")
            for (address in nic.inetAddresses) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                val host = address.hostAddress ?: continue
                // Never accept the GO's own address as the phone's source: that only happens
                // when the phone ended up as Group Owner, which the join already rejects.
                if (host == GROUP_OWNER_IP) continue
                if (nic.name == iface) return address
                if (nameMatches && host.startsWith("192.168.49.")) return address
            }
        }
        null
    }.getOrNull()

    private fun registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "reason $reason"
    }

    private fun statusName(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "connected"
        WifiP2pDevice.INVITED -> "invited"
        WifiP2pDevice.FAILED -> "failed"
        WifiP2pDevice.AVAILABLE -> "available"
        WifiP2pDevice.UNAVAILABLE -> "unavailable"
        else -> "status $status"
    }

    private fun removeGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        closeChannelAfter: Boolean
    ) {
        // The channel must stay open until removeGroup has completed, so it is closed from the
        // callbacks - closing it earlier silently cancels the pending framework call.
        fun finish() {
            if (closeChannelAfter) runCatching { channel.close() }
        }
        runCatching {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("Wi-Fi Direct group released.")
                    finish()
                }
                override fun onFailure(reason: Int) = finish()
            })
        }.onFailure { finish() }
    }

    companion object {
        /**
         * Whole-join budget: peer discovery, the preparation calls, and the group forming. The
         * OEM app allows itself the same 35s, and the 25s this used to be expired while the
         * group was still negotiating on the riders who reported the failure.
         */
        private const val CONNECT_TIMEOUT_MS = 35_000L
        private const val PEER_DISCOVERY_TIMEOUT_MS = 6_000L
        private const val PEER_POLL_INTERVAL_MS = 600L
        private const val FRAMEWORK_CALL_TIMEOUT_MS = 3_000L
        private const val CONNECT_CALL_TIMEOUT_MS = 6_000L
        /** Distinguishes "connect() was accepted" from "the framework never answered". */
        private const val CONNECT_ACCEPTED = Int.MIN_VALUE
        private const val CANCEL_SETTLE_MS = 500L
        private const val CONNECT_ATTEMPTS = 2
        private const val CONNECT_RETRY_DELAY_MS = 1_200L
        private const val IP_POLL_TIMEOUT_MS = 10_000L
        private const val IP_POLL_INTERVAL_MS = 500L
        private const val GROUP_OWNER_IP = "192.168.49.1"
        private const val DIRECT_PREFIX = "DIRECT-"

        /** Wi-Fi Direct group names always start with "DIRECT-" (Android convention). */
        fun isWifiDirectSsid(ssid: String): Boolean =
            ssid.trim().removeSurrounding("\"").startsWith(DIRECT_PREFIX, ignoreCase = true)

        /**
         * Recovers the dash's P2P device name from a group SSID. Android names a group
         * `DIRECT-<two random chars>-<device name>`, so `DIRECT-go-CFMOTO-EF7198` belongs to the
         * peer called `CFMOTO-EF7198`. Returns null when the SSID does not follow the convention,
         * which simply means the join falls back to credentials.
         */
        internal fun peerNameFromGroupSsid(ssid: String): String? {
            val normalized = ssid.trim().removeSurrounding("\"")
            if (!normalized.startsWith(DIRECT_PREFIX, ignoreCase = true)) return null
            val afterPrefix = normalized.substring(DIRECT_PREFIX.length)
            val separator = afterPrefix.indexOf('-')
            if (separator <= 0 || separator == afterPrefix.lastIndex) return null
            return afterPrefix.substring(separator + 1)
        }

        /**
         * Whether a formed group's network name is the profile's dash. A null/blank name cannot
         * be verified (some frameworks withhold it from legacy clients) and is accepted rather
         * than breaking joins that used to work.
         */
        internal fun groupNameMatchesProfile(groupName: String?, profileSsid: String): Boolean {
            val normalizedGroup = groupName?.trim()?.removeSurrounding("\"").orEmpty()
            if (normalizedGroup.isEmpty()) return true
            val normalizedProfile = profileSsid.trim().removeSurrounding("\"")
            return normalizedGroup.equals(normalizedProfile, ignoreCase = true)
        }
    }
}
