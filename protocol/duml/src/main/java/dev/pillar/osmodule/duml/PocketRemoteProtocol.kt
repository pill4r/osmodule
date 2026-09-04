package dev.pillar.osmodule.duml

/**
 * A typed DUML command that can be handed directly to the UDP datalink or encoded as a complete
 * frame for tests/BLE-sized transports.
 *
 * [cmdType] is the numeric DJI command type used by `DumlTransport.sendDuml` (`2` = request,
 * `0` = unacknowledged notify), not the already-shifted flags byte.
 */
data class PocketDumlCommand(
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
    val receiverType: Int,
    val receiverId: Int = 0,
    val cmdType: Int = REQUEST_CMD_TYPE,
) {
    init {
        require(cmdSet in 0..0xFF) { "cmdSet must fit in one byte" }
        require(cmdId in 0..0xFF) { "cmdId must fit in one byte" }
        require(receiverType in 0..0x1F) { "receiverType must fit in five bits" }
        require(receiverId in 0..0x07) { "receiverId must fit in three bits" }
        require(cmdType in 0..0x07) { "cmdType must fit in three bits" }
        require(payload.size <= MAX_PAYLOAD_SIZE) { "payload is too large for a DUML frame" }
    }

    /** `(receiverId << 5) | receiverType`, paired with the fixed App sender byte `0x02`. */
    val receiver: Int get() = (receiverId shl 5) or receiverType

    /** Existing [DjiMessage] packs sender in the low target byte and receiver in the high byte. */
    val target: Int get() = APP_SENDER or (receiver shl 8)

    /** Low byte of the three-byte DUML type field. */
    val flags: Int get() = cmdType shl 5

    /** Encode a complete CRC-protected DUML frame. UDP callers should normally use the typed fields. */
    fun encode(id: Int = DEFAULT_MESSAGE_ID): ByteArray {
        require(id in 0..0xFFFF) { "message id must fit in two bytes" }
        val type = flags or (cmdSet shl 8) or (cmdId shl 16)
        return DjiMessage(target, id, type, payload.copyOf()).encode()
    }

    companion object {
        const val APP_SENDER = 0x02
        const val REQUEST_CMD_TYPE = 2
        const val NOTIFY_CMD_TYPE = 0
        const val DEFAULT_MESSAGE_ID = 0x8000
        private const val MAX_PAYLOAD_SIZE = 0xFF - 13
    }
}

enum class PocketCaptureKind { PHOTO, RECORDING }

/**
 * Sparse Pocket 4 Pro shooting-mode values in the same order and wording as the camera carousel.
 *
 * These values are deliberately tabled. Unknown values must never be probed on the camera.
 */
enum class PocketShootingMode(
    val wireValue: Int,
    val captureKind: PocketCaptureKind,
) {
    PANORAMA(0x0C, PocketCaptureKind.PHOTO),
    PHOTO(0x17, PocketCaptureKind.PHOTO),
    VIDEO(0x01, PocketCaptureKind.RECORDING),
    LOW_LIGHT_VIDEO(0x28, PocketCaptureKind.RECORDING),
    SLOW_MOTION(0x00, PocketCaptureKind.RECORDING),
    STATIC_TIMELAPSE(0x02, PocketCaptureKind.RECORDING),
    ;

    companion object {
        fun fromWireValue(value: Int): PocketShootingMode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Pocket 4 / Pocket 4 Pro remote-control commands.
 *
 * Command routes and payloads are adapted from OpenPocketCine `Commands.swift`, Copyright 2026
 * Erik Sutton and OpenPocketCine contributors, licensed under Apache License 2.0:
 * https://github.com/erik-sutton95/OpenPocketCine
 *
 * This Kotlin adaptation exposes UDP routing metadata separately from full-frame encoding and
 * rejects unrecognised shooting modes and out-of-range gimbal values before they reach hardware.
 */
object PocketRemoteCommands {
    const val CAMERA_RECEIVER_TYPE = 0x01
    const val LIVE_VIEW_RECEIVER_TYPE = 0x08
    const val GIMBAL_INIT_RECEIVER_TYPE = 0x03
    const val GIMBAL_RECEIVER_TYPE = 0x04

    const val GIMBAL_CENTER = 1024
    const val GIMBAL_TRAVEL = 550
    const val GIMBAL_MIN = GIMBAL_CENTER - GIMBAL_TRAVEL
    const val GIMBAL_MAX = GIMBAL_CENTER + GIMBAL_TRAVEL

    private fun camera(cmdId: Int, payload: ByteArray) = PocketDumlCommand(
        cmdSet = 0x02,
        cmdId = cmdId,
        payload = payload,
        receiverType = CAMERA_RECEIVER_TYPE,
    )

    private fun gimbal(
        cmdId: Int,
        payload: ByteArray,
        cmdType: Int = PocketDumlCommand.REQUEST_CMD_TYPE,
    ) = PocketDumlCommand(
        cmdSet = 0x04,
        cmdId = cmdId,
        payload = payload,
        receiverType = GIMBAL_RECEIVER_TYPE,
        cmdType = cmdType,
    )

    /** `0x02/0x01 [01]`: trigger one photo in the current photo mode. */
    fun shootPhoto() = camera(0x01, byteArrayOf(0x01))

    /** `0x02/0x02 [01]`: explicitly start recording; this is not a toggle. */
    fun startRecording() = setRecording(true)

    /** `0x02/0x02 [00]`: explicitly stop recording; this is not a toggle. */
    fun stopRecording() = setRecording(false)

    fun setRecording(recording: Boolean) =
        camera(0x02, byteArrayOf(if (recording) 0x01 else 0x00))

    /** `0x02/0xE1 [mode]`; only the sparse, tabled Pocket 4 values can be encoded. */
    fun setShootingMode(mode: PocketShootingMode) =
        camera(0xE1, byteArrayOf(mode.wireValue.toByte()))

    /** Safe raw-value bridge for persistence/UI code. Unknown values are refused. */
    fun setShootingMode(rawValue: Int): PocketDumlCommand? =
        PocketShootingMode.fromWireValue(rawValue)?.let(::setShootingMode)

    /**
     * `0x02/0xB8 [0A 4E lens:u16le]`: Pocket 4 Pro's absolute 1x…12x zoom slider.
     * Continuous UI updates should be coalesced by the caller to roughly 20 Hz.
     */
    fun setZoom(factor: Double): PocketDumlCommand {
        val lens = PocketZoom.lensPosition(factor)
        return camera(
            0xB8,
            byteArrayOf(0x0A, 0x4E, lens.toByte(), (lens ushr 8).toByte()),
        )
    }

    /** `0x02/0x0C [01 01 00 00]`: leave media playback and return to capture mode. */
    fun exitPlayback() = camera(0x0C, byteArrayOf(0x01, 0x01, 0x00, 0x00))

    /**
     * `0x02/0x68 [08]`: Mimo's Pocket live-entry hint. On a fresh SoftAP session this is sent
     * immediately before the first `0x09/0xA8`; it is not used as a periodic keepalive.
     */
    fun prepareLiveView() = camera(0x68, byteArrayOf(0x08))

    /**
     * `0x09/0xA8`: enable the Pocket HEVC viewfinder stream. Send once after [prepareLiveView] on a
     * fresh session; repeatedly issuing it resets the encoder GOP and can leave the preview black.
     */
    fun enableLiveView() = PocketDumlCommand(
        cmdSet = 0x09,
        cmdId = 0xA8,
        payload = byteArrayOf(0x00, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        receiverType = LIVE_VIEW_RECEIVER_TYPE,
    )

    /** `0x03/0xDA 05 FF FF FF FF`: register/initialise the gimbal command path. */
    fun gimbalInit() = PocketDumlCommand(
        cmdSet = 0x03,
        cmdId = 0xDA,
        payload = byteArrayOf(0x05, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        receiverType = GIMBAL_INIT_RECEIVER_TYPE,
    )

    /** `0x04/0x50 01 04 05`: heartbeat/readiness probe for gimbal parameters 4 and 5. */
    fun gimbalReadiness() = gimbal(0x50, byteArrayOf(0x01, 0x04, 0x05))

    /**
     * `0x04/0x01`, cmdType 0: virtual joystick velocity. Active motion should be streamed and an
     * explicit centre command must be sent on release/shutdown.
     *
     * Payload: `[pitch:u16le][reserved:0000][yaw:u16le][00 80 22 00]`.
     */
    fun gimbalStick(pitch: Int = GIMBAL_CENTER, yaw: Int = GIMBAL_CENTER): PocketDumlCommand {
        require(pitch in GIMBAL_MIN..GIMBAL_MAX) {
            "pitch must be in the confirmed $GIMBAL_MIN..$GIMBAL_MAX range"
        }
        require(yaw in GIMBAL_MIN..GIMBAL_MAX) {
            "yaw must be in the confirmed $GIMBAL_MIN..$GIMBAL_MAX range"
        }
        return gimbal(
            cmdId = 0x01,
            payload = byteArrayOf(
                pitch.toByte(), (pitch ushr 8).toByte(),
                0x00, 0x00,
                yaw.toByte(), (yaw ushr 8).toByte(),
                0x00, 0x80.toByte(), 0x22, 0x00,
            ),
            cmdType = PocketDumlCommand.NOTIFY_CMD_TYPE,
        )
    }

    /** Neutral joystick frame for release, interruption, and safe shutdown paths. */
    fun gimbalNeutral() = gimbalStick()

    /** `0x04/0x4C FE 08`: recenter the gimbal. */
    fun recenter() = gimbal(0x4C, byteArrayOf(0xFE.toByte(), 0x08))

    /** `0x04/0x4C FE 09`: toggle front/selfie orientation. */
    fun flip() = gimbal(0x4C, byteArrayOf(0xFE.toByte(), 0x09))
}

/**
 * Decoded `0x04/0x05` telemetry. Angles are signed tenths of a degree.
 *
 * The first three axes are the Pocket 3 minimum layout documented by Kaze-for-DJI. On Pocket 4,
 * OpenPocketCine's later physical tests use `@4` for yaw and **negated `@20`** for operator tilt;
 * `@0`/`@2` must therefore not be treated as authoritative Pocket 4 pitch/roll. Both observations
 * stay explicit here instead of silently assigning one body's semantics to the other.
 */
data class PocketGimbalTelemetry(
    val pitchTenthDegrees: Int,
    val rollTenthDegrees: Int,
    val wrappedYawTenthDegrees: Int,
    /** Observed values exist, but the full mode enum is not established. */
    val rawModeOrStatus: Int?,
    /** Candidate flags are deliberately left uninterpreted; they are not reliable limit guards. */
    val rawCandidateFlags: Int?,
    /** Pocket 4 operator tilt, `-i16le(@20)`; null when the payload is shorter than 22 bytes. */
    val operatorTiltTenthDegrees: Int?,
) {
    val pitchDegrees: Double get() = pitchTenthDegrees / 10.0
    val rollDegrees: Double get() = rollTenthDegrees / 10.0
    val wrappedYawDegrees: Double get() = wrappedYawTenthDegrees / 10.0
    val operatorTiltDegrees: Double? get() = operatorTiltTenthDegrees?.div(10.0)
}

/** Fully decoded portion of the unsolicited `0x02/0x80` camera-state push. */
data class PocketCameraStatus(
    val rawFlags: Long,
    val isConnected: Boolean,
    val isRecording: Boolean,
    val isRecordingTransitionInProgress: Boolean,
    val isInPlayback: Boolean,
    val isVideoLike: Boolean,
    val storageTotalMiB: Long,
    val storageFreeMiB: Long,
    val remainingRecordSeconds: Long,
    val elapsedRecordSeconds: Int,
    val shootingModeRaw: Int,
    /** Null when a future/unknown raw value is observed; [shootingModeRaw] is still preserved. */
    val shootingMode: PocketShootingMode?,
)

/** Pocket 4 Pro hybrid optical/digital zoom mapping observed in `cam_fov` and `cam_lens_state`. */
object PocketZoom {
    const val MIN_FACTOR = 1.0
    const val MAX_FACTOR = 12.0
    const val LENS_1X = 217
    const val LENS_3X = 651
    const val LENS_6X = 1_302
    const val LENS_12X = 2_604
    private const val RAW_AT_1X = 12_287
    private const val RAW_AT_3X = 9_368
    private const val RAW_AT_12X = 2_341

    fun clamp(factor: Double, maxFactor: Double = MAX_FACTOR): Double =
        factor.coerceIn(MIN_FACTOR, maxFactor.coerceAtLeast(MIN_FACTOR))

    fun lensPosition(factor: Double): Int {
        val value = clamp(factor)
        return when {
            value <= 3.0 -> lerp(LENS_1X, LENS_3X, (value - 1.0) / 2.0)
            else -> lerp(LENS_3X, LENS_12X, (value - 3.0) / 9.0)
        }
    }

    fun factorFromFov(value: ByteArray): Double? {
        if (value.size < 4) return null
        val raw = value.u8(0) or
            (value.u8(1) shl 8) or
            (value.u8(2) shl 16) or
            (value.u8(3) shl 24)
        return when {
            raw == 0 -> null
            raw >= RAW_AT_1X -> MIN_FACTOR
            raw <= RAW_AT_12X -> MAX_FACTOR
            raw >= RAW_AT_3X -> {
                val progress = (RAW_AT_1X - raw).toDouble() / (RAW_AT_1X - RAW_AT_3X)
                clamp(MIN_FACTOR + progress * 2.0)
            }
            else -> {
                val progress = (RAW_AT_3X - raw).toDouble() / (RAW_AT_3X - RAW_AT_12X)
                clamp(3.0 + progress * 9.0)
            }
        }
    }

    fun factorFromLensState(value: ByteArray): Double? {
        if (value.size < 16) return null
        val lens = value.u8(14) or (value.u8(15) shl 8)
        if (lens !in 100..3_000) return null
        return when {
            lens <= LENS_1X -> MIN_FACTOR
            lens >= LENS_12X -> MAX_FACTOR
            lens <= LENS_3X -> {
                val progress = (lens - LENS_1X).toDouble() / (LENS_3X - LENS_1X)
                clamp(MIN_FACTOR + progress * 2.0)
            }
            else -> {
                val progress = (lens - LENS_3X).toDouble() / (LENS_12X - LENS_3X)
                clamp(3.0 + progress * 9.0)
            }
        }
    }

    private fun lerp(start: Int, end: Int, progress: Double): Int =
        kotlin.math.round(start + (end - start) * progress.coerceIn(0.0, 1.0)).toInt()

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
}

sealed interface PocketRemoteEvent {
    data class CameraStatus(val value: PocketCameraStatus) : PocketRemoteEvent
    data class GimbalTelemetry(val value: PocketGimbalTelemetry) : PocketRemoteEvent
    data class Zoom(val factor: Double) : PocketRemoteEvent
}

/** Strict decoder for the unsolicited event families used by the Pocket remote controller. */
object PocketRemoteDecoder {
    /** Unknown opcodes, malformed frames, and short payloads return null. */
    fun decode(message: DjiMessage): PocketRemoteEvent? =
        decode(message.cmdSet, message.cmdId, message.payload)

    /** Unknown opcodes and short payloads return null without manufacturing zero-valued state. */
    fun decode(cmdSet: Int, cmdId: Int, payload: ByteArray): PocketRemoteEvent? = when {
        cmdSet == 0x02 && cmdId == 0x80 ->
            decodeCameraStatus(payload)?.let(PocketRemoteEvent::CameraStatus)
        cmdSet == 0x04 && cmdId == 0x05 ->
            decodeGimbalTelemetry(payload)?.let(PocketRemoteEvent::GimbalTelemetry)
        cmdSet == 0x00 && cmdId == 0x99 -> decodeZoomSubscription(payload)
        else -> null
    }

    private fun decodeZoomSubscription(payload: ByteArray): PocketRemoteEvent.Zoom? {
        val item = parseSubscription(payload) ?: return null
        val factor = when (item.first) {
            "cam_fov" -> PocketZoom.factorFromFov(item.second)
            "cam_lens_state" -> PocketZoom.factorFromLensState(item.second)
            else -> null
        }
        return factor?.let(PocketRemoteEvent::Zoom)
    }

    /** Parses a camera `0x00/0x99` property push without manufacturing truncated values. */
    private fun parseSubscription(payload: ByteArray): Pair<String, ByteArray>? {
        if (payload.size < 24 || payload[0] != 0x02.toByte() || payload[1] != 0x06.toByte()) {
            return null
        }
        val nameLength = payload.u16le(13)
        if (nameLength !in 1..79 || 15 + nameLength + 8 > payload.size) return null
        val name = payload.decodeToString(15, 15 + nameLength)
        val valueLengthAt = 15 + nameLength + 6
        if (valueLengthAt + 2 > payload.size) return null
        val valueLength = payload.u16le(valueLengthAt)
        val valueAt = valueLengthAt + 2
        if (valueAt + valueLength > payload.size) return null
        return name to payload.copyOfRange(valueAt, valueAt + valueLength)
    }

    /** `0x04/0x05` needs the three signed 16-bit axes; optional bytes stay explicitly nullable. */
    fun decodeGimbalTelemetry(payload: ByteArray): PocketGimbalTelemetry? {
        if (payload.size < 6) return null
        return PocketGimbalTelemetry(
            pitchTenthDegrees = payload.s16le(0),
            rollTenthDegrees = payload.s16le(2),
            wrappedYawTenthDegrees = payload.s16le(4),
            rawModeOrStatus = payload.u8OrNull(6),
            rawCandidateFlags = payload.u8OrNull(10),
            operatorTiltTenthDegrees =
                if (payload.size >= 22) -payload.s16le(20) else null,
        )
    }

    /**
     * `0x02/0x80` requires 58 bytes before exposing the mode/timing block. This deliberately rejects
     * partial packets instead of silently substituting zero for fields that never arrived.
     */
    fun decodeCameraStatus(payload: ByteArray): PocketCameraStatus? {
        if (payload.size < 58) return null
        val flags = payload.u32le(0)
        val rawMode = payload.u8(57)
        return PocketCameraStatus(
            rawFlags = flags,
            isConnected = flags and 0x0000_0001L != 0L,
            isRecording = flags and 0x0000_0080L != 0L,
            isRecordingTransitionInProgress = flags and 0x0000_0040L != 0L,
            isInPlayback = flags and 0x4000_0000L != 0L,
            isVideoLike = payload.u8(4) == 0x01,
            storageTotalMiB = payload.u32le(5),
            storageFreeMiB = payload.u32le(9),
            // Capture-confirmed Pocket status schema: @17..18 is u16. Bytes @19..20 belong to
            // adjacent state and must not be folded into the remaining-time counter.
            remainingRecordSeconds = payload.u16le(17).toLong(),
            elapsedRecordSeconds = payload.u16le(29),
            shootingModeRaw = rawMode,
            shootingMode = PocketShootingMode.fromWireValue(rawMode),
        )
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u8OrNull(offset: Int): Int? =
        getOrNull(offset)?.toInt()?.and(0xFF)

    private fun ByteArray.u16le(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.s16le(offset: Int): Int {
        val value = u16le(offset)
        return if (value and 0x8000 != 0) value - 0x1_0000 else value
    }

    private fun ByteArray.u32le(offset: Int): Long =
        u8(offset).toLong() or
            (u8(offset + 1).toLong() shl 8) or
            (u8(offset + 2).toLong() shl 16) or
            (u8(offset + 3).toLong() shl 24)
}
