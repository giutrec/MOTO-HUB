// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

interface VideoAccessUnitSink {
    fun offerAccessUnit(accessUnit: ByteArray): Boolean

    /**
     * Offers one JPEG still with the frame id the dash acknowledges by. False by default, so a
     * sink with no still path refuses instead of quietly dropping the frame id.
     */
    fun offerStill(jpeg: ByteArray, frameId: Int): Boolean = false

    fun close() = Unit
}
