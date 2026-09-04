package dev.pillar.osmodule.plugin.pocket4p

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import dev.pillar.osmodule.modules.ModuleRegistry

class Pocket4pPluginApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleRegistry.initialize(this).failures.forEach {
            Log.e("osmodulePocket4p", "Failed to load ${it.entryPoint}: ${it.reason}")
        }
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
