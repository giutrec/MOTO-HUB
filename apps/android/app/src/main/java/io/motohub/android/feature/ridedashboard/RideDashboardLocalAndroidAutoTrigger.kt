package io.motohub.android.feature.ridedashboard

/**
 * Triggers Google Android Auto's self-mode connect once Ride Dashboard's LOCAL embedded AA
 * receiver is ready (map source ANDROID_AUTO, running in the same process as this Activity).
 * Only meaningful in CORE, which owns the AGPL receiver; PRO's Dashboard source asks CORE to
 * start the receiver through the output-Surface IPC handshake.
 */
interface RideDashboardLocalAndroidAutoTrigger {
    fun trigger()
}
