package io.motohub.android.tbox

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YunmoProtocolTest {

    @Test
    fun encodeSimpleFramesTheCommandWithLengthAndChecksum() {
        val frame = YunmoProtocol.encodeSimple(YunmoProtocol.CMD_DISPLAY, byteArrayOf(7))
        // FE FE FE FE | A0 | 00 01 | (00+01)&FF | 07 | checksum(sum of bytes[4..9))
        val expectedChecksum = (0xA0 + 0x00 + 0x01 + 0x01 + 0x07) and 0xFF
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
                0xA0.toByte(), 0x00, 0x01, 0x01, 0x07, expectedChecksum.toByte()
            ),
            frame
        )
    }

    @Test
    fun theHandshakeFramesMatchTheReferenceOpcodes() {
        assertArrayEquals(
            YunmoProtocol.encodeSimple(YunmoProtocol.CMD_DISPLAY_ALT, byteArrayOf(1, 0, 1)),
            YunmoProtocol.dimQueryFrame()
        )
        val start = YunmoProtocol.startMirrorFrames()
        assertEquals(YunmoProtocol.CMD_DISPLAY_ALT, start[0][4].toInt() and 0xFF)
        assertEquals(7, start[0][8].toInt())
        assertEquals(YunmoProtocol.CMD_DISPLAY, start[1][4].toInt() and 0xFF)
        assertEquals(7, start[1][8].toInt())
        val stop = YunmoProtocol.stopFrames()
        assertEquals(3, stop[0][8].toInt())
        assertEquals(5, stop[1][8].toInt())
        assertEquals(6, YunmoProtocol.mapNaviFrame()[8].toInt())
    }

    @Test
    fun encodeH264ExPadsToThirtyTwoAndKeepsMetadataOff() {
        val accessUnit = ByteArray(10) { 1 }
        val frame = YunmoProtocol.encodeH264Ex(accessUnit, width = 1024, height = 464, frameId = 9)

        // padded = ceil(10/32)*32 = 32; total = 32 + 40 header
        assertEquals(72, frame.size)
        assertEquals(0x1D, frame[4].toInt() and 0xFF)
        // blocks = (32 + 32) / 32 = 2, big-endian in [5..6], checksum in [7]
        assertEquals(0, frame[5].toInt())
        assertEquals(2, frame[6].toInt())
        assertEquals(2, frame[7].toInt())
        // media type LEGACY at [15]
        assertEquals(YunmoProtocol.MEDIA_TYPE_LEGACY, frame[15].toInt())
        // real length little-endian at [8..11]
        assertEquals(10, frame[8].toInt())
        assertEquals(0, frame[9].toInt())
        // AU byte checksum little-endian at [12..13] = 10
        assertEquals(10, frame[12].toInt())
        assertEquals(0, frame[13].toInt())
        // metadata block [16..24] stays zero because omitMeta defaults true
        for (i in 16..24) assertEquals("byte $i", 0, frame[i].toInt())
        // access unit copied verbatim at offset 40
        assertArrayEquals(accessUnit, frame.copyOfRange(40, 50))
    }

    @Test
    fun encodeH264ExWritesMetadataWhenAskedTo() {
        val frame = YunmoProtocol.encodeH264Ex(
            ByteArray(4) { 0 }, width = 0x0201, height = 0x0403, frameId = 0x0A, omitMeta = false
        )
        // frameId LE [16..19]
        assertEquals(0x0A, frame[16].toInt())
        // width LE [20..21]
        assertEquals(0x01, frame[20].toInt())
        assertEquals(0x02, frame[21].toInt())
        // height LE [22..23]
        assertEquals(0x03, frame[22].toInt())
        assertEquals(0x04, frame[23].toInt())
    }

    @Test
    fun parseOkDimensionReadsTheRealPanelSizeBigEndianWithoutDoubling() {
        // A dimension reply captured from a real X-Cape 1200. This test used to assert a 512x232
        // report doubling to the 1024x464 the owner measured on the OEM display; the capture shows
        // the dash reports that 1024x464 itself, so doubling asked for twice the panel.
        val payload = byteArrayOf(0, 0, 0, 0, 0x04, 0x00, 0x01, 0xD0.toByte())
        val report = YunmoProtocol.parseOkDimension(payload)!!
        assertEquals(1024, report.reportedWidth)
        assertEquals(464, report.reportedHeight)

        val (w, h) = YunmoProtocol.encodeCanvas(report, fallbackWidth = 800, fallbackHeight = 480)
        assertEquals(1024, w)
        assertEquals(464, h)
    }

    @Test
    fun encodeCanvasNeverExceedsWhatTheDashReportedAndFallsBackWhenItIsSilent() {
        // Odd axes round DOWN: overshooting the panel is the failure being guarded against, so an
        // odd height loses a line rather than gaining fifteen to reach a multiple of 16.
        val report = YunmoProtocol.DimensionReport(301, 151)
        val (w, h) = YunmoProtocol.encodeCanvas(report, 800, 480)
        assertEquals(300, w)
        assertEquals(150, h)

        val (fw, fh) = YunmoProtocol.encodeCanvas(null, 800, 480)
        assertEquals(800, fw)
        assertEquals(480, fh)
    }

    @Test
    fun parseOkDimensionRejectsShortOrOutOfRangePayloads() {
        assertNull(YunmoProtocol.parseOkDimension(ByteArray(4)))
        // width 0 is below the floor
        assertNull(YunmoProtocol.parseOkDimension(byteArrayOf(0, 0, 0, 0, 0, 0, 0x01, 0x00)))
    }

    @Test
    fun parseAckReadsLittleEndianFrameIdAndRejectsNonAcks() {
        assertEquals(0x04030201, YunmoProtocol.parseAck(byteArrayOf(0, 1, 2, 3, 4)))
        // A map-nav confirm (payload[0] == 6) is not an ack.
        assertNull(YunmoProtocol.parseAck(byteArrayOf(6)))
        assertTrue(YunmoProtocol.isMapNaviConfirm(byteArrayOf(6)))
    }

    @Test
    fun readSimpleFrameRoundTripsAnEncodedFrame() {
        val encoded = YunmoProtocol.encodeSimple(YunmoProtocol.CMD_OK_A, byteArrayOf(9, 8, 7))
        val frame = YunmoProtocol.readSimpleFrame(ByteArrayInputStream(encoded))!!
        assertEquals(YunmoProtocol.CMD_OK_A, frame.command)
        assertArrayEquals(byteArrayOf(9, 8, 7), frame.payload)
    }

    @Test
    fun readSimpleFrameResyncsPastLeadingGarbage() {
        val encoded = YunmoProtocol.encodeSimple(YunmoProtocol.CMD_DISPLAY, byteArrayOf(0, 1, 2, 3, 4))
        val noisy = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55) + encoded
        val frame = YunmoProtocol.readSimpleFrame(ByteArrayInputStream(noisy))!!
        assertEquals(YunmoProtocol.CMD_DISPLAY, frame.command)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), frame.payload)
    }

    @Test
    fun readSimpleFrameRejectsACorruptChecksum() {
        val encoded = YunmoProtocol.encodeSimple(YunmoProtocol.CMD_OK_A, byteArrayOf(1, 2, 3))
        encoded[encoded.size - 1] = (encoded[encoded.size - 1] + 1).toByte()
        assertNull(YunmoProtocol.readSimpleFrame(ByteArrayInputStream(encoded)))
    }

    @Test
    fun annexBFromAvccRewritesLengthPrefixesToStartCodes() {
        // One AVCC NAL: 4-byte big-endian length 3, then payload 67 01 02 (SPS).
        val avcc = byteArrayOf(0, 0, 0, 3, 0x67, 0x01, 0x02)
        val annexB = YunmoProtocol.annexBFromAvcc(avcc)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x01, 0x02), annexB)
        val nals = YunmoProtocol.splitAnnexB(annexB)
        assertEquals(1, nals.size)
        assertEquals(YunmoProtocol.NAL_SPS, nals[0].type)
    }

    @Test
    fun nalNameLabelsTheTypesThePhaseLogReports() {
        assertEquals("SPS", YunmoProtocol.nalName(YunmoProtocol.NAL_SPS))
        assertEquals("PPS", YunmoProtocol.nalName(YunmoProtocol.NAL_PPS))
        assertEquals("IDR", YunmoProtocol.nalName(YunmoProtocol.NAL_IDR))
        assertEquals("P", YunmoProtocol.nalName(YunmoProtocol.NAL_P))
        assertEquals("NAL9", YunmoProtocol.nalName(9))
    }

    @Test
    fun stripLeadingSpsPpsKeepsOnlyTheCodedPicture() {
        val sc = byteArrayOf(0, 0, 0, 1)
        val sps = sc + byteArrayOf(0x67, 0xAA.toByte())
        val pps = sc + byteArrayOf(0x68, 0xBB.toByte())
        val idr = sc + byteArrayOf(0x65, 0xCC.toByte())
        val keyframe = sps + pps + idr

        assertTrue(YunmoProtocol.annexBContainsNal(keyframe, YunmoProtocol.NAL_IDR))
        assertArrayEquals(idr, YunmoProtocol.stripLeadingSpsPps(keyframe))
    }

    @Test
    fun displayOpcodesTellApartMapNavConfirmSimpleNaviAndAnAck() {
        // cmd=6: the dash is on the full-screen map and will paint what we push.
        assertTrue(YunmoProtocol.isMapNaviConfirm(byteArrayOf(6)))
        assertFalse(YunmoProtocol.isSimpleNaviSwitch(byteArrayOf(6)))

        // cmd=5 inbound: the dash draws its own arrows and discards our frames. The same value is
        // the second half of our outbound teardown pair, so only the direction separates them.
        assertTrue(YunmoProtocol.isSimpleNaviSwitch(byteArrayOf(5)))
        assertFalse(YunmoProtocol.isMapNaviConfirm(byteArrayOf(5)))
        assertEquals(YunmoProtocol.DISP_EXIT_B, YunmoProtocol.DISP_SIMPLE_NAVI)

        // payload[0]==0 is an ack carrying a little-endian frame id, not a mode at all.
        val ack = byteArrayOf(0, 0x2A, 0, 0, 0)
        assertFalse(YunmoProtocol.isMapNaviConfirm(ack))
        assertFalse(YunmoProtocol.isSimpleNaviSwitch(ack))
        assertEquals(42, YunmoProtocol.parseAck(ack))

        // An empty payload must not be read as any of them.
        assertFalse(YunmoProtocol.isMapNaviConfirm(ByteArray(0)))
        assertFalse(YunmoProtocol.isSimpleNaviSwitch(ByteArray(0)))
    }
}
