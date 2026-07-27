package io.motohub.android.tbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDaemonSessionGenerationTest {
    @Test
    fun callbackFromActiveSessionIsAccepted() {
        assertTrue(isCurrentRideDaemonSession(callbackGeneration = 7L, activeGeneration = 7L))
    }

    @Test
    fun callbackAfterStopIsIgnored() {
        assertFalse(isCurrentRideDaemonSession(callbackGeneration = 7L, activeGeneration = 0L))
    }

    @Test
    fun callbackFromPreviousSessionIsIgnored() {
        assertFalse(isCurrentRideDaemonSession(callbackGeneration = 7L, activeGeneration = 8L))
    }
}
