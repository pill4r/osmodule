package dev.konraditurbe.osmosis.plugin.pocket4p

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import dev.konraditurbe.osmosis.plugin.PluginContract

/** Plugin-owned permission screen; Base cannot request runtime permissions for another APK. */
class Pocket4pPluginHomeActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var permissionButton: MaterialButton
    private var autoRequested = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(36), dp(28), dp(28))
            addView(TextView(this@Pocket4pPluginHomeActivity).apply {
                text = getString(R.string.pocket4p_plugin_ready)
                textSize = 18f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@Pocket4pPluginHomeActivity).apply {
                text = getString(R.string.pocket4p_permissions_explanation)
                textSize = 14f
                setPadding(0, dp(24), 0, dp(8))
            })
            status = TextView(this@Pocket4pPluginHomeActivity).apply {
                textSize = 14f
                setPadding(0, dp(8), 0, dp(16))
            }
            addView(status)
            permissionButton = MaterialButton(this@Pocket4pPluginHomeActivity).apply {
                setOnClickListener { requestCameraWifiPermission() }
            }
            addView(
                permissionButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (isXiaomiFamilyDevice()) {
                addView(TextView(this@Pocket4pPluginHomeActivity).apply {
                    text = getString(R.string.pocket4p_autostart_explanation)
                    textSize = 14f
                    setPadding(0, dp(18), 0, dp(8))
                })
                addView(
                    MaterialButton(this@Pocket4pPluginHomeActivity).apply {
                        text = getString(R.string.pocket4p_autostart_settings)
                        setOnClickListener { openAutostartSettings() }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            addView(
                MaterialButton(this@Pocket4pPluginHomeActivity).apply {
                    text = getString(R.string.pocket4p_permissions_settings)
                    setOnClickListener {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    }
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
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
        if (!autoRequested && intent.getBooleanExtra(PluginContract.KEY_REQUEST_PERMISSIONS, false)) {
            autoRequested = true
            status.post(::requestCameraWifiPermission)
        }
    }

    private fun requestCameraWifiPermission() {
        val missing = cameraWifiPermissions().filterNot(::isPermissionGranted)
        if (missing.isEmpty()) refreshStatus() else permissionRequest.launch(missing.toTypedArray())
    }

    private fun refreshStatus() {
        val granted = cameraWifiPermissions().all(::isPermissionGranted)
        status.text = getString(
            R.string.pocket4p_permission_status,
            getString(
                if (granted) R.string.pocket4p_permission_state_granted
                else R.string.pocket4p_permission_state_missing,
            ),
        )
        permissionButton.isEnabled = !granted
        permissionButton.text = getString(
            if (granted) R.string.pocket4p_permission_granted
            else R.string.pocket4p_permissions_grant,
        )
    }

    private fun cameraWifiPermissions(): Set<String> = when {
        Build.VERSION.SDK_INT >= 33 -> setOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> setOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> setOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun isXiaomiFamilyDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Redmi", ignoreCase = true) ||
            Build.BRAND.equals("POCO", ignoreCase = true)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
