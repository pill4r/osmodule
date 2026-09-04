package dev.pillar.osmodule.plugins

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import dev.pillar.osmodule.R
import dev.pillar.osmodule.plugin.PluginContract
import dev.pillar.osmodule.modules.DeviceModels
import dev.pillar.osmodule.modules.ModuleDelivery
import dev.pillar.osmodule.modules.ModuleDescriptor
import dev.pillar.osmodule.modules.ModuleRegistry
import dev.pillar.osmodule.modules.ModuleSettings
import java.io.File

/** Base-owned module management plane. Installation always goes through Android's package installer. */
class PluginManagerActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private var requestedPackage: String? = null
    private var pendingInstallFile: File? = null
    private var pendingInstallPackage: String? = null
    private var downloadingPackage: String? = null

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val expectedPackage = requestedPackage
        requestedPackage = null
        if (uri != null && expectedPackage != null) stageAndVerify(uri, expectedPackage)
    }

    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = pendingInstallFile
        val expectedPackage = pendingInstallPackage
        if (file != null && expectedPackage != null && packageManager.canRequestPackageInstalls()) {
            launchPackageInstaller(file, expectedPackage)
        }
    }

    private val packageInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val installedPackage = pendingInstallPackage
        pendingInstallFile = null
        pendingInstallPackage = null
        ExternalPluginRegistry.refreshAsync {
            if (isFinishing || isDestroyed) return@refreshAsync
            render()
            // Some OEM package installers report RESULT_CANCELED even after a successful install.
            if (installedPackage != null) {
                list.postDelayed({ promptPluginPermissionsIfInstalled(installedPackage) }, 500)
            }
        }
    }

    private val packageUninstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ExternalPluginRegistry.refreshAsync {
            if (!isFinishing && !isDestroyed) render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.modules_title)
            subtitle = getString(R.string.modules_subtitle)
            setNavigationIcon(dev.pillar.osmodule.feature.media.R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) {
            render()
            ExternalPluginRegistry.refreshAsync {
                if (!isFinishing && !isDestroyed) render()
            }
        }
    }

    override fun onDestroy() {
        KNOWN_PLUGINS.mapNotNull { it.openCapability }
            .forEach(ExternalPluginRegistry::cancelPendingPanelLaunch)
        super.onDestroy()
    }

    private fun render() {
        list.removeAllViews()
        list.addView(text(getString(R.string.modules_intro), 14f).apply {
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY))
            setLineSpacing(0f, 1.12f)
            setPadding(0, 0, 0, dp(18))
        })
        section(R.string.module_core_section)
        ModuleRegistry.catalog().modules
            .filter { it.delivery == ModuleDelivery.CORE && it.id != "external-plugin-bridge" }
            .forEach(::addBundledModuleCard)
        section(R.string.module_optional_section)
        ModuleRegistry.catalog().modules
            .filter { it.delivery == ModuleDelivery.OPTIONAL_BUNDLED }
            .forEach(::addBundledModuleCard)
        KNOWN_PLUGINS.forEach(::addPluginCard)
    }

    private fun section(title: Int) {
        list.addView(text(getString(title), 16f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(10), 0, dp(10))
        })
    }

    private fun addBundledModuleCard(module: ModuleDescriptor) {
        val (name, summary) = when (module.id) {
            "media" -> R.string.module_media_name to R.string.module_media_summary
            "panorama360" -> R.string.module_panorama_name to R.string.module_panorama_summary
            else -> return
        }
        val installed = module.delivery == ModuleDelivery.CORE || ModuleSettings.isEnabled(this, module.id)
        val card = MaterialCardView(this).apply {
            styleModuleCard()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        content.addView(text(getString(name), 19f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(text(getString(summary), 14f).apply { setPadding(0, dp(5), 0, dp(9)) })
        content.addView(text(
            getString(
                when {
                    module.delivery == ModuleDelivery.CORE -> R.string.module_core_active
                    installed -> R.string.module_bundled_enabled
                    else -> R.string.module_bundled_disabled
                },
            ),
            13f,
        ))
        content.addView(text(applicability(module.supportedDeviceModels), 13f).apply {
            setPadding(0, dp(4), 0, 0)
        })
        if (module.delivery != ModuleDelivery.CORE) {
            content.addView(MaterialButton(this).apply {
                text = getString(if (installed) R.string.module_remove else R.string.module_install)
                styleAction(emphasized = !installed, destructive = installed)
                setOnClickListener {
                    ModuleSettings.setEnabled(this@PluginManagerActivity, module.id, !installed)
                    render()
                }
            }, actionLayoutParams())
        }
        card.addView(content)
        addCard(card)
    }

    private fun addPluginCard(known: KnownPlugin) {
        val record = ExternalPluginRegistry.packageRecord(known.packageName)
        val descriptor = record?.descriptor
        val packageInstalled = isPackageInstalled(known.packageName)
        val card = MaterialCardView(this).apply {
            styleModuleCard()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        content.addView(text(getString(known.name), 19f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        content.addView(text(getString(known.summary), 14f).apply { setPadding(0, dp(5), 0, dp(9)) })
        val status = when {
            record == null && !packageInstalled -> getString(R.string.module_not_installed)
            record == null -> getString(R.string.module_issue, getString(R.string.module_descriptor_missing))
            record.issue != null -> getString(R.string.module_issue, record.issue)
            descriptor != null -> getString(
                R.string.module_installed,
                descriptor.version,
                descriptor.protocolMin,
                descriptor.protocolMax,
            )
            else -> getString(R.string.module_issue, "Invalid descriptor")
        }
        content.addView(text(status, 13f))
        content.addView(text(applicability(known.supportedModels), 13f).apply {
            setPadding(0, dp(4), 0, 0)
        })
        if (descriptor != null) {
            content.addView(text(getString(R.string.module_capabilities, descriptor.capabilities.sorted().joinToString()), 12f).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(MaterialButton(this).apply {
            text = getString(
                when {
                    downloadingPackage == known.packageName -> R.string.module_downloading_apk
                    packageInstalled -> R.string.module_update_from_github
                    else -> R.string.module_install_from_github
                },
            )
            isEnabled = downloadingPackage == null
            setOnClickListener { downloadFromGitHub(known.packageName) }
        })
        actions.addView(MaterialButton(this).apply {
            text = getString(R.string.module_choose_local_apk)
            isEnabled = downloadingPackage == null
            setOnClickListener { selectApk(known.packageName) }
        })
        if (record?.compatible == true && known.hasPermissionCenter) {
            actions.addView(MaterialButton(this).apply {
                text = getString(
                    if (isXiaomiFamilyDevice()) R.string.module_permissions_and_autostart
                    else R.string.module_permissions,
                )
                setOnClickListener { openPluginPermissions(known.packageName) }
            })
        }
        if (packageInstalled) {
            actions.addView(MaterialButton(this).apply {
                text = getString(R.string.module_remove)
                setOnClickListener { uninstallPlugin(known.packageName) }
            })
            actions.addView(MaterialButton(this).apply {
                text = getString(R.string.module_app_info)
                setOnClickListener { openPluginAppInfo(known.packageName) }
            })
            if (isXiaomiFamilyDevice()) {
                actions.addView(MaterialButton(this).apply {
                    text = getString(R.string.module_autostart_settings)
                    setOnClickListener { openPluginAutostartSettings(known.packageName) }
                })
            }
        }
        if (record?.compatible == true && known.openCapability != null) {
            actions.addView(MaterialButton(this).apply {
                text = getString(R.string.module_open)
                setOnClickListener {
                    ExternalPluginRegistry.openPanel(
                        this@PluginManagerActivity,
                        known.openCapability,
                        Bundle(),
                    ) { showPluginStartError(known.packageName, it) }
                }
            })
        }
        for (i in 0 until actions.childCount) {
            val button = actions.getChildAt(i) as? MaterialButton ?: continue
            val destructive = button.text == getString(R.string.module_remove)
            button.styleAction(emphasized = i == 0 && !destructive, destructive = destructive)
            button.layoutParams = actionLayoutParams()
        }
        content.addView(actions)
        card.addView(content)
        addCard(card)
    }

    private fun addCard(card: MaterialCardView) {
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
    }

    private fun MaterialCardView.styleModuleCard() {
        radius = dp(20).toFloat()
        cardElevation = 0f
        useCompatPadding = false
        setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow, Color.WHITE))
        strokeColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
        strokeWidth = dp(1)
    }

    private fun MaterialButton.styleAction(emphasized: Boolean, destructive: Boolean = false) {
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(14)
        isAllCaps = false
        val fillAttr = when {
            destructive -> com.google.android.material.R.attr.colorErrorContainer
            emphasized -> com.google.android.material.R.attr.colorPrimary
            else -> com.google.android.material.R.attr.colorSurfaceContainerHigh
        }
        val textAttr = when {
            destructive -> com.google.android.material.R.attr.colorOnErrorContainer
            emphasized -> com.google.android.material.R.attr.colorOnPrimary
            else -> com.google.android.material.R.attr.colorOnSurface
        }
        backgroundTintList = ColorStateList.valueOf(themeColor(fillAttr, Color.LTGRAY))
        setTextColor(themeColor(textAttr, Color.DKGRAY))
        strokeWidth = if (emphasized || destructive) 0 else dp(1)
        strokeColor = ColorStateList.valueOf(
            themeColor(com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY),
        )
    }

    private fun actionLayoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(48),
    ).apply { topMargin = dp(8) }

    private fun themeColor(attr: Int, fallback: Int): Int = MaterialColors.getColor(this, attr, fallback)

    private fun applicability(models: Set<String>): String {
        val label = if (models.isEmpty()) {
            getString(R.string.module_all_cameras)
        } else {
            models.map { model ->
                when (model) {
                    DeviceModels.OSMO_360 -> getString(R.string.module_osmo360)
                    DeviceModels.OSMO_POCKET_4_PRO -> getString(R.string.module_pocket4p)
                    else -> model
                }
            }.joinToString()
        }
        return getString(R.string.module_applies_to, label)
    }

    private fun selectApk(packageName: String) {
        requestedPackage = packageName
        apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
    }

    private fun downloadFromGitHub(expectedPackage: String) {
        if (isDebugHostBuild()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.module_debug_signing_title)
                .setMessage(R.string.module_debug_github_signing_explanation)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val source = OfficialPluginCatalog.policyFor(expectedPackage)?.releaseApkUrl
        if (source == null) {
            showDownloadError(getString(R.string.module_download_invalid_source))
            return
        }
        downloadingPackage = expectedPackage
        render()
        Thread {
            val result = runCatching {
                val directory = File(cacheDir, "plugin-installs")
                val apk = PluginApkDownloader.download(
                    this,
                    source,
                    File(directory, "$expectedPackage.apk"),
                )
                apk to ExternalPluginRegistry.verifyArchive(apk, expectedPackage)
            }
            runOnUiThread {
                downloadingPackage = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                render()
                result.onSuccess { (apk, check) ->
                    if (check.accepted) requestPackageInstall(apk, expectedPackage)
                    else showError(check.reason ?: getString(R.string.module_invalid_apk))
                }.onFailure { showDownloadError(downloadFailureMessage(it)) }
            }
        }.apply {
            name = "osmodule-plugin-download"
            isDaemon = true
        }.start()
    }

    private fun uninstallPlugin(pluginPackage: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pluginPackage")).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        runCatching { packageUninstaller.launch(intent) }
            .onFailure { showError(it.message ?: getString(R.string.module_remove_failed)) }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        ExternalPluginRegistry.isPackageInstalled(packageName)

    private fun stageAndVerify(uri: Uri, expectedPackage: String) {
        Thread {
            val result = runCatching {
                val directory = File(cacheDir, "plugin-installs").apply { mkdirs() }
                val apk = File(directory, "$expectedPackage.apk")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { getString(R.string.module_copy_failed) }
                    apk.outputStream().use(input::copyTo)
                }
                apk to ExternalPluginRegistry.verifyArchive(apk, expectedPackage)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { (apk, check) ->
                    if (check.accepted) requestPackageInstall(apk, expectedPackage)
                    else showError(check.reason ?: getString(R.string.module_invalid_apk))
                }.onFailure { showError(it.message ?: getString(R.string.module_copy_failed)) }
            }
        }.apply {
            name = "osmodule-plugin-local-verify"
            isDaemon = true
        }.start()
    }

    private fun requestPackageInstall(apk: File, expectedPackage: String) {
        pendingInstallFile = apk
        pendingInstallPackage = expectedPackage
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.module_install_access)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    installPermission.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                    )
                }
                .show()
            return
        }
        launchPackageInstaller(apk, expectedPackage)
    }

    private fun launchPackageInstaller(apk: File, expectedPackage: String) {
        pendingInstallFile = apk
        pendingInstallPackage = expectedPackage
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        runCatching { packageInstaller.launch(intent) }
            .onFailure { showError(it.message ?: getString(R.string.module_copy_failed)) }
    }

    private fun promptPluginPermissionsIfInstalled(pluginPackage: String, attempt: Int = 0) {
        if (KNOWN_PLUGINS.firstOrNull { it.packageName == pluginPackage }?.hasPermissionCenter != true) {
            render()
            return
        }
        if (ExternalPluginRegistry.packageRecord(pluginPackage)?.compatible == true) {
            openPluginPermissions(pluginPackage)
        } else if (attempt < 4) {
            list.postDelayed({ promptPluginPermissionsIfInstalled(pluginPackage, attempt + 1) }, 500)
        } else {
            render()
        }
    }

    private fun openPluginPermissions(pluginPackage: String) {
        ExternalPluginRegistry.openPermissionCenter(
            this,
            pluginPackage,
        ) { showPluginStartError(pluginPackage, it) }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.module_invalid_apk)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showDownloadError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.module_download_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun downloadFailureMessage(error: Throwable): String = when (error) {
        is PluginApkDownloader.DownloadException -> when (error.failure) {
            PluginApkDownloader.Failure.NO_INTERNET -> getString(R.string.module_download_no_internet)
            PluginApkDownloader.Failure.HTTP -> getString(
                R.string.module_download_http_error,
                error.httpStatus ?: 0,
            )
            PluginApkDownloader.Failure.TOO_LARGE -> getString(R.string.module_download_too_large)
            PluginApkDownloader.Failure.INVALID_SOURCE -> getString(R.string.module_download_invalid_source)
        }
        else -> getString(R.string.module_download_failed)
    }

    private fun showPluginStartError(pluginPackage: String, message: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.module_start_failed_title)
            .setMessage(message)
        if (isXiaomiFamilyDevice()) {
            dialog
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.module_autostart_settings) { _, _ ->
                    openPluginAutostartSettings(pluginPackage)
                }
        } else {
            dialog.setPositiveButton(android.R.string.ok, null)
        }
        dialog.show()
    }

    private fun openPluginAppInfo(pluginPackage: String) {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pluginPackage"),
            ),
        )
    }

    private fun openPluginAutostartSettings(pluginPackage: String) {
        val autostart = Intent("miui.intent.action.OP_AUTO_START").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage("com.miui.securitycenter")
            putExtra("extra_pkgname", pluginPackage)
        }
        val permissionEditor = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage("com.miui.securitycenter")
            putExtra("extra_pkgname", pluginPackage)
        }
        if (runCatching { startActivity(autostart) }.isFailure &&
            runCatching { startActivity(permissionEditor) }.isFailure
        ) {
            openPluginAppInfo(pluginPackage)
        }
    }

    private fun isDebugHostBuild(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun isXiaomiFamilyDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Redmi", ignoreCase = true) ||
            Build.BRAND.equals("POCO", ignoreCase = true)

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class KnownPlugin(
        val packageName: String,
        val name: Int,
        val summary: Int,
        val openCapability: String?,
        val supportedModels: Set<String>,
        val hasPermissionCenter: Boolean,
    )

    private companion object {
        val KNOWN_PLUGINS = listOf(
            KnownPlugin(
                PluginContract.RSDK_PACKAGE,
                R.string.module_rsdk_name,
                R.string.module_rsdk_summary,
                PluginContract.CAPABILITY_RSDK_PANEL,
                setOf(DeviceModels.OSMO_360),
                true,
            ),
            KnownPlugin(
                PluginContract.POCKET4P_PACKAGE,
                R.string.module_pocket4p_name,
                R.string.module_pocket4p_summary,
                PluginContract.CAPABILITY_POCKET4P_PANEL,
                setOf(DeviceModels.OSMO_POCKET_4_PRO),
                true,
            ),
            KnownPlugin(
                PluginContract.PANORAMA_PACKAGE,
                R.string.module_panorama_name,
                R.string.module_panorama_summary,
                null,
                setOf(DeviceModels.OSMO_360),
                true,
            ),
        )
    }
}
