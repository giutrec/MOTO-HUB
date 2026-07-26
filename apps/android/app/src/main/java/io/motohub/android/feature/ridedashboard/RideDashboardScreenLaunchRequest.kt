package io.motohub.android.feature.ridedashboard

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Which Ride Dashboard screen a cross-app launch (see IpcBridgeContract's
 *  EXTRA_OPEN_RIDE_DASHBOARD_CONTROLS/CUSTOMIZE) asked this Activity instance to jump to. */
enum class RideDashboardScreen { CONTROLS, CUSTOMIZE }

/**
 * One-shot signal analogous to AndroidAutoPreviewLaunchRequest, for the two Ride Dashboard
 * screens (pan/zoom controls, widget customization) that only work when opened in whichever
 * process actually runs RideDashboardSessionService. Only ever published from Core, in response
 * to a launch from Advanced - see MainActivity's handleAndroidAutoPreviewLaunchIntent().
 */
object RideDashboardScreenLaunchRequest {
    private val mutableRequests = MutableSharedFlow<RideDashboardScreen>(extraBufferCapacity = 1)
    val requests: SharedFlow<RideDashboardScreen> = mutableRequests.asSharedFlow()

    fun publish(screen: RideDashboardScreen) {
        mutableRequests.tryEmit(screen)
    }
}
