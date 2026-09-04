package dev.pillar.osmodule.rsdk

import dev.pillar.osmodule.duml.DjiMessage
import java.io.ByteArrayOutputStream

internal fun osmo360DumlFrame(
    receiver: Int,
    wireSequence: Int,
    flags: Int,
    set: Int,
    id: Int,
    payload: ByteArray,
): ByteArray {
    val target = 0x02 or ((receiver and 0xFF) shl 8)
    val type = flags or (set shl 8) or (id shl 16)
    // DjiMessage models its id as LE; swap so the 360/Mimo wire id is BE.
    val messageId = ((wireSequence and 0xFF) shl 8) or ((wireSequence ushr 8) and 0xFF)
    return DjiMessage(target, messageId, type, payload).encode()
}

internal object Osmo360PacketParser {
    private const val MIN_MEDIA_PACKET_SIZE = 512
    private const val LEGACY_MEDIA_OFFSET = 36
    private const val SESSION_MEDIA_OFFSET = 20

    data class VideoFragment(
        val frameNumber: Int,
        val fragmentIndex: Int,
        val payload: ByteArray,
    )

    /**
     * DJI pktType 0x02 is already frame packetized: byte 16 is the frame number, while byte 17's
     * high bit and byte 18 form a 9-bit fragment index. Keep that metadata instead of guessing
     * picture boundaries from slice bytes.
     */
    fun videoFragment(packet: ByteArray): VideoFragment? {
        val total = totalLength(packet)
        if (total < MIN_MEDIA_PACKET_SIZE || total > packet.size) return null
        val payloadOffset = when {
            hasLegacyMagic(packet) && packet.size > LEGACY_MEDIA_OFFSET -> LEGACY_MEDIA_OFFSET
            hasSessionMagic(packet) && packet.size > SESSION_MEDIA_OFFSET &&
                (packet[6].toInt() and 0xFF) == 0x02 -> SESSION_MEDIA_OFFSET
            else -> return null
        }
        return VideoFragment(
            frameNumber = packet[16].toInt() and 0xFF,
            fragmentIndex = (if (packet[17].toInt() and 0x80 != 0) 0x100 else 0) or
                (packet[18].toInt() and 0xFF),
            payload = packet.copyOfRange(payloadOffset, total),
        )
    }

    fun videoPayload(packet: ByteArray): ByteArray? {
        val total = totalLength(packet)
        if (total < 4 || total > packet.size || total < MIN_MEDIA_PACKET_SIZE) return null
        if (hasLegacyMagic(packet) && packet.size > LEGACY_MEDIA_OFFSET) {
            return packet.copyOfRange(LEGACY_MEDIA_OFFSET, packet.size)
        }
        if (!hasSessionMagic(packet) || packet.size <= SESSION_MEDIA_OFFSET ||
            (packet[6].toInt() and 0xFF) != 0x02 || u32(packet, 16) == 0
        ) return null
        val annexStart = findAnnexStart(packet)
        val start = annexStart.takeIf { it >= SESSION_MEDIA_OFFSET } ?: SESSION_MEDIA_OFFSET
        return packet.copyOfRange(start, packet.size)
    }

    fun hasSessionMagic(packet: ByteArray): Boolean =
        packet.size >= 4 && packet[2] == 0x92.toByte() && packet[3] == 0xEC.toByte()

    private fun hasLegacyMagic(packet: ByteArray): Boolean =
        packet.size >= 4 && packet[2] == 0x5F.toByte() && packet[3] == 0xC1.toByte()

    private fun totalLength(packet: ByteArray): Int = if (packet.size < 2) 0 else
        (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0x7F) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun findAnnexStart(bytes: ByteArray): Int {
        val likely = intArrayOf(20, 24, 28, 32, 36, 40, 44, 48)
        likely.firstOrNull { validAvcStart(bytes, it) }?.let { return it }
        for (index in 0 until bytes.size - 3) if (validAvcStart(bytes, index)) return index
        return -1
    }

    private fun validAvcStart(bytes: ByteArray, offset: Int): Boolean {
        val header = when {
            offset + 3 < bytes.size && bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() &&
                bytes[offset + 2] == 1.toByte() -> offset + 3
            offset + 4 < bytes.size && bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() &&
                bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 1.toByte() -> offset + 4
            else -> return false
        }
        val value = bytes.getOrNull(header)?.toInt()?.and(0xFF) ?: return false
        if (value and 0x80 != 0) return false
        return when (value and 0x1F) {
            1, 5, 6, 7, 8, 9 -> true
            else -> false
        }
    }
}

/**
 * Reassembles DJI's packet fragments by their camera frame number, then emits one decoder access unit
 * per camera picture. This is the live equivalent of the LRF/OSV container's sample boundaries.
 */
internal class Osmo360FrameAssembler {
    private var frameBuffer = ByteArrayOutputStream(256 * 1024)
    private var currentFrameNumber = -1
    private var lastFragmentIndex = -1
    private var lastFragment: ByteArray? = null
    private var discardCurrentFrame = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastFeedAt = 0L
    @Volatile var droppedUnits: Int = 0
        private set

    @Synchronized
    fun feed(fragment: Osmo360PacketParser.VideoFragment): List<ByteArray> {
        val output = ArrayList<ByteArray>(1)
        if (currentFrameNumber >= 0 && fragment.frameNumber != currentFrameNumber) {
            finishFrame()?.let(output::add)
            startFrame(fragment.frameNumber)
        } else if (currentFrameNumber < 0) {
            startFrame(fragment.frameNumber)
        }

        if (!discardCurrentFrame) {
            // A duplicate UDP fragment must not be appended twice; doing so shifts every macroblock
            // after it and usually leaves only the next IDR decodable.
            val duplicate = fragment.fragmentIndex == lastFragmentIndex &&
                lastFragment?.contentEquals(fragment.payload) == true
            if (!duplicate) {
                val expected = (lastFragmentIndex + 1) and FRAGMENT_INDEX_MASK
                val sequenceBroken = lastFragmentIndex >= 0 && fragment.fragmentIndex != expected
                val frameTooLarge = frameBuffer.size() + fragment.payload.size > MAX_FRAME_SIZE
                if (sequenceBroken || frameTooLarge) {
                    discardFrame()
                } else {
                    frameBuffer.write(fragment.payload, 0, fragment.payload.size)
                    lastFragmentIndex = fragment.fragmentIndex
                    lastFragment = fragment.payload.copyOf()
                }
            }
        }
        lastFeedAt = monotonicMs()
        return output
    }

    @Synchronized
    fun flushIfStalled(now: Long = monotonicMs()): List<ByteArray> {
        if (currentFrameNumber < 0 || lastFeedAt == 0L || now - lastFeedAt < STALL_FLUSH_MS) {
            return emptyList()
        }
        return listOfNotNull(finishFrame()).also { currentFrameNumber = -1 }
    }

    @Synchronized
    fun reset() {
        frameBuffer.reset()
        currentFrameNumber = -1
        lastFragmentIndex = -1
        lastFragment = null
        discardCurrentFrame = false
        sps = null
        pps = null
        lastFeedAt = 0L
        droppedUnits = 0
    }

    private fun startFrame(number: Int) {
        frameBuffer.reset()
        currentFrameNumber = number
        lastFragmentIndex = -1
        lastFragment = null
        discardCurrentFrame = false
    }

    private fun discardFrame() {
        if (!discardCurrentFrame) droppedUnits++
        discardCurrentFrame = true
        frameBuffer.reset()
    }

    private fun finishFrame(): ByteArray? {
        if (discardCurrentFrame) {
            frameBuffer.reset()
            return null
        }
        val frame = frameBuffer.toByteArray()
        frameBuffer.reset()
        if (frame.isEmpty()) return null

        val nals = splitAnnexBNals(frame).filter { nal ->
            // 0xFF is DJI's private per-frame marker, not an AVC NAL. Drop it and any other invalid
            // prefix while retaining standard parameter, metadata and picture NALs.
            nalType(nal) in 1..12
        }
        if (nals.isEmpty()) return null
        nals.forEach { nal ->
            when (nalType(nal)) {
                7 -> sps = nal.copyOf()
                8 -> pps = nal.copyOf()
            }
        }
        val pictureNals = nals.filter { nalType(it) == 1 || nalType(it) == 5 }
        if (pictureNals.isEmpty()) return null

        val isIdr = pictureNals.any { nalType(it) == 5 }
        val containsSps = nals.any { nalType(it) == 7 }
        val containsPps = nals.any { nalType(it) == 8 }
        return ByteArrayOutputStream(frame.size + 128).apply {
            if (isIdr && !containsSps) sps?.let { write(it, 0, it.size) }
            if (isIdr && !containsPps) pps?.let { write(it, 0, it.size) }
            nals.forEach { write(it, 0, it.size) }
        }.toByteArray()
    }

    private fun splitAnnexBNals(bytes: ByteArray): List<ByteArray> {
        val starts = startCodes(bytes)
        if (starts.isEmpty()) return emptyList()
        return starts.indices.mapNotNull { index ->
            val from = starts[index]
            val to = starts.getOrNull(index + 1) ?: bytes.size
            if (to > from) bytes.copyOfRange(from, to) else null
        }
    }

    private fun startCodes(bytes: ByteArray): List<Int> {
        val result = ArrayList<Int>()
        var index = 0
        while (index + 3 <= bytes.size) {
            when {
                index + 4 <= bytes.size && bytes[index] == 0.toByte() &&
                    bytes[index + 1] == 0.toByte() && bytes[index + 2] == 0.toByte() &&
                    bytes[index + 3] == 1.toByte() -> {
                    result += index
                    index += 4
                }
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 1.toByte() -> {
                    result += index
                    index += 3
                }
                else -> index++
            }
        }
        return result
    }

    private fun nalType(nal: ByteArray): Int {
        val header = when {
            nal.size >= 5 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
            nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 1.toByte() -> 3
            else -> return -1
        }
        return nal[header].toInt() and 0x1F
    }

    private companion object {
        const val MAX_FRAME_SIZE = 8 * 1024 * 1024
        const val FRAGMENT_INDEX_MASK = 0x1FF
        const val STALL_FLUSH_MS = 250L
        fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
    }
}

/** Converts the Osmo 360's packet-spanning Annex-B byte stream into decoder access units. */
internal class Osmo360AnnexBAssembler {
    private var annexBuffer = ByteArrayOutputStream(256 * 1024)
    private var pendingUnit = ByteArrayOutputStream(256 * 1024)
    private var pendingHasVcl = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastFeedAt = 0L
    @Volatile var droppedUnits: Int = 0
        private set

    @Synchronized
    fun feed(fragment: ByteArray): List<ByteArray> {
        if (fragment.isEmpty()) return emptyList()
        if (annexBuffer.size() + fragment.size > MAX_BUFFER_SIZE) {
            annexBuffer.reset()
            pendingUnit.reset()
            pendingHasVcl = false
            droppedUnits++
        }
        annexBuffer.write(fragment, 0, fragment.size)
        lastFeedAt = monotonicMs()
        return extractComplete(flushTail = false)
    }

    @Synchronized
    fun flushIfStalled(now: Long = monotonicMs()): List<ByteArray> {
        if (annexBuffer.size() == 0 || lastFeedAt == 0L || now - lastFeedAt < STALL_FLUSH_MS) {
            return emptyList()
        }
        val output = extractComplete(flushTail = true).toMutableList()
        flushPending()?.let(output::add)
        return output
    }

    @Synchronized
    fun reset() {
        annexBuffer.reset()
        pendingUnit.reset()
        pendingHasVcl = false
        sps = null
        pps = null
        lastFeedAt = 0L
        droppedUnits = 0
    }

    private fun extractComplete(flushTail: Boolean): List<ByteArray> {
        val bytes = annexBuffer.toByteArray()
        val starts = startCodes(bytes)
        if (starts.isEmpty() || (!flushTail && starts.size < 2)) return emptyList()
        val completed = if (flushTail) starts.size else starts.size - 1
        val output = ArrayList<ByteArray>()
        for (index in 0 until completed) {
            val from = starts[index]
            val to = starts.getOrNull(index + 1) ?: bytes.size
            processNal(bytes.copyOfRange(from, to))?.let(output::add)
        }
        annexBuffer.reset()
        if (!flushTail) {
            val keepFrom = starts.last()
            annexBuffer.write(bytes, keepFrom, bytes.size - keepFrom)
        }
        return output
    }

    private fun processNal(nal: ByteArray): ByteArray? {
        val type = nalType(nal)
        when (type) {
            7 -> {
                sps = nal.copyOf()
                return null
            }
            8 -> {
                pps = nal.copyOf()
                return null
            }
            6 -> return null
            9 -> return flushPending()
        }
        if (type != 1 && type != 5) return null

        // A picture may contain several VCL slices. Treating every slice as a complete decoder
        // access unit corrupts all multi-slice P-frames, leaving only the periodic IDR visible.
        // first_mb_in_slice == 0 marks the first slice of the next picture (H.264 7.4.3).
        val firstMacroblock = firstMacroblockInSlice(nal)
        val startsNewPicture = pendingHasVcl && (firstMacroblock == 0 || firstMacroblock == null)
        val completed = if (startsNewPicture) flushPending() else null
        if (type == 5 && !pendingHasVcl) {
            sps?.let { pendingUnit.write(it, 0, it.size) }
            pps?.let { pendingUnit.write(it, 0, it.size) }
        }
        pendingUnit.write(nal, 0, nal.size)
        pendingHasVcl = true
        return completed
    }

    private fun flushPending(): ByteArray? {
        val output = pendingUnit.takeIf { pendingHasVcl && it.size() > 0 }?.toByteArray()
        pendingUnit.reset()
        pendingHasVcl = false
        return output
    }

    private fun startCodes(bytes: ByteArray): List<Int> {
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 <= bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte()) {
                starts += if (index > 0 && bytes[index - 1] == 0.toByte()) index - 1 else index
                index += 3
            } else {
                index++
            }
        }
        return starts.distinct()
    }

    private fun nalType(nal: ByteArray): Int {
        val offset = nalHeaderOffset(nal)
        if (offset < 0) return -1
        return nal[offset].toInt() and 0x1F
    }

    private fun firstMacroblockInSlice(nal: ByteArray): Int? {
        val header = nalHeaderOffset(nal)
        if (header < 0 || header + 1 >= nal.size) return null
        val reader = RbspBitReader(nal, header + 1)
        return reader.readUnsignedExpGolomb()
    }

    private fun nalHeaderOffset(nal: ByteArray): Int = when {
        nal.size >= 5 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() &&
            nal[3] == 1.toByte() -> 4
        nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte() -> 3
        else -> -1
    }

    private companion object {
        const val MAX_BUFFER_SIZE = 8 * 1024 * 1024
        const val STALL_FLUSH_MS = 250L

        fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
    }
}

/** Minimal RBSP reader used for the first unsigned Exp-Golomb value in an AVC slice header. */
private class RbspBitReader(data: ByteArray, start: Int) {
    private val bytes = ByteArrayOutputStream(data.size - start).apply {
        var zeros = 0
        for (index in start until data.size) {
            val value = data[index].toInt() and 0xFF
            if (zeros == 2 && value == 0x03) {
                zeros = 0
                continue
            }
            write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
    }.toByteArray()
    private var bitOffset = 0

    fun readUnsignedExpGolomb(): Int? {
        var leadingZeros = 0
        while (true) {
            when (readBit()) {
                1 -> break
                0 -> {
                    leadingZeros++
                    if (leadingZeros > 30) return null
                }
                else -> return null
            }
        }
        var suffix = 0
        repeat(leadingZeros) {
            val bit = readBit()
            if (bit < 0) return null
            suffix = (suffix shl 1) or bit
        }
        return ((1 shl leadingZeros) - 1) + suffix
    }

    private fun readBit(): Int {
        if (bitOffset >= bytes.size * 8) return -1
        val byte = bytes[bitOffset / 8].toInt() and 0xFF
        val value = (byte ushr (7 - bitOffset % 8)) and 1
        bitOffset++
        return value
    }
}
