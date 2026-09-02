package dev.konraditurbe.osmosis.panorama

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.konraditurbe.osmosis.modules.AppModule
import dev.konraditurbe.osmosis.modules.Capabilities
import dev.konraditurbe.osmosis.modules.DeviceModels
import dev.konraditurbe.osmosis.modules.ModuleDelivery
import dev.konraditurbe.osmosis.modules.ModuleDescriptor
import dev.konraditurbe.osmosis.modules.ModuleScope
import dev.konraditurbe.osmosis.modules.PanoramaVideoRequest
import dev.konraditurbe.osmosis.modules.PanoramaVideoViewerLauncher

class Panorama360Module : AppModule {
    override val descriptor = ModuleDescriptor(
        id = MODULE_ID,
        displayName = "360° video viewer",
        delivery = ModuleDelivery.OPTIONAL_BUNDLED,
        capabilities = setOf(Capabilities.MEDIA_360_VIEW),
        supportedDeviceModels = setOf(DeviceModels.OSMO_360),
    )

    override fun install(scope: ModuleScope) {
        scope.bind(PanoramaVideoViewerLauncher::class.java, Launcher())
    }

    private class Launcher : PanoramaVideoViewerLauncher {
        override fun open(context: Context, request: PanoramaVideoRequest): Boolean = runCatching {
            require(request.deviceModel == DeviceModels.OSMO_360)
            require(request.streamCandidates.isNotEmpty())
            val intent = Intent(context, PanoramaVideoActivity::class.java)
                .putExtra(PanoramaVideoActivity.EXTRA_TITLE, request.title)
                .putStringArrayListExtra(
                    PanoramaVideoActivity.EXTRA_STREAMS,
                    ArrayList(request.streamCandidates),
                )
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    companion object {
        const val MODULE_ID = "panorama360"
    }
}
