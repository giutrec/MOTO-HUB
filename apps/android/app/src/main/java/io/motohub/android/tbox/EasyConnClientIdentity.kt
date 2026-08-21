// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/**
 * The application identity MOTO-HUB presents in the EasyConn wake probe.
 *
 * The probe's JSON body names the companion app asking the dash to wake up. Firmware that checks
 * the name against its own list of accepted companions will simply never acknowledge an unknown
 * one, and on a Wi-Fi Direct group the wake probe is the only route to a host — so a single
 * hard-coded name turns "this brand doesn't recognise us" into an unexplained connection failure.
 *
 * Rather than guess one universal name, the probe walks a short ladder of candidates and keeps the
 * one that earns an acknowledgement. The winner is remembered for the rest of the process so a
 * reconnect leads with what already worked instead of paying for the search again.
 */
internal object EasyConnClientIdentity {
    /**
     * Tried in order. The CFMOTO companion is first because it is the one proven against real
     * hardware; the Carbit framework namespace follows as the most plausible name for a dash from
     * another manufacturer, since that is the package the reference EasyConn SDK ships under.
     */
    val candidates: List<String> = listOf(
        "com.cfmoto.cfmotointernational",
        "net.easyconn.carman",
        "com.carbit.easyconnect"
    )

    /** The first candidate, used wherever a probe has not yet settled the question. */
    val default: String get() = candidates.first()

    @Volatile
    private var accepted: String? = null

    /**
     * Candidates ordered for the next probe: an identity that has already been acknowledged on
     * this device leads, and the untried ones follow so a wrong guess is still recoverable.
     */
    fun probeOrder(): List<String> {
        val winner = accepted ?: return candidates
        return listOf(winner) + candidates.filterNot { it == winner }
    }

    /** Callers log the acknowledgement themselves; this stays a plain value holder. */
    fun remember(identity: String) {
        accepted = identity
    }

    /** Only for tests, which must not inherit a winner from an earlier case. */
    internal fun forget() {
        accepted = null
    }

    fun probeBody(identity: String): String =
        "{\"phoneType\":\"Android\",\"packageName\":\"$identity\"}"
}
