package dev.pillar.osmodule.pocket4p

import dev.pillar.osmodule.modules.AppModule
import dev.pillar.osmodule.modules.Capabilities
import dev.pillar.osmodule.modules.DeviceModels
import dev.pillar.osmodule.modules.ModuleDelivery
import dev.pillar.osmodule.modules.ModuleDescriptor
import dev.pillar.osmodule.modules.ModuleScope

/** Descriptor for the external Pocket 4 Pro DUML remote-control feature. */
class Pocket4pControlModule : AppModule {
    override val descriptor = ModuleDescriptor(
        id = "pocket4p-control",
        displayName = "Pocket 4P RC",
        delivery = ModuleDelivery.EXTERNAL_APK,
        capabilities = setOf(Capabilities.POCKET4P_REMOTE_PANEL),
        supportedDeviceModels = setOf(DeviceModels.OSMO_POCKET_4_PRO),
    )

    override fun install(scope: ModuleScope) = Unit
}
