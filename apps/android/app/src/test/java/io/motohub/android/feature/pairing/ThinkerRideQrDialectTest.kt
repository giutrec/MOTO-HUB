// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.tbox.ThinkerRideProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkerRideQrDialectTest {

    @Test
    fun parsesTheKoveThinkerRidePairingCode() {
        val payload = TBoxQrParser.parse("http://g.thinkerride.com/?KOVE-800X-3f2a&87654321&ap=1").getOrThrow()

        assertEquals("KOVE-800X-3f2a", payload.ssid)
        assertEquals("87654321", payload.password)
        assertEquals("wpa2-psk", payload.encryption)
        assertEquals(ThinkerRideProtocol.PROVISIONING_MODEL_ID, payload.modelId)
        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
        assertEquals(TBoxConnectionMode.THINKERRIDE, payload.suggestedConnectionMode)
    }

    @Test
    fun recognisesTheHostWithoutTheApMarker() {
        val payload = TBoxQrParser.parse("http://g.thinkerride.com/?KOVE-450R-11bc&pass1234").getOrThrow()

        assertEquals(TBoxQrOrigin.RECOGNISED, payload.origin)
        assertEquals(TBoxConnectionMode.THINKERRIDE, payload.suggestedConnectionMode)
    }

    @Test
    fun acceptsARebadgedOemHostOnlyWithTheApMarkerAndAsksTheRider() {
        // Same positional shape from an unknown host: the `ap=1` marker is the second witness,
        // and the payload still goes to the rider as UNVERIFIED instead of being trusted.
        val payload = TBoxQrParser.parse("http://pair.some-oem.example/?BIKE-AP&secret99&ap=1").getOrThrow()

        assertEquals("BIKE-AP", payload.ssid)
        assertEquals("secret99", payload.password)
        assertEquals(TBoxQrOrigin.UNVERIFIED, payload.origin)
        assertEquals(TBoxConnectionMode.THINKERRIDE, payload.suggestedConnectionMode)
    }

    @Test
    fun refusesThePositionalShapeFromAnUnknownHostWithoutTheMarker() {
        // Nothing vouches for this: not the host, not the marker. It must not become a
        // ThinkerRide profile, and the provisioning parser correctly finds no ssid= in it.
        val result = TBoxQrParser.parse("http://example.com/?SOME-AP&secret")

        assertTrue(result.isFailure)
    }

    @Test
    fun decodesPercentEscapedCredentials() {
        val payload = TBoxQrParser.parse("http://g.thinkerride.com/?KOVE%20800X&pa%2Bss&ap=1").getOrThrow()

        assertEquals("KOVE 800X", payload.ssid)
        assertEquals("pa+ss", payload.password)
    }

    @Test
    fun leavesCarbitProvisioningCodesToTheProvisioningParser() {
        // A keyed query never matches the positional ThinkerRide shape, even with an ap
        // parameter somewhere in it.
        val payload = TBoxQrParser.parse(
            "https://setup.carbit.com/connect?ssid=TBOX-1234&pwd=secret&auth=wpa2-psk&ap=1"
        ).getOrThrow()

        assertEquals("TBOX-1234", payload.ssid)
        assertNull(payload.suggestedConnectionMode)
        assertNull(payload.modelId)
    }

    @Test
    fun leavesMotoFunCodesToTheMotoFunParser() {
        val payload = TBoxQrParser.parse(
            "http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c&MachineID=dc0d30da1b6c&ProductID=00297"
        ).getOrThrow()

        assertEquals("ML174167", payload.ssid)
        assertEquals("12345678", payload.password)
        assertNull(payload.suggestedConnectionMode)
    }
}
