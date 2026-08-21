// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.feature.settings.MotoHubSettings
import io.motohub.android.session.ProjectionEventLog

/**
 * Owns the one [EcBtpTimeLink] a session may have, so two callers with different timing can both
 * ask for it without either of them holding the reference.
 *
 * The timing is the whole reason this exists. A rider running CORE alone flips the switch in CORE's
 * own settings, so the transport reads a fresh value when it connects. A rider running ADVANCED
 * flips it there, and the companion's copy only reaches CORE inside `applyAndroidAutoSettings`,
 * which arrives *after* the T-Box connect - about six seconds after, in the log that exposed this.
 * With the link started only at connect, the first ride after enabling would do nothing at all and
 * the rider would reasonably report the feature as broken. So the transport opens it at connect and
 * the IPC bridge re-evaluates when the companion's value lands; whichever happens second is the one
 * that wins, and [refresh] is safe to call either way round.
 */
internal object EcBtpClockChannel {

    private val lock = Any()
    private var link: EcBtpTimeLink? = null

    /**
     * True while a session is running against a dash this channel may be used with.
     *
     * ThinkerRide (KOVE) dashes are excluded: they hold their own GATT connection and a second one
     * is a known way to destabilise the Android Bluetooth stack, which is the very thing those
     * dashes need to work at all.
     */
    private var eligible = false

    /** Called by the transport when a session starts, to say whether this dash qualifies. */
    fun onSessionStarted(context: Context, family: TBoxTransportFamily) {
        synchronized(lock) {
            eligible = family == TBoxTransportFamily.EASYCONN
            if (!eligible) {
                closeLocked()
                return
            }
        }
        refresh(context)
    }

    /**
     * Opens or closes the link to match the setting as it stands right now.
     *
     * Idempotent: calling it while the link is already open and still wanted leaves it alone, so
     * the companion re-pushing its settings at every session start does not churn the connection.
     */
    fun refresh(context: Context) {
        val wanted = MotoHubSettings.bluetoothClockSync(context)
        synchronized(lock) {
            if (!eligible || !wanted) {
                if (link != null) {
                    ProjectionEventLog.record(
                        "TBOX",
                        "EC-BTP: Bluetooth dash-clock sync is off; closing the link."
                    )
                }
                closeLocked()
                return
            }
            if (link != null) return
            val opened = EcBtpTimeLink(
                context = context.applicationContext,
                log = { message -> ProjectionEventLog.record("TBOX", message) }
            )
            link = opened
            runCatching { opened.start() }
                .onFailure {
                    ProjectionEventLog.warning("TBOX", "EC-BTP: the Bluetooth clock link could not start.", it)
                    closeLocked()
                }
        }
    }

    fun onSessionStopped() {
        synchronized(lock) {
            eligible = false
            closeLocked()
        }
    }

    private fun closeLocked() {
        link?.let { runCatching { it.close() } }
        link = null
    }
}
