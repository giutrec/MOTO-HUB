// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewGeometryTest {
    private val source = DisplayGeometry(800, 480)

    @Test
    fun `portrait preview contains the complete Android Auto source`() {
        val viewport = calculatePreviewViewport(DisplayGeometry(900, 1800), source)

        assertEquals(0, viewport.x)
        assertEquals(630, viewport.y)
        assertEquals(900, viewport.width)
        assertEquals(540, viewport.height)
        assertEquals(0 to 0, viewport.mapToSource(0, 630))
        assertEquals(799 to 479, viewport.mapToSource(899, 1169))
        assertNull(viewport.mapToSource(450, 629))
    }

    @Test
    fun `landscape preview contains the complete Android Auto source`() {
        val viewport = calculatePreviewViewport(DisplayGeometry(2048, 607), source)

        assertEquals(518, viewport.x)
        assertEquals(0, viewport.y)
        assertEquals(1011, viewport.width)
        assertEquals(607, viewport.height)
        assertEquals(0 to 0, viewport.mapToSource(518, 0))
        assertEquals(799 to 479, viewport.mapToSource(1528, 606))
        assertNull(viewport.mapToSource(517, 300))
    }

    @Test
    fun `portrait source maps preview touches without rotating coordinates`() {
        val portraitSource = DisplayGeometry(720, 1280)
        val viewport = calculatePreviewViewport(DisplayGeometry(1080, 1920), portraitSource)

        assertEquals(0, viewport.x)
        assertEquals(0, viewport.y)
        assertEquals(1080, viewport.width)
        assertEquals(1920, viewport.height)
        assertEquals(0 to 0, viewport.mapToSource(0, 0))
        assertEquals(719 to 1279, viewport.mapToSource(1079, 1919))
    }

    @Test
    fun `portrait source maps T-Box touches and rejects side bars`() {
        val portraitSource = DisplayGeometry(720, 1280)
        val viewport = calculatePreviewViewport(DisplayGeometry(800, 944), portraitSource)

        assertEquals(134, viewport.x)
        assertEquals(0, viewport.y)
        assertEquals(531, viewport.width)
        assertEquals(944, viewport.height)
        assertNull(viewport.mapToSource(133, 472))
        assertEquals(0 to 0, viewport.mapToSource(134, 0))
        assertEquals(718 to 1278, viewport.mapToSource(664, 943))
        assertNull(viewport.mapToSource(665, 472))
    }

    @Test
    fun `fill viewport crops the source symmetrically`() {
        val viewport = calculateFillViewport(DisplayGeometry(600, 480), source)

        assertEquals(-100, viewport.x)
        assertEquals(0, viewport.y)
        assertEquals(800, viewport.width)
        assertEquals(480, viewport.height)
        assertEquals(100 to 0, viewport.mapToSource(0, 0))
        assertEquals(699 to 479, viewport.mapToSource(599, 479))
    }

    @Test
    fun `margin crop viewport fills the safe area while preserving source touch offsets`() {
        val viewport = calculateMarginCropViewport(
            canvas = DisplayGeometry(800, 384),
            source = DisplayGeometry(1280, 720),
            marginWidth = 0,
            marginHeight = 106
        )

        assertEquals(0, viewport.x)
        assertEquals(0, viewport.y)
        assertEquals(800, viewport.width)
        assertEquals(384, viewport.height)
        assertEquals(0 to 53, viewport.mapToSource(0, 0))
        assertEquals(1278 to 665, viewport.mapToSource(799, 383))
    }

    @Test
    fun `margin cropped video uses zero based Android Auto touch coordinates`() {
        val cropViewport = calculateMarginCropViewport(
            canvas = DisplayGeometry(800, 384),
            source = DisplayGeometry(1280, 720),
            marginWidth = 0,
            marginHeight = 106
        )
        val touchViewport = cropViewport.copy(sourceLeft = 0, sourceTop = 0)

        assertEquals(0 to 0, touchViewport.mapToSource(0, 0))
        assertEquals(1278 to 612, touchViewport.mapToSource(799, 383))
    }

    @Test
    fun `stretch viewport fills safe area from active Android Auto content`() {
        val viewport = calculateStretchViewport(
            canvas = DisplayGeometry(800, 384),
            source = source,
            marginWidth = 0,
            marginHeight = 96
        )

        assertEquals(0, viewport.x)
        assertEquals(0, viewport.y)
        assertEquals(800, viewport.width)
        assertEquals(384, viewport.height)
        assertEquals(0 to 48, viewport.mapToSource(0, 0))
        assertEquals(799 to 431, viewport.mapToSource(799, 383))
    }

    @Test
    fun `margin crop viewport honors an asymmetric offset instead of centering`() {
        // left=0, right=100: all margin is on the right, so the crop must start at source x=0,
        // not at the halfway point (marginWidth / 2 = 50) a symmetric assumption would produce.
        val viewport = calculateMarginCropViewport(
            canvas = DisplayGeometry(700, 480),
            source = DisplayGeometry(800, 480),
            marginWidth = 100,
            marginHeight = 0,
            offsetX = 0,
            offsetY = 0
        )

        assertEquals(0, viewport.sourceLeft)
        assertEquals(700, viewport.sourceWidth)
        assertEquals(0 to 0, viewport.mapToSource(0, 0))
    }

    @Test
    fun `stretch viewport keeps the complete source when no margins exist`() {
        val viewport = calculateStretchViewport(
            canvas = DisplayGeometry(800, 384),
            source = source
        )

        assertEquals(0, viewport.x)
        assertEquals(0, viewport.y)
        assertEquals(800, viewport.width)
        assertEquals(384, viewport.height)
        assertEquals(0 to 0, viewport.mapToSource(0, 0))
        assertEquals(799 to 478, viewport.mapToSource(799, 383))
    }

    @Test
    fun `800 by 480 source has distinct fit stretch and crop geometry on 800 by 384 TFT`() {
        val tft = DisplayGeometry(800, 384)

        val fit = calculatePreviewViewport(tft, source)
        assertEquals(80, fit.x)
        assertEquals(0, fit.y)
        assertEquals(640, fit.width)
        assertEquals(384, fit.height)

        val stretch = calculateStretchViewport(tft, source)
        assertEquals(0, stretch.x)
        assertEquals(0, stretch.y)
        assertEquals(800, stretch.width)
        assertEquals(384, stretch.height)
        assertEquals(0 to 0, stretch.mapToSource(0, 0))

        val crop = calculateFillViewport(tft, source)
        assertEquals(0, crop.x)
        assertEquals(-48, crop.y)
        assertEquals(800, crop.width)
        assertEquals(480, crop.height)
        assertEquals(0 to 48, crop.mapToSource(0, 0))
        assertEquals(799 to 431, crop.mapToSource(799, 383))
    }

    /**
     * The black-bands bug: Android Auto was told to keep margins clear, drew its UI centred inside
     * them, and the compositor then copied the whole coded frame - black rows and all - onto the
     * dashboard. No resolution or display mode could hide them because every mode sampled the
     * full frame.
     */
    @Test
    fun `content margins are sampled out of the coded frame, centred`() {
        val coded = DisplayGeometry(720, 1280)
        val margins = AaAspectMargins(0, 424) // 720x856 usable, matching an 800x951 panel
        val canvas = DisplayGeometry(800, 944)

        val viewport = calculatePreviewViewport(canvas, DisplayGeometry(720, 856))
            .sampleContentOf(coded, margins)

        assertEquals(coded, viewport.source)
        assertEquals(0, viewport.sourceLeft)
        assertEquals(212, viewport.sourceTop) // half the margin, not zero and not all of it
        assertEquals(720, viewport.sourceWidth)
        assertEquals(856, viewport.sourceHeight)
    }

    /** Touch has to follow the crop, or every tap lands half a margin off. */
    @Test
    fun `the first drawn pixel maps to where Android Auto starts drawing`() {
        val coded = DisplayGeometry(720, 1280)
        val margins = AaAspectMargins(0, 424)
        val viewport = calculatePreviewViewport(DisplayGeometry(800, 944), DisplayGeometry(720, 856))
            .sampleContentOf(coded, margins)

        val topLeft = viewport.mapToSource(viewport.x, viewport.y)

        assertEquals(0 to 212, topLeft)
        // ...and subtracting the same half-margin puts it back at Android Auto's own origin.
        assertEquals(0, topLeft!!.second - margins.height / 2)
    }

    @Test
    fun `a viewport without content margins is left exactly as it was`() {
        val viewport = calculatePreviewViewport(DisplayGeometry(800, 480), DisplayGeometry(800, 480))

        assertEquals(viewport, viewport.sampleContentOf(DisplayGeometry(800, 480), AaAspectMargins.NONE))
    }

    @Test
    fun `fit stretch and crop cover every supported source and projection shape`() {
        val cases = listOf(
            DisplayGeometry(800, 480) to DisplayGeometry(800, 384),
            DisplayGeometry(800, 480) to DisplayGeometry(544, 512),
            DisplayGeometry(1280, 720) to DisplayGeometry(800, 480),
            DisplayGeometry(720, 1280) to DisplayGeometry(800, 944),
            DisplayGeometry(720, 1280) to DisplayGeometry(720, 712)
        )

        cases.forEach { (source, canvas) ->
            val fit = calculatePreviewViewport(canvas, source)
            assertEquals(source, fit.source)
            assert(fit.width <= canvas.width && fit.height <= canvas.height)
            assert(fit.mapToSource(fit.x, fit.y) != null)

            val stretch = calculateStretchViewport(canvas, source)
            assertEquals(canvas.width, stretch.width)
            assertEquals(canvas.height, stretch.height)
            assertEquals(0 to 0, stretch.mapToSource(0, 0))

            val crop = calculateFillViewport(canvas, source)
            assert(crop.width >= canvas.width && crop.height >= canvas.height)
            assert(crop.mapToSource(0, 0) != null)
            assert(crop.mapToSource(canvas.width - 1, canvas.height - 1) != null)
        }
    }
}
