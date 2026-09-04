package dev.pillar.osmodule.pocket4p

import dev.pillar.osmodule.duml.PocketCameraStatus
import dev.pillar.osmodule.duml.PocketCaptureKind

/** Pocket 4 Pro zoom limits that change with the camera's active capture mode. */
internal object PocketZoomCapabilities {
    const val PHOTO_MAX_FACTOR = 9.0
    const val VIDEO_MAX_FACTOR = 12.0

    fun maxFactor(status: PocketCameraStatus?): Double = when {
        status?.shootingMode?.captureKind == PocketCaptureKind.PHOTO -> PHOTO_MAX_FACTOR
        status?.shootingMode?.captureKind == PocketCaptureKind.RECORDING -> VIDEO_MAX_FACTOR
        status?.isVideoLike == false -> PHOTO_MAX_FACTOR
        else -> VIDEO_MAX_FACTOR
    }
}
