package dev.konraditurbe.osmosis.media

import dev.konraditurbe.osmosis.modules.AppModule
import dev.konraditurbe.osmosis.modules.Capabilities
import dev.konraditurbe.osmosis.modules.ModuleDelivery
import dev.konraditurbe.osmosis.modules.ModuleDescriptor

class MediaFeatureModule : AppModule {
    override val descriptor = ModuleDescriptor(
        id = "media",
        displayName = "Camera media",
        delivery = ModuleDelivery.CORE,
        capabilities = setOf(
            Capabilities.MEDIA_PAIR,
            Capabilities.MEDIA_CONNECT,
            Capabilities.MEDIA_BROWSE,
            Capabilities.MEDIA_PREVIEW,
            Capabilities.MEDIA_DOWNLOAD,
        ),
    )
}
