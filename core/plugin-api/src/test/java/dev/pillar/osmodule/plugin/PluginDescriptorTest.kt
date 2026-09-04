package dev.pillar.osmodule.plugin

import org.junit.Assert.assertEquals
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
                "dev.pillar.osmodule.plugin.rsdk.bootstrap",
        )
        assertTrue(
            PluginContract.bootstrapAuthority(PluginContract.PANORAMA_PACKAGE) ==
                "dev.pillar.osmodule.plugin.panorama360.bootstrap",
        )
        assertTrue(
            PluginContract.bootstrapAuthority(PluginContract.POCKET4P_PACKAGE) ==
                "dev.pillar.osmodule.plugin.pocket4p.bootstrap",
        )
    }

    @Test
    fun cameraNetworkHandoffKeyRemainsStable() {
        assertEquals("camera_network", PluginContract.KEY_CAMERA_NETWORK)
    }
}
