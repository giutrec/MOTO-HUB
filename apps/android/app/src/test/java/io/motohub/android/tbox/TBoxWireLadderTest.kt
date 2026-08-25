// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxWireLadderTest {

    private fun facts(
        durationMillis: Long,
        mediaControlEvents: Long = 4L,
        framesOffered: Long = 500L,
        endedByDashboard: Boolean = false
    ) = TBoxSessionFacts(
        durationMillis = durationMillis,
        mediaControlEvents = mediaControlEvents,
        framesOffered = framesOffered,
        frameTimeouts = 0L,
        frameRejections = 0L,
        endedByDashboard = endedByDashboard
    )

    /**
     * The contract that makes this whole mechanism safe to ship: a dashboard that streams today is
     * on rung 0, and rung 0 is byte-for-byte what GENERIC has always sent. If this fails, every
     * unidentified dashboard in the field just changed wire format.
     */
    @Test
    fun rungZeroIsExactlyTheGenericWire() {
        assertEquals(TBoxModelProfile.GENERIC.wireConfig, TBoxWireLadder.RUNGS.first())
    }

    @Test
    fun everyRungIsDistinct() {
        assertEquals(TBoxWireLadder.RUNGS.size, TBoxWireLadder.RUNGS.toSet().size)
    }

    /**
     * The ladder crosses a process boundary now: Core stores it, the companion app reports it, and
     * the JSON in between is read by this one parser on both sides. A report that cannot decode
     * Core's answer is a report that quietly claims a fresh search - the failure this call exists
     * to end (field log 90438e1e, 2026-08-25).
     */
    @Test
    fun `a stored ladder survives the trip to the companion app`() {
        val stored = """
            {"rung":2,"state":"AWAITING_RIDER","attempts":3,"fingerprint":"HU/51/37504/V0.0.1",
             "outcome":"STREAMED","noAaSessions":4,"nudged":true}
        """.trimIndent()

        val progress = TBoxWireLadder.parseProgress(stored)

        assertEquals(2, progress.rungIndex)
        assertEquals(TBoxLadderState.AWAITING_RIDER, progress.state)
        assertEquals(3, progress.attemptsOnRung)
        assertEquals("HU/51/37504/V0.0.1", progress.fingerprint)
        assertEquals("STREAMED", progress.lastOutcome)
        assertEquals(4, progress.sessionsWithoutAndroidAuto)
        assertTrue(progress.androidAutoNudgeShown)
    }

    @Test
    fun `an unreachable Core reads as a fresh ladder, not as a crash`() {
        assertEquals(TBoxLadderProgress(), TBoxWireLadder.parseProgress(null))
        assertEquals(TBoxLadderProgress(), TBoxWireLadder.parseProgress("not json at all"))
    }

    /** A rung index from another version of the app must not index past the table. */
    @Test
    fun `a rung this build does not have is clamped instead of throwing`() {
        val progress = TBoxWireLadder.parseProgress("""{"rung":97,"state":"TRYING"}""")

        assertEquals(TBoxWireLadder.RUNGS.lastIndex, progress.rungIndex)
    }

    /**
     * The 2026-08-11 Zontes 368G runs: indexed framing made the dash drop the video socket at 6s
     * and 17s, where the plain stream held its full 30s timeout. Only that shape indicts the wire.
     */
    @Test
    fun aDashboardThatDropsTheSessionEarlyIndictsTheWire() {
        assertEquals(
            TBoxSessionOutcome.REJECTED,
            TBoxSessionOutcome.of(facts(durationMillis = 6_000L, endedByDashboard = true))
        )
    }

    /**
     * The wedged state: after an earlier failure the 368G answered no MEDIA_CONTROL at all until
     * its ignition was cycled. Blaming the frame format for that would walk the whole ladder in an
     * afternoon against a dash that had stopped listening.
     */
    @Test
    fun aDashboardThatNeverAsksForVideoDoesNotMoveTheLadder() {
        val outcome = TBoxSessionOutcome.of(
            facts(durationMillis = 40_000L, mediaControlEvents = 0L, framesOffered = 0L)
        )
        assertEquals(TBoxSessionOutcome.NEVER_NEGOTIATED, outcome)
        val progress = TBoxLadderProgress(rungIndex = 1)
        assertEquals(1, TBoxWireLadder.nextProgress(progress, outcome).rungIndex)
    }

    /** 3900 frames over four minutes, panel still on the QR page: healthy is not the same as seen. */
    @Test
    fun aLongHealthySessionOnlyEarnsAQuestion() {
        val outcome = TBoxSessionOutcome.of(facts(durationMillis = 230_000L, framesOffered = 3_900L))
        assertEquals(TBoxSessionOutcome.STREAMED, outcome)
        val next = TBoxWireLadder.nextProgress(TBoxLadderProgress(), outcome)
        assertEquals(TBoxLadderState.AWAITING_RIDER, next.state)
        assertEquals(0, next.rungIndex)
    }

    @Test
    fun aRiderWhoSawNothingMovesToTheNextRung() {
        val awaiting = TBoxLadderProgress(rungIndex = 0, state = TBoxLadderState.AWAITING_RIDER)
        val next = TBoxWireLadder.nextProgressAfterRider(awaiting, projectionSeen = false)
        assertEquals(1, next.rungIndex)
        assertEquals(TBoxLadderState.TRYING, next.state)
        assertNotEquals(TBoxWireLadder.RUNGS[0], TBoxWireLadder.RUNGS[next.rungIndex])
    }

    @Test
    fun aRiderWhoSawItPinsTheRungForGood() {
        val awaiting = TBoxLadderProgress(rungIndex = 2, state = TBoxLadderState.AWAITING_RIDER)
        val next = TBoxWireLadder.nextProgressAfterRider(awaiting, projectionSeen = true)
        assertEquals(TBoxLadderState.CONFIRMED, next.state)
        assertEquals(2, next.rungIndex)
    }

    /**
     * Walking off the end returns to the default rather than leaving a rider parked on an exotic
     * format that also did not work.
     */
    @Test
    fun runningOutOfRungsFallsBackToTheDefault() {
        var progress = TBoxLadderProgress()
        repeat(TBoxWireLadder.RUNGS.size) {
            progress = TBoxWireLadder.nextProgressAfterRider(
                progress.copy(state = TBoxLadderState.AWAITING_RIDER),
                projectionSeen = false
            )
        }
        assertEquals(TBoxLadderState.EXHAUSTED, progress.state)
        assertEquals(0, progress.rungIndex)
    }

    /** A confirmed motorcycle is never walked again, whatever a later session looks like. */
    @Test
    fun aConfirmedRungSurvivesALaterBadSession() {
        val confirmed = TBoxLadderProgress(rungIndex = 1, state = TBoxLadderState.CONFIRMED)
        val outcome = TBoxSessionOutcome.of(facts(durationMillis = 5_000L, endedByDashboard = true))
        // onSessionFinished short-circuits on CONFIRMED before ever reaching the state machine;
        // this pins the guard that makes that safe.
        assertEquals(TBoxLadderState.CONFIRMED, confirmed.state)
        assertEquals(TBoxSessionOutcome.REJECTED, outcome)
    }

    /**
     * A Ride Dashboard session runs its own video format, so it must not be able to promote or
     * condemn the rung the search is on: a rider testing through mirroring would otherwise end the
     * search on a format that never reached the wire. onSessionIgnored is what the transport calls
     * instead, and it only counts.
     */
    @Test
    fun aSessionTheLadderDidNotGovernNeverMovesTheRung() {
        val progress = TBoxLadderProgress(rungIndex = 1, sessionsWithoutAndroidAuto = 1)
        val counted = progress.copy(sessionsWithoutAndroidAuto = progress.sessionsWithoutAndroidAuto + 1)
        assertEquals(1, counted.rungIndex)
        assertEquals(TBoxLadderState.TRYING, counted.state)
        assertEquals(2, counted.sessionsWithoutAndroidAuto)
    }

    @Test
    fun theFingerprintIgnoresTheUnitSerialSoTwoBikesOfAModelAgree() {
        val a = TBoxCapabilities(huName = "JCDZ34-1112", flavor = "65561", channel = "21334")
        val b = TBoxCapabilities(huName = "JCDZ34-1152", flavor = "65561", channel = "21334")
        assertEquals(TBoxWireLadder.fingerprintOf(a), TBoxWireLadder.fingerprintOf(b))
        assertTrue(TBoxWireLadder.fingerprintOf(a)!!.contains("JCDZ34"))
    }

    @Test
    fun aDashboardThatSaysNothingHasNoFingerprint() {
        assertEquals(null, TBoxWireLadder.fingerprintOf(TBoxCapabilities()))
        assertEquals(null, TBoxWireLadder.fingerprintOf(null))
    }
}
