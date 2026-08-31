// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot signal that an incoming Intent (see IpcBridgeContract.EXTRA_OPEN_ANDROID_AUTO_PREVIEW)
 * asked this Activity instance to jump straight to the Android Auto preview screen. Only ever
 * published from Core, in response to a launch from Advanced — see MainActivity's
 * handleAndroidAutoPreviewLaunchIntent(). A SharedFlow (not a StateFlow) because this is an
 * event, not a persisted state: replaying an old "open" request after the rider has already
 * navigated away would be wrong.
 */
object AndroidAutoPreviewLaunchRequest {
    private val mutableRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val requests: SharedFlow<Boolean> = mutableRequests.asSharedFlow()

    /** @param fullscreen whether the preview should open already maximized. */
    fun publish(fullscreen: Boolean) {
        mutableRequests.tryEmit(fullscreen)
    }
}
