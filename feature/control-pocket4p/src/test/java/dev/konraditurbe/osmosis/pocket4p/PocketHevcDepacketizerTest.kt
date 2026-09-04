package dev.konraditurbe.osmosis.pocket4p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PocketHevcDepacketizerTest {
    private val marker = bytes(0, 0, 1, 0xFF) + ByteArray(13)
    private val slice = bytes(0, 0, 1, 0x02, 0xAA, 0xBB)

    @Test
    fun `groups fragments and emits marker-free access unit at frame boundary`() {
        val depacketizer = PocketHevcDepacketizer()
        val frame = marker + slice
        assertNull(depacketizer.feed(videoPacket(0x10, 0, frame.copyOfRange(0, 10))))
        assertNull(depacketizer.feed(videoPacket(0x10, 1, frame.copyOfRange(10, frame.size))))

        val accessUnit = depacketizer.feed(videoPacket(0x11, 0, marker))
        assertArrayEquals(slice, accessUnit)
        assertEquals(0, depacketizer.droppedIncomplete)
    }

    @Test
    fun `missing fragment drops the whole access unit`() {
        val depacketizer = PocketHevcDepacketizer()
        depacketizer.feed(videoPacket(0x20, 0, marker))
        depacketizer.feed(videoPacket(0x20, 2, slice))

        assertNull(depacketizer.feed(videoPacket(0x21, 0, marker)))
        assertEquals(1, depacketizer.droppedIncomplete)
    }

    @Test
    fun `duplicate fragment is ignored without corrupting access unit`() {
        val depacketizer = PocketHevcDepacketizer()
        val frame = marker + slice
        val first = videoPacket(0x30, 0, frame.copyOfRange(0, 10))
        depacketizer.feed(first)
        depacketizer.feed(first.copyOf())
        depacketizer.feed(videoPacket(0x30, 1, frame.copyOfRange(10, frame.size)))

        assertArrayEquals(slice, depacketizer.feed(videoPacket(0x31, 0, marker)))
        assertEquals(0, depacketizer.droppedIncomplete)
    }

    @Test
    fun `relative fragment sequence may start above zero`() {
        val depacketizer = PocketHevcDepacketizer()
        val frame = marker + slice
        depacketizer.feed(videoPacket(0x40, 128, frame.copyOfRange(0, 10)))
        depacketizer.feed(videoPacket(0x40, 129, frame.copyOfRange(10, frame.size)))

        assertArrayEquals(slice, depacketizer.feed(videoPacket(0x41, 0, marker)))
        assertEquals(0, depacketizer.droppedIncomplete)
    }

    @Test
    fun `frame counter wrap is a normal boundary`() {
        val depacketizer = PocketHevcDepacketizer()
        val frame = marker + slice
        depacketizer.feed(videoPacket(0xFF, 0, frame.copyOfRange(0, 10)))
        depacketizer.feed(videoPacket(0xFF, 1, frame.copyOfRange(10, frame.size)))

        assertArrayEquals(slice, depacketizer.feed(videoPacket(0x00, 0, marker)))
        assertEquals(0, depacketizer.droppedIncomplete)
    }

    @Test
    fun `reused frame number with lower position closes previous access unit`() {
        val depacketizer = PocketHevcDepacketizer()
        val frame = marker + slice
        depacketizer.feed(videoPacket(0x50, 8, frame.copyOfRange(0, 10)))
        depacketizer.feed(videoPacket(0x50, 9, frame.copyOfRange(10, frame.size)))

        assertArrayEquals(slice, depacketizer.feed(videoPacket(0x50, 0, marker + slice)))
        assertEquals(0, depacketizer.droppedIncomplete)
    }

    @Test
    fun `lower nonzero fragment marks access unit corrupt instead of emitting a prefix`() {
        val depacketizer = PocketHevcDepacketizer()
        val frame = marker + slice
        depacketizer.feed(videoPacket(0x55, 8, frame.copyOfRange(0, 10)))
        depacketizer.feed(videoPacket(0x55, 9, frame.copyOfRange(10, frame.size)))

        assertNull(depacketizer.feed(videoPacket(0x55, 5, marker)))
        assertNull(depacketizer.feed(videoPacket(0x56, 0, marker)))
        assertEquals(1, depacketizer.droppedIncomplete)
    }

    @Test
    fun `non-video and truncated packets do not disturb active frame`() {
        val depacketizer = PocketHevcDepacketizer()
        val frame = marker + slice
        depacketizer.feed(videoPacket(0x60, 0, frame.copyOfRange(0, 10)))
        assertNull(depacketizer.feed(ByteArray(20)))
        assertNull(depacketizer.feed(videoPacket(0x60, 99, bytes(1, 2), packetType = 0x05)))
        depacketizer.feed(videoPacket(0x60, 1, frame.copyOfRange(10, frame.size)))

        assertArrayEquals(slice, depacketizer.feed(videoPacket(0x61, 0, marker)))
    }

    @Test
    fun `four-byte Annex-B prefix remains valid after DJI marker`() {
        val depacketizer = PocketHevcDepacketizer()
        val fourByteSlice = bytes(0, 0, 0, 1, 0x28, 0x01, 0xCC)
        depacketizer.feed(videoPacket(0x70, 0, marker + fourByteSlice))

        // The marker stripper finds the 00 00 01 suffix within a four-byte prefix, matching the
        // OpenPocketCine reference normalization to a three-byte Annex-B start code.
        assertArrayEquals(
            bytes(0, 0, 1, 0x28, 0x01, 0xCC),
            depacketizer.feed(videoPacket(0x71, 0, marker)),
        )
    }

    @Test
    fun `reset clears assembly and diagnostics`() {
        val depacketizer = PocketHevcDepacketizer()
        depacketizer.feed(videoPacket(1, 0, marker))
        depacketizer.feed(videoPacket(1, 2, slice))
        depacketizer.feed(videoPacket(2, 0, marker))
        assertEquals(1, depacketizer.droppedIncomplete)

        depacketizer.reset()
        assertEquals(0, depacketizer.droppedIncomplete)
        assertNull(depacketizer.feed(videoPacket(3, 0, marker + slice)))
        assertArrayEquals(slice, depacketizer.feed(videoPacket(4, 0, marker)))
    }

    private fun videoPacket(
        frame: Int,
        position: Int,
        body: ByteArray,
        packetType: Int = 0x02,
    ): ByteArray {
        require(position in 0..511)
        return ByteArray(20).apply {
            this[6] = packetType.toByte()
            this[16] = frame.toByte()
            this[17] = ((position and 1) shl 7).toByte()
            this[18] = (position / 2).toByte()
        } + body
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
