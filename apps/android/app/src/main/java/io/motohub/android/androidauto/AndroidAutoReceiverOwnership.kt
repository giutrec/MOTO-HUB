// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import io.motohub.android.session.ProjectionEventLog

/**
 * Port 5288 (AaReceiver.PORT) is a single fixed local socket, so only one AaReceiver can be
 * bound at a time - but three independent features each build their own instance:
 * AndroidAutoSessionService (a real T-Box session), PhoneOnlyAndroidAutoBridge (no T-Box), and
 * IpcBridgeService's embedded receiver (an AA panel driven by the companion app over AIDL). None
 * of them previously knew about the others, so if one was left running (e.g. a phone-only session
 * backgrounded without being stopped) and a second one tried to start, `AaReceiver.start()` failed
 * outright with "Android Auto local port 5288 is unavailable" - a raw bind failure with no
 * recovery except force-killing the app to release the socket at the OS level.
 *
 * Every owner must [claim] the port (which stops whichever owner held it before) right before
 * building its own AaReceiver, and [release] its claim when it stops. This turns the collision
 * into an automatic hand-off - the newest request to start Android Auto always wins - instead of
 * a hard failure.
 *
 * Ownership is keyed on the owning *object*, never on [name] (which is only there to say who took
 * over from whom in the log). Keying on the name let two different instances of the same feature
 * impersonate each other: a second PhoneOnlyAndroidAutoBridge could [claim] without stopping the
 * first (same name), and - worse - a bridge that had never claimed anything could [release] the
 * live owner's registration, dropping the only reference able to close the bound socket. The
 * socket then stayed bound for the life of the process and every later Android Auto start failed
 * with EADDRINUSE, while mirroring and Ride Dashboard (which never touch 5288) kept working.
 */
object AndroidAutoReceiverOwnership {
    private val lock = Any()
    private var owner: Any? = null
    private var ownerName: String? = null
    private var ownerStop: (() -> Unit)? = null

    /**
     * Injected the same way AaReceiver/AaCompositor take their `log` lambda, so the hand-off can
     * be exercised in a JVM unit test (where `android.util.Log` throws) without the port and its
     * two-instance failure mode being testable only on a device.
     */
    internal var log: (String) -> Unit = { ProjectionEventLog.record("ANDROID AUTO", it) }

    /**
     * The previous owner's [stop] runs outside the lock, only after [owner]/[ownerStop] have
     * already been reassigned - calling out to arbitrary code (another component's full teardown,
     * itself possibly synchronized on its own instance) while holding this object's lock risks a
     * lock-ordering deadlock against a thread doing the reverse (holding that component's lock,
     * waiting to [release] here).
     */
    fun claim(owner: Any, name: String, stop: () -> Unit) {
        val previousName: String?
        val previousStop: (() -> Unit)?
        synchronized(lock) {
            val previousOwner = this.owner
            previousName = ownerName
            previousStop = if (previousOwner != null && previousOwner !== owner) ownerStop else null
            this.owner = owner
            ownerName = name
            ownerStop = stop
        }
        if (previousStop != null) {
            log("$name is taking over the Android Auto receiver from $previousName.")
            runCatching { previousStop.invoke() }
        }
    }

    /** No-op unless [owner] is the object that actually holds the claim. */
    fun release(owner: Any) {
        synchronized(lock) {
            if (this.owner === owner) {
                this.owner = null
                ownerName = null
                ownerStop = null
            }
        }
    }
}
