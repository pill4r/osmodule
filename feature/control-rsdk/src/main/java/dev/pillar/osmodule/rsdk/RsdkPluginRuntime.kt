package dev.pillar.osmodule.rsdk

import dev.pillar.osmodule.session.CameraSessionCoordinator

data class RsdkPluginRuntimeState(val active: Boolean, val cameraName: String?)

/** Minimal process state exposed to the signed plugin Binder service. */
object RsdkPluginRuntime {
    fun snapshot(): RsdkPluginRuntimeState {
        val active = CameraSessionCoordinator.current()
        return RsdkPluginRuntimeState(active != null, GpsSyncState.snapshot().cameraName ?: active?.cameraAddress)
    }
}
