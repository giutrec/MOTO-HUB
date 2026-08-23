// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * The one [TBoxTransport] the app holds. Routes every call to the wire implementation the
 * configured [TBoxModelProfile] belongs to — EasyConn ([RideDaemonTransport]), ThinkerRide
 * ([ThinkerRideTransport]) or Yunmo ([YunmoTransport]) — so HubViewModel, CoreTBoxConnector and
 * the session services keep a
 * single transport field and a single event stream regardless of which protocol the motorcycle
 * speaks. [configureProtocolProfile] runs before [discover] on every connect path, which is what
 * makes the profile a safe routing key.
 */
class SelectingTBoxTransport(context: Context) : TBoxTransport {

    private val easyConn = RideDaemonTransport(context)
    private val thinkerRide = ThinkerRideTransport(context)
    private val yunmo = YunmoTransport(context)

    @Volatile
    private var active: TBoxTransport = easyConn

    /**
     * The profile a dash gets when it is discovered to speak Yunmo without the rider having pinned
     * anything. [TBoxModelProfile.MORINI_XCAPE_1200] is the only Yunmo profile there is, and its
     * settings come from the only Yunmo dash anyone has captured, so it is the best available
     * guess rather than a claim about which motorcycle this is.
     *
     * The session services pick this up through [activeProtocolProfile], so the encoder settings
     * follow the same switch the transport did. They used to re-resolve the profile from the saved
     * motorcycle instead, which left an auto-detected Yunmo session encoding at generic settings.
     */
    private val yunmoSessionProfile = TBoxModelProfile.MORINI_XCAPE_1200

    /**
     * What [discover] ended up routing to, for the session services to configure the encoder from.
     *
     * Without this the handover was only half a switch: the session spoke Yunmo but still encoded
     * with the settings of whatever the saved motorcycle resolved to (GENERIC, for a dash nothing
     * can identify from its QR). On the X-Cape that meant 30 fps all-intra instead of 10 - and
     * since every all-intra frame is a keyframe, and a keyframe is split into three wire frames,
     * three times the frames at three times the rate against a three-frame send window.
     */
    @Volatile
    override var activeProtocolProfile: TBoxModelProfile? = null
        private set

    /** Remembered so the Yunmo fallback below can hand the ladder the same motorcycle. */
    @Volatile
    private var configuredMotorcycle: io.motohub.android.session.MotorcycleProfile? = null

    override val events: Flow<TBoxEvent> = merge(easyConn.events, thinkerRide.events, yunmo.events)

    override fun configureProtocolProfile(
        profile: TBoxModelProfile,
        motorcycle: io.motohub.android.session.MotorcycleProfile?
    ) {
        active = when (profile.transportFamily) {
            TBoxTransportFamily.EASYCONN -> easyConn
            TBoxTransportFamily.THINKERRIDE -> thinkerRide
            TBoxTransportFamily.YUNMO -> yunmo
        }
        activeProtocolProfile = null
        configuredMotorcycle = motorcycle
        // The one line that says which wire this session will speak. A field log without it
        // cannot distinguish "the override never reached routing" from "the transport failed"
        // (X-Cape 1200 log, 2026-08-07: session ran EasyConn NSD with no way to tell why).
        ProjectionEventLog.record(
            "PROFILE",
            "Protocol profile: ${profile.key} (${profile.displayName}); " +
                "transport family=${profile.transportFamily}."
        )
        active.configureProtocolProfile(profile, motorcycle)
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> {
        val first = active.discover(link, expectedModelId)
        if (first.isSuccess || active !== easyConn) return first

        // EasyConn found nothing. Before giving the rider "T-Box offline", ask whether this dash
        // speaks Yunmo instead — a question only the dash can answer, and one that used to require
        // the rider to find and pin a profile override by hand. Nothing else reaches this point:
        // a dash that answers EasyConn returned above, and a dash that answers neither costs one
        // extra short connect on a path that has already failed.
        val yunmoHost = yunmo.answersOnThisLink(link) ?: return first
        ProjectionEventLog.record(
            "PROFILE",
            "EasyConn discovery found nothing but the dash answered Yunmo on " +
                "$yunmoHost:${YunmoProtocol.DEFAULT_PORT}; switching this session to the Yunmo transport."
        )
        active = yunmo
        yunmo.configureProtocolProfile(yunmoSessionProfile, configuredMotorcycle)
        activeProtocolProfile = yunmoSessionProfile
        return yunmo.discover(link, expectedModelId)
    }

    override suspend fun start(host: TBoxHost): Result<Unit> = active.start(host)

    /**
     * Routes a JPEG still to the Yunmo transport, for the X-Cape profile that captures stills
     * instead of encoding video. Returns false on any other family, which is what the caller wants:
     * only a session configured for that profile ever produces JPEG frames in the first place.
     */
    fun offerJpegFrame(jpeg: ByteArray, frameId: Int): Boolean =
        if (active === yunmo) yunmo.offerJpegFrame(jpeg, frameId) else false

    /**
     * The [TBoxTransport] face of [offerJpegFrame], so a caller holding only the interface - the
     * companion app's frames arriving over the AIDL video pipe - reaches the same still path the
     * in-process session services reach directly.
     */
    override fun offerStillFrame(jpeg: ByteArray, frameId: Int): Boolean =
        offerJpegFrame(jpeg, frameId)

    override fun offerAccessUnit(avcc: ByteArray): Boolean = active.offerAccessUnit(avcc)

    override suspend fun stop() {
        // Stopping all three is deliberate: a routing change between sessions must never leave the
        // previous family's sockets or BLE link alive, and stop() is a no-op on an idle transport.
        easyConn.stop()
        thinkerRide.stop()
        yunmo.stop()
    }
}
