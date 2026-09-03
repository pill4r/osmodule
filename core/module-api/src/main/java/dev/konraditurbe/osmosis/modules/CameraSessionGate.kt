package dev.konraditurbe.osmosis.modules

import android.content.Context

data class CameraSessionAvailability(
    val available: Boolean,
    val ownerName: String? = null,
    val cameraName: String? = null,
    val error: String? = null,
)

/** Asynchronous cross-process guard checked before Base starts a media transport. */
interface CameraSessionGate {
    fun check(context: Context, result: (CameraSessionAvailability) -> Unit): Boolean
}
