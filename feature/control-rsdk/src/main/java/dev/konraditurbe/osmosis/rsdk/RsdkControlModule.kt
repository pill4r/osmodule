package dev.konraditurbe.osmosis.rsdk

import android.content.Context
import dev.konraditurbe.osmosis.modules.AppModule
import dev.konraditurbe.osmosis.modules.CameraExclusiveController
import dev.konraditurbe.osmosis.modules.CameraExclusiveState
import dev.konraditurbe.osmosis.modules.CameraRemoteControl
import dev.konraditurbe.osmosis.modules.CameraRemotePanelLauncher
import dev.konraditurbe.osmosis.modules.Capabilities
import dev.konraditurbe.osmosis.modules.ModuleDelivery
import dev.konraditurbe.osmosis.modules.ModuleDescriptor
import dev.konraditurbe.osmosis.modules.ModuleScope
import dev.konraditurbe.osmosis.modules.DeviceModels
import java.util.concurrent.ConcurrentHashMap

class RsdkControlModule : AppModule {
    override val descriptor = ModuleDescriptor(
        id = "rsdk-control",
        displayName = "Osmo 360 RC",
        delivery = ModuleDelivery.EXTERNAL_APK,
        capabilities = setOf(
            Capabilities.CAMERA_EXCLUSIVE_MODE,
            Capabilities.RSDK_REMOTE_CONTROL,
            Capabilities.RSDK_REMOTE_PANEL,
            Capabilities.RSDK_CAMERA_STATUS,
            Capabilities.RSDK_GPS_SYNC,
        ),
        supportedDeviceModels = setOf(DeviceModels.OSMO_360),
    )

    override fun install(scope: ModuleScope) {
        scope.bind(CameraExclusiveController::class.java, RsdkGpsController())
        scope.bind(CameraRemoteControl::class.java, RsdkRemoteController())
        scope.bind(CameraRemotePanelLauncher::class.java, RsdkRemotePanelLauncher())
    }
}

private class RsdkGpsController : CameraExclusiveController {
    private val listenerBridges = ConcurrentHashMap<CameraExclusiveController.Listener, GpsSyncState.Listener>()

    override val state: CameraExclusiveState
        get() = GpsSyncState.snapshot()

    override fun requiredPermissions(apiLevel: Int): Set<String> =
        RsdkPermissionPolicy.gpsPermissions(apiLevel)

    override fun addListener(listener: CameraExclusiveController.Listener) {
        val bridge = GpsSyncState.Listener { phase, cameraName ->
            listener.onStateChanged(phase.toPublicState(cameraName))
        }
        if (listenerBridges.putIfAbsent(listener, bridge) == null) GpsSyncState.addListener(bridge)
    }

    override fun removeListener(listener: CameraExclusiveController.Listener) {
        listenerBridges.remove(listener)?.let(GpsSyncState::removeListener)
    }

    override fun start(context: Context, cameraMac: String, cameraName: String) {
        GpsService.start(context, cameraMac, cameraName)
    }

    override fun stop(context: Context) {
        GpsService.stop(context)
    }
}

internal fun GpsSyncState.Phase.toPublicState(cameraName: String?) = CameraExclusiveState(
    phase = when (this) {
        GpsSyncState.Phase.STOPPED -> CameraExclusiveState.Phase.STOPPED
        GpsSyncState.Phase.STARTING -> CameraExclusiveState.Phase.STARTING
        GpsSyncState.Phase.ACTIVE -> CameraExclusiveState.Phase.ACTIVE
    },
    cameraName = cameraName,
)
