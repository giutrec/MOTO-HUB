package io.motohub.android.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.Surface

/**
 * Binds to Core's IpcBridgeService for Android Auto control (full session → real T-Box or a
 * decoded output Surface for PRO's embedded Dashboard, see IAndroidAutoReceiverService.aidl).
 * The caller's OWN manifest must still declare (neither can
 * be enforced from here — omitting either fails differently):
 *   <queries><package android:name="io.motohub.android"/></queries>            (or bindService() silently returns false)
 *   <uses-permission android:name="io.motohub.android.permission.BIND_CORE_SERVICE"/>  (or bindService() throws SecurityException)
 */
class AndroidAutoReceiverClient(
    private val context: Context,
    private val corePackage: String = "io.motohub.android",
    private val onStateChanged: (state: Int, message: String) -> Unit = { _, _ -> }
) {
    private var service: IAndroidAutoReceiverService? = null
    private var bound = false

    private val stateListener = object : IAndroidAutoStateListener.Stub() {
        override fun onStateChanged(state: Int, message: String) =
            this@AndroidAutoReceiverClient.onStateChanged(state, message)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = IAndroidAutoReceiverService.Stub.asInterface(binder)
            service = bound
            bound.registerStateListener(stateListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun bind(): Boolean {
        val intent = Intent(IpcBridgeContract.BIND_ACTION_ANDROID_AUTO_RECEIVER).apply {
            setPackage(corePackage)
        }
        return try {
            // BIND_ALLOW_ACTIVITY_STARTS keeps Core eligible to start its Android Auto receiver
            // while PRO is foreground and the output Surface is being attached.
            val ok = context.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_ALLOW_ACTIVITY_STARTS
            )
            bound = ok
            ok
        } catch (e: SecurityException) {
            Log.e(TAG, "bind denied - is BIND_CORE_SERVICE declared in the caller's manifest? ${e.message}")
            false
        }
    }

    fun unbind() {
        if (!bound) return
        service?.unregisterStateListener(stateListener)
        context.unbindService(connection)
        service = null
        bound = false
    }

    /** True once the bound-service connection is established (bind() is async). */
    val isConnected: Boolean get() = service != null

    /** Asks Core to decode Android Auto into a caller-owned Surface. */
    fun attachOutputSurface(surface: Surface, width: Int, height: Int): Boolean =
        service?.attachOutputSurface(surface, width, height) ?: false

    fun detachOutputSurface() {
        service?.detachOutputSurface()
    }

    fun attachPreviewSurface(surface: Surface, width: Int, height: Int): Boolean =
        service?.attachPreviewSurface(surface, width, height) ?: false

    fun detachPreviewSurface() {
        service?.detachPreviewSurface()
    }

    fun applyAndroidAutoSettings(settings: AndroidAutoSettingsParcel) {
        service?.applyAndroidAutoSettings(settings)
    }

    fun setNightMode(isNight: Boolean): Boolean = service?.setNightMode(isNight) ?: false

    fun sendKey(keycode: Int): Boolean = service?.sendKey(keycode) ?: false

    fun sendScroll(delta: Int): Boolean = service?.sendScroll(delta) ?: false

    fun sendTouch(action: Int, x: Int, y: Int): Boolean = service?.sendTouch(action, x, y) ?: false

    fun sendPreviewTouch(action: Int, x: Int, y: Int): Boolean =
        service?.sendPreviewTouch(action, x, y) ?: false

    fun startFullSession(): Boolean = service?.startFullSession() ?: false

    fun stopFullSession() {
        service?.stopFullSession()
    }

    private companion object {
        const val TAG = "AndroidAutoReceiverClient"
    }
}
