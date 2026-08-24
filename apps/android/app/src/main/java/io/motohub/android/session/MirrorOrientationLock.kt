// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.content.pm.ActivityInfo
import io.motohub.android.androidauto.DisplayGeometry
import io.motohub.android.tbox.TBoxModelProfile

/**
 * The shape the phone has to take so a mirrored session fills the TFT.
 *
 * Sensor orientations rather than fixed ones: which way round the phone sits in its cradle is the
 * rider's business, and a lock that picks a side for them ends up upside down on half the mounts.
 */
enum class MirrorShape(val requestedOrientation: Int) {
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
}

/**
 * Which way the phone should be turned while mirroring.
 *
 * Mirroring hands the dash a VirtualDisplay the size of the TFT and lets the system fill it with
 * `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`, and that mirror preserves the *phone's* aspect ratio. So a
 * portrait phone on a landscape dash does not stretch to fit, it lands in a centred strip with
 * black either side: a 1080x2400 phone on an 800x480 panel keeps 480*1080/2400 = 216 pixels of the
 * 800 available, 27% of the TFT. Nothing downstream can undo that - by the time the frame reaches
 * the encoder the black is part of the picture. The only lever is the phone's own rotation, which
 * is why this exists and why the session, not the rider, sets it.
 *
 * The panel is taken from the geometry the dash reported on a previous ride and fell back to the
 * profile's own figure, because the live one is not known until the handshake, well after consent
 * has to be asked for. Guessing wrong is cheap: AUTO_MIRROR re-mirrors continuously, so a rotation
 * applied at any point is picked up.
 */
object MirrorOrientationLock {

    /**
     * [saved] is the last measured TFT area for this motorcycle, [modelProfile] the profile that
     * will be resolved for it. Square panels count as landscape - it is the shape every dash that
     * is not explicitly portrait has, and it is what an unrecognised dash gets.
     */
    fun shapeFor(saved: DisplayGeometry?, modelProfile: TBoxModelProfile): MirrorShape {
        val width: Int
        val height: Int
        val fallback = modelProfile.fallbackTBoxVideoArea
        when {
            saved != null -> {
                width = saved.width
                height = saved.height
            }
            fallback != null -> {
                width = fallback.width
                height = fallback.height
            }
            // Most profiles carry no fallback area at all - they wait for the dash to say. There is
            // nothing to go on before the handshake, and landscape is the shape of nearly every TFT.
            else -> return MirrorShape.LANDSCAPE
        }
        return if (width >= height) MirrorShape.LANDSCAPE else MirrorShape.PORTRAIT
    }
}
