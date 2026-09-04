package dev.konraditurbe.osmosis.plugins

/** Terminal result for one asynchronous plugin IPC operation. */
internal sealed interface PluginAsyncResult<out T> {
    data class Success<T>(val value: T) : PluginAsyncResult<T>
    data class Failure(val message: String) : PluginAsyncResult<Nothing>
    data object Cancelled : PluginAsyncResult<Nothing>
}

/** Failure UI is advisory; it must never suppress the launch completion signal. */
internal fun deliverPluginPanelFailure(
    message: String,
    onFailure: (String) -> Unit,
    onComplete: (Boolean) -> Unit,
    onCallbackError: (Throwable) -> Unit = {},
) {
    runCatching { onFailure(message) }.onFailure(onCallbackError)
    onComplete(false)
}

/**
 * Small, Android-free state machine shared by bootstrap, Binder and PendingIntent dispatch.
 *
 * No external action runs under the state lock. [proceedBefore] checks again after its action so a
 * concurrent cancellation can finish immediately and suppress continuation. [succeedBefore]
 * atomically reserves the irreversible dispatch first; once reserved, cancellation is too late to
 * unsend it and returns immediately without changing its eventual result.
 */
internal class PluginAsyncOperation<T>(
    val generation: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    onTerminal: (PluginAsyncResult<T>) -> Unit,
) {
    private val lock = Any()
    private var state = State.ACTIVE
    private var terminalCallback: ((PluginAsyncResult<T>) -> Unit)? = onTerminal
    private val cleanups = ArrayList<() -> Unit>()

    fun isActive(): Boolean = synchronized(lock) { state == State.ACTIVE }

    fun addCleanup(cleanup: () -> Unit) {
        val runNow = synchronized(lock) {
            if (state != State.ACTIVE) true else {
                cleanups += cleanup
                false
            }
        }
        if (runNow) runCatching(cleanup)
    }

    fun cancel(): Boolean = finish(PluginAsyncResult.Cancelled)

    fun fail(message: String): Boolean = finish(PluginAsyncResult.Failure(message))

    fun succeed(value: T): Boolean = finish(PluginAsyncResult.Success(value))

    /** Drops Activity-facing completion state after an irreversible dispatch was superseded. */
    fun detachTerminalCallback() {
        synchronized(lock) {
            if (state == State.DISPATCHING) terminalCallback = null
        }
    }

    /** Runs a short non-terminal action only while this operation is current and before deadline. */
    fun proceedBefore(
        deadlineNanos: Long,
        timeoutMessage: String,
        failureMessage: String,
        action: () -> Unit,
    ): Boolean {
        if (!checkActiveBefore(deadlineNanos, timeoutMessage)) return false
        val actionResult = runCatching(action)
        var delivery: TerminalDelivery<T>? = null
        val proceeded = synchronized(lock) {
            when {
                state != State.ACTIVE -> false
                nanoTime() >= deadlineNanos -> {
                    delivery = finishLocked(PluginAsyncResult.Failure(timeoutMessage))
                    false
                }
                actionResult.isFailure -> {
                    val error = actionResult.exceptionOrNull()
                    delivery = finishLocked(
                        PluginAsyncResult.Failure(error?.message ?: failureMessage),
                    )
                    false
                }
                else -> true
            }
        }
        delivery?.deliver()
        return proceeded
    }

    /**
     * Runs a short final action and publishes success atomically with respect to cancellation.
     * This is the only path used to send a delayed plugin PendingIntent.
     */
    fun succeedBefore(
        deadlineNanos: Long,
        timeoutMessage: String,
        failureMessage: String,
        value: T,
        action: () -> Unit = {},
    ): Boolean {
        var delivery: TerminalDelivery<T>? = null
        var dispatchCleanups: List<() -> Unit> = emptyList()
        val reserved = synchronized(lock) {
            when {
                state != State.ACTIVE -> false
                nanoTime() >= deadlineNanos -> {
                    delivery = finishLocked(PluginAsyncResult.Failure(timeoutMessage))
                    false
                }
                else -> {
                    // This reservation is the launch's linearization point. From here the external
                    // dispatch is considered started and cannot truthfully be cancelled.
                    state = State.DISPATCHING
                    dispatchCleanups = cleanups.toList()
                    cleanups.clear()
                    true
                }
            }
        }
        delivery?.deliver()
        if (!reserved) return false

        // The Binder result is already local and the dispatch cannot be revoked. Release the
        // service binding and timeout before invoking PendingIntent.send(), which itself is an
        // external call that could stall indefinitely.
        dispatchCleanups.forEach { cleanup -> runCatching(cleanup) }
        val actionResult = runCatching(action)
        delivery = synchronized(lock) {
            if (state != State.DISPATCHING) {
                null
            } else if (actionResult.isSuccess) {
                finishDispatchLocked(PluginAsyncResult.Success(value))
            } else {
                val error = actionResult.exceptionOrNull()
                finishDispatchLocked(
                    PluginAsyncResult.Failure(error?.message ?: failureMessage),
                )
            }
        }
        delivery?.deliver()
        val succeeded = actionResult.isSuccess
        return succeeded
    }

    private fun checkActiveBefore(deadlineNanos: Long, timeoutMessage: String): Boolean {
        var delivery: TerminalDelivery<T>? = null
        val active = synchronized(lock) {
            when {
                state != State.ACTIVE -> false
                nanoTime() >= deadlineNanos -> {
                    delivery = finishLocked(PluginAsyncResult.Failure(timeoutMessage))
                    false
                }
                else -> true
            }
        }
        delivery?.deliver()
        return active
    }

    private fun finish(result: PluginAsyncResult<T>): Boolean {
        val delivery = synchronized(lock) { finishLocked(result) } ?: return false
        delivery.deliver()
        return true
    }

    private fun finishLocked(result: PluginAsyncResult<T>): TerminalDelivery<T>? {
        if (state != State.ACTIVE) return null
        state = State.TERMINAL
        return terminalDeliveryLocked(result)
    }

    private fun finishDispatchLocked(result: PluginAsyncResult<T>): TerminalDelivery<T>? {
        if (state != State.DISPATCHING) return null
        state = State.TERMINAL
        return terminalDeliveryLocked(result)
    }

    private fun terminalDeliveryLocked(result: PluginAsyncResult<T>): TerminalDelivery<T> {
        val callback = terminalCallback
        terminalCallback = null
        val terminalCleanups = cleanups.toList()
        cleanups.clear()
        return TerminalDelivery(result, callback, terminalCleanups)
    }

    private data class TerminalDelivery<T>(
        val result: PluginAsyncResult<T>,
        val callback: ((PluginAsyncResult<T>) -> Unit)?,
        val cleanups: List<() -> Unit>,
    ) {
        fun deliver() {
            cleanups.forEach { cleanup -> runCatching(cleanup) }
            callback?.invoke(result)
        }
    }

    private enum class State {
        ACTIVE,
        DISPATCHING,
        TERMINAL,
    }
}

/** One current operation plus a monotonically increasing generation. */
internal class PluginAsyncOperationSlot<T>(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var nextGeneration = 0L
    private var current: PluginAsyncOperation<T>? = null

    fun begin(onTerminal: (PluginAsyncResult<T>) -> Unit): PluginAsyncOperation<T> {
        val previous: PluginAsyncOperation<T>?
        val operation: PluginAsyncOperation<T>
        synchronized(lock) {
            previous = current
            val generation = ++nextGeneration
            operation = PluginAsyncOperation(generation, nanoTime) { result ->
                synchronized(lock) {
                    if (current?.generation == generation) current = null
                }
                onTerminal(result)
            }
            current = operation
        }
        previous?.let { old ->
            if (!old.cancel()) old.detachTerminalCallback()
        }
        return operation
    }

    fun cancel() {
        val operation = synchronized(lock) {
            nextGeneration++
            current.also { current = null }
        }
        operation?.let { pending ->
            if (!pending.cancel()) pending.detachTerminalCallback()
        }
    }

    fun currentGeneration(): Long? = synchronized(lock) { current?.generation }
}
