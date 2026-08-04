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

    fun hasBlePermissions(context: Context): Boolean = blePermissions.all { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun missingPermissionMessage(appName: String): String =
        "$appName does not have the \"Nearby devices\" (Bluetooth) permission, which this " +
            "dashboard needs for pairing. Allow it in $appName's app info, then tap Connect again."
}
