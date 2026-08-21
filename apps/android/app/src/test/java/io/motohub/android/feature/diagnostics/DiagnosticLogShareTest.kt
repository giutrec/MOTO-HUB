// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogShareTest {
    @Test
    fun `diagnostic file name is stable and readable`() {
        assertEquals(
            "MOTO-HUB-diagnostics-19700101-000000.txt",
            DiagnosticLogShare.fileName(0L)
        )
    }
}
