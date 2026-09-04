package dev.pillar.osmodule.modules

import android.content.Context
import android.net.Network

data class CameraRemoteTarget(
    val address: String,
    val name: String,
    val inRange: Boolean,
    val deviceModel: String,
    /** Camera SoftAP details already obtained by Base during the media connection. */
    val wifiSsid: String? = null,
    val wifiPassphrase: String? = null,
    val wifiWpa3: Boolean = false,
    /** Existing camera AP lease held by Base and borrowed by a same-signature remote plugin. */
    val network: Network? = null,
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
    /** Target-aware availability for launchers that route more than one camera-specific plugin. */
    fun isAvailable(context: Context, deviceModel: String): Boolean = isAvailable(context)
    fun open(context: Context, target: CameraRemoteTarget): Boolean

    /**
     * Opens the panel and reports whether the launch itself completed. Implementations with an
     * asynchronous bridge should invoke [onComplete] only for the still-current launch generation;
     * `true` means the destination UI was dispatched, not merely that bootstrap was accepted.
     */
    fun open(
        context: Context,
        target: CameraRemoteTarget,
        onComplete: (opened: Boolean) -> Unit,
    ): Boolean = open(context, target).also(onComplete)

    /** Invalidates delayed work so it cannot open a panel for an Activity or target that is stale. */
    fun cancelPending() = Unit
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
