// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Ported from headunit-revived (AGPLv3): connection/AccessoryConnection.kt
package io.motohub.android.aa

interface AccessoryConnection {
    val isSingleMessage: Boolean
    fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int
    fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int
    val isConnected: Boolean
    fun connect(): Boolean
    fun disconnect()
}
