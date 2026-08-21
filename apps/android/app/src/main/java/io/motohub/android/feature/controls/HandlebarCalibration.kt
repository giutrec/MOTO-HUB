// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import android.content.Context

/** The buttons a handlebar can have. Which ones exist differs per motorcycle. */
enum class HandlebarButton(val id: String, val label: String, val glyph: String) {
    UP("up", "Up", "▲"),
    DOWN("down", "Down", "▼"),
    LEFT("left", "Left", "◀"),
    RIGHT("right", "Right", "▶"),
    SELECT("select", "Select / OK", "●")
}

/** How a button can be pressed. Every button offers the same three. */
enum class PressKind(val id: String, val label: String) {
    PRESS("press", "Press"),
    DOUBLE("double", "Double press"),
    HOLD("hold", "Hold")
}

/**
 * One thing the rider's hand can do: a button and a way of pressing it.
 *
 * This is deliberately NOT the same as [HandlebarGesture], which names the Bluetooth command
 * that carried the press. The two do not line up, and cannot be assumed to: on a CFMOTO 700MT
 * a HELD up rocker arrives as the "next track" command (confirmed on the bike, 2026-07-29),
 * while the same command could be a plain LEFT press on a handlebar that has separate
 * left/right buttons. Only the rider can settle it, by performing each press once.
 */
enum class PhysicalPress(val button: HandlebarButton, val kind: PressKind) {
    UP_PRESS(HandlebarButton.UP, PressKind.PRESS),
    UP_DOUBLE(HandlebarButton.UP, PressKind.DOUBLE),
    UP_HOLD(HandlebarButton.UP, PressKind.HOLD),
    DOWN_PRESS(HandlebarButton.DOWN, PressKind.PRESS),
    DOWN_DOUBLE(HandlebarButton.DOWN, PressKind.DOUBLE),
    DOWN_HOLD(HandlebarButton.DOWN, PressKind.HOLD),
    LEFT_PRESS(HandlebarButton.LEFT, PressKind.PRESS),
    LEFT_DOUBLE(HandlebarButton.LEFT, PressKind.DOUBLE),
    LEFT_HOLD(HandlebarButton.LEFT, PressKind.HOLD),
    RIGHT_PRESS(HandlebarButton.RIGHT, PressKind.PRESS),
    RIGHT_DOUBLE(HandlebarButton.RIGHT, PressKind.DOUBLE),
    RIGHT_HOLD(HandlebarButton.RIGHT, PressKind.HOLD),
    SELECT_PRESS(HandlebarButton.SELECT, PressKind.PRESS),
    SELECT_DOUBLE(HandlebarButton.SELECT, PressKind.DOUBLE),
    SELECT_HOLD(HandlebarButton.SELECT, PressKind.HOLD);

    val id: String get() = "${button.id}_${kind.id}"
    val label: String get() = "${button.label} · ${kind.label.lowercase()}"
}

/**
 * Which motorcycle command answers each thing the rider's hand does, learned by asking.
 *
 * Only two bindings are safe to assume on every dashboard: the volume steps are the rocker's
 * short presses, and play/pause is the select button. Everything else - above all the track
 * commands, which are a held rocker on one handlebar and a left button on another - is left
 * unbound until the rider teaches it.
 */
object HandlebarCalibration {
    private const val PREFERENCES = "handlebar_calibration"

    private val ASSUMED: Map<PhysicalPress, HandlebarGesture> = mapOf(
        PhysicalPress.UP_PRESS to HandlebarGesture.VOLUME_UP,
        PhysicalPress.UP_DOUBLE to HandlebarGesture.VOLUME_UP_DOUBLE,
        PhysicalPress.DOWN_PRESS to HandlebarGesture.VOLUME_DOWN,
        PhysicalPress.DOWN_DOUBLE to HandlebarGesture.VOLUME_DOWN_DOUBLE,
        PhysicalPress.SELECT_PRESS to HandlebarGesture.ENTER,
        PhysicalPress.SELECT_DOUBLE to HandlebarGesture.ENTER_DOUBLE,
        PhysicalPress.SELECT_HOLD to HandlebarGesture.ENTER_LONG
    )

    /** What the CURRENT motorcycle stores for [press]: gesture id, [MISSING], or null.
     *  All reads and writes go through [MotorcycleScope] — each bike keeps its own handlebar. */
    private fun stored(context: Context, press: PhysicalPress): String? =
        MotorcycleScope.getString(preferences(context), context, press.id, null)

    /** The gesture bound to [press], learned or assumed; null when nothing is known yet. */
    fun gestureFor(context: Context, press: PhysicalPress): HandlebarGesture? {
        val stored = stored(context, press)
        if (stored != null) return HandlebarGesture.entries.firstOrNull { it.id == stored }
        // Assumptions cover the OUT-OF-BOX experience only. Once the rider has taught any
        // press, the taught set IS this handlebar: an assumption surviving next to taught
        // bindings kept a phantom volume rocker alive on a dash that never delivers one
        // (CFDL16, field report 2026-07-30) — and that phantom kept the volume pin on,
        // which turned the PHONE's own volume keys into fake handlebar presses.
        if (isCalibrated(context)) return null
        return ASSUMED[press]
    }

    /** The press bound to [gesture], for naming a gesture wherever one is shown. */
    fun pressFor(context: Context, gesture: HandlebarGesture): PhysicalPress? =
        PhysicalPress.entries.firstOrNull { gestureFor(context, it) == gesture }

    /**
     * Records that the motorcycle sends [gesture] for [press]. Any other press previously
     * claiming that gesture is released - one command, one press, so re-teaching can never
     * leave two cells pointing at the same button.
     */
    fun record(context: Context, press: PhysicalPress, gesture: HandlebarGesture) {
        val p = preferences(context)
        PhysicalPress.entries
            .filter { it != press && stored(context, it) == gesture.id }
            // Written as UNBOUND, not removed: the conflicting binding may live in the global
            // fallback (pre-garage calibration), which a scoped remove cannot shadow.
            .forEach { MotorcycleScope.putString(p, context, it.id, UNBOUND) }
        MotorcycleScope.putString(p, context, press.id, gesture.id)
    }

    /** Marks a press as absent on this handlebar, so its row can be hidden. */
    fun recordMissing(context: Context, press: PhysicalPress) {
        MotorcycleScope.putString(preferences(context), context, press.id, MISSING)
    }

    fun isMissing(context: Context, press: PhysicalPress): Boolean =
        stored(context, press) == MISSING

    /** Buttons worth showing: those with at least one press that exists on this handlebar. */
    fun presentButtons(context: Context): List<HandlebarButton> =
        HandlebarButton.entries.filter { button ->
            PhysicalPress.entries.any { it.button == button && !isMissing(context, it) }
        }

    fun isCalibrated(context: Context): Boolean =
        PhysicalPress.entries.any { MotorcycleScope.contains(preferences(context), context, it.id) }

    /**
     * Observed - not taught - that this motorcycle's volume rocker never reaches the phone.
     *
     * Deliberately NOT stored as [MISSING] on the volume presses, which is what a straight port
     * of OpenCfMoto's ButtonPresence would do. Writing any [PhysicalPress] key flips
     * [isCalibrated] to true, and [gestureFor] then stops handing out [ASSUMED] for *every* press
     * - so inferring an absent rocker would silently unbind the select button's play/pause on a
     * rider who never opened the wizard. The key below is not a press id, so [isCalibrated],
     * [export] and [import] (all of which iterate [PhysicalPress]) are untouched.
     *
     * An inference is also weaker than a rider's statement, and is treated as such: it only
     * suppresses the volume pin, it never hides a row, and the first genuine volume press
     * clears it.
     */
    fun noteVolumeRockerSilent(context: Context) {
        MotorcycleScope.putString(preferences(context), context, KEY_VOLUME_ROCKER_SILENT, "true")
    }

    /** The rocker spoke after all: drop the inference so the pin comes back. */
    fun clearVolumeRockerSilent(context: Context) {
        if (!isVolumeRockerSilent(context)) return
        MotorcycleScope.putString(preferences(context), context, KEY_VOLUME_ROCKER_SILENT, "")
    }

    fun isVolumeRockerSilent(context: Context): Boolean =
        MotorcycleScope.getString(preferences(context), context, KEY_VOLUME_ROCKER_SILENT, null) == "true"

    /** Not a [PhysicalPress] id, on purpose - see [noteVolumeRockerSilent]. Internal so the
     *  test can assert that separation rather than trusting the comment. */
    internal const val KEY_VOLUME_ROCKER_SILENT = "observed_volume_rocker_silent"

    /**
     * Serializes the CURRENT motorcycle's taught bindings as "pressId=value" pairs joined by
     * ',' for the companion→Core session sync. Values are gesture ids, [MISSING], or
     * [UNBOUND]; presses with nothing stored are omitted.
     */
    fun export(context: Context): String =
        PhysicalPress.entries.mapNotNull { press ->
            stored(context, press)?.let { value -> "${press.id}=$value" }
        }.joinToString(",")

    /** Applies a companion's [export]ed calibration to the CURRENT motorcycle. */
    fun import(context: Context, encoded: String) {
        if (encoded.isBlank()) return
        val p = preferences(context)
        encoded.split(',').forEach { entry ->
            parseCalibrationEntry(entry)?.let { (press, value) ->
                MotorcycleScope.putString(p, context, press.id, value)
            }
        }
    }

    internal const val MISSING = "__missing__"

    /** A binding explicitly released during re-teaching: no gesture, but not "button absent".
     *  [gestureFor] resolves it to null without falling back to [ASSUMED] or the global key. */
    internal const val UNBOUND = ""

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

/**
 * One "pressId=value" pair from a serialized calibration ([HandlebarCalibration.export]).
 * Null for an unknown press or a value that is neither a gesture id nor one of the markers —
 * a defensive parse, since the string crosses the app boundary over IPC.
 */
internal fun parseCalibrationEntry(entry: String): Pair<PhysicalPress, String>? {
    val pressId = entry.substringBefore('=', "")
    val value = entry.substringAfter('=', "")
    val press = PhysicalPress.entries.firstOrNull { it.id == pressId } ?: return null
    val valid = value == HandlebarCalibration.MISSING ||
        value == HandlebarCalibration.UNBOUND ||
        HandlebarGesture.entries.any { it.id == value }
    return if (valid) press to value else null
}
