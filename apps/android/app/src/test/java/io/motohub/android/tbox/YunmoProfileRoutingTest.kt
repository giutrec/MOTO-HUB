package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YunmoProfileRoutingTest {

    /** The X-Cape profile and its header-variant experiments; everything else must stay EasyConn. */
    private val yunmoProfiles = setOf(
        TBoxModelProfile.MORINI_XCAPE_1200,
        TBoxModelProfile.MORINI_XCAPE_1200_B,
        TBoxModelProfile.MORINI_XCAPE_1200_C,
        TBoxModelProfile.MORINI_XCAPE_1200_D
    )

    @Test
    fun theXCape1200ProfileRoutesToTheYunmoTransport() {
        assertEquals(TBoxTransportFamily.YUNMO, TBoxModelProfile.MORINI_XCAPE_1200.transportFamily)
    }

    @Test
    fun onlyTheXCape1200RoutesToYunmoEveryOtherProfileKeepsItsWire() {
        TBoxModelProfile.entries
            .filterNot { it in yunmoProfiles }
            .forEach { profile ->
                assertNotEquals(
                    "${profile.name} must not accidentally route to Yunmo",
                    TBoxTransportFamily.YUNMO,
                    profile.transportFamily
                )
            }
    }

    @Test
    fun theHeaderVariantsCoverTheFullTwoByTwoAndDifferOnlyInThoseTwoFields() {
        val corners = yunmoProfiles.map { it.yunmoTypedMediaHeader to it.yunmoFrameMetadata }.toSet()
        assertEquals(
            "the four variants must be the four combinations, with no duplicates",
            setOf(false to false, true to false, true to true, false to true),
            corners
        )
        // A variant that also changed geometry or rate would not be a controlled experiment.
        yunmoProfiles.forEach { profile ->
            assertEquals(10, profile.encoderFrameRate)
            assertEquals(187, profile.virtualDisplayDpi)
            assertEquals(TBoxTransportFamily.YUNMO, profile.transportFamily)
            assertTrue("${profile.name} must drive the OEM map-nav path", profile.yunmoMapNavExperiment)
        }
    }

    @Test
    fun theSharedProductIdNeverAutoResolvesToTheYunmoProfile() {
        // ProductID 00297 is shared with the EasyConn X-Cape 649/700 and Seiemmezzo, so the QR
        // model id alone must land on GENERIC (EasyConn), never on the 1200's Yunmo profile.
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.fromModelId("00297"))
        assertEquals(TBoxModelProfile.GENERIC, TBoxModelProfile.resolve("00297", null))
        assertEquals(TBoxTransportFamily.EASYCONN, TBoxModelProfile.resolve("00297", null).transportFamily)
    }

    @Test
    fun theManualOverridePinsTheYunmoProfile() {
        assertEquals(
            TBoxModelProfile.MORINI_XCAPE_1200,
            TBoxModelProfile.resolve("00297", null, ProfileOverride.MORINI_XCAPE_1200)
        )
        assertEquals(ProfileOverride.MORINI_XCAPE_1200, ProfileOverride.byKey("morini_xcape_1200"))
        assertEquals(TBoxModelProfile.MORINI_XCAPE_1200, ProfileOverride.MORINI_XCAPE_1200.resolve())
    }

    @Test
    fun theProfileCarriesFallbackGeometryAndDrivesTheOemMapNavPath() {
        val area = TBoxModelProfile.MORINI_XCAPE_1200.fallbackTBoxVideoArea!!
        assertEquals(800, area.width)
        assertEquals(480, area.height)
        // The OEM app never mirrors this dash - it always drives the navigation path with the
        // presentation the rider picked on the TFT (owner ADB capture, 2026-08-07).
        assertTrue(TBoxModelProfile.MORINI_XCAPE_1200.yunmoMapNavExperiment)
    }

    @Test
    fun mapNavStaysOffForEveryProfileThatIsNotTheXCape() {
        // The flag only ever applies to Yunmo sessions, and only this dash has evidence for it.
        TBoxModelProfile.entries
            .filterNot { it in yunmoProfiles }
            .forEach { profile ->
                assertFalse(
                    "${profile.name} must not enable the Yunmo map-nav path",
                    profile.yunmoMapNavExperiment
                )
            }
    }
}
