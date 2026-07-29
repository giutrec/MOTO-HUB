package io.motohub.android.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxQrParserTest {
    @Test
    fun parsesEasyConnQrWithEncodedCredentials() {
        val result = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=TBOX%20RIDE&pwd=pass%2Bword&auth=WPA2&name=My%20Bike"
        )

        assertEquals("TBOX RIDE", result.getOrThrow().ssid)
        assertEquals("pass+word", result.getOrThrow().password)
        assertEquals("My Bike", result.getOrThrow().displayName)
        assertEquals(TBoxQrOrigin.CARBIT, result.getOrThrow().origin)
    }

    @Test
    fun preservesTheQrModelIdAsAnOpaqueTboxIdentifier() {
        val result = TBoxQrParser.parse(
            "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37416&sn=test&action=9&ssid=TBOX-test&pwd=example&auth=wpa2-psk&mac=00%3A00%3A00%3A00%3A00%3A00&name=TBOX-test"
        )

        assertEquals("TBOX-test", result.getOrThrow().ssid)
        assertEquals("example", result.getOrThrow().password)
        assertEquals("wpa2-psk", result.getOrThrow().encryption)
        assertEquals("37416", result.getOrThrow().modelId)
        assertEquals(TBoxQrOrigin.CARBIT, result.getOrThrow().origin)
    }

    @Test
    fun acceptsARebrandedProvisioningHostAsUnverified() {
        val payload = TBoxQrParser.parse(
            "https://connect.example-motors.com/pair?modelid=90210&ssid=VG-9F21A0&pwd=secret&auth=wpa2-psk"
        ).getOrThrow()

        assertEquals("VG-9F21A0", payload.ssid)
        assertEquals("secret", payload.password)
        assertEquals("90210", payload.modelId)
        assertEquals(TBoxQrOrigin.UNVERIFIED, payload.origin)
    }

    @Test
    fun parsesAPlainWifiNetworkCodeAsUnverified() {
        val payload = TBoxQrParser.parse("WIFI:S:ZT-DASH-7742;T:WPA;P:rider2026;H:false;;").getOrThrow()

        assertEquals("ZT-DASH-7742", payload.ssid)
        assertEquals("rider2026", payload.password)
        assertEquals("WPA", payload.encryption)
        assertNull(payload.modelId)
        assertEquals(TBoxQrOrigin.UNVERIFIED, payload.origin)
    }

    @Test
    fun honoursBackslashEscapesInsideAWifiNetworkCode() {
        val payload = TBoxQrParser.parse("WIFI:S:Bike\\:One;T:WPA;P:a\\;b\\\\c;;").getOrThrow()

        assertEquals("Bike:One", payload.ssid)
        assertEquals("a;b\\c", payload.password)
    }

    @Test
    fun rejectsContentWithoutANetworkName() {
        assertTrue(TBoxQrParser.parse("https://example.com/watch?v=abc123").isFailure)
        assertTrue(TBoxQrParser.parse("just some scanned text").isFailure)
        assertTrue(TBoxQrParser.parse("WIFI:T:WPA;P:secret;;").isFailure)
    }
}
