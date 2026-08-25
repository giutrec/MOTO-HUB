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
    val bluetoothClockSync: Boolean = false,
    /**
     * Which protocol the handlebar remote speaks (HandlebarInputMode.id: "avrcp" or "hid"),
     * carried for the same reason as the Bluetooth clock above rather than inside the handlebar
     * block: CORE ships this picker in its own settings too, so a companion that predates the
     * field must not silently reset a rider who selected HID there. Only the choice travels -
     * the Accessibility Service each edition needs for HID is granted per app, in system
     * settings, and cannot be handed over.
     */
    val handlebarInputModeProvided: Boolean = false,
    val handlebarInputMode: String = "",
    /**
     * The TFT pixels the dash's own furniture covers, taught with the companion's calibration
     * ruler. CORE composites Android Auto against ITS copy of these margins and the companion
     * composites the Ride Dashboard against its own, so until this travelled the same bike was
     * projected two different ways by the two halves: field log 7efdfa33 (2026-08-25) shows CORE
     * insetting Android Auto to a 680x408 viewport for a right margin of 120 while the Ride
     * Dashboard, one process away, filled all 800x480 of the same panel.
     *
     * [screenMarginsProvided] is not ceremony, and it is deliberately stricter than the other
     * gates here: it is true only when the rider has actually SAVED margins in the companion.
     * CORE ships the same ruler, so a companion whose store is empty must not push four zeros
     * over a calibration the rider did in CORE - "I never taught this" and "I taught it to be
     * zero" are different answers, and only the store can tell them apart.
     */
    val screenMarginsProvided: Boolean = false,
    val screenMarginTop: Int = 0,
    val screenMarginBottom: Int = 0,
    val screenMarginLeft: Int = 0,
    val screenMarginRight: Int = 0
) : Parcelable
