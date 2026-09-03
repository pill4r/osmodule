package dev.konraditurbe.osmosis.plugin.rsdk

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import dev.konraditurbe.osmosis.modules.ModuleRegistry

class RsdkPluginApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleRegistry.initialize(this).failures.forEach {
            Log.e("osmoduleRsdk", "Failed to load ${it.entryPoint}: ${it.reason}")
        }
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
