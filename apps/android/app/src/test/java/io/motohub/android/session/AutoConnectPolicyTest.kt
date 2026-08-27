// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPolicyTest {
    private fun decide(
        riderCancelled: Boolean = false,
        previousAttempts: Int = 0,
        dashBroadcasting: Boolean? = null,
    ) = autoConnectDecision(riderCancelled, previousAttempts, dashBroadcasting)

    @Test
    fun firstAttemptOfALaunchAlwaysRuns() {
        // The attempt the rider actually waits for. A scan can be minutes old at launch, so
        // even a definite absence must not stop this one.
        assertEquals(AutoConnectDecision.Go, decide(dashBroadcasting = false))
        assertEquals(AutoConnectDecision.Go, decide(dashBroadcasting = null))
        assertEquals(AutoConnectDecision.Go, decide(dashBroadcasting = true))
    }

    @Test
    fun aCancelIsNotAnsweredByTheNextResume() {
        // Rider c110050c, 2026-08-26: cancelled at 18:41:04, got a fresh attempt at 18:42:04,
        // cancelled again. And 2026-08-25 21:53:51 → 21:53:56, that one while he was starting
        // phone-only Android Auto.
        assertTrue(decide(riderCancelled = true, dashBroadcasting = null) is AutoConnectDecision.Skip)
        assertTrue(decide(riderCancelled = true, dashBroadcasting = false) is AutoConnectDecision.Skip)
    }

    @Test
    fun aCancelIsLiftedWhenTheDashIsSeenOnTheAir() {
        // The rider said no to a hunt that could not have worked. Once the bike is demonstrably
        // broadcasting the situation has changed, and that is what auto-connect is for.
        assertEquals(
            AutoConnectDecision.Go,
            decide(riderCancelled = true, previousAttempts = 3, dashBroadcasting = true)
        )
    }

    @Test
    fun retriesStopWhileTheDashIsDefinitelyAbsent() {
        // Rider c110050c, 2026-08-25 21:46-21:47: four attempts fired by the ON_RESUME of
        // returning from the photo picker, each burning a 30s+6s Wi-Fi request for an SSID CORE
        // had already reported missing from a 5-network scan.
        assertTrue(decide(previousAttempts = 1, dashBroadcasting = false) is AutoConnectDecision.Skip)
        assertTrue(decide(previousAttempts = 9, dashBroadcasting = false) is AutoConnectDecision.Skip)
    }

    @Test
    fun noScanEvidenceNeverBlocksARetry() {
        // The tri-state is the whole safety of this gate: a phone that hands back nothing is
        // describing itself, not the dash, and must not be allowed to convict it.
        assertEquals(AutoConnectDecision.Go, decide(previousAttempts = 5, dashBroadcasting = null))
    }

    @Test
    fun aSightingKeepsTheRetriesComing() {
        assertEquals(AutoConnectDecision.Go, decide(previousAttempts = 5, dashBroadcasting = true))
    }

    @Test
    fun everySkipSaysWhyInTheLog() {
        // These strings land in a rider's log and are the only account of a connect that did not
        // happen; an empty one would read as a bug in the app rather than a decision.
        listOf(
            decide(riderCancelled = true, dashBroadcasting = false),
            decide(previousAttempts = 1, dashBroadcasting = false),
        ).forEach { decision ->
            assertTrue((decision as AutoConnectDecision.Skip).reason.length > 20)
        }
    }
}
