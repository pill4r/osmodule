package dev.pillar.osmodule.plugins

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
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
import dev.pillar.osmodule.R
import dev.pillar.osmodule.plugin.IOsmosisPlugin
import dev.pillar.osmodule.plugin.PluginContract
import dev.pillar.osmodule.plugin.PluginDescriptor
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

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

private data class PluginCatalogSnapshot(
    val records: List<ExternalPluginRecord> = emptyList(),
    val installedPackages: Set<String> = emptySet(),
)

/**
 * Base-side plugin catalog and one-shot Binder launcher.
 *
 * External code is never loaded into the Base process. Discovery is restricted to an explicit
 * service action, every package must share Base's signing certificate, and the service is rechecked
 * over AIDL before its immutable PendingIntent is allowed to launch plugin-owned UI.
 */
object ExternalPluginRegistry {
    private const val TAG = "osmodulePlugin"
    private const val PLUGIN_BOOTSTRAP_TIMEOUT_MS = 5_000L
    private const val PLUGIN_BIND_TIMEOUT_MS = 5_000L
    private const val PLUGIN_IPC_THREADS = 2
    private const val PLUGIN_IPC_QUEUE_CAPACITY = 16
    private const val PLUGIN_CATALOG_QUEUE_CAPACITY = 8
    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val pendingPanelLaunches = ConcurrentHashMap<String, PluginAsyncOperationSlot<Unit>>()
    private val ipcThreadNumber = AtomicInteger()
    private val catalogThreadNumber = AtomicInteger()
    private val timerThreadNumber = AtomicInteger()
    private val packageReceiverRegistered = AtomicBoolean(false)
    private val ipcWorker = ThreadPoolExecutor(
        PLUGIN_IPC_THREADS,
        PLUGIN_IPC_THREADS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(PLUGIN_IPC_QUEUE_CAPACITY),
        namedDaemonThreadFactory("osmodule-plugin-ipc", ipcThreadNumber),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val catalogWorker = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(PLUGIN_CATALOG_QUEUE_CAPACITY),
        namedDaemonThreadFactory("osmodule-plugin-catalog", catalogThreadNumber),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val timeoutWorker = ScheduledThreadPoolExecutor(
        1,
        namedDaemonThreadFactory("osmodule-plugin-timeout", timerThreadNumber),
    ).apply { removeOnCancelPolicy = true }

    @Volatile
    private var cachedCatalog = PluginCatalogSnapshot()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        registerPackageChangeReceiver()
        refreshAsync()
    }

    private fun discoverCatalog(context: Context): PluginCatalogSnapshot {
        val pm = context.packageManager
        val hostSigners = signerDigests(packageInfo(pm, context.packageName))
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentServices(
            Intent(PluginContract.BIND_ACTION),
            PackageManager.GET_META_DATA,
        )
        val discoveredRecords = resolved.mapNotNull { resolve ->
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
        val installedPackages = OfficialPluginCatalog.policies.mapNotNullTo(linkedSetOf()) { policy ->
            policy.packageName.takeIf { packageInfo(pm, it) != null }
        }
        return PluginCatalogSnapshot(discoveredRecords, installedPackages)
    }

    fun catalog(): List<ExternalPluginRecord> = cachedCatalog.records

    fun packageRecord(packageName: String): ExternalPluginRecord? = cachedCatalog.records.firstOrNull {
        it.packageName == packageName
    }

    fun hasCapability(capability: String): Boolean = cachedCatalog.records.any {
        it.compatible && capability in it.descriptor!!.capabilities
    }

    fun isPackageInstalled(packageName: String): Boolean =
        packageName in cachedCatalog.installedPackages

    /** Refreshes the read-only catalog cache without ever querying PackageManager on the caller. */
    fun refreshAsync(onComplete: ((List<ExternalPluginRecord>) -> Unit)? = null): Boolean {
        if (!::appContext.isInitialized) return false
        val context = appContext
        return executeCatalog(
            onRejected = {
                onComplete?.let { callback -> main.post { callback(cachedCatalog.records) } }
            },
        ) {
            val discovered = runCatching { discoverCatalog(context) }.getOrNull()
            if (discovered != null) cachedCatalog = discovered
            onComplete?.let { callback -> main.post { callback(cachedCatalog.records) } }
        }
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
        val applicationContext = context.applicationContext
        val launchContext = WeakReference(context)
        val intent = Intent(PluginContract.PERMISSION_CENTER_ACTION)
            .setPackage(pluginPackage)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(PluginContract.KEY_REQUEST_PERMISSIONS, true)

        fun fail(message: String) {
            main.post {
                val target = launchContext.get()
                if (target is Activity &&
                    (target.isFinishing || target.isDestroyed || !target.hasWindowFocus())
                ) return@post
                runCatching { onFailure(message) }
                    .onFailure { Log.e(TAG, "Plugin permission failure callback threw", it) }
            }
        }

        return executeCatalog(
            onRejected = { fail("Plugin catalog worker is busy") },
        ) {
            val discovered = runCatching { discoverCatalog(applicationContext) }.getOrElse { error ->
                fail(error.message ?: "Unable to discover plugins")
                return@executeCatalog
            }
            cachedCatalog = discovered
            val plugin = discovered.records.firstOrNull { it.packageName == pluginPackage }
            if (plugin?.compatible != true) {
                fail(plugin?.issue ?: "Plugin is not installed or compatible")
                return@executeCatalog
            }
            val activity = applicationContext.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
            if (activity == null || !activity.exported || activity.packageName != pluginPackage ||
                activity.permission != PluginContract.BIND_PERMISSION
            ) {
                fail("Plugin permission center is missing or not protected")
                return@executeCatalog
            }
            main.post {
                val target = launchContext.get() ?: return@post
                if (target is Activity &&
                    (target.isFinishing || target.isDestroyed || !target.hasWindowFocus())
                ) return@post
                val launchIntent = Intent(intent).apply {
                    if (target !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { target.startActivity(launchIntent) }
                    .onFailure { fail(it.message ?: "Unable to open plugin permissions") }
            }
        }
    }

    fun openPanel(
        context: Context,
        capability: String,
        request: Bundle,
        onFailure: (String) -> Unit,
    ): Boolean = openPanel(
        context = context,
        capability = capability,
        request = request,
        launchGroup = capability,
        onFailure = onFailure,
        onComplete = {},
    )

    /**
     * Starts one cancellable panel launch generation. A newer launch in [launchGroup], or an
     * explicit [cancelPendingPanelLaunch], invalidates bootstrap/bind/RPC work from the old one.
     * [onComplete] runs on the main thread exactly once for this registry generation.
     */
    fun openPanel(
        context: Context,
        capability: String,
        request: Bundle,
        launchGroup: String,
        onFailure: (String) -> Unit,
        onComplete: (opened: Boolean) -> Unit,
    ): Boolean {
        val slot = pendingPanelLaunches.computeIfAbsent(launchGroup) {
            PluginAsyncOperationSlot()
        }
        val applicationContext = context.applicationContext
        val operation = slot.begin { result ->
            main.post {
                when (result) {
                    is PluginAsyncResult.Success -> onComplete(true)
                    is PluginAsyncResult.Cancelled -> onComplete(false)
                    is PluginAsyncResult.Failure -> {
                        Log.e(TAG, "Plugin panel launch failed for $capability: ${result.message}")
                        deliverPluginPanelFailure(
                            result.message,
                            onFailure,
                            onComplete,
                        ) { Log.e(TAG, "Plugin failure callback threw", it) }
                    }
                }
            }
        }
        val scheduled = executeCatalog(operation, "Plugin catalog worker is busy") {
            val discovered = runCatching { discoverCatalog(applicationContext) }.getOrElse { error ->
                operation.fail(error.message ?: "Unable to discover plugins")
                return@executeCatalog
            }
            cachedCatalog = discovered
            if (!operation.isActive()) return@executeCatalog
            val plugin = discovered.records.firstOrNull {
                it.compatible && capability in it.descriptor!!.capabilities
            }
            if (plugin == null) {
                operation.fail("Plugin is not installed or compatible")
                return@executeCatalog
            }
            bootstrapPlugin(applicationContext, plugin, operation) {
                bindPanel(applicationContext, plugin, request, operation)
            }
        }
        return scheduled
    }

    fun cancelPendingPanelLaunch(launchGroup: String) {
        pendingPanelLaunches[launchGroup]?.cancel()
    }

    private fun bindPanel(
        context: Context,
        plugin: ExternalPluginRecord,
        request: Bundle,
        operation: PluginAsyncOperation<Unit>,
    ) {
        val deadline = deadlineAfter(PLUGIN_BIND_TIMEOUT_MS)
        val timeoutMessage = "Plugin service connection timed out"
        val timeout = timeoutWorker.schedule(
            { operation.fail(timeoutMessage) },
            PLUGIN_BIND_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        operation.addCleanup { timeout.cancel(false) }
        val rpcStarted = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        lateinit var binding: ServiceBindingLease

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!rpcStarted.compareAndSet(false, true) || !operation.isActive()) return
                executeIpc(operation, "Plugin IPC worker is busy") {
                    val result = runCatching {
                        val remote = IOsmosisPlugin.Stub.asInterface(binder)
                        require(remote.protocolVersion == PluginContract.PROTOCOL_VERSION) {
                            "Plugin protocol changed while binding"
                        }
                        val remoteDescriptor = PluginDescriptor.fromBundle(remote.descriptor)
                            ?: error("Plugin returned an invalid descriptor")
                        require(remoteDescriptor == plugin.descriptor) {
                            "Plugin identity does not match its signed manifest"
                        }
                        remote.createPanelIntent(request)
                    }
                    result.onFailure { error ->
                        operation.fail(error.message ?: "Unable to open plugin")
                    }
                    val pendingIntent = result.getOrNull() ?: return@executeIpc
                    val sent = operation.succeedBefore(
                        deadlineNanos = deadline,
                        timeoutMessage = timeoutMessage,
                        failureMessage = "Unable to open plugin",
                        value = Unit,
                    ) {
                        sendPanelIntent(context, pendingIntent)
                    }
                    if (sent) Log.i(TAG, "Plugin panel PendingIntent sent for ${plugin.packageName}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                operation.fail("Plugin service disconnected")
            }

            override fun onBindingDied(name: ComponentName) {
                operation.fail("Plugin service binding died")
            }

            override fun onNullBinding(name: ComponentName) {
                operation.fail("Plugin returned an empty binding")
            }
        }
        binding = ServiceBindingLease(context, connection)
        operation.addCleanup(binding::close)

        val intent = Intent(PluginContract.BIND_ACTION).setComponent(plugin.service)
        operation.proceedBefore(
            deadlineNanos = deadline,
            timeoutMessage = timeoutMessage,
            failureMessage = "Unable to bind plugin",
        ) {
            val flags = Context.BIND_AUTO_CREATE or if (Build.VERSION.SDK_INT >= 34) {
                Context.BIND_ALLOW_ACTIVITY_STARTS
            } else 0
            val bound = context.bindService(intent, connection, flags)
            binding.onBindResult(bound)
            require(bound) { pluginStartBlockedMessage(context, plugin) }
        }
    }

    /**
     * Android and OEM task managers can kill a plugin process while leaving its package unstopped.
     * Xiaomi/HyperOS then returns false from an otherwise valid explicit service bind. A protected
     * ContentProvider call is synchronous IPC: it starts the plugin without Activity policy and
     * returns only after its process is ready. Run the handshake before every short service bind;
     * FLAG_STOPPED alone is not a reliable readiness signal on OEM builds. The call itself runs off
     * the main thread and is bounded from the caller's perspective because a wedged provider must not
     * freeze Base's UI or leave a panel launch permanently in flight.
     */
    private fun <T> bootstrapPlugin(
        context: Context,
        plugin: ExternalPluginRecord,
        operation: PluginAsyncOperation<T>,
        action: () -> Unit,
    ) {
        val deadline = deadlineAfter(PLUGIN_BOOTSTRAP_TIMEOUT_MS)
        val timeoutMessage = context.getString(
            R.string.module_start_failed_generic,
            plugin.displayName(),
        )
        val timeout = timeoutWorker.schedule(
            { operation.fail(timeoutMessage) },
            PLUGIN_BOOTSTRAP_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        operation.addCleanup { timeout.cancel(false) }

        executeIpc(operation, timeoutMessage) {
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
                    error.message ?: timeoutMessage
                }
                operation.fail(message)
                return@executeIpc
            }
            if (result?.getInt(PluginContract.KEY_BOOTSTRAP_PROTOCOL, -1) != PluginContract.PROTOCOL_VERSION) {
                operation.fail("Plugin first-launch handshake is incompatible")
                return@executeIpc
            }
            timeout.cancel(false)
            operation.proceedBefore(
                deadlineNanos = deadline,
                timeoutMessage = timeoutMessage,
                failureMessage = "Unable to bind plugin",
                action = action,
            )
        }
    }

    private fun sendPanelIntent(context: Context, pendingIntent: PendingIntent) {
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
            context,
            0,
            null,
            null,
            null,
            null,
            senderOptions,
        )
    }

    /**
     * All potentially blocking provider/Binder work is admitted through one bounded executor.
     * Timeouts run on an independent scheduler, so a wedged plugin cannot prevent its own timeout.
     */
    private fun <T> executeIpc(
        operation: PluginAsyncOperation<T>,
        rejectedMessage: String,
        action: () -> Unit,
    ): Boolean = executeOperation(ipcWorker, operation, rejectedMessage, action)

    private fun <T> executeCatalog(
        operation: PluginAsyncOperation<T>,
        rejectedMessage: String,
        action: () -> Unit,
    ): Boolean = executeOperation(catalogWorker, operation, rejectedMessage, action)

    private fun <T> executeOperation(
        worker: ThreadPoolExecutor,
        operation: PluginAsyncOperation<T>,
        rejectedMessage: String,
        action: () -> Unit,
    ): Boolean {
        val task = FutureTask {
            if (operation.isActive()) action()
        }
        operation.addCleanup {
            task.cancel(false)
            worker.remove(task)
        }
        return try {
            worker.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            operation.fail(rejectedMessage)
            false
        }
    }

    private fun executeCatalog(
        onRejected: () -> Unit,
        action: () -> Unit,
    ): Boolean {
        val task = FutureTask(action)
        return try {
            catalogWorker.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            onRejected()
            false
        }
    }

    private fun registerPackageChangeReceiver() {
        if (!packageReceiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshAsync()
            }
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (!registered) packageReceiverRegistered.set(false)
    }

    private fun deadlineAfter(timeoutMillis: Long): Long =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

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
            val reason = if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                appContext.getString(R.string.module_debug_signing_mismatch)
            } else {
                appContext.getString(R.string.module_signing_mismatch)
            }
            return PluginArchiveCheck(false, archive.packageName, reason)
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

    /** Handles cancellation racing bindService's return without leaking a late successful bind. */
    private class ServiceBindingLease(
        private val context: Context,
        private val connection: ServiceConnection,
    ) {
        private val lock = Any()
        private var bound = false
        private var closeRequested = false

        fun onBindResult(isBound: Boolean) {
            val unbindNow = synchronized(lock) {
                bound = isBound
                if (bound && closeRequested) {
                    bound = false
                    true
                } else {
                    false
                }
            }
            if (unbindNow) unbind()
        }

        fun close() {
            val unbindNow = synchronized(lock) {
                closeRequested = true
                if (bound) {
                    bound = false
                    true
                } else {
                    false
                }
            }
            if (unbindNow) unbind()
        }

        private fun unbind() {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun namedDaemonThreadFactory(
        prefix: String,
        counter: AtomicInteger,
    ): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

}
