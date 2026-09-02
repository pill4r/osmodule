package dev.konraditurbe.osmosis.modules

import android.content.Context

data class CameraRemoteTarget(
    val address: String,
    val name: String,
    val inRange: Boolean,
    val deviceModel: String,
    /** Camera SoftAP details already obtained by Base during the media connection. */
    val wifiSsid: String? = null,
    val wifiPassphrase: String? = null,
    val wifiWpa3: Boolean = false,
    val datalinkPort: Int = 9004,
    val datalinkTcpPoke: Boolean = true,
    /** Recent Osmo 360 LRF URLs whose djmd header carries this camera's factory lens geometry. */
    val panoramaCalibrationStreams: List<String> = emptyList(),
    /** Already parsed DJMD lens geometry, encoded by the shared panorama renderer for IPC. */
    val panoramaCalibrationData: FloatArray? = null,
)

/** Optional UI entry point. The implementation may live in another signed APK. */
interface CameraRemotePanelLauncher {
    fun isAvailable(context: Context): Boolean = true
    fun open(context: Context, target: CameraRemoteTarget): Boolean
}

enum class ModuleInstallationState {
    INSTALLED,
    NOT_INSTALLED,
    NEEDS_ATTENTION,
}

/** Read-only module information shown from an individual device card. */
data class DeviceModuleStatus(
    val id: String,
    val name: String,
    val description: String,
    val installationState: ModuleInstallationState,
)

/** Opens the Base-owned module manager and exposes its per-device read-only catalog. */
interface ModuleManagementLauncher {
    fun open(context: Context): Boolean
    fun modulesForDevice(context: Context, deviceModel: String): List<DeviceModuleStatus> = emptyList()
}
