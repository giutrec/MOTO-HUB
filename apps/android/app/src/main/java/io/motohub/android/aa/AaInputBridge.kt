package io.motohub.android.aa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide hand-off from Android Auto's active AAP session to phone controls. */
object AaInputBridge {
    @Volatile private var activeInput: AaInput? = null
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    fun install(input: AaInput) {
        activeInput = input
        mutableReady.value = true
    }

    fun clear(input: AaInput? = null) {
        if (input == null || activeInput === input) {
            activeInput = null
            mutableReady.value = false
        }
    }

    fun isReady(): Boolean = activeInput != null

    fun sendKey(keycode: Int): Boolean {
        val input = activeInput ?: return false
        input.sendKey(keycode)
        return true
    }

    fun sendScroll(delta: Int): Boolean {
        val input = activeInput ?: return false
        input.sendScroll(delta)
        return true
    }
}
