package io.motohub.android.ipc

/** Int constants used by IAndroidAutoStateListener.onStateChanged — shared vocabulary
 *  for both sides of the Binder call, since AIDL has no sealed-class equivalent. */
object AndroidAutoIpcState {
    const val IDLE = 0
    const val PREPARING = 1
    const val RECEIVER_READY = 2
    const val STREAMING = 3
    const val STOPPED = 4
    const val FAILED = 5
}

/** Also used for io.motohub.android.ipc.IpcBridgeService's own internal AA session bookkeeping. */
object IpcBridgeContract {
    /**
     * Two distinct actions, not one action + an extra: Android caches the IBinder returned by
     * onBind() per Intent.filterEquals(), which ignores extras. A single shared action would
     * make the second bind (regardless of its extra) silently receive the first caller's binder.
     */
    const val BIND_ACTION_TBOX_TRANSPORT = "io.motohub.android.ipc.BIND_TBOX_TRANSPORT"
    const val BIND_ACTION_ANDROID_AUTO_RECEIVER = "io.motohub.android.ipc.BIND_ANDROID_AUTO_RECEIVER"

    /** Signature-level permission a caller must hold to bind IpcBridgeService. */
    const val BIND_PERMISSION = "io.motohub.android.permission.BIND_CORE_SERVICE"

    /**
     * Revision of ITBoxTransportService this build implements, answered by getContractVersion().
     * Bump it whenever a call is appended, and gate the caller on the constant naming the call
     * it needs - a Core that predates getContractVersion() itself answers 0.
     *
     * 1: everything up to clearDiagnosticLog(), which had no version call.
     * 2: connectOverFormedGroup().
     * 3: getLastConnectFailure() + getLastConnectFailureStage().
     */
    const val CONTRACT_VERSION = 3

    /** First [CONTRACT_VERSION] whose Core implements connectOverFormedGroup(). */
    const val CONTRACT_VERSION_FORMED_GROUP = 2

    /** First [CONTRACT_VERSION] whose Core can say why a connect failed, and at which stage. */
    const val CONTRACT_VERSION_CONNECT_FAILURE_REASON = 3

    /**
     * Which half of Core's connect produced the last failure, answered by
     * getLastConnectFailureStage(). The distinction is what keeps help for a busy EasyConn session
     * off a failure that never reached one: below [CONNECT_STAGE_DISCOVERY] no session existed for
     * another app to be holding, so nothing about another app can be the explanation.
     */
    const val CONNECT_STAGE_UNKNOWN = 0

    /** The phone never got a usable link to the dash: Wi-Fi join, P2P group, or routing. */
    const val CONNECT_STAGE_NETWORK = 1

    /** The link was up and the dash did not answer as an EasyConn (or other family) host. */
    const val CONNECT_STAGE_DISCOVERY = 2

    /** Core refused before trying: it is already driving this dash for someone else. */
    const val CONNECT_STAGE_REFUSED = 3

    const val CORE_PACKAGE_NAME = "io.motohub.android"
    const val CORE_MAIN_ACTIVITY_CLASS_NAME = "io.motohub.android.MainActivity"

    /** Core-side deep-link used by the full Android Auto preview controls. */
    const val EXTRA_OPEN_ANDROID_AUTO_PREVIEW = "io.motohub.android.extra.OPEN_ANDROID_AUTO_PREVIEW"
    const val EXTRA_OPEN_ANDROID_AUTO_PREVIEW_FULLSCREEN =
        "io.motohub.android.extra.OPEN_ANDROID_AUTO_PREVIEW_FULLSCREEN"

    /** Legacy Core deep-links retained only for settings migration compatibility. */
    const val EXTRA_OPEN_RIDE_DASHBOARD_CONTROLS = "io.motohub.android.extra.OPEN_RIDE_DASHBOARD_CONTROLS"
    const val EXTRA_OPEN_RIDE_DASHBOARD_CUSTOMIZE = "io.motohub.android.extra.OPEN_RIDE_DASHBOARD_CUSTOMIZE"

    /**
     * Same idea as [EXTRA_OPEN_ANDROID_AUTO_PREVIEW], but for a session with no T-Box at all:
     * Advanced asks Core to both START a phone-only Android Auto session (Core builds/runs the AA
     * receiver itself, bound only to the phone's own preview Surface) AND open the screen that
     * shows it, in one launch.
     */
    const val EXTRA_START_PHONE_ONLY_ANDROID_AUTO = "io.motohub.android.extra.START_PHONE_ONLY_ANDROID_AUTO"

    /**
     * Advanced's own Android Auto Display default (see the Garage's default-settings panel),
     * as an [io.motohub.android.androidauto.AndroidAutoDisplayMode] name string. Core is the
     * only process that ever runs the phone-only receiver, so Advanced's own copy of this
     * setting has no effect unless it rides along on this extra — Core applies it to its own
     * store before starting the session. Optional: absent means "use whatever Core already has."
     */
    const val EXTRA_ANDROID_AUTO_DISPLAY_MODE = "io.motohub.android.extra.ANDROID_AUTO_DISPLAY_MODE"
}
