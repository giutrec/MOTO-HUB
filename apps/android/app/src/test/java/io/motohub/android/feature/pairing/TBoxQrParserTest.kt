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
        assertEquals(TBoxQrOrigin.RECOGNISED, result.getOrThrow().origin)
    }

    @Test
    fun keepsALiteralPlusInsideAPassphrase() {
        // A provisioning URL is a query string, not a submitted form: URLDecoder's form rules
        // turned this passphrase into "rider 2026" and every join failed association silently.
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=VOGE-5G-58e4&pwd=rider+2026&auth=wpa2-psk"
        ).getOrThrow()

        assertEquals("rider+2026", payload.password)
        assertEquals("VOGE-5G-58e4", payload.ssid)
    }

    @Test
    fun keepsAnUnescapedPercentInsteadOfRejectingTheWholeCode() {
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=TBOX-9f21&pwd=100%pure&auth=wpa2-psk"
        ).getOrThrow()

        assertEquals("100%pure", payload.password)
        assertEquals("TBOX-9f21", payload.ssid)
        // The host still has to be recognised on the hand-rolled path, or a valid Carbit QR
        // would silently drop to UNVERIFIED just because its passphrase held a `%`.
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
    }

    @Test
    fun decodesMultiByteEscapeSequencesAsOneCharacter() {
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=TBOX-9f21&pwd=secret&name=Moto%C3%A8"
        ).getOrThrow()

        assertEquals("Motoè", payload.displayName)
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
        assertEquals(TBoxQrOrigin.RECOGNISED, result.getOrThrow().origin)
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

    @Test
    fun readsAMotoFunCodeWhoseSeparatorWouldBeMistakenForAFragment() {
        // Confirmed on the Moto Morini X-Cape 649 / 700 and Seiemmezzo. Both URI.rawQuery and a
        // substringBefore('#') split stop at the first separator and return "Wifi=ML174167",
        // dropping the password: whatever reads this has to scan the raw string.
        val payload = TBoxQrParser.parse(
            "http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c" +
                "&MachineID=dc0d30da1b6c&ProductID=00297"
        ).getOrThrow()

        assertEquals("ML174167", payload.ssid)
        assertEquals("12345678", payload.password)
        assertEquals("00297", payload.modelId)
        assertEquals("wpa2-psk", payload.encryption)
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
    }

    @Test
    fun readsAMotoFunCodeWithNoTrailingMacAddress() {
        val payload = TBoxQrParser.parse(
            "http://admin.motomorini.com/app.html?Wifi=ML174167#12345678&ProductID=00297"
        ).getOrThrow()

        assertEquals("ML174167", payload.ssid)
        assertEquals("12345678", payload.password)
    }

    @Test
    fun treatsAnUncorroboratedMotoFunShapeAsUnverified() {
        // Same shape, unfamiliar host, and neither MotoFun identifier to vouch for it: usable, but
        // the rider confirms it rather than having it saved on the strength of the shape alone.
        val payload = TBoxQrParser.parse(
            "http://dash.example-motors.com/app.html?Wifi=XM-4471#rider2026"
        ).getOrThrow()

        assertEquals("XM-4471", payload.ssid)
        assertEquals("rider2026", payload.password)
        assertEquals(TBoxQrOrigin.UNVERIFIED, payload.origin)
    }

    @Test
    fun leavesACarbitCodeToTheQueryParserWhenNoPasswordFollowsTheSeparator() {
        // `wifi=` without a `#password` after it is not the MotoFun dialect, so the code still has
        // to be read as a query string - otherwise adding that dialect would break Carbit dashes.
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?wifi=1&ssid=TBOX-9f21&pwd=secret&auth=wpa2-psk"
        ).getOrThrow()

        assertEquals("TBOX-9f21", payload.ssid)
        assertEquals("secret", payload.password)
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
    }

    @Test
    fun readsParameterNamesRegardlessOfCase() {
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?SSID=TBOX-9f21&PWD=secret&Auth=wpa2-psk&ModelId=37416"
        ).getOrThrow()

        assertEquals("TBOX-9f21", payload.ssid)
        assertEquals("secret", payload.password)
        assertEquals("37416", payload.modelId)
    }

    @Test
    fun namesTheVehicleInformationCodeInsteadOfCallingItUnreadable() {
        // The dash prints several codes and only one of them pairs. "Unreadable" sends the rider
        // polishing the screen; naming the content sends them to the right screen.
        val failure = TBoxQrParser.parse("code:8A1&engine:CF400&vin:LCEPRJ&color:Fuji White")

        assertTrue(failure.isFailure)
        assertTrue(
            failure.exceptionOrNull()?.message.orEmpty().contains("vehicle information")
        )
    }

    @Test
    fun explainsAMotoMoriniCodeThatIsNotThePairingScreen() {
        val failure = TBoxQrParser.parse("http://admin.motomorini.com/app.html?MachineID=dc0d30da")

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("Wifi="))
    }
}
