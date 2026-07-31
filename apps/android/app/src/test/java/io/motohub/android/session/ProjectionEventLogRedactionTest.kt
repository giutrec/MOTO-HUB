package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionEventLogRedactionTest {
    @Test
    fun redactsPasswordKeyValuePairs() {
        assertEquals(
            "Connecting with password=<redacted> to SSID CFMOTO-1234",
            ProjectionEventLog.redact("Connecting with password=Sup3rSecret to SSID CFMOTO-1234")
        )
    }

    @Test
    fun redactsPskAndPassphraseSynonyms() {
        assertFalse(ProjectionEventLog.redact("psk: abc123def").contains("abc123def"))
        assertFalse(ProjectionEventLog.redact("passphrase=hunter2!!").contains("hunter2"))
    }

    @Test
    fun redactsIpv4AddressesInsideFreeText() {
        val redacted = ProjectionEventLog.redact(
            "java.net.ConnectException: failed to connect to /192.168.49.1 (port 7788)"
        )
        assertFalse(redacted.contains("192.168.49.1"))
        assertEquals(
            "java.net.ConnectException: failed to connect to /<redacted-ip> (port 7788)",
            redacted
        )
    }

    @Test
    fun redactsColonAndHyphenSeparatedMacAddresses() {
        assertFalse(ProjectionEventLog.redact("Peer MAC AA:BB:CC:DD:EE:FF found").contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(ProjectionEventLog.redact("Peer MAC aa-bb-cc-dd-ee-ff found").contains("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun doesNotMangleVersionOrResolutionStrings() {
        val message = "MOTO-HUB 0.9.0-beta.10 negotiated 1280x720@30fps"
        assertEquals(message, ProjectionEventLog.redact(message))
    }

    @Test
    fun redactsTheDashboardsStableIdentifiersInARawClientInfoDump() {
        // Verbose T-Box logging is on by default, so the raw CLIENT_INFO JSON reaches logs
        // that riders paste into public threads - HUID and uuid identify the dash forever
        // and must not survive. This redaction is the precondition of that default.
        val redacted = ProjectionEventLog.redact(
            """{"HUID": "8f4a2b1c9d3e", "uuid": "c1a2b3d4e5f6", "flavor": "65536"}"""
        )
        assertFalse(redacted.contains("8f4a2b1c9d3e"))
        assertFalse(redacted.contains("c1a2b3d4e5f6"))
        // The brand flavor is the diagnostic payload - it has to survive the scrub.
        assertTrue(redacted.contains("65536"))
    }

    /**
     * The AI-provider key rules used to exist only in the companion edition's copy of this
     * shared file. A key is worth real money if it leaks, and "which edition am I in?" is not
     * a question a redaction rule should have an answer to - both editions carry them now.
     */
    @Test
    fun redactsAiProviderKeysWhateverShapeTheyArriveIn() {
        assertFalse(ProjectionEventLog.redact("api_key=sk-abcdefghijklmnop").contains("abcdefghijklmnop"))
        assertFalse(ProjectionEventLog.redact("""{"apiKey": "xyz123456789"}""").contains("xyz123456789"))
        assertFalse(ProjectionEventLog.redact("access_token: ya29.A0AfH6SMB").contains("ya29"))
    }

    @Test
    fun redactsABearerTokenPastTheFirstSpace() {
        // SECRET_PATTERN stops at the first space, so without the Bearer rule this used to
        // redact the word "Bearer" and leave the token itself in the clear.
        val redacted = ProjectionEventLog.redact(
            "HTTP 401 for Authorization: Bearer sk-proj-AbCdEf0123456789xyz"
        )
        assertFalse(redacted.contains("sk-proj-AbCdEf0123456789xyz"))
        assertFalse(redacted.contains("AbCdEf0123456789"))
    }

    @Test
    fun redactsABareKeyLiteralWithNoLabelAtAll() {
        val redacted = ProjectionEventLog.redact("request failed using sk-or-v1-0123456789abcdef")
        assertFalse(redacted.contains("sk-or-v1-0123456789abcdef"))
        assertTrue(redacted.contains("<redacted-key>"))
    }
}
