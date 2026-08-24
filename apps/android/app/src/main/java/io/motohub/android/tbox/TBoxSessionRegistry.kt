// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog

data class TBoxSessionHandle(
    val transport: TBoxTransport,
    val host: TBoxHost,
    val networkConnector: TBoxNetworkConnector,
    val motorcycle: MotorcycleProfile,
    val link: TBoxLink
)

/**
 * In-process handoff from connection UI to the foreground projection service.
 *
 * One T-Box session is shared by every mode that can be running at once (mirroring, Android
 * Auto, Ride Dashboard, and - in Core - a companion app driving it over IPC). Ending one mode
 * must not tear the session out from under another: stopping an Android Auto session that had
 * never streamed a frame once killed a live Ride Dashboard, whose watchdog then read the broken
 * pipe as a fault and silently rebuilt everything, so the rider's Stop appeared to do nothing.
 * Modes therefore [claim] the session and end it through [releaseAndClear], which only really
 * tears it down once the last claim is gone.
 */
/** Who is currently using the shared session. Pure bookkeeping, unit-tested on its own. */
internal class SessionConsumers {
    private val consumers = linkedSetOf<String>()

    /** @return true when [consumer] was not already holding the session. */
    fun claim(consumer: String): Boolean = consumers.add(consumer)

    fun release(consumer: String) {
        consumers -= consumer
    }

    /** @return true when dropping [consumer] leaves nobody using the session. */
    fun releaseIsLast(consumer: String): Boolean {
        consumers -= consumer
        return consumers.isEmpty()
    }

    fun clear() = consumers.clear()

    fun describe(): String = consumers.joinToString()
}

object TBoxSessionRegistry {
    private var activeHandle: TBoxSessionHandle? = null
    private val consumers = SessionConsumers()
    /**
     * Everyone who held the session at any point in its life, unlike [consumers] which only knows
     * who holds it right now. [TBoxWireLadder] needs the difference: by the time a session ends,
     * the mode that ran it has already let go, and "which mode was this?" is exactly the question
     * that decides whether the session is evidence about the video format or not.
     */
    private val consumersSeen = linkedSetOf<String>()

    @Synchronized
    fun install(handle: TBoxSessionHandle) {
        activeHandle = handle
        consumers.clear()
        consumersSeen.clear()
        // The installed session holds its own interest in the shared network connector, released
        // in [clear]. This - not a skipped disconnect in whoever created the connector - is what
        // keeps the Wi-Fi request alive when the connecting ViewModel dies under a running
        // stream, and what lets the request actually drop when the last session ends.
        // Idempotent across a replacing install; a no-op for a handle built around an unmanaged
        // connector (tests).
        TBoxNetworkConnectors.adoptForSession(handle.networkConnector)
        // Honour the rider's manual pin. Reporting the modelId's own answer here made the log
        // contradict itself for anyone who had overridden the profile - the session line said
        // "Generic EasyConn dashboard" while the mode that started moments later reported the
        // pinned profile, which reads as "the override was ignored" when it was not.
        val modelProfile = TBoxModelProfile.resolve(
            handle.motorcycle.modelId,
            null,
            ProfileOverride.byKey(handle.motorcycle.profileOverrideKey)
        )
        ProjectionEventLog.record(
            "SESSION",
            "Registry installed T-Box ${handle.host.ipAddress}:${handle.host.port} for " +
                "${handle.motorcycle.ssid}; model=${modelProfile.displayName}, " +
                "modelId=${handle.motorcycle.modelId ?: "unknown"}."
        )
    }

    @Synchronized
    fun current(): TBoxSessionHandle? = activeHandle

    /**
     * Who is using the active session right now, for a caller deciding whether it may take the
     * radio. Empty when nobody holds it - including when there is no session at all.
     */
    @Synchronized
    fun activeConsumers(): String = if (activeHandle == null) "" else consumers.describe()

    /** Registers [consumer] as a user of the active session. No-op when there is none. */
    @Synchronized
    fun claim(consumer: String): Boolean {
        if (activeHandle == null) return false
        consumersSeen += consumer
        if (consumers.claim(consumer)) {
            ProjectionEventLog.debug("SESSION", "T-Box session claimed by $consumer.")
        }
        return true
    }

    /** True when [consumer] held the running session at any point, even if it has since let go. */
    @Synchronized
    fun everClaimed(consumer: String): Boolean = consumer in consumersSeen

    /** Drops [consumer]'s claim without touching the session itself. */
    @Synchronized
    fun release(consumer: String) {
        consumers.release(consumer)
    }

    /**
     * Ends [consumer]'s use of [handle] and tears the session down only if nothing else holds it.
     *
     * @return true when the session was actually cleared, so the caller may also stop the
     *   transport and drop the network. False means another mode is still streaming on it and
     *   the caller must leave the transport alone.
     */
    @Synchronized
    fun releaseAndClear(consumer: String, handle: TBoxSessionHandle? = null): Boolean {
        val wasLast = consumers.releaseIsLast(consumer)
        if (handle != null && activeHandle !== handle) return false
        if (activeHandle == null) return false
        if (!wasLast) {
            ProjectionEventLog.record(
                "SESSION",
                "T-Box session kept after $consumer stopped: still used by ${consumers.describe()}."
            )
            return false
        }
        clear(handle)
        return true
    }

    /**
     * Unconditional teardown, for an explicit rider disconnect. Mode teardowns must use
     * [releaseAndClear] instead so they cannot end a session another mode is still using.
     */
    @Synchronized
    fun clear(handle: TBoxSessionHandle? = null) {
        if (handle == null || activeHandle === handle) {
            val previous = activeHandle
            activeHandle = null
            consumers.clear()
            if (previous != null) {
                previous.link.disconnect()
                // Registry monitor -> connector-owner lock, never the reverse; see the ordering
                // note on TBoxNetworkConnectors. The session's interest goes with the session:
                // when it was the last one, this is the release that actually drops the Wi-Fi
                // request - previously nothing here touched the connector, and a cleared
                // session's request could keep hunting for three more minutes.
                TBoxNetworkConnectors.releaseSession()
                ProjectionEventLog.record("SESSION", "T-Box registry cleared.")
            }
        }
    }
}
