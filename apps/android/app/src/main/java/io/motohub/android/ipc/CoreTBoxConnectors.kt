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
     * The connector for a new connect attempt, with the previous one torn down first.
     *
     * Releasing through `cancel()` rather than `disconnect()` is deliberate: `cancel()` tears down
     * *that* connector's own transport and network connector directly, which is exactly what an
     * orphan needs, while `disconnect()` would look up whatever session the registry happens to
     * hold and could leave the orphan's Wi-Fi request open.
     */
    suspend fun replace(context: Context): CoreTBoxConnector =
        live.replace { CoreTBoxConnector(context) }

    fun current(): CoreTBoxConnector? = live.peek()

    suspend fun clear() = live.clear()
}
