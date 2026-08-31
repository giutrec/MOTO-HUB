// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasyConnMetadataTest {
    @Test
    fun usesThePackageAdvertisedByTheTbox() {
        assertEquals(
            "net.easyconn.receiver",
            decodeEasyConnPackage("  net.easyconn.receiver  ".toByteArray())
        )
    }

    @Test
    fun rejectsMissingPackageMetadataInsteadOfInventingOne() {
        assertNull(decodeEasyConnPackage(null))
        assertNull(decodeEasyConnPackage("   ".toByteArray()))
    }
}
