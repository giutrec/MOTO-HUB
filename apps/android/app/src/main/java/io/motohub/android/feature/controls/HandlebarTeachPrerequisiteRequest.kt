package io.motohub.android.feature.controls

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot signal from the "Teach my handlebar" prerequisite dialog (see
 * [HandlebarMappingScreen]) asking Core's MainActivity to get an Android Auto session running.
 * Calibration needs live capture ([io.motohub.android.feature.controls.MediaButtonBridge]'s
 * `captureActive`) to see anything at all, and this dialog is reached from deep inside
 * Settings, with no session-state or permission-launcher access of its own to start one
 * directly - mirrors [io.motohub.android.androidauto.PhoneOnlyAndroidAutoLaunchRequest]'s shape
 * for the same reason.
 */
object HandlebarTeachPrerequisiteRequest {
    sealed interface Choice {
        /** Start Android Auto entirely on this phone, no T-Box - see
         *  [io.motohub.android.androidauto.PhoneOnlyAndroidAutoBridge]. */
        data object PhoneOnly : Choice

        /** Select this saved motorcycle as active, then connect to its T-Box. */
        data class Connect(val motorcycleId: String) : Choice
    }

    private val mutableRequests = MutableSharedFlow<Choice>(extraBufferCapacity = 1)
    val requests: SharedFlow<Choice> = mutableRequests.asSharedFlow()

    fun publish(choice: Choice) {
        mutableRequests.tryEmit(choice)
    }
}
