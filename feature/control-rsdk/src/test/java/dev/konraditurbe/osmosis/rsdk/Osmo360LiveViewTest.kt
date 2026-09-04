package dev.konraditurbe.osmosis.rsdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Osmo360LiveViewTest {
    @Test
    fun buildsMimoLiveSubscriptionWithBigEndianWireSequence() {
        val payload = ByteArray(11).apply { this[10] = 0x03 }

        val frame = osmo360DumlFrame(0x08, 0x9988, 0x40, 0x02, 0x09, payload)

        assertArrayEquals(hex("55180420020899884002090000000000000000000003b5a9"), frame)
    }

    @Test
    fun extractsSessionVideoAtDetectedAnnexBoundary() {
        val nal = hex("000000016742001f")
        val packet = sessionPacket(video = true, mediaOffset = 24, payload = nal)

        val extracted = Osmo360PacketParser.videoPayload(packet)!!
        assertArrayEquals(nal, extracted.copyOf(nal.size))
    }

    @Test
    fun ignoresSessionControlPackets() {
        assertNull(Osmo360PacketParser.videoPayload(sessionPacket(video = false, payload = ByteArray(520))))
    }

    @Test
    fun parsesFrameAndFragmentNumbersIncludingWrappedFrameZero() {
        val packet = sessionPacket(
            video = true,
            payload = hex("00000001618011"),
        ).apply {
            this[16] = 0
            this[17] = 0x0E
            this[18] = 7
        }

        val fragment = Osmo360PacketParser.videoFragment(packet)!!

        assertEquals(0, fragment.frameNumber)
        assertEquals(7, fragment.fragmentIndex)
    }

    @Test
    fun parsesHighHalfOfNineBitFragmentIndex() {
        val packet = sessionPacket(video = true, payload = hex("00000001618011")).apply {
            this[17] = 0x8E.toByte()
            this[18] = 7
        }

        assertEquals(263, Osmo360PacketParser.videoFragment(packet)!!.fragmentIndex)
    }

    @Test
    fun frameAssemblerEmitsEveryCameraPictureInsteadOfWaitingForAnIdr() {
        val assembler = Osmo360FrameAssembler()
        val marker = hex("000001ff10203040")
        val firstSliceA = hex("00000001618011")
        val firstSliceB = hex("00000001614022")
        val secondPicture = hex("00000001618033")
        val thirdPicture = hex("00000001618044")

        assertTrue(assembler.feed(fragment(12, 0, marker + firstSliceA)).isEmpty())
        assertTrue(assembler.feed(fragment(12, 1, firstSliceB)).isEmpty())
        val first = assembler.feed(fragment(13, 0, marker + secondPicture))
        val second = assembler.feed(fragment(14, 0, marker + thirdPicture))

        assertEquals(1, first.size)
        assertArrayEquals(firstSliceA + firstSliceB, first.single())
        assertEquals(1, second.size)
        assertArrayEquals(secondPicture, second.single())
    }

    @Test
    fun frameAssemblerCarriesFactoryParameterSetsIntoFollowingIdrFrame() {
        val assembler = Osmo360FrameAssembler()
        val sps = hex("000000016742001f")
        val pps = hex("0000000168ce06e2")
        val idr = hex("00000001658011")
        val next = hex("00000001618022")

        assertTrue(assembler.feed(fragment(20, 0, sps + pps)).isEmpty())
        assertTrue(assembler.feed(fragment(21, 0, idr)).isEmpty())
        val output = assembler.feed(fragment(22, 0, next))

        assertEquals(1, output.size)
        assertArrayEquals(sps + pps + idr, output.single())
    }

    @Test
    fun frameAssemblerDiscardsAFrameWithAMissingFragmentAndRecovers() {
        val assembler = Osmo360FrameAssembler()
        val brokenStart = hex("000000016180")
        val brokenEnd = hex("11")
        val nextPicture = hex("00000001618022")
        val followingPicture = hex("00000001618033")

        assertTrue(assembler.feed(fragment(30, 0, brokenStart)).isEmpty())
        assertTrue(assembler.feed(fragment(30, 2, brokenEnd)).isEmpty())
        assertTrue(assembler.feed(fragment(31, 0, nextPicture)).isEmpty())
        val output = assembler.feed(fragment(32, 0, followingPicture))

        assertEquals(1, assembler.droppedUnits)
        assertEquals(1, output.size)
        assertArrayEquals(nextPicture, output.single())
    }

    @Test
    fun frameAssemblerAcceptsFragmentIndexHalfBoundaryAndIgnoresExactDuplicate() {
        val assembler = Osmo360FrameAssembler()
        val first = hex("000000016180")
        val tail = hex("11")
        val nextPicture = hex("00000001618022")

        assertTrue(assembler.feed(fragment(40, 255, first)).isEmpty())
        assertTrue(assembler.feed(fragment(40, 255, first)).isEmpty())
        assertTrue(assembler.feed(fragment(40, 256, tail)).isEmpty())
        val output = assembler.feed(fragment(41, 0, nextPicture))

        assertEquals(0, assembler.droppedUnits)
        assertEquals(1, output.size)
        assertArrayEquals(first + tail, output.single())
    }

    @Test
    fun assemblerInjectsParameterSetsIntoFirstKeyframe() {
        val assembler = Osmo360AnnexBAssembler()
        val sps = hex("000000016742001f")
        val pps = hex("0000000168ce06e2")
        val idr = hex("00000001658884")
        val pFrame = hex("00000001619a10")
        val nextFrame = hex("00000001619a20")

        val output = assembler.feed(sps + pps + idr + pFrame + nextFrame)

        assertEquals(1, output.size)
        assertArrayEquals(sps + pps + idr, output.single())
        assertEquals(0, assembler.droppedUnits)
    }

    @Test
    fun assemblerPreservesStartCodeSplitAcrossPackets() {
        val assembler = Osmo360AnnexBAssembler()
        val first = hex("000000016742001f0000")
        val second = hex("000168ce06e20000000165888400000001619a1000000001619a20")

        assertTrue(assembler.feed(first).isEmpty())
        val output = assembler.feed(second)

        assertEquals(1, output.size)
        assertTrue(output.single().contentEquals(hex("000000016742001f0000000168ce06e200000001658884")))
    }

    @Test
    fun assemblerKeepsMultipleSlicesOfOnePictureInTheSameAccessUnit() {
        val assembler = Osmo360AnnexBAssembler()
        // first_mb_in_slice is ue(v): 0x80 begins with `1` => 0; 0x40 begins with `010` => 1.
        val firstSlice = hex("00000001618011")
        val secondSlice = hex("00000001614022")
        val nextPicture = hex("00000001618033")
        val followingPicture = hex("00000001618044")

        val output = assembler.feed(firstSlice + secondSlice + nextPicture + followingPicture)

        assertEquals(1, output.size)
        assertArrayEquals(firstSlice + secondSlice, output.single())
    }

    @Test
    fun assemblerInjectsParameterSetsOnlyOnceForAMultiSliceKeyframe() {
        val assembler = Osmo360AnnexBAssembler()
        val sps = hex("000000016742001f")
        val pps = hex("0000000168ce06e2")
        val firstIdrSlice = hex("00000001658011")
        val secondIdrSlice = hex("00000001654022")
        val nextPicture = hex("00000001618033")
        val followingPicture = hex("00000001618044")

        val output = assembler.feed(
            sps + pps + firstIdrSlice + secondIdrSlice + nextPicture + followingPicture,
        )

        assertEquals(1, output.size)
        assertArrayEquals(sps + pps + firstIdrSlice + secondIdrSlice, output.single())
    }

    @Test
    fun assemblerUsesAudAsAnExplicitPictureBoundary() {
        val assembler = Osmo360AnnexBAssembler()
        val picture = hex("00000001618011")
        val aud = hex("0000000169f0")
        val nextPicture = hex("00000001618022")
        val followingPicture = hex("00000001618033")

        val output = assembler.feed(picture + aud + nextPicture + followingPicture)

        assertEquals(1, output.size)
        assertArrayEquals(picture, output.single())
    }

    private fun sessionPacket(
        video: Boolean,
        mediaOffset: Int = 20,
        payload: ByteArray,
    ): ByteArray {
        val size = maxOf(512, mediaOffset + payload.size)
        return ByteArray(size).apply {
            this[0] = (size and 0xFF).toByte()
            this[1] = (0x80 or (size ushr 8)).toByte()
            this[2] = 0x92.toByte()
            this[3] = 0xEC.toByte()
            this[6] = if (video) 0x02 else 0x01
            if (video) this[16] = 0x01
            payload.copyInto(this, mediaOffset)
        }
    }

    private fun fragment(frame: Int, index: Int, payload: ByteArray) =
        Osmo360PacketParser.VideoFragment(frame, index, payload)

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
