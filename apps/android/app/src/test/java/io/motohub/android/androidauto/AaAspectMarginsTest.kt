package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AaAspectMarginsTest {
    private fun aspect(width: Int, height: Int) = width.toDouble() / height

    @Test
    fun `a portrait panel gives up rows from a portrait coded source`() {
        // The rider's case, 2026-07-31 (modelId 37426): an 800x951 panel was being letterboxed
        // into a 418x744 viewport - 291 black pixels down each side. AUTO now picks 720x1280 and
        // trims 424 rows, leaving 720x856, which is the panel's shape to three decimal places.
        val margins = AaAspectMargins.forPanel(DisplayGeometry(720, 1280), DisplayGeometry(800, 951))
        assertEquals(0, margins.width)
        assertEquals(424, margins.height)
        assertTrue(abs(aspect(720, 1280 - 424) - aspect(800, 951)) < 0.002)
    }

    @Test
    fun `a landscape coded source gives up columns for the same panel`() {
        // The numbers another app shows for this dash, reproduced exactly: 800x480 coded against
        // an 800x951 panel leaves 404x480. Different coded source, same resulting shape - which
        // is the point of trimming only the axis that is too long.
        val margins = AaAspectMargins.forPanel(DisplayGeometry(800, 480), DisplayGeometry(800, 951))
        assertEquals(396, margins.width)
        assertEquals(0, margins.height)
    }

    @Test
    fun `a panel that already matches gives up nothing`() {
        assertEquals(
            AaAspectMargins.NONE,
            AaAspectMargins.forPanel(DisplayGeometry(800, 480), DisplayGeometry(800, 480))
        )
        // And a panel of the same shape at another size is still a match.
        assertEquals(
            AaAspectMargins.NONE,
            AaAspectMargins.forPanel(DisplayGeometry(800, 480), DisplayGeometry(1600, 960))
        )
    }

    @Test
    fun `a wide panel trims a tall source and a tall panel trims a wide one`() {
        val wide = AaAspectMargins.forPanel(DisplayGeometry(720, 1280), DisplayGeometry(1920, 480))
        assertEquals(0, wide.width)
        assertTrue("expected rows to be given up, got $wide", wide.height > 0)

        val tall = AaAspectMargins.forPanel(DisplayGeometry(1280, 720), DisplayGeometry(480, 1920))
        assertEquals(0, tall.height)
        assertTrue("expected columns to be given up, got $tall", tall.width > 0)
    }

    @Test
    fun `an absurd panel is floored rather than collapsing the picture`() {
        // A misreported panel must not shrink Android Auto to a sliver: below the floor, keeping
        // a wrong aspect beats keeping nothing to look at.
        val margins = AaAspectMargins.forPanel(DisplayGeometry(1280, 720), DisplayGeometry(10, 4000))
        assertEquals(1280 - AaAspectMargins.MIN_USABLE, margins.width)
        assertEquals(0, margins.height)
    }

    @Test
    fun `aspect margins and screen furniture both reach the AAP fields`() {
        // They describe different things - one is the panel's shape, the other is chrome the
        // motorcycle draws over the picture - but Android Auto has only two numbers for both.
        val profile = AndroidAutoCapabilityProfile(
            videoPreset = AndroidAutoVideoPreset.PORTRAIT_720X1280,
            source = AndroidAutoCapabilitySource.SAVED_TBOX_GEOMETRY,
            target = DisplayGeometry(800, 951),
            reason = "test",
            screenMargins = TBoxScreenMargins(top = 22),
            aspectMargins = AaAspectMargins(0, 424)
        )
        assertEquals(0, profile.marginWidth)
        assertEquals(446, profile.marginHeight)
        assertEquals(DisplayGeometry(720, 834), profile.touchSurface)
    }
}
