// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// MOTO-HUB glue. The entry point Android Auto 17.4 leaves open, and why this file exists at all.
//
// Everything AaSelfMode asks for is closed on 17.4.663004 (manifest read from the shipping bundle):
// WirelessStartupActivity is exported=false, WirelessStartupReceiver is enabled=false - so the
// broadcast is swallowed without an error - and DeveloperHeadUnitNetworkService is exported=false,
// which is why the head unit server can only be started by hand from Android Auto's own menu. That
// hand-start is the instruction riders were being given, and it is not something to ask of anyone
// on a motorcycle.
//
// The QR-pairing family is a different story, because production wireless head units depend on it:
//   - DeepLinkResolver          exported=true, VIEW + BROWSABLE on https://androidauto.com/projection/*
//   - WifiBluetoothReceiver     enabled=true, exported=true, actions START_WIRELESS_PROJECTION(_WPP)
// Both still open on 17.4, with parsing identical to 17.2. The deep link is what a head unit prints
// as a QR code: it carries the car's Wi-Fi credentials plus the address its projection server
// listens on, and Android Auto stores that against a Bluetooth address. Here MOTO-HUB is the head
// unit, so the address it advertises is the phone's own.
package io.motohub.android.aa

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64

object AaWirelessPairing {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"

    private const val DEEP_LINK_ACTIVITY =
        "com.google.android.apps.auto.wireless.deeplink.DeepLinkResolver"
    private const val WPP_RECEIVER =
        "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver"

    /** Gearhead's own QR host. The intent filter is a GLOB on /projection/.*, nothing more. */
    private const val DEEP_LINK_PREFIX = "https://androidauto.com/projection/?data="

    /**
     * The two actions [WPP_RECEIVER] accepts from outside. WPP is the one the QR flow sends after
     * the rider accepts; the plain one is the same request for a car that is already stored.
     */
    const val ACTION_START_WIRELESS_PROJECTION_WPP =
        "com.google.android.projection.gearhead.START_WIRELESS_PROJECTION_WPP"
    const val ACTION_START_WIRELESS_PROJECTION =
        "com.google.android.projection.gearhead.START_WIRELESS_PROJECTION"

    /**
     * Android Auto reads the device out of the parcelable extra first and falls back to this
     * string, which spares us building a BluetoothDevice for an address that has none.
     */
    private const val EXTRA_DEVICE_ADDRESS = "DEVICE_ADDRESS"

    /**
     * What the QR payload describes: a wireless head unit, which here is this phone.
     *
     * [hostAddress] and [port] are where Android Auto will dial for the AAP session - that is
     * [AaReceiver]'s listening socket - and [ssid]/[passkey] are the dashboard's access point,
     * the network Android Auto expects that address to live on.
     */
    data class Car(
        val ssid: String,
        val bssid: String,
        val passkey: String,
        val hostAddress: String,
        val port: Int,
        val bluetoothAddress: String
    )

    /**
     * A locally-administered Bluetooth address derived from the dashboard's SSID.
     *
     * Android Auto only format-checks this field, and it identifies the car in its own storage, so
     * one bike always gets the same address and two bikes never collide. A real address would be
     * worse than useless: the receiver bails out ("device has a battery level, exiting") when the
     * address belongs to something that reports a battery, which every headset does.
     */
    fun syntheticBluetoothAddress(ssid: String): String = syntheticAddress(ssid, "bt")

    /**
     * The same trick for the access point's hardware address, used when Android will not report
     * the real one. It must not collide with [syntheticBluetoothAddress]: the two describe
     * different things in Android Auto's storage - one the car, one its network - and handing it
     * the same value for both invites it to treat them as one.
     */
    fun syntheticBssid(ssid: String): String = syntheticAddress(ssid, "ap")

    private fun syntheticAddress(ssid: String, salt: String): String {
        var hash = 0x811C9DC5.toInt()
        for (character in salt + ssid) {
            hash = (hash xor character.code) * 0x01000193
        }
        val bytes = listOf(
            0x02, // locally administered, unicast
            (hash ushr 24) and 0xFF,
            (hash ushr 16) and 0xFF,
            (hash ushr 8) and 0xFF,
            hash and 0xFF,
            ssid.length and 0xFF
        )
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    /** Whether this Android Auto build still exposes the QR pairing entry point. */
    fun isPairingAvailable(context: Context): Boolean =
        isComponentUsable(context, DEEP_LINK_ACTIVITY, PackageManager.GET_ACTIVITIES)

    /** Whether a stored car can be asked to project without going through the deep link again. */
    fun isProjectionRequestAvailable(context: Context): Boolean =
        isComponentUsable(context, WPP_RECEIVER, PackageManager.GET_RECEIVERS)

    /**
     * Opens Android Auto's own pairing sheet for [car]. The rider taps "Continue" once, ever:
     * accepting stores the credentials and Android Auto starts projecting, and from then on
     * [requestProjection] is enough.
     *
     * Must be called from the foreground - it is an Activity, and a background launch is dropped
     * by the system without an exception, which would read here as Android Auto ignoring us.
     */
    fun launchPairing(context: Context, car: Car, log: (String) -> Unit): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri(car))).apply {
            setClassName(GEARHEAD_PKG, DEEP_LINK_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        log("[AA] opening Android Auto's pairing sheet for ${car.ssid} → ${car.hostAddress}:${car.port}")
        context.startActivity(intent)
        true
    } catch (failure: Exception) {
        log("[AA] pairing sheet refused: ${failure.message}")
        false
    }

    /**
     * Asks Android Auto to project to an already-stored car. This is the zero-tap path, and the
     * only one that can run without the rider looking at the phone.
     */
    fun requestProjection(
        context: Context,
        bluetoothAddress: String,
        action: String = ACTION_START_WIRELESS_PROJECTION_WPP,
        log: (String) -> Unit
    ): Boolean = try {
        val intent = Intent(action).apply {
            setClassName(GEARHEAD_PKG, WPP_RECEIVER)
            putExtra(EXTRA_DEVICE_ADDRESS, bluetoothAddress)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendBroadcast(intent)
        log("[AA] asked Android Auto to project to stored car $bluetoothAddress (${action.substringAfterLast('.')})")
        true
    } catch (failure: Exception) {
        log("[AA] projection request refused: ${failure.message}")
        false
    }

    /** The URL Android Auto would have read off a head unit's QR code. */
    fun deepLinkUri(car: Car): String =
        DEEP_LINK_PREFIX + Base64.encodeToString(payload(car), Base64.URL_SAFE or Base64.NO_WRAP)

    /**
     * The QR message itself, before base64. Separate from [deepLinkUri] so the field numbering -
     * the part that has to match Android Auto exactly, and the part a future release could
     * change - is testable without android.util.Base64.
     */
    internal fun payload(car: Car): ByteArray =
        string(1, car.ssid) +
            string(2, car.bssid) +
            string(3, car.passkey) +
            string(4, car.hostAddress) +
            varint(5, car.port.toLong()) +
            string(6, car.bluetoothAddress)
    // Field 7 (security mode) is left out on purpose: Android Auto defaults it to WPA2_PERSONAL,
    // which is what every dashboard access point seen so far uses.

    /**
     * Exported AND enabled. A disabled component takes the intent and does nothing - the exact
     * shape of the 17.4 failure this file exists to route around - so it must not count as usable.
     */
    private fun isComponentUsable(context: Context, className: String, flag: Int): Boolean =
        runCatching {
            val info = context.packageManager.getPackageInfo(
                GEARHEAD_PKG,
                flag or PackageManager.MATCH_DISABLED_COMPONENTS
            )
            val components = when (flag) {
                PackageManager.GET_ACTIVITIES -> info.activities.orEmpty()
                else -> info.receivers.orEmpty()
            }
            components.any { it.name == className && it.exported && it.enabled }
        }.getOrDefault(false)

    // ── Minimal protobuf writer ──────────────────────────────────────
    // Six fields, no schema evolution, no dependency: the wire format is simpler than pulling a
    // generated stub into the build for a message that lives in someone else's app.

    private fun string(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(((field shl 3) or 2).toByte()) + lengthPrefix(bytes.size) + bytes
    }

    private fun varint(field: Int, value: Long): ByteArray =
        byteArrayOf((field shl 3).toByte()) + lengthPrefix(value)

    private fun lengthPrefix(value: Int): ByteArray = lengthPrefix(value.toLong())

    private fun lengthPrefix(value: Long): ByteArray {
        var remaining = value
        val out = ArrayList<Byte>(5)
        do {
            val chunk = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            out += (if (remaining != 0L) chunk or 0x80 else chunk).toByte()
        } while (remaining != 0L)
        return out.toByteArray()
    }
}
