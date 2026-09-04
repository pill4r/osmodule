package dev.pillar.osmodule.plugin.panorama360

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import dev.pillar.osmodule.panorama.PanoramaVideoActivity
import dev.pillar.osmodule.plugin.IOsmosisPlugin
import dev.pillar.osmodule.plugin.PluginContract
import dev.pillar.osmodule.plugin.PluginDescriptor

class PanoramaPluginService : Service() {
    private val pluginDescriptor = PluginDescriptor(
        id = PluginContract.PANORAMA_PLUGIN_ID,
        name = "osmodule 360 Viewer",
        version = 3,
        protocolMin = 1,
        protocolMax = 1,
        capabilities = setOf(PluginContract.CAPABILITY_MEDIA_360_VIEW),
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
            return Bundle.EMPTY
        }

        override fun createPanelIntent(request: Bundle): PendingIntent {
            enforceHostCaller()
            val deviceModel = request.getString(PluginContract.KEY_MEDIA_DEVICE_MODEL).orEmpty()
            require(deviceModel == OSMO_360) { "The 360 viewer only supports Osmo 360" }
            val streams = request.getStringArrayList(PluginContract.KEY_MEDIA_STREAM_CANDIDATES)
                .orEmpty()
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
            require(streams.isNotEmpty()) { "No valid panorama preview stream was supplied" }
            val sourceKind = request.getString(PluginContract.KEY_MEDIA_SOURCE_KIND)
                ?: PluginContract.MEDIA_SOURCE_DUAL_FISHEYE_VIDEO
            require(
                sourceKind == PluginContract.MEDIA_SOURCE_DUAL_FISHEYE_VIDEO ||
                    sourceKind == PluginContract.MEDIA_SOURCE_EQUIRECTANGULAR_IMAGE,
            ) {
                "Unsupported panorama source kind"
            }
            val network = request.network()
            val panel = Intent(this@PanoramaPluginService, PanoramaVideoActivity::class.java)
                .putExtra(
                    PanoramaVideoActivity.EXTRA_TITLE,
                    request.getString(PluginContract.KEY_MEDIA_TITLE).orEmpty(),
                )
                .putStringArrayListExtra(PanoramaVideoActivity.EXTRA_STREAMS, ArrayList(streams))
                .putExtra(PanoramaVideoActivity.EXTRA_SOURCE_KIND, sourceKind)
                .putExtra(PanoramaVideoActivity.EXTRA_NETWORK, network)
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
                this@PanoramaPluginService,
                31 * streams.hashCode() + sourceKind.hashCode(),
                panel,
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

    @Suppress("DEPRECATION")
    private fun Bundle.network(): Network? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelable(PluginContract.KEY_MEDIA_NETWORK, Network::class.java)
    } else {
        getParcelable(PluginContract.KEY_MEDIA_NETWORK)
    }

    private companion object {
        const val OSMO_360 = "osmo360"
    }
}
