package dev.konraditurbe.osmosis.plugins

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device regression for the fresh-install plugin path.
 *
 * Run after installing Base and the R-SDK plugin while the plugin still reports
 * `stopped=true, notLaunched=true`:
 *
 * ```sh
 * adb shell am instrument -w \
 *   dev.konraditurbe.osmosis.test/dev.konraditurbe.osmosis.plugins.PluginBindingInstrumentation
 * ```
 *
 * This executes as Base's instrumentation identity, so it exercises the real signature permission,
 * synchronous bootstrap provider and Binder runtime-state query without UI automation or a connected
 * camera.
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
        val started = ExternalPluginRegistry.queryActiveCameraSession(targetContext) { _, error ->
            failure = error
            completed.countDown()
        }
        if (!started) failure = "Plugin runtime-state query did not start"
        if (started && !completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            failure = "Plugin runtime-state query timed out"
        }

        val result = Bundle().apply {
            putString(
                "stream",
                failure?.let { "FAIL: $it\n" } ?: "OK: fresh-install plugin bootstrap and Binder query succeeded\n",
            )
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 8L
    }
}
