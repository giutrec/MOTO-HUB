// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.motohub.android.BuildConfig
import io.motohub.android.androidauto.AndroidAutoDisplayModeStore
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.InstallationId
import io.motohub.android.ipc.IpcBridgeContract
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.SentryIntegration
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.TBoxCapabilities
import io.motohub.android.tbox.TBoxCapabilityStore
import io.motohub.android.tbox.TBoxModelProfile
import io.motohub.android.tbox.TBoxWireLadder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Why a report went out; the server groups on it. */
enum class DiagnosticReportTrigger(val wireName: String) {
    STARTUP("startup"),
    CRASH("crash"),
    MANUAL("manual")
}

/** One report ready to upload: the metadata document and the combined log it describes. */
class DiagnosticReport(
    val reportId: String,
    val supportId: String,
    val deviceId: String,
    val metadata: JSONObject,
    val logText: String
)

/**
 * Gathers everything support keeps asking riders for, in one document: which bike (as precisely
 * as the dashboard ever told us), which phone, which versions of Android, Android Auto, CORE and
 * ADVANCED, and the complete ADVANCED + CORE log. Nothing here is collected specially for the
 * report - every field is already on the phone, in the stores the app uses to run.
 *
 * What is deliberately left out: the T-Box password (encrypted at rest, never read here), the
 * dash's real MAC/BSSID (the app's standing rule is never to record those), the raw ANDROID_ID
 * (only hashed, see [InstallationId]), and anything from the AI assistant's credentials.
 */
object DiagnosticReportBuilder {
    private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    private const val CORE_PACKAGE = "io.motohub.android"

    /** Same vocabulary the Sentry integration next door uses, so the two can be joined up. */
    private val EDITION: String get() = if (BuildConfig.IS_PRO) "advanced" else "core"

    suspend fun build(context: Context, trigger: DiagnosticReportTrigger): DiagnosticReport {
        val appContext = context.applicationContext
        val profileStore = MotorcycleProfileStore(appContext)
        val profiles = runCatching { profileStore.loadAll() }.getOrDefault(emptyList())
        val active = runCatching { profileStore.load() }.getOrNull()
        val supportId = InstallationId.supportId(appContext, active?.id)
        val deviceId = InstallationId.deviceId(appContext)
        val reportId = UUID.randomUUID().toString()
        val companion = createDiagnosticsCompanion(appContext)
        val logText = companion.exportLog(appContext)
        val ladders = companion.wireLadders(appContext, profiles)
        val bluetooth = companion.handlebarBluetoothGrants(appContext)

        val metadata = JSONObject().apply {
            // 2: every motorcycle now carries a "wireLadder" object. Bumped rather than added
            // silently so the collector can tell an old report's absent field from a new one's
            // empty search.
            // 3: "permissions", with the Bluetooth grant of each half. Same reason: a missing
            // field and a denied permission must not read the same, and the difference is the
            // whole diagnosis of a handlebar that never worked.
            put("schema", 3)
            put("reportId", reportId)
            put("supportId", supportId)
            put("deviceId", deviceId)
            put("trigger", trigger.wireName)
            put("createdAt", isoNow())
            put("sentry", JSONObject().apply {
                putOpt("installationId", SentryIntegration.sdkInstallationId(appContext))
                put("environment", EDITION)
                put("release", "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}")
            })
            put("phone", phone())
            put("apps", apps(appContext))
            put("androidAuto", packageVersion(appContext, GEARHEAD_PACKAGE))
            put("motorcycle", active?.let { motorcycle(appContext, it, isActive = true, ladders = ladders) } ?: JSONObject.NULL)
            put("motorcycles", JSONArray().apply {
                profiles.forEach { put(motorcycle(appContext, it, isActive = it.id == active?.id, ladders = ladders)) }
            })
            put("permissions", permissions(bluetooth))
            put("settings", settings(appContext))
            put("log", JSONObject().apply {
                put("chars", logText.length)
                put("loggingEnabled", MotoHubSettings.loggingEnabled(appContext))
                put("verboseTBoxLogging", MotoHubSettings.verboseTBoxLogging(appContext))
            })
        }
        return DiagnosticReport(reportId, supportId, deviceId, metadata, logText)
    }

    /**
     * The runtime grants that decide whether a feature can work at all, per package.
     *
     * Only Bluetooth so far, and only because a whole class of "the handlebar does nothing"
     * report turns on it: the permission belongs to whichever app decodes the presses, and that
     * is not the app the rider configured. JSONObject.NULL rather than a dropped key for the half
     * that could not be asked - see HandlebarBluetoothGrants.
     */
    private fun permissions(bluetooth: HandlebarBluetoothGrants) = JSONObject().apply {
        put("bluetoothConnect", JSONObject().apply {
            put("advanced", bluetooth.advanced ?: JSONObject.NULL)
            put("core", bluetooth.core ?: JSONObject.NULL)
        })
    }

    private fun phone() = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("fingerprint", Build.FINGERPRINT)
        put("androidVersion", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("locale", Locale.getDefault().toLanguageTag())
        put("timeZone", TimeZone.getDefault().id)
    }

    /**
     * This app under its edition's name, plus whatever it knows of the other one.
     *
     * The keys keep their meaning across editions rather than collapsing into a single "this
     * app": a CORE-only rider's report has an "advanced" that is simply absent, and the collector
     * can tell that apart from a pairing whose ADVANCED half failed to report - which it could
     * not if both editions wrote themselves into the same field.
     */
    private fun apps(context: Context) = JSONObject().apply {
        val self = JSONObject().apply {
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("applicationId", BuildConfig.APPLICATION_ID)
            put("buildType", BuildConfig.BUILD_TYPE)
        }
        if (BuildConfig.IS_PRO) {
            put("advanced", self)
            put("core", packageVersion(context, CORE_PACKAGE))
        } else {
            put("core", self)
        }
        put("ipcContractVersion", IpcBridgeContract.CONTRACT_VERSION)
    }

    /** `{installed:false}` when the package is absent; both queried packages are declared in the manifest. */
    private fun packageVersion(context: Context, packageName: String): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        val info = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        put("installed", info != null)
        if (info != null) {
            put("versionName", info.versionName ?: "")
            put("versionCode", info.longVersionCode)
        }
    }

    private fun motorcycle(
        context: Context,
        profile: MotorcycleProfile,
        isActive: Boolean,
        ladders: Map<String, String>
    ): JSONObject {
        val snapshot = runCatching { TBoxCapabilityStore(context).load(profile) }.getOrNull()
        val capabilities = snapshot?.capabilities
        val override = ProfileOverride.byKey(profile.profileOverrideKey)
        val resolved = TBoxModelProfile.resolve(profile.modelId, capabilities, override)
        val displayMode = runCatching { AndroidAutoDisplayModeStore(context).load(profile).name }.getOrNull()
        return JSONObject().apply {
            put("profileId", profile.id)
            put("active", isActive)
            put("ssid", profile.ssid)
            putOpt("displayName", profile.displayName)
            putOpt("modelId", profile.modelId)
            put("connectionMode", profile.connectionMode.name)
            put("profileOverride", override.key)
            put("resolvedProfile", resolved.key)
            put("resolvedProfileName", resolved.displayName)
            putOpt("androidAutoDisplayMode", displayMode)
            putOpt("fuelTankRangeKm", profile.fuelTankRangeKm)
            snapshot?.host?.let { host ->
                put("dashboard", JSONObject().apply {
                    put("port", host.port)
                    put("packageName", host.packageName)
                })
            }
            putOpt("discoveredAt", snapshot?.discoveredAtEpochMillis?.let(::iso))
            putOpt("capabilitiesObservedAt", snapshot?.capabilitiesObservedAtEpochMillis?.let(::iso))
            put("capabilities", capabilities?.let(::capabilities) ?: JSONObject.NULL)
            put("wireLadder", wireLadder(ladders[profile.id], resolved))
        }
    }

    /**
     * Where the wire search stands for this motorcycle, and which format it settled on.
     *
     * This is the field that makes the whole search worth more than one rider's afternoon.
     * A dashboard MOTO-HUB has never seen walks the ladder alone and, if it is lucky, lands on a
     * format that works - knowledge that then dies on that phone. Reported here, the same
     * fingerprint arriving from several riders with the same confirmed rung is exactly the
     * evidence a shipped profile is made of, without anyone having to own the hardware.
     *
     * Carries no identifiers of its own: the fingerprint is firmware metadata (HU family, SDK
     * flavor, channel, version), the same class of thing already in `capabilities`.
     */
    private fun wireLadder(coreProgress: String?, resolved: TBoxModelProfile): JSONObject {
        val progress = TBoxWireLadder.parseProgress(coreProgress)
        val rung = TBoxWireLadder.RUNGS.getOrElse(progress.rungIndex) { TBoxWireLadder.RUNGS.first() }
        return JSONObject().apply {
            // False for a dashboard a hand-written profile already claims: its wire came from
            // somebody measuring the hardware, and the ladder never ran.
            put("searching", resolved == TBoxModelProfile.GENERIC)
            // Whether these numbers are Core's or a stand-in, said out loud. This block used to be
            // read out of THIS process's copy of the ladder preferences, which nothing here ever
            // writes: every report from every rider claimed rung 0, TRYING, no fingerprint, while
            // Core's log in the same file said otherwise (field log 90438e1e, 2026-08-25). An
            // unreachable Core is now an unreachable Core, not a fabricated fresh start.
            put("knownToCore", coreProgress != null)
            put("rung", progress.rungIndex)
            put("rungCount", TBoxWireLadder.RUNGS.size)
            put("wire", rung.signature)
            put("state", progress.state.name)
            put("attemptsOnRung", progress.attemptsOnRung)
            putOpt("lastOutcome", progress.lastOutcome)
            putOpt("dashboardFingerprint", progress.fingerprint)
        }
    }

    /** The dashboard's own account of itself: the closest thing to a bike model the link carries. */
    private fun capabilities(value: TBoxCapabilities) = JSONObject().apply {
        putOpt("huName", value.huName)
        putOpt("carBrand", value.carBrand)
        putOpt("carModel", value.carModel)
        putOpt("flavor", value.flavor)
        putOpt("channel", value.channel)
        putOpt("packageName", value.packageName)
        putOpt("versionName", value.versionName)
        putOpt("versionCode", value.versionCode)
        putOpt("sdkVersion", value.sdkVersion)
        putOpt("pxcVersion", value.pxcVersion)
        putOpt("productType", value.productType)
        putOpt("screenType", value.screenType)
        putOpt("transportType", value.transportType)
        putOpt("supportFunction", value.supportFunction)
        putOpt("dpi", value.dpi)
        putOpt("screenTouch", value.screenTouch)
        putOpt("screenMirroring", value.screenMirroring)
        putOpt("hid", value.hid)
        putOpt("microphone", value.microphone)
    }

    private fun settings(context: Context) = JSONObject().apply {
        put("autostartEnabled", MotoHubSettings.autostartEnabled(context))
        put("autostartService", MotoHubSettings.autostartService(context).name)
        put("autoUpdateChecks", MotoHubSettings.autoUpdateChecks(context))
        put("keepScreenOn", MotoHubSettings.keepScreenOn(context))
        put("autoDiagnosticsUpload", DiagnosticReportSettings.autoUploadEnabled(context))
    }

    private fun isoNow() = iso(System.currentTimeMillis())

    private fun iso(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(epochMillis))
}
