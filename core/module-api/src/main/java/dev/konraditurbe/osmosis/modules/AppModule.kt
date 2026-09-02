package dev.konraditurbe.osmosis.modules

/** Stable identifiers shared without creating feature-to-feature dependencies. */
object Capabilities {
    const val MODULE_MANAGEMENT = "osmosis.module.management"
    const val MEDIA_PAIR = "camera.media.pair"
    const val MEDIA_CONNECT = "camera.media.connect"
    const val MEDIA_BROWSE = "camera.media.browse"
    const val MEDIA_PREVIEW = "camera.media.preview"
    const val MEDIA_DOWNLOAD = "camera.media.download"
    const val MEDIA_360_VIEW = "camera.media.360-view"
    const val CAMERA_EXCLUSIVE_MODE = "camera.mode.exclusive"
    const val RSDK_REMOTE_CONTROL = "camera.rsdk.remote-control"
    const val RSDK_REMOTE_PANEL = "camera.rsdk.remote-panel"
    const val RSDK_CAMERA_STATUS = "camera.rsdk.status"
    const val RSDK_GPS_SYNC = "camera.rsdk.gps-sync"
}

/** Stable model keys used by module metadata without depending on the BLE transport module. */
object DeviceModels {
    const val OSMO_360 = "osmo360"
}

enum class ModuleDelivery {
    CORE,
    DEFAULT,
    OPTIONAL_BUNDLED,
    EXTERNAL_APK,
}

data class ModuleDescriptor(
    val id: String,
    val displayName: String,
    val version: Int = 1,
    val delivery: ModuleDelivery,
    val capabilities: Set<String>,
    /** Empty means the module is model-agnostic. */
    val supportedDeviceModels: Set<String> = emptySet(),
) {
    fun supports(deviceModel: String): Boolean =
        supportedDeviceModels.isEmpty() || deviceModel in supportedDeviceModels
}

/** Entry point declared by each feature in its manifest metadata. */
interface AppModule {
    val descriptor: ModuleDescriptor
    fun install(scope: ModuleScope) = Unit
}

interface ModuleScope {
    fun <T : Any> bind(type: Class<T>, service: T)
}

inline fun <reified T : Any> ModuleScope.bind(service: T) = bind(T::class.java, service)
