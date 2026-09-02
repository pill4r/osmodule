package dev.konraditurbe.osmosis.plugins

import dev.konraditurbe.osmosis.plugin.PluginContract
import dev.konraditurbe.osmosis.plugin.PluginDescriptor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialPluginCatalogTest {
    @Test
    fun acceptsOnlyCatalogedPanoramaIdentityAndCapability() {
        val descriptor = PluginDescriptor(
            id = PluginContract.PANORAMA_PLUGIN_ID,
            name = "osmodule 360 Viewer",
            version = 1,
            protocolMin = 1,
            protocolMax = 1,
            capabilities = setOf(PluginContract.CAPABILITY_MEDIA_360_VIEW),
        )

        assertNull(OfficialPluginCatalog.validationIssue(PluginContract.PANORAMA_PACKAGE, descriptor))
        assertNotNull(OfficialPluginCatalog.validationIssue("example.untrusted", descriptor))
        assertNotNull(
            OfficialPluginCatalog.validationIssue(
                PluginContract.PANORAMA_PACKAGE,
                descriptor.copy(capabilities = descriptor.capabilities + "camera.unreviewed"),
            ),
        )
    }
}
