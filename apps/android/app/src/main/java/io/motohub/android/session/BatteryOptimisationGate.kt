// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * What to tell a rider whose session was ended by the phone, and where to send them.
 *
 * Android's battery optimisation is the setting that decides whether the system feels free to
 * stop this app in the background. Being exempt from it is not a guarantee: several OEMs run
 * their own app-management layer on top, with its own rules - the OnePlus kill that started this
 * (`o-kill`, 2026-07-30) took a process that was running a **foreground service**, which AOSP
 * treats as protected. So the exemption improves the odds and nothing more, and the wording here
 * says so rather than promising a fix that may not arrive.
 *
 * Which is also why the advice changes with the answer. A rider who is already exempt and was
 * killed anyway must not be told to do the thing they have already done - for them the remaining
 * lever is the manufacturer's own list, reached from the app's info page.
 */
internal object BatteryOptimisationGate {

    fun isExempt(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.applicationContext.packageName)
        }.getOrDefault(true)
    }

    /** The advice for a rider who has just been told their session was stopped by the phone. */
    fun advice(context: Context, appName: String): String = if (isExempt(context)) {
        "$appName is already exempt from battery optimisation, so this was your phone's own app " +
            "management. Open its app info and allow background activity - on some phones the " +
            "setting is called Battery usage or App launch - and lock $appName in the recent-apps " +
            "screen."
    } else {
        "Your phone is allowed to stop $appName in the background. Excluding it from battery " +
            "optimisation makes that much less likely during a long ride."
    }

    /** Label for the action that goes with [advice]. */
    fun actionLabel(context: Context): String =
        if (isExempt(context)) "Open app info" else "Open battery settings"

    /**
     * Opens the screen that matches the advice.
     *
     * The battery-optimisation *list* rather than the direct "allow this app" prompt: the prompt
     * needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which is a permission app stores treat as
     * sensitive and which this app has no other reason to hold. The rider does one extra tap and
     * the app asks for nothing.
     */
    fun openSettings(context: Context): Boolean {
        val intent = if (isExempt(context)) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.applicationContext.packageName, null))
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
