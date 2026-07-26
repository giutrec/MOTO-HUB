package io.motohub.android.androidauto

import io.motohub.android.session.ProjectionEventLog

/**
 * Port 5288 (AaReceiver.PORT) is a single fixed local socket, so only one AaReceiver can be
 * bound at a time - but three independent features each build their own instance:
 * AndroidAutoSessionService (a real T-Box session), PhoneOnlyAndroidAutoBridge (no T-Box), and
 * EmbeddedAndroidAutoSource (an embedded AA panel). None of them previously knew
 * about the others, so if one was left running (e.g. a phone-only session backgrounded without
 * being stopped) and a second one tried to start, `AaReceiver.start()` failed outright with
 * "Android Auto local port 5288 is unavailable" - a raw bind failure with no recovery except
 * force-killing the app to release the socket at the OS level.
 *
 * Every owner must [claim] the port (which stops whichever owner held it before) right before
 * building its own AaReceiver, and [release] its claim when it stops. This turns the collision
 * into an automatic hand-off - the newest request to start Android Auto always wins - instead of
 * a hard failure.
 */
object AndroidAutoReceiverOwnership {
    private val lock = Any()
    private var ownerName: String? = null
    private var ownerStop: (() -> Unit)? = null

    /**
     * The previous owner's [stop] runs outside the lock, only after [ownerName]/[ownerStop]
     * have already been reassigned - calling out to arbitrary code (another component's full
     * teardown, itself possibly synchronized on its own instance) while holding this object's
     * lock risks a lock-ordering deadlock against a thread doing the reverse (holding that
     * component's lock, waiting to [release] here).
     */
    fun claim(name: String, stop: () -> Unit) {
        val previousName: String?
        val previousStop: (() -> Unit)?
        synchronized(lock) {
            previousName = ownerName
            previousStop = if (previousName != null && previousName != name) ownerStop else null
            ownerName = name
            ownerStop = stop
        }
        if (previousStop != null) {
            ProjectionEventLog.record(
                "ANDROID AUTO",
                "$name is taking over the Android Auto receiver from $previousName."
            )
            runCatching { previousStop.invoke() }
        }
    }

    fun release(name: String) {
        synchronized(lock) {
            if (ownerName == name) {
                ownerName = null
                ownerStop = null
            }
        }
    }
}
