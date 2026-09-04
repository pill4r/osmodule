package dev.pillar.osmodule.modules

import android.content.Context

data class CameraExclusiveState(
    val phase: Phase = Phase.STOPPED,
    val cameraName: String? = null,
) {
    enum class Phase { STOPPED, STARTING, ACTIVE }
    val locked: Boolean get() = phase != Phase.STOPPED
}

/**
 * A camera mode that needs exclusive ownership of the camera's BLE link.
 *
 * The media UI depends only on this contract. R-SDK and service implementation classes can remain
 * completely absent from osmodule Base.
 */
interface CameraExclusiveController {
    fun interface Listener {
        fun onStateChanged(state: CameraExclusiveState)
    }

    val state: CameraExclusiveState
    fun requiredPermissions(apiLevel: Int): Set<String>
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun start(context: Context, cameraMac: String, cameraName: String)
    fun stop(context: Context)
}
