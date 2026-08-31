// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import androidx.annotation.RequiresPermission

/**
 * The value-carrying GATT write overloads are API 33; Android 12 still writes through the
 * mutable `value` field on the characteristic/descriptor. Both shapes are normalized to the
 * API-33 status-code contract so call sites keep their `BluetoothStatusCodes.SUCCESS` checks
 * (those constants are compile-time inlined ints, safe to reference on any OS). The legacy
 * boolean carries no failure detail, so a refused legacy write reports [ERROR_UNKNOWN].
 *
 * A [SecurityException] for a missing BLUETOOTH_CONNECT grant propagates unchanged on both
 * paths - callers already handle it.
 */
internal object BleCompat {

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, value, writeType)
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = writeType
            characteristic.value = value
            if (gatt.writeCharacteristic(characteristic)) {
                BluetoothStatusCodes.SUCCESS
            } else {
                BluetoothStatusCodes.ERROR_UNKNOWN
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value)
    } else {
        @Suppress("DEPRECATION")
        run {
            descriptor.value = value
            if (gatt.writeDescriptor(descriptor)) {
                BluetoothStatusCodes.SUCCESS
            } else {
                BluetoothStatusCodes.ERROR_UNKNOWN
            }
        }
    }
}
