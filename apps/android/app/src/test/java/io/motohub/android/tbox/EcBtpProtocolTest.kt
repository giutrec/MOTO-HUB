// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EcBtpProtocolTest {

    @Test
    fun `sync time reply matches the layout Carbit sends`() {
        // 0x0102030405060708 chosen so a byte-order mistake cannot accidentally pass.
        val reply = EcBtpProtocol.syncTimeReply(nowMillis = 0x0102030405060708L, rawOffsetMillis = 0)

        assertEquals("frame is payload + 5 bytes", 13, reply.size)
        assertEquals("start", 0x24.toByte(), reply[0])
        assertEquals("command", 0x01.toByte(), reply[1])
        assertEquals("length is payload + 4", 0x0C.toByte(), reply[2])
        assertArrayEquals(
            "epoch millis are little-endian",
            byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01),
            reply.copyOfRange(3, 11)
        )
        assertEquals("terminator", 0x0A.toByte(), reply[12])
    }

    @Test
    fun `sync time applies the raw zone offset like the official app`() {
        val twoHours = 2 * 60 * 60 * 1000
        val reply = EcBtpProtocol.syncTimeReply(nowMillis = 0L, rawOffsetMillis = twoHours)

        val parsed = EcBtpProtocol.parse(reply)
        assertNotNull(parsed)
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (parsed!!.payload[index].toLong() and 0xFF)
        }
        assertEquals(twoHours.toLong(), value)
    }

    @Test
    fun `query time reply carries the dotted date with a zone name, not milliseconds`() {
        val zone = TimeZone.getTimeZone("Europe/Rome")
        // Straight out of a rider's Voge log (2026-08-09): the PXC reply we sent that session
        // carried "time":1786272219244 alongside "dateTime":"09.08.2026 12:43:39:244", so the same
        // instant must render here as the same local wall clock - only with the zone name in place
        // of the milliseconds, which is the one difference between the two channels' layouts.
        val reply = EcBtpProtocol.queryTimeReply(Date(1786272219244L), zone, Locale.UK)

        val parsed = EcBtpProtocol.parse(reply)
        assertNotNull(parsed)
        assertEquals(EcBtpProtocol.CMD_QUERY_TIME, parsed!!.command)
        val text = String(parsed.payload, Charsets.UTF_8)
        assertEquals("09.08.2026 12:43:39:CEST", text)
    }

    @Test
    fun `a built frame parses back to what went in`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33)
        val round = EcBtpProtocol.parse(EcBtpProtocol.build(0x42, payload))

        assertNotNull(round)
        assertEquals(0x42.toByte(), round!!.command)
        assertArrayEquals(payload, round.payload)
    }

    @Test
    fun `an empty payload still frames and parses`() {
        val round = EcBtpProtocol.parse(EcBtpProtocol.build(EcBtpProtocol.CMD_SYNC_TIME, ByteArray(0)))

        assertNotNull(round)
        assertEquals(0, round!!.payload.size)
    }

    // The parser is the gate that decides whether this app is allowed to write to a stranger's
    // GATT characteristic. Every rejection below is a device we must stay silent to: the service
    // UUIDs this rides on are shared with intercoms, OBD dongles and TPMS sensors.
    @Test
    fun `rejects anything that is not a well formed frame`() {
        val good = EcBtpProtocol.build(EcBtpProtocol.CMD_SYNC_TIME, byteArrayOf(1, 2, 3, 4))

        assertNull("too short", EcBtpProtocol.parse(byteArrayOf(0x24, 0x01, 0x05)))
        assertNull("wrong start byte", EcBtpProtocol.parse(good.copyOf().also { it[0] = 0x23 }))
        assertNull("wrong terminator", EcBtpProtocol.parse(good.copyOf().also { it[it.size - 1] = 0x0B }))
        assertNull("corrupt checksum", EcBtpProtocol.parse(good.copyOf().also { it[it.size - 2] = 0x00 }))
        assertNull("length disagrees with the buffer", EcBtpProtocol.parse(good.copyOf().also { it[2] = 0x20 }))
        assertNull("length below the header overhead", EcBtpProtocol.parse(good.copyOf().also { it[2] = 0x01 }))
        assertNull("empty buffer", EcBtpProtocol.parse(ByteArray(0)))
    }

    @Test
    fun `rejects plausible traffic from a device that merely shares the serial UUID`() {
        // An HM-10 style module echoing ASCII, and a JSON-ish line from an OBD dongle: both start
        // with neither 0x24 nor end with 0x0A in the right place, and neither can pass the XOR.
        assertNull(EcBtpProtocol.parse("OK+CONN".toByteArray()))
        assertNull(EcBtpProtocol.parse("{\"rpm\":900}\n".toByteArray()))
    }

    @Test
    fun `frames survive a payload byte that looks like the terminator`() {
        val payload = byteArrayOf(0x0A, 0x24, 0x0A)
        val round = EcBtpProtocol.parse(EcBtpProtocol.build(EcBtpProtocol.CMD_QUERY_TIME, payload))

        assertNotNull("length and checksum must carry the frame, not a scan for 0x0A", round)
        assertArrayEquals(payload, round!!.payload)
    }
}
