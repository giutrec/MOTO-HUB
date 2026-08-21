// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatCollapserTest {
    private fun append(decision: RepeatDecision): RepeatDecision.Append {
        assertTrue("expected an append, got $decision", decision is RepeatDecision.Append)
        return decision as RepeatDecision.Append
    }

    @Test
    fun `the first line of a run is written and the rest are folded`() {
        val collapser = RepeatCollapser()
        assertNull(append(collapser.onLine("ENCODER", "keyframe", LogLevel.INFO)).closed)
        repeat(666) {
            assertEquals(
                RepeatDecision.Folded,
                collapser.onLine("ENCODER", "keyframe", LogLevel.INFO)
            )
        }
        // 667 entries in a rider's log became two: the line, and the count.
        val summary = collapser.close()
        assertEquals("The line above repeated 666 more times.", summary?.message)
        assertEquals("ENCODER", summary?.source)
        assertEquals(LogLevel.INFO, summary?.level)
    }

    @Test
    fun `a different line closes the run and is written after the summary`() {
        val collapser = RepeatCollapser()
        collapser.onLine("ENCODER", "keyframe", LogLevel.INFO)
        collapser.onLine("ENCODER", "keyframe", LogLevel.INFO)
        val next = append(collapser.onLine("NETWORK", "T-Box link lost", LogLevel.WARNING))
        assertEquals("The line above repeated 1 more time.", next.closed?.message)
        // The summary belongs to the run that ended, not to the line that ended it.
        assertEquals("ENCODER", next.closed?.source)
        assertEquals(LogLevel.INFO, next.closed?.level)
    }

    @Test
    fun `a line written once leaves no summary behind`() {
        val collapser = RepeatCollapser()
        collapser.onLine("UI", "Main activity resumed", LogLevel.DEBUG)
        assertNull(collapser.close())
        // And a second close on nothing stays silent rather than counting zero.
        assertNull(collapser.close())
    }

    @Test
    fun `same text at a different level or source is not a repeat`() {
        // Levels and sources carry meaning: an INFO and an ERROR reading the same are two
        // different events, and folding them would hide the one that matters.
        val collapser = RepeatCollapser()
        collapser.onLine("TBOX", "handshake", LogLevel.INFO)
        assertNull(append(collapser.onLine("TBOX", "handshake", LogLevel.ERROR)).closed)
        assertNull(append(collapser.onLine("NETWORK", "handshake", LogLevel.ERROR)).closed)
    }

    @Test
    fun `alternating lines never fold - the documented limit`() {
        // Stated in the class doc and pinned here so nobody assumes otherwise: A B A B is
        // exactly the case this design does not cover.
        val collapser = RepeatCollapser()
        repeat(4) { index ->
            val decision = collapser.onLine("ENCODER", if (index % 2 == 0) "a" else "b", LogLevel.INFO)
            assertTrue(decision is RepeatDecision.Append)
        }
        assertNull(collapser.close())
    }

    @Test
    fun `reset drops the run without producing a summary`() {
        val collapser = RepeatCollapser()
        collapser.onLine("ENCODER", "keyframe", LogLevel.INFO)
        collapser.onLine("ENCODER", "keyframe", LogLevel.INFO)
        collapser.reset()
        assertNull(collapser.close())
    }
}
