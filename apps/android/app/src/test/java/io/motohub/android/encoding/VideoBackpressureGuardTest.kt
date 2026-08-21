// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBackpressureGuardTest {
    private var now = 0L
    private val guard = VideoBackpressureGuard(
        minRejections = 3,
        minStreakMillis = 1_000L,
        clock = { now }
    )

    @Test
    fun `a single rejection never ends the session`() {
        assertFalse(guard.onRejected())
        assertTrue(guard.isStreakStart())
        assertEquals(1L, guard.totalRejections())
    }

    @Test
    fun `an accepted frame clears the streak`() {
        repeat(2) { guard.onRejected() }
        now += 5_000L
        guard.onAccepted()

        assertEquals(0, guard.rejectionStreak())
        assertFalse(guard.onRejected())
    }

    @Test
    fun `a burst shorter than the window is tolerated`() {
        now = 100L
        repeat(10) {
            assertFalse(guard.onRejected())
            now += 30L
        }
    }

    @Test
    fun `a sustained streak ends the session`() {
        assertFalse(guard.onRejected())
        now += 600L
        assertFalse(guard.onRejected())
        now += 600L

        assertTrue(guard.onRejected())
        assertEquals(3, guard.rejectionStreak())
        assertEquals(1_200L, guard.streakMillis())
    }

    @Test
    fun `the streak restarts after a recovery`() {
        assertFalse(guard.onRejected())
        now += 5_000L
        guard.onAccepted()

        assertFalse(guard.onRejected())
        now += 5_000L
        assertFalse(guard.onRejected())
        now += 5_000L
        assertTrue(guard.onRejected())
    }
}
