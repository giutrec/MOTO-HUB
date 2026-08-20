package io.motohub.android.tbox

import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcBtpNetProtocolTest {

    @Test
    fun `client info is a well formed frame carrying the phone identity`() {
        val frame = EcBtpNetProtocol.clientInfo("MOTO-HUB")
        val parsed = EcBtpProtocol.parse(frame)

        assertNotNull(parsed)
        assertEquals(EcBtpNetProtocol.CMD_CLIENT_INFO, parsed!!.command)
        val json = JSONObject(parsed.payload.toString(StandardCharsets.UTF_8))
        assertEquals(0, json.getInt("phoneType"))
        assertEquals("MOTO-HUB", json.getString("phoneID"))
    }

    @Test
    fun `build net request carries no payload`() {
        val parsed = EcBtpProtocol.parse(EcBtpNetProtocol.requestBuildNet())

        assertNotNull(parsed)
        assertEquals(EcBtpNetProtocol.CMD_REQUEST_BUILD_NET, parsed!!.command)
        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun `phone ap info sends the credentials the dash needs to join`() {
        val frame = EcBtpNetProtocol.phoneApInfo(
            ssid = "AndroidShare_1234",
            password = "secretpass",
            auth = "WPA2",
            ip = "192.168.43.1"
        )
        val parsed = EcBtpProtocol.parse(frame!!)

        assertEquals(EcBtpNetProtocol.CMD_NOTIFY_AP_INFO, parsed!!.command)
        val json = JSONObject(parsed.payload.toString(StandardCharsets.UTF_8))
        assertEquals("AndroidShare_1234", json.getString("ssid"))
        assertEquals("secretpass", json.getString("pwd"))
        assertEquals("WPA2", json.getString("auth"))
        assertEquals("192.168.43.1", json.getString("ip"))
        // Carbit sends an empty MAC here too; the field has to exist, its value does not.
        assertEquals("", json.getString("mac"))
    }

    @Test
    fun `phone ap info omits the address when there is none to give`() {
        val frame = EcBtpNetProtocol.phoneApInfo(ssid = "n", password = "p", auth = "WPA2", ip = null)
        val json = JSONObject(EcBtpProtocol.parse(frame!!)!!.payload.toString(StandardCharsets.UTF_8))

        assertFalse(json.has("ip"))
    }

    @Test
    fun `credentials too long for one frame are refused rather than truncated`() {
        val frame = EcBtpNetProtocol.phoneApInfo(
            ssid = "s".repeat(200),
            password = "p".repeat(200),
            auth = "WPA2"
        )

        assertNull(frame)
    }

    @Test
    fun `a handshake without the support field is not a handshake`() {
        assertNull(EcBtpNetProtocol.parseHandshake("""{"modelid":"21322"}""".toByteArray()))
        assertNull(EcBtpNetProtocol.parseHandshake(ByteArray(0)))
        assertNull(EcBtpNetProtocol.parseHandshake("not json".toByteArray()))
    }

    @Test
    fun `build net support is bit one of the support function`() {
        val supports = EcBtpNetProtocol.parseHandshake(
            """{"supportFunction":7,"modelid":"21322"}""".toByteArray()
        )
        val doesNot = EcBtpNetProtocol.parseHandshake("""{"supportFunction":5}""".toByteArray())

        assertTrue(supports!!.supportsBuildNet)
        assertEquals("21322", supports.modelId)
        assertFalse(doesNot!!.supportsBuildNet)
        assertNull(doesNot.modelId)
    }

    @Test
    fun `use phone ap is read with the band the dash asked for`() {
        val status = EcBtpNetProtocol.parseBuildNet(
            """{"status":2,"phoneApFrequency":2437}""".toByteArray()
        )

        assertEquals(EcBtpNetProtocol.STATUS_USE_PHONE_AP, status!!.status)
        assertEquals(2437, status.phoneApFrequencyMhz)
    }

    @Test
    fun `a dash that wants to be joined describes its own network`() {
        val status = EcBtpNetProtocol.parseBuildNet(
            """
            {"status":1,"carNetDeviceInfo":{"ssid":"ZT5Gcf3b","pwd":"12345678",
            "auth":"WPA2","mac":"dc:0d:30:17:38:d4","mode":8,"name":"Zontes"}}
            """.trimIndent().toByteArray()
        )

        assertEquals(EcBtpNetProtocol.STATUS_PHONE_JOINS_DASH, status!!.status)
        assertEquals("ZT5Gcf3b", status.dashSsid)
        assertEquals("12345678", status.dashPassword)
        assertEquals(EcBtpNetProtocol.CAR_NET_MODE_P2P, status.dashMode)
    }

    @Test
    fun `car net info yields every address the dash listed`() {
        val interfaces = EcBtpNetProtocol.parseCarNetInterfaces(
            """{"cnt":2,"data":[{"name":"wlan0","ip":"192.168.43.49","mask":"255.255.255.0"},
                {"name":"eth0","ip":"10.0.0.2"}]}""".toByteArray()
        )

        assertEquals(2, interfaces.size)
        assertEquals("192.168.43.49", interfaces[0].ip)
        assertEquals("wlan0", interfaces[0].name)
        assertEquals("255.255.255.0", interfaces[0].mask)
        assertNull(interfaces[1].mask)
    }

    @Test
    fun `an announcement without an address yields nothing to probe`() {
        assertTrue(
            EcBtpNetProtocol.parseCarNetInterfaces("""{"cnt":1,"data":[{"name":"wlan0"}]}""".toByteArray())
                .isEmpty()
        )
        assertTrue(EcBtpNetProtocol.parseCarNetInterfaces("""{"cnt":0}""".toByteArray()).isEmpty())
    }

    @Test
    fun `frames split across notifications are reassembled`() {
        val frame = EcBtpNetProtocol.clientInfo("MOTO-HUB")
        val assembler = EcBtpNetProtocol.FrameAssembler()

        val firstHalf = assembler.accept(frame.copyOfRange(0, 7))
        val secondHalf = assembler.accept(frame.copyOfRange(7, frame.size))

        assertTrue(firstHalf.isEmpty())
        assertEquals(1, secondHalf.size)
        assertTrue(EcBtpProtocol.parse(frame)!!.payload.contentEquals(secondHalf[0].payload))
    }

    @Test
    fun `two frames in one notification both come out`() {
        val assembler = EcBtpNetProtocol.FrameAssembler()

        val frames = assembler.accept(
            EcBtpNetProtocol.requestBuildNet() + EcBtpNetProtocol.buildNetFinished()
        )

        assertEquals(2, frames.size)
        assertEquals(EcBtpNetProtocol.CMD_REQUEST_BUILD_NET, frames[0].command)
        assertEquals(EcBtpNetProtocol.CMD_NOTIFY_BUILD_NET_FINISH, frames[1].command)
    }

    @Test
    fun `noise before a frame is skipped rather than swallowing it`() {
        val assembler = EcBtpNetProtocol.FrameAssembler()

        // A stray start byte, then a real frame: resyncing costs bytes, never the frame.
        val frames = assembler.accept(byteArrayOf(0x24, 0x00) + EcBtpNetProtocol.requestBuildNet())

        assertEquals(1, frames.size)
        assertEquals(EcBtpNetProtocol.CMD_REQUEST_BUILD_NET, frames[0].command)
    }

    @Test
    fun `a payload containing the terminator is still one frame`() {
        // 0x0A inside JSON is what makes length-driven framing necessary: scanning for the
        // terminator would cut this frame in half.
        val frame = EcBtpProtocol.build(
            EcBtpNetProtocol.CMD_NOTIFY_CAR_NET_INFO,
            "{\n\"cnt\":1\n}".toByteArray(StandardCharsets.UTF_8)
        )
        val frames = EcBtpNetProtocol.FrameAssembler().accept(frame)

        assertEquals(1, frames.size)
        assertEquals("{\n\"cnt\":1\n}", frames[0].payload.toString(StandardCharsets.UTF_8))
    }
}
