package io.motohub.android.tbox

import android.content.Context

/**
 * Remembers which wire protocol a motorcycle turned out to speak, keyed by its SSID.
 *
 * Discovery can answer this question, but only slowly: a dash that speaks Yunmo is found by first
 * letting EasyConn fail, which costs two 15-second NSD windows plus wake probes before anything
 * else is tried. A rider who pins the profile by hand connects in seconds; a rider on Auto waits
 * nearly a minute for the same result, every single time. Since the dash's answer does not change
 * between rides, it is worth writing down.
 *
 * Deliberately separate from the rider's own profile override: this is something the app learned,
 * not something the rider chose, so it must never silently rewrite a Garage setting. It is also
 * only ever a *shortcut* — [TBoxTransportFamily.EASYCONN] is never recorded, because that is the
 * path taken anyway and remembering it could only ever slow a later session down or pin a bike to
 * the wrong family after a firmware change.
 */
class TBoxProtocolMemory(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /** The family this SSID was last seen speaking, or null when nothing was learned. */
    fun learnedFamily(ssid: String): TBoxTransportFamily? {
        if (ssid.isBlank()) return null
        val stored = preferences.getString(key(ssid), null) ?: return null
        return TBoxTransportFamily.entries.firstOrNull { it.name == stored }
    }

    /**
     * Records a family discovery settled on. EasyConn is stored as "nothing learned" so the
     * default path stays the default.
     */
    fun remember(ssid: String, family: TBoxTransportFamily) {
        if (ssid.isBlank()) return
        val editor = preferences.edit()
        if (family == TBoxTransportFamily.EASYCONN) {
            editor.remove(key(ssid))
        } else {
            editor.putString(key(ssid), family.name)
        }
        editor.apply()
    }

    /** Drops what was learned, so the next session rediscovers from scratch. */
    fun forget(ssid: String) {
        if (ssid.isBlank()) return
        preferences.edit().remove(key(ssid)).apply()
    }

    private fun key(ssid: String): String = "$ssid:family"

    private companion object {
        const val PREFERENCES_NAME = "tbox_protocol_memory"
    }
}
