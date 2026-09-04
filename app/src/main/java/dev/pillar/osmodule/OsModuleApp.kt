package dev.pillar.osmodule

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import dev.pillar.osmodule.modules.ModuleRegistry
import dev.pillar.osmodule.plugins.ExternalPluginBridgeModule
import dev.pillar.osmodule.plugins.ExternalPluginRegistry

/** Initializes osmodule's built-in modules and same-signature external plugin bridge. */
class OsModuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val catalog = ModuleRegistry.initialize(this)
        catalog.failures.forEach { failure ->
            Log.e("osmoduleModules", "Failed to load ${failure.entryPoint}: ${failure.reason}")
        }
        ExternalPluginRegistry.initialize(this)
        runCatching { ModuleRegistry.install(ExternalPluginBridgeModule()) }
            .onFailure { Log.e("osmoduleModules", "Failed to install external plugin bridge", it) }
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
