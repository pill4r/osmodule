package dev.pillar.osmodule.rsdk

import android.content.Context
import android.util.Log
import dev.pillar.osmodule.modules.CameraRemoteCommand
import dev.pillar.osmodule.modules.CameraRemoteCommandOutcome
import dev.pillar.osmodule.modules.CameraRemoteCommandResult
import dev.pillar.osmodule.modules.CameraRemoteControl
import dev.pillar.osmodule.modules.CameraRemoteMode
import dev.pillar.osmodule.modules.CameraRemotePhase
import dev.pillar.osmodule.modules.CameraRemoteState
import dev.pillar.osmodule.modules.CameraRemoteStatus
import dev.pillar.osmodule.modules.CameraRemoteVersion
import dev.pillar.osmodule.session.CameraSessionCoordinator
import java.util.concurrent.CopyOnWriteArraySet

internal class RsdkRemoteController : CameraRemoteControl, RsdkSessionHub.Listener {
    private val listeners = CopyOnWriteArraySet<CameraRemoteControl.Listener>()

    @Volatile
    private var currentState = CameraRemoteState()
    private var textModeLabel: String? = null

    override val state: CameraRemoteState get() = currentState

    override fun connectionPermissions(apiLevel: Int): Set<String> =
        RsdkPermissionPolicy.remoteControlPermissions(apiLevel)
            .filterNotTo(linkedSetOf()) { it == android.Manifest.permission.BLUETOOTH_ADVERTISE }

    override fun wakePermissions(apiLevel: Int): Set<String> =
        RsdkPermissionPolicy.remoteControlPermissions(apiLevel)
            .filterNotTo(linkedSetOf()) { it == android.Manifest.permission.BLUETOOTH_CONNECT }

    override fun addListener(listener: CameraRemoteControl.Listener) {
        listeners += listener
        listener.onStateChanged(currentState)
    }

    override fun removeListener(listener: CameraRemoteControl.Listener) {
        listeners -= listener
    }

    override fun connect(context: Context, cameraMac: String, cameraName: String): Boolean {
        updateState(CameraRemoteState(
            phase = CameraRemotePhase.CONNECTING,
            cameraAddress = cameraMac.uppercase(),
            cameraName = cameraName,
            lastError = null,
        ))
        val accepted = RsdkSessionHub.open(context, cameraMac, cameraName, REMOTE_CONSUMER, this)
        if (!accepted && currentState.phase == CameraRemotePhase.CONNECTING) {
            updateState(currentState.copy(
                phase = CameraRemotePhase.DISCONNECTED,
                lastError = "Unable to start the R-SDK connection",
            ))
        }
        return accepted
    }

    override fun disconnect() {
        RsdkWakeBroadcaster.cancel()
        RsdkSessionHub.close(REMOTE_CONSUMER)
        textModeLabel = null
        updateState(CameraRemoteState())
    }

    override fun queryVersion(): Boolean = accepted(CameraRemoteCommand.QUERY_VERSION) { RsdkSessionHub.queryVersion() }
    override fun capture(): Boolean = accepted(CameraRemoteCommand.CAPTURE) { RsdkSessionHub.capture() }
    override fun quickSwitch(): Boolean = accepted(CameraRemoteCommand.QUICK_SWITCH) { RsdkSessionHub.quickSwitch() }
    override fun snapshot(): Boolean = accepted(CameraRemoteCommand.SNAPSHOT) { RsdkSessionHub.snapshot() }

    override fun switchMode(mode: CameraRemoteMode): Boolean = accepted(CameraRemoteCommand.SWITCH_MODE) {
        val wireMode = RsdkProtocol.CameraMode.entries.firstOrNull { it.value == mode.protocolValue }
            ?: return@accepted false
        RsdkSessionHub.switchMode(wireMode)
    }

    override fun setRecording(start: Boolean): Boolean = accepted(
        if (start) CameraRemoteCommand.START_RECORDING else CameraRemoteCommand.STOP_RECORDING,
    ) { RsdkSessionHub.setRecording(start) }

    override fun sleep(): Boolean = accepted(CameraRemoteCommand.SLEEP) { RsdkSessionHub.sleep() }
    override fun restart(): Boolean = accepted(CameraRemoteCommand.RESTART) { RsdkSessionHub.restart() }

    override fun wake(context: Context, cameraMac: String): Boolean {
        val active = CameraSessionCoordinator.current()
        if (active != null) {
            dispatch(CameraRemoteCommandResult(
                CameraRemoteCommand.WAKE,
                CameraRemoteCommandOutcome.TRANSPORT_FAILED,
                detail = "A camera session is already active (${active.purpose})",
            ))
            return false
        }
        return RsdkWakeBroadcaster.start(context, cameraMac) { success, detail ->
            dispatch(CameraRemoteCommandResult(
                CameraRemoteCommand.WAKE,
                if (success) CameraRemoteCommandOutcome.SUCCEEDED else CameraRemoteCommandOutcome.TRANSPORT_FAILED,
                detail = detail,
            ))
        }
    }

    private inline fun accepted(command: CameraRemoteCommand, action: () -> Boolean): Boolean {
        val result = action()
        if (!result) dispatch(CameraRemoteCommandResult(
            command, CameraRemoteCommandOutcome.TRANSPORT_FAILED, detail = "R-SDK camera is not connected",
        ))
        return result
    }

    override fun onConnecting(cameraAddress: String, cameraName: String) {
        updateState(currentState.copy(
            phase = CameraRemotePhase.CONNECTING,
            cameraAddress = cameraAddress,
            cameraName = cameraName,
        ))
    }

    override fun onConnected() {
        updateState(currentState.copy(phase = CameraRemotePhase.CONNECTED, lastError = null))
    }

    override fun onStatus(status: RsdkProtocol.CameraStatus) {
        val publicStatus = CameraRemoteStatus(
            rawMode = status.mode,
            mode = CameraRemoteMode.fromProtocolValue(status.mode),
            modeLabel = textModeLabel ?: status.modeName,
            rawStatus = status.status,
            activeCapture = status.activeCapture,
            recording = status.recording,
            preRecording = status.preRecording,
            resolutionCode = status.resolution,
            fpsCode = status.fps,
            eisCode = status.eisMode,
            recordTimeSeconds = status.recordTimeS,
            photoRatioCode = status.photoRatio,
            countdownSeconds = status.realTimeCountdownS,
            timelapseIntervalTenths = status.timelapseIntervalTenths,
            timelapseDurationSeconds = status.timelapseDurationS,
            remainingCapacityMb = status.remainingCapacityMb,
            remainingPhotos = status.remainingPhotoCount,
            remainingRecordSeconds = status.remainingRecordTimeS,
            customModeIndex = status.userMode,
            powerMode = status.powerMode,
            nextModeCode = status.nextMode,
            thermalState = status.thermalState,
            photoCountdownMs = status.photoCountdownMs,
            loopRecordSeconds = status.loopRecordSeconds,
            batteryPercent = status.battery,
        )
        updateState(currentState.copy(modeLabel = publicStatus.modeLabel, status = publicStatus))
    }

    override fun onModeInfo(info: RsdkProtocol.ModeInfo) {
        textModeLabel = info.label
        updateState(currentState.copy(
            modeLabel = info.label,
            status = currentState.status?.copy(modeLabel = info.label),
        ))
    }

    override fun onVersion(info: RsdkProtocol.VersionInfo) {
        updateState(currentState.copy(version = CameraRemoteVersion(
            resultCode = info.resultCode,
            productId = info.productId,
            sdkVersion = info.sdkVersion,
            deviceName = info.deviceName,
            firmwareVersion = info.firmwareVersion,
        )))
    }

    override fun onCommandResult(result: RsdkCommandResult) {
        Log.i(
            LOG_TAG,
            "${result.command}: ${result.outcome}, seq=${result.sequence}, " +
                "code=${result.returnCode}, detail=${result.detail}",
        )
        dispatch(CameraRemoteCommandResult(
            command = result.command.toPublic(),
            outcome = result.outcome.toPublic(),
            sequence = result.sequence,
            returnCode = result.returnCode,
            detail = result.detail,
        ))
    }

    override fun onDisconnected() {
        textModeLabel = null
        updateState(currentState.copy(
            phase = CameraRemotePhase.DISCONNECTED,
            status = null,
            version = null,
            lastError = "Camera disconnected",
        ))
    }

    override fun onFailed(reason: String) {
        textModeLabel = null
        updateState(currentState.copy(
            phase = CameraRemotePhase.DISCONNECTED,
            status = null,
            version = null,
            lastError = reason,
        ))
    }

    override fun onLog(message: String) {
        Log.i(LOG_TAG, message)
    }

    private fun updateState(state: CameraRemoteState) {
        currentState = state
        listeners.forEach { it.onStateChanged(state) }
    }

    private fun dispatch(result: CameraRemoteCommandResult) {
        listeners.forEach { it.onCommandResult(result) }
    }

    private fun RsdkCommand.toPublic(): CameraRemoteCommand = when (this) {
        RsdkCommand.QUERY_VERSION -> CameraRemoteCommand.QUERY_VERSION
        RsdkCommand.CAPTURE -> CameraRemoteCommand.CAPTURE
        RsdkCommand.QUICK_SWITCH -> CameraRemoteCommand.QUICK_SWITCH
        RsdkCommand.SNAPSHOT -> CameraRemoteCommand.SNAPSHOT
        RsdkCommand.SWITCH_MODE -> CameraRemoteCommand.SWITCH_MODE
        RsdkCommand.START_RECORDING -> CameraRemoteCommand.START_RECORDING
        RsdkCommand.STOP_RECORDING -> CameraRemoteCommand.STOP_RECORDING
        RsdkCommand.SLEEP -> CameraRemoteCommand.SLEEP
        RsdkCommand.RESTART -> CameraRemoteCommand.RESTART
    }

    private fun RsdkCommandOutcome.toPublic(): CameraRemoteCommandOutcome = when (this) {
        RsdkCommandOutcome.SUCCEEDED -> CameraRemoteCommandOutcome.SUCCEEDED
        RsdkCommandOutcome.REJECTED -> CameraRemoteCommandOutcome.REJECTED
        RsdkCommandOutcome.TIMED_OUT -> CameraRemoteCommandOutcome.TIMED_OUT
        RsdkCommandOutcome.TRANSPORT_FAILED -> CameraRemoteCommandOutcome.TRANSPORT_FAILED
    }

    private companion object {
        const val LOG_TAG = "osmoduleRsdk"
        const val REMOTE_CONSUMER = "remote-control"
    }
}
