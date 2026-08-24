// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import io.motohub.android.androidauto.DisplayGeometry
import io.motohub.android.tbox.TBoxModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MirrorOrientationLockTest {

    @Test
    fun measuredGeometryDecidesTheShape() {
        assertEquals(
            MirrorShape.LANDSCAPE,
            MirrorOrientationLock.shapeFor(DisplayGeometry(800, 480), TBoxModelProfile.GENERIC)
        )
        assertEquals(
            MirrorShape.PORTRAIT,
            MirrorOrientationLock.shapeFor(DisplayGeometry(480, 800), TBoxModelProfile.GENERIC)
        )
    }

    /** The measurement is the dash's own word for its panel; the profile is only a guess. */
    @Test
    fun measuredGeometryWinsOverTheProfileFallback() {
        val portraitProfile = TBoxModelProfile.entries.first {
            val area = it.fallbackTBoxVideoArea
            area != null && area.height > area.width
        }
        assertEquals(
            MirrorShape.LANDSCAPE,
            MirrorOrientationLock.shapeFor(DisplayGeometry(800, 480), portraitProfile)
        )
    }

    @Test
    fun withoutAMeasurementTheProfileFallbackDecides() {
        val portraitProfile = TBoxModelProfile.entries.first {
            val area = it.fallbackTBoxVideoArea
            area != null && area.height > area.width
        }
        assertEquals(
            MirrorShape.PORTRAIT,
            MirrorOrientationLock.shapeFor(saved = null, modelProfile = portraitProfile)
        )
        val landscapeProfile = TBoxModelProfile.entries.first {
            val area = it.fallbackTBoxVideoArea
            area != null && area.width >= area.height
        }
        assertEquals(
            MirrorShape.LANDSCAPE,
            MirrorOrientationLock.shapeFor(saved = null, modelProfile = landscapeProfile)
        )
    }

    /** Most profiles never declare an area; landscape is the only sane thing to assume. */
    @Test
    fun aProfileWithNoFallbackAreaStaysLandscape() {
        val undeclared = TBoxModelProfile.entries.first { it.fallbackTBoxVideoArea == null }
        assertEquals(
            MirrorShape.LANDSCAPE,
            MirrorOrientationLock.shapeFor(saved = null, modelProfile = undeclared)
        )
    }

    /**
     * A square panel has no wrong answer, and landscape is the one every unrecognised dash gets.
     * Falling to portrait here would rotate a phone away from the shape most TFTs actually are.
     */
    @Test
    fun squarePanelsCountAsLandscape() {
        assertEquals(
            MirrorShape.LANDSCAPE,
            MirrorOrientationLock.shapeFor(DisplayGeometry(544, 544), TBoxModelProfile.GENERIC)
        )
    }

    /** Sensor orientations, so the rider's cradle decides which way up - not us. */
    @Test
    fun theTwoShapesRequestDifferentOrientations() {
        assertNotEquals(
            MirrorShape.LANDSCAPE.requestedOrientation,
            MirrorShape.PORTRAIT.requestedOrientation
        )
    }
}
