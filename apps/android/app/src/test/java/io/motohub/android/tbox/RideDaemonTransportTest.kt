package io.motohub.android.tbox

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDaemonTransportTest {
    @Test
    fun `decodes CFDL26 capture area`() {
        val payload = captureRequest(width = 720, height = 712)

        assertEquals(TBoxEvent.VideoArea(width = 720, height = 712), decodeTBoxVideoArea(payload))
    }

    @Test
    fun `decodes legacy capture area`() {
        val payload = captureRequest(width = 800, height = 386)

        assertEquals(TBoxEvent.VideoArea(width = 800, height = 386), decodeTBoxVideoArea(payload))
    }

    @Test
    fun `rejects incomplete or empty capture area`() {
        assertNull(decodeTBoxVideoArea(byteArrayOf(1, 2, 3)))
        assertNull(decodeTBoxVideoArea(captureRequest(width = 0, height = 712)))
    }

    @Test
    fun `describes the capture request of the Zontes field log`() {
        // Verbatim opening bytes of the 204-byte REQ_RV_CONFIG_CAPTURE body logged by the Zontes
        // dash (package tayo.com.ZontesIntelligence, modelId 21334) on 2026-07-30 - the session
        // that negotiated cleanly, took 4531 frames and never lit the TFT.
        val payload = ByteArray(204)
        byteArrayOf(
            0x00, 0x04, 0xa9.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0x00,
            0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00
        ).copyInto(payload)

        val described = describeTBoxCaptureRequest(payload)

        assertEquals(
            "size=204B, device=1024x425, fps=0, encoder=2, supportCodec=2, bitrate=8388608, " +
                "capScreenMode=0, touchMode=0, orientation=1, videoType=0, supportExtendProtocol=0",
            described
        )
    }

    @Test
    fun `describes a short capture request without inventing fields`() {
        val described = describeTBoxCaptureRequest(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0, 800).putShort(2, 480).array()
        )

        assertEquals(
            "size=4B, device=800x480, fps=?, encoder=?, supportCodec=?, bitrate=?, " +
                "capScreenMode=?, touchMode=?, orientation=?, videoType=?, supportExtendProtocol=?",
            described
        )
        assertNull(describeTBoxCaptureRequest(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `accepts simulator compatibility preset advertisements`() {
        assertTrue(isMotoHubSimulatorAdvertisement("MOTO-HUB T-Box Simulator 55262", "MOTO-HUB-SIMULATOR"))
        assertTrue(isMotoHubSimulatorAdvertisement("CFDL16-6GUV", "37416"))
        assertTrue(isMotoHubSimulatorAdvertisement("CFMOTO-805120", "37426"))
        assertTrue(isMotoHubSimulatorAdvertisement("CFMOTO-66660742", "66660742"))
    }

    @Test
    fun `rejects unrelated EasyConn advertisements for simulator profile`() {
        assertFalse(isMotoHubSimulatorAdvertisement("Someone Else", "37416"))
        assertFalse(isMotoHubSimulatorAdvertisement("CFDL16-6GUV", "unknown"))
        assertFalse(isMotoHubSimulatorAdvertisement("CFMOTO-123456", null))
    }

    @Test
    fun `native startup timeout allows simulator compatibility retries`() {
        assertEquals(25L, RIDE_DAEMON_STARTUP_TIMEOUT_SEC)
    }

    private fun captureRequest(width: Int, height: Int): ByteArray = ByteBuffer
        .allocate(204)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0, width.toShort())
        .putShort(2, height.toShort())
        .array()
}
