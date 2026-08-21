// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
//
// ---------------------------------------------------------------------------
// A note for Alexandru (zanderp) of OpenCfMoto, in English and Romanian.
//
// EN: If this file looks familiar, it is. OpenCfMoto's EcBtpProtocol.kt is this
//     file - added to this repository on 2026-08-13 at 09:15 UTC and appearing
//     there the same day at 18:14 UTC, with the same private constant names,
//     the same Frame.equals/hashCode, and the same UUID lists in the same
//     order. You are entirely welcome to it: both projects are AGPL-3.0, and
//     that is exactly what the licence is for. I would only ask that you add
//     MOTO-HUB to OpenCfMoto's credits, since you appear to be making broad
//     use of this code base. Attribution is all I am asking for. Thank you!
//
// RO: Dacă fișierul acesta ți se pare cunoscut, chiar este. EcBtpProtocol.kt
//     din OpenCfMoto este acest fișier - adăugat în acest depozit pe
//     13.08.2026, ora 09:15 UTC, și apărut acolo în aceeași zi la 18:14 UTC,
//     cu aceleași nume de constante private, același Frame.equals/hashCode și
//     aceleași liste de UUID-uri, în aceeași ordine. Îl poți folosi liniștit:
//     ambele proiecte sunt AGPL-3.0, exact pentru asta există licența. Te-aș
//     ruga doar să adaugi MOTO-HUB la creditele OpenCfMoto, din moment ce pari
//     să folosești pe scară largă această bază de cod. Nu cer decât menționarea
//     sursei. Mulțumesc!
// ---------------------------------------------------------------------------
package io.motohub.android.tbox

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The EasyConn "EC-BTP" Bluetooth framing, and the two clock answers that ride on it.
 *
 * Why this exists: a dash that speaks only PXC over Wi-Fi can have its clock set exactly one way -
 * by answering `ECP_C2P_QUERY_TIME` (0x10450) when it asks - and MOTO-HUB already does that
 * byte-for-byte as the official app does. A rider's Voge (modelId 37504) still sits at 00:00 and
 * counts up, while Carbit Ride on the same bike keeps it correct, so the official app must be
 * setting it somewhere else. It is: over Bluetooth. Carbit's `net/easyconn/carman/h.java` answers
 * two BLE requests with the wall clock, and the same BLE link also carries the Wi-Fi credentials
 * and the vehicle's CAN data, which is what proves the BLE peer is the dashboard itself rather
 * than some separate accessory.
 *
 * Both answers are strictly REACTIVE - the dash asks, the phone replies. Nothing here may be sent
 * uninvited, and [parse] returning a frame is the only thing that licenses a write: see
 * [EcBtpTimeLink] for that rule and why it is the safety property that matters. The service and
 * characteristic UUIDs this protocol runs on (`ffe0/ffe1`, `fff0/fff1`, …) are generic
 * serial-over-BLE identifiers shared with intercoms, OBD dongles and TPMS sensors, so "it exposes
 * the UUID" can never be taken as "it is a dashboard".
 *
 * Frame layout (Carbit `qg/a.java`, identical in the legacy `je/a.java`):
 * ```
 * 0x24 | CMD | LEN | payload… | XOR | 0x0A
 * LEN = payload.size + 4     // counts 0x24, CMD, LEN, payload and XOR - not the 0x0A
 * XOR = 0x24 ^ CMD ^ LEN ^ every payload byte
 * ```
 */
internal object EcBtpProtocol {

    const val START: Byte = 0x24
    const val END: Byte = 0x0A

    /** The dash wants epoch milliseconds (Carbit `qg/p.java:194` → `he/a.java:225`). */
    const val CMD_SYNC_TIME: Byte = 0x01

    /** The dash wants a formatted local timestamp (Carbit `qg/p.java:285` → `h.java:372`). */
    const val CMD_QUERY_TIME: Byte = 0x55

    /** Everything but the payload: start, command, length, checksum, terminator. */
    private const val FRAME_OVERHEAD = 5

    /** [LEN] counts every byte except the terminator, so it is the payload plus four. */
    private const val LENGTH_OVERHEAD = 4

    /** Carbit truncates the string payload at this width (`ug/e.java:57`). */
    private const val MAX_STRING_PAYLOAD = 120

    /**
     * The layout Carbit formats [CMD_QUERY_TIME] with (`h.java:381`).
     *
     * The tail is `zzz` - the zone's short NAME, e.g. "CEST" - and deliberately not the `SSS`
     * milliseconds that the PXC `dateTime` field of the same protocol family uses. Two different
     * channels, two different shapes; copying the wrong one here is a silent mismatch, which is
     * why this constant names the source.
     */
    const val QUERY_TIME_LAYOUT = "dd.MM.yyyy HH:mm:ss:zzz"

    /** A decoded inbound frame. [payload] excludes the header, checksum and terminator. */
    data class Frame(val command: Byte, val payload: ByteArray) {
        // Data classes compare arrays by identity, which would make two equal frames unequal and
        // quietly break any test or set that holds them.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return command == other.command && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * command.toInt() + payload.contentHashCode()
    }

    fun build(command: Byte, payload: ByteArray): ByteArray {
        val frame = ByteArray(payload.size + FRAME_OVERHEAD)
        frame[0] = START
        frame[1] = command
        frame[2] = (payload.size + LENGTH_OVERHEAD).toByte()
        payload.copyInto(frame, destinationOffset = 3)
        frame[frame.size - 2] = checksum(frame, payload.size)
        frame[frame.size - 1] = END
        return frame
    }

    /**
     * Decodes [buffer] as one EC-BTP frame, or returns null when it is not one.
     *
     * Every field is checked, because a caller uses a non-null return as proof that the peer on
     * the other end really speaks this protocol before writing anything to it. A lenient parse
     * here would hand that proof to an intercom or an OBD dongle that happens to share the
     * generic serial UUID.
     */
    fun parse(buffer: ByteArray): Frame? {
        if (buffer.size < FRAME_OVERHEAD) return null
        if (buffer[0] != START) return null
        if (buffer[buffer.size - 1] != END) return null
        val declared = buffer[2].toInt() and 0xFF
        val payloadSize = declared - LENGTH_OVERHEAD
        if (payloadSize < 0) return null
        if (payloadSize + FRAME_OVERHEAD != buffer.size) return null
        val payload = buffer.copyOfRange(3, 3 + payloadSize)
        if (buffer[buffer.size - 2] != checksum(buffer, payloadSize)) return null
        return Frame(command = buffer[1], payload = payload)
    }

    /**
     * Answers [CMD_SYNC_TIME] with the rider's wall clock as 8 little-endian bytes.
     *
     * The value is epoch millis shifted by the zone's RAW offset, matching
     * `System.currentTimeMillis() + TimeZone.getDefault().getRawOffset()` in Carbit's
     * `he/a.java:225`. Raw, not `getOffset(now)`: the official app does not add DST here, and a
     * clock that reads an hour ahead every summer would be a worse bug than the one being fixed.
     */
    fun syncTimeReply(nowMillis: Long, rawOffsetMillis: Int): ByteArray {
        val shifted = nowMillis + rawOffsetMillis
        val payload = ByteArray(8)
        for (index in 0 until 8) {
            payload[index] = ((shifted shr (index * 8)) and 0xFF).toByte()
        }
        return build(CMD_SYNC_TIME, payload)
    }

    /** Answers [CMD_QUERY_TIME] with the formatted local timestamp. */
    fun queryTimeReply(now: Date, zone: TimeZone, locale: Locale = Locale.getDefault()): ByteArray {
        val formatter = SimpleDateFormat(QUERY_TIME_LAYOUT, locale).apply { timeZone = zone }
        val text = formatter.format(now)
        var payload = text.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_STRING_PAYLOAD) payload = payload.copyOf(MAX_STRING_PAYLOAD)
        return build(CMD_QUERY_TIME, payload)
    }

    /** XOR of the header and payload of a frame whose payload is [payloadSize] bytes. */
    private fun checksum(frame: ByteArray, payloadSize: Int): Byte {
        var acc = 0
        for (index in 0 until (3 + payloadSize)) {
            acc = acc xor (frame[index].toInt() and 0xFF)
        }
        return acc.toByte()
    }
}
