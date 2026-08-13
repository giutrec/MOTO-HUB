package io.motohub.android.feature.controls

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.motohub.android.session.ProjectionEventLog

/**
 * Sees key presses from a Bluetooth HID-keyboard handlebar remote system-wide, regardless of
 * which app currently has focus on the TFT.
 *
 * Ordinary HID key events are delivered only to whatever window has focus — during a ride that
 * is normally Android Auto's own surface, not this app — so a plain `dispatchKeyEvent` in
 * [io.motohub.android.MainActivity] would never see them. Worse, a literal volume keycode
 * bypasses app focus entirely and goes straight to the system volume UI. An Accessibility
 * Service with [AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS] is the one mechanism
 * short of root that can intercept either before that happens, which is why HID mode exists
 * as a whole: [MediaButtonBridge]'s normal path only ever sees keys the system already decided
 * to treat as AVRCP media-button events.
 *
 * Entirely inert unless BOTH are true: the rider granted this service in system Accessibility
 * settings (an explicit toggle Android requires; nothing in-app can flip it for them — see
 * [openAccessibilitySettings]) AND [HandlebarControlStore.inputMode] is currently
 * [HandlebarInputMode.HID]. In AVRCP mode, or with the service off, every key event is returned
 * unconsumed so it reaches whatever app would normally get it.
 */
class HandlebarHidCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 0
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Traced unconditionally, before every gate below: whether this even fires at all is
        // the first fact needed to tell "Android never delivered the key event here" apart from
        // "it arrived but a gate downstream dropped it" - the two look identical from a rider
        // just seeing the press do nothing.
        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> "action=${event.action}"
        }
        val trace = "[HID] raw ${KeyEvent.keyCodeToString(event.keyCode)} $actionName " +
            "repeat=${event.repeatCount} source=${event.source}"
        if (HandlebarControlStore.inputMode(applicationContext) != HandlebarInputMode.HID) {
            ProjectionEventLog.debug("HID_BTN", "$trace (AVRCP mode selected; ignored)")
            return false
        }
        if (!HandlebarControlStore.isEnabled(applicationContext)) {
            ProjectionEventLog.record("HID_BTN", "$trace (handlebar capture is off in Settings; ignored)")
            return false
        }
        val consumed = MediaButtonBridge.dispatchHidKeyEvent(event.keyCode, event.action, event.repeatCount)
        if (!consumed) {
            ProjectionEventLog.record(
                "HID_BTN",
                "$trace not consumed - no session is capturing right now (start Android Auto " +
                    "or mirroring first), or this keycode is not recognized"
            )
        }
        return consumed
    }

    companion object {
        /** Deep-links to the system screen where the rider grants this service — Android
         *  requires an explicit in-settings toggle; no runtime permission covers it. */
        fun openAccessibilitySettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        /** Whether the rider has already granted this service, for the settings screen's hint. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, HandlebarHidCaptureService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
