package dev.konraditurbe.osmosis.modules

import android.content.Context

/** User-controlled enable state for optional in-process modules. */
object ModuleSettings {
    private const val PREFS = "osmodule_modules"
    private const val PREFIX = "enabled."

    fun isEnabled(context: Context, moduleId: String, defaultValue: Boolean = true): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREFIX + moduleId, defaultValue)

    fun setEnabled(context: Context, moduleId: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFIX + moduleId, enabled)
            .apply()
    }
}
