// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import android.content.Context

class AndroidAutoNightModeStore(private val context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): Boolean = preferences.getBoolean(KEY_NIGHT, false)

    fun save(isNight: Boolean) {
        preferences.edit().putBoolean(KEY_NIGHT, isNight).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "android_auto_night_mode"
        const val KEY_NIGHT = "night"
    }
}
