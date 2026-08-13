package io.motohub.android.tbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkerRideProtocolTest {

    @Test
    fun framesControlJsonWithMagicLengthAndTerminator() {
        val framed = ThinkerRideProtocol.frameControlJson("{}")

        assertArrayEquals(
            byteArrayOf(
                0xEE.toByte(), 0xFD.toByte(), // magic
                0x00, 0x00, 0x00, 0x02, // big-endian body length
                '{'.code.toByte(), '}'.code.toByte(),
                0xFF.toByte() // terminator
            ),
            framed
        )
    }

    @Test
    fun videoSizeHeaderCarriesOsNameAndBigEndianGeometry() {
        val header = ThinkerRideProtocol.videoSizeHeader(600, 1024)

        assertEquals(69, header.size)
        assertEquals(0, header[0].toInt())
        assertEquals("android", String(header, 1, 7, Charsets.US_ASCII))
        // 600 = 0x0258, 1024 = 0x0400, both big-endian at offsets 65..68.
        assertEquals(0x02, header[65].toInt() and 0xFF)
        assertEquals(0x58, header[66].toInt() and 0xFF)
        assertEquals(0x04, header[67].toInt() and 0xFF)
        assertEquals(0x00, header[68].toInt() and 0xFF)
    }

    @Test
    fun videoSizeHeaderIsResolutionAgnostic() {
        // Multi-model support hinges on this: any profile geometry must be encodable.
        val header = ThinkerRideProtocol.videoSizeHeader(1280, 535)

        assertEquals(0x05, header[65].toInt() and 0xFF)
        assertEquals(0x00, header[66].toInt() and 0xFF)
        assertEquals(0x02, header[67].toInt() and 0xFF)
        assertEquals(0x17, header[68].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun videoSizeHeaderRejectsAnEmptyGeometry() {
        ThinkerRideProtocol.videoSizeHeader(0, 1024)
    }

    @Test
    fun convertsAnAvccAccessUnitToAnnexB() {
        val avcc = byteArrayOf(
            0x00, 0x00, 0x00, 0x03, 0x67, 0x42, 0x00, // SPS-ish NAL, length 3
            0x00, 0x00, 0x00, 0x02, 0x65, 0x11 // IDR-ish NAL, length 2
        )

        val annexB = ThinkerRideProtocol.annexBFromAvcc(avcc)

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00,
                0x00, 0x00, 0x00, 0x01, 0x65, 0x11
            ),
            annexB
        )
    }

    @Test
    fun passesThroughAccessUnitsAlreadyInAnnexB() {
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11)

        assertSame(annexB, ThinkerRideProtocol.annexBFromAvcc(annexB))
    }

    @Test
    fun shipsMalformedAccessUnitsUntouchedRatherThanCorrupting() {
        // Length prefix claims more bytes than the buffer holds.
        val malformed = byteArrayOf(0x00, 0x00, 0x00, 0x7F, 0x65)

        assertSame(malformed, ThinkerRideProtocol.annexBFromAvcc(malformed))
    }

    @Test
    fun recognisesTheSixByteKeepaliveProbe() {
        assertTrue(ThinkerRideProtocol.isKeepaliveProbe(byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)))
        assertFalse(ThinkerRideProtocol.isKeepaliveProbe(byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00)))
        assertFalse(ThinkerRideProtocol.isKeepaliveProbe(byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00)))
        // A longer read that merely starts like a probe is not one.
        val buffer = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x55)
        assertFalse(ThinkerRideProtocol.isKeepaliveProbe(buffer, buffer.size))
        assertTrue(ThinkerRideProtocol.isKeepaliveProbe(buffer, 6))
    }

    @Test
    fun controlHandshakeHasTheFixedLayoutFromTheReferenceCapture() {
        val handshake = ThinkerRideProtocol.controlHandshake("id@example.com")

        // 6 (cmd 1) + 10 (cmd 23) + 6 + 256 (cmd 18 + identity) + 6 (cmd 14) + 6 (cmd 17).
        assertEquals(290, handshake.size)
        assertEquals(0x01, handshake[0].toInt())
        assertEquals(0x17, handshake[7].toInt())
        assertEquals(0x12, handshake[17].toInt())
        // Identity begins right after cmd 18's 6-byte header and is zero-padded to 256 bytes.
        assertEquals("id@example.com", String(handshake, 22, 14, Charsets.UTF_8))
        assertEquals(0x00, handshake[22 + 14].toInt())
        assertEquals(0x0E, handshake[279].toInt())
        assertEquals(0x11, handshake[285].toInt())
    }

    @Test
    fun mirrorStatusPacketsToggleBothMessageTypes() {
        val start = ThinkerRideProtocol.bleMirrorStatusPackets(active = true)
        val stop = ThinkerRideProtocol.bleMirrorStatusPackets(active = false)

        assertEquals(2, start.size)
        assertTrue(start[0].contains("\"msg_type\":23") && start[0].contains("\"status\":1"))
        assertTrue(start[1].contains("\"msg_type\":21") && start[1].contains("\"status\":1"))
        assertTrue(stop[0].contains("\"msg_type\":23") && stop[0].contains("\"status\":0"))
        assertTrue(stop[1].contains("\"msg_type\":21") && stop[1].contains("\"status\":0"))
    }

    @Test
    fun handshakeSendsPairInfoFirstAndCarriesTheTimestamp() {
        val packets = ThinkerRideProtocol.bleHandshakePackets("2026-08-04 10:15:00")

        assertEquals(4, packets.size)
        assertTrue(packets[0].contains("get_pairinfo"))
        assertTrue(packets[3].contains("2026-08-04 10:15:00"))
    }

    @Test
    fun acceptsOnlyAPositivePairResult() {
        assertTrue(
            ThinkerRideProtocol.isPairConfirmation(
                """{"msg_id":27,"act":"send_pairresult","result":1}"""
            )
        )
        assertFalse(
            ThinkerRideProtocol.isPairConfirmation(
                """{"msg_id":27,"act":"send_pairresult","result":0}"""
            )
        )
        assertFalse(ThinkerRideProtocol.isPairConfirmation("""{"msg_id":25,"msg_type":24}"""))
        assertFalse(ThinkerRideProtocol.isPairConfirmation("send_pairresult but not json"))
    }

    @Test
    fun recognisesAPairResultInsideAConcatenatedNotification() {
        // Dash firmware packs multiple JSON objects into one notify payload; whole-string
        // parsing fails on these, so the confirmation must still be recognised.
        assertTrue(
            ThinkerRideProtocol.isPairConfirmation(
                """{"msg_id":10,"item":1}{"msg_id":27,"func":"PAIR","act":"send_pairresult","result":1}"""
            )
        )
        assertTrue(
            ThinkerRideProtocol.isPairConfirmation(
                "{\n\t\"msg_id\":\t27,\n\t\"act\":\t\"send_pairresult\",\n\t\"result\":\t1\n}{\"msg_id\":10}"
            )
        )
        assertFalse(
            ThinkerRideProtocol.isPairConfirmation(
                """{"msg_id":27,"act":"send_pairresult","result":0}{"msg_id":10,"item":1}"""
            )
        )
    }
}
