package io.motohub.android.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A snapshot of the caller's Android-Auto-affecting settings, sent to CORE right before
 * startFullSession so the session CORE runs honors the settings the user configured in the
 * companion app (whose SharedPreferences CORE cannot read directly). Enums travel as their
 * `.name`; CORE parses them defensively and falls back to its own value on any mismatch.
 */
@Parcelize
data class AndroidAutoSettingsParcel(
    val resolutionMode: String,
    val aspectMatching: String,
    val videoQuality: String,
    val disableTouchscreen: Boolean,
    val seamlessResume: Boolean,
    val nightMode: Boolean,
    /** Per-motorcycle AndroidAutoDisplayMode.name (Garage setting) — Core stores this keyed by
     *  motorcycle id/ssid, separately from the global settings above. Empty when unknown. */
    val displayMode: String = "",
    /**
     * Handlebar sync. Appended at the parcel's end so an OLD caller's parcel deserializes these
     * as false/empty/zero on a NEW Core: [handlebarSyncProvided] then gates the whole block, so
     * Core keeps its own handlebar configuration for callers that predate the sync. A NEW
     * caller against an OLD Core is harmless (extra trailing fields are never read).
     */
    val handlebarSyncProvided: Boolean = false,
    val handlebarControlsEnabled: Boolean = false,
    /** "gestureId=actionId" pairs joined by ',' (HandlebarGesture/HandlebarAction ids). */
    val handlebarMapping: String = "",
    val handlebarDoubleTapMillis: Long = 0L,
    val handlebarSelectHoldMillis: Long = 0L,
    /**
     * Appended after the first handlebar block (same trailing-field compatibility rules).
     * Gated by [handlebarSyncProvided] like the rest; an old caller's parcel deserializes
     * these as true/true/"" — the shipped defaults — so Core behaves as if unconfigured.
     */
    val handlebarEagerSingles: Boolean = true,
    val handlebarHoldsEnabled: Boolean = true,
    /** "pressId=storedValue" pairs joined by ',' (PhysicalPress ids; the value is a
     *  HandlebarGesture id, the `__missing__` marker, or "" for an unbound press). */
    val handlebarCalibration: String = "",
    /**
     * The Bluetooth dash-clock channel, which runs in CORE because that is where the T-Box
     * transport lives - so a rider who flips it in the companion app is configuring a process that
     * never reads it. Mirrored here for the same reason the handlebar block above is.
     *
     * [bluetoothClockSyncProvided] is not ceremony: CORE ships this toggle in its own settings
     * too, so a caller that predates these fields would deserialize `false` and silently switch
     * off a rider who had enabled it in CORE directly. The gate keeps an old companion from
     * overwriting a choice it does not know about.
     */
    val bluetoothClockSyncProvided: Boolean = false,
    val bluetoothClockSync: Boolean = false
) : Parcelable
