package dev.konraditurbe.osmosis.plugins

import dev.konraditurbe.osmosis.plugin.PluginContract
import dev.konraditurbe.osmosis.plugin.PluginDescriptor

/** The official Base accepts only these same-signature plugin identities and capabilities. */
internal object OfficialPluginCatalog {
    data class Policy(
        val packageName: String,
        val pluginId: String,
        val requiredCapabilities: Set<String>,
        val allowedCapabilities: Set<String>,
    )

    val policies = listOf(
        Policy(
            packageName = PluginContract.PANORAMA_PACKAGE,
            pluginId = PluginContract.PANORAMA_PLUGIN_ID,
            requiredCapabilities = setOf(PluginContract.CAPABILITY_MEDIA_360_VIEW),
            allowedCapabilities = setOf(PluginContract.CAPABILITY_MEDIA_360_VIEW),
        ),
        Policy(
            packageName = PluginContract.RSDK_PACKAGE,
            pluginId = PluginContract.RSDK_PLUGIN_ID,
            requiredCapabilities = setOf(
                PluginContract.CAPABILITY_RSDK_PANEL,
                PluginContract.CAPABILITY_CAMERA_SESSION_OWNER,
            ),
            allowedCapabilities = setOf(
                PluginContract.CAPABILITY_RSDK_PANEL,
                PluginContract.CAPABILITY_RSDK_REMOTE_CONTROL,
                PluginContract.CAPABILITY_RSDK_STATUS,
                PluginContract.CAPABILITY_RSDK_GPS,
                PluginContract.CAPABILITY_CAMERA_SESSION_OWNER,
            ),
        ),
    )

    fun policyFor(packageName: String): Policy? = policies.firstOrNull {
        it.packageName == packageName
    }

    fun validationIssue(packageName: String, descriptor: PluginDescriptor?): String? {
        val policy = policyFor(packageName) ?: return "Plugin package is not in the official catalog"
        descriptor ?: return "Plugin metadata is incomplete"
        if (descriptor.id != policy.pluginId) return "Plugin id does not match the official catalog"
        if (!descriptor.capabilities.containsAll(policy.requiredCapabilities)) {
            return "Plugin is missing a required capability"
        }
        if (!policy.allowedCapabilities.containsAll(descriptor.capabilities)) {
            return "Plugin declares a capability not allowed by the official catalog"
        }
        return null
    }
}
