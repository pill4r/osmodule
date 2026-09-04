package dev.konraditurbe.osmosis.pocket4p

import dev.konraditurbe.osmosis.modules.AppModule
import dev.konraditurbe.osmosis.modules.Capabilities
import dev.konraditurbe.osmosis.modules.DeviceModels
import dev.konraditurbe.osmosis.modules.ModuleDelivery
import dev.konraditurbe.osmosis.modules.ModuleDescriptor
import dev.konraditurbe.osmosis.modules.ModuleScope

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
