package io.motohub.android.feature.ridedashboard

import android.content.Context

/** CORE does not expose or start a Ride Dashboard; PRO owns that feature. */
fun createRideDashboardLocalAndroidAutoTrigger(context: Context): RideDashboardLocalAndroidAutoTrigger =
    CoreRideDashboardLocalAndroidAutoTrigger

private object CoreRideDashboardLocalAndroidAutoTrigger : RideDashboardLocalAndroidAutoTrigger {
    override fun trigger() = Unit
}
