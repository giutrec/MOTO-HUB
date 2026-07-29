package io.motohub.android.feature.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaButtonBridgeTest {
    @Test
    fun `maps volume changes to dashboard gestures`() {
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_UP_DOUBLE, forceDouble = false),
            interpretVolumeDelta(1, HandlebarAction.SCROLL_BACK, streamMax = 25)
        )
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_UP_DOUBLE, forceDouble = true),
            interpretVolumeDelta(3, HandlebarAction.SCROLL_BACK, streamMax = 25)
        )
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_DOWN, HandlebarGesture.VOLUME_DOWN_DOUBLE, forceDouble = false),
            interpretVolumeDelta(-1, HandlebarAction.SCROLL_FORWARD, streamMax = 25)
        )
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_DOWN, HandlebarGesture.VOLUME_DOWN_DOUBLE, forceDouble = true),
            interpretVolumeDelta(-3, HandlebarAction.SCROLL_FORWARD, streamMax = 25)
        )
        assertNull(interpretVolumeDelta(0, HandlebarAction.SCROLL_FORWARD, streamMax = 25))
    }

    @Test
    fun `replays a fused two-step jump as two scroll clicks`() {
        assertEquals(
            VolumeDeltaRead.ScrollClicks(HandlebarGesture.VOLUME_DOWN, 2),
            interpretVolumeDelta(-2, HandlebarAction.SCROLL_FORWARD, streamMax = 25)
        )
        assertEquals(
            VolumeDeltaRead.ScrollClicks(HandlebarGesture.VOLUME_UP, 2),
            interpretVolumeDelta(2, HandlebarAction.SCROLL_BACK, streamMax = 25)
        )
        // A non-repeatable mapping must never fire twice for one physical press.
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_UP_DOUBLE, forceDouble = false),
            interpretVolumeDelta(2, HandlebarAction.SELECT, streamMax = 25)
        )
    }

    @Test
    fun `reads a bike absolute-volume overwrite as one press of its sign`() {
        // CFDL16 road test 2026-07-29: pin 159, bike wrote 70 → delta −89 on a 255-step stream.
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_DOWN, HandlebarGesture.VOLUME_DOWN_DOUBLE, forceDouble = false),
            interpretVolumeDelta(-89, HandlebarAction.SCROLL_FORWARD, streamMax = 255)
        )
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_UP_DOUBLE, forceDouble = false),
            interpretVolumeDelta(64, HandlebarAction.SELECT, streamMax = 255)
        )
        // The simulator's small coalesced deltas keep their double-press meaning.
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_UP_DOUBLE, forceDouble = true),
            interpretVolumeDelta(3, HandlebarAction.SELECT, streamMax = 255)
        )
        // On coarse 15-step streams the floor stays above the coalesced-double threshold.
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_DOWN, HandlebarGesture.VOLUME_DOWN_DOUBLE, forceDouble = false),
            interpretVolumeDelta(-5, HandlebarAction.SELECT, streamMax = 15)
        )
    }

    @Test
    fun `new handlebar gestures keep the expected defaults`() {
        assertEquals(HandlebarAction.SELECT, HandlebarGesture.ENTER.defaultAction)
        assertEquals(HandlebarAction.HOME, HandlebarGesture.ENTER_LONG.defaultAction)
        assertEquals(HandlebarAction.BACK, HandlebarGesture.ENTER_DOUBLE.defaultAction)
        assertEquals(HandlebarAction.HOME, HandlebarGesture.TRACK_BACK_DOUBLE.defaultAction)
        assertEquals(HandlebarAction.BACK, HandlebarGesture.TRACK_FORWARD_DOUBLE.defaultAction)
    }

    @Test
    fun `timing options match the upstream release values`() {
        assertEquals(listOf(200L, 300L, 450L), DoubleTapDelay.entries.map { it.millis })
        assertEquals(listOf(500L, 600L, 800L), SelectHoldDelay.entries.map { it.millis })
    }

    @Test
    fun `simulator gesture ids map to handlebar gestures`() {
        HandlebarGesture.entries.forEach { gesture ->
            assertEquals(gesture, handlebarGestureForSimulatorId(gesture.id))
        }
        assertNull(handlebarGestureForSimulatorId("missing"))
    }
}
