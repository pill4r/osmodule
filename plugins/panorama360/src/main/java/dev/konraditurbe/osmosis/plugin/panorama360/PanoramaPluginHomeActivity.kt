package dev.konraditurbe.osmosis.plugin.panorama360

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Plugin-owned setup screen. HyperOS Autostart can only be confirmed by the user. */
class PanoramaPluginHomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(36), dp(28), dp(28))
            addView(TextView(this@PanoramaPluginHomeActivity).apply {
                text = getString(R.string.panorama_plugin_ready)
                textSize = 18f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@PanoramaPluginHomeActivity).apply {
                text = getString(
                    if (isXiaomiFamilyDevice()) R.string.panorama_autostart_explanation
                    else R.string.panorama_no_runtime_permissions,
                )
                textSize = 14f
                setPadding(0, dp(24), 0, dp(8))
            })
            if (isXiaomiFamilyDevice()) {
                addView(
                    MaterialButton(this@PanoramaPluginHomeActivity).apply {
                        text = getString(R.string.panorama_autostart_settings)
                        setOnClickListener { openAutostartSettings() }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            addView(
                MaterialButton(this@PanoramaPluginHomeActivity).apply {
                    text = getString(R.string.panorama_app_settings)
                    setOnClickListener { openAppSettings() }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    private fun openAutostartSettings() {
        val autostart = Intent("miui.intent.action.OP_AUTO_START").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage("com.miui.securitycenter")
            putExtra("extra_pkgname", packageName)
        }
        val permissionEditor = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage("com.miui.securitycenter")
            putExtra("extra_pkgname", packageName)
        }
        if (runCatching { startActivity(autostart) }.isFailure &&
            runCatching { startActivity(permissionEditor) }.isFailure
        ) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun isXiaomiFamilyDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Redmi", ignoreCase = true) ||
            Build.BRAND.equals("POCO", ignoreCase = true)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
