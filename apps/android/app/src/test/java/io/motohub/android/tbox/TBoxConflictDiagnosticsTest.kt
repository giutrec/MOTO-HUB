// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxConflictDiagnosticsTest {
    @Test
    fun detectsNativeBindFailureOnAnEasyConnLinkPort() {
        assertTrue(
            TBoxConflictDiagnostics.isPortConflict(
                "listen tcp 0.0.0.0:10920: bind: address already in use"
            )
        )
    }

    @Test
    fun detectsPortHeldMessageWithoutAStandardSocketException() {
        assertTrue(
            TBoxConflictDiagnostics.isPortConflict(
                "The bike link ports 10921-10922 are held by another client"
            )
        )
    }

    @Test
    fun ignoresUnrelatedTBoxErrors() {
        assertFalse(TBoxConflictDiagnostics.isPortConflict("connection timed out"))
    }

    @Test
    fun replacesTechnicalConflictWithActionableMessage() {
        assertEquals(
            TBoxConflictDiagnostics.PORT_CONFLICT_MESSAGE,
            TBoxConflictDiagnostics.userFacingMessage("bind: address already in use on 10922")
        )
    }
}
