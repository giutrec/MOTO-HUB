// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot signal that an incoming Intent (see
 * IpcBridgeContract.EXTRA_START_PHONE_ONLY_ANDROID_AUTO) asked this Core Activity instance to
 * start a phone-only Android Auto session (no T-Box) and show it. Only ever published from
 * Core's MainActivity.handleAndroidAutoPreviewLaunchIntent, in response to a launch from
 * Advanced — see PhoneOnlyAndroidAutoBridge for what actually runs. A SharedFlow (not a
 * StateFlow), same reasoning as AndroidAutoPreviewLaunchRequest: this is an event, not persisted
 * state.
 *
 * [displayMode] carries Advanced's own default Android Auto Display setting (see
 * IpcBridgeContract.EXTRA_ANDROID_AUTO_DISPLAY_MODE) — Advanced never runs the receiver itself,
 * so its own copy of that setting would otherwise have no effect; MainActivity applies it to
 * Core's own AndroidAutoDisplayModeStore before starting the bridge, which is what
 * PhoneOnlyAndroidAutoBridge actually reads.
 */
object PhoneOnlyAndroidAutoLaunchRequest {
    private val mutableRequests = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val requests: SharedFlow<String?> = mutableRequests.asSharedFlow()

    fun publish(displayMode: String?) {
        mutableRequests.tryEmit(displayMode)
    }
}
