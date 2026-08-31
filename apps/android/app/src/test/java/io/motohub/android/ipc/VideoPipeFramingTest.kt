// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

class VideoPipeFramingTest {

    private fun reader(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    private fun written(block: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(block)
        return buffer.toByteArray()
    }

    @Test
    fun `an access unit survives the round trip`() {
        val payload = byteArrayOf(0, 0, 0, 1, 103, 66, -128, 30)
        val frame = VideoPipeFraming.read(reader(written { VideoPipeFraming.writeAccessUnit(it, payload) }))
        assertFalse(frame.isStill)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `a still carries its frame id`() {
        val payload = byteArrayOf(-1, -40, -1, -32, 9, 9, 9)
        val frame = VideoPipeFraming.read(reader(written { VideoPipeFraming.writeStill(it, payload, 4242) }))
        assertTrue(frame.isStill)
        assertEquals(4242, frame.frameId)
        assertArrayEquals(payload, frame.payload)
    }

    /**
     * The wire a companion app built before stills existed still writes: a bare positive length
     * then the payload. Reading it must not change, or every rider on the older ADVANCED loses
     * video the moment Core is updated.
     */
    @Test
    fun `the pre-stills wire is read unchanged`() {
        val payload = byteArrayOf(7, 7, 7, 7, 7)
        val legacy = written { out ->
            out.writeInt(payload.size)
            out.write(payload)
        }
        val frame = VideoPipeFraming.read(reader(legacy))
        assertFalse(frame.isStill)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `frames read back in order from one stream`() {
        val stream = reader(
            written { out ->
                VideoPipeFraming.writeAccessUnit(out, byteArrayOf(1))
                VideoPipeFraming.writeStill(out, byteArrayOf(2), frameId = 9)
                VideoPipeFraming.writeAccessUnit(out, byteArrayOf(3))
            }
        )
        assertArrayEquals(byteArrayOf(1), VideoPipeFraming.read(stream).payload)
        val still = VideoPipeFraming.read(stream)
        assertEquals(9, still.frameId)
        assertArrayEquals(byteArrayOf(2), still.payload)
        assertArrayEquals(byteArrayOf(3), VideoPipeFraming.read(stream).payload)
    }

    @Test(expected = EOFException::class)
    fun `a closed pipe reports the end rather than a bad frame`() {
        VideoPipeFraming.read(reader(ByteArray(0)))
    }

    /** No length was ever allowed to be zero, so this can only be a desynchronised stream. */
    @Test(expected = IllegalArgumentException::class)
    fun `a zero length is refused`() {
        VideoPipeFraming.read(reader(written { it.writeInt(0) }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown marker is refused rather than skipped`() {
        VideoPipeFraming.read(reader(written { it.writeInt(-99) }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an oversized frame is refused`() {
        VideoPipeFraming.read(
            reader(written { it.writeInt(VideoPipeFraming.MAX_FRAME_BYTES + 1) })
        )
    }

    /** A still must never be written to a Core that predates the marker. */
    @Test
    fun `the stills contract version is the one the marker arrived in`() {
        assertEquals(4, IpcBridgeContract.CONTRACT_VERSION_VIDEO_STILLS)
        assertTrue(IpcBridgeContract.CONTRACT_VERSION >= IpcBridgeContract.CONTRACT_VERSION_VIDEO_STILLS)
    }
}
