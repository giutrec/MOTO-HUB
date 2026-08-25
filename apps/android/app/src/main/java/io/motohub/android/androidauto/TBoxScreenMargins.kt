// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import android.content.Context
import android.content.SharedPreferences
import io.motohub.android.session.MotorcycleProfile

/** Physical TFT pixels reserved by motorcycle-owned UI around the projection area. */
data class TBoxScreenMargins(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0
) {
    init {
        require(top >= 0 && bottom >= 0 && left >= 0 && right >= 0) {
            "Screen margins cannot be negative"
        }
        require(top <= MAX && bottom <= MAX && left <= MAX && right <= MAX) {
            "Screen margins exceed the supported limit"
        }
    }

    fun inset(geometry: DisplayGeometry): DisplayGeometry = DisplayGeometry(
        width = (geometry.width - left - right).coerceAtLeast(1),
        height = (geometry.height - top - bottom).coerceAtLeast(1)
    )

    companion object {
        const val MAX = 200
        val NONE = TBoxScreenMargins()
    }
}

/**
 * [TBoxScreenMarginsStore.loadStored]'s decision, without a Context so it can be tested: the four
 * edges as they came out of the preferences, with [TBoxScreenMarginsStore.UNSET] standing for
 * "no value stored". Null unless ALL FOUR are present - a half-written record is not a teaching,
 * and filling its gaps with zeros is how a stored zero and a missing value become the same answer
 * on the companion boundary, which is exactly what must not happen there.
 */
internal fun storedMargins(edges: List<Int>): TBoxScreenMargins? {
    if (edges.size != 4 || edges.any { it == TBoxScreenMarginsStore.UNSET }) return null
    return TBoxScreenMargins(top = edges[0], bottom = edges[1], left = edges[2], right = edges[3])
}

/** Stores margins per motorcycle so one bike's panel furniture never affects another's. */
class TBoxScreenMarginsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(
        profile: MotorcycleProfile,
        defaults: TBoxScreenMargins = TBoxScreenMargins.NONE
    ): TBoxScreenMargins = TBoxScreenMargins(
        top = preferences.getInt(key(profile.ssid, "top"), defaults.top),
        bottom = preferences.getInt(key(profile.ssid, "bottom"), defaults.bottom),
        left = preferences.getInt(key(profile.ssid, "left"), defaults.left),
        right = preferences.getInt(key(profile.ssid, "right"), defaults.right)
    )

    fun save(profile: MotorcycleProfile, margins: TBoxScreenMargins) {
        preferences.edit()
            .putInt(key(profile.ssid, "top"), margins.top)
            .putInt(key(profile.ssid, "bottom"), margins.bottom)
            .putInt(key(profile.ssid, "left"), margins.left)
            .putInt(key(profile.ssid, "right"), margins.right)
            .apply()
    }

    /**
     * What this store actually holds for [profile], or null when the rider has never taught it.
     *
     * [load] cannot answer that question: it folds "nothing saved" into the model profile's
     * defaults, which is right for compositing and wrong for handing the value to the other half
     * of the pair. Four zeros returned because nothing was ever taught would overwrite, on the
     * companion boundary, a calibration the rider did in the other app - and zero margins are a
     * legitimate answer a rider can also teach, so the two cases cannot be told apart afterwards.
     */
    fun loadStored(profile: MotorcycleProfile): TBoxScreenMargins? =
        storedMargins(EDGES.map { edge -> preferences.getInt(key(profile.ssid, edge), UNSET) })

    fun reset(profile: MotorcycleProfile) {
        preferences.edit()
            .remove(key(profile.ssid, "top"))
            .remove(key(profile.ssid, "bottom"))
            .remove(key(profile.ssid, "left"))
            .remove(key(profile.ssid, "right"))
            .apply()
    }

    /**
     * Notifies [listener] whenever [save] or [reset] changes any margin for any SSID -
     * callers should use [belongsToMotorcycle] to filter for the SSID they care about. Lets
     * an active projection session apply a margin change picked in
     * [io.motohub.android.feature.garage.MotorcycleDetailsScreen] without restarting.
     */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** True if the preference [key] reported by [addListener] belongs to [ssid]. */
    fun belongsToMotorcycle(key: String?, ssid: String): Boolean = key?.startsWith("$ssid:") == true

    private fun key(ssid: String, edge: String): String = "$ssid:$edge"

    internal companion object {
        private const val PREFERENCES_NAME = "tbox_screen_margins"

        /** Order matters: [loadStored] reads the edges positionally, and so does [storedMargins]. */
        private val EDGES = listOf("top", "bottom", "left", "right")

        /** Outside the 0..[TBoxScreenMargins.MAX] range a saved margin can occupy. */
        const val UNSET = -1
    }
}
