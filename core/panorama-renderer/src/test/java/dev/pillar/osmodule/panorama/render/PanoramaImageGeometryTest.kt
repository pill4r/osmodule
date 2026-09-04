package dev.pillar.osmodule.panorama.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaImageGeometryTest {
    @Test
    fun `recognises stitched Osmo 360 image sizes`() {
        assertTrue(PanoramaImageGeometry.isEquirectangular(7776, 3888))
        assertTrue(PanoramaImageGeometry.isEquirectangular(15520, 7760))
    }

    @Test
    fun `does not mistake single-lens photos for panoramas`() {
        assertFalse(PanoramaImageGeometry.isEquirectangular(6400, 4800))
        assertFalse(PanoramaImageGeometry.isEquirectangular(4000, 3000))
        assertFalse(PanoramaImageGeometry.isEquirectangular(0, 0))
    }

    @Test
    fun `samples large panoramas to the renderer texture limit`() {
        assertEquals(1, PanoramaImageGeometry.decodeSampleSize(7776, 3888, 8192))
        assertEquals(2, PanoramaImageGeometry.decodeSampleSize(7776, 3888, 4096))
        assertEquals(4, PanoramaImageGeometry.decodeSampleSize(15520, 7760, 4096))
    }
}
