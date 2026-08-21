// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reports why previous processes of this app ended, on the launch that follows.
 *
 * The complement to [CrashRecovery], which can only describe a death that came with an uncaught
 * exception. The deaths that hurt most in the field are the silent ones: the process is gone, the
 * TFT goes dark mid-ride, and every layer above reports it in its own vocabulary - "the T-Box
 * ended Android Auto", "the session no longer accepts video frames", a Wi-Fi association that
 * dies at full signal - none of which says that the app simply stopped existing.
 *
 * That is what happened on 2026-07-30 (OnePlus, 1.1.24): the companion process was killed 26
 * minutes into a Ride Dashboard session with no Java crash, no native crash and no ANR anywhere
 * in Sentry, which left "why" unanswerable from any log we had. Android knew all along -
 * [ActivityManager.getHistoricalProcessExitReasons] keeps the record - we just never asked.
 *
 * This is the right instrument for a failure that needs a motorcycle and twenty-five minutes to
 * reproduce: it needs nobody attached to the phone, nothing running, and no reproduction at all.
 * Android keeps the history across app updates, so the first launch after installing this reports
 * deaths that happened before it existed.
 *
 * Read the reason with care in one respect: OEM "app management" on phones that are aggressive
 * about background processes frequently reports a kill as `USER_REQUESTED` or `OTHER` rather than
 * `LOW_MEMORY`, so a low-memory death is not always labelled as one. The PSS/RSS figures printed
 * alongside are what distinguishes them.
 */
object ProcessExitReport {
    private const val PREFERENCES = "moto-hub-process-exits"
    private const val KEY_LAST_REPORTED_AT = "last-reported-exit-at"

    /**
     * How far back to look. Generous rather than minimal: the history survives app updates, and
     * a rider who updates before exporting a log should still carry the death that prompted it.
     */
    private const val MAX_RECORDS = 10

    private const val KEY_ACKNOWLEDGED_KILL_AT = "acknowledged-kill-at"

    /**
     * The most recent death that the phone inflicted on this app rather than the app inflicting
     * on itself, when the rider has not been told about it yet.
     *
     * Read by the home screen: a rider whose ride ended mid-session deserves to know it was the
     * phone that stopped MOTO-HUB and not a fault in it - and, more usefully, what to change so
     * it stops happening. Only a death the rider has not acknowledged appears, so the notice is
     * shown once per occurrence rather than becoming another banner people learn to ignore.
     */
    @Volatile
    var unacknowledgedSystemKill: SystemKill? = null
        private set

    /** A death caused by the system, in the terms the rider's notice needs. */
    data class SystemKill(
        val at: Long,
        val reason: String,
        val description: String?,
        val rssMegabytes: Long
    )

    /** Stops the current [unacknowledgedSystemKill] from being shown again. */
    fun acknowledgeSystemKill(context: Context) {
        val kill = unacknowledgedSystemKill ?: return
        unacknowledgedSystemKill = null
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ACKNOWLEDGED_KILL_AT, kill.at)
            .apply()
    }

    /** Reports every process death not reported before, oldest first. */
    fun reportPreviousExits(context: Context) {
        val appContext = context.applicationContext
        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return
        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val lastReportedAt = preferences.getLong(KEY_LAST_REPORTED_AT, 0L)

        val exits = runCatching {
            // null package name means this app's own processes.
            activityManager.getHistoricalProcessExitReasons(null, 0, MAX_RECORDS)
        }.getOrElse { failure ->
            ProjectionEventLog.debug(
                "APP",
                "Android would not say how previous processes ended: ${failure.message}."
            )
            return
        }

        // The notice is decided from the whole history, not just the unreported part: the death
        // that matters may already have been logged by an earlier launch that the rider never
        // opened, and it is still the thing they need to be told about.
        val acknowledgedAt = preferences.getLong(KEY_ACKNOWLEDGED_KILL_AT, 0L)
        unacknowledgedSystemKill = exits
            .filter { it.timestamp > acknowledgedAt && isSystemKill(it.reason) }
            .maxByOrNull { it.timestamp }
            ?.let { exit ->
                SystemKill(
                    at = exit.timestamp,
                    reason = reasonName(exit.reason),
                    description = exit.description?.takeIf(String::isNotBlank),
                    rssMegabytes = exit.rss / 1024
                )
            }

        val fresh = exits.filter { it.timestamp > lastReportedAt }.sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        fresh.forEach(::report)
        preferences.edit().putLong(KEY_LAST_REPORTED_AT, fresh.last().timestamp).apply()
    }

    /**
     * Whether the phone took the process away, as opposed to the process ending for a reason of
     * its own. Narrower than [isWorthInvestigating]: a crash or an ANR is our fault and telling
     * the rider to change a phone setting would be misdirection. These are the ones where the
     * app was working and the system stopped it anyway.
     */
    private fun isSystemKill(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_FREEZER -> true
        else -> false
    }

    private fun report(exit: ApplicationExitInfo) {
        val when_ = TIMESTAMP_FORMAT.format(Date(exit.timestamp))
        val detail = buildString {
            append("A previous MOTO-HUB process ended at $when_: ${reasonName(exit.reason)}")
            exit.description?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
            append(". process=${exit.processName}, importance=${importanceName(exit.importance)}")
            // The memory footprint at the moment of death is what separates a kill for memory
            // pressure from a kill for policy, which the reason alone often cannot.
            append(", pss=${exit.pss / 1024}MB, rss=${exit.rss / 1024}MB")
            if (exit.status != 0) append(", status=${exit.status}")
            append(".")
        }
        if (isWorthInvestigating(exit.reason)) {
            ProjectionEventLog.warning("APP", detail)
        } else {
            ProjectionEventLog.debug("APP", detail)
        }
    }

    /**
     * Whether a death is the app's problem or simply how Android works. An ordinary stop, a
     * package update or a permission change ends the process by design and should not read like
     * a fault in a rider's log; everything else is a lead.
     */
    private fun isWorthInvestigating(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> false
        else -> true
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "the process exited on its own"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed because the phone was low on memory"
        ApplicationExitInfo.REASON_CRASH -> "an unhandled Java exception"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "a native crash"
        ApplicationExitInfo.REASON_ANR -> "an ANR (the app stopped responding)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "the process failed to initialise"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED ->
            "a user or system request (this is also how several OEM battery managers kill apps)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "the user stopped the app"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depended on died"
        ApplicationExitInfo.REASON_FREEZER -> "the freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "a package state change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "the app being updated"
        ApplicationExitInfo.REASON_OTHER ->
            "an unclassified system kill (OEM process management usually lands here)"
        else -> "reason $reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> "importance $importance"
    }

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
}
