package dev.konraditurbe.osmosis.plugins

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.modules.AppModule
import dev.konraditurbe.osmosis.modules.CameraRemotePanelLauncher
import dev.konraditurbe.osmosis.modules.CameraRemoteTarget
import dev.konraditurbe.osmosis.modules.CameraSessionAvailability
import dev.konraditurbe.osmosis.modules.CameraSessionGate
import dev.konraditurbe.osmosis.modules.Capabilities
import dev.konraditurbe.osmosis.modules.DeviceModuleStatus
import dev.konraditurbe.osmosis.modules.ModuleDelivery
import dev.konraditurbe.osmosis.modules.ModuleDescriptor
import dev.konraditurbe.osmosis.modules.ModuleInstallationState
import dev.konraditurbe.osmosis.modules.ModuleManagementLauncher
import dev.konraditurbe.osmosis.modules.ModuleRegistry
import dev.konraditurbe.osmosis.modules.ModuleScope
import dev.konraditurbe.osmosis.modules.DeviceModels
import dev.konraditurbe.osmosis.modules.PanoramaVideoRequest
import dev.konraditurbe.osmosis.modules.PanoramaVideoViewerLauncher
import dev.konraditurbe.osmosis.plugin.PluginContract

class ExternalPluginBridgeModule : AppModule {
    override val descriptor = ModuleDescriptor(
        id = "external-plugin-bridge",
        displayName = "External plugin bridge",
        delivery = ModuleDelivery.CORE,
        capabilities = setOf(Capabilities.MODULE_MANAGEMENT),
    )

    override fun install(scope: ModuleScope) {
        scope.bind(ModuleManagementLauncher::class.java, BaseModuleManagementLauncher())
        scope.bind(CameraRemotePanelLauncher::class.java, ExternalRsdkPanelLauncher())
        scope.bind(PanoramaVideoViewerLauncher::class.java, ExternalPanoramaViewerLauncher())
        scope.bind(CameraSessionGate::class.java, ExternalCameraSessionGate())
    }
}

private class ExternalCameraSessionGate : CameraSessionGate {
    override fun check(context: Context, result: (CameraSessionAvailability) -> Unit): Boolean =
        ExternalPluginRegistry.queryActiveCameraSession(context) { active, error ->
            result(
                when {
                    error != null -> CameraSessionAvailability(false, error = error)
                    active != null -> CameraSessionAvailability(
                        available = false,
                        ownerName = active.pluginName,
                        cameraName = active.cameraName,
                    )
                    else -> CameraSessionAvailability(true)
                },
            )
        }
}

private class BaseModuleManagementLauncher : ModuleManagementLauncher {
    override fun open(context: Context): Boolean = runCatching {
        val intent = Intent(context, PluginManagerActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    override fun modulesForDevice(context: Context, deviceModel: String): List<DeviceModuleStatus> {
        val bundled = ModuleRegistry.catalog().modules
            .asSequence()
            .filter { it.id != "external-plugin-bridge" && it.supports(deviceModel) }
            .mapNotNull { module -> module.deviceStatus(context) }
            .toList()
        val external = if (deviceModel == DeviceModels.OSMO_360) listOf(
            externalStatus(
                context,
                PluginContract.PANORAMA_PACKAGE,
                PluginContract.PANORAMA_PLUGIN_ID,
                R.string.module_panorama_name,
                R.string.module_panorama_summary,
            ),
            externalStatus(
                context,
                PluginContract.RSDK_PACKAGE,
                PluginContract.RSDK_PLUGIN_ID,
                R.string.module_rsdk_name,
                R.string.module_rsdk_summary,
            ),
        ) else emptyList()
        return bundled + external
    }

    private fun externalStatus(
        context: Context,
        packageName: String,
        moduleId: String,
        name: Int,
        description: Int,
    ): DeviceModuleStatus {
        val record = ExternalPluginRegistry.packageRecord(packageName)
        val packageInstalled = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
        return DeviceModuleStatus(
            id = moduleId,
            name = context.getString(name),
            description = context.getString(description),
            installationState = when {
                record?.compatible == true -> ModuleInstallationState.INSTALLED
                record == null && !packageInstalled -> ModuleInstallationState.NOT_INSTALLED
                else -> ModuleInstallationState.NEEDS_ATTENTION
            },
        )
    }

    private fun ModuleDescriptor.deviceStatus(context: Context): DeviceModuleStatus? {
        val (name, description) = when (id) {
            "media" -> R.string.module_media_name to R.string.module_media_summary
            "panorama360" -> R.string.module_panorama_name to R.string.module_panorama_summary
            else -> return null
        }
        val installed = delivery == ModuleDelivery.CORE || delivery == ModuleDelivery.DEFAULT
        return DeviceModuleStatus(
            id = id,
            name = context.getString(name),
            description = context.getString(description),
            installationState = if (installed) {
                ModuleInstallationState.INSTALLED
            } else {
                ModuleInstallationState.NOT_INSTALLED
            },
        )
    }
}

private class ExternalPanoramaViewerLauncher : PanoramaVideoViewerLauncher {
    override fun isAvailable(context: Context): Boolean =
        ExternalPluginRegistry.hasCapability(PluginContract.CAPABILITY_MEDIA_360_VIEW)

    override fun open(context: Context, request: PanoramaVideoRequest): Boolean {
        if (request.deviceModel != DeviceModels.OSMO_360 || request.streamCandidates.isEmpty()) return false
        val streams = request.streamCandidates.filter { stream ->
            stream.startsWith("http://") || stream.startsWith("https://")
        }
        if (streams.isEmpty()) return false
        val pluginRequest = Bundle().apply {
            putString(PluginContract.KEY_MEDIA_TITLE, request.title)
            putString(PluginContract.KEY_MEDIA_DEVICE_MODEL, request.deviceModel)
            putStringArrayList(PluginContract.KEY_MEDIA_STREAM_CANDIDATES, ArrayList(streams))
            request.network?.let { putParcelable(PluginContract.KEY_MEDIA_NETWORK, it) }
        }
        return ExternalPluginRegistry.openPanel(
            context,
            PluginContract.CAPABILITY_MEDIA_360_VIEW,
            pluginRequest,
        ) { message -> Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show() }
    }
}

private class ExternalRsdkPanelLauncher : CameraRemotePanelLauncher {
    override fun isAvailable(context: Context): Boolean =
        ExternalPluginRegistry.hasCapability(PluginContract.CAPABILITY_RSDK_PANEL)

    override fun open(context: Context, target: CameraRemoteTarget): Boolean {
        if (!MAC.matches(target.address)) return false
        if (target.deviceModel != DeviceModels.OSMO_360) return false
        val request = Bundle().apply {
            putString(PluginContract.KEY_CAMERA_ADDRESS, target.address.uppercase())
            putString(PluginContract.KEY_CAMERA_NAME, target.name)
            putBoolean(PluginContract.KEY_CAMERA_IN_RANGE, target.inRange)
            putString(PluginContract.KEY_CAMERA_WIFI_SSID, target.wifiSsid)
            putString(PluginContract.KEY_CAMERA_WIFI_PASSPHRASE, target.wifiPassphrase)
            putBoolean(PluginContract.KEY_CAMERA_WIFI_WPA3, target.wifiWpa3)
            putInt(PluginContract.KEY_CAMERA_DATALINK_PORT, target.datalinkPort)
            putBoolean(PluginContract.KEY_CAMERA_DATALINK_TCP_POKE, target.datalinkTcpPoke)
            putStringArrayList(
                PluginContract.KEY_CAMERA_PANORAMA_CALIBRATION_STREAMS,
                ArrayList(target.panoramaCalibrationStreams),
            )
            putFloatArray(
                PluginContract.KEY_CAMERA_PANORAMA_CALIBRATION_DATA,
                target.panoramaCalibrationData,
            )
        }
        return ExternalPluginRegistry.openPanel(
            context,
            PluginContract.CAPABILITY_RSDK_PANEL,
            request,
        ) { message -> Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        val MAC = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}
