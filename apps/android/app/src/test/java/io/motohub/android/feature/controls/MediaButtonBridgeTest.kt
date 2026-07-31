package io.motohub.android.feature.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * Regression: thresholds used to be a fixed number of steps, which assumed the common
     * 0-15 volume scale. A OnePlus CPH2653 runs 0-160 and moves ten steps per key press
     * (road test 2026-07-29), so every single press was being reported as a double.
     */
    @Test
    fun `a single press on a fine-grained volume scale is not read as a double`() {
        val read = interpretVolumeDelta(delta = -10, singleAction = HandlebarAction.BACK, streamMax = 160)

        val tap = read as VolumeDeltaRead.Tap
        assertEquals(HandlebarGesture.VOLUME_DOWN, tap.single)
        assertFalse(tap.forceDouble)
    }

    /**
     * Three presses' worth forces a double on any scale - the same rule the 15-step stream
     * has always used, now counted in presses rather than raw steps. Two presses stay a
     * single here exactly as they do on a coarse scale: the tap window pairs those.
     */
    @Test
    fun `three coalesced presses on a fine-grained scale read as a double`() {
        val two = interpretVolumeDelta(delta = 20, singleAction = HandlebarAction.BACK, streamMax = 160)
        val three = interpretVolumeDelta(delta = 30, singleAction = HandlebarAction.BACK, streamMax = 160)

        assertFalse((two as VolumeDeltaRead.Tap).forceDouble)
        assertTrue((three as VolumeDeltaRead.Tap).forceDouble)
    }

    /** The classic 0-15 scale must keep behaving exactly as it did before. */
    @Test
    fun `a coarse scale keeps its original single and double thresholds`() {
        val singlePress = interpretVolumeDelta(delta = 1, singleAction = HandlebarAction.BACK, streamMax = 15)
        val doublePress = interpretVolumeDelta(delta = 3, singleAction = HandlebarAction.BACK, streamMax = 15)

        assertFalse((singlePress as VolumeDeltaRead.Tap).forceDouble)
        assertTrue((doublePress as VolumeDeltaRead.Tap).forceDouble)
    }

    // ── tap dispatch (eager singles) ─────────────────────────────────────────────────────────

    @Test
    fun `an eager single fires immediately and a second press still fires the double`() {
        assertEquals(
            TapDispatch.SINGLE_NOW,
            resolveTapDispatch(forceDouble = false, eagerSingle = true, hasPending = false, gapMillis = 5_000)
        )
        // The marker from the first press is still alive: the second press is a double.
        assertEquals(
            TapDispatch.DOUBLE,
            resolveTapDispatch(forceDouble = false, eagerSingle = true, hasPending = true, gapMillis = 200)
        )
    }

    @Test
    fun `a deferred single waits and pairs with a pending press as a double`() {
        assertEquals(
            TapDispatch.SINGLE_DEFERRED,
            resolveTapDispatch(forceDouble = false, eagerSingle = false, hasPending = false, gapMillis = 5_000)
        )
        assertEquals(
            TapDispatch.DOUBLE,
            resolveTapDispatch(forceDouble = false, eagerSingle = false, hasPending = true, gapMillis = 200)
        )
    }

    @Test
    fun `a same-press echo inside the refractory window is suppressed`() {
        assertEquals(
            TapDispatch.SUPPRESS_ECHO,
            resolveTapDispatch(forceDouble = false, eagerSingle = true, hasPending = true, gapMillis = 40)
        )
        // A dash-coalesced double is never mistaken for an echo.
        assertEquals(
            TapDispatch.DOUBLE,
            resolveTapDispatch(forceDouble = true, eagerSingle = true, hasPending = false, gapMillis = 40)
        )
    }

    // ── calibration sync encoding ────────────────────────────────────────────────────────────

    @Test
    fun `calibration entries round-trip through the IPC encoding`() {
        assertEquals(
            PhysicalPress.UP_HOLD to HandlebarGesture.TRACK_FORWARD.id,
            parseCalibrationEntry("${PhysicalPress.UP_HOLD.id}=${HandlebarGesture.TRACK_FORWARD.id}")
        )
        assertEquals(
            PhysicalPress.LEFT_PRESS to HandlebarCalibration.MISSING,
            parseCalibrationEntry("${PhysicalPress.LEFT_PRESS.id}=${HandlebarCalibration.MISSING}")
        )
        // An unbound (released) press keeps its empty value.
        assertEquals(
            PhysicalPress.SELECT_DOUBLE to HandlebarCalibration.UNBOUND,
            parseCalibrationEntry("${PhysicalPress.SELECT_DOUBLE.id}=")
        )
    }

    @Test
    fun `calibration parsing rejects unknown presses and values`() {
        assertNull(parseCalibrationEntry("not_a_press=${HandlebarGesture.ENTER.id}"))
        assertNull(parseCalibrationEntry("${PhysicalPress.UP_PRESS.id}=not_a_gesture"))
        assertNull(parseCalibrationEntry("garbage"))
    }

    @Test
    fun `every physical press round-trips every legal stored value`() {
        PhysicalPress.entries.forEach { press ->
            HandlebarGesture.entries.forEach { gesture ->
                assertEquals(press to gesture.id, parseCalibrationEntry("${press.id}=${gesture.id}"))
            }
            assertEquals(press to HandlebarCalibration.MISSING, parseCalibrationEntry("${press.id}=${HandlebarCalibration.MISSING}"))
        }
    }
}
