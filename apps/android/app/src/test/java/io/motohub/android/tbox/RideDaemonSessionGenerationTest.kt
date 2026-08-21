// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDaemonSessionGenerationTest {
    @Test
    fun callbackFromActiveSessionIsAccepted() {
        assertTrue(isCurrentRideDaemonSession(callbackGeneration = 7L, activeGeneration = 7L))
    }

    @Test
    fun callbackAfterStopIsIgnored() {
        assertFalse(isCurrentRideDaemonSession(callbackGeneration = 7L, activeGeneration = 0L))
    }

    @Test
    fun callbackFromPreviousSessionIsIgnored() {
        assertFalse(isCurrentRideDaemonSession(callbackGeneration = 7L, activeGeneration = 8L))
    }
}
