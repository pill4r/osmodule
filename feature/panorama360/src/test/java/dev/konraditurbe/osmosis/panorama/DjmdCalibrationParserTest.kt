package dev.konraditurbe.osmosis.panorama

import dev.konraditurbe.osmosis.panorama.render.DjmdCalibrationParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DjmdCalibrationParserTest {
    @Test
    fun `decodes and normalizes two factory-calibrated lenses from first mdat payload`() {
        val first = lens(1049.1f, 1913.5f, floatArrayOf(0f, 0f, 0.7071f, -0.7071f))
        val second = lens(1048.9f, 1913.3f, floatArrayOf(0.7071f, 0.7071f, 0f, 0f))
        val calibrationContainer = message(field(1, first), field(2, second))
        val sample = message(field(2, message(field(6, calibrationContainer))))
        val file = box("ftyp", byteArrayOf()) + box("mdat", sample)

        val calibration = DjmdCalibrationParser.parseFileHeader(file)
        assertNotNull(calibration)
        val parsed = calibration!!

        assertEquals(2, parsed.lenses.size)
        assertEquals(1049.1f, parsed.lenses[0].fx, 0.001f)
        assertEquals(1913.3f, parsed.lenses[1].cx, 0.001f)
        assertEquals(1913.5f / 3840f, parsed.lenses[0].shaderValues.lens[0], 0.00001f)
        assertEquals(1049.1f / 3840f, parsed.lenses[0].shaderValues.lens[2], 0.00001f)
        assertEquals(0.0011f, parsed.lenses[0].shaderValues.k5, 0.00001f)
        assertArrayEquals(floatArrayOf(-0.0003f, 0.0007f), parsed.lenses[0].tangential, 0.00001f)
        // Field 21 is already xyzw and must not be reshuffled before it reaches GLSL.
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0.7071f, -0.7071f),
            parsed.lenses[0].shaderValues.quaternionXyzw,
            0.0001f,
        )
    }

    @Test
    fun `rejects truncated or unrelated data`() {
        assertNull(DjmdCalibrationParser.parseFileHeader(byteArrayOf(1, 2, 3)))
        assertNull(DjmdCalibrationParser.parseFileHeader(box("mdat", field(1, byteArrayOf(1)))))
    }

    private fun lens(fx: Float, cx: Float, quaternion: FloatArray): ByteArray {
        val lutX = floatArrayOf(1920f, 318f, 518f, 753f, 1012f, 1299f, 1605f, 1920f, 2235f, 2541f, 2828f, 3087f, 3322f, 3522f)
        val lutY = floatArrayOf(3735f, 2845f, 3096f, 3310f, 3492f, 3626f, 3707f, 3735f, 3707f, 3626f, 3492f, 3310f, 3096f, 2845f)
        return message(
            fixed32(1, fx), fixed32(2, fx), fixed32(3, cx), fixed32(4, 1919f),
            fixed32(5, 0.063f), fixed32(6, -0.0086f), fixed32(7, 0.008f), fixed32(8, -0.006f),
            fixed32(10, 3840f), fixed32(11, 3840f),
            fixed32(15, 0.0011f), field(20, packed(floatArrayOf(-0.0003f, 0.0007f))),
            field(21, packed(quaternion)), field(22, packed(lutX)), field(23, packed(lutY)),
        )
    }

    private fun fixed32(number: Int, value: Float): ByteArray =
        varint((number shl 3) or 5) + ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(value)
            .array()

    private fun field(number: Int, value: ByteArray): ByteArray =
        varint((number shl 3) or 2) + varint(value.size) + value

    private fun packed(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .also { buffer -> values.forEach(buffer::putFloat) }
        .array()

    private fun message(vararg fields: ByteArray): ByteArray = ByteArrayOutputStream().run {
        fields.forEach(::write)
        toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(payload.size + 8)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(payload.size + 8)
        .put(type.toByteArray(Charsets.US_ASCII))
        .put(payload)
        .array()

    private fun varint(number: Int): ByteArray {
        var value = number
        return ByteArrayOutputStream().run {
            do {
                val next = value and 0x7f
                value = value ushr 7
                write(if (value == 0) next else next or 0x80)
            } while (value != 0)
            toByteArray()
        }
    }
}
