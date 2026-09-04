package dev.konraditurbe.osmosis.ui

/**
 * Linearizes one pending resource and its promoted active resource across reconnect generations.
 *
 * A generation change detaches both resources while holding the same lock used by publication. A
 * worker that arrives late therefore either published before invalidation (and is detached there) or
 * observes the new generation and releases its own resource; it can never publish into the gap after
 * teardown.
 */
internal class GenerationResourceSlot<T : Any>(
    private val release: (T) -> Unit,
) {
    private val lock = Any()
    private var generation = 0
    private var pending: T? = null
    private var active: T? = null

    /** Starts a new generation and releases resources detached from the previous one. */
    fun begin(): Int {
        val detached: List<T>
        val next: Int
        synchronized(lock) {
            next = ++generation
            val oldPending = pending
            val oldActive = active
            pending = null
            active = null
            detached = buildList {
                oldPending?.let(::add)
                if (oldActive != null && oldActive !== oldPending) add(oldActive)
            }
        }
        detached.forEach(release)
        return next
    }

    fun isCurrent(expectedGeneration: Int): Boolean = synchronized(lock) {
        generation == expectedGeneration
    }

    /** Publishes a worker-owned resource only if its generation is still current. */
    fun installPending(expectedGeneration: Int, resource: T): Boolean {
        val installed = synchronized(lock) {
            if (generation != expectedGeneration || pending != null) false
            else {
                pending = resource
                true
            }
        }
        if (!installed) release(resource)
        return installed
    }

    /** Removes and releases [resource] without disturbing a newer pending/active resource. */
    fun discard(resource: T) {
        val owned = synchronized(lock) {
            val matches = pending === resource || active === resource
            if (pending === resource) pending = null
            if (active === resource) active = null
            matches
        }
        if (owned) release(resource)
    }

    /**
     * Atomically performs the final setup and promotes [resource]. [onPromoted] runs under the
     * publication lock so invalidation cannot detach ownership between setup and publication.
     */
    fun promote(
        expectedGeneration: Int,
        resource: T,
        onPromoted: (T) -> Unit = {},
    ): Boolean {
        var promoted = false
        var failure: Throwable? = null
        synchronized(lock) {
            if (generation == expectedGeneration && pending === resource) {
                try {
                    onPromoted(resource)
                    pending = null
                    active = resource
                    promoted = true
                } catch (error: Throwable) {
                    pending = null
                    failure = error
                }
            }
        }
        if (!promoted) release(resource)
        failure?.let { throw it }
        return promoted
    }

    fun active(): T? = synchronized(lock) { active }
}
