package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
            try {
                val link = withTimeout(CONNECT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        receiver = registerAndJoin(manager, channel, profile, continuation)
                        continuation.invokeOnCancellation {
                            receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
                        }
                    }
                }
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

    @SuppressLint("MissingPermission")
    private fun registerAndJoin(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        profile: MotorcycleProfile,
        continuation: CancellableContinuation<TBoxLink.WifiDirect>
    ): BroadcastReceiver {
        // Ensures the continuation is completed exactly once even if multiple connection-changed
        // broadcasts race (each spawns an async requestConnectionInfo callback).
        val settled = AtomicBoolean(false)

        fun settle(result: Result<TBoxLink.WifiDirect>) {
            if (settled.compareAndSet(false, true) && continuation.isActive) {
                continuation.resumeWith(result)
            }
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

        // Some devices require an active peer discovery before connect() will form a group.
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                log("Wi-Fi Direct peer discovery kick failed (${reasonName(reason)}); proceeding to connect anyway.")
            }
        })

        // A leftover/persistent group may already be formed before this receiver saw any broadcast.
        checkForFormedGroup(manager, channel, profile, ::settle)

        log("Joining Wi-Fi Direct group ${profile.ssid} as a legacy client.")
        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(profile.ssid)
                .setPassphrase(profile.password)
                .enablePersistentMode(false)
                .build()
        } catch (failure: RuntimeException) {
            // setNetworkName rejects non-"DIRECT-" names; build() rejects a bad passphrase length.
            settle(
                Result.failure(
                    IllegalStateException(
                        "Wi-Fi Direct join is not possible for ${profile.ssid}: ${failure.message}"
                    )
                )
            )
            return receiver
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Wi-Fi Direct connect() accepted; waiting for the group to form.")
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY) {
                    // A stale group is being torn down; the connection-changed broadcast still fires.
                    log("Wi-Fi Direct connect() busy; waiting for the pending group.")
                    return
                }
                settle(
                    Result.failure(
                        IllegalStateException("Wi-Fi Direct connect() failed: ${reasonName(reason)}.")
                    )
                )
            }
        })
        return receiver
    }

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
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val IP_POLL_TIMEOUT_MS = 10_000L
        private const val IP_POLL_INTERVAL_MS = 500L
        private const val GROUP_OWNER_IP = "192.168.49.1"

        /** Wi-Fi Direct group names always start with "DIRECT-" (Android convention). */
        fun isWifiDirectSsid(ssid: String): Boolean =
            ssid.trim().removeSurrounding("\"").startsWith("DIRECT-", ignoreCase = true)

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
