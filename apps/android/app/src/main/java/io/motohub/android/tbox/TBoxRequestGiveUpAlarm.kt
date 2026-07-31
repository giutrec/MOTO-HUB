package io.motohub.android.tbox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.motohub.android.session.ProjectionEventLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Releases a pending T-Box Wi-Fi request from outside the process's own sense of time.
 *
 * [TBoxNetworkConnector] bounds an exclusive `WifiNetworkSpecifier` request with a coroutine
 * delay, and that delay is only as awake as the process holding it. A rider who leaves the pairing
 * screen while a request is in flight has the app cached within seconds; Android freezes it, and
 * every timer inside freezes with it. The 2026-07-31 QJ log (`qj-5G-09dd`) shows the 180s give-up
 * landing at 528s - the exact moment the rider reopened the app - with the request holding the
 * radio for the whole interval, and Sentry carries reports of 727s. An alarm is the one timer the
 * freezer honours, because delivering it thaws the process.
 *
 * Inexact on purpose: `setAndAllowWhileIdle` needs no permission where `setExactAndAllowWhileIdle`
 * would ask the rider for SCHEDULE_EXACT_ALARM, and precision is not what this is for. It is armed
 * [SLACK_MS] behind the in-process timer so that whenever the app IS alive the ordinary path does
 * the work and the alarm only ever finds the job already done.
 */
internal object TBoxRequestGiveUpAlarm {
    private const val ACTION = "io.motohub.android.tbox.RELEASE_PENDING_WIFI_REQUEST"
    private const val EXTRA_SSID = "ssid"

    /** Long enough that a merely slow process still wins the race and the alarm stays a backstop. */
    private const val SLACK_MS = 15_000L

    /**
     * What to run when the alarm lands, per SSID.
     *
     * Held in memory on purpose. If the process died rather than froze, its network request died
     * with it and there is nothing left to release - an empty map is the correct answer to an
     * alarm that outlived its process, not a bug to work around.
     */
    private val handlers = ConcurrentHashMap<String, () -> Unit>()

    fun arm(context: Context, ssid: String, delayMillis: Long, onFire: () -> Unit) {
        handlers[ssid] = onFire
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMillis + SLACK_MS,
                pendingIntent(context, ssid)
            )
        }.onFailure { failure ->
            // Not fatal: the in-process timer still covers every case where the app stays alive.
            ProjectionEventLog.warning(
                "NETWORK",
                "Could not arm the wake-up alarm that releases a stale Wi-Fi request.",
                failure
            )
        }
    }

    fun disarm(context: Context, ssid: String) {
        handlers.remove(ssid)
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, ssid))
        }
    }

    /** Invoked by [TBoxRequestGiveUpReceiver]; a no-op once anything else has released the request. */
    fun fire(ssid: String?) {
        handlers.remove(ssid ?: return)?.invoke()
    }

    fun ssidFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_SSID)

    private fun pendingIntent(context: Context, ssid: String): PendingIntent = PendingIntent
        .getBroadcast(
            context,
            // One slot per SSID, so re-arming for the same bike replaces its own alarm and two
            // bikes cannot cancel each other's.
            ssid.hashCode(),
            Intent(ACTION)
                .setClass(context, TBoxRequestGiveUpReceiver::class.java)
                .putExtra(EXTRA_SSID, ssid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** Manifest-declared, so the alarm can be delivered to - and thaw - a cached process. */
class TBoxRequestGiveUpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TBoxRequestGiveUpAlarm.fire(TBoxRequestGiveUpAlarm.ssidFrom(intent))
    }
}
