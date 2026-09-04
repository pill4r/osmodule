package dev.pillar.osmodule.modules

import android.content.Context
import android.content.pm.PackageManager

data class ModuleLoadFailure(val entryPoint: String, val reason: String)

data class ModuleCatalog(
    val modules: List<ModuleDescriptor>,
    val failures: List<ModuleLoadFailure>,
)

/**
 * Process-wide module management plane.
 *
 * In-process feature entry points are discovered from merged manifest metadata. External APKs are
 * never class-loaded here; the Base app talks to them through the separate AIDL plugin registry.
 */
object ModuleRegistry {
    const val METADATA_PREFIX = "dev.pillar.osmodule.module."

    private val lock = Any()
    private val descriptors = linkedMapOf<String, ModuleDescriptor>()
    private val services = linkedMapOf<Class<*>, Any>()
    private val loadFailures = mutableListOf<ModuleLoadFailure>()

    @Volatile
    private var initialized = false

    fun initialize(context: Context): ModuleCatalog = synchronized(lock) {
        if (initialized) return@synchronized catalogLocked()

        for (entryPoint in manifestEntryPoints(context)) {
            runCatching {
                val type = Class.forName(entryPoint)
                val module = type.getDeclaredConstructor().newInstance() as? AppModule
                    ?: error("$entryPoint does not implement AppModule")
                installLocked(module)
            }.onFailure { error ->
                loadFailures += ModuleLoadFailure(
                    entryPoint = entryPoint,
                    reason = error.message ?: error.javaClass.simpleName,
                )
            }
        }
        initialized = true
        catalogLocked()
    }

    fun catalog(): ModuleCatalog = synchronized(lock) { catalogLocked() }

    /** Install an in-process adapter, such as the Base-side proxy for external AIDL plugins. */
    fun install(module: AppModule): ModuleCatalog = synchronized(lock) {
        installLocked(module)
        catalogLocked()
    }

    fun <T : Any> capability(type: Class<T>): T? = synchronized(lock) {
        @Suppress("UNCHECKED_CAST")
        services[type] as? T
    }

    inline fun <reified T : Any> capability(): T? = capability(T::class.java)

    private fun installLocked(module: AppModule) {
        val descriptor = module.descriptor
        require(descriptor.id.isNotBlank()) { "Module id must not be blank" }
        require(descriptor.id !in descriptors) { "Duplicate module id: ${descriptor.id}" }

        val staged = linkedMapOf<Class<*>, Any>()
        module.install(object : ModuleScope {
            override fun <T : Any> bind(type: Class<T>, service: T) {
                require(type.isInstance(service)) { "${service.javaClass.name} is not a ${type.name}" }
                require(type !in staged && type !in services) { "Duplicate capability binding: ${type.name}" }
                staged[type] = service
            }
        })
        descriptors[descriptor.id] = descriptor
        services.putAll(staged)
    }

    private fun catalogLocked() = ModuleCatalog(descriptors.values.toList(), loadFailures.toList())

    @Suppress("DEPRECATION")
    private fun manifestEntryPoints(context: Context): List<String> {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val metadata = appInfo.metaData ?: return emptyList()
        return metadata.keySet()
            .asSequence()
            .filter { it.startsWith(METADATA_PREFIX) }
            .sorted()
            .mapNotNull { metadata.getString(it) }
            .distinct()
            .toList()
    }
}
