// MOTO-HUB glue (technique ported from headunit-revived AGPLv3 AapService.startSelfMode).
// Triggers Google Android Auto's loopback "self-mode": asks gearhead to project to 127.0.0.1:PORT
// with NO VPN. Best launched from a foreground Activity to satisfy Android's background-activity-
// launch restrictions (Android 12+/15).
package io.motohub.android.aa

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import kotlinx.coroutines.delay

object AaSelfMode {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"

    /**
     * The entry point that has worked historically. Android Auto 17.x no longer exports it:
     * `startActivity` fails with "Permission Denial: … not exported", which no caller-side change
     * can work around. The remaining exported components are found at runtime instead of guessing
     * class names that change between releases (17.4 exposes WirelessStartupReceiver plus the
     * WirelessSetupShared* services).
     */
    private const val CLASSIC_ACTIVITY =
        "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
    private const val CLASSIC_RECEIVER =
        "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
    private const val RECEIVER_ACTION =
        "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"

    /**
     * Android Auto's own head unit server, the one its Developer settings start from the overflow
     * menu. Starting it turns Android Auto into the listener on
     * [AaReceiver.HEAD_UNIT_SERVER_PORT], which [AaReceiver] then dials — the one path still open
     * on releases that removed self-mode. Historically a hidden service, so this may well be
     * refused; it costs one intent to find out, and the log says which it was.
     */
    private const val HEAD_UNIT_SERVER_SERVICE =
        "com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"

    private val REQUIRED_KEYWORDS = listOf("wireless")
    private val ENTRY_KEYWORDS = listOf("startup", "start", "projection", "setup")
    /** Matches the head unit server family, whose names carry no "wireless" at all. */
    private val HEAD_UNIT_KEYWORDS = listOf("headunit", "head_unit")

    /** How long one entry point gets to produce an inbound AAP connection before the next is tried. */
    private const val ATTEMPT_WAIT_MS = 4_000L
    private const val POLL_INTERVAL_MS = 200L

    /**
     * Asks Android Auto to project here, trying every entry point the installed build exposes.
     *
     * [isConnected] is what makes this reliable: `sendBroadcast` and `startService` only report
     * that an intent was dispatched, never that Gearhead acted on it, so each attempt is followed
     * by a short wait for the local AAP socket to actually be opened. Without that check the
     * sequence stopped at the first component that merely accepted the intent.
     */
    suspend fun trigger(
        context: Context,
        port: Int = AaReceiver.PORT,
        isConnected: () -> Boolean = { AaReceiver.hasAndroidAutoConnectedSinceStart() },
        onProgress: (String) -> Unit = { io.motohub.android.androidauto.AndroidAutoRuntime.publishStartupDetail(it) },
        log: (String) -> Unit
    ) {
        val version = gearheadVersion(context)
        log("[AA] Android Auto app: ${version ?: "not installed"}")
        if (io.motohub.android.androidauto.AndroidAutoSelfModeHelp.isKnownBrokenVersion(version)) {
            // Still attempted below: Google could restore the entry points, and a version string
            // is not a good enough reason to refuse outright. The rider just learns immediately
            // why the next few seconds are likely to be wasted.
            log(
                "[AA] Android Auto $version is a release known to have removed wireless " +
                    "self-mode projection (17.2 works, 17.4 does not). Trying anyway."
            )
            onProgress("Android Auto $version may not support wireless projection…")
        }
        val extras = SelfModeExtras(context, port)

        val discovered = discoverStartupComponents(context)
        if (discovered.isEmpty()) {
            log("[AA] Android Auto exports no wireless-startup component on this device.")
        } else {
            log("[AA] Exported wireless-startup candidates: ${discovered.joinToString { it.describe() }}")
        }

        val headUnitServers = discoverHeadUnitServerComponents(context)
        if (headUnitServers.isEmpty()) {
            log(
                "[AA] Android Auto exports no head unit server component; it can only be started " +
                    "by hand from Developer settings ▸ overflow menu ▸ Start head unit server."
            )
        } else {
            log("[AA] Head unit server candidates: ${headUnitServers.joinToString { it.describe() }}")
        }

        // Activity first (the historical, most reliable shape), then whatever is still exported,
        // the legacy receiver in case discovery missed it, and finally the head unit server -
        // which does not project by itself but makes Android Auto listen for [AaReceiver].
        val attempts = buildList {
            add(StartupComponent(ComponentKind.ACTIVITY, CLASSIC_ACTIVITY))
            addAll(discovered)
            if (discovered.none { it.className == CLASSIC_RECEIVER }) {
                add(StartupComponent(ComponentKind.RECEIVER, CLASSIC_RECEIVER))
            }
            addAll(headUnitServers)
            if (headUnitServers.none { it.className == HEAD_UNIT_SERVER_SERVICE }) {
                add(StartupComponent(ComponentKind.SERVICE, HEAD_UNIT_SERVER_SERVICE))
            }
        }

        attempts.forEachIndexed { index, attempt ->
            if (isConnected()) return
            // The rider sees this in the session card and the preview screen: several seconds of
            // apparent inactivity is otherwise indistinguishable from a hang.
            onProgress("Asking Android Auto to start (${index + 1}/${attempts.size})…")
            val dispatched = when (attempt.kind) {
                ComponentKind.ACTIVITY -> startActivityComponent(context, attempt.className, extras, log)
                ComponentKind.RECEIVER -> sendReceiverBroadcast(context, attempt.className, extras, log)
                ComponentKind.SERVICE -> startServiceComponent(context, attempt.className, extras, log)
            }
            if (!dispatched) return@forEachIndexed
            onProgress("Waiting for Android Auto to answer (${index + 1}/${attempts.size})…")
            if (awaitConnection(isConnected)) {
                log("[AA] Android Auto connected after ${attempt.describe()}.")
                onProgress("Android Auto is starting up…")
                return
            }
            log("[AA] no connection ${ATTEMPT_WAIT_MS}ms after ${attempt.describe()}; trying the next entry point.")
        }

        if (isConnected()) return
        onProgress("Start \"head unit server\" in Android Auto ▸ Developer settings ▸ ⋮ menu…")
        log(
            "[AA] Self-mode could not be triggered: none of ${attempts.size} entry points produced " +
                "a connection. Android Auto 17.4 closed them all - the activity is no longer " +
                "exported and WirelessStartupReceiver ships disabled (same wall headunit-revived " +
                "hit in its issue #698). The receiver keeps polling Android Auto's own head unit " +
                "server on :${AaReceiver.HEAD_UNIT_SERVER_PORT}, so starting it from Android Auto's " +
                "Developer settings connects without any downgrade."
        )
    }

    /** Polls the receiver rather than trusting the dispatch result. See [trigger]. */
    private suspend fun awaitConnection(isConnected: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ATTEMPT_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isConnected()) return true
            delay(POLL_INTERVAL_MS)
        }
        return isConnected()
    }

    private fun startActivityComponent(
        context: Context,
        className: String,
        extras: SelfModeExtras,
        log: (String) -> Unit
    ): Boolean = try {
        val intent = Intent().apply {
            setClassName(GEARHEAD_PKG, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            extras.applyActivityExtras(this)
        }
        log("[AA] launching Android Auto $className → 127.0.0.1:${extras.port}")
        context.startActivity(intent)
        true
    } catch (failure: Exception) {
        log("[AA] activity ${className.substringAfterLast('.')} refused: ${failure.message}")
        false
    }

    private fun sendReceiverBroadcast(
        context: Context,
        className: String,
        extras: SelfModeExtras,
        log: (String) -> Unit
    ): Boolean = try {
        val intent = Intent().apply {
            setClassName(GEARHEAD_PKG, className)
            action = RECEIVER_ACTION
            extras.applyReceiverExtras(this)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendBroadcast(intent)
        log("[AA] broadcast sent to ${className.substringAfterLast('.')}")
        true
    } catch (failure: Exception) {
        log("[AA] broadcast to ${className.substringAfterLast('.')} refused: ${failure.message}")
        false
    }

    /**
     * startService returns the resolved component, or null when nothing matched — the one dispatch
     * result in this file that is actually informative, so a missing service is not counted as an
     * attempt worth waiting on.
     */
    private fun startServiceComponent(
        context: Context,
        className: String,
        extras: SelfModeExtras,
        log: (String) -> Unit
    ): Boolean = try {
        val intent = Intent().apply {
            setClassName(GEARHEAD_PKG, className)
            action = RECEIVER_ACTION
            extras.applyReceiverExtras(this)
        }
        val resolved = context.startService(intent)
        if (resolved == null) {
            log("[AA] service ${className.substringAfterLast('.')} did not resolve")
            false
        } else {
            log("[AA] service ${className.substringAfterLast('.')} started")
            true
        }
    } catch (failure: Exception) {
        log("[AA] service ${className.substringAfterLast('.')} refused: ${failure.message}")
        false
    }

    private enum class ComponentKind { ACTIVITY, RECEIVER, SERVICE }

    private data class StartupComponent(val kind: ComponentKind, val className: String) {
        fun describe(): String = "${kind.name.lowercase()}:${className.substringAfterLast('.')}"
    }

    /**
     * Exported gearhead components that look like a wireless-projection entry point, activities
     * first (the historical shape). Exported-only: a non-exported component cannot be started
     * from another uid no matter how it is invoked, so listing it would only produce noise.
     */
    private fun discoverStartupComponents(context: Context): List<StartupComponent> = runCatching {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or
            PackageManager.MATCH_DISABLED_COMPONENTS
        val info = context.packageManager.getPackageInfo(GEARHEAD_PKG, flags)
        // Exported AND enabled: MATCH_DISABLED_COMPONENTS is needed to see the whole manifest,
        // but a disabled component silently swallows anything sent to it — Android Auto 17.4
        // ships WirelessStartupReceiver disabled, which is exactly why the broadcast that used
        // to work now vanishes without an error.
        val activities = info.activities.orEmpty()
            .filter { it.exported && it.isEnabled() && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.ACTIVITY, it.name) }
        val receivers = info.receivers.orEmpty()
            .filter { it.exported && it.isEnabled() && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.RECEIVER, it.name) }
        val services = info.services.orEmpty()
            .filter { it.exported && it.isEnabled() && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.SERVICE, it.name) }
        (activities + receivers + services).filterNot { it.className == CLASSIC_ACTIVITY }
    }.getOrDefault(emptyList())

    /**
     * The manifest `enabled` flag combined with any runtime override the system holds for the
     * component, so a component Google turned off after install is not treated as usable.
     */
    private fun android.content.pm.ComponentInfo.isEnabled(): Boolean = enabled

    /**
     * Exported, enabled components whose name marks them as the head unit server. Kept separate
     * from [discoverStartupComponents] because these do not advertise "wireless" anywhere.
     */
    private fun discoverHeadUnitServerComponents(context: Context): List<StartupComponent> = runCatching {
        val info = context.packageManager.getPackageInfo(
            GEARHEAD_PKG,
            PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS
        )
        info.services.orEmpty()
            .filter { it.exported && it.enabled && looksLikeHeadUnitServer(it.name) }
            .map { StartupComponent(ComponentKind.SERVICE, it.name) }
    }.getOrDefault(emptyList())

    private fun looksLikeHeadUnitServer(className: String?): Boolean {
        val name = className?.lowercase()?.replace("_", "") ?: return false
        return HEAD_UNIT_KEYWORDS.any { name.contains(it.replace("_", "")) }
    }

    private fun looksLikeStartup(className: String?): Boolean {
        val name = className?.lowercase() ?: return false
        return REQUIRED_KEYWORDS.all { name.contains(it) } && ENTRY_KEYWORDS.any { name.contains(it) }
    }

    private fun gearheadVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(GEARHEAD_PKG, 0).versionName
    }.getOrNull()

    /** The reflective network/WifiInfo payload every entry point expects, built once per trigger. */
    private class SelfModeExtras(context: Context, val port: Int) {
        private val network: Parcelable? = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            (manager.activeNetwork as? Parcelable) ?: createFakeNetwork(0)
        }.getOrNull()
        private val wifiInfo: Parcelable? = createFakeWifiInfo()

        fun applyActivityExtras(intent: Intent) {
            intent.putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
            intent.putExtra("PARAM_SERVICE_PORT", port)
            network?.let { intent.putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            wifiInfo?.let { intent.putExtra("wifi_info", it) }
        }

        fun applyReceiverExtras(intent: Intent) {
            intent.putExtra("ip_address", "127.0.0.1")
            intent.putExtra("projection_port", port)
            network?.let { intent.putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            wifiInfo?.let { intent.putExtra("wifi_info", it) }
        }
    }

    /** Reflectively build an android.net.Network from a raw netId (HUR technique). */
    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (e: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    /** Reflectively build a WifiInfo with a fake SSID for the self-mode intent (HUR technique). */
    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID").apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (_: Exception) {}
            wifiInfo
        } catch (e: Exception) {
            null
        }
    }
}
