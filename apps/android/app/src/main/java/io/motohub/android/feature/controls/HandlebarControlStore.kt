package io.motohub.android.feature.controls

import android.content.Context

enum class HandlebarAction(val id: String, val label: String) {
    NONE("none", "Do nothing"),
    SCROLL_FORWARD("scrollForward", "Rotary forward"),
    SCROLL_BACK("scrollBack", "Rotary back"),
    DPAD_UP("dpadUp", "D-pad up"),
    DPAD_DOWN("dpadDown", "D-pad down"),
    DPAD_LEFT("dpadLeft", "D-pad left"),
    DPAD_RIGHT("dpadRight", "D-pad right"),
    SELECT("select", "Select / OK"),
    BACK("back", "Back"),
    HOME("home", "Home"),
    ASSISTANT("assistant", "Assistant"),
    NAV_1("nav1", "Navigate → place 1"),
    NAV_2("nav2", "Navigate → place 2"),
    NAV_3("nav3", "Navigate → place 3")
}

enum class HandlebarGesture(
    val id: String,
    val label: String,
    val transportHint: String,
    val defaultAction: HandlebarAction
) {
    VOLUME_UP("volumeUp", "Up press", "Bluetooth absolute-volume change", HandlebarAction.SCROLL_BACK),
    VOLUME_UP_DOUBLE("volumeUpDouble", "Up double press", "Larger absolute-volume change", HandlebarAction.HOME),
    VOLUME_DOWN("volumeDown", "Down press", "Bluetooth absolute-volume change", HandlebarAction.SCROLL_FORWARD),
    VOLUME_DOWN_DOUBLE("volumeDownDouble", "Down double press", "Larger absolute-volume change", HandlebarAction.BACK),
    ENTER("enter", "Select tap - Enter / Star", "Bluetooth play/pause command", HandlebarAction.SELECT),
    ENTER_LONG("enterLong", "Select hold - Enter / Star", "Requires a real Bluetooth key-up", HandlebarAction.HOME),
    ENTER_DOUBLE("enterDouble", "Select double tap - Enter / Star", "Two presses inside the double-tap window", HandlebarAction.BACK),
    TRACK_BACK("trackBack", "Backward - Left", "Bluetooth previous-track command", HandlebarAction.SCROLL_BACK),
    TRACK_BACK_DOUBLE("trackBackDouble", "Backward double tap - Left", "Two previous-track presses inside the window", HandlebarAction.HOME),
    // Only reachable on dashboards that report a key release separated in time from the press;
    // see HandlebarControlStore.dashboardReportsHolds. Default NONE: on a dashboard that does
    // not, these can never fire, and a default action would advertise something the hardware
    // may not deliver.
    TRACK_BACK_LONG("trackBackLong", "Backward hold - Left", "Requires a real Bluetooth key-up", HandlebarAction.NONE),
    TRACK_FORWARD("trackForward", "Forward - Right", "Bluetooth next-track command", HandlebarAction.SCROLL_FORWARD),
    TRACK_FORWARD_DOUBLE("trackForwardDouble", "Forward double tap - Right", "Two next-track presses inside the window", HandlebarAction.BACK),
    TRACK_FORWARD_LONG("trackForwardLong", "Forward hold - Right", "Requires a real Bluetooth key-up", HandlebarAction.NONE)
}

object HandlebarControlStore {
    private const val PREFERENCES = "handlebar_controls"
    private const val ENABLED = "enabled"
    private const val MANAGED_BY_COMPANION = "managed_by_companion"
    private const val DASHBOARD_REPORTS_HOLDS = "dashboard_reports_holds"
    private const val CALIBRATION_PREFIX = "calibrated_"
    /** Bumped when [defaultAction] values change — auto-migrates stored overrides. */
    private const val DEFAULTS_VER = 2
    private const val KEY_DEFAULTS_VER = "defaults_ver"

    /**
     * ON by default (changed from OFF, 2026-07-30): capture is the feature's whole point, and
     * with OFF the rider had to discover a buried switch before the buttons did anything.
     * A rider who explicitly turned it off has a stored false, which this default never
     * overrides. Scoped per motorcycle, like the mapping it gates.
     */
    fun isEnabled(context: Context): Boolean =
        MotorcycleScope.getBoolean(preferences(context), context, ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        MotorcycleScope.putBoolean(preferences(context), context, ENABLED, enabled)
    }

    /**
     * True once a companion app (PRO/ADV) has mirrored its handlebar configuration over IPC.
     * From that point the companion is the source of truth — it re-pushes its values at every
     * session start, silently overwriting anything edited here. The Core settings screen uses
     * this to tell the rider where the real switch lives instead of appearing to ignore them.
     */
    fun isManagedByCompanion(context: Context): Boolean =
        preferences(context).getBoolean(MANAGED_BY_COMPANION, false)

    fun setManagedByCompanion(context: Context, managed: Boolean) {
        preferences(context).edit().putBoolean(MANAGED_BY_COMPANION, managed).apply()
    }

    /**
     * True once this phone has seen the dashboard report a key RELEASE for a previous/next
     * press. Until then a hold on those buttons is undetectable - the press arrives, nothing
     * says when it ended - so [HandlebarGesture.TRACK_BACK_LONG]/[HandlebarGesture.TRACK_FORWARD_LONG]
     * stay dormant rather than guessing. The first release seen switches them on for good:
     * the motorcycle proves the capability instead of the app assuming it.
     */
    /** Scoped per motorcycle: it is a learned property of the DASH, not of the phone. */
    fun dashboardReportsHolds(context: Context): Boolean =
        MotorcycleScope.getBoolean(preferences(context), context, DASHBOARD_REPORTS_HOLDS, false)

    fun setDashboardReportsHolds(context: Context, reports: Boolean) {
        MotorcycleScope.putBoolean(preferences(context), context, DASHBOARD_REPORTS_HOLDS, reports)
    }

    fun action(context: Context, gesture: HandlebarGesture): HandlebarAction {
        ensureDefaultsMigrated(context)
        val stored = MotorcycleScope.getString(preferences(context), context, gesture.id, null)
        return HandlebarAction.entries.firstOrNull { it.id == stored } ?: gesture.defaultAction
    }

    fun setAction(context: Context, gesture: HandlebarGesture, action: HandlebarAction) {
        MotorcycleScope.putString(preferences(context), context, gesture.id, action.id)
    }

    /**
     * Restores every gesture to its default action for the CURRENT motorcycle (other bikes keep
     * theirs). Written as EXPLICIT defaults, not key removals: a removal only uncovers whatever
     * global value a pre-garage install left underneath, which made "reset to defaults" restore
     * the rider's old custom map instead. The enabled flag is a separate choice and survives.
     */
    fun reset(context: Context) {
        HandlebarGesture.entries.forEach { setAction(context, it, it.defaultAction) }
    }

    private fun ensureDefaultsMigrated(context: Context) {
        val p = preferences(context)
        if (p.getInt(KEY_DEFAULTS_VER, 0) >= DEFAULTS_VER) return
        // V2: Select hold/double defaulted to ASSISTANT twice, leaving Back and Home unreachable
        // on volume-only dashes. Clear stored values still equal to the old default so the new
        // HOME/BACK defaults apply; explicit rider overrides are kept.
        val editor = p.edit()
        listOf(HandlebarGesture.ENTER_LONG, HandlebarGesture.ENTER_DOUBLE).forEach { gesture ->
            if (p.getString(gesture.id, null) == HandlebarAction.ASSISTANT.id) {
                editor.remove(gesture.id)
            }
        }
        editor.putInt(KEY_DEFAULTS_VER, DEFAULTS_VER).apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
