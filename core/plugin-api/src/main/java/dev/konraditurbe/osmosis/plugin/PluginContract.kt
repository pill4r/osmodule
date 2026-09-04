package dev.konraditurbe.osmosis.plugin

import android.os.Bundle

object PluginContract {
    const val PROTOCOL_VERSION = 1
    const val HOST_PACKAGE = "dev.konraditurbe.osmosis"
    const val BIND_ACTION = "dev.konraditurbe.osmosis.plugin.BIND"
    const val BIND_PERMISSION = "dev.konraditurbe.osmosis.permission.BIND_PLUGIN"
    const val PERMISSION_CENTER_ACTION = "dev.konraditurbe.osmosis.plugin.MANAGE_PERMISSIONS"

    /**
     * Some Android builds refuse a first cross-package service bind while the newly installed plugin
     * is stopped/not-launched. A synchronous, signature-protected ContentProvider call starts the
     * plugin without invoking OEM background-Activity policy, then Base can bind normally.
     */
    const val BOOTSTRAP_PROVIDER_CLASS =
        "dev.konraditurbe.osmosis.plugin.PluginBootstrapProvider"
    const val BOOTSTRAP_METHOD = "bootstrap"
    const val KEY_BOOTSTRAP_PROTOCOL = "bootstrap_protocol"

    fun bootstrapAuthority(packageName: String): String = "$packageName.bootstrap"

    const val METADATA_ID = "dev.konraditurbe.osmosis.plugin.ID"
    const val METADATA_NAME = "dev.konraditurbe.osmosis.plugin.NAME"
    const val METADATA_VERSION = "dev.konraditurbe.osmosis.plugin.VERSION"
    const val METADATA_PROTOCOL_MIN = "dev.konraditurbe.osmosis.plugin.PROTOCOL_MIN"
    const val METADATA_PROTOCOL_MAX = "dev.konraditurbe.osmosis.plugin.PROTOCOL_MAX"
    const val METADATA_CAPABILITIES = "dev.konraditurbe.osmosis.plugin.CAPABILITIES"

    const val KEY_ID = "id"
    const val KEY_NAME = "name"
    const val KEY_VERSION = "version"
    const val KEY_PROTOCOL_MIN = "protocol_min"
    const val KEY_PROTOCOL_MAX = "protocol_max"
    const val KEY_CAPABILITIES = "capabilities"
    const val KEY_CAMERA_ADDRESS = "camera_address"
    const val KEY_CAMERA_NAME = "camera_name"
    const val KEY_CAMERA_DEVICE_MODEL = "camera_device_model"
    const val KEY_CAMERA_IN_RANGE = "camera_in_range"
    const val KEY_CAMERA_WIFI_SSID = "camera_wifi_ssid"
    const val KEY_CAMERA_WIFI_PASSPHRASE = "camera_wifi_passphrase"
    const val KEY_CAMERA_WIFI_WPA3 = "camera_wifi_wpa3"
    const val KEY_CAMERA_DATALINK_PORT = "camera_datalink_port"
    const val KEY_CAMERA_DATALINK_TCP_POKE = "camera_datalink_tcp_poke"
    const val KEY_CAMERA_PANORAMA_CALIBRATION_STREAMS = "camera_panorama_calibration_streams"
    const val KEY_CAMERA_PANORAMA_CALIBRATION_DATA = "camera_panorama_calibration_data"
    const val KEY_CAMERA_SESSION_ACTIVE = "camera_session_active"
    const val KEY_CAMERA_SESSION_NAME = "camera_session_name"
    const val KEY_REQUEST_PERMISSIONS = "request_permissions"
    const val KEY_MEDIA_TITLE = "media_title"
    const val KEY_MEDIA_DEVICE_MODEL = "media_device_model"
    const val KEY_MEDIA_STREAM_CANDIDATES = "media_stream_candidates"
    const val KEY_MEDIA_NETWORK = "media_network"

    const val RSDK_PACKAGE = "dev.konraditurbe.osmosis.plugin.rsdk"
    const val RSDK_PLUGIN_ID = "rsdk-control"
    const val POCKET4P_PACKAGE = "dev.konraditurbe.osmosis.plugin.pocket4p"
    const val POCKET4P_PLUGIN_ID = "pocket4p-control"
    const val PANORAMA_PACKAGE = "dev.konraditurbe.osmosis.plugin.panorama360"
    const val PANORAMA_PLUGIN_ID = "panorama360"

    const val CAPABILITY_RSDK_PANEL = "camera.rsdk.remote-panel"
    const val CAPABILITY_RSDK_REMOTE_CONTROL = "camera.rsdk.remote-control"
    const val CAPABILITY_RSDK_STATUS = "camera.rsdk.status"
    const val CAPABILITY_RSDK_GPS = "camera.rsdk.gps-sync"
    const val CAPABILITY_POCKET4P_PANEL = "camera.pocket4p.remote-panel"
    const val CAPABILITY_CAMERA_SESSION_OWNER = "camera.session.owner"
    const val CAPABILITY_MEDIA_360_VIEW = "camera.media.360-view"
}

data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: Int,
    val protocolMin: Int,
    val protocolMax: Int,
    val capabilities: Set<String>,
) {
    fun supportsHostProtocol(): Boolean = PluginContract.PROTOCOL_VERSION in protocolMin..protocolMax

    fun toBundle(): Bundle = Bundle().apply {
        putString(PluginContract.KEY_ID, id)
        putString(PluginContract.KEY_NAME, name)
        putInt(PluginContract.KEY_VERSION, version)
        putInt(PluginContract.KEY_PROTOCOL_MIN, protocolMin)
        putInt(PluginContract.KEY_PROTOCOL_MAX, protocolMax)
        putStringArrayList(PluginContract.KEY_CAPABILITIES, ArrayList(capabilities.sorted()))
    }

    companion object {
        fun fromBundle(bundle: Bundle): PluginDescriptor? {
            val id = bundle.getString(PluginContract.KEY_ID).orEmpty()
            val name = bundle.getString(PluginContract.KEY_NAME).orEmpty()
            val version = bundle.getInt(PluginContract.KEY_VERSION, -1)
            val protocolMin = bundle.getInt(PluginContract.KEY_PROTOCOL_MIN, -1)
            val protocolMax = bundle.getInt(PluginContract.KEY_PROTOCOL_MAX, -1)
            val capabilities = bundle.getStringArrayList(PluginContract.KEY_CAPABILITIES).orEmpty().toSet()
            if (id.isBlank() || name.isBlank() || version < 1 || protocolMin < 1 || protocolMax < protocolMin) {
                return null
            }
            return PluginDescriptor(id, name, version, protocolMin, protocolMax, capabilities)
        }
    }
}
