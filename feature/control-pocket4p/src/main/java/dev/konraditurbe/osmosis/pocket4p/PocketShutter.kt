package dev.konraditurbe.osmosis.pocket4p

import dev.konraditurbe.osmosis.duml.PocketCameraStatus
import dev.konraditurbe.osmosis.duml.PocketCaptureKind

/** One mode-aware shutter replaces mutually ineffective Photo and Record buttons. */
internal object PocketShutter {
    enum class Action { PHOTO, START_RECORDING, STOP_RECORDING, WAIT }

    fun action(status: PocketCameraStatus?): Action = when {
        status == null || status.isRecordingTransitionInProgress -> Action.WAIT
        status.isRecording -> Action.STOP_RECORDING
        status.shootingMode?.captureKind == PocketCaptureKind.PHOTO -> Action.PHOTO
        status.shootingMode?.captureKind == PocketCaptureKind.RECORDING -> Action.START_RECORDING
        status.isVideoLike -> Action.START_RECORDING
        else -> Action.PHOTO
    }
}
