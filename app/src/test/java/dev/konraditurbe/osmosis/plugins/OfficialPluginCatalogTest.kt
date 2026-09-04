package dev.konraditurbe.osmosis.plugins

import dev.konraditurbe.osmosis.plugin.PluginContract
import dev.konraditurbe.osmosis.plugin.PluginDescriptor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

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

    @Test
    fun acceptsOnlyCatalogedPocket4pIdentityAndCapabilities() {
        val descriptor = PluginDescriptor(
            id = PluginContract.POCKET4P_PLUGIN_ID,
            name = "Pocket 4P RC",
            version = 1,
            protocolMin = 1,
            protocolMax = 1,
            capabilities = setOf(
                PluginContract.CAPABILITY_POCKET4P_PANEL,
                PluginContract.CAPABILITY_CAMERA_SESSION_OWNER,
            ),
        )

        assertNull(OfficialPluginCatalog.validationIssue(PluginContract.POCKET4P_PACKAGE, descriptor))
        assertNotNull(
            OfficialPluginCatalog.validationIssue(
                PluginContract.POCKET4P_PACKAGE,
                descriptor.copy(capabilities = setOf(PluginContract.CAPABILITY_POCKET4P_PANEL)),
            ),
        )
        assertNotNull(
            OfficialPluginCatalog.validationIssue(
                PluginContract.POCKET4P_PACKAGE,
                descriptor.copy(capabilities = descriptor.capabilities + "camera.unreviewed"),
            ),
        )
    }

    @Test
    fun everyOfficialPluginUsesARepositoryReleaseApk() {
        OfficialPluginCatalog.policies.forEach { policy ->
            assertTrue(
                "Unexpected release URL for ${policy.packageName}",
                PluginApkDownloader.isOfficialReleaseUrl(URL(policy.releaseApkUrl)),
            )
        }
    }

    @Test
    fun downloadSourceRejectsNonRepositoryAndNonApkUrls() {
        assertTrue(
            !PluginApkDownloader.isOfficialReleaseUrl(
                URL("https://example.com/pill4r/osmodule/releases/latest/download/rsdk-release.apk"),
            ),
        )
        assertTrue(
            !PluginApkDownloader.isOfficialReleaseUrl(
                URL("https://github.com/pill4r/osmodule/releases/latest/download/notes.txt"),
            ),
        )
    }
}
