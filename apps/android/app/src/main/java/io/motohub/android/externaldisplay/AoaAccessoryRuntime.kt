// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.externaldisplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a USB AOA accessory (external head unit) is currently attached.
 *
 * Android only resolves [android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED] to an
 * activity launch/onNewIntent - it is never sent as a plain broadcast - so this can't be kept
 * fresh with a registered BroadcastReceiver alone. MainActivity publishes here from onCreate,
 * onNewIntent, and its ACTION_USB_ACCESSORY_DETACHED receiver.
 */
object AoaAccessoryRuntime {
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    fun publish(connected: Boolean) {
        mutableConnected.value = connected
    }
}
