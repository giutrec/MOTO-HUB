package io.motohub.android.feature.controls

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The volume-silence inference is stored beside the calibration, not inside it. These tests pin
 * that separation, because the failure it prevents is silent: if the flag were ever written under
 * a [PhysicalPress] id, [HandlebarCalibration.isCalibrated] would flip to true and every ASSUMED
 * binding - play/pause included - would vanish for a rider who never opened the wizard.
 *
 * SharedPreferences needs a Context and this module has no Robolectric, so what is asserted here
 * is the key algebra rather than the read/write path.
 */
class HandlebarCalibrationKeysTest {

    @Test
    fun `the volume-silence key is not a press id`() {
        PhysicalPress.entries.forEach { press ->
            assertNotEquals(
                "the inference must never be stored under a press id",
                press.id,
                HandlebarCalibration.KEY_VOLUME_ROCKER_SILENT
            )
        }
    }

    @Test
    fun `the volume-silence key is not a gesture id either`() {
        // Values are matched back to gestures by id in gestureFor; a collision here would make
        // the flag decode as a binding.
        HandlebarGesture.entries.forEach { gesture ->
            assertNotEquals(gesture.id, HandlebarCalibration.KEY_VOLUME_ROCKER_SILENT)
        }
    }

    @Test
    fun `the volume-silence key cannot ride through a companion sync`() {
        // export/import move "pressId=value" pairs. The flag is not a press, so a crafted or
        // stale entry naming it is dropped rather than written back into the calibration.
        assertNull(parseCalibrationEntry("${HandlebarCalibration.KEY_VOLUME_ROCKER_SILENT}=true"))
    }

    @Test
    fun `the key survives motorcycle scoping like any other`() {
        // MotorcycleScope splits on '#', so a key containing one would collide across bikes.
        assertTrue(!HandlebarCalibration.KEY_VOLUME_ROCKER_SILENT.contains('#'))
    }
}
