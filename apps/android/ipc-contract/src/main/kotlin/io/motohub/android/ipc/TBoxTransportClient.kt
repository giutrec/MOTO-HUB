package io.motohub.android.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Binds to Core's IpcBridgeService for T-Box transport access. The caller's OWN manifest must
 * still declare (neither can be enforced from here — omitting either fails differently):
 *   <queries><package android:name="io.motohub.android"/></queries>            (or bindService() silently returns false)
 *   <uses-permission android:name="io.motohub.android.permission.BIND_CORE_SERVICE"/>  (or bindService() throws SecurityException)
 */
class TBoxTransportClient(
    private val context: Context,
    private val corePackage: String = "io.motohub.android",
    private val onSessionReady: () -> Unit = {},
    private val onSessionLost: () -> Unit = {}
) {
    private var service: ITBoxTransportService? = null
    private var bound = false

    private val sessionListener = object : ITBoxSessionListener.Stub() {
        override fun onSessionReady() = this@TBoxTransportClient.onSessionReady()
        override fun onSessionLost() = this@TBoxTransportClient.onSessionLost()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = ITBoxTransportService.Stub.asInterface(binder)
            service = bound
            bound.registerSessionListener(sessionListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    /** Returns false if the bind call itself failed (see manifest requirements above). */
    fun bind(): Boolean {
        val intent = Intent(IpcBridgeContract.BIND_ACTION_TBOX_TRANSPORT).apply {
            setPackage(corePackage)
        }
        return try {
            // See the identical flag on AndroidAutoReceiverClient.bind() - Pro and Core share one
            // process-wide background-activity-launch exemption while Pro stays bound, needed for
            // RideDashboardTrampolineActivity regardless of which client happens to be bound when
            // Core's IpcBridgeService is asked to promote the dashboard to a location-typed FGS.
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
        runCatching { service?.unregisterSessionListener(sessionListener) }
        runCatching { context.unbindService(connection) }
        service = null
        bound = false
    }

    /** True once the bound-service connection has actually been established (bind() is async). */
    val isConnected: Boolean get() = service != null

    fun isSessionReady(): Boolean = service?.isSessionReady() ?: false

    fun getActiveMotorcycle(): MotorcycleSummary? = service?.getActiveMotorcycle()

    /**
     * Asks Core to start the T-Box video session (EasyConn video + TFT area negotiation) and
     * returns the negotiated capture area, or null if it failed / the service isn't bound.
     * Must succeed before offerAccessUnit() delivers any frames.
     */
    fun startVideoSession(): EncoderProfileParcel? = service?.startVideoSession()

    /** Opens the high-rate video data plane; null means the older Binder-only Core is in use. */
    fun openVideoStream(): ParcelFileDescriptor? = service?.openVideoStream()

    fun closeVideoStream() {
        service?.closeVideoStream()
    }

    fun offerAccessUnit(accessUnit: ByteArray): Boolean = service?.offerAccessUnit(accessUnit) ?: false

    /** Asks Core to establish the T-Box connection in its own process (it owns the GPL transport). */
    fun connect(request: MotorcycleConnectRequest): Boolean = service?.connect(request) ?: false

    /**
     * Which revision of the contract the bound Core implements, or 0 when it is not bound or is
     * old enough not to know the call at all. Gate an appended call on this rather than calling
     * it and reading the answer: a dead transaction returns the same `false` a real refusal does.
     */
    fun contractVersion(): Int = runCatching { service?.getContractVersion() }.getOrNull() ?: 0

    /**
     * Hands Core a Wi-Fi Direct group THIS process formed, with the addresses already resolved
     * here - see ITBoxTransportService.aidl for why Core cannot resolve them itself. Only call
     * it when [contractVersion] is at least [IpcBridgeContract.CONTRACT_VERSION_FORMED_GROUP];
     * an older Core answers false without ever running a connect.
     */
    fun connectOverFormedGroup(
        request: MotorcycleConnectRequest,
        localIpv4: String,
        groupOwnerIpv4: String
    ): Boolean =
        runCatching { service?.connectOverFormedGroup(request, localIpv4, groupOwnerIpv4) }
            .getOrNull() ?: false

    /** Aborts an in-flight connect() on Core's side; see ITBoxTransportService.aidl. */
    fun cancelConnect() {
        service?.cancelConnect()
    }

    fun disconnect() {
        service?.disconnect()
    }

    /**
     * Read-only snapshot of Core's diagnostic log, or null when the service is not bound,
     * the log is empty, or an older Core predates the method (the dead transaction surfaces
     * here as an exception, caught like openVideoStream()'s "older Binder-only Core" case).
     */
    fun openDiagnosticLogSnapshot(): ParcelFileDescriptor? =
        runCatching { service?.openDiagnosticLogSnapshot() }.getOrNull()

    /** True when the clear reached Core; false when unbound or Core predates the method. */
    fun clearDiagnosticLog(): Boolean =
        runCatching { service?.also { it.clearDiagnosticLog() } != null }.getOrDefault(false)

    private companion object {
        const val TAG = "TBoxTransportClient"
    }
}
