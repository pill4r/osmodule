package dev.pillar.osmodule.rsdk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DJI **R-SDK** wire protocol — a *different* framing from the media-path DUML (`0x55`/CRC8/CRC16).
 * Ported byte-for-byte from DJI's Osmo-GPS-Controller-Demo (`dji_protocol_parser.c`, `custom_crc16/32.c`),
 * verified against golden frames emitted by that C in `RsdkProtocolTest`.
 *
 * Frame (all little-endian):
 * ```
 * 0    SOF        0xAA
 * 1-2  Ver/Length (ver<<10) | (total_len & 0x3FF)
 * 3    CmdType    [4:0] resp-type, [5] frame-type (0 cmd / 1 resp)
 * 4    ENC        0 = none
 * 5-7  RES        0
 * 8-9  SEQ
 * 10-11 CRC16     over bytes[0..9]        (init 0x3AA3, poly 0xA001 reflected, no final xor)
 * 12   CmdSet
 * 13   CmdID
 * 14.. DATA payload
 * +4   CRC32      over bytes[0..end-4]    (init 0x3AA3, poly 0xEDB88320 reflected, no final xor)
 * ```
 * Runs over the same GATT service as the media path (fff0 / notify fff4 / write fff5).
 */
object RsdkProtocol {

    // Tables computed from the polynomials (matches custom_crc16.c / custom_crc32.c exactly).
    private val CRC16_TABLE = IntArray(256) { i ->
        var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }; c
    }
    private val CRC32_TABLE = IntArray(256) { i ->
        var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -0x12477ce0 /* 0xEDB88320 */ else c ushr 1 }; c
    }

    private fun crc16(d: ByteArray, len: Int): Int {
        var c = 0x3AA3
        for (i in 0 until len) c = CRC16_TABLE[(c xor d[i].toInt()) and 0xFF] xor (c ushr 8)
        return c and 0xFFFF
    }

    private fun crc32(d: ByteArray, len: Int): Int {
        var c = 0x3AA3
        for (i in 0 until len) c = CRC32_TABLE[(c xor d[i].toInt()) and 0xFF] xor (c ushr 8)
        return c
    }

    // CmdType values (enums_logic.h).
    const val CMD_NO_RESPONSE = 0x00
    const val CMD_RESPONSE_OR_NOT = 0x01
    const val CMD_WAIT_RESULT = 0x02
    const val ACK_NO_RESPONSE = 0x20
    const val ACK_RESPONSE_OR_NOT = 0x21
    const val ACK_WAIT_RESULT = 0x22

    // Public (CmdSet, CmdID) pairs documented by DJI's Osmo R-SDK.
    const val SET_GENERAL = 0x00
    const val ID_VERSION_QUERY = 0x00
    const val ID_KEY_REPORT = 0x11
    const val ID_DEVICE_RESTART = 0x16
    const val ID_GPS_PUSH = 0x17
    const val ID_CONNECTION = 0x19
    const val ID_POWER_MODE = 0x1A
    const val SET_CAMERA = 0x1D
    const val ID_STATUS_PUSH = 0x02
    const val ID_RECORD_CTRL = 0x03
    const val ID_MODE_SWITCH = 0x04
    const val ID_STATUS_SUB = 0x05
    const val ID_STATUS_PUSH_NEW = 0x06   // DJI's `new_camera_status_push` — mode as text

    enum class CameraMode(val value: Int) {
        SLOW_MOTION(0x00), VIDEO(0x01), TIMELAPSE(0x02), PHOTO(0x05), HYPERLAPSE(0x0A),
        LIVE_STREAMING(0x1A), UVC_LIVE_STREAMING(0x23), SUPER_NIGHT(0x28), SUBJECT_TRACKING(0x34),
        PANORAMIC_VIDEO(0x38), PANORAMIC_HYPERLAPSE(0x3A), SELFIE(0x3C), PANORAMIC_PHOTO(0x3F),
        BOOST_VIDEO(0x41), VORTEX(0x43), PANORAMIC_SUPER_NIGHT(0x44), SINGLE_LENS_SUPER_NIGHT(0x4A),
    }

    enum class KeyCode(val value: Int) { RECORD(0x01), QUICK_SWITCH(0x02), SNAPSHOT(0x03) }
    enum class KeyReportMode(val value: Int) { PRESS_STATE(0x00), EVENT(0x01) }
    enum class KeyEvent(val value: Int) {
        SHORT_PRESS(0x00), LONG_PRESS(0x01), DOUBLE_CLICK(0x02), TRIPLE_CLICK(0x03), QUADRUPLE_CLICK(0x04),
    }
    enum class PowerMode(val value: Int) { NORMAL(0x00), SLEEP(0x03) }

    /** Wrap a DATA payload (after CmdSet/CmdID) in a full R-SDK frame. */
    fun frame(cmdSet: Int, cmdId: Int, cmdType: Int, payload: ByteArray, seq: Int): ByteArray {
        val frameLen = 14 + payload.size + 4
        val f = ByteArray(frameLen)
        var o = 0
        f[o++] = 0xAA.toByte()
        val verLen = frameLen and 0x03FF // version 0
        f[o++] = (verLen and 0xFF).toByte(); f[o++] = ((verLen ushr 8) and 0xFF).toByte()
        f[o++] = cmdType.toByte()
        f[o++] = 0; f[o++] = 0; f[o++] = 0; f[o++] = 0 // ENC + RES[3]
        f[o++] = (seq and 0xFF).toByte(); f[o++] = ((seq ushr 8) and 0xFF).toByte()
        val c16 = crc16(f, o)
        f[o++] = (c16 and 0xFF).toByte(); f[o++] = ((c16 ushr 8) and 0xFF).toByte()
        f[o++] = cmdSet.toByte(); f[o++] = cmdId.toByte()
        System.arraycopy(payload, 0, f, o, payload.size); o += payload.size
        val c32 = crc32(f, o)
        f[o++] = (c32 and 0xFF).toByte(); f[o++] = ((c32 ushr 8) and 0xFF).toByte()
        f[o++] = ((c32 ushr 16) and 0xFF).toByte(); f[o] = ((c32 ushr 24) and 0xFF).toByte()
        return f
    }

    /** A parsed inbound frame. */
    data class Frame(val cmdSet: Int, val cmdId: Int, val cmdType: Int, val seq: Int, val payload: ByteArray) {
        val isResponse: Boolean get() = (cmdType and 0x20) != 0
    }

    /** Parse + validate a received frame; null if malformed or a CRC fails. */
    fun parse(f: ByteArray): Frame? {
        if (f.size < 16 || (f[0].toInt() and 0xFF) != 0xAA) return null
        val len = ((f[1].toInt() and 0xFF) or ((f[2].toInt() and 0xFF) shl 8)) and 0x03FF
        if (len != f.size) return null
        if (crc16(f, 10) != ((f[10].toInt() and 0xFF) or ((f[11].toInt() and 0xFF) shl 8))) return null
        val dataEnd = len - 4
        val c32 = crc32(f, dataEnd)
        val stored = (f[dataEnd].toInt() and 0xFF) or ((f[dataEnd + 1].toInt() and 0xFF) shl 8) or
            ((f[dataEnd + 2].toInt() and 0xFF) shl 16) or ((f[dataEnd + 3].toInt() and 0xFF) shl 24)
        if (c32 != stored) return null
        return Frame(
            cmdType = f[3].toInt() and 0xFF,
            seq = (f[8].toInt() and 0xFF) or ((f[9].toInt() and 0xFF) shl 8),
            cmdSet = f[12].toInt() and 0xFF,
            cmdId = f[13].toInt() and 0xFF,
            payload = f.copyOfRange(14, dataEnd),
        )
    }

    private fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    /** Version Query (0x00/0x00) has no DATA payload. */
    fun versionQuery(): ByteArray = ByteArray(0)

    /** Key Reporting (0x00/0x11), normally using EVENT + SHORT_PRESS for remote buttons. */
    fun keyReport(key: KeyCode, mode: KeyReportMode, value: Int): ByteArray {
        require(value in 0..0xFFFF) { "Key value must fit uint16" }
        return buf(4).put(key.value.toByte()).put(mode.value.toByte()).putShort(value.toShort()).array()
    }

    fun keyEvent(key: KeyCode, event: KeyEvent = KeyEvent.SHORT_PRESS): ByteArray =
        keyReport(key, KeyReportMode.EVENT, event.value)

    /** Device Restart (0x00/0x16). */
    fun deviceRestart(deviceId: Int): ByteArray = buf(8).putInt(deviceId).putInt(0).array()

    /** Connection Request (0x00/0x19) — `connection_request_command_frame` (33 B). */
    fun connectionRequest(deviceId: Int, mac: ByteArray, verifyMode: Int, verifyData: Int): ByteArray {
        val b = buf(33)
        b.putInt(deviceId)
        b.put(mac.size.toByte())
        b.put(mac); repeat(16 - mac.size) { b.put(0) } // mac_addr[16]
        b.putInt(0)          // fw_version
        b.put(0)             // conidx
        b.put(verifyMode.toByte())
        b.putShort(verifyData.toShort())
        b.putInt(0)          // reserved[4]
        return b.array()
    }

    /** Connection Response (0x00/0x19, ACK) — `connection_request_response_frame` (9 B). */
    fun connectionResponse(deviceId: Int, retCode: Int, cameraReserved: Int): ByteArray {
        val b = buf(9)
        b.putInt(deviceId); b.put(retCode.toByte())
        b.put(cameraReserved.toByte()); b.put(0); b.put(0); b.put(0) // reserved[4], [0]=cameraReserved
        return b.array()
    }

    /** Camera Status Subscription (0x1D/0x05) — `camera_status_subscription_command_frame` (6 B). */
    fun statusSubscription(pushMode: Int, pushFreq: Int): ByteArray {
        val b = buf(6)
        b.put(pushMode.toByte()); b.put(pushFreq.toByte()); b.putInt(0) // reserved[4]
        return b.array()
    }

    /** Camera Mode Switch (0x1D/0x04). */
    fun modeSwitch(deviceId: Int, mode: CameraMode): ByteArray =
        buf(9).putInt(deviceId).put(mode.value.toByte()).putInt(0).array()

    /** Recording control (0x1D/0x03) — 0 = start, 1 = stop. `record_control_command_frame_t` (9 B). */
    fun recordControl(deviceId: Int, start: Boolean): ByteArray {
        val b = buf(9)
        b.putInt(deviceId); b.put(if (start) 0 else 1); b.putInt(0)
        return b.array()
    }

    /** Camera Power Mode Settings (0x00/0x1A). */
    fun powerMode(mode: PowerMode): ByteArray = byteArrayOf(mode.value.toByte())

    /**
     * Manufacturer-specific payload for DJI's documented sleeping-camera wake advertisement.
     * Android's advertiser supplies `[length][0xff]['W']['K']`; this is the following `P + reversed MAC`.
     */
    fun wakeManufacturerPayload(cameraMac: ByteArray): ByteArray {
        require(cameraMac.size == 6) { "A camera MAC address must contain 6 bytes" }
        return byteArrayOf('P'.code.toByte()) + cameraMac.reversedArray()
    }

    /** GPS data push (0x00/0x17) — `gps_data_push_command_frame` (48 B). Units per DJI: coords ×1e7,
     *  height mm, velocity cm/s, accuracy mm / cm/s. */
    fun gpsPush(
        yearMonthDay: Int, hourMinuteSecond: Int, longitude1e7: Int, latitude1e7: Int, heightMm: Int,
        speedNorthCmS: Float, speedEastCmS: Float, speedDownCmS: Float,
        vertAccMm: Int, horizAccMm: Int, speedAccCmS: Int, satellites: Int,
    ): ByteArray {
        val b = buf(48)
        b.putInt(yearMonthDay); b.putInt(hourMinuteSecond)
        b.putInt(longitude1e7); b.putInt(latitude1e7); b.putInt(heightMm)
        b.putFloat(speedNorthCmS); b.putFloat(speedEastCmS); b.putFloat(speedDownCmS)
        b.putInt(vertAccMm); b.putInt(horizAccMm); b.putInt(speedAccCmS); b.putInt(satellites)
        return b.array()
    }

    /** Camera status decoded from DJI's packed 38-byte `camera_status_push_command_frame`. */
    data class CameraStatus(
        val mode: Int,
        val status: Int,
        val resolution: Int,
        val fps: Int,
        val eisMode: Int?,
        val recordTimeS: Int,
        val fovType: Int?,
        val photoRatio: Int?,
        val realTimeCountdownS: Int?,
        val timelapseIntervalTenths: Int?,
        val timelapseDurationS: Int?,
        val remainingCapacityMb: Long?,
        val remainingPhotoCount: Long?,
        val remainingRecordTimeS: Long?,
        val userMode: Int?,
        val powerMode: Int?,
        val nextMode: Int?,
        val thermalState: Int?,
        val photoCountdownMs: Long?,
        val loopRecordSeconds: Int?,
        val battery: Int?,
    ) {
        val activeCapture: Boolean get() = status == 0x03 || status == 0x05
        val preRecording: Boolean get() = status == 0x05
        val recording: Boolean get() = preRecording || (status == 0x03 && mode != CameraMode.PHOTO.value)
        val modeName: String get() = MODE_NAMES[mode] ?: "Mode 0x%02X".format(mode)
    }

    private fun u8(p: ByteArray, at: Int): Int? = p.getOrNull(at)?.toInt()?.and(0xFF)
    private fun u16(p: ByteArray, at: Int): Int? = if (at + 1 < p.size)
        (p[at].toInt() and 0xFF) or ((p[at + 1].toInt() and 0xFF) shl 8) else null
    private fun u32(p: ByteArray, at: Int): Long? = if (at + 3 < p.size)
        ((p[at].toLong() and 0xFF) or ((p[at + 1].toLong() and 0xFF) shl 8) or
            ((p[at + 2].toLong() and 0xFF) shl 16) or ((p[at + 3].toLong() and 0xFF) shl 24)) else null

    /** Older firmware may truncate the tail, so mandatory head fields decode and absent tail fields stay null. */
    fun parseCameraStatus(p: ByteArray): CameraStatus? {
        if (p.size < 7) return null
        return CameraStatus(
            mode = p[0].toInt() and 0xFF, status = p[1].toInt() and 0xFF,
            resolution = p[2].toInt() and 0xFF, fps = p[3].toInt() and 0xFF,
            eisMode = u8(p, 4), recordTimeS = u16(p, 5)!!, fovType = u8(p, 7), photoRatio = u8(p, 8),
            realTimeCountdownS = u16(p, 9), timelapseIntervalTenths = u16(p, 11),
            timelapseDurationS = u16(p, 13), remainingCapacityMb = u32(p, 15),
            remainingPhotoCount = u32(p, 19), remainingRecordTimeS = u32(p, 23), userMode = u8(p, 27),
            powerMode = u8(p, 28), nextMode = u8(p, 29), thermalState = u8(p, 30),
            photoCountdownMs = u32(p, 31), loopRecordSeconds = u16(p, 35), battery = u8(p, 37),
        )
    }

    data class VersionInfo(
        val resultCode: Int,
        val productId: String,
        val sdkVersion: String?,
        val deviceName: String?,
        val firmwareVersion: String?,
    )

    /** Parse Version Query response: uint16 result, fixed product id, then up to three NUL-separated strings. */
    fun parseVersionInfo(p: ByteArray): VersionInfo? {
        if (p.size < 18) return null
        fun ascii(from: Int, to: Int): String = String(p, from, to - from, Charsets.US_ASCII).trimEnd('\u0000').trim()
        val tail = p.copyOfRange(18, p.size)
        val parts = tail.toList().splitOnZero()
            .map { bytes -> bytes.toByteArray().toString(Charsets.US_ASCII).trim() }
            .filter { it.isNotEmpty() }
        return VersionInfo(
            resultCode = u16(p, 0)!!,
            productId = ascii(2, 18),
            sdkVersion = parts.getOrNull(0),
            deviceName = parts.getOrNull(1),
            firmwareVersion = parts.getOrNull(2),
        )
    }

    private fun List<Byte>.splitOnZero(): List<List<Byte>> {
        if (isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<Byte>>(mutableListOf())
        for (byte in this) {
            if (byte.toInt() == 0) {
                if (result.last().isNotEmpty()) result.add(mutableListOf())
            } else result.last() += byte
        }
        return result.filter { it.isNotEmpty() }
    }

    /** Return code location differs only for connection/restart and version responses. */
    fun responseCode(frame: Frame): Int? = when {
        !frame.isResponse -> null
        frame.cmdSet == SET_GENERAL && frame.cmdId == ID_VERSION_QUERY -> u16(frame.payload, 0)
        frame.cmdSet == SET_GENERAL && frame.cmdId in setOf(ID_CONNECTION, ID_DEVICE_RESTART) -> u8(frame.payload, 4)
        else -> u8(frame.payload, 0)
    }

    /**
     * The camera's mode as **text**, off a `0x1D/0x06` push — DJI's `new_camera_status_push`.
     *
     * A newer body reports its mode this way instead of (or as well as) the numeric `0x1D/0x02`
     * struct: a name ("VIDEO") and a parameter line ("4K30"), each a length-prefixed ASCII field in a
     * fixed 46-byte frame. Nothing in it says whether the camera is recording, so [CameraStatus] is
     * still the only source for that.
     */
    data class ModeInfo(val name: String, val param: String) {
        /** "VIDEO · 4K30", or just the name when the camera sends no parameter line. */
        val label: String get() = if (param.isEmpty()) name else "$name · $param"
    }

    /**
     * Parse `new_camera_status_push_command_frame` (`0x1D/0x06`), 46 B:
     * `[01][nameLen][name:21][02][paramLen][param:21]`.
     *
     * The two type bytes are fixed, so they are checked rather than skipped — a frame that doesn't
     * carry them is a different layout and reading strings out of it would produce plausible garbage.
     */
    fun parseNewCameraStatus(p: ByteArray): ModeInfo? {
        if (p.size < 46) return null
        if ((p[0].toInt() and 0xFF) != 0x01 || (p[23].toInt() and 0xFF) != 0x02) return null
        fun str(at: Int, len: Int): String {
            val n = len.coerceIn(0, 21)
            return String(p, at, n, Charsets.US_ASCII).trimEnd('\u0000').trim()
        }
        return ModeInfo(str(2, p[1].toInt() and 0xFF), str(25, p[24].toInt() and 0xFF))
    }

    private val MODE_NAMES = mapOf(
        0x00 to "Slow Motion", 0x01 to "Video", 0x02 to "Timelapse", 0x05 to "Photo",
        0x0A to "Hyperlapse", 0x1A to "Live", 0x23 to "UVC Live", 0x28 to "SuperNight",
        0x34 to "Tracking", 0x38 to "Pano Video", 0x3A to "Hyperlapse", 0x3C to "Selfie",
        0x3F to "Pano Photo", 0x41 to "Boost Video", 0x43 to "Vortex", 0x44 to "360 SuperNight",
        0x4A to "Single Lens SuperNight",
    )
}
