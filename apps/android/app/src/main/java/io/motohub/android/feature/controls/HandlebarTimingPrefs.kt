// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import android.content.Context

enum class DoubleTapDelay(val millis: Long, val label: String) {
    FAST(200L, "200 ms - snappier singles"),
    NORMAL(300L, "300 ms - balanced"),
    SLOW(450L, "450 ms - forgiving doubles")
}

enum class SelectHoldDelay(val millis: Long, val label: String) {
    SHORT(500L, "500 ms - quicker hold"),
    NORMAL(600L, "600 ms - balanced"),
    LONG(800L, "800 ms - deliberate hold")
}

/** Global handlebar timing. It applies to the rider's interaction style, not a motorcycle profile. */
object HandlebarTimingPrefs {
    private const val PREFS = "handlebar_timing"
    private const val DOUBLE_TAP_MILLIS = "double_tap_millis"
    private const val SELECT_HOLD_MILLIS = "select_hold_millis"
    private const val EAGER_SINGLES = "eager_singles"
    private const val HOLDS_ENABLED = "holds_enabled"

    /**
     * When true (default), a single press fires immediately instead of waiting out the
     * double-tap window; a second press inside the window still fires the double action.
     * The cost is that a double also runs the single first — harmless for cursor movement,
     * and the reason open-cfmoto's field testing settled on eager: waiting made every OK
     * and every knob step land ~300ms late, which reads as "dead buttons" at speed.
     */
    fun eagerSingles(context: Context): Boolean =
        preferences(context).getBoolean(EAGER_SINGLES, true)

    fun setEagerSingles(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(EAGER_SINGLES, enabled).apply()
    }

    /**
     * When false, no press is ever classified as a hold — every release is a tap or double.
     * The escape hatch for pods that need a long physical press per click, where hold
     * detection would eat every press.
     */
    fun holdsEnabled(context: Context): Boolean =
        preferences(context).getBoolean(HOLDS_ENABLED, true)

    fun setHoldsEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(HOLDS_ENABLED, enabled).apply()
    }

    fun doubleTap(context: Context): DoubleTapDelay {
        val stored = preferences(context).getLong(DOUBLE_TAP_MILLIS, DoubleTapDelay.NORMAL.millis)
        return DoubleTapDelay.entries.firstOrNull { it.millis == stored } ?: DoubleTapDelay.NORMAL
    }

    fun setDoubleTap(context: Context, value: DoubleTapDelay) {
        preferences(context).edit().putLong(DOUBLE_TAP_MILLIS, value.millis).apply()
    }

    fun selectHold(context: Context): SelectHoldDelay {
        val stored = preferences(context).getLong(SELECT_HOLD_MILLIS, SelectHoldDelay.NORMAL.millis)
        return SelectHoldDelay.entries.firstOrNull { it.millis == stored } ?: SelectHoldDelay.NORMAL
    }

    fun setSelectHold(context: Context, value: SelectHoldDelay) {
        preferences(context).edit().putLong(SELECT_HOLD_MILLIS, value.millis).apply()
    }

    fun doubleTapMillis(context: Context): Long = doubleTap(context).millis

    fun selectHoldMillis(context: Context): Long = selectHold(context).millis

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )
}
