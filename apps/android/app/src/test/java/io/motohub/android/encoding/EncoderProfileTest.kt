package io.motohub.android.encoding

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderProfileTest {
    @Test
    fun `legacy landscape area is aligned to complete H264 macroblocks`() {
        assertEquals(EncoderProfile(width = 800, height = 384), EncoderProfile.forTBoxArea(800, 386))
    }

    @Test
    fun `portrait area is aligned without model-specific dimensions`() {
        assertEquals(EncoderProfile(width = 800, height = 944), EncoderProfile.forTBoxArea(800, 951))
    }

    @Test
    fun `already aligned runtime area remains unchanged`() {
        assertEquals(EncoderProfile(width = 1280, height = 576), EncoderProfile.forTBoxArea(1280, 576))
    }

    @Test
    fun `unknown runtime area is aligned without a device profile`() {
        assertEquals(EncoderProfile(width = 1024, height = 592), EncoderProfile.forTBoxArea(1024, 601))
    }

    @Test
    fun `a GOP this codec cannot repair falls back to all-intra`() {
        // Without intra refresh nothing repairs the picture between keyframes, so every frame the
        // link drops smears the TFT until the next IDR - green macroblocks on a CFMOTO MTX800,
        // while Android Auto's all-intra stream on the same phone stayed clean.
        assertEquals(
            0,
            effectiveKeyframeIntervalSeconds(
                requestedSeconds = 1,
                plainGopWithoutIntraRefresh = false,
                intraRefreshAvailable = false
            )
        )
    }

    @Test
    fun `a GOP backed by intra refresh is kept`() {
        assertEquals(
            1,
            effectiveKeyframeIntervalSeconds(
                requestedSeconds = 1,
                plainGopWithoutIntraRefresh = false,
                intraRefreshAvailable = true
            )
        )
    }

    @Test
    fun `a profile that refused intra refresh keeps its plain GOP`() {
        // Yunmo splits real keyframes into three wire frames and KOVE's decoder froze on refresh:
        // those GOPs are deliberate, not a codec capability the encoder gets to second-guess.
        assertEquals(
            2,
            effectiveKeyframeIntervalSeconds(
                requestedSeconds = 2,
                plainGopWithoutIntraRefresh = true,
                intraRefreshAvailable = false
            )
        )
    }

    @Test
    fun `negotiated T-Box profile defaults to the all-intra stream`() {
        // AA projection and mirroring pace frames by dropping encoder output, which is only
        // decodable on an all-intra stream; GOP encoding is an explicit per-session opt-in.
        assertEquals(0, EncoderProfile.forTBoxArea(800, 386).keyframeIntervalSeconds)
    }
}
