package dev.konraditurbe.osmosis.pocket4p

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketPreviewLayoutTest {
    @Test
    fun `landscape feed fits fully inside portrait view`() {
        val scale = PocketPreviewLayout.aspectFit(1080, 2400, 1280, 720)

        assertEquals(1f, scale.x, EPSILON)
        assertEquals(0.253125f, scale.y, EPSILON)
    }

    @Test
    fun `landscape feed fits fully inside wider landscape view`() {
        val scale = PocketPreviewLayout.aspectFit(2400, 1080, 1280, 720)

        assertEquals(0.8f, scale.x, EPSILON)
        assertEquals(1f, scale.y, EPSILON)
    }

    @Test
    fun `matching aspect ratio needs no correction`() {
        val scale = PocketPreviewLayout.aspectFit(1920, 1080, 1280, 720)

        assertEquals(1f, scale.x, EPSILON)
        assertEquals(1f, scale.y, EPSILON)
    }

    @Test
    fun `invalid dimensions retain identity transform`() {
        assertEquals(PocketPreviewLayout.Scale(1f, 1f), PocketPreviewLayout.aspectFit(0, 0, 1280, 720))
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
