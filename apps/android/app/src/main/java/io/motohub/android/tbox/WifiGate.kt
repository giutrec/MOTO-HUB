// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * Phone Wi-Fi must be on before Android can join the T-Box AP. When it's off,
 * [TBoxNetworkConnector.connect] silently runs out the clock on its 30s timeout instead of
 * failing fast, so callers should check this first and skip straight to an actionable message.
 *
 * None of that holds for [io.motohub.android.session.TBoxConnectionMode.PHONE_HOTSPOT], where the
 * phone hosts the network and the dash is the client - see [isHostingANetwork], which is the
 * question to ask there instead.
 */
internal object WifiGate {
    const val WIFI_OFF_MESSAGE = "Phone Wi-Fi is off. Turn it on, then tap Connect again."

    const val HOTSPOT_OFF_MESSAGE = "Phone hotspot is off. Turn it on, then tap Connect again."

    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return true // can't tell - don't block the connection attempt
        @Suppress("DEPRECATION")
        return wifiManager.isWifiEnabled
    }

    /**
     * Whether the phone currently holds a private subnet of its own - the shape a running hotspot
     * makes, and the only precondition a `PHONE_HOTSPOT` connect actually has.
     *
     * Asking [isWifiEnabled] there is not merely useless, it is inverted: every OEM that supports
     * tethering turns the Wi-Fi *station* radio off when the hotspot starts, so a working session
     * reports Wi-Fi off for its whole life. Field log 2026-08-19 (Samsung SM-A566B, Benelli TRK
     * 702X, dash on `swlan0`) caught what that costs: the dash dropped the link mid-ride, the
     * Ride Dashboard watchdog reconnected through it five times, and every attempt that went
     * through the connect gate instead - including the auto-connect that fires when a Ride
     * Dashboard stops - died on "phone Wi-Fi is off". The banner then told the rider to turn
     * Wi-Fi on, which on that phone switches the hotspot off and takes the bike with it, so the
     * only way back was re-pairing at the roadside.
     *
     * Deliberately permissive. It reads interfaces rather than names, so an OEM that calls its
     * SoftAP something nobody has seen before still passes, and a phone merely joined to a home
     * network passes too - that costs one connect attempt that ends in "T-Box not found", which
     * is the failure the rider would have got anyway. A false *block* is the outcome worth
     * ruling out: it is unrecoverable from the road.
     */
    fun isHostingANetwork(): Boolean =
        TBoxHotspotScan.tetheringSubnets(TBoxHotspotScan.snapshotInterfaces()).isNotEmpty()

    fun openWifiSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /**
     * Android publishes no action that opens the tethering page, so the AOSP activity is tried by
     * name first and the public "network settings" screen catches every device that renamed it.
     */
    fun openHotspotSettings(context: Context): Boolean {
        val tethering = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setClassName("com.android.settings", "com.android.settings.TetherSettings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (tethering) return true
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }
}
