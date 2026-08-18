package io.motohub.android.ipc;

import android.os.ParcelFileDescriptor;

import io.motohub.android.ipc.MotorcycleSummary;
import io.motohub.android.ipc.EncoderProfileParcel;
import io.motohub.android.ipc.MotorcycleConnectRequest;
import io.motohub.android.ipc.ITBoxSessionListener;

/**
 * Bound-service contract exposing Core's already-established T-Box transport
 * (EasyConn session, H.264 delivery) to another app's process. Core owns the
 * GPL-3.0-derived connection; callers never touch it directly.
 */
interface ITBoxTransportService {
    boolean isSessionReady();

    /** Null when no T-Box session is currently active. Contains no credentials. */
    MotorcycleSummary getActiveMotorcycle();

    /** Null when no session is active or the video area has not yet been negotiated. */
    EncoderProfileParcel getNegotiatedEncoderProfile();

    /**
     * Starts the T-Box video session inside Core: EasyConn video start + live TFT area
     * negotiation (both need the GPL transport Core owns). Blocking; returns the negotiated
     * capture area (width/height are the raw TFT area — the caller applies its own quality and
     * bitrate), or null if negotiation failed. offerAccessUnit() only delivers frames after this
     * has returned successfully.
     */
    EncoderProfileParcel startVideoSession();

    /** Same call shape as the in-process VideoAccessUnitSink.offerAccessUnit(). */
    boolean offerAccessUnit(in byte[] accessUnit);

    /**
     * Establishes the T-Box connection inside Core's process (Wi-Fi join + EasyConn discovery,
     * which need the GPL transport and the socket binding Core owns) and installs the session.
     * Blocking; returns true once the session is READY. Session ready/lost is also reported to
     * registered listeners.
     */
    boolean connect(in MotorcycleConnectRequest request);

    /**
     * Aborts an in-flight connect() as quickly as possible and cleans up any partial state it
     * left behind. Safe to call even if no connect() is in flight, or if it already finished.
     * A blocked connect() call returns false shortly after this is received.
     */
    void cancelConnect();

    /** Tears down the active T-Box session established via connect(). */
    void disconnect();

    void registerSessionListener(ITBoxSessionListener listener);
    void unregisterSessionListener(ITBoxSessionListener listener);

    /** Opens the one-way data plane for high-rate encoded video frames. */
    ParcelFileDescriptor openVideoStream();

    /** Closes the pipe returned by openVideoStream(). */
    void closeVideoStream();

    /**
     * Read-only snapshot of Core's diagnostic log, already formatted for sharing. A file
     * descriptor rather than a String: the export can exceed the 1 MB Binder transaction
     * buffer. Null when the log is empty or the snapshot cannot be produced. Declared last
     * so the preceding methods keep their transaction ids - a caller talking to an older
     * Core gets a dead transaction (surfaced as null by the client), not a misrouted one.
     */
    ParcelFileDescriptor openDiagnosticLogSnapshot();

    /**
     * Clears Core's diagnostic log. The companion app's own Clear calls this so "clear the
     * log" means the same thing it does everywhere else in the pair: both halves, one gesture.
     * Same tail-position rule as above for compatibility with older Cores.
     */
    void clearDiagnosticLog();

    /**
     * Revision of this contract that Core implements, so a caller can tell a Core that simply
     * does not know a call from one that answered it. A Core older than this method returns 0:
     * the dead transaction leaves the reply parcel empty and an empty parcel reads as 0.
     * Same tail-position rule as the two calls above.
     */
    int getContractVersion();

    /**
     * connect() for a Wi-Fi Direct group the CALLER has already formed and still owns.
     *
     * Core cannot resolve the phone's own 192.168.49.x address for a group another process
     * formed - field logs (samsung SM-S918B, Android 16, VOGE dash, 2026-08-06) show 35 of 44
     * handovers failing with "no usable 192.168.49.x address appeared", 10s apart, while the
     * companion app that formed the group read its address instantly. So the caller passes the
     * addresses it already resolved and Core skips its own interface lookup. Core verifies the
     * group is really formed and never releases it: the process that formed it still owns it.
     *
     * @param localIpv4 the phone's address inside the group, as the caller resolved it
     * @param groupOwnerIpv4 the dash's address (the P2P Group Owner)
     */
    boolean connectOverFormedGroup(
        in MotorcycleConnectRequest request,
        String localIpv4,
        String groupOwnerIpv4
    );

    /**
     * Why the last connect() or connectOverFormedGroup() returned false, in the caller's own
     * words to its rider. Null when the last connect succeeded, when none has run, or when Core
     * predates this call.
     *
     * connect() answers a bare boolean, and for a long time that was everything the companion app
     * knew. It could only say "Core failed to connect to the T-Box" and then offer the help it had
     * - which is help for a busy EasyConn session, so every network-layer failure came out looking
     * like the official CFMOTO app was holding the link. A rider chased that as far as
     * uninstalling an app that had nothing to do with it (2026-08-15), while the real reason - a
     * VPN holding the route to the dash - sat in Core's log where nothing showed it to him.
     *
     * Read it only after a false answer, and pair it with getLastConnectFailureStage().
     */
    String getLastConnectFailure();

    /**
     * Which half of the connect produced getLastConnectFailure(): one of
     * IpcBridgeContract.CONNECT_STAGE_*. Lets a caller offer network help for a network failure
     * and session help for a session failure, without matching on message text that is assembled
     * from exceptions and translated. Returns CONNECT_STAGE_UNKNOWN (0) on a Core that predates
     * this call, which is also what an empty reply parcel reads as.
     */
    int getLastConnectFailureStage();
}
