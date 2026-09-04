package dev.pillar.osmodule.modules

import android.content.ContentProviderClient
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stable IPC contract for the Base-owned camera-session arbiter.
 *
 * The provider is deliberately outside the plugin Binder protocol: adding this contract does not
 * change the existing plugin service ABI. Access is restricted by a signature permission in Base.
 */
object CameraSessionOwnerContract {
    const val AUTHORITY = "dev.pillar.osmodule.camera-session"
    const val PERMISSION = "dev.pillar.osmodule.permission.CAMERA_SESSION"

    internal const val METHOD_ACQUIRE = "acquire"
    internal const val METHOD_RELEASE = "release"

    internal const val KEY_RESULT = "result"
    internal const val RESULT_GRANTED = "granted"
    internal const val RESULT_BUSY = "busy"
    internal const val RESULT_ERROR = "error"
    internal const val KEY_LEASE_ID = "lease_id"
    internal const val KEY_OWNER_TOKEN = "owner_token"
    internal const val KEY_OWNER_ID = "owner_id"
    internal const val KEY_CAMERA_ADDRESS = "camera_address"
    internal const val KEY_PURPOSE = "purpose"
    internal const val KEY_ERROR = "error"
    internal const val KEY_RELEASED = "released"

    @JvmField
    val URI: Uri = Uri.parse("content://$AUTHORITY")
}

data class CameraSessionOwnerSnapshot(
    val ownerId: String,
    val cameraAddress: String,
    val purpose: String,
)

sealed interface CameraSessionOwnerResult {
    data class Granted(val lease: CameraSessionOwnerLease) : CameraSessionOwnerResult
    data class Busy(val active: CameraSessionOwnerSnapshot) : CameraSessionOwnerResult

    /** The arbiter could not prove exclusive ownership, so the caller must not open a transport. */
    data class Unavailable(val reason: String) : CameraSessionOwnerResult
}

/** A process-owner token retained together with a stable provider reference for the lease lifetime. */
class CameraSessionOwnerLease internal constructor(
    private val provider: ContentProviderClient,
    private val ownerToken: IBinder,
    private val leaseId: Long,
    val snapshot: CameraSessionOwnerSnapshot,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        CameraSessionOwnerClient.releaseAsync(provider, ownerToken, leaseId)
    }
}

/** Cancellation handle for an asynchronous cross-process ownership request. */
interface CameraSessionOwnerAcquire : AutoCloseable {
    fun cancel()
    override fun close() = cancel()
}

/**
 * Acquires the one cross-process camera transport slot from Base.
 *
 * A null provider, permission error, malformed response, or Binder failure is an unavailable result,
 * never an implicit grant. Each successful call uses a fresh Binder token so owner-process death
 * automatically releases the corresponding provider-side lease.
 */
object CameraSessionOwnerClient {
    /**
     * Runs provider acquisition away from the caller thread and reports on [callbackExecutor].
     *
     * Timeout or cancellation only abandons delivery; Binder itself has no cancellable provider-call
     * API. If that call later returns a grant, the delivery gate immediately releases it instead of
     * allowing a stale Activity/session generation to start a transport.
     */
    fun acquireAsync(
        context: Context,
        ownerId: String,
        cameraAddress: String,
        purpose: String,
        timeoutMs: Long = ACQUIRE_TIMEOUT_MS,
        callbackExecutor: Executor = context.mainExecutor,
        callback: (CameraSessionOwnerResult) -> Unit,
    ): CameraSessionOwnerAcquire {
        val delivery = CancellableResultDelivery(
            executor = callbackExecutor,
            dispose = ::disposeResult,
            callback = callback,
        )
        val operation = AsyncAcquireOperation(delivery)
        if (ownerId.isBlank() || cameraAddress.isBlank() || purpose.isBlank()) {
            delivery.complete(CameraSessionOwnerResult.Unavailable("Invalid camera ownership request"))
            return operation
        }

        val timeout = timeoutWorker.schedule({
            if (delivery.complete(CameraSessionOwnerResult.Unavailable("Camera ownership check timed out"))) {
                operation.interruptWorker()
            }
        }, timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        operation.attachTimeout(timeout)

        try {
            operation.attachWorker(acquireWorker.submit {
                val result = acquireBlocking(context, ownerId, cameraAddress, purpose)
                if (delivery.complete(result)) operation.cancelTimeout()
            })
        } catch (_: RejectedExecutionException) {
            operation.cancelTimeout()
            delivery.complete(CameraSessionOwnerResult.Unavailable("Camera ownership worker is busy"))
        }
        return operation
    }

    private fun acquireBlocking(
        context: Context,
        ownerId: String,
        cameraAddress: String,
        purpose: String,
    ): CameraSessionOwnerResult {

        val provider = runCatching {
            context.applicationContext.contentResolver.acquireContentProviderClient(
                CameraSessionOwnerContract.URI,
            )
        }.getOrNull() ?: return CameraSessionOwnerResult.Unavailable(
            "Base camera-session arbiter is unavailable",
        )
        val ownerToken = Binder()
        val request = Bundle().apply {
            putString(CameraSessionOwnerContract.KEY_OWNER_ID, ownerId)
            putString(CameraSessionOwnerContract.KEY_CAMERA_ADDRESS, cameraAddress)
            putString(CameraSessionOwnerContract.KEY_PURPOSE, purpose)
            putBinder(CameraSessionOwnerContract.KEY_OWNER_TOKEN, ownerToken)
        }

        val response = try {
            provider.call(CameraSessionOwnerContract.METHOD_ACQUIRE, null, request)
        } catch (error: Exception) {
            provider.close()
            return CameraSessionOwnerResult.Unavailable(
                error.message ?: "Base camera-session arbiter call failed",
            )
        }

        return when (response?.getString(CameraSessionOwnerContract.KEY_RESULT)) {
            CameraSessionOwnerContract.RESULT_GRANTED -> {
                val leaseId = response.getLong(CameraSessionOwnerContract.KEY_LEASE_ID, 0L)
                if (leaseId <= 0L) {
                    provider.close()
                    CameraSessionOwnerResult.Unavailable("Invalid camera-session lease response")
                } else {
                    CameraSessionOwnerResult.Granted(
                        CameraSessionOwnerLease(
                            provider = provider,
                            ownerToken = ownerToken,
                            leaseId = leaseId,
                            snapshot = CameraSessionOwnerSnapshot(
                                ownerId = ownerId,
                                cameraAddress = cameraAddress.uppercase(Locale.ROOT),
                                purpose = purpose,
                            ),
                        ),
                    )
                }
            }

            CameraSessionOwnerContract.RESULT_BUSY -> {
                val activeOwner = response.getString(CameraSessionOwnerContract.KEY_OWNER_ID)
                val activeAddress = response.getString(CameraSessionOwnerContract.KEY_CAMERA_ADDRESS)
                val activePurpose = response.getString(CameraSessionOwnerContract.KEY_PURPOSE)
                provider.close()
                if (activeOwner.isNullOrBlank() || activeAddress.isNullOrBlank() || activePurpose.isNullOrBlank()) {
                    CameraSessionOwnerResult.Unavailable("Invalid camera-session busy response")
                } else {
                    CameraSessionOwnerResult.Busy(
                        CameraSessionOwnerSnapshot(activeOwner, activeAddress, activePurpose),
                    )
                }
            }

            CameraSessionOwnerContract.RESULT_ERROR -> {
                val reason = response.getString(CameraSessionOwnerContract.KEY_ERROR)
                    ?: "Camera-session arbiter rejected the request"
                provider.close()
                CameraSessionOwnerResult.Unavailable(reason)
            }

            else -> {
                provider.close()
                CameraSessionOwnerResult.Unavailable("Invalid camera-session arbiter response")
            }
        }
    }

    internal fun releaseAsync(
        provider: ContentProviderClient,
        ownerToken: IBinder,
        leaseId: Long,
    ) {
        // Capturing ownerToken keeps the death-linked Binder alive until the release call finishes.
        releaseWorker.execute {
            try {
                provider.call(
                    CameraSessionOwnerContract.METHOD_RELEASE,
                    null,
                    Bundle().apply {
                        putLong(CameraSessionOwnerContract.KEY_LEASE_ID, leaseId)
                        putBinder(CameraSessionOwnerContract.KEY_OWNER_TOKEN, ownerToken)
                    },
                )
            } catch (_: Exception) {
                // Fail closed: Base remains busy until Binder death if release cannot be confirmed.
            } finally {
                provider.close()
            }
        }
    }

    private fun disposeResult(result: CameraSessionOwnerResult) {
        if (result is CameraSessionOwnerResult.Granted) result.lease.close()
    }

    private const val ACQUIRE_TIMEOUT_MS = 5_000L
    private const val ACQUIRE_THREADS = 2
    private const val ACQUIRE_QUEUE_CAPACITY = 16

    private val threadNumber = AtomicInteger()
    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "camera-owner-ipc-${threadNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val acquireWorker = ThreadPoolExecutor(
        ACQUIRE_THREADS,
        ACQUIRE_THREADS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(ACQUIRE_QUEUE_CAPACITY),
        threadFactory,
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val releaseWorker = Executors.newCachedThreadPool(threadFactory)
    private val timeoutWorker = ScheduledThreadPoolExecutor(1, threadFactory).apply {
        removeOnCancelPolicy = true
    }

}

private class AsyncAcquireOperation(
    private val delivery: CancellableResultDelivery<CameraSessionOwnerResult>,
) : CameraSessionOwnerAcquire {
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private var worker: Future<*>? = null
    private var timeout: ScheduledFuture<*>? = null

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        delivery.cancel()
        synchronized(lock) {
            worker?.cancel(true)
            timeout?.cancel(false)
        }
    }

    fun attachWorker(next: Future<*>) = synchronized(lock) {
        worker = next
        if (cancelled.get() || delivery.isSettled()) next.cancel(true)
    }

    fun attachTimeout(next: ScheduledFuture<*>) = synchronized(lock) {
        timeout = next
        if (cancelled.get() || delivery.isSettled()) next.cancel(false)
    }

    fun interruptWorker() = synchronized(lock) { worker?.cancel(true) }
    fun cancelTimeout() = synchronized(lock) { timeout?.cancel(false) }
}

/** A cancellation-safe delivery gate used by the IPC client and exercised in plain JVM tests. */
internal class CancellableResultDelivery<T : Any>(
    private val executor: Executor,
    private val dispose: (T) -> Unit,
    private val callback: (T) -> Unit,
) {
    private enum class State { PENDING, QUEUED, DELIVERED, CANCELLED }

    private val lock = Any()
    private var state = State.PENDING
    private var queued: T? = null

    fun complete(value: T): Boolean {
        val accepted = synchronized(lock) {
            if (state != State.PENDING) false
            else {
                state = State.QUEUED
                queued = value
                true
            }
        }
        if (!accepted) {
            dispose(value)
            return false
        }
        try {
            executor.execute(::deliver)
        } catch (_: RuntimeException) {
            cancel()
        }
        return true
    }

    fun cancel() {
        val abandoned = synchronized(lock) {
            if (state == State.CANCELLED || state == State.DELIVERED) return@synchronized null
            state = State.CANCELLED
            queued.also { queued = null }
        }
        abandoned?.let(dispose)
    }

    fun isSettled(): Boolean = synchronized(lock) { state != State.PENDING }

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
}

/** Token abstraction keeps the atomic arbitration rules executable in plain JVM tests. */
internal interface CameraSessionDeathToken {
    fun matches(other: CameraSessionDeathToken): Boolean
    fun linkToDeath(recipient: () -> Unit): Boolean
    fun unlinkToDeath()
}

internal data class CameraSessionOwnerRequest(
    val ownerId: String,
    val cameraAddress: String,
    val purpose: String,
)

internal sealed interface CameraSessionArbiterResult {
    data class Granted(val leaseId: Long) : CameraSessionArbiterResult
    data class Busy(val active: CameraSessionOwnerRequest) : CameraSessionArbiterResult
    data object Rejected : CameraSessionArbiterResult
}

/** The provider's single atomic check-and-set state machine. */
internal class CameraSessionArbiter {
    private data class Active(
        val leaseId: Long,
        val request: CameraSessionOwnerRequest,
        val token: CameraSessionDeathToken,
    )

    private val lock = Any()
    private var nextLeaseId = 1L
    private var active: Active? = null

    fun acquire(
        request: CameraSessionOwnerRequest,
        token: CameraSessionDeathToken,
    ): CameraSessionArbiterResult = synchronized(lock) {
        active?.let { return@synchronized CameraSessionArbiterResult.Busy(it.request) }

        val leaseId = nextLeaseId++
        val candidate = Active(leaseId, request, token)
        active = candidate
        val linked = runCatching {
            token.linkToDeath { ownerDied(leaseId, token) }
        }.getOrDefault(false)

        // A token may die while linkToDeath is being installed. ownerDied uses this same lock, so
        // either it clears candidate before this check or it runs immediately after we return.
        if (!linked || active !== candidate) {
            if (active === candidate) active = null
            runCatching { token.unlinkToDeath() }
            CameraSessionArbiterResult.Rejected
        } else {
            CameraSessionArbiterResult.Granted(leaseId)
        }
    }

    fun release(leaseId: Long, token: CameraSessionDeathToken): Boolean = synchronized(lock) {
        val current = active ?: return@synchronized false
        if (current.leaseId != leaseId || !current.token.matches(token)) return@synchronized false
        active = null
        runCatching { current.token.unlinkToDeath() }
        true
    }

    fun current(): CameraSessionOwnerRequest? = synchronized(lock) { active?.request }

    private fun ownerDied(leaseId: Long, token: CameraSessionDeathToken) = synchronized(lock) {
        val current = active ?: return@synchronized
        if (current.leaseId == leaseId && current.token.matches(token)) active = null
    }
}

internal class BinderCameraSessionDeathToken(private val binder: IBinder) : CameraSessionDeathToken {
    private var deathRecipient: IBinder.DeathRecipient? = null

    override fun matches(other: CameraSessionDeathToken): Boolean =
        other is BinderCameraSessionDeathToken && binder === other.binder

    override fun linkToDeath(recipient: () -> Unit): Boolean {
        if (!binder.isBinderAlive) return false
        val binderRecipient = IBinder.DeathRecipient(recipient)
        binder.linkToDeath(binderRecipient, 0)
        deathRecipient = binderRecipient
        return binder.isBinderAlive
    }

    override fun unlinkToDeath() {
        val recipient = deathRecipient ?: return
        deathRecipient = null
        binder.unlinkToDeath(recipient, 0)
    }
}
