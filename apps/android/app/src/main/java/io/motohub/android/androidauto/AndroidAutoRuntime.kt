// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AndroidAutoRuntimeState {
    data object Idle : AndroidAutoRuntimeState
    data object Preparing : AndroidAutoRuntimeState
    data object ReceiverReady : AndroidAutoRuntimeState
    data object Streaming : AndroidAutoRuntimeState
    data class Stopped(val reason: String) : AndroidAutoRuntimeState
    data class Failed(val message: String) : AndroidAutoRuntimeState
}

object AndroidAutoRuntime {
    private val mutableState = MutableStateFlow<AndroidAutoRuntimeState>(AndroidAutoRuntimeState.Idle)
    val state: StateFlow<AndroidAutoRuntimeState> = mutableState.asStateFlow()

    /**
     * What the session is doing while it sits in [AndroidAutoRuntimeState.ReceiverReady].
     *
     * Asking Google Android Auto to project can take several seconds and several attempts on
     * recent versions, and the rider was left staring at a screen that claimed to be connected
     * while nothing visibly happened. Null whenever there is nothing more specific to say.
     */
    private val mutableStartupDetail = MutableStateFlow<String?>(null)
    val startupDetail: StateFlow<String?> = mutableStartupDetail.asStateFlow()

    fun publish(state: AndroidAutoRuntimeState) {
        // The detail only describes the wait inside ReceiverReady; any other state supersedes it.
        if (state !is AndroidAutoRuntimeState.ReceiverReady) mutableStartupDetail.value = null
        mutableState.value = state
    }

    fun publishStartupDetail(detail: String?) {
        mutableStartupDetail.value = detail
    }

    fun isActive(): Boolean = when (mutableState.value) {
        AndroidAutoRuntimeState.Preparing,
        AndroidAutoRuntimeState.ReceiverReady,
        AndroidAutoRuntimeState.Streaming -> true
        else -> false
    }
}
