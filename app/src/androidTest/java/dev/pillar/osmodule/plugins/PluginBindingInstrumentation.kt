package dev.pillar.osmodule.plugins

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.pillar.osmodule.plugin.PluginContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device regression for fresh-install plugin discovery.
 *
 * Run after installing Base and the R-SDK plugin while the plugin still reports
 * `stopped=true, notLaunched=true`:
 *
 * ```sh
 * adb shell am instrument -w \
 *   dev.pillar.osmodule.test/dev.pillar.osmodule.plugins.PluginBindingInstrumentation
 * ```
 *
 * This verifies that Base can validate the signed plugin catalog without opening plugin providers,
 * binding plugin services or requiring a connected camera. Runtime camera exclusion is owned by the
 * central CameraSessionOwnerProvider instead of discovery-time plugin polling.
 */
class PluginBindingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        ExternalPluginRegistry.initialize(targetContext)
        val completed = CountDownLatch(1)
        var failure: String? = null
        val started = ExternalPluginRegistry.refreshAsync { records ->
            val plugin = records.firstOrNull {
                it.packageName == PluginContract.RSDK_PACKAGE
            }
            failure = when {
                plugin == null -> "R-SDK plugin was not discovered"
                !plugin.compatible -> plugin.issue ?: "R-SDK plugin is incompatible"
                else -> null
            }
            completed.countDown()
        }
        if (!started) failure = "Plugin catalog refresh did not start"
        if (started && !completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            failure = "Plugin catalog refresh timed out"
        }

        val result = Bundle().apply {
            putString(
                "stream",
                failure?.let { "FAIL: $it\n" } ?: "OK: fresh-install plugin discovery succeeded without runtime polling\n",
            )
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 8L
    }
}
