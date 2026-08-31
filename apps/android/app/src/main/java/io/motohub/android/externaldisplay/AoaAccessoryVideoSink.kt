// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.externaldisplay

import io.motohub.android.encoding.VideoAccessUnitSink

class AoaAccessoryVideoSink(
    private val session: AoaAccessorySession
) : VideoAccessUnitSink {
    override fun offerAccessUnit(accessUnit: ByteArray): Boolean {
        session.write(accessUnit)
        return true
    }

    override fun close() {
        session.close()
    }
}
