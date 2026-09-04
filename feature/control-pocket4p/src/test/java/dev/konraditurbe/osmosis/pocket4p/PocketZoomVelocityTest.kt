package dev.konraditurbe.osmosis.pocket4p

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketZoomVelocityTest {
    @Test
    fun `held upward and downward positions produce opposite constant rates`() {
        assertEquals(3.0, PocketZoomVelocity.factorPerSecond(-100f, 100f), EPSILON)
        assertEquals(-3.0, PocketZoomVelocity.factorPerSecond(100f, 100f), EPSILON)
        assertEquals(1.465909, PocketZoomVelocity.factorPerSecond(-55f, 100f), EPSILON)
    }

    @Test
    fun `small movement remains neutral`() {
        assertEquals(0.0, PocketZoomVelocity.factorPerSecond(10f, 100f), EPSILON)
        assertEquals(0.0, PocketZoomVelocity.factorPerSecond(-10f, 100f), EPSILON)
    }

    @Test
    fun `time integration is smooth and clamps to one through twelve`() {
        assertEquals(1.3, PocketZoomVelocity.advance(1.0, 3.0, 100, 1.0, 12.0), EPSILON)
        assertEquals(12.0, PocketZoomVelocity.advance(11.9, 3.0, 100, 1.0, 12.0), EPSILON)
        assertEquals(1.0, PocketZoomVelocity.advance(1.1, -3.0, 100, 1.0, 12.0), EPSILON)
    }

    private companion object {
        const val EPSILON = 0.0001
    }
}
