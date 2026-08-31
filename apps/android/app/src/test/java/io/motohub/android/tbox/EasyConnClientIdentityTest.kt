// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EasyConnClientIdentityTest {
    @Before
    @After
    fun clearRememberedIdentity() {
        EasyConnClientIdentity.forget()
    }

    @Test
    fun leadsWithTheProvenIdentityBeforeAnyProbeHasRun() {
        assertEquals("com.cfmoto.cfmotointernational", EasyConnClientIdentity.probeOrder().first())
        assertEquals(EasyConnClientIdentity.default, EasyConnClientIdentity.probeOrder().first())
    }

    @Test
    fun offersEveryCandidateExactlyOnce() {
        val order = EasyConnClientIdentity.probeOrder()

        assertEquals(EasyConnClientIdentity.candidates.size, order.size)
        assertEquals(EasyConnClientIdentity.candidates.toSet(), order.toSet())
    }

    @Test
    fun promotesAnAcknowledgedIdentityWithoutDroppingTheOthers() {
        val late = EasyConnClientIdentity.candidates.last()
        EasyConnClientIdentity.remember(late)

        val order = EasyConnClientIdentity.probeOrder()

        assertEquals(late, order.first())
        // The rest must survive: a dash that answered once can still refuse after a firmware
        // update, and the ladder is the only way back.
        assertEquals(EasyConnClientIdentity.candidates.toSet(), order.toSet())
    }

    @Test
    fun namesTheIdentityInsideTheProbeBody() {
        val body = EasyConnClientIdentity.probeBody("com.example.companion")

        assertTrue(body.contains("\"packageName\":\"com.example.companion\""))
        assertTrue(body.contains("\"phoneType\":\"Android\""))
    }
}
