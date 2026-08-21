// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.externaldisplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AoaExternalRuntimeState {
    data object Idle : AoaExternalRuntimeState
    data object Starting : AoaExternalRuntimeState
    data object Streaming : AoaExternalRuntimeState
    data class Stopped(val reason: String) : AoaExternalRuntimeState
    data class Failed(val message: String) : AoaExternalRuntimeState
}

object AoaExternalRuntime {
    private val mutableState = MutableStateFlow<AoaExternalRuntimeState>(AoaExternalRuntimeState.Idle)
    private val active = AtomicBoolean(false)
    val state: StateFlow<AoaExternalRuntimeState> = mutableState.asStateFlow()

    fun publish(state: AoaExternalRuntimeState) {
        active.set(state is AoaExternalRuntimeState.Starting || state is AoaExternalRuntimeState.Streaming)
        mutableState.value = state
    }

    fun isActive(): Boolean = active.get()
}
