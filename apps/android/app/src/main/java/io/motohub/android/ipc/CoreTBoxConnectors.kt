// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// CORE-only: who owns the one T-Box connector this process is allowed to have.
package io.motohub.android.ipc

import android.content.Context
import io.motohub.android.session.ProjectionEventLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds at most one live [T] and releases the previous one before handing out its replacement.
 *
 * Generic and free of Android types so the invariant that actually broke - "acquiring a second
 * one releases the first, exactly once, even under concurrent callers" - can be unit tested. The
 * bug it exists to prevent needs a motorcycle, twenty-five minutes and a process death to
 * reproduce, so a test that pins the rule is the only verification available.
 */
internal class SingleLiveInstance<T : Any>(
    private val release: suspend (T) -> Unit,
    /**
     * Where a failed teardown is reported. Injected rather than logged directly so this class
     * stays free of Android types and runs in a plain JVM test - the first version reached for
     * ProjectionEventLog here and the "a failing release still adopts the replacement" test
     * failed on android.util.Log instead of on the behaviour it was written for.
     */
    private val onReleaseFailure: (Throwable) -> Unit = {}
) {
    private val mutex = Mutex()
    private var current: T? = null

    /** Releases whatever is held, then creates and adopts the replacement. */
    suspend fun replace(create: () -> T): T = mutex.withLock {
        releaseCurrentLocked()
        create().also { current = it }
    }

    /**
     * The live instance if [reuse] accepts it, otherwise [replace]'s behaviour: release it and
     * adopt a fresh one. Runs under the same lock as [replace], so "at most one live instance"
     * holds exactly as it does there - a reuse decision and a concurrent replacement can never
     * both see the pre-decision instance as live.
     */
    suspend fun acquire(reuse: (T) -> Boolean, create: () -> T): T = mutex.withLock {
        current?.let { if (reuse(it)) return@withLock it }
        releaseCurrentLocked()
        create().also { current = it }
    }

    /** The live instance, or null when nothing is held. */
    fun peek(): T? = current

    suspend fun clear() = mutex.withLock { releaseCurrentLocked() }

    private suspend fun releaseCurrentLocked() {
        val previous = current ?: return
        // Cleared before releasing, not after: release() suspends, and a caller that saw the old
        // instance in that window would work with something already being torn down.
        current = null
        runCatching { release(previous) }.onFailure(onReleaseFailure)
    }
}

/**
 * The process-wide owner of CORE's [CoreTBoxConnector].
 *
 * Every AIDL `connect()` used to build its own connector, and with it its own
 * [TBoxNetworkConnector][io.motohub.android.tbox.TBoxNetworkConnector] and its own exclusive
 * `WifiNetworkSpecifier` request. Nothing released the previous one: `disconnect()` reaches the
 * session through `TBoxSessionRegistry`, so a connector whose session had already been cleared
 * became unreachable while its request and its rejoin ladder kept running. Two of those compete
 * for the same association and each release drops the other's network, which the rider
 * experiences as a connection that survives a few seconds, over and over, until the bike is
 * switched off and on.
 *
 * Found on 2026-07-30 (OnePlus, 1.1.24) after the companion app's process was killed 26 minutes
 * into a Ride Dashboard session: the restart asked CORE to connect again while the previous
 * connector was still hunting, and the log showed every network line two, three and four times
 * over. Eight AIDL connects in one log, each one another orphan.
 */
internal object CoreTBoxConnectors {
    private val live = SingleLiveInstance<CoreTBoxConnector>(
        release = { it.cancel() },
        onReleaseFailure = {
            ProjectionEventLog.warning("IPC_TBOX", "Releasing the previous T-Box connector failed.", it)
        }
    )

    /**
     * The connector for a new connect attempt targeting [ssid].
     *
     * The previous one is reused as-is when it is still chasing this same SSID and has not yet
     * installed a session ([CoreTBoxConnector.isReusableFor]) - so a retry (auto-reconnect, the
     * rider reopening the app, a QR rescan) joins a `WifiNetworkSpecifier` hunt already in
     * progress instead of tearing it down and asking Android to start over. That reset-to-zero
     * was silently defeating the "reuse a still-pending request" fix in
     * [io.motohub.android.tbox.TBoxNetworkConnector.connect] for every AIDL retry, because that
     * fix lives on the connector *instance* and every retry used to be handed a brand new one -
     * QJ SRT 700X field log, 2026-08-05: the phone never associated within 30s on any of six
     * consecutive retries across two days, each one a fresh hunt that could only succeed by luck
     * against Android's own scan timing.
     *
     * Any other case - a different SSID, or a connector that already has a live session - is torn
     * down and replaced exactly as before. Releasing through `cancel()` rather than `disconnect()`
     * is deliberate: `cancel()` tears down *that* connector's own transport and network connector
     * directly, which is exactly what an orphan needs, while `disconnect()` would look up whatever
     * session the registry happens to hold and could leave the orphan's Wi-Fi request open.
     */
    suspend fun acquire(context: Context, ssid: String): CoreTBoxConnector =
        live.acquire(reuse = { it.isReusableFor(ssid) }, create = { CoreTBoxConnector(context) })

    fun current(): CoreTBoxConnector? = live.peek()

    suspend fun clear() = live.clear()
}
