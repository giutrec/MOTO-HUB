package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkerRideProfileRoutingTest {

    @Test
    fun theQrPseudoModelIdResolvesToTheKoveProfile() {
        assertEquals(
            TBoxModelProfile.KOVE_800X,
            TBoxModelProfile.fromModelId(ThinkerRideProtocol.PROVISIONING_MODEL_ID)
        )
        assertEquals(
            TBoxModelProfile.KOVE_800X,
            TBoxModelProfile.resolve(ThinkerRideProtocol.PROVISIONING_MODEL_ID, null)
        )
    }

    @Test
    fun theManualOverridePinsTheKoveProfile() {
        assertEquals(
            TBoxModelProfile.KOVE_800X,
            TBoxModelProfile.resolve(null, null, ProfileOverride.KOVE_800X)
        )
        assertEquals(ProfileOverride.KOVE_800X, ProfileOverride.byKey("kove_800x"))
    }

    @Test
    fun onlyThinkerRideProfilesRouteToTheThinkerRideTransport() {
        assertEquals(TBoxTransportFamily.THINKERRIDE, TBoxModelProfile.KOVE_800X.transportFamily)
        // Every EasyConn profile must keep the wire it has always used. KOVE is ThinkerRide and
        // the X-Cape 1200 is Yunmo; every other profile stays on EasyConn.
        TBoxModelProfile.entries
            .filterNot {
                it == TBoxModelProfile.KOVE_800X ||
                    it.transportFamily == TBoxTransportFamily.YUNMO
            }
            .forEach { profile ->
                assertEquals(
                    "${profile.name} must stay on EasyConn",
                    TBoxTransportFamily.EASYCONN,
                    profile.transportFamily
                )
            }
    }

    @Test
    fun theKoveProfileOwnsThePanelGeometryBecauseTheWireCannotNegotiateOne() {
        val area = TBoxModelProfile.KOVE_800X.fallbackTBoxVideoArea

        assertEquals(ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH, area?.width)
        assertEquals(ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT, area?.height)
    }

    @Test
    fun theKoveStreamMatchesTheReferenceImplementation() {
        // Field logs 2026-08-13: intra refresh (IDR only every 10s), a 592-wide stream under a
        // 600-wide header, and 2.5 Mbps froze the dash 15-30s into every session, on two bikes.
        // KoveMirror's plain 1s-IDR 600x1024 ~1.8 Mbps stream runs clean on the same hardware,
        // so the profile pins the exact same stream shape.
        val profile = TBoxModelProfile.KOVE_800X

        assertEquals(1, profile.encoderKeyframeIntervalSeconds)
        assertEquals(true, profile.encoderPlainGopWithoutIntraRefresh)
        assertEquals(true, profile.encoderUsesExactVideoArea)
        assertEquals(
            ThinkerRideProtocol.DEFAULT_VIDEO_WIDTH * ThinkerRideProtocol.DEFAULT_VIDEO_HEIGHT * 3,
            profile.encoderBitRate
        )
    }
}
