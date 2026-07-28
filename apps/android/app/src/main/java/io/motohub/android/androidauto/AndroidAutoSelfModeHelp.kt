package io.motohub.android.androidauto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The one prerequisite MOTO-HUB cannot check or set for the rider.
 *
 * Google Android Auto only projects to a head unit it is willing to accept, and a sideloaded one
 * counts as an unknown car: until "Add new cars to Android Auto" (older wording: "Unknown
 * sources") is enabled inside Android Auto's own developer settings, the projection request is
 * simply ignored — the app is asked to start and silently does nothing, which is exactly what a
 * refusal looks like from here. There is no public API to read or toggle it, so all that can be
 * done is name the step and deep-link to the screen.
 */
object AndroidAutoSelfModeHelp {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"
    private const val SETTINGS_ACTIVITY =
        "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"

    /**
     * Shown when the receiver never saw an inbound connection. Matched by the UI to offer help.
     *
     * The beta comes first because it is the case actually observed: on Android Auto 17.4 beta
     * every exported entry point was tried and none produced a connection, while release builds
     * of the same era still work.
     */
    const val NEVER_CONNECTED_MESSAGE =
        "Google Android Auto never connected to MOTO-HUB. If you are in the Android Auto beta " +
            "programme, leave it and reinstall the stable version — recent betas refuse every " +
            "way an app can ask Android Auto to project. Otherwise open Android Auto, tap " +
            "Version ten times to unlock Developer settings, and enable \"Add new cars to " +
            "Android Auto\" (older versions: \"Unknown sources\")."

    fun isMessageAboutSelfMode(message: String?): Boolean = message == NEVER_CONNECTED_MESSAGE

    /**
     * Opens Android Auto's settings, falling back to its App info page: the settings activity is
     * an internal component and may stop being launchable, exactly as the wireless-startup one did.
     */
    fun openAndroidAutoSettings(context: Context): Boolean {
        val direct = runCatching {
            context.startActivity(
                Intent().apply {
                    setClassName(GEARHEAD_PKG, SETTINGS_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
        if (direct) return true

        val launcher = runCatching {
            context.packageManager.getLaunchIntentForPackage(GEARHEAD_PKG)?.let { intent ->
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } ?: false
        }.getOrDefault(false)
        if (launcher) return true

        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$GEARHEAD_PKG")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }
}
