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
     * Android Auto releases from this one on have removed loopback ("self mode") projection:
     * WirelessStartupActivity is no longer exported and WirelessStartupReceiver ships disabled,
     * so every way an app can ask for it is closed. 17.2.662634 is verified working with
     * MOTO-HUB; 17.4.663004 fails on every entry point (the headunit-revived project hit the
     * same wall in its issue #698). 17.3 is untested, hence the boundary sits at 17.3.
     */
    private const val FIRST_BROKEN_MAJOR = 17
    private const val FIRST_BROKEN_MINOR = 3

    /**
     * Shown when the receiver never saw an inbound connection.
     *
     * The remedy deliberately leads with Android Auto's own head unit server rather than
     * downgrading: it needs no sideloaded APK, works on the versions that removed self-mode, and
     * MOTO-HUB connects to it automatically as soon as the rider starts it.
     */
    const val NEVER_CONNECTED_MESSAGE =
        "Google Android Auto never connected to MOTO-HUB. Android Auto 17.4 removed the way apps " +
            "ask it to project, so start it from Android Auto instead: open Android Auto, tap " +
            "Version ten times to unlock Developer settings, then choose \"Start head unit " +
            "server\" from its menu. MOTO-HUB connects to it on its own — leave MOTO-HUB running " +
            "while you do it."

    /**
     * Whether the installed Android Auto is new enough to have dropped self-mode. Used to warn
     * up front instead of after a full round of attempts — but never to skip them: this is a
     * behavioural regression Google could undo, and a version number is a poor thing to hard-code
     * a refusal on.
     */
    fun isKnownBrokenVersion(versionName: String?): Boolean {
        val numbers = versionName.orEmpty().substringBefore('-').split('.')
        val major = numbers.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = numbers.getOrNull(1)?.toIntOrNull() ?: return false
        return major > FIRST_BROKEN_MAJOR ||
            (major == FIRST_BROKEN_MAJOR && minor >= FIRST_BROKEN_MINOR)
    }

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
