// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import android.os.SystemClock

/**
 * Decides when rejected access units mean the T-Box link is really gone.
 *
 * A rider log (build 91, OnePlus CPH2449) showed a mirroring session ending after 1104 delivered
 * frames because *one* access unit was rejected while the previous `pushFrame()` was still
 * running - a momentary overlap the transport recovers from on the next frame. Ending the
 * session on the first rejection turns that hiccup into a dropped ride.
 *
 * A dead transport rejects continuously, so the fatal condition is a streak: at least
 * [minRejections] consecutive rejections spanning at least [minStreakMillis]. Any accepted frame
 * clears the streak. Both bounds matter - the count alone would fire on a fast burst at 30fps,
 * and the duration alone would fire on a slow trickle that is still mostly delivering.
 */
class VideoBackpressureGuard(
    private val minRejections: Int = DEFAULT_MIN_REJECTIONS,
    private val minStreakMillis: Long = DEFAULT_MIN_STREAK_MILLIS,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    private val lock = Any()
    private var consecutiveRejections = 0
    private var streakStartedAt = 0L
    private var totalRejections = 0L

    fun onAccepted() {
        synchronized(lock) {
            consecutiveRejections = 0
            streakStartedAt = 0L
        }
    }

    /** Records a rejected access unit and returns true when the session must be torn down. */
    fun onRejected(): Boolean {
        synchronized(lock) {
            val now = clock()
            if (consecutiveRejections == 0) streakStartedAt = now
            consecutiveRejections++
            totalRejections++
            return consecutiveRejections >= minRejections &&
                now - streakStartedAt >= minStreakMillis
        }
    }

    /** True for the first rejection of a streak, so callers log the hiccup once instead of per frame. */
    fun isStreakStart(): Boolean = synchronized(lock) { consecutiveRejections == 1 }

    fun rejectionStreak(): Int = synchronized(lock) { consecutiveRejections }

    fun totalRejections(): Long = synchronized(lock) { totalRejections }

    fun streakMillis(): Long = synchronized(lock) {
        if (consecutiveRejections == 0) 0L else clock() - streakStartedAt
    }

    companion object {
        /** ~1s of a fully blocked transport at 30fps, on top of the 1s the transport already waits. */
        const val DEFAULT_MIN_REJECTIONS = 30

        /** The transport's own submit grace period is 1s, so require three of them back to back. */
        const val DEFAULT_MIN_STREAK_MILLIS = 3_000L
    }
}
