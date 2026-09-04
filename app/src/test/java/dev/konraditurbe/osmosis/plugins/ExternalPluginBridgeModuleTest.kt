package dev.konraditurbe.osmosis.plugins

import dev.konraditurbe.osmosis.modules.DeviceModels
import dev.konraditurbe.osmosis.plugin.PluginContract
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
}
