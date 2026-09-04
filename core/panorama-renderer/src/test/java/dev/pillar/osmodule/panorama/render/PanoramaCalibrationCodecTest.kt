package dev.pillar.osmodule.panorama.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanoramaCalibrationCodecTest {
    @Test
    fun `round trips both factory lenses through primitive IPC data`() {
        val source = PanoramaCalibration(
            listOf(
                lens(1049.1f, 1913.5f, floatArrayOf(1f, 0f, 0f, 0f)),
                lens(1048.9f, 1913.3f, floatArrayOf(0f, 1f, 0f, 0f)),
            ),
        )

        val decoded = PanoramaCalibrationCodec.decode(PanoramaCalibrationCodec.encode(source))!!

        assertEquals(2, decoded.lenses.size)
        source.lenses.zip(decoded.lenses).forEach { (expected, actual) ->
            assertEquals(expected.fx, actual.fx, 0f)
            assertEquals(expected.cx, actual.cx, 0f)
            assertArrayEquals(expected.quaternion, actual.quaternion, 0f)
            assertArrayEquals(expected.distortion, actual.distortion, 0f)
            assertArrayEquals(expected.radialLutX, actual.radialLutX, 0f)
            assertArrayEquals(expected.radialLutY, actual.radialLutY, 0f)
        }
    }

    @Test
    fun `rejects truncated or foreign IPC data`() {
        assertNull(PanoramaCalibrationCodec.decode(floatArrayOf()))
        assertNull(PanoramaCalibrationCodec.decode(floatArrayOf(36_001f, 1f, 2f)))
        assertNull(PanoramaCalibrationCodec.decode(floatArrayOf(123f, 1f, 2f)))
    }

    private fun lens(fx: Float, cx: Float, quaternion: FloatArray) = LensCalibration(
        fx = fx,
        fy = fx + 0.2f,
        cx = cx,
        cy = 1919f,
        width = 3840f,
        height = 3840f,
        quaternion = quaternion,
        distortion = floatArrayOf(0.063f, -0.0086f, 0.008f, -0.006f),
        radialLutX = floatArrayOf(1920f, 318f, 518f, 753f),
        radialLutY = floatArrayOf(3735f, 2845f, 3096f, 3310f),
    )
}
