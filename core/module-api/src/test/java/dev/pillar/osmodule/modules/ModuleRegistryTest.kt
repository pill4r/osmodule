package dev.pillar.osmodule.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleRegistryTest {
    private interface ExampleCapability
    private object ExampleService : ExampleCapability

    @Test
    fun installedModulePublishesDescriptorAndCapability() {
        val module = object : AppModule {
            override val descriptor = ModuleDescriptor(
                id = "module-api-test",
                displayName = "Module API test",
                delivery = ModuleDelivery.OPTIONAL_BUNDLED,
                capabilities = setOf("test.capability"),
            )

            override fun install(scope: ModuleScope) {
                scope.bind(ExampleCapability::class.java, ExampleService)
            }
        }

        val catalog = ModuleRegistry.install(module)

        assertEquals("module-api-test", catalog.modules.single { it.id == "module-api-test" }.id)
        assertSame(ExampleService, ModuleRegistry.capability(ExampleCapability::class.java))
    }

    @Test
    fun descriptorEnforcesModelApplicability() {
        val viewer = ModuleDescriptor(
            id = "panorama360-test",
            displayName = "360 viewer",
            delivery = ModuleDelivery.OPTIONAL_BUNDLED,
            capabilities = setOf(Capabilities.MEDIA_360_VIEW),
            supportedDeviceModels = setOf(DeviceModels.OSMO_360),
        )
        val media = viewer.copy(id = "media-test", supportedDeviceModels = emptySet())

        assertTrue(viewer.supports(DeviceModels.OSMO_360))
        assertFalse(viewer.supports("osmoaction6"))
        assertTrue(media.supports("osmoaction6"))
    }
}
