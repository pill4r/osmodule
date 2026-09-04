package dev.konraditurbe.osmosis.plugin.rsdk

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
import dev.konraditurbe.osmosis.rsdk.RsdkPermissionPolicy

/** Plugin-owned first-run screen. Runtime permissions cannot be requested by the Base package. */
class RsdkPluginHomeActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var remotePermissionButton: MaterialButton
    private lateinit var gpsPermissionButton: MaterialButton
    private var notificationPermissionButton: MaterialButton? = null
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
            addView(TextView(this@RsdkPluginHomeActivity).apply {
                text = getString(R.string.rsdk_plugin_ready)
                textSize = 18f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@RsdkPluginHomeActivity).apply {
                text = getString(R.string.rsdk_permissions_explanation)
                textSize = 14f
                setPadding(0, dp(24), 0, dp(8))
            })
            status = TextView(this@RsdkPluginHomeActivity).apply {
                textSize = 14f
                setPadding(0, dp(8), 0, dp(16))
            }
            addView(status)
            remotePermissionButton = MaterialButton(this@RsdkPluginHomeActivity).apply {
                setOnClickListener { requestPermissions(remotePermissions()) }
            }
            addView(
                remotePermissionButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            gpsPermissionButton = MaterialButton(this@RsdkPluginHomeActivity).apply {
                setOnClickListener { requestPermissions(gpsPermissions()) }
            }
            addView(
                gpsPermissionButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (notificationPermissions().isNotEmpty()) {
                notificationPermissionButton = MaterialButton(this@RsdkPluginHomeActivity).apply {
                    setOnClickListener { requestPermissions(notificationPermissions()) }
                }
                addView(
                    notificationPermissionButton,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            if (isXiaomiFamilyDevice()) {
                addView(TextView(this@RsdkPluginHomeActivity).apply {
                    text = getString(R.string.rsdk_autostart_explanation)
                    textSize = 14f
                    setPadding(0, dp(18), 0, dp(8))
                })
                addView(
                    MaterialButton(this@RsdkPluginHomeActivity).apply {
                        text = getString(R.string.rsdk_autostart_settings)
                        setOnClickListener { openAutostartSettings() }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            addView(
                MaterialButton(this@RsdkPluginHomeActivity).apply {
                    text = getString(R.string.rsdk_permissions_settings)
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
            status.post { requestPermissions(remotePermissions()) }
        }
    }

    private fun requestPermissions(permissions: Set<String>) {
        val missing = RsdkPermissionPolicy.pendingRequest(
            Build.VERSION.SDK_INT,
            permissions,
            ::isPermissionGranted,
        )
        if (missing.isEmpty()) refreshStatus() else permissionRequest.launch(missing.toTypedArray())
    }

    private fun refreshStatus() {
        val remoteReady = remotePermissions().all(::isPermissionGranted)
        val gpsReady = gpsPermissions().all(::isPermissionGranted)
        val notificationsReady = notificationPermissions().all(::isPermissionGranted)
        status.text = buildString {
            append(getString(R.string.rsdk_permission_remote_status, stateLabel(remoteReady)))
            append('\n')
            append(getString(R.string.rsdk_permission_gps_status, stateLabel(gpsReady)))
            if (notificationPermissions().isNotEmpty()) {
                append('\n')
                append(getString(R.string.rsdk_permission_notification_status, stateLabel(notificationsReady)))
            }
        }
        updateButton(remotePermissionButton, remoteReady, R.string.rsdk_permissions_grant_remote)
        updateButton(gpsPermissionButton, gpsReady, R.string.rsdk_permissions_grant_gps)
        notificationPermissionButton?.let {
            updateButton(it, notificationsReady, R.string.rsdk_permissions_grant_notifications)
        }
    }

    private fun updateButton(button: MaterialButton, granted: Boolean, label: Int) {
        button.isEnabled = !granted
        button.text = getString(if (granted) R.string.rsdk_permission_granted else label)
    }

    private fun stateLabel(granted: Boolean): String =
        getString(if (granted) R.string.rsdk_permission_state_granted else R.string.rsdk_permission_state_missing)

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun remotePermissions() =
        RsdkPermissionPolicy.remoteControlPermissions(Build.VERSION.SDK_INT) +
            RsdkPermissionPolicy.livePreviewPermissions(Build.VERSION.SDK_INT)

    private fun gpsPermissions() =
        RsdkPermissionPolicy.gpsPermissions(Build.VERSION.SDK_INT)

    private fun notificationPermissions() =
        RsdkPermissionPolicy.notificationPermissions(Build.VERSION.SDK_INT)

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
