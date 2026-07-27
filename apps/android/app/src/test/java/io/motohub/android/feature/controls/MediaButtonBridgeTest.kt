package io.motohub.android.feature.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaButtonBridgeTest {
    @Test
    fun `maps volume changes to dashboard gestures`() {
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_UP_DOUBLE, forceDouble = false),
            interpretVolumeDelta(1, HandlebarAction.SCROLL_BACK)
        )
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_UP_DOUBLE, forceDouble = true),
            interpretVolumeDelta(3, HandlebarAction.SCROLL_BACK)
        )
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_DOWN, HandlebarGesture.VOLUME_DOWN_DOUBLE, forceDouble = false),
            interpretVolumeDelta(-1, HandlebarAction.SCROLL_FORWARD)
        )
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_DOWN, HandlebarGesture.VOLUME_DOWN_DOUBLE, forceDouble = true),
            interpretVolumeDelta(-3, HandlebarAction.SCROLL_FORWARD)
        )
        assertNull(interpretVolumeDelta(0, HandlebarAction.SCROLL_FORWARD))
    }

    @Test
    fun `replays a fused two-step jump as two scroll clicks`() {
        assertEquals(
            VolumeDeltaRead.ScrollClicks(HandlebarGesture.VOLUME_DOWN, 2),
            interpretVolumeDelta(-2, HandlebarAction.SCROLL_FORWARD)
        )
        assertEquals(
            VolumeDeltaRead.ScrollClicks(HandlebarGesture.VOLUME_UP, 2),
            interpretVolumeDelta(2, HandlebarAction.SCROLL_BACK)
        )
        // A non-repeatable mapping must never fire twice for one physical press.
        assertEquals(
            VolumeDeltaRead.Tap(HandlebarGesture.VOLUME_UP, HandlebarGesture.VOLUME_UP_DOUBLE, forceDouble = false),
            interpretVolumeDelta(2, HandlebarAction.SELECT)
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
