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

    /** The gesture bound to [press], learned or assumed; null when nothing is known yet. */
    fun gestureFor(context: Context, press: PhysicalPress): HandlebarGesture? {
        val stored = preferences(context).getString(press.id, null)
        if (stored != null) return HandlebarGesture.entries.firstOrNull { it.id == stored }
        // A learned binding elsewhere overrides an assumption here: if the rider taught us
        // that VOLUME_UP is something else, this press no longer owns it.
        val assumed = ASSUMED[press] ?: return null
        val takenByAnother = PhysicalPress.entries.any { other ->
            other != press && preferences(context).getString(other.id, null) == assumed.id
        }
        return assumed.takeIf { !takenByAnother }
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
        val editor = preferences(context).edit()
        PhysicalPress.entries
            .filter { it != press && preferences(context).getString(it.id, null) == gesture.id }
            .forEach { editor.remove(it.id) }
        editor.putString(press.id, gesture.id).apply()
    }

    /** Marks a press as absent on this handlebar, so its row can be hidden. */
    fun recordMissing(context: Context, press: PhysicalPress) {
        preferences(context).edit().putString(press.id, MISSING).apply()
    }

    fun isMissing(context: Context, press: PhysicalPress): Boolean =
        preferences(context).getString(press.id, null) == MISSING

    /** Buttons worth showing: those with at least one press that exists on this handlebar. */
    fun presentButtons(context: Context): List<HandlebarButton> =
        HandlebarButton.entries.filter { button ->
            PhysicalPress.entries.any { it.button == button && !isMissing(context, it) }
        }

    fun isCalibrated(context: Context): Boolean =
        preferences(context).all.keys.any { key -> PhysicalPress.entries.any { it.id == key } }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private const val MISSING = "__missing__"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
