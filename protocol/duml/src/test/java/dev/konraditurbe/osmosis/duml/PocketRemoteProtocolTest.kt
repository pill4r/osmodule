package dev.konraditurbe.osmosis.duml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketRemoteProtocolTest {
    @Test
    fun `camera commands carry the Pocket camera route and request type`() {
        val commands = listOf(
            PocketRemoteCommands.shootPhoto(),
            PocketRemoteCommands.startRecording(),
            PocketRemoteCommands.stopRecording(),
            PocketRemoteCommands.setShootingMode(PocketShootingMode.PHOTO),
            PocketRemoteCommands.setShootingMode(PocketShootingMode.PANORAMA),
            PocketRemoteCommands.exitPlayback(),
        )

        commands.forEach { command ->
            assertEquals(0x02, command.cmdSet)
            assertEquals(0x01, command.receiverType)
            assertEquals(0, command.receiverId)
            assertEquals(2, command.cmdType)
            assertEquals(0x01, command.receiver)
            assertEquals(0x0102, command.target)
            assertEquals(0x40, command.flags)
        }
        assertEquals(0x01, commands[0].cmdId)
        assertArrayEquals(bytes(0x01), commands[0].payload)
        assertEquals(0x02, commands[1].cmdId)
        assertArrayEquals(bytes(0x01), commands[1].payload)
        assertArrayEquals(bytes(0x00), commands[2].payload)
        assertEquals(0xE1, commands[3].cmdId)
        assertArrayEquals(bytes(0x17), commands[3].payload)
        assertEquals(0xE1, commands[4].cmdId)
        assertArrayEquals(bytes(0x0C), commands[4].payload)
        assertEquals(0x0C, commands[5].cmdId)
        assertArrayEquals(bytes(0x01, 0x01, 0x00, 0x00), commands[5].payload)
    }

    @Test
    fun `gimbal commands carry their distinct receivers and command types`() {
        val init = PocketRemoteCommands.gimbalInit()
        assertEquals(0x03, init.cmdSet)
        assertEquals(0xDA, init.cmdId)
        assertEquals(0x03, init.receiverType)
        assertEquals(2, init.cmdType)
        assertArrayEquals(bytes(0x05, 0xFF, 0xFF, 0xFF, 0xFF), init.payload)

        val readiness = PocketRemoteCommands.gimbalReadiness()
        assertEquals(0x04, readiness.cmdSet)
        assertEquals(0x50, readiness.cmdId)
        assertEquals(0x04, readiness.receiverType)
        assertEquals(2, readiness.cmdType)
        assertArrayEquals(bytes(0x01, 0x04, 0x05), readiness.payload)

        val recenter = PocketRemoteCommands.recenter()
        assertEquals(0x04, recenter.cmdSet)
        assertEquals(0x4C, recenter.cmdId)
        assertArrayEquals(bytes(0xFE, 0x08), recenter.payload)
        assertArrayEquals(bytes(0xFE, 0x09), PocketRemoteCommands.flip().payload)

        val stick = PocketRemoteCommands.gimbalNeutral()
        assertEquals(0x04, stick.cmdSet)
        assertEquals(0x01, stick.cmdId)
        assertEquals(0x04, stick.receiverType)
        assertEquals(0, stick.cmdType)
        assertEquals(0x00, stick.flags)
    }

    @Test
    fun `live view enable uses the Pocket encoder route and captured payload`() {
        val prepare = PocketRemoteCommands.prepareLiveView()
        assertEquals(0x02, prepare.cmdSet)
        assertEquals(0x68, prepare.cmdId)
        assertEquals(0x01, prepare.receiverType)
        assertEquals(0, prepare.receiverId)
        assertEquals(2, prepare.cmdType)
        assertArrayEquals(bytes(0x08), prepare.payload)

        val command = PocketRemoteCommands.enableLiveView()
        assertEquals(0x09, command.cmdSet)
        assertEquals(0xA8, command.cmdId)
        assertEquals(0x08, command.receiverType)
        assertEquals(0, command.receiverId)
        assertEquals(2, command.cmdType)
        assertArrayEquals(bytes(0, 4, 2, 0, 0, 0, 0, 0, 0, 0), command.payload)
    }

    @Test
    fun `golden camera record frames match captured DUML bytes`() {
        // MEDIA_PROTOCOL.md sections 11/12, captured with message id 0x0402.
        assertEquals(
            "550e046602010204400202014e61",
            PocketRemoteCommands.startRecording().encode(id = 0x0402).hex(),
        )
        assertEquals(
            "550e04660201020440020200c770",
            PocketRemoteCommands.stopRecording().encode(id = 0x0402).hex(),
        )
    }

    @Test
    fun `golden playback exit frame matches OpenPocketCine`() {
        assertEquals(
            "551104920201020440020c0101000009da",
            PocketRemoteCommands.exitPlayback().encode(id = 0x0402).hex(),
        )
    }

    @Test
    fun `golden gimbal payloads match published Pocket captures`() {
        // Kaze-for-DJI protocol/test-vectors/gimbal/gimbal.json.
        assertEquals(
            "00040000000400802200",
            PocketRemoteCommands.gimbalNeutral().payload.hex(),
        )
        assertEquals(
            "4a050000b60200802200",
            PocketRemoteCommands.gimbalStick(pitch = 1354, yaw = 694).payload.hex(),
        )
        assertEquals(
            "b60200004a0500802200",
            PocketRemoteCommands.gimbalStick(pitch = 694, yaw = 1354).payload.hex(),
        )
        assertEquals("fe08", PocketRemoteCommands.recenter().payload.hex())
    }

    @Test
    fun `full frame encoding retains typed route metadata`() {
        val command = PocketRemoteCommands.gimbalReadiness()
        val decoded = DjiMessage.fromBytes(command.encode(id = 0xA123))
        assertEquals(command.target, decoded.target)
        assertEquals(0xA123, decoded.id)
        assertEquals(command.flags, decoded.flags)
        assertEquals(command.cmdSet, decoded.cmdSet)
        assertEquals(command.cmdId, decoded.cmdId)
        assertArrayEquals(command.payload, decoded.payload)
    }

    @Test
    fun `unknown shooting modes and unsafe gimbal values are rejected`() {
        assertNull(PocketRemoteCommands.setShootingMode(0x05)) // Nano photo, not Pocket 4 photo.
        assertArrayEquals(
            bytes(0x0C),
            PocketRemoteCommands.setShootingMode(0x0C)!!.payload,
        )
        assertNull(PocketRemoteCommands.setShootingMode(0xFF))
        assertThrows(IllegalArgumentException::class.java) {
            PocketRemoteCommands.gimbalStick(PocketRemoteCommands.GIMBAL_MIN - 1, 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PocketRemoteCommands.gimbalStick(1024, PocketRemoteCommands.GIMBAL_MAX + 1)
        }
    }

    @Test
    fun `Pocket zoom factor maps to the captured absolute lens slider`() {
        assertArrayEquals(bytes(0x0A, 0x4E, 0xD9, 0x00), PocketRemoteCommands.setZoom(1.0).payload)
        assertArrayEquals(bytes(0x0A, 0x4E, 0x8B, 0x02), PocketRemoteCommands.setZoom(3.0).payload)
        assertArrayEquals(bytes(0x0A, 0x4E, 0x16, 0x05), PocketRemoteCommands.setZoom(6.0).payload)
        assertArrayEquals(bytes(0x0A, 0x4E, 0x2C, 0x0A), PocketRemoteCommands.setZoom(12.0).payload)
        assertEquals(217, PocketZoom.lensPosition(-1.0))
        assertEquals(2_604, PocketZoom.lensPosition(99.0))
    }

    @Test
    fun `zoom is decoded from camera property subscriptions`() {
        val fov = PocketRemoteDecoder.decode(
            0x00,
            0x99,
            subscription("cam_fov", bytes(0x25, 0x09, 0x00, 0x00)),
        ) as PocketRemoteEvent.Zoom
        assertEquals(12.0, fov.factor, 0.0)

        val lensValue = ByteArray(16).apply { putU16le(this, 14, 651) }
        val lens = PocketRemoteDecoder.decode(
            0x00,
            0x99,
            subscription("cam_lens_state", lensValue),
        ) as PocketRemoteEvent.Zoom
        assertEquals(3.0, lens.factor, 0.0)
        assertNull(PocketRemoteDecoder.decode(0x00, 0x99, subscription("cam_iso", bytes(1))))
    }

    @Test
    fun `gimbal telemetry decodes signed axes and optional raw bytes`() {
        val event = PocketRemoteDecoder.decode(
            cmdSet = 0x04,
            cmdId = 0x05,
            payload = bytes(0x85, 0xFF, 0xC8, 0x00, 0x02, 0xF9, 0x40, 0, 0, 0, 0x45),
        ) as PocketRemoteEvent.GimbalTelemetry

        assertEquals(-123, event.value.pitchTenthDegrees)
        assertEquals(200, event.value.rollTenthDegrees)
        assertEquals(-1790, event.value.wrappedYawTenthDegrees)
        assertEquals(-12.3, event.value.pitchDegrees, 0.0)
        assertEquals(20.0, event.value.rollDegrees, 0.0)
        assertEquals(-179.0, event.value.wrappedYawDegrees, 0.0)
        assertEquals(0x40, event.value.rawModeOrStatus)
        assertEquals(0x45, event.value.rawCandidateFlags)
        assertNull(event.value.operatorTiltTenthDegrees)

        val minimum = PocketRemoteDecoder.decodeGimbalTelemetry(bytes(1, 0, 2, 0, 3, 0))!!
        assertNull(minimum.rawModeOrStatus)
        assertNull(minimum.rawCandidateFlags)
        assertNull(minimum.operatorTiltTenthDegrees)

        // OpenPocketCine's Pocket 4 tilt take: raw i16 @20 = +435, operator look-up = -43.5 deg.
        val pocket4 = ByteArray(22)
        putU16le(pocket4, 4, 1)
        putU16le(pocket4, 20, 435)
        val pocket4Decoded = PocketRemoteDecoder.decodeGimbalTelemetry(pocket4)!!
        assertEquals(1, pocket4Decoded.wrappedYawTenthDegrees)
        assertEquals(-435, pocket4Decoded.operatorTiltTenthDegrees)
        assertEquals(-43.5, pocket4Decoded.operatorTiltDegrees!!, 0.0)
    }

    @Test
    fun `camera status decodes record transition mode and known status fields`() {
        val payload = ByteArray(58)
        // flags: connected + transition + recording + playback
        putU32le(payload, 0, 0x4000_00C1L)
        payload[4] = 0x01
        putU32le(payload, 5, 131_072)
        putU32le(payload, 9, 65_536)
        putU16le(payload, 17, 1_050)
        // These neighbouring bytes are intentionally non-zero: @17 is a u16, not a u32.
        payload[19] = 0x34
        payload[20] = 0x12
        putU16le(payload, 29, 42)
        payload[57] = 0x17

        val decoded = PocketRemoteDecoder.decode(0x02, 0x80, payload)
            as PocketRemoteEvent.CameraStatus
        val status = decoded.value
        assertTrue(status.isConnected)
        assertTrue(status.isRecording)
        assertTrue(status.isRecordingTransitionInProgress)
        assertTrue(status.isInPlayback)
        assertTrue(status.isVideoLike)
        assertEquals(131_072L, status.storageTotalMiB)
        assertEquals(65_536L, status.storageFreeMiB)
        assertEquals(1_050L, status.remainingRecordSeconds)
        assertEquals(42, status.elapsedRecordSeconds)
        assertEquals(0x17, status.shootingModeRaw)
        assertEquals(PocketShootingMode.PHOTO, status.shootingMode)
    }

    @Test
    fun `unknown status mode is preserved raw without being mislabelled`() {
        val payload = ByteArray(58)
        payload[57] = 0x7E
        val status = PocketRemoteDecoder.decodeCameraStatus(payload)!!
        assertEquals(0x7E, status.shootingModeRaw)
        assertNull(status.shootingMode)
        assertFalse(status.isRecording)
    }

    @Test
    fun `decoder safely rejects unknown opcodes and short payloads`() {
        assertNull(PocketRemoteDecoder.decode(0x04, 0x06, ByteArray(64)))
        assertNull(PocketRemoteDecoder.decode(0x02, 0x80, ByteArray(57)))
        assertNull(PocketRemoteDecoder.decode(0x04, 0x05, ByteArray(5)))
        assertNull(PocketRemoteDecoder.decodeGimbalTelemetry(ByteArray(0)))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun putU16le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32le(target: ByteArray, offset: Int, value: Long) {
        repeat(4) { byte -> target[offset + byte] = (value ushr (byte * 8)).toByte() }
    }

    private fun subscription(name: String, value: ByteArray): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val valueLengthAt = 15 + nameBytes.size + 6
        return ByteArray(valueLengthAt + 2 + value.size).also { payload ->
            payload[0] = 0x02
            payload[1] = 0x06
            putU16le(payload, 13, nameBytes.size)
            nameBytes.copyInto(payload, 15)
            putU16le(payload, valueLengthAt, value.size)
            value.copyInto(payload, valueLengthAt + 2)
        }
    }
}
