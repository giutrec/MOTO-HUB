package io.motohub.android.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Binds to Core's IpcBridgeService for Android Auto control (full session → real T-Box, see
 * IAndroidAutoReceiverService.aidl). The caller's OWN manifest must still declare (neither can
 * be enforced from here — omitting either fails differently):
 *   <queries><package android:name="io.motohub.android"/></queries>            (or bindService() silently returns false)
 *   <uses-permission android:name="io.motohub.android.permission.BIND_CORE_SERVICE"/>  (or bindService() throws SecurityException)
 */
class AndroidAutoReceiverClient(
    private val context: Context,
    private val corePackage: String = "io.motohub.android",
    private val onStateChanged: (state: Int, message: String) -> Unit = { _, _ -> },
    /** Separate from [onStateChanged]: reports Core's Ride-Dashboard-with-embedded-AA session
     *  state (startEmbeddedDashboardSession), not the full-AA-screen state. */
    private val onEmbeddedDashboardStateChanged: (state: Int, message: String) -> Unit = { _, _ -> }
) {
    private var service: IAndroidAutoReceiverService? = null
    private var bound = false

    private val stateListener = object : IAndroidAutoStateListener.Stub() {
        override fun onStateChanged(state: Int, message: String) =
            this@AndroidAutoReceiverClient.onStateChanged(state, message)
    }

    private val embeddedDashboardStateListener = object : IAndroidAutoStateListener.Stub() {
        override fun onStateChanged(state: Int, message: String) =
            this@AndroidAutoReceiverClient.onEmbeddedDashboardStateChanged(state, message)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = IAndroidAutoReceiverService.Stub.asInterface(binder)
            service = bound
            bound.registerStateListener(stateListener)
            bound.registerEmbeddedDashboardStateListener(embeddedDashboardStateListener)
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
            // BIND_ALLOW_ACTIVITY_STARTS: without it, Core's process sits at BOUND_TOP (bound by
            // Pro, which is foreground) rather than PROC_STATE_TOP itself, and modern Android
            // treats those differently - RideDashboardTrampolineActivity (needed to promote the
            // dashboard to a location-typed foreground service) gets silently killed by the
            // background-activity-launch guard, so startEmbeddedDashboardSession() reports
            // success but nothing ever actually starts. This flag grants Core that exemption for
            // as long as Pro stays bound.
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
        service?.unregisterEmbeddedDashboardStateListener(embeddedDashboardStateListener)
        context.unbindService(connection)
        service = null
        bound = false
    }

    /** True once the bound-service connection is established (bind() is async). */
    val isConnected: Boolean get() = service != null

    fun applyAndroidAutoSettings(settings: AndroidAutoSettingsParcel) {
        service?.applyAndroidAutoSettings(settings)
    }

    fun setNightMode(isNight: Boolean): Boolean = service?.setNightMode(isNight) ?: false

    fun sendKey(keycode: Int): Boolean = service?.sendKey(keycode) ?: false

    fun sendScroll(delta: Int): Boolean = service?.sendScroll(delta) ?: false

    fun sendTouch(action: Int, x: Int, y: Int): Boolean = service?.sendTouch(action, x, y) ?: false

    fun startFullSession(): Boolean = service?.startFullSession() ?: false

    fun stopFullSession() {
        service?.stopFullSession()
    }

    fun startEmbeddedDashboardSession(): Boolean = service?.startEmbeddedDashboardSession() ?: false

    fun stopEmbeddedDashboardSession() {
        service?.stopEmbeddedDashboardSession()
    }

    private companion object {
        const val TAG = "AndroidAutoReceiverClient"
    }
}
