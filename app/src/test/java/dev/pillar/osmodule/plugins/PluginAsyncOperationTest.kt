package dev.pillar.osmodule.plugins

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAsyncOperationTest {
    @Test
    fun failureCallbackCannotSuppressCompletion() {
        val completion = AtomicReference<Boolean>()
        val callbackError = AtomicReference<Throwable>()

        deliverPluginPanelFailure(
            message = "broken",
            onFailure = { error("UI callback failed") },
            onComplete = completion::set,
            onCallbackError = callbackError::set,
        )

        assertEquals(false, completion.get())
        assertEquals("UI callback failed", callbackError.get().message)
    }

    @Test
    fun cancellationPreventsLateDispatchAndCompletesOnce() {
        val results = mutableListOf<PluginAsyncResult<Unit>>()
        val cleanups = AtomicInteger()
        val dispatches = AtomicInteger()
        val operation = PluginAsyncOperation<Unit>(generation = 7L) { results += it }
        operation.addCleanup { cleanups.incrementAndGet() }

        assertTrue(operation.cancel())
        assertFalse(operation.cancel())
        assertFalse(
            operation.succeedBefore(
                deadlineNanos = Long.MAX_VALUE,
                timeoutMessage = "timeout",
                failureMessage = "failure",
                value = Unit,
            ) { dispatches.incrementAndGet() },
        )

        assertEquals(listOf(PluginAsyncResult.Cancelled), results)
        assertEquals(1, cleanups.get())
        assertEquals(0, dispatches.get())
    }

    @Test
    fun expiredDeadlinePreventsActionAndIgnoresLateSuccess() {
        var now = 101L
        val result = AtomicReference<PluginAsyncResult<String>>()
        val actions = AtomicInteger()
        val operation = PluginAsyncOperation(
            generation = 1L,
            nanoTime = { now },
            onTerminal = result::set,
        )

        assertFalse(
            operation.succeedBefore(
                deadlineNanos = 100L,
                timeoutMessage = "timed out",
                failureMessage = "failed",
                value = "late",
            ) { actions.incrementAndGet() },
        )
        assertEquals(PluginAsyncResult.Failure("timed out"), result.get())
        assertEquals(0, actions.get())
        assertFalse(operation.succeed("later"))
    }

    @Test
    fun exactDeadlineIsExpiredButOneNanosecondBeforeCanDispatch() {
        var now = 99L
        val beforeResult = AtomicReference<PluginAsyncResult<String>>()
        val before = PluginAsyncOperation(
            generation = 1L,
            nanoTime = { now },
            onTerminal = beforeResult::set,
        )
        assertTrue(
            before.succeedBefore(100L, "timeout", "failure", "sent"),
        )
        assertEquals(PluginAsyncResult.Success("sent"), beforeResult.get())

        now = 100L
        val boundaryResult = AtomicReference<PluginAsyncResult<String>>()
        val boundary = PluginAsyncOperation(
            generation = 2L,
            nanoTime = { now },
            onTerminal = boundaryResult::set,
        )
        assertFalse(
            boundary.succeedBefore(100L, "timeout", "failure", "late"),
        )
        assertEquals(PluginAsyncResult.Failure("timeout"), boundaryResult.get())
    }

    @Test
    fun dispatchExceptionUsesItsMessageAndCompletesOnce() {
        val results = mutableListOf<PluginAsyncResult<Unit>>()
        val cleanups = AtomicInteger()
        val operation = PluginAsyncOperation<Unit>(generation = 1L) { results += it }
        operation.addCleanup { cleanups.incrementAndGet() }

        assertFalse(
            operation.succeedBefore(
                deadlineNanos = Long.MAX_VALUE,
                timeoutMessage = "timeout",
                failureMessage = "fallback",
                value = Unit,
            ) { error("pending intent was cancelled") },
        )

        assertEquals(listOf(PluginAsyncResult.Failure("pending intent was cancelled")), results)
        assertEquals(1, cleanups.get())
        assertFalse(operation.fail("late timeout"))
    }

    @Test
    fun dispatchClaimMakesConcurrentCancellationReturnImmediately() {
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val cancelWon = AtomicBoolean(true)
        val cleanupRan = AtomicBoolean(false)
        val result = AtomicReference<PluginAsyncResult<String>>()
        val operation = PluginAsyncOperation<String>(generation = 1L, onTerminal = result::set)
        operation.addCleanup { cleanupRan.set(true) }

        val dispatchThread = thread {
            assertTrue(
                operation.succeedBefore(
                    deadlineNanos = Long.MAX_VALUE,
                    timeoutMessage = "timeout",
                    failureMessage = "failure",
                    value = "sent",
                ) {
                    actionEntered.countDown()
                    assertTrue(releaseAction.await(2, TimeUnit.SECONDS))
                },
            )
        }
        assertTrue(actionEntered.await(2, TimeUnit.SECONDS))
        assertTrue(cleanupRan.get())
        val cancelThread = thread {
            cancelWon.set(operation.cancel())
            cancelReturned.countDown()
        }
        assertTrue(cancelReturned.await(2, TimeUnit.SECONDS))
        assertFalse(cancelWon.get())
        releaseAction.countDown()
        dispatchThread.join(2_000)
        cancelThread.join(2_000)

        assertEquals(PluginAsyncResult.Success("sent"), result.get())
    }

    @Test
    fun cleanupRegisteredAfterDispatchClaimRunsWithoutWaitingForSend() {
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val cleanupRan = CountDownLatch(1)
        val operation = PluginAsyncOperation<Unit>(generation = 1L) {}
        val dispatchThread = thread {
            operation.succeedBefore(
                deadlineNanos = Long.MAX_VALUE,
                timeoutMessage = "timeout",
                failureMessage = "failure",
                value = Unit,
            ) {
                actionEntered.countDown()
                assertTrue(releaseAction.await(2, TimeUnit.SECONDS))
            }
        }

        assertTrue(actionEntered.await(2, TimeUnit.SECONDS))
        operation.addCleanup { cleanupRan.countDown() }
        assertTrue(cleanupRan.await(2, TimeUnit.SECONDS))

        releaseAction.countDown()
        dispatchThread.join(2_000)
    }

    @Test
    fun cancellationCanFinishWhileProceedActionIsBlocked() {
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val proceeded = AtomicBoolean(true)
        val result = AtomicReference<PluginAsyncResult<Unit>>()
        val operation = PluginAsyncOperation<Unit>(generation = 1L, onTerminal = result::set)
        val worker = thread {
            proceeded.set(
                operation.proceedBefore(
                    deadlineNanos = Long.MAX_VALUE,
                    timeoutMessage = "timeout",
                    failureMessage = "failure",
                ) {
                    actionEntered.countDown()
                    releaseAction.await(2, TimeUnit.SECONDS)
                },
            )
        }

        assertTrue(actionEntered.await(2, TimeUnit.SECONDS))
        assertTrue(operation.cancel())
        assertEquals(PluginAsyncResult.Cancelled, result.get())
        releaseAction.countDown()
        worker.join(2_000)
        assertFalse(proceeded.get())
    }

    @Test
    fun reentrantCancellationDuringDispatchCannotRewriteCommittedSuccess() {
        val results = mutableListOf<PluginAsyncResult<String>>()
        lateinit var operation: PluginAsyncOperation<String>
        operation = PluginAsyncOperation(generation = 1L) { results += it }

        assertTrue(
            operation.succeedBefore(
                deadlineNanos = Long.MAX_VALUE,
                timeoutMessage = "timeout",
                failureMessage = "failure",
                value = "sent",
            ) {
                assertFalse(operation.cancel())
            },
        )
        assertEquals(listOf(PluginAsyncResult.Success("sent")), results)
    }

    @Test
    fun replacingGenerationCancelsOldWorkWithoutClearingNewGeneration() {
        val slot = PluginAsyncOperationSlot<Unit>()
        val firstResult = AtomicReference<PluginAsyncResult<Unit>>()
        val secondResult = AtomicReference<PluginAsyncResult<Unit>>()
        val first = slot.begin(firstResult::set)
        val second = slot.begin(secondResult::set)

        assertEquals(PluginAsyncResult.Cancelled, firstResult.get())
        assertFalse(first.isActive())
        assertEquals(second.generation, slot.currentGeneration())
        assertTrue(second.succeed(Unit))
        assertEquals(PluginAsyncResult.Success(Unit), secondResult.get())
        assertNull(slot.currentGeneration())
    }

    @Test
    fun cancellationDuringDiscoveryCannotBeLostBeforeLateCompletion() {
        val slot = PluginAsyncOperationSlot<Unit>()
        val result = AtomicReference<PluginAsyncResult<Unit>>()
        val dispatches = AtomicInteger()
        val operation = slot.begin(result::set)

        slot.cancel()
        assertFalse(
            operation.succeedBefore(
                deadlineNanos = Long.MAX_VALUE,
                timeoutMessage = "timeout",
                failureMessage = "failure",
                value = Unit,
            ) { dispatches.incrementAndGet() },
        )

        assertEquals(PluginAsyncResult.Cancelled, result.get())
        assertEquals(0, dispatches.get())
        assertNull(slot.currentGeneration())
    }

    @Test
    fun slotCancellationDetachesCallbackFromIrreversibleBlockedDispatch() {
        val slot = PluginAsyncOperationSlot<Unit>()
        val result = AtomicReference<PluginAsyncResult<Unit>>()
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val operation = slot.begin(result::set)
        val worker = thread {
            operation.succeedBefore(
                deadlineNanos = Long.MAX_VALUE,
                timeoutMessage = "timeout",
                failureMessage = "failure",
                value = Unit,
            ) {
                actionEntered.countDown()
                releaseAction.await(2, TimeUnit.SECONDS)
            }
        }

        assertTrue(actionEntered.await(2, TimeUnit.SECONDS))
        slot.cancel()
        releaseAction.countDown()
        worker.join(2_000)

        assertNull(result.get())
        assertNull(slot.currentGeneration())
    }

    @Test
    fun cleanupRegisteredAfterTerminalRunsImmediatelyOnce() {
        val cleanups = AtomicInteger()
        val operation = PluginAsyncOperation<Unit>(generation = 1L) {}
        assertTrue(operation.succeed(Unit))

        operation.addCleanup { cleanups.incrementAndGet() }

        assertEquals(1, cleanups.get())
    }

    @Test
    fun terminalRacePublishesAndCleansUpExactlyOnce() {
        val racers = 12
        val barrier = CyclicBarrier(racers)
        val winners = AtomicInteger()
        val cleanups = AtomicInteger()
        val callbacks = AtomicInteger()
        val operation = PluginAsyncOperation<Unit>(generation = 1L) {
            callbacks.incrementAndGet()
        }
        repeat(3) { operation.addCleanup { cleanups.incrementAndGet() } }

        val threads = (0 until racers).map { index ->
            thread {
                barrier.await(2, TimeUnit.SECONDS)
                val won = when (index % 3) {
                    0 -> operation.cancel()
                    1 -> operation.fail("failure")
                    else -> operation.succeed(Unit)
                }
                if (won) winners.incrementAndGet()
            }
        }
        threads.forEach { it.join(2_000) }

        assertEquals(1, winners.get())
        assertEquals(1, callbacks.get())
        assertEquals(3, cleanups.get())
    }
}
