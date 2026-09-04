package dev.pillar.osmodule.pocket4p

import java.io.ByteArrayOutputStream

/**
 * Reassembles Pocket live-view `pktType 0x02` datagrams into Annex-B HEVC access units.
 *
 * Adapted from OpenPocketCine `HevcDepacketizer.swift` and `Hevc.swift`, Copyright 2026 Erik
 * Sutton and OpenPocketCine contributors, licensed under Apache License 2.0:
 * https://github.com/erik-sutton95/OpenPocketCine
 *
 * This Kotlin adaptation accepts complete DJI datagrams, rejects incomplete access units, and
 * removes the private DJI NAL type 63 marker before returning an access unit.
 */
internal class PocketHevcDepacketizer {
    private var currentFrame: Int? = null
    private var buffer = ByteArrayOutputStream(INITIAL_ACCESS_UNIT_CAPACITY)
    private var lastPosition: Int? = null
    private var corrupt = false

    /** Access units discarded because a fragment was missing, reordered, or exceeded the cap. */
    var droppedIncomplete: Int = 0
        private set

    /**
     * Feeds one complete DJI UDP datagram.
     *
     * A completed access unit is emitted only when the first fragment of the next frame arrives.
     * Non-video and truncated packets are ignored without disturbing the in-progress frame.
     */
    @Synchronized
    fun feed(packet: ByteArray): ByteArray? {
        if (packet.size <= VIDEO_DATA_OFFSET || packet.u8(PACKET_TYPE_OFFSET) != VIDEO_PACKET_TYPE) {
            return null
        }

        val frameNumber = packet.u8(FRAME_NUMBER_OFFSET)
        val position = packet.u8(FRAGMENT_PAIR_OFFSET) * 2 +
            (packet.u8(FRAGMENT_HALF_OFFSET) ushr 7)

        var completed: ByteArray? = null
        val activeFrame = currentFrame
        if (activeFrame != null && activeFrame != frameNumber) {
            completed = finishCurrentFrame()
            clearFrameBuffer()
        }
        currentFrame = frameNumber

        val previousPosition = lastPosition
        if (previousPosition != null) {
            when {
                position == previousPosition -> return completed // Duplicate UDP fragment.
                position == 0 && previousPosition > 0 -> {
                    // The encoder may reuse a frame number after a GOP restart. Close the old AU
                    // only when the new fragment explicitly restarts at zero. An arbitrary lower
                    // position is packet reordering and must not make a truncated AU look complete.
                    completed = finishCurrentFrame()
                    clearFrameBuffer()
                }
                position < previousPosition -> corrupt = true
                position != previousPosition + 1 -> corrupt = true
            }
        }

        lastPosition = position
        val fragmentSize = packet.size - VIDEO_DATA_OFFSET
        if (buffer.size() + fragmentSize > MAX_ACCESS_UNIT_SIZE) {
            corrupt = true
        } else if (!corrupt || buffer.size() < MAX_ACCESS_UNIT_SIZE) {
            buffer.write(packet, VIDEO_DATA_OFFSET, fragmentSize)
        }
        return completed
    }

    @Synchronized
    fun reset() {
        currentFrame = null
        clearFrameBuffer()
        droppedIncomplete = 0
    }

    private fun finishCurrentFrame(): ByteArray? {
        if (buffer.size() == 0) return null
        if (corrupt) {
            droppedIncomplete++
            return null
        }
        return stripDjiMarker(buffer.toByteArray()).takeIf { it.isNotEmpty() }
    }

    private fun clearFrameBuffer() {
        buffer.reset()
        corrupt = false
        lastPosition = null
    }

    private fun stripDjiMarker(annexB: ByteArray): ByteArray {
        var index = 0
        while (index + 4 <= annexB.size) {
            if (annexB[index] == 0.toByte() &&
                annexB[index + 1] == 0.toByte() &&
                annexB[index + 2] == 1.toByte()
            ) {
                val nalType = (annexB[index + 3].toInt() ushr 1) and 0x3F
                if (nalType != DJI_MARKER_NAL_TYPE) return annexB.copyOfRange(index, annexB.size)
                index += 3
            } else {
                index++
            }
        }
        return annexB
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private companion object {
        const val PACKET_TYPE_OFFSET = 6
        const val FRAME_NUMBER_OFFSET = 16
        const val FRAGMENT_HALF_OFFSET = 17
        const val FRAGMENT_PAIR_OFFSET = 18
        const val VIDEO_DATA_OFFSET = 20
        const val VIDEO_PACKET_TYPE = 0x02
        const val DJI_MARKER_NAL_TYPE = 63
        const val INITIAL_ACCESS_UNIT_CAPACITY = 512 * 1024
        const val MAX_ACCESS_UNIT_SIZE = 8 * 1024 * 1024
    }
}
