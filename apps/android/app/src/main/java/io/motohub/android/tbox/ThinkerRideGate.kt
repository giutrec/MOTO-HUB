package io.motohub.android.tbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.TBoxConnectionMode

/**
 * Whether a ThinkerRide (BLE-paired) connection is allowed to run — the Bluetooth sibling of
 * [WifiDirectGate]. Checked before [ThinkerRideTransport] starts scanning, because a missing
 * `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` grant makes the LE scanner throw `SecurityException`
 * from deep inside a callback, which reads as a crash rather than the one screen tap that
 * actually fixes it.
 */
internal object ThinkerRideGate {

    /** Runtime grants a BLE GATT handshake needs on Android 12+ (minSdk 34 here). */
    val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    /** True when [profile] pairs over BLE, so connect flows know to ask for [blePermissions]. */
    fun requiresBle(profile: MotorcycleProfile?): Boolean =
        profile?.connectionMode == TBoxConnectionMode.THINKERRIDE

    /**
     * Like [WifiDirectGate.hasNearbyDevicesPermission], [packageName] matters because the BLE
     * scan runs in the process that owns the transport: when a companion (PRO) drives the
     * connection, the pairing happens inside CORE, whose runtime grants are the ones that count
     * — and which the rider never gets asked for, because CORE stays in the background.
     */
    fun hasBlePermissions(
        context: Context,
        packageName: String = context.packageName
    ): Boolean = blePermissions.all { permission ->
        context.packageManager.checkPermission(permission, packageName) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun missingPermissionMessage(appName: String): String =
        "$appName does not have the $PERMISSION_MARKER, which this dashboard needs for " +
            "pairing. Allow it in $appName's app info, then tap Connect again."

    /**
     * Whether an error banner is showing [missingPermissionMessage], so the screen can offer
     * the one action that fixes it — matched on the message text the same way [WifiDirectGate]
     * does, and distinct from its marker (this one names Bluetooth).
     */
    fun isMissingPermissionMessage(message: String): Boolean = PERMISSION_MARKER in message

    private const val PERMISSION_MARKER = "\"Nearby devices\" (Bluetooth) permission"
}
