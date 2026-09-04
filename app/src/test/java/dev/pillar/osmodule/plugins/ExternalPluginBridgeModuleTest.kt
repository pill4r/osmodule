package dev.pillar.osmodule.plugins

import dev.pillar.osmodule.modules.DeviceModels
import dev.pillar.osmodule.modules.CameraRemotePanelLauncher
import dev.pillar.osmodule.modules.ModuleManagementLauncher
import dev.pillar.osmodule.modules.ModuleScope
import dev.pillar.osmodule.modules.PanoramaVideoViewerLauncher
import dev.pillar.osmodule.plugin.PluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalPluginBridgeModuleTest {
    @Test
    fun remotePanelCapabilityIsSelectedByCameraModel() {
        assertEquals(
            PluginContract.CAPABILITY_RSDK_PANEL,
            externalRemotePanelCapability(DeviceModels.OSMO_360),
        )
        assertEquals(
            PluginContract.CAPABILITY_POCKET4P_PANEL,
            externalRemotePanelCapability(DeviceModels.OSMO_POCKET_4_PRO),
        )
        assertNull(externalRemotePanelCapability("unsupported-camera"))
    }

    @Test
    fun bridgeDoesNotInstallAGalleryOwnershipGate() {
        val bindings = linkedSetOf<Class<*>>()

        ExternalPluginBridgeModule().install(object : ModuleScope {
            override fun <T : Any> bind(type: Class<T>, service: T) {
                bindings += type
            }
        })

        assertEquals(
            setOf(
                ModuleManagementLauncher::class.java,
                CameraRemotePanelLauncher::class.java,
                PanoramaVideoViewerLauncher::class.java,
            ),
            bindings,
        )
    }
}
