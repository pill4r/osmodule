package dev.pillar.osmodule.plugins

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.pillar.osmodule.R
import dev.pillar.osmodule.modules.AppModule
import dev.pillar.osmodule.modules.CameraRemotePanelLauncher
import dev.pillar.osmodule.modules.CameraRemoteTarget
import dev.pillar.osmodule.modules.Capabilities
import dev.pillar.osmodule.modules.DeviceModuleStatus
import dev.pillar.osmodule.modules.ModuleDelivery
import dev.pillar.osmodule.modules.ModuleDescriptor
import dev.pillar.osmodule.modules.ModuleInstallationState
import dev.pillar.osmodule.modules.ModuleManagementLauncher
import dev.pillar.osmodule.modules.ModuleRegistry
import dev.pillar.osmodule.modules.ModuleScope
import dev.pillar.osmodule.modules.DeviceModels
import dev.pillar.osmodule.modules.PanoramaVideoRequest
import dev.pillar.osmodule.modules.PanoramaVideoViewerLauncher
import dev.pillar.osmodule.modules.PanoramaSourceKind
import dev.pillar.osmodule.plugin.PluginContract
import java.util.concurrent.atomic.AtomicLong

internal fun externalRemotePanelCapability(deviceModel: String): String? = when (deviceModel) {
    DeviceModels.OSMO_360 -> PluginContract.CAPABILITY_RSDK_PANEL
    DeviceModels.OSMO_POCKET_4_PRO -> PluginContract.CAPABILITY_POCKET4P_PANEL
    else -> null
}

class ExternalPluginBridgeModule : AppModule {
    override val descriptor = ModuleDescriptor(
        id = "external-plugin-bridge",
        displayName = "External plugin bridge",
        delivery = ModuleDelivery.CORE,
        capabilities = setOf(Capabilities.MODULE_MANAGEMENT),
    )

    override fun install(scope: ModuleScope) {
        scope.bind(ModuleManagementLauncher::class.java, BaseModuleManagementLauncher())
        scope.bind(CameraRemotePanelLauncher::class.java, ExternalCameraRemotePanelLauncher())
        scope.bind(PanoramaVideoViewerLauncher::class.java, ExternalPanoramaViewerLauncher())
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
        val external = when (deviceModel) {
            DeviceModels.OSMO_360 -> listOf(
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
            )
            DeviceModels.OSMO_POCKET_4_PRO -> listOf(
                externalStatus(
                    context,
                    PluginContract.POCKET4P_PACKAGE,
                    PluginContract.POCKET4P_PLUGIN_ID,
                    R.string.module_pocket4p_name,
                    R.string.module_pocket4p_summary,
                ),
            )
            else -> emptyList()
        }
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
        val packageInstalled = ExternalPluginRegistry.isPackageInstalled(packageName)
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
        if (
            request.deviceModel != DeviceModels.OSMO_360 ||
            request.streamCandidates.isEmpty() ||
            !PanoramaSourceKind.isSupported(request.sourceKind)
        ) return false
        val streams = request.streamCandidates.filter { stream ->
            stream.startsWith("http://") || stream.startsWith("https://")
        }
        if (streams.isEmpty()) return false
        val pluginRequest = Bundle().apply {
            putString(PluginContract.KEY_MEDIA_TITLE, request.title)
            putString(PluginContract.KEY_MEDIA_DEVICE_MODEL, request.deviceModel)
            putStringArrayList(PluginContract.KEY_MEDIA_STREAM_CANDIDATES, ArrayList(streams))
            putString(PluginContract.KEY_MEDIA_SOURCE_KIND, request.sourceKind)
            request.network?.let { putParcelable(PluginContract.KEY_MEDIA_NETWORK, it) }
        }
        return ExternalPluginRegistry.openPanel(
            context,
            PluginContract.CAPABILITY_MEDIA_360_VIEW,
            pluginRequest,
        ) { message -> Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show() }
    }
}

private class ExternalCameraRemotePanelLauncher : CameraRemotePanelLauncher {
    private val launchGeneration = AtomicLong()

    override fun isAvailable(context: Context): Boolean =
        ExternalPluginRegistry.hasCapability(PluginContract.CAPABILITY_RSDK_PANEL) ||
            ExternalPluginRegistry.hasCapability(PluginContract.CAPABILITY_POCKET4P_PANEL)

    override fun isAvailable(context: Context, deviceModel: String): Boolean =
        externalRemotePanelCapability(deviceModel)?.let(ExternalPluginRegistry::hasCapability) == true

    override fun open(context: Context, target: CameraRemoteTarget): Boolean =
        open(context, target) {}

    override fun open(
        context: Context,
        target: CameraRemoteTarget,
        onComplete: (opened: Boolean) -> Unit,
    ): Boolean {
        val generation = launchGeneration.incrementAndGet()
        ExternalPluginRegistry.cancelPendingPanelLaunch(CAMERA_REMOTE_LAUNCH_GROUP)
        if (!MAC.matches(target.address)) {
            onComplete(false)
            return false
        }
        val capability = externalRemotePanelCapability(target.deviceModel)
        if (capability == null) {
            onComplete(false)
            return false
        }
        val notificationContext = context.applicationContext
        val request = Bundle().apply {
            putString(PluginContract.KEY_CAMERA_ADDRESS, target.address.uppercase())
            putString(PluginContract.KEY_CAMERA_NAME, target.name)
            putString(PluginContract.KEY_CAMERA_DEVICE_MODEL, target.deviceModel)
            putBoolean(PluginContract.KEY_CAMERA_IN_RANGE, target.inRange)
            putString(PluginContract.KEY_CAMERA_WIFI_SSID, target.wifiSsid)
            putString(PluginContract.KEY_CAMERA_WIFI_PASSPHRASE, target.wifiPassphrase)
            putBoolean(PluginContract.KEY_CAMERA_WIFI_WPA3, target.wifiWpa3)
            target.network?.let { putParcelable(PluginContract.KEY_CAMERA_NETWORK, it) }
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
            context = context,
            capability = capability,
            request = request,
            launchGroup = CAMERA_REMOTE_LAUNCH_GROUP,
            onFailure = { message ->
                if (launchGeneration.get() == generation) {
                    Toast.makeText(notificationContext, message, Toast.LENGTH_LONG).show()
                }
            },
            onComplete = { opened ->
                if (launchGeneration.get() == generation) onComplete(opened)
            },
        )
    }

    override fun cancelPending() {
        launchGeneration.incrementAndGet()
        ExternalPluginRegistry.cancelPendingPanelLaunch(CAMERA_REMOTE_LAUNCH_GROUP)
    }

    private companion object {
        const val CAMERA_REMOTE_LAUNCH_GROUP = "camera-remote-panel"
        val MAC = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}
