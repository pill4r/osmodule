package dev.pillar.osmodule.media

import dev.pillar.osmodule.modules.AppModule
import dev.pillar.osmodule.modules.Capabilities
import dev.pillar.osmodule.modules.ModuleDelivery
import dev.pillar.osmodule.modules.ModuleDescriptor

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
