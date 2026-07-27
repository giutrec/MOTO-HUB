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

object AaSelfMode {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"

    /**
     * The entry point that has worked historically. Newer Android Auto builds (~16.4+) stopped
     * exporting it: `startActivity` then fails with "Permission Denial: … not exported", which no
     * caller-side change can work around. [discoverStartupComponents] looks for whatever the
     * installed build does export instead, so a version bump does not silently kill self-mode.
     */
    private const val CLASSIC_ACTIVITY =
        "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
    private const val CLASSIC_RECEIVER =
        "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
    private const val RECEIVER_ACTION =
        "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"

    /** A component is a self-mode candidate when its name mentions both concepts. */
    private val REQUIRED_KEYWORDS = listOf("wireless")
    private val ENTRY_KEYWORDS = listOf("startup", "start", "projection", "setup")

    fun trigger(context: Context, port: Int = AaReceiver.PORT, log: (String) -> Unit) {
        log("[AA] Android Auto app: ${gearheadVersion(context) ?: "not installed"}")
        val extras = SelfModeExtras(context, port)

        if (startActivityComponent(context, CLASSIC_ACTIVITY, extras, log)) return

        // The classic activity was refused (not exported, or gone). Ask the installed build what
        // it still exposes rather than guessing class names that change between AA releases.
        val discovered = discoverStartupComponents(context)
        if (discovered.isEmpty()) {
            log(
                "[AA] Android Auto exports no wireless-startup component on this device; " +
                    "only the legacy broadcast is left to try."
            )
        } else {
            log("[AA] Exported wireless-startup candidates: ${discovered.joinToString { it.describe() }}")
            for (candidate in discovered) {
                val started = when (candidate.kind) {
                    ComponentKind.ACTIVITY -> startActivityComponent(context, candidate.className, extras, log)
                    ComponentKind.RECEIVER -> sendReceiverBroadcast(context, candidate.className, extras, log)
                    ComponentKind.SERVICE -> startServiceComponent(context, candidate.className, extras, log)
                }
                if (started) return
            }
        }

        if (sendReceiverBroadcast(context, CLASSIC_RECEIVER, extras, log)) return
        log(
            "[AA] Self-mode could not be triggered: this Android Auto version refuses every known " +
                "entry point. Android Auto must project to MOTO-HUB itself; if you are on the " +
                "Android Auto beta, leaving it restores the working entry point."
        )
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
        log("[AA] activity $className refused: ${failure.message}")
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
        // sendBroadcast never reports whether anything handled it, so this is "sent", not "worked":
        // the caller still has to watch for an inbound AAP connection to know if it took effect.
        log("[AA] broadcast sent to $className")
        true
    } catch (failure: Exception) {
        log("[AA] broadcast to $className refused: ${failure.message}")
        false
    }

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
        context.startService(intent)
        log("[AA] service $className started")
        true
    } catch (failure: Exception) {
        log("[AA] service $className refused: ${failure.message}")
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
        val activities = info.activities.orEmpty()
            .filter { it.exported && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.ACTIVITY, it.name) }
        val receivers = info.receivers.orEmpty()
            .filter { it.exported && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.RECEIVER, it.name) }
        val services = info.services.orEmpty()
            .filter { it.exported && looksLikeStartup(it.name) }
            .map { StartupComponent(ComponentKind.SERVICE, it.name) }
        (activities + receivers + services).filterNot { it.className == CLASSIC_ACTIVITY }
    }.getOrDefault(emptyList())

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
