package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

sealed interface TBoxNetworkEvent {
    data class Lost(val network: Network) : TBoxNetworkEvent
    data class Reacquired(val network: Network) : TBoxNetworkEvent
}

/** What the T-Box Wi-Fi rejoin ladder does next. */
internal sealed interface TBoxRejoinStep {
    data class WaitThenRetry(val delayMillis: Long) : TBoxRejoinStep
    data object GiveUp : TBoxRejoinStep
}

/**
 * Decides the ladder's next move: a quick first retry for the ordinary blip, then a growing and
 * capped wait, and eventually surrender.
 *
 * The budget is what stops a bike that was simply switched off from leaving an exclusive
 * WifiNetworkSpecifier request open for as long as the app lives.
 */
internal fun nextTBoxRejoinStep(
    attempt: Int,
    elapsedMillis: Long,
    budgetMillis: Long,
    firstDelayMillis: Long,
    baseDelayMillis: Long,
    maxDelayMillis: Long
): TBoxRejoinStep {
    if (elapsedMillis >= budgetMillis) return TBoxRejoinStep.GiveUp
    val delay = if (attempt <= 1) {
        firstDelayMillis
    } else {
        (baseDelayMillis * (attempt - 1)).coerceAtMost(maxDelayMillis)
    }
    return TBoxRejoinStep.WaitThenRetry(delay)
}

/** Requests the T-Box AP explicitly and binds the process for its reverse TCP servers. */
class TBoxNetworkConnector(context: Context) {
    private val appContext = context.applicationContext
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    @Volatile
    private var hiddenSsidFallbackLogged = false
    private val mutableEvents = MutableSharedFlow<TBoxNetworkEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TBoxNetworkEvent> = mutableEvents.asSharedFlow()

    /**
     * Guards the whole network-request lifecycle: clearing the previous registration, storing the
     * new one and handing it to ConnectivityManager have to happen as one step.
     *
     * They did not, and two callers - a connect() from the UI or the AIDL bridge, and the rejoin
     * ladder below - could interleave their clear/store pairs. Both callbacks ended up registered
     * with ConnectivityManager while the connector tracked only the last one. The untracked one
     * was unreachable forever: it kept receiving onLost, kept calling [scheduleRejoin], and no
     * disconnect() could ever release it. A rider's diagnostics showed two rejoin ladders running
     * in lockstep for nineteen minutes, each new exclusive WifiNetworkSpecifier request tearing
     * down the network the other had just been granted - and still fighting the rider's own manual
     * reconnect at the end of it.
     */
    private val requestLock = Any()

    /**
     * Every callback currently registered with ConnectivityManager. [callback] is the one the
     * current attempt owns; this set is what guarantees none of the others can be orphaned.
     */
    private val registeredCallbacks = mutableSetOf<ConnectivityManager.NetworkCallback>()

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var activeNetwork: Network? = null
    @Volatile
    private var processBoundNetwork: Network? = null
    @Volatile
    private var activeProfile: MotorcycleProfile? = null
    @Volatile
    private var connectedOnce = false
    /**
     * True between [releaseProcessBinding] and [rebindProcessToTBox]: the route was released on
     * purpose (a local projection start needs the internet route for Google's servers) and the
     * persistent callback must not re-bind it on ordinary DHCP/IPv6 link updates.
     */
    @Volatile
    private var processBindingSuspended = false
    @Volatile
    private var rejoinJob: Job? = null

    /**
     * One rejoin ladder at a time. `rejoinJob?.isActive` was a check-then-act across threads:
     * two onLost callbacks 6ms apart both passed it and started their own ladder.
     */
    private val rejoinActive = AtomicBoolean(false)
    @Volatile
    private var simulatorMonitorJob: Job? = null

    /**
     * SSID of the specifier request currently registered with ConnectivityManager, whether or
     * not a network has been granted yet. What lets a retry for the same bike join the hunt
     * already in progress instead of restarting it — see [connect].
     */
    @Volatile
    private var pendingRequestSsid: String? = null
    /** When the live specifier request was submitted, so onUnavailable can say how fast it came. */
    @Volatile
    private var specifierSubmittedAt = 0L

    /** Terminal failure produced by the registered callback, observed by [awaitRequestedNetwork]. */
    @Volatile
    private var pendingFailure: Throwable? = null

    /**
     * Whether Android ever granted a network for the request currently registered - set by
     * `onAvailable`, which fires on association, well before any IP address exists.
     *
     * Separates the two ways a join can run out of time: the phone never associated to the AP at
     * all, or it associated and the dash never handed out a usable IPv4. They have different
     * causes and different remedies, and used to share one message that named only the second.
     */
    @Volatile
    private var networkGranted = false

    @Volatile
    private var pendingGiveUpJob: Job? = null

    suspend fun connect(profile: MotorcycleProfile): Result<Network> {
        if (TBoxModelProfile.fromModelId(profile.modelId) == TBoxModelProfile.MOTO_HUB_SIMULATOR) {
            disconnect()
            activeProfile = profile
            connectedOnce = false
            processBindingSuspended = false
            return try {
                ProjectionEventLog.record(
                    "NETWORK",
                    "Simulator profile detected for SSID ${profile.ssid}; reusing the phone's existing Wi-Fi " +
                        "instead of requesting a WifiNetworkSpecifier."
                )
                val network = withTimeout(CONNECTION_TIMEOUT_MS) { findExistingWifi(profile.ssid) }
                connectedOnce = true
                startSimulatorMonitor(profile)
                Result.success(network)
            } catch (_: TimeoutCancellationException) {
                ProjectionEventLog.error("NETWORK", "Wi-Fi setup timed out after ${CONNECTION_TIMEOUT_MS}ms.")
                disconnect()
                Result.failure(
                    IllegalStateException(
                        "The simulator requires the phone and Mac to be connected to the same Wi-Fi network " +
                            "with a usable IPv4 address."
                    )
                )
            } catch (cancelled: CancellationException) {
                // A real cancellation (user cancel, scope teardown) must propagate, not become a Result.
                throw cancelled
            } catch (failure: Throwable) {
                ProjectionEventLog.error("NETWORK", "T-Box AP request failed.", failure)
                // activeVpnLabel() omitted here: merely having a VpnService-based app present isn't evidence this failure caused it.
                val vpnMessage = TBoxVpnDiagnostics.userFacingMessage(failure, activeVpnLabel = null)
                Result.failure(vpnMessage?.let { IllegalStateException(it, failure) } ?: failure)
            }
        }

        // Session watchdogs retry through here every ~35s while recovering a dropped ride, and
        // with the screen off Android scans for Wi-Fi so rarely that a 30s window may not contain
        // a single scan. Tearing the exclusive request down and re-submitting on every attempt
        // kept resetting that hunt right before it could succeed (road test 2026-07-29: four
        // consecutive "Wi-Fi setup timed out" while recovering the CFDL16 mid-ride). Reuse what
        // is already there instead: first the granted network, then the still-pending request.
        if (activeProfile?.ssid == profile.ssid) {
            activeNetwork?.let { existing ->
                val rebindFailure = runCatching {
                    if (!processBindingSuspended && processBoundNetwork == null) {
                        check(connectivityManager.bindProcessToNetwork(existing)) {
                            "Android cannot restore the binding to the T-Box network."
                        }
                        processBoundNetwork = existing
                    }
                }.exceptionOrNull()
                if (rebindFailure == null) {
                    ProjectionEventLog.record(
                        "NETWORK",
                        "Reusing the active T-Box network $existing for ${profile.ssid}."
                    )
                    return Result.success(existing)
                }
                ProjectionEventLog.warning(
                    "NETWORK",
                    "Active T-Box network could not be re-bound; requesting a fresh one.",
                    rebindFailure
                )
            }
            if (pendingRequestSsid == profile.ssid) {
                ProjectionEventLog.record(
                    "NETWORK",
                    "Joining the pending Wi-Fi request for ${profile.ssid} instead of re-submitting it."
                )
                return awaitRequestedNetwork(profile)
            }
        }

        disconnect()
        activeProfile = profile
        connectedOnce = false
        processBindingSuspended = false
        ProjectionEventLog.record(
            "NETWORK",
            "Requesting Android Wi-Fi network for SSID ${profile.ssid}; passwordPresent=${profile.password.isNotEmpty()}."
        )
        submitSpecifierRequest(profile)
        return awaitRequestedNetwork(profile)
    }

    fun disconnect() {
        rejoinJob?.cancel()
        rejoinJob = null
        rejoinActive.set(false)
        simulatorMonitorJob?.cancel()
        simulatorMonitorJob = null
        activeProfile = null
        connectedOnce = false
        clearCurrentNetworkRequest()
    }

    private fun clearCurrentNetworkRequest() {
        synchronized(requestLock) { clearCurrentNetworkRequestLocked() }
    }

    /** Releases the process binding and *every* registered callback. Call under [requestLock]. */
    private fun clearCurrentNetworkRequestLocked() {
        ProjectionEventLog.debug(
            "NETWORK",
            "Disconnect requested; callbacks=${registeredCallbacks.size}, " +
                "activeNetwork=$activeNetwork, processBound=$processBoundNetwork."
        )
        callback = null
        pendingRequestSsid = null
        pendingFailure = null
        networkGranted = false
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = null
        if (processBoundNetwork != null) {
            connectivityManager.bindProcessToNetwork(null)
            processBoundNetwork = null
        }
        activeNetwork = null
        val released = registeredCallbacks.toList()
        registeredCallbacks.clear()
        released.forEach { unregister(it) }
    }

    /** Releases one attempt's registration without touching a newer attempt's state. */
    private fun releaseCallback(target: ConnectivityManager.NetworkCallback) {
        synchronized(requestLock) {
            if (callback === target) {
                callback = null
                // This registration was the pending request; without it there is nothing left
                // for a retry to join.
                pendingRequestSsid = null
            }
            if (registeredCallbacks.remove(target)) unregister(target)
        }
    }

    private fun unregister(target: ConnectivityManager.NetworkCallback) {
        runCatching { connectivityManager.unregisterNetworkCallback(target) }
            .onFailure {
                ProjectionEventLog.warning("NETWORK", "Network callback unregister failed.", it)
            }
    }

    /** Current Wi-Fi network confirmed by the SSID-specific request callback, if still active. */
    fun currentNetwork(): Network? = activeNetwork

    /** Waits for the persistent network request to reacquire the T-Box AP. */
    suspend fun awaitNetworkAvailable(timeoutMillis: Long): Network? =
        withTimeoutOrNull(timeoutMillis) {
            var network: Network? = currentNetwork()
            while (network == null) {
                delay(NETWORK_POLL_MS)
                network = currentNetwork()
            }
            network
        }

    /** Keeps the requested T-Box network alive but restores Android's normal process route. */
    @Synchronized
    fun releaseProcessBinding() {
        if (processBoundNetwork == null) return
        val released = connectivityManager.bindProcessToNetwork(null)
        processBoundNetwork = null
        processBindingSuspended = true
        ProjectionEventLog.record("NETWORK", "Process binding released; result=$released. T-Box request remains active.")
    }

    /** Rebinds reverse EasyConn sockets to the still-requested T-Box network. */
    @Synchronized
    fun rebindProcessToTBox(): Result<Network> = runCatching {
        processBindingSuspended = false
        val network = checkNotNull(activeNetwork) { "The T-Box network is no longer available." }
        check(connectivityManager.bindProcessToNetwork(network)) {
            "Android cannot restore the binding to the T-Box network."
        }
        processBoundNetwork = network
        ProjectionEventLog.record("NETWORK", "Process rebound to T-Box network=$network.")
        network
    }.onFailure { ProjectionEventLog.error("NETWORK", "Unable to restore T-Box process binding.", it) }

    /**
     * Restores the process route after a local projection has released it. Android can briefly
     * clear the callback's network while the T-Box AP is still being reacquired, so wait for the
     * persistent request instead of failing immediately on a transient null network.
     */
    suspend fun rebindProcessToTBoxWhenAvailable(timeoutMillis: Long): Result<Network> {
        if (awaitNetworkAvailable(timeoutMillis) == null) {
            val failure = IllegalStateException(
                "The T-Box network did not become available within ${timeoutMillis}ms."
            )
            ProjectionEventLog.error(
                "NETWORK",
                "Unable to restore T-Box process binding: network wait timed out.",
                failure
            )
            return Result.failure(failure)
        }
        return rebindProcessToTBox()
    }

    /**
     * Records what the radio can actually see, immediately before the request is submitted.
     *
     * This is the datum a rider log was missing. When Android never grants the network there is no
     * `onAvailable` and no link properties either, so the log says only that nothing happened - and
     * "the dash is not broadcasting", "the dash is on a channel this phone will not join" and "the
     * saved password is wrong" all look identical. A VOGE dash (SSID `VOGE-5G-58e4`, 2026-07-30)
     * cost a full log analysis and a round trip to the rider to get no further than that.
     *
     * The band matters on its own: an SSID advertising 5G is a hint, not evidence, and a dash whose
     * only AP sits on a 5GHz channel the phone's regulatory domain forbids can never be joined. The
     * sibling scan exists for the same reason - several of these dashboards broadcast a 2.4GHz twin
     * of the same network, and if one is in range it is almost certainly the one to pair with.
     *
     * BSSIDs are deliberately not logged: they are stable hardware identifiers and these logs get
     * pasted into public threads.
     */
    @SuppressLint("MissingPermission")
    private fun logVisibleApSnapshot(profile: MotorcycleProfile) {
        val target = profile.ssid.trim().removeSurrounding("\"")
        val results = runCatching { wifiManager.scanResults }.getOrNull()
        if (results == null) {
            ProjectionEventLog.debug(
                "NETWORK",
                "Wi-Fi scan results are unavailable, so it cannot be said whether $target is in range."
            )
            return
        }
        fun ScanResult.ssidText(): String =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wifiSsid?.toString() else null)
                ?.removeSurrounding("\"")
                ?: @Suppress("DEPRECATION") SSID.orEmpty().removeSurrounding("\"")

        val match = results.firstOrNull { it.ssidText().equals(target, ignoreCase = true) }
        if (match == null) {
            ProjectionEventLog.warning(
                "NETWORK",
                "$target is NOT in the phone's latest Wi-Fi scan (${results.size} networks seen). " +
                    "Either the dash is not broadcasting it right now, or the phone cannot see " +
                    "that channel."
            )
        } else {
            // The security line is the one that can convict us rather than the dash. The specifier
            // below only ever offers setWpa2Passphrase, so an AP that requires WPA3/SAE can never
            // be matched no matter how correct the password is, and the failure is indistinguishable
            // from a dash that is not broadcasting. Logging what the AP actually advertises is what
            // separates those two, and nothing in a rider log has been able to so far.
            ProjectionEventLog.record(
                "NETWORK",
                "$target is in range: ${bandName(match.frequency)} (${match.frequency}MHz), " +
                    "rssi=${match.level}dBm, security=${securityName(match.capabilities)}."
            )
        }
        // A twin on the other band shares the dash's serial-looking last token.
        val tail = target.substringAfterLast('-', "").takeIf { it.length >= 3 }
        if (tail != null) {
            val siblings = results
                .map { it.ssidText() to it.frequency }
                .filter { (ssid, _) ->
                    ssid.isNotEmpty() &&
                        !ssid.equals(target, ignoreCase = true) &&
                        ssid.endsWith(tail, ignoreCase = true)
                }
                .distinctBy { it.first }
                .take(SIBLING_AP_LOG_LIMIT)
            if (siblings.isNotEmpty()) {
                ProjectionEventLog.record(
                    "NETWORK",
                    "The same dash also broadcasts: " +
                        siblings.joinToString { (ssid, frequency) -> "$ssid on ${bandName(frequency)}" } +
                        ". If $target will not join, one of these may."
                )
            }
        }
    }

    /**
     * Registers the exclusive specifier request and returns immediately; the callback drives the
     * shared connection state, and [awaitRequestedNetwork] observes the outcome. Deliberately not
     * a suspend-until-connected call: the registration must be able to outlive any single
     * caller's patience, because Android keeps matching a live request against every later Wi-Fi
     * scan — that background hunt is exactly what a screen-off recovery needs.
     */
    private fun submitSpecifierRequest(profile: MotorcycleProfile) {
        pendingFailure = null
        networkGranted = false
        logVisibleApSnapshot(profile)
        specifierSubmittedAt = SystemClock.elapsedRealtime()
        lateinit var networkCallback: ConnectivityManager.NetworkCallback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Association only - there is no address yet, so this is not success. It is the
                // one signal that separates "never joined the AP" from "joined it and got no IP",
                // and its absence across a whole rider log is itself the diagnosis.
                networkGranted = true
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Android granted network=$network for ${profile.ssid}; awaiting a usable IPv4 address."
                )
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val addresses = linkProperties.linkAddresses.mapNotNull { it.address.hostAddress }
                val gateways = linkProperties.routes.mapNotNull { it.gateway?.hostAddress }.distinct()
                // Dev-only raw logcat line (leaks the phone's Wi-Fi IPs) - the real,
                // production diagnostic record is ProjectionEventLog.debug() below, gated
                // by the runtime "Enable logging" setting regardless of build type.
                if (io.motohub.android.BuildConfig.DEBUG) Log.d(TAG, "Wi-Fi addresses=$addresses")
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Link properties changed: network=$network, interface=${linkProperties.interfaceName}, " +
                        "addresses=$addresses, gateways=$gateways."
                )
                val isTBoxNetwork = linkProperties.linkAddresses
                    .any { isUsableTBoxIpv4Address(it.address) }
                if (isTBoxNetwork) {
                    if (processBindingSuspended) {
                        // The binding was released on purpose while a local projection
                        // starts. Re-binding here on a routine DHCP/IPv6 link update would
                        // cut Google Android Auto off the internet mid-handshake; the
                        // projection flow rebinds explicitly when it is ready.
                        markConnected(network)
                        ProjectionEventLog.debug(
                            "NETWORK",
                            "T-Box link update accepted without re-binding: the process " +
                                "binding is deliberately released."
                        )
                        return
                    }
                    val bindFailure = runCatching {
                        check(connectivityManager.bindProcessToNetwork(network)) {
                            "Android cannot bind MOTO-HUB to the T-Box network."
                        }
                    }.exceptionOrNull()
                    if (bindFailure != null) {
                        val activeVpn = activeVpnLabel()
                        val message = TBoxVpnDiagnostics.userFacingMessage(bindFailure, activeVpn)
                            ?: bindFailure.message.orEmpty()
                        Log.e(TAG, "T-Box process binding rejected; activeVpn=$activeVpn", bindFailure)
                        ProjectionEventLog.error(
                            "NETWORK",
                            "Process binding rejected for network=$network; activeVpn=${activeVpn ?: "none"}; " +
                                "reason=$message."
                        )
                        pendingFailure = IllegalStateException(message, bindFailure)
                        releaseCallback(networkCallback)
                        return
                    }
                    processBoundNetwork = network
                    markConnected(network)
                    Log.i(TAG, "T-Box Wi-Fi is active: ${profile.ssid}, addresses=$addresses")
                    ProjectionEventLog.record(
                        "NETWORK",
                        "T-Box Wi-Fi validated and process-bound: ssid=${profile.ssid}, network=$network, addresses=$addresses."
                    )
                    if (MotoHubSettings.verboseTBoxLogging(appContext)) {
                        runCatching { wifiManager.connectionInfo }.getOrNull()?.let { info ->
                            ProjectionEventLog.debug(
                                "NETWORK",
                                "Wi-Fi link (verbose): frequency=${info.frequency}MHz, " +
                                    "rssi=${info.rssi}dBm, linkSpeed=${info.linkSpeed}Mbps."
                            )
                        }
                    }
                } else if (network == activeNetwork) {
                    // OnePlus can briefly publish incomplete LinkProperties while the AP stays
                    // associated. Only onLost is a real disconnect signal for the active network.
                    Log.w(TAG, "T-Box network address update is temporarily incomplete")
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "Active T-Box network temporarily has no usable IPv4 address; " +
                            "waiting for onLost before disconnecting."
                    )
                }
            }

            override fun onLost(network: Network) {
                if (network != activeNetwork) return
                ProjectionEventLog.warning("NETWORK", "Android onLost received for active T-Box network=$network.")
                if (processBoundNetwork == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    processBoundNetwork = null
                }
                activeNetwork = null
                mutableEvents.tryEmit(TBoxNetworkEvent.Lost(network))
                scheduleRejoin()
            }

            override fun onUnavailable() {
                // The elapsed time is the diagnosis, not decoration. Android takes seconds to scan
                // for a network it is genuinely hunting; a verdict inside a few tens of
                // milliseconds means the request was refused outright rather than attempted, and
                // reading that off two timestamps by hand is how it gets missed.
                val elapsed = specifierSubmittedAt
                    .takeIf { it > 0L }
                    ?.let { "${SystemClock.elapsedRealtime() - it}ms after the request" }
                    ?: "with no recorded request time"
                ProjectionEventLog.error(
                    "NETWORK",
                    "Android reported the requested T-Box Wi-Fi as unavailable $elapsed; " +
                        "granted=$networkGranted."
                )
                pendingFailure = IllegalStateException(
                    if (networkGranted) {
                        "Android dropped the ${profile.ssid} network before it became usable."
                    } else {
                        "Android gave up connecting to ${profile.ssid}: either the dash was not " +
                            "broadcasting it, the saved password no longer matches, or the " +
                            "connection dialog was dismissed. Rescan the dash QR code and retry."
                    }
                )
                releaseCallback(networkCallback)
            }
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(profile.ssid)
            .apply {
                if (profile.password.isNotBlank()) setWpa2Passphrase(profile.password)
            }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        ProjectionEventLog.debug(
            "NETWORK",
            "Submitting WifiNetworkSpecifier request for ${profile.ssid} without INTERNET capability."
        )

        // Drop the previous registration, take ownership and register, with no window in
        // between for a concurrent caller to slip its own pair through.
        val requestFailure = synchronized(requestLock) {
            clearCurrentNetworkRequestLocked()
            callback = networkCallback
            registeredCallbacks += networkCallback
            pendingRequestSsid = profile.ssid
            runCatching {
                connectivityManager.requestNetwork(request, networkCallback)
            }.exceptionOrNull()
        }
        if (requestFailure != null) {
            ProjectionEventLog.error(
                "NETWORK",
                "ConnectivityManager.requestNetwork threw an exception.",
                requestFailure
            )
            pendingFailure = requestFailure
            releaseCallback(networkCallback)
            return
        }
        schedulePendingGiveUp(profile)
    }

    /** Success bookkeeping shared by both callback paths (bound and deliberately unbound). */
    private fun markConnected(network: Network) {
        activeNetwork = network
        connectedOnce = true
        pendingFailure = null
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = null
    }

    /**
     * Waits for [submitSpecifierRequest]'s callback to produce a usable network. On timeout the
     * registration is deliberately left in place: Android keeps matching it against every later
     * scan, so a recovery retry joins a hunt that has been running the whole time instead of
     * restarting it from zero. [schedulePendingGiveUp] still bounds how long the radio can be
     * held by a request nothing ever answers.
     *
     * The wait can run [UNAVAILABLE_GRACE_MS] past [CONNECTION_TIMEOUT_MS], but only while
     * nothing has been granted - the window exists to catch Android's own late verdict.
     */
    private suspend fun awaitRequestedNetwork(profile: MotorcycleProfile): Result<Network> {
        try {
            pollForOutcome(SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS)?.let { return it }
            if (!networkGranted) {
                ProjectionEventLog.debug(
                    "NETWORK",
                    "Nothing granted for ${profile.ssid} within ${CONNECTION_TIMEOUT_MS}ms; waiting up " +
                        "to ${UNAVAILABLE_GRACE_MS}ms for Android's own verdict."
                )
                pollForOutcome(SystemClock.elapsedRealtime() + UNAVAILABLE_GRACE_MS)?.let { return it }
            }
        } catch (cancelled: CancellationException) {
            // A real cancellation (user cancel, scope teardown) must release the exclusive
            // request and propagate, not become a Result.
            clearCurrentNetworkRequest()
            throw cancelled
        }
        ProjectionEventLog.error(
            "NETWORK",
            "Wi-Fi setup timed out after ${CONNECTION_TIMEOUT_MS}ms with " +
                (if (networkGranted) "the network granted but no usable IPv4 address" else "no network granted") +
                "; the request stays pending for the next attempt."
        )
        return Result.failure(IllegalStateException(setupTimeoutMessage(profile)))
    }

    /**
     * Polls the shared connection state until [deadline], returning null if it runs out with
     * neither a network nor a failure - the caller decides whether that is worth waiting past.
     */
    private suspend fun pollForOutcome(deadline: Long): Result<Network>? {
        while (SystemClock.elapsedRealtime() < deadline) {
            currentNetwork()?.let { return Result.success(it) }
            pendingFailure?.let { failure ->
                pendingFailure = null
                // activeVpnLabel() omitted here: merely having a VpnService-based app present isn't evidence this failure caused it.
                val vpnMessage = TBoxVpnDiagnostics.userFacingMessage(failure, activeVpnLabel = null)
                return Result.failure(vpnMessage?.let { IllegalStateException(it, failure) } ?: failure)
            }
            delay(NETWORK_POLL_MS)
        }
        return null
    }

    /**
     * Names which half of the join ran out of time. One message used to cover both, and it named
     * only the second: a rider whose phone never associated at all was told Android had not got
     * an IP address *from the AP*, which points at the bike when the AP was never reached.
     */
    private fun setupTimeoutMessage(profile: MotorcycleProfile): String = if (networkGranted) {
        "The phone joined ${profile.ssid} but Android never obtained a usable IPv4 address from " +
            "it within ${CONNECTION_TIMEOUT_MS}ms. Switch the dash off and on again, then retry."
    } else {
        "The phone never joined ${profile.ssid}: Android did not associate to it within " +
            "${CONNECTION_TIMEOUT_MS}ms. Check that ${profile.ssid} is listed in the phone's Wi-Fi " +
            "settings while the dash shows its pairing screen - if it is not, the dash is not " +
            "broadcasting. If Android showed a dialog asking to connect to it, accept it and retry."
    }

    /**
     * A pending request left in place by [awaitRequestedNetwork] must not outlive every recovery
     * budget: past that point it only takes the Wi-Fi radio away from whoever asks next —
     * including the rider reconnecting by hand — so release it once nothing has connected for
     * [REJOIN_GIVE_UP_MS].
     */
    private fun schedulePendingGiveUp(profile: MotorcycleProfile) {
        pendingGiveUpJob?.cancel()
        pendingGiveUpJob = reconnectScope.launch {
            delay(REJOIN_GIVE_UP_MS)
            if (activeNetwork == null && pendingRequestSsid == profile.ssid) {
                ProjectionEventLog.warning(
                    "NETWORK",
                    "Releasing the pending T-Box Wi-Fi request for ${profile.ssid}: nothing " +
                        "connected within ${REJOIN_GIVE_UP_MS / 1_000L}s."
                )
                clearCurrentNetworkRequest()
            }
        }
    }

    private fun scheduleRejoin() {
        val profile = activeProfile ?: return
        if (!connectedOnce) return
        // Exactly one ladder, whatever raced its way in here.
        if (!rejoinActive.compareAndSet(false, true)) return
        rejoinJob = reconnectScope.launch {
            var attempt = 0
            val startedAt = SystemClock.elapsedRealtime()
            try {
                ladder@ while (activeProfile != null && connectedOnce && activeNetwork == null) {
                    val step = nextTBoxRejoinStep(
                        attempt = attempt + 1,
                        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                        budgetMillis = REJOIN_GIVE_UP_MS,
                        firstDelayMillis = REJOIN_FIRST_DELAY_MS,
                        baseDelayMillis = REJOIN_BASE_DELAY_MS,
                        maxDelayMillis = REJOIN_MAX_DELAY_MS
                    )
                    if (step is TBoxRejoinStep.GiveUp) {
                        // Every downstream recovery budget is shorter than this, so past the
                        // deadline there is no session left for a reacquired AP to serve. Holding
                        // an exclusive WifiNetworkSpecifier request open past that point only
                        // takes the Wi-Fi radio away from whoever asks next - including the rider
                        // reconnecting by hand.
                        ProjectionEventLog.warning(
                            "NETWORK",
                            "Giving up on the T-Box Wi-Fi after $attempt rejoin attempt(s) over " +
                                "${REJOIN_GIVE_UP_MS / 1_000L}s; releasing the network request."
                        )
                        clearCurrentNetworkRequest()
                        break@ladder
                    }
                    attempt++
                    delay((step as TBoxRejoinStep.WaitThenRetry).delayMillis)
                    if (activeNetwork != null) break@ladder
                    // A fresh submission per attempt: the ladder exists for devices whose stale
                    // specifier registration never reconnects on its own, so unlike the connect()
                    // retry path it deliberately does NOT join the previous pending request.
                    submitSpecifierRequest(profile)
                    val network = awaitLadderNetwork()
                    if (network != null) {
                        ProjectionEventLog.record(
                            "NETWORK",
                            "T-Box Wi-Fi automatically reacquired on attempt $attempt: network=$network."
                        )
                        mutableEvents.tryEmit(TBoxNetworkEvent.Reacquired(network))
                        return@launch
                    }
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "T-Box Wi-Fi rejoin attempt $attempt failed."
                    )
                }
            } finally {
                // Order matters: clear the handle before reopening the gate, so a ladder started
                // by the next onLost cannot have its own job reference nulled out from under it -
                // which would leave it running and uncancellable, the very shape of the bug.
                rejoinJob = null
                rejoinActive.set(false)
            }
        }
    }

    /** One ladder attempt's wait: no timeout error spam, no teardown — the loop resubmits anyway. */
    private suspend fun awaitLadderNetwork(): Network? {
        val deadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            currentNetwork()?.let { return it }
            pendingFailure?.let { failure ->
                pendingFailure = null
                ProjectionEventLog.warning(
                    "NETWORK",
                    "T-Box Wi-Fi rejoin request failed: ${failure.message}"
                )
                return null
            }
            delay(NETWORK_POLL_MS)
        }
        return null
    }

    private suspend fun findExistingWifi(ssid: String): Network {
        while (true) {
            val network = findMatchingWifi(ssid)
            if (network != null) {
                activeNetwork = network
                ProjectionEventLog.record(
                    "NETWORK",
                    "Existing Wi-Fi validated for simulator: ssid=${normalizeSsid(ssid)}, network=$network, " +
                        "addresses=${usableIpv4Addresses(network)}."
                )
                return network
            }
            delay(EXISTING_WIFI_POLL_MS)
        }
    }

    /** Polls the already-connected Wi-Fi used by the Mac simulator, which has no specifier callback. */
    private fun startSimulatorMonitor(profile: MotorcycleProfile) {
        if (simulatorMonitorJob?.isActive == true) return
        simulatorMonitorJob = reconnectScope.launch {
            try {
                while (activeProfile == profile && connectedOnce) {
                    delay(SIMULATOR_MONITOR_POLL_MS)
                    val matching = findMatchingWifi(profile.ssid)
                    val current = activeNetwork
                    when {
                        current != null && matching == null -> {
                            activeNetwork = null
                            ProjectionEventLog.warning(
                                "NETWORK",
                                "Simulator Wi-Fi disappeared; waiting for it to become available again."
                            )
                            mutableEvents.tryEmit(TBoxNetworkEvent.Lost(current))
                        }
                        current == null && matching != null -> {
                            activeNetwork = matching
                            ProjectionEventLog.record(
                                "NETWORK",
                                "Simulator Wi-Fi automatically reacquired: network=$matching."
                            )
                            mutableEvents.tryEmit(TBoxNetworkEvent.Reacquired(matching))
                        }
                    }
                }
            } finally {
                simulatorMonitorJob = null
            }
        }
    }

    private fun findMatchingWifi(expectedSsid: String): Network? {
        val normalizedExpected = normalizeSsid(expectedSsid)
        val connectedSsid = runCatching { normalizeSsid(wifiManager.connectionInfo?.ssid.orEmpty()) }
            .getOrDefault("")
        val candidates = connectivityManager.allNetworks.asSequence()
            .filter { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            .filter { network -> usableIpv4Addresses(network).isNotEmpty() }
            .toList()
        val exactMatch = if (connectedSsid == normalizedExpected) {
            val active = connectivityManager.activeNetwork
            candidates.firstOrNull { it == active } ?: candidates.firstOrNull()
        } else {
            null
        }
        if (exactMatch != null) return exactMatch

        // Some Android builds hide WifiInfo.ssid despite the granted Wi-Fi permissions. When there
        // is exactly one usable Wi-Fi network, it is still safe to use it for the simulator.
        if (connectedSsid.isBlank() || connectedSsid == "<unknown ssid>") {
            return candidates.singleOrNull()?.also {
                // This runs on a per-second poll, and the hidden SSID is a stable property of
                // the build, not an event: logging it every call buried real entries under one
                // warning per second for the whole session. Report the transition only.
                if (!hiddenSsidFallbackLogged) {
                    hiddenSsidFallbackLogged = true
                    ProjectionEventLog.warning(
                        "NETWORK",
                        "Android did not expose the current Wi-Fi SSID; using the only usable Wi-Fi network " +
                            "for the simulator. Further occurrences are not logged."
                    )
                }
            }
        }
        return null
    }

    private fun usableIpv4Addresses(network: Network): List<String> =
        connectivityManager.getLinkProperties(network)?.linkAddresses
            ?.mapNotNull { it.address }
            ?.filter(::isUsableTBoxIpv4Address)
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

    private fun normalizeSsid(value: String): String = value.trim().removeSurrounding("\"")

    internal fun activeVpnLabel(): String? {
        val capabilities = connectivityManager.allNetworks.asSequence()
            .mapNotNull { connectivityManager.getNetworkCapabilities(it) }
            .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            ?: return null
        val ownerUid = capabilities.ownerUid
        val packageName = if (ownerUid >= 0) {
            contextPackageManager.getPackagesForUid(ownerUid)?.firstOrNull()
        } else {
            null
        }
        val applicationLabel = packageName?.let { name ->
            runCatching {
                val info = contextPackageManager.getApplicationInfo(name, 0)
                contextPackageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }
        return applicationLabel?.takeIf { it.isNotBlank() } ?: "active"
    }

    private companion object {
        const val TAG = "TBoxNetwork"
        const val CONNECTION_TIMEOUT_MS = 30_000L

        /**
         * Extra wait for Android's own `onUnavailable` verdict once [CONNECTION_TIMEOUT_MS] has
         * run out with nothing granted. Android times a specifier request out 30s after the rider
         * approves the picker, so its verdict always lands a few seconds after ours - in a rider
         * log of twelve consecutive failures it arrived 2.8-4.4s late every single time, which
         * meant the specific "Android could not deliver this network" reason was never the one
         * reported. Bounded, because a rider who leaves the picker open pushes it out of reach.
         */
        const val UNAVAILABLE_GRACE_MS = 6_000L
        const val EXISTING_WIFI_POLL_MS = 250L
        const val NETWORK_POLL_MS = 250L
        const val SIMULATOR_MONITOR_POLL_MS = 1_000L
        const val REJOIN_FIRST_DELAY_MS = 300L
        const val REJOIN_BASE_DELAY_MS = 2_500L
        const val REJOIN_MAX_DELAY_MS = 15_000L

        /**
         * How long the ladder keeps chasing a vanished T-Box AP. Deliberately longer than every
         * downstream recovery budget (mirroring and Android Auto both give up after 120s), so a
         * dropout short enough for a session to survive is still covered - and finite, so a bike
         * that is simply switched off does not leave the radio under an exclusive request.
         */
        const val REJOIN_GIVE_UP_MS = 180_000L
        /** Enough to show a band twin without turning a busy scan into a wall of text. */
        const val SIBLING_AP_LOG_LIMIT = 4
    }

    private val contextPackageManager = context.applicationContext.packageManager
}

/**
 * Summarises what an AP requires to be joined, from the raw `ScanResult.capabilities` string.
 *
 * WPA3 is called out on its own because it is the one answer that indicts this app: the specifier
 * only offers a WPA2 passphrase, so a dash that requires SAE cannot be joined at all.
 */
internal fun securityName(capabilities: String?): String {
    val caps = capabilities.orEmpty().uppercase()
    val schemes = buildList {
        if (caps.contains("SAE")) add("WPA3/SAE")
        if (caps.contains("RSN") || caps.contains("WPA2")) add("WPA2")
        if (caps.contains("WPA-") || caps.contains("WPA_") || Regex("(^|[^23A-Z])WPA($|[^23])").containsMatchIn(caps)) {
            add("WPA")
        }
        if (caps.contains("WEP")) add("WEP")
    }
    return when {
        schemes.isNotEmpty() -> schemes.joinToString("+")
        caps.isBlank() -> "not reported"
        else -> "open or unrecognised ($caps)"
    }
}

/** Names the band a scan frequency sits in; "?" rather than a guess when it is out of range. */
internal fun bandName(frequencyMhz: Int): String = when (frequencyMhz) {
    in 2400..2500 -> "2.4GHz"
    in 4900..5900 -> "5GHz"
    in 5925..7125 -> "6GHz"
    else -> "an unknown band"
}

internal fun isUsableTBoxIpv4Address(address: InetAddress): Boolean =
    address is Inet4Address &&
        !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isLinkLocalAddress &&
        !address.isMulticastAddress
