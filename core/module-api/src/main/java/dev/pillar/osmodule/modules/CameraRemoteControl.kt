package dev.pillar.osmodule.modules

import android.content.Context

enum class CameraRemotePhase { DISCONNECTED, CONNECTING, CONNECTED }

/** Modes that DJI currently documents in the public Osmo R-SDK protocol. */
enum class CameraRemoteMode(val protocolValue: Int) {
    SLOW_MOTION(0x00),
    VIDEO(0x01),
    TIMELAPSE(0x02),
    PHOTO(0x05),
    HYPERLAPSE(0x0A),
    LIVE_STREAMING(0x1A),
    UVC_LIVE_STREAMING(0x23),
    SUPER_NIGHT(0x28),
    SUBJECT_TRACKING(0x34),
    PANORAMIC_VIDEO(0x38),
    PANORAMIC_HYPERLAPSE(0x3A),
    SELFIE(0x3C),
    PANORAMIC_PHOTO(0x3F),
    BOOST_VIDEO(0x41),
    VORTEX(0x43),
    PANORAMIC_SUPER_NIGHT(0x44),
    SINGLE_LENS_SUPER_NIGHT(0x4A),
    ;

    companion object {
        fun fromProtocolValue(value: Int): CameraRemoteMode? = entries.firstOrNull { it.protocolValue == value }
    }
}

data class CameraRemoteStatus(
    val rawMode: Int,
    val mode: CameraRemoteMode?,
    val modeLabel: String,
    val rawStatus: Int,
    val activeCapture: Boolean,
    val recording: Boolean,
    val preRecording: Boolean,
    val resolutionCode: Int,
    val fpsCode: Int,
    val eisCode: Int?,
    val recordTimeSeconds: Int,
    val photoRatioCode: Int?,
    val countdownSeconds: Int?,
    val timelapseIntervalTenths: Int?,
    val timelapseDurationSeconds: Int?,
    val remainingCapacityMb: Long?,
    val remainingPhotos: Long?,
    val remainingRecordSeconds: Long?,
    val customModeIndex: Int?,
    val powerMode: Int?,
    val nextModeCode: Int?,
    val thermalState: Int?,
    val photoCountdownMs: Long?,
    val loopRecordSeconds: Int?,
    val batteryPercent: Int?,
)

data class CameraRemoteVersion(
    val resultCode: Int,
    val productId: String,
    val sdkVersion: String?,
    val deviceName: String?,
    val firmwareVersion: String?,
)

enum class CameraRemoteCommand {
    QUERY_VERSION,
    CAPTURE,
    QUICK_SWITCH,
    SNAPSHOT,
    SWITCH_MODE,
    START_RECORDING,
    STOP_RECORDING,
    SLEEP,
    WAKE,
    RESTART,
}

enum class CameraRemoteCommandOutcome { SUCCEEDED, REJECTED, TIMED_OUT, TRANSPORT_FAILED }

data class CameraRemoteCommandResult(
    val command: CameraRemoteCommand,
    val outcome: CameraRemoteCommandOutcome,
    val sequence: Int? = null,
    val returnCode: Int? = null,
    val detail: String? = null,
)

data class CameraRemoteState(
    val phase: CameraRemotePhase = CameraRemotePhase.DISCONNECTED,
    val cameraAddress: String? = null,
    val cameraName: String? = null,
    val modeLabel: String? = null,
    val status: CameraRemoteStatus? = null,
    val version: CameraRemoteVersion? = null,
    val lastError: String? = null,
)

/** Stable management-plane API exposed only when the optional R-SDK module is installed. */
interface CameraRemoteControl {
    interface Listener {
        fun onStateChanged(state: CameraRemoteState) = Unit
        fun onCommandResult(result: CameraRemoteCommandResult) = Unit
    }

    val state: CameraRemoteState

    fun connectionPermissions(apiLevel: Int): Set<String>
    fun wakePermissions(apiLevel: Int): Set<String>
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    fun connect(context: Context, cameraMac: String, cameraName: String): Boolean
    fun disconnect()
    fun queryVersion(): Boolean
    fun capture(): Boolean
    fun quickSwitch(): Boolean
    fun snapshot(): Boolean
    fun switchMode(mode: CameraRemoteMode): Boolean
    fun setRecording(start: Boolean): Boolean
    fun sleep(): Boolean
    fun wake(context: Context, cameraMac: String): Boolean
    fun restart(): Boolean
}
