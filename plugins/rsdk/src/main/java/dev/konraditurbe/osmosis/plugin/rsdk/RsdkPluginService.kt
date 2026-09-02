package dev.konraditurbe.osmosis.plugin.rsdk

import android.app.PendingIntent
import android.app.Service
import android.app.ActivityOptions
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import dev.konraditurbe.osmosis.plugin.IOsmosisPlugin
import dev.konraditurbe.osmosis.plugin.PluginContract
import dev.konraditurbe.osmosis.plugin.PluginDescriptor
import dev.konraditurbe.osmosis.rsdk.RsdkRemoteActivity
import dev.konraditurbe.osmosis.rsdk.RsdkPluginRuntime

class RsdkPluginService : Service() {
    private val pluginDescriptor = PluginDescriptor(
        id = PluginContract.RSDK_PLUGIN_ID,
        // Must exactly match the service metadata in AndroidManifest.xml. Base compares both views
        // before accepting a panel PendingIntent, so drift here intentionally blocks the plugin.
        name = "osmodule Remote Control",
        version = 5,
        protocolMin = 1,
        protocolMax = 1,
        capabilities = setOf(
            PluginContract.CAPABILITY_RSDK_PANEL,
            "camera.rsdk.remote-control",
            "camera.rsdk.status",
            PluginContract.CAPABILITY_RSDK_GPS,
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
            val runtime = RsdkPluginRuntime.snapshot()
            return Bundle().apply {
                putBoolean(PluginContract.KEY_CAMERA_SESSION_ACTIVE, runtime.active)
                putString(PluginContract.KEY_CAMERA_SESSION_NAME, runtime.cameraName)
            }
        }

        override fun createPanelIntent(request: Bundle): PendingIntent {
            enforceHostCaller()
            val address = request.getString(PluginContract.KEY_CAMERA_ADDRESS).orEmpty().uppercase()
            val cameraIntent = if (MAC.matches(address)) {
                Intent(this@RsdkPluginService, RsdkRemoteActivity::class.java)
                    .putExtra(RsdkRemoteActivity.EXTRA_CAMERA_ADDRESS, address)
                    .putExtra(
                        RsdkRemoteActivity.EXTRA_CAMERA_NAME,
                        request.getString(PluginContract.KEY_CAMERA_NAME).orEmpty().ifBlank { address },
                    )
                    .putExtra(
                        RsdkRemoteActivity.EXTRA_AUTO_CONNECT,
                        request.getBoolean(PluginContract.KEY_CAMERA_IN_RANGE, false),
                    )
                    .putExtra(
                        RsdkRemoteActivity.EXTRA_WIFI_SSID,
                        request.getString(PluginContract.KEY_CAMERA_WIFI_SSID),
                    )
                    .putExtra(
                        RsdkRemoteActivity.EXTRA_WIFI_PASSPHRASE,
                        request.getString(PluginContract.KEY_CAMERA_WIFI_PASSPHRASE),
                    )
                    .putExtra(
                        RsdkRemoteActivity.EXTRA_WIFI_WPA3,
                        request.getBoolean(PluginContract.KEY_CAMERA_WIFI_WPA3, false),
                    )
                    .putExtra(
                        RsdkRemoteActivity.EXTRA_DATALINK_PORT,
                        request.getInt(PluginContract.KEY_CAMERA_DATALINK_PORT, 9004),
                    )
                    .putExtra(
                        RsdkRemoteActivity.EXTRA_DATALINK_TCP_POKE,
                        request.getBoolean(PluginContract.KEY_CAMERA_DATALINK_TCP_POKE, true),
                    )
                    .putStringArrayListExtra(
                        RsdkRemoteActivity.EXTRA_PANORAMA_CALIBRATION_STREAMS,
                        request.getStringArrayList(
                            PluginContract.KEY_CAMERA_PANORAMA_CALIBRATION_STREAMS,
                        ) ?: arrayListOf(),
                    )
                    .putExtra(
                        RsdkRemoteActivity.EXTRA_PANORAMA_CALIBRATION_DATA,
                        request.getFloatArray(
                            PluginContract.KEY_CAMERA_PANORAMA_CALIBRATION_DATA,
                        ),
                    )
            } else {
                Intent(this@RsdkPluginService, RsdkPluginHomeActivity::class.java)
                    .putExtra(
                        PluginContract.KEY_REQUEST_PERMISSIONS,
                        request.getBoolean(PluginContract.KEY_REQUEST_PERMISSIONS, false),
                    )
            }
            // Android 15+ makes a PendingIntent creator opt in before the PendingIntent may start an
            // Activity. This plugin process has no visible window of its own: Base is the visible,
            // user-clicked sender. The PendingIntent is immutable, explicit and only obtainable over
            // our same-signature Binder service, so granting this one launch is narrowly scoped.
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
                this@RsdkPluginService,
                address.hashCode(),
                cameraIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions,
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

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
