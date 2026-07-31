package io.motohub.android.feature.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class MotorcycleScopeTest {

    @Test
    fun `reads prefer the scoped key and fall back to the global one`() {
        val stored = setOf("mapping#bikeA")

        // The scoped bike reads its own value.
        assertEquals("mapping#bikeA", MotorcycleScope.readKey("mapping", "bikeA", stored::contains))
        // Another bike with no scoped value falls back to the global key: existing
        // single-bike settings become every bike's default, nothing is lost on upgrade.
        assertEquals("mapping", MotorcycleScope.readKey("mapping", "bikeB", stored::contains))
        // No motorcycle known at all: plain global keys, exactly like before scoping.
        assertEquals("mapping", MotorcycleScope.readKey("mapping", null, stored::contains))
    }

    @Test
    fun `writes always target the scoped key when a motorcycle is known`() {
        assertEquals("mapping#bikeA", MotorcycleScope.writeKeyFor("mapping", "bikeA"))
        assertEquals("mapping", MotorcycleScope.writeKeyFor("mapping", null))
    }

    @Test
    fun `scoped keys cannot collide with plain base keys`() {
        // '#' never appears in gesture/press ids, so a scoped key is never a valid base key.
        assertEquals("volumeUp#id", MotorcycleScope.scopedKey("volumeUp", "id"))
        HandlebarGesture.entries.forEach { gesture -> assert(!gesture.id.contains('#')) }
        PhysicalPress.entries.forEach { press -> assert(!press.id.contains('#')) }
    }

    /**
     * MotorcycleScope reads the garage's active-profile id straight from its preferences
     * (decoding the full profile store per button press would be far too heavy), so these
     * names must stay in lockstep with MotorcycleProfileStore's private constants.
     */
    @Test
    fun `garage preference names match MotorcycleProfileStore`() {
        assertEquals("motorcycle_profiles", MotorcycleScope.GARAGE_PREFERENCES)
        assertEquals("active_id", MotorcycleScope.GARAGE_ACTIVE_ID)
    }
}
