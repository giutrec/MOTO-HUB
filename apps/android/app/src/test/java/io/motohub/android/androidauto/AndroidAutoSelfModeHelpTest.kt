package io.motohub.android.androidauto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoSelfModeHelpTest {
    @Test
    fun `versions verified working are not flagged`() {
        // 17.2.662634 is the build Android Auto projection is confirmed working on.
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17.2.662634-release"))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17.1.6624"))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("16.9.999999-release"))
    }

    @Test
    fun `versions that removed self-mode are flagged`() {
        // 17.4.663004 is the build where every entry point stopped working.
        assertTrue(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17.4.663004-release"))
        assertTrue(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17.3.0"))
        assertTrue(AndroidAutoSelfModeHelp.isKnownBrokenVersion("18.0.1-release"))
    }

    @Test
    fun `an unreadable version is never flagged`() {
        // Guessing "broken" from a version we cannot parse would scare users off a working setup.
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion(null))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion(""))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("not-a-version"))
        assertFalse(AndroidAutoSelfModeHelp.isKnownBrokenVersion("17"))
    }
}
