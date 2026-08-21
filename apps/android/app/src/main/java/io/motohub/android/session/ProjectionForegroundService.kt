// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import io.motohub.android.i18n.motoHubText

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import io.motohub.android.i18n.motoHubText
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.motohub.android.R

/**
 * Foreground service that holds a live projection while the phone is locked
 * or the app is in the background. Bound from MainActivity so we know when
 * the user is still actively watching, but always runs as a foreground service
 * while streaming to dodge the 60-second background startup deadline.
 */
class ProjectionForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MOTO-HUB:ProjectionService"
        )
        // In onCreate an uncaught failure here is the worst shape of this bug: the service dies
        // before it has done anything, START_STICKY brings it straight back, and it fails
        // identically - a loop that holds no projection and only shows up as battery drain.
        // Android refuses the promotion for reasons that will not change on a retry: a start from
        // the background (ForegroundServiceStartNotAllowedException, API 31+) or a missing
        // permission. Record it and stand down.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }
        val failure = promoted.exceptionOrNull()
        if (failure != null) {
            foregroundRefused = true
            ProjectionEventLog.error(
                "SERVICE",
                "Projection foreground service was refused " +
                    "(${failure.javaClass.simpleName}: ${failure.message}); stopping rather than " +
                    "restarting into the same refusal. The projection keeps running only while " +
                    "the app is in the foreground."
            )
            stopSelf()
            return
        }
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        ProjectionEventLog.record("SERVICE", "Projection foreground service created, wake lock acquired")
    }

    /** Set when Android refused the foreground promotion, so the restart is not re-armed. */
    private var foregroundRefused = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY is what makes the refusal loop: it is only worth asking for when the
        // service actually got its foreground slot.
        return if (foregroundRefused) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ProjectionEventLog.record("SERVICE", "Projection foreground service destroyed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.apply {
            runCatching { release() }
            wakeLock = null
        }
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MOTO-HUB projection service",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(motoHubText("MOTO-HUB"))
            .setContentText(motoHubText("Projection is running"))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "projection_foreground_service_v1"
        private const val NOTIFICATION_ID = 4200
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 300_000L

        fun start(context: Context) {
            val intent = Intent(context, ProjectionForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProjectionForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
