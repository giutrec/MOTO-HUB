// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.encoding.VideoAccessUnitSink

class TBoxVideoSink(
    private val handle: TBoxSessionHandle
) : VideoAccessUnitSink {
    override fun offerAccessUnit(accessUnit: ByteArray): Boolean =
        handle.transport.offerAccessUnit(accessUnit)

    override fun offerStill(jpeg: ByteArray, frameId: Int): Boolean =
        handle.transport.offerStillFrame(jpeg, frameId)
}
