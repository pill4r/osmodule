package dev.pillar.osmodule.plugin.pocket4p

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import dev.pillar.osmodule.modules.DeviceModels
import dev.pillar.osmodule.plugin.IOsmosisPlugin
import dev.pillar.osmodule.plugin.PluginContract
import dev.pillar.osmodule.plugin.PluginDescriptor
import dev.pillar.osmodule.pocket4p.Pocket4pPluginRuntime
import dev.pillar.osmodule.pocket4p.Pocket4pRemoteActivity

class Pocket4pPluginService : Service() {
    private val pluginDescriptor = PluginDescriptor(
        id = PluginContract.POCKET4P_PLUGIN_ID,
        // Base compares this Binder descriptor with immutable manifest metadata before launch.
        name = "Pocket 4P RC",
        version = 1,
        protocolMin = 1,
        protocolMax = 1,
        capabilities = setOf(
            PluginContract.CAPABILITY_POCKET4P_PANEL,
            PluginContract.CAPABILITY_CAMERA_SESSION_OWNER,
        ),
    )

    private val binder = object : IOsmosisPlugin.Stub() {
        override fun getProtocolVersion(): Int {
            enforceHostCaller()
            return PluginContract.PROTOCOL_VERSION
        }

        override fun getDescriptor(): Bundle {
            enforceHostCaller()
            return pluginDescriptor.toBundle()
        }

        override fun getRuntimeState(): Bundle {
            enforceHostCaller()
            val runtime = Pocket4pPluginRuntime.snapshot()
            return Bundle().apply {
                putBoolean(PluginContract.KEY_CAMERA_SESSION_ACTIVE, runtime.active)
                putString(PluginContract.KEY_CAMERA_SESSION_NAME, runtime.cameraName)
            }
        }

        override fun createPanelIntent(request: Bundle): PendingIntent {
            enforceHostCaller()
            val address = request.getString(PluginContract.KEY_CAMERA_ADDRESS).orEmpty().uppercase()
            val panel = if (address.isBlank()) {
                Intent(this@Pocket4pPluginService, Pocket4pPluginHomeActivity::class.java)
                    .putExtra(
                        PluginContract.KEY_REQUEST_PERMISSIONS,
                        request.getBoolean(PluginContract.KEY_REQUEST_PERMISSIONS, false),
                    )
            } else {
                require(MAC.matches(address)) { "Invalid camera address" }
                require(
                    request.getString(PluginContract.KEY_CAMERA_DEVICE_MODEL) ==
                        DeviceModels.OSMO_POCKET_4_PRO,
                ) { "Pocket 4P RC only supports Osmo Pocket 4 Pro" }
                Intent(this@Pocket4pPluginService, Pocket4pRemoteActivity::class.java)
                    .putExtra(Pocket4pRemoteActivity.EXTRA_CAMERA_ADDRESS, address)
                    .putExtra(
                        Pocket4pRemoteActivity.EXTRA_CAMERA_NAME,
                        request.getString(PluginContract.KEY_CAMERA_NAME).orEmpty().ifBlank { address },
                    )
                    .putExtra(
                        Pocket4pRemoteActivity.EXTRA_AUTO_CONNECT,
                        request.getBoolean(PluginContract.KEY_CAMERA_IN_RANGE, false),
                    )
                    .putExtra(
                        Pocket4pRemoteActivity.EXTRA_WIFI_SSID,
                        request.getString(PluginContract.KEY_CAMERA_WIFI_SSID),
                    )
                    .putExtra(
                        Pocket4pRemoteActivity.EXTRA_WIFI_PASSPHRASE,
                        request.getString(PluginContract.KEY_CAMERA_WIFI_PASSPHRASE),
                    )
                    .putExtra(
                        Pocket4pRemoteActivity.EXTRA_WIFI_WPA3,
                        request.getBoolean(PluginContract.KEY_CAMERA_WIFI_WPA3, false),
                    )
                    .putExtra(Pocket4pRemoteActivity.EXTRA_NETWORK, request.cameraNetwork())
                    .putExtra(
                        Pocket4pRemoteActivity.EXTRA_DATALINK_PORT,
                        request.getInt(PluginContract.KEY_CAMERA_DATALINK_PORT, 9004),
                    )
                    .putExtra(
                        Pocket4pRemoteActivity.EXTRA_DATALINK_TCP_POKE,
                        request.getBoolean(PluginContract.KEY_CAMERA_DATALINK_TCP_POKE, true),
                    )
            }
            val creatorOptions = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        if (Build.VERSION.SDK_INT >= 36) {
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        } else {
                            @Suppress("DEPRECATION")
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        },
                    )
                }.toBundle()
            } else null
            return PendingIntent.getActivity(
                this@Pocket4pPluginService,
                address.hashCode(),
                panel,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions,
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @Suppress("DEPRECATION")
    private fun Bundle.cameraNetwork(): Network? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelable(PluginContract.KEY_CAMERA_NETWORK, Network::class.java)
    } else {
        getParcelable(PluginContract.KEY_CAMERA_NETWORK)
    }

    private fun enforceHostCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (PluginContract.HOST_PACKAGE !in packages) {
            throw SecurityException("Only osmodule Base may call this plugin")
        }
    }

    private companion object {
        val MAC = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}
