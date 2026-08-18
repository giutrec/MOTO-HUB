package io.motohub.android.encoding

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveVideoPolicyTest {
    @Test
    fun `a healthy link runs at the ceiling it was given`() {
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = 4_000_000,
            baseFrameRate = 30,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            previousLinkFactor = AdaptiveVideoPolicy.LINK_MAX,
            lostFrames = 0
        )
        assertEquals(4_000_000, decision.bitrate)
        assertEquals(30, decision.frameRate)
    }

    @Test
    fun `a link losing frames gives up frame rate as well as bitrate`() {
        // Cutting the bitrate alone leaves the same number of frames queued on the transport whose
        // queue is what overflowed, which is how a CFMOTO MTX800 kept feeding 30fps into a pipe
        // that was dropping ~280 frames a session.
        val decision = AdaptiveVideoPolicy.decide(
            baseBitrate = 4_000_000,
            baseFrameRate = 30,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
            previousLinkFactor = AdaptiveVideoPolicy.LINK_MAX,
            lostFrames = AdaptiveVideoPolicy.DROP_THRESHOLD
        )
        assertTrue(decision.bitrate < 4_000_000)
        assertTrue(decision.frameRate < 30)
    }

    @Test
    fun `the backoff never paces below the slideshow floor`() {
        var factor = AdaptiveVideoPolicy.LINK_MAX
        repeat(50) {
            factor = AdaptiveVideoPolicy.nextLinkFactor(factor, AdaptiveVideoPolicy.DROP_THRESHOLD)
        }
        assertEquals(AdaptiveVideoPolicy.LINK_MIN, factor, 0.001f)
        assertEquals(
            AdaptiveVideoPolicy.MIN_FRAME_RATE,
            AdaptiveVideoPolicy.linkFrameRateCap(baseFrameRate = 30, linkFactor = factor)
        )
    }

    @Test
    fun `a ceiling already below the floor is never raised by the backoff`() {
        assertEquals(
            10,
            AdaptiveVideoPolicy.linkFrameRateCap(baseFrameRate = 10, linkFactor = 0.4f)
        )
    }

    @Test
    fun `a recovered link climbs back to the ceiling`() {
        var factor = AdaptiveVideoPolicy.LINK_MIN
        repeat(20) { factor = AdaptiveVideoPolicy.nextLinkFactor(factor, 0) }
        assertEquals(AdaptiveVideoPolicy.LINK_MAX, factor, 0.001f)
        assertEquals(
            24,
            AdaptiveVideoPolicy.linkFrameRateCap(baseFrameRate = 24, linkFactor = factor)
        )
    }
}
