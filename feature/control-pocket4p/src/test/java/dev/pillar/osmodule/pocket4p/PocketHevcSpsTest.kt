package dev.pillar.osmodule.pocket4p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketHevcSpsTest {
    @Test
    fun `captured Pocket live SPS is 1280 by 720`() {
        val raster = PocketHevcSps.raster(annexB(SPS))
        requireNotNull(raster)
        assertEquals(1280, raster.width)
        assertEquals(720, raster.height)
    }

    @Test
    fun `same raster parameter change keeps codec but a screen flip rebuilds`() {
        val original = annexB(SPS)
        val changed = original.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        assertFalse(original.contentEquals(changed))
        val landscape = PocketHevcSps.raster(changed)
        assertEquals(PocketHevcSps.Raster(1280, 720), landscape)
        assertFalse(PocketHevcDecoder.shouldRebuildForRaster(1280, 720, landscape))
        assertTrue(
            PocketHevcDecoder.shouldRebuildForRaster(
                1280,
                720,
                PocketHevcSps.Raster(720, 1280),
            ),
        )
        assertFalse(
            "an unparseable SPS cannot prove the raster changed",
            PocketHevcDecoder.shouldRebuildForRaster(1280, 720, null),
        )
    }

    @Test
    fun `malformed SPS is rejected without an unbounded bit read`() {
        assertNull(PocketHevcSps.raster(annexB("42010000")))
    }

    @Test
    fun `first dropped dependent frame requests one fresh camera GOP`() {
        assertTrue(PocketHevcDecoder.shouldRequestFreshIrap(false, isIrap = false, queued = false))
        assertFalse(PocketHevcDecoder.shouldRequestFreshIrap(true, isIrap = false, queued = false))
        assertFalse(PocketHevcDecoder.shouldRequestFreshIrap(false, isIrap = true, queued = false))
        assertFalse(PocketHevcDecoder.shouldRequestFreshIrap(false, isIrap = false, queued = true))
    }

    private fun annexB(hex: String): ByteArray = byteArrayOf(0, 0, 1) + hex(hex)

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        // Capture-confirmed Pocket live-view SPS used by OpenPocketCine's Android regression test.
        const val SPS =
            "42010121600000030000030000030000030096a00280802d17aeedc9ae5d4d404040410000030001000003001908"
    }
}
