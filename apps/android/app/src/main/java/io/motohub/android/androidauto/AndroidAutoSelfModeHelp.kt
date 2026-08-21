// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
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
     * so every way an app can ask for it is closed. 17.2.662634 was verified working with
     * MOTO-HUB; 17.4.663004 fails on every entry point (the headunit-revived project hit the
     * same wall in its issue #698). 17.3 is untested, hence the boundary sits at 17.3.
     *
     * The boundary is a warning, never a refusal, and this is why: a rider log of 2026-07-31
     * (OnePlus CPH2653) running that same 17.2.662634 had WirelessStartupActivity refused as not
     * exported. So the version alone does not decide it - Google gates this per device and per
     * rollout - and what actually separates the two failures is whether an entry point ACCEPTED
     * the intent. See [ACCEPTED_BUT_SILENT_MESSAGE]. The boundary is left where it is rather than
     * widened to 17.2: it would then fire for every rider on a release that does work for most
     * of them, to say something the attempt itself reports a few seconds later anyway.
     */
    private const val FIRST_BROKEN_MAJOR = 17
    private const val FIRST_BROKEN_MINOR = 3

    /**
     * Shown when the receiver never saw an inbound connection.
     *
     * Leads with the head unit server because it is the path confirmed working on the releases
     * that removed self-mode, and it needs nothing but Android Auto's own menu — no sideloaded
     * APK. MOTO-HUB keeps polling for it, so the rider can start it without restarting anything.
     */
    const val NEVER_CONNECTED_MESSAGE =
        "Google Android Auto never connected to MOTO-HUB. Newer Android Auto releases removed the " +
            "way apps ask it to project, so start it from Android Auto itself: open Android Auto ▸ " +
            "tap Version ten times ▸ Developer settings ▸ the ⋮ menu at the top right ▸ \"Start head " +
            "unit server\". Leave MOTO-HUB running: it connects on its own within a couple of " +
            "seconds, and you can leave the server running for next time."

    /**
     * Shown instead of [NEVER_CONNECTED_MESSAGE] when Android Auto *took* the request and then did
     * nothing.
     *
     * The two failures look identical to the rider and have different remedies. A refusal at the
     * intent ("not exported") is the release having closed self-mode, and only the head unit server
     * is left. An accepted intent followed by silence is Android Auto declining to project to a
     * head unit it does not trust, which is the "Add new cars" switch and nothing else - and the
     * rider was being sent to the head unit server for it, which does not fix that. Field log
     * 2026-07-31 (OnePlus CPH2653) on 17.2.662634, a release this file calls verified working:
     * one refusal, three acceptances, total silence.
     */
    const val ACCEPTED_BUT_SILENT_MESSAGE =
        "Google Android Auto took MOTO-HUB's request and then ignored it. That is what it does " +
            "with a head unit it has not been told to trust: open Android Auto ▸ tap Version ten " +
            "times ▸ Developer settings ▸ turn on \"Add new cars to Android Auto\" (older builds " +
            "call it \"Unknown sources\"), then start Android Auto from MOTO-HUB again. If it " +
            "still does nothing, use the ⋮ menu on that same screen ▸ \"Start head unit server\"."

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

    fun isMessageAboutSelfMode(message: String?): Boolean =
        message == NEVER_CONNECTED_MESSAGE || message == ACCEPTED_BUT_SILENT_MESSAGE

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
