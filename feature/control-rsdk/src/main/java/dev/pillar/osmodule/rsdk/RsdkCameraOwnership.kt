package dev.pillar.osmodule.rsdk

import android.content.Context
import dev.pillar.osmodule.modules.CameraSessionOwnerAcquire
import dev.pillar.osmodule.modules.CameraSessionOwnerClient
import dev.pillar.osmodule.modules.CameraSessionOwnerLease
import dev.pillar.osmodule.modules.CameraSessionOwnerResult
import dev.pillar.osmodule.session.CameraLeaseResult
import dev.pillar.osmodule.session.CameraSessionCoordinator
import dev.pillar.osmodule.session.CameraSessionLease
import dev.pillar.osmodule.session.CameraSessionPurpose
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reference-counted ownership shared by Osmo 360 RC's BLE control and Wi-Fi preview transports.
 *
 * Provider IPC always runs through [CameraSessionOwnerClient]'s bounded background worker. Pending
 * callers for the same camera share that acquisition. Cancellation removes the caller before a late
 * grant can open a transport; the final active consumer closes the local guard first and then queues
 * the cross-process release.
 */
internal object RsdkCameraOwnership {
    sealed interface Result {
        data class Granted(val lease: Lease) : Result
        data class Busy(val reason: String) : Result
    }

    interface Request : AutoCloseable {
        fun cancel()
        override fun close() = cancel()
    }

    class Lease internal constructor(
        private val token: Long,
        val cameraAddress: String,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release(token)
        }
    }

    private data class Active(
        val cameraAddress: String,
        val processLease: CameraSessionOwnerLease,
        val localLease: CameraSessionLease,
        val consumers: MutableSet<Long>,
    )

    private data class Pending(
        val generation: Long,
        val cameraAddress: String,
        val waiters: MutableMap<Long, ResultDelivery>,
        var upstream: CameraSessionOwnerAcquire? = null,
    )

    private val lock = Any()
    private var nextToken = 1L
    private var nextGeneration = 1L
    private var active: Active? = null
    private var pending: Pending? = null

    fun acquireAsync(
        context: Context,
        cameraAddress: String,
        callback: (Result) -> Unit,
    ): Request {
        val normalized = cameraAddress.trim().uppercase(Locale.ROOT)
        val delivery = ResultDelivery(context.mainExecutor, callback)
        if (normalized.isBlank()) {
            delivery.complete(Result.Busy("Invalid camera address"))
            return RequestHandle(null, -1L, delivery)
        }

        val waiterId: Long
        var pendingGeneration: Long? = null
        var start: Pending? = null
        var immediate: Result? = null
        synchronized(lock) {
            waiterId = nextToken++
            active?.let { current ->
                immediate = if (current.cameraAddress == normalized) {
                    current.consumers += waiterId
                    Result.Granted(Lease(waiterId, normalized))
                } else {
                    Result.Busy("Osmo 360 RC is already using ${current.cameraAddress}")
                }
                return@synchronized
            }

            pending?.let { acquiring ->
                immediate = if (acquiring.cameraAddress == normalized) {
                    acquiring.waiters[waiterId] = delivery
                    pendingGeneration = acquiring.generation
                    null
                } else {
                    Result.Busy("Osmo 360 RC is already opening ${acquiring.cameraAddress}")
                }
                return@synchronized
            }

            val acquiring = Pending(
                generation = nextGeneration++,
                cameraAddress = normalized,
                waiters = linkedMapOf(waiterId to delivery),
            )
            pending = acquiring
            pendingGeneration = acquiring.generation
            start = acquiring
        }

        val handle = RequestHandle(pendingGeneration, waiterId, delivery)
        immediate?.let(delivery::complete)

        start?.let { acquiring ->
            val upstream = CameraSessionOwnerClient.acquireAsync(
                context = context.applicationContext,
                ownerId = OWNER_ID,
                cameraAddress = normalized,
                purpose = CameraSessionPurpose.RSDK_CONTROL.name,
            ) { result ->
                completePending(acquiring.generation, result)
            }
            val retain = synchronized(lock) {
                pending?.takeIf { it.generation == acquiring.generation }?.also {
                    it.upstream = upstream
                } != null
            }
            if (!retain) upstream.cancel()
        }
        return handle
    }

    private fun completePending(
        generation: Long,
        ownerResult: CameraSessionOwnerResult,
    ) {
        val deliveries = mutableListOf<Pair<ResultDelivery, Result>>()
        var abandonedOwner: CameraSessionOwnerLease? = null
        synchronized(lock) {
            val acquiring = pending?.takeIf { it.generation == generation }
            if (acquiring == null) {
                abandonedOwner = (ownerResult as? CameraSessionOwnerResult.Granted)?.lease
                return@synchronized
            }
            pending = null
            val waiters = acquiring.waiters.values.filter(ResultDelivery::isPending)
            if (waiters.isEmpty()) {
                abandonedOwner = (ownerResult as? CameraSessionOwnerResult.Granted)?.lease
                return@synchronized
            }

            when (ownerResult) {
                is CameraSessionOwnerResult.Busy -> {
                    val reason = "Camera is busy in ${ownerResult.active.purpose.lowercase().replace('_', ' ')} mode"
                    waiters.forEach { deliveries += it to Result.Busy(reason) }
                }

                is CameraSessionOwnerResult.Unavailable -> {
                    val reason = "Camera ownership check failed: ${ownerResult.reason}"
                    waiters.forEach { deliveries += it to Result.Busy(reason) }
                }

                is CameraSessionOwnerResult.Granted -> {
                    val localLease = when (val acquired = CameraSessionCoordinator.acquire(
                        ownerId = OWNER_ID,
                        cameraAddress = acquiring.cameraAddress,
                        purpose = CameraSessionPurpose.RSDK_CONTROL,
                    )) {
                        is CameraLeaseResult.Granted -> acquired.lease
                        is CameraLeaseResult.Busy -> {
                            abandonedOwner = ownerResult.lease
                            val reason = "Camera is busy in ${acquired.active.purpose.name.lowercase().replace('_', ' ')} mode"
                            waiters.forEach { deliveries += it to Result.Busy(reason) }
                            null
                        }
                    }
                    if (localLease != null) {
                        val consumerTokens = waiters.map { nextToken++ }
                        active = Active(
                            cameraAddress = acquiring.cameraAddress,
                            processLease = ownerResult.lease,
                            localLease = localLease,
                            consumers = consumerTokens.toMutableSet(),
                        )
                        waiters.zip(consumerTokens).forEach { (waiter, token) ->
                            deliveries += waiter to Result.Granted(Lease(token, acquiring.cameraAddress))
                        }
                    }
                }
            }
        }

        abandonedOwner?.close()
        deliveries.forEach { (delivery, result) -> delivery.complete(result) }
    }

    private fun cancelPending(generation: Long?, waiterId: Long) {
        if (generation == null) return
        val upstream = synchronized(lock) {
            val acquiring = pending?.takeIf { it.generation == generation } ?: return@synchronized null
            acquiring.waiters.remove(waiterId)
            if (acquiring.waiters.isNotEmpty()) return@synchronized null
            pending = null
            acquiring.upstream
        }
        upstream?.cancel()
    }

    private fun release(token: Long) {
        val released = synchronized(lock) {
            val current = active ?: return@synchronized null
            if (!current.consumers.remove(token) || current.consumers.isNotEmpty()) {
                return@synchronized null
            }
            active = null
            current
        } ?: return
        released.localLease.close()
        released.processLease.close()
    }

    private class RequestHandle(
        private val generation: Long?,
        private val waiterId: Long,
        private val delivery: ResultDelivery,
    ) : Request {
        private val cancelled = AtomicBoolean(false)

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            delivery.cancel()
            cancelPending(generation, waiterId)
        }
    }

    /** Queued callback cancellation disposes an undelivered lease instead of leaking ownership. */
    private class ResultDelivery(
        private val executor: Executor,
        private val callback: (Result) -> Unit,
    ) {
        private enum class State { PENDING, QUEUED, DELIVERED, CANCELLED }

        private val lock = Any()
        private var state = State.PENDING
        private var queued: Result? = null

        fun isPending(): Boolean = synchronized(lock) { state == State.PENDING }

        fun complete(value: Result) {
            val accepted = synchronized(lock) {
                if (state != State.PENDING) false else {
                    state = State.QUEUED
                    queued = value
                    true
                }
            }
            if (!accepted) {
                dispose(value)
                return
            }
            try {
                executor.execute(::deliver)
            } catch (_: RuntimeException) {
                cancel()
            }
        }

        fun cancel() {
            val abandoned = synchronized(lock) {
                if (state == State.CANCELLED || state == State.DELIVERED) return@synchronized null
                state = State.CANCELLED
                queued.also { queued = null }
            }
            abandoned?.let(::dispose)
        }

        private fun deliver() {
            val value = synchronized(lock) {
                if (state != State.QUEUED) return
                state = State.DELIVERED
                queued.also { queued = null }
            } ?: return
            try {
                callback(value)
            } catch (_: RuntimeException) {
                dispose(value)
            }
        }

        private fun dispose(value: Result) {
            (value as? Result.Granted)?.lease?.close()
        }
    }

    private const val OWNER_ID = "osmo360-rc"
}
