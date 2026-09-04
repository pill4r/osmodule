package dev.konraditurbe.osmosis.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDescriptorTest {
    @Test
    fun protocolRangeControlsCompatibility() {
        assertTrue(PluginDescriptor("test", "Test", 2, 1, 3, setOf("a")).supportsHostProtocol())
        assertFalse(PluginDescriptor("future", "Future", 1, 2, 3, emptySet()).supportsHostProtocol())
    }

    @Test
    fun bootstrapAuthorityIsScopedToEachPluginPackage() {
        assertTrue(
            PluginContract.bootstrapAuthority(PluginContract.RSDK_PACKAGE) ==
                "dev.konraditurbe.osmosis.plugin.rsdk.bootstrap",
        )
        assertTrue(
            PluginContract.bootstrapAuthority(PluginContract.PANORAMA_PACKAGE) ==
                "dev.konraditurbe.osmosis.plugin.panorama360.bootstrap",
        )
        assertTrue(
            PluginContract.bootstrapAuthority(PluginContract.POCKET4P_PACKAGE) ==
                "dev.konraditurbe.osmosis.plugin.pocket4p.bootstrap",
        )
    }
}
