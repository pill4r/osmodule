package dev.pillar.osmodule.rsdk

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Linearization barrier for a transport whose worker creates resources asynchronously.
 *
 * Registration and short wire writes share the close lock. Thus a resource is either registered
 * before close (and included in close's snapshot) or rejected and closed itself. A wire write either
 * finishes before close linearizes or is rejected; no worker can successfully register/send after
 * the owning camera lease has been released by the caller of [close].
 */
internal class TransportCloseBarrier {
    private val lock = Any()
    private var closed = false
    private val resources = Collections.newSetFromMap(IdentityHashMap<AutoCloseable, Boolean>())

    val isClosed: Boolean get() = synchronized(lock) { closed }

    fun register(resource: AutoCloseable): Boolean {
        val accepted = synchronized(lock) {
            if (closed) false else resources.add(resource)
        }
        if (!accepted) runCatching { resource.close() }
        return accepted
    }

    /** Call only after the resource itself has been closed. */
    fun unregister(resource: AutoCloseable) {
        synchronized(lock) { resources.remove(resource) }
    }

    /** Runs a short operation on the open side of the close linearization point. */
    fun runIfOpen(operation: () -> Unit): Boolean = synchronized(lock) {
        if (closed) false
        else {
            operation()
            true
        }
    }

    /** @return true only for the call that changed the barrier from open to closed. */
    fun close(): Boolean {
        val detached = synchronized(lock) {
            if (closed) return false
            closed = true
            resources.toList().also { resources.clear() }
        }
        detached.forEach { runCatching { it.close() } }
        return true
    }
}
