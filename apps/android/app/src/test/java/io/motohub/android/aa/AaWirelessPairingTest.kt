// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the QR payload's field numbering against Android Auto's parser, because getting it wrong
 * fails the way that is hardest to diagnose: the sheet opens and says the code is invalid, with
 * no indication of which field it disliked. The numbering was read off gearhead 17.4.663004
 * (identical in 17.2.662634): 1 SSID, 2 BSSID, 3 passkey, 4 projection host, 5 port, 6 Bluetooth
 * address.
 */
class AaWirelessPairingTest {

    private val car = AaWirelessPairing.Car(
        ssid = "CFMOTO-f470e5",
        bssid = "50:d1:4a:08:25:ff",
        passkey = "12345678",
        hostAddress = "192.168.43.1",
        port = 5288,
        bluetoothAddress = "02:AB:CD:EF:01:0D"
    )

    @Test
    fun `payload carries every field Android Auto validates`() {
        val fields = decode(AaWirelessPairing.payload(car))

        assertEquals("CFMOTO-f470e5", fields[1])
        assertEquals("50:d1:4a:08:25:ff", fields[2])
        assertEquals("12345678", fields[3])
        assertEquals("192.168.43.1", fields[4])
        assertEquals(5288L, fields[5])
        assertEquals("02:AB:CD:EF:01:0D", fields[6])
    }

    /** 5288 needs two varint bytes; a single-byte writer would have passed a 127-port test. */
    @Test
    fun `port is written as a multi-byte varint`() {
        assertEquals(5288L, decode(AaWirelessPairing.payload(car.copy(port = 5288)))[5])
        assertEquals(5277L, decode(AaWirelessPairing.payload(car.copy(port = 5277)))[5])
    }

    @Test
    fun `synthetic bluetooth address is stable, locally administered and per-bike`() {
        val first = AaWirelessPairing.syntheticBluetoothAddress("CFMOTO-f470e5")

        assertEquals(first, AaWirelessPairing.syntheticBluetoothAddress("CFMOTO-f470e5"))
        assertNotEquals(first, AaWirelessPairing.syntheticBluetoothAddress("CFMOTO-aa11bb"))
        assertTrue("locally administered prefix expected, was $first", first.startsWith("02:"))
        assertTrue(first.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
    }

    /**
     * The car and its access point are two different things in Android Auto's storage. Deriving
     * both from the same SSID with the same function handed it one value for both.
     */
    @Test
    fun `synthetic access point address never collides with the bluetooth one`() {
        assertNotEquals(
            AaWirelessPairing.syntheticBluetoothAddress("CFMOTO-f470e5"),
            AaWirelessPairing.syntheticBssid("CFMOTO-f470e5")
        )
        assertEquals(
            AaWirelessPairing.syntheticBssid("CFMOTO-f470e5"),
            AaWirelessPairing.syntheticBssid("CFMOTO-f470e5")
        )
    }

    /** Minimal reader for the wire format the writer produces: length-delimited and varint only. */
    private fun decode(bytes: ByteArray): Map<Int, Any> {
        val out = mutableMapOf<Int, Any>()
        var index = 0
        while (index < bytes.size) {
            val key = readVarint(bytes, index)
            index = key.second
            val field = (key.first shr 3).toInt()
            when ((key.first and 0x7L).toInt()) {
                0 -> {
                    val value = readVarint(bytes, index)
                    out[field] = value.first
                    index = value.second
                }
                2 -> {
                    val length = readVarint(bytes, index)
                    index = length.second
                    val end = index + length.first.toInt()
                    out[field] = String(bytes, index, length.first.toInt(), Charsets.UTF_8)
                    index = end
                }
                else -> error("unexpected wire type in field $field")
            }
        }
        return out
    }

    private fun readVarint(bytes: ByteArray, from: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = from
        while (true) {
            val byte = bytes[index].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            index++
            if (byte and 0x80 == 0) return result to index
            shift += 7
        }
    }
}
