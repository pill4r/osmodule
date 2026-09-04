package dev.pillar.osmodule.pocket4p

/**
 * Minimal HEVC SPS parser for deciding whether a live parameter-set change changed the coded raster.
 * Adapted from OpenPocketCine's `LivePictureSps.kt` (Apache-2.0).
 */
internal object PocketHevcSps {
    data class Raster(val width: Int, val height: Int)

    fun raster(spsNal: ByteArray): Raster? = runCatching {
        val header = when {
            spsNal.size >= 6 && spsNal[0] == 0.toByte() && spsNal[1] == 0.toByte() &&
                spsNal[2] == 0.toByte() && spsNal[3] == 1.toByte() -> 4
            spsNal.size >= 5 && spsNal[0] == 0.toByte() && spsNal[1] == 0.toByte() &&
                spsNal[2] == 1.toByte() -> 3
            else -> 0
        }
        require(spsNal.size >= header + 3)
        require(((spsNal[header].toInt() and 0xFF) ushr 1) and 0x3F == SPS_NAL_TYPE)

        val bits = BitReader(rbsp(spsNal, header + HEVC_NAL_HEADER_BYTES))
        bits.read(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = bits.read(3)
        bits.read(1) // sps_temporal_id_nesting_flag
        skipProfileTierLevel(bits, maxSubLayersMinus1)
        bits.ue() // sps_seq_parameter_set_id
        val chromaFormatIdc = bits.ue()
        if (chromaFormatIdc == 3) bits.read(1)
        var width = bits.ue()
        var height = bits.ue()
        if (bits.read(1) == 1) {
            val left = bits.ue()
            val right = bits.ue()
            val top = bits.ue()
            val bottom = bits.ue()
            val subWidth = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
            val subHeight = if (chromaFormatIdc == 1) 2 else 1
            width -= (left + right) * subWidth
            height -= (top + bottom) * subHeight
        }
        require(width > 1 && height > 1)
        Raster(width, height)
    }.getOrNull()

    private fun skipProfileTierLevel(bits: BitReader, maxSubLayersMinus1: Int) {
        bits.skip(2 + 1 + 5 + 32 + 48 + 8)
        if (maxSubLayersMinus1 <= 0) return
        val hasProfile = BooleanArray(maxSubLayersMinus1)
        val hasLevel = BooleanArray(maxSubLayersMinus1)
        repeat(maxSubLayersMinus1) { index ->
            hasProfile[index] = bits.read(1) == 1
            hasLevel[index] = bits.read(1) == 1
        }
        repeat(8 - maxSubLayersMinus1) { bits.skip(2) }
        repeat(maxSubLayersMinus1) { index ->
            if (hasProfile[index]) bits.skip(2 + 1 + 5 + 32 + 48)
            if (hasLevel[index]) bits.skip(8)
        }
    }

    /** Remove emulation-prevention `03` bytes after the NAL header. */
    private fun rbsp(nal: ByteArray, payloadOffset: Int): ByteArray {
        val out = ArrayList<Byte>(nal.size - payloadOffset)
        var index = payloadOffset
        while (index < nal.size) {
            if (index + 2 < nal.size && nal[index] == 0.toByte() &&
                nal[index + 1] == 0.toByte() && nal[index + 2] == 3.toByte()
            ) {
                out += 0
                out += 0
                index += 3
            } else {
                out += nal[index]
                index++
            }
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var bitOffset = 0

        fun read(count: Int): Int {
            require(count in 0..31 && bitOffset + count <= data.size * 8)
            var value = 0
            repeat(count) {
                val byteIndex = bitOffset / 8
                val shift = 7 - bitOffset % 8
                value = (value shl 1) or ((data[byteIndex].toInt() ushr shift) and 1)
                bitOffset++
            }
            return value
        }

        fun skip(count: Int) {
            require(count >= 0 && bitOffset + count <= data.size * 8)
            bitOffset += count
        }

        fun ue(): Int {
            var leadingZeros = 0
            while (read(1) == 0) {
                require(leadingZeros < 30)
                leadingZeros++
            }
            return if (leadingZeros == 0) 0 else ((1 shl leadingZeros) - 1) + read(leadingZeros)
        }
    }

    private const val HEVC_NAL_HEADER_BYTES = 2
    private const val SPS_NAL_TYPE = 33
}
