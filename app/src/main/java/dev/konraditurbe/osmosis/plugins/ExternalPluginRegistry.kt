package dev.konraditurbe.osmosis.plugins

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.konraditurbe.osmosis.R
import dev.konraditurbe.osmosis.plugin.IOsmosisPlugin
import dev.konraditurbe.osmosis.plugin.PluginContract
import dev.konraditurbe.osmosis.plugin.PluginDescriptor
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class ExternalPluginRecord(
    val packageName: String,
    val service: ComponentName,
    val descriptor: PluginDescriptor?,
    val trusted: Boolean,
    val issue: String? = null,
) {
    val compatible: Boolean get() = trusted && descriptor?.supportsHostProtocol() == true && issue == null
}

data class PluginArchiveCheck(
    val accepted: Boolean,
    val packageName: String? = null,
    val reason: String? = null,
)

data class ActiveExternalCameraSession(val pluginName: String, val cameraName: String?)

/**
 * Base-side plugin catalog and one-shot Binder launcher.
 *
 * External code is never loaded into the Base process. Discovery is restricted to an explicit
 * service action, every package must share Base's signing certificate, and the service is rechecked
 * over AIDL before its immutable PendingIntent is allowed to launch plugin-owned UI.
 */
object ExternalPluginRegistry {
    private const val TAG = "osmodulePlugin"
    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var records: List<ExternalPluginRecord> = emptyList()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh(): List<ExternalPluginRecord> {
        if (!::appContext.isInitialized) return emptyList()
        val pm = appContext.packageManager
        val hostSigners = signerDigests(packageInfo(pm, appContext.packageName))
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentServices(
            Intent(PluginContract.BIND_ACTION),
            PackageManager.GET_META_DATA,
        )
        records = resolved.mapNotNull { resolve ->
            val service = resolve.serviceInfo ?: return@mapNotNull null
            val component = ComponentName(service.packageName, service.name)
            val descriptor = descriptorFromMetadata(service)
            val catalogIssue = OfficialPluginCatalog.validationIssue(service.packageName, descriptor)
            val pluginSigners = signerDigests(packageInfo(pm, service.packageName))
            val trusted = hostSigners.isNotEmpty() && pluginSigners.any(hostSigners::contains)
            val bootstrap = pm.resolveContentProvider(
                PluginContract.bootstrapAuthority(service.packageName),
                0,
            )
            val issue = when {
                !service.exported -> "Plugin service is not exported"
                service.permission != PluginContract.BIND_PERMISSION -> "Plugin service is not protected by the signature permission"
                descriptor == null -> "Plugin metadata is incomplete"
                catalogIssue != null -> catalogIssue
                !trusted -> "Signing certificate does not match osmodule Base"
                bootstrap == null -> "Plugin bootstrap provider is missing"
                !bootstrap.exported -> "Plugin bootstrap provider is not exported"
                bootstrap.readPermission != PluginContract.BIND_PERMISSION ||
                    bootstrap.writePermission != PluginContract.BIND_PERMISSION ->
                    "Plugin bootstrap provider is not protected by the signature permission"
                !descriptor.supportsHostProtocol() -> "Plugin protocol is incompatible with Base v${PluginContract.PROTOCOL_VERSION}"
                else -> null
            }
            ExternalPluginRecord(service.packageName, component, descriptor, trusted, issue)
        }.sortedWith(compareBy({ it.descriptor?.name ?: it.packageName }, { it.packageName }))
        return records
    }

    fun catalog(): List<ExternalPluginRecord> = records

    fun packageRecord(packageName: String): ExternalPluginRecord? = refresh().firstOrNull {
        it.packageName == packageName
    }

    fun hasCapability(capability: String): Boolean = refresh().any {
        it.compatible && capability in it.descriptor!!.capabilities
    }

    /**
     * Opens plugin-owned permission UI directly from a visible, user-initiated Base Activity.
     * This intentionally avoids bootstrapping a background provider/service first: HyperOS blocks
     * that wake-up until Autostart is enabled, even though the user just tapped Permissions.
     */
    fun openPermissionCenter(
        context: Context,
        pluginPackage: String,
        onFailure: (String) -> Unit,
    ): Boolean {
        val plugin = packageRecord(pluginPackage)
        if (plugin?.compatible != true) {
            main.post { onFailure(plugin?.issue ?: "Plugin is not installed or compatible") }
            return false
        }
        val intent = Intent(PluginContract.PERMISSION_CENTER_ACTION)
            .setPackage(pluginPackage)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(PluginContract.KEY_REQUEST_PERMISSIONS, true)
        val activity = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
        if (activity == null || !activity.exported || activity.packageName != pluginPackage ||
            activity.permission != PluginContract.BIND_PERMISSION
        ) {
            main.post { onFailure("Plugin permission center is missing or not protected") }
            return false
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            main.post { onFailure(error.message ?: "Unable to open plugin permissions") }
            false
        }
    }

    fun openPanel(
        context: Context,
        capability: String,
        request: Bundle,
        onFailure: (String) -> Unit,
    ): Boolean {
        val plugin = refresh().firstOrNull {
            it.compatible && capability in it.descriptor!!.capabilities
        } ?: return false
        val applicationContext = context.applicationContext

        fun bindPanel(): Boolean = bindPanel(context, plugin, request, onFailure)

        return afterPluginReady(applicationContext, plugin, onFailure, ::bindPanel)
    }

    private fun bindPanel(
        launchContext: Context,
        plugin: ExternalPluginRecord,
        request: Bundle,
        onFailure: (String) -> Unit,
    ): Boolean {
        val finished = AtomicBoolean(false)
        lateinit var connection: ServiceConnection

        fun finish(error: String? = null) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { launchContext.unbindService(connection) }
            if (error != null) {
                Log.e(TAG, "Plugin panel launch failed for ${plugin.packageName}: $error")
                main.post { onFailure(error) }
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching {
                    val remote = IOsmosisPlugin.Stub.asInterface(binder)
                    require(remote.protocolVersion == PluginContract.PROTOCOL_VERSION) {
                        "Plugin protocol changed while binding"
                    }
                    val remoteDescriptor = PluginDescriptor.fromBundle(remote.descriptor)
                        ?: error("Plugin returned an invalid descriptor")
                    require(remoteDescriptor == plugin.descriptor) {
                        "Plugin identity does not match its signed manifest"
                    }
                    val pendingIntent: PendingIntent = remote.createPanelIntent(request)
                    val senderOptions = if (Build.VERSION.SDK_INT >= 34) {
                        ActivityOptions.makeBasic().apply {
                            setPendingIntentBackgroundActivityStartMode(
                                if (Build.VERSION.SDK_INT >= 36) {
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                                } else {
                                    @Suppress("DEPRECATION")
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                                },
                            )
                        }.toBundle()
                    } else null
                    pendingIntent.send(
                        launchContext,
                        0,
                        null,
                        null,
                        null,
                        null,
                        senderOptions,
                    )
                    Log.i(TAG, "Plugin panel PendingIntent sent for ${plugin.packageName}")
                }.onSuccess {
                    finish()
                }.onFailure { error ->
                    finish(error.message ?: "Unable to open plugin")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = finish("Plugin service disconnected")
            override fun onBindingDied(name: ComponentName) = finish("Plugin service binding died")
            override fun onNullBinding(name: ComponentName) = finish("Plugin returned an empty binding")
        }

        val intent = Intent(PluginContract.BIND_ACTION).setComponent(plugin.service)
        val bindResult = runCatching {
            val flags = Context.BIND_AUTO_CREATE or if (Build.VERSION.SDK_INT >= 34) {
                Context.BIND_ALLOW_ACTIVITY_STARTS
            } else 0
            launchContext.bindService(intent, connection, flags)
        }
        bindResult.exceptionOrNull()?.let {
            finish(it.message ?: "Unable to bind plugin")
            return false
        }
        val bound = bindResult.getOrDefault(false)
        if (!bound) {
            finish(pluginStartBlockedMessage(launchContext.applicationContext, plugin))
        } else {
            main.postDelayed(
                { finish("Plugin service connection timed out") },
                PLUGIN_BIND_TIMEOUT_MS,
            )
        }
        return bound
    }

    /** Queries every trusted external camera owner over Binder before Base opens its own transport. */
    fun queryActiveCameraSession(
        context: Context,
        callback: (ActiveExternalCameraSession?, String?) -> Unit,
    ): Boolean {
        val owners = refresh().filter {
            it.compatible && PluginContract.CAPABILITY_CAMERA_SESSION_OWNER in it.descriptor!!.capabilities
        }
        if (owners.isEmpty()) {
            main.post { callback(null, null) }
            return true
        }

        fun query(index: Int) {
            if (index >= owners.size) {
                main.post { callback(null, null) }
                return
            }
            val plugin = owners[index]
            queryRuntimeState(context, plugin) { state, error ->
                if (error != null) {
                    callback(null, error)
                } else if (state?.getBoolean(PluginContract.KEY_CAMERA_SESSION_ACTIVE, false) == true) {
                    callback(
                        ActiveExternalCameraSession(
                            plugin.descriptor?.name ?: plugin.packageName,
                            state.getString(PluginContract.KEY_CAMERA_SESSION_NAME),
                        ),
                        null,
                    )
                } else {
                    query(index + 1)
                }
            }
        }
        query(0)
        return true
    }

    private fun queryRuntimeState(
        context: Context,
        plugin: ExternalPluginRecord,
        callback: (Bundle?, String?) -> Unit,
    ) {
        val applicationContext = context.applicationContext
        val finished = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        fun finish(state: Bundle? = null, error: String? = null) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { applicationContext.unbindService(connection) }
            main.post { callback(state, error) }
        }

        fun bindRuntimeState(): Boolean {
            val bindResult = runCatching {
                applicationContext.bindService(
                    Intent(PluginContract.BIND_ACTION).setComponent(plugin.service),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }
            bindResult.exceptionOrNull()?.let {
                finish(error = it.message ?: "Unable to bind plugin")
                return false
            }
            val bound = bindResult.getOrDefault(false)
            if (!bound) {
                finish(error = pluginStartBlockedMessage(applicationContext, plugin))
            } else {
                main.postDelayed(
                    { finish(error = "Plugin service connection timed out") },
                    PLUGIN_BIND_TIMEOUT_MS,
                )
            }
            return bound
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching {
                    val remote = IOsmosisPlugin.Stub.asInterface(binder)
                    require(remote.protocolVersion == PluginContract.PROTOCOL_VERSION) { "Plugin protocol mismatch" }
                    val remoteDescriptor = PluginDescriptor.fromBundle(remote.descriptor)
                        ?: error("Plugin returned an invalid descriptor")
                    require(remoteDescriptor == plugin.descriptor) {
                        "Plugin identity does not match its signed manifest"
                    }
                    remote.runtimeState
                }.onSuccess { finish(state = it) }
                    .onFailure { finish(error = it.message ?: "Unable to query plugin") }
            }

            override fun onServiceDisconnected(name: ComponentName) = finish(error = "Plugin service disconnected")
            override fun onBindingDied(name: ComponentName) = finish(error = "Plugin service binding died")
            override fun onNullBinding(name: ComponentName) = finish(error = "Plugin returned an empty binding")
        }

        afterPluginReady(
            applicationContext,
            plugin,
            onFailure = { finish(error = it) },
            action = ::bindRuntimeState,
        )
    }

    /**
     * Android and OEM task managers can kill a plugin process while leaving its package unstopped.
     * Xiaomi/HyperOS then returns false from an otherwise valid explicit service bind. A protected
     * ContentProvider call is synchronous IPC: it starts the plugin without Activity policy and
     * returns only after its process is ready. Run the handshake before every short service bind;
     * FLAG_STOPPED alone is not a reliable readiness signal on OEM builds. The call itself runs off
     * the main thread and is bounded from the caller's perspective because a wedged provider must not
     * freeze Base's UI or leave its camera-session gate permanently in flight.
     */
    private fun afterPluginReady(
        context: Context,
        plugin: ExternalPluginRecord,
        onFailure: (String) -> Unit,
        action: () -> Boolean,
    ): Boolean {
        val completed = AtomicBoolean(false)

        fun fail(message: String) {
            if (completed.compareAndSet(false, true)) main.post { onFailure(message) }
        }

        main.postDelayed(
            {
                fail(
                    context.getString(
                        R.string.module_start_failed_generic,
                        plugin.displayName(),
                    ),
                )
            },
            PLUGIN_BOOTSTRAP_TIMEOUT_MS,
        )

        Thread({
            val result = runCatching {
                context.contentResolver.call(
                    Uri.Builder()
                        .scheme("content")
                        .authority(PluginContract.bootstrapAuthority(plugin.packageName))
                        .build(),
                    PluginContract.BOOTSTRAP_METHOD,
                    null,
                    null,
                )
            }.getOrElse { error ->
                val message = if (isXiaomiFamilyDevice()) {
                    pluginStartBlockedMessage(context, plugin)
                } else {
                    error.message
                        ?: context.getString(R.string.module_start_failed_generic, plugin.displayName())
                }
                fail(message)
                return@Thread
            }
            if (result?.getInt(PluginContract.KEY_BOOTSTRAP_PROTOCOL, -1) != PluginContract.PROTOCOL_VERSION) {
                fail("Plugin first-launch handshake is incompatible")
                return@Thread
            }
            if (completed.compareAndSet(false, true)) main.post { action() }
        }, "osmodule-plugin-bootstrap").apply { isDaemon = true }.start()
        return true
    }

    fun verifyArchive(apk: File, expectedPackage: String): PluginArchiveCheck {
        if (!::appContext.isInitialized) return PluginArchiveCheck(false, reason = "Plugin registry is not initialized")
        val pm = appContext.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA,
        )
            ?: return PluginArchiveCheck(false, reason = "The selected file is not a readable APK")
        if (archive.packageName != expectedPackage) {
            return PluginArchiveCheck(
                false,
                archive.packageName,
                "Expected $expectedPackage but selected ${archive.packageName}",
            )
        }
        val hostSigners = signerDigests(packageInfo(pm, appContext.packageName))
        val archiveSigners = signerDigests(archive)
        if (hostSigners.isEmpty() || archiveSigners.none(hostSigners::contains)) {
            return PluginArchiveCheck(false, archive.packageName, "APK signing certificate does not match osmodule Base")
        }
        val policy = OfficialPluginCatalog.policyFor(expectedPackage)
            ?: return PluginArchiveCheck(false, archive.packageName, "Unknown plugin package")
        val service = archive.services.orEmpty().firstOrNull {
            it.exported && it.permission == PluginContract.BIND_PERMISSION &&
                descriptorFromMetadata(it)?.id == policy.pluginId
        } ?: return PluginArchiveCheck(false, archive.packageName, "APK has no protected osmodule plugin service")
        val descriptor = descriptorFromMetadata(service)
            ?: return PluginArchiveCheck(false, archive.packageName, "APK plugin metadata is invalid")
        OfficialPluginCatalog.validationIssue(expectedPackage, descriptor)?.let { issue ->
            return PluginArchiveCheck(false, archive.packageName, issue)
        }
        val bootstrapAuthority = PluginContract.bootstrapAuthority(expectedPackage)
        val hasBootstrap = archive.providers.orEmpty().any {
            it.name == PluginContract.BOOTSTRAP_PROVIDER_CLASS &&
                it.authority == bootstrapAuthority && it.exported &&
                it.readPermission == PluginContract.BIND_PERMISSION &&
                it.writePermission == PluginContract.BIND_PERMISSION
        }
        if (!hasBootstrap) {
            return PluginArchiveCheck(
                false,
                archive.packageName,
                "APK has no protected osmodule bootstrap provider",
            )
        }
        if (!descriptor.supportsHostProtocol()) {
            return PluginArchiveCheck(false, archive.packageName, "Plugin protocol is not compatible with Base")
        }
        return PluginArchiveCheck(true, archive.packageName)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo? = runCatching {
        pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }.getOrNull()

    private fun signerDigests(info: PackageInfo?): Set<String> {
        val signingInfo = info?.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun ExternalPluginRecord.displayName(): String = descriptor?.name ?: packageName

    private fun pluginStartBlockedMessage(context: Context, plugin: ExternalPluginRecord): String {
        val resource = if (isXiaomiFamilyDevice()) {
            R.string.module_hyperos_autostart_required
        } else {
            R.string.module_start_failed_generic
        }
        return context.getString(resource, plugin.displayName())
    }

    internal fun isXiaomiFamilyDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.equals("Redmi", ignoreCase = true) ||
            Build.BRAND.equals("POCO", ignoreCase = true)

    private fun descriptorFromMetadata(service: ServiceInfo): PluginDescriptor? {
        val metadata = service.metaData ?: return null
        val id = metadata.getString(PluginContract.METADATA_ID).orEmpty()
        val name = metadata.getString(PluginContract.METADATA_NAME).orEmpty()
        val version = metadata.getInt(PluginContract.METADATA_VERSION, -1)
        val protocolMin = metadata.getInt(PluginContract.METADATA_PROTOCOL_MIN, -1)
        val protocolMax = metadata.getInt(PluginContract.METADATA_PROTOCOL_MAX, -1)
        val capabilities = metadata.getString(PluginContract.METADATA_CAPABILITIES)
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (id.isBlank() || name.isBlank() || version < 1 || protocolMin < 1 || protocolMax < protocolMin) {
            return null
        }
        return PluginDescriptor(id, name, version, protocolMin, protocolMax, capabilities)
    }

    private const val PLUGIN_BOOTSTRAP_TIMEOUT_MS = 5_000L
    private const val PLUGIN_BIND_TIMEOUT_MS = 5_000L

}
