package dev.konraditurbe.osmosis.modules

import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionArbiterTest {
    @Test fun `active owner cannot be preempted and stale release cannot clear successor`() {
        val arbiter = CameraSessionArbiter()
        val firstToken = FakeDeathToken()
        val secondToken = FakeDeathToken()
        val first = arbiter.acquire(request("base"), firstToken) as CameraSessionArbiterResult.Granted

        val blocked = arbiter.acquire(request("pocket4p"), secondToken)
        assertTrue(blocked is CameraSessionArbiterResult.Busy)
        assertEquals("base", (blocked as CameraSessionArbiterResult.Busy).active.ownerId)
        assertFalse(arbiter.release(first.leaseId, secondToken))
        assertEquals("base", arbiter.current()?.ownerId)

        assertTrue(arbiter.release(first.leaseId, firstToken))
        val second = arbiter.acquire(request("pocket4p"), secondToken) as CameraSessionArbiterResult.Granted
        assertFalse(arbiter.release(first.leaseId, firstToken))
        assertEquals("pocket4p", arbiter.current()?.ownerId)

        assertTrue(arbiter.release(second.leaseId, secondToken))
        assertNull(arbiter.current())
    }

    @Test fun `owner death releases its exact lease`() {
        val arbiter = CameraSessionArbiter()
        val deadOwner = FakeDeathToken()
        arbiter.acquire(request("osmo360"), deadOwner) as CameraSessionArbiterResult.Granted

        deadOwner.die()

        assertNull(arbiter.current())
        assertTrue(
            arbiter.acquire(request("base"), FakeDeathToken()) is CameraSessionArbiterResult.Granted,
        )
    }

    @Test fun `token that dies while linking is rejected fail closed`() {
        val arbiter = CameraSessionArbiter()
        val token = FakeDeathToken(dieWhileLinking = true)

        assertEquals(
            CameraSessionArbiterResult.Rejected,
            arbiter.acquire(request("dying"), token),
        )
        assertNull(arbiter.current())
    }

    @Test fun `concurrent contenders produce exactly one grant`() {
        val arbiter = CameraSessionArbiter()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val results = Collections.synchronizedList(mutableListOf<CameraSessionArbiterResult>())
        try {
            val tasks = (0 until 24).map { index ->
                pool.submit {
                    start.await()
                    results += arbiter.acquire(request("owner-$index"), FakeDeathToken())
                }
            }
            start.countDown()
            tasks.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, results.count { it is CameraSessionArbiterResult.Granted })
        assertEquals(23, results.count { it is CameraSessionArbiterResult.Busy })
    }

    @Test fun `cancelled queued delivery disposes a late grant without callback`() {
        val executor = QueuedExecutor()
        val disposed = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val gate = CancellableResultDelivery(executor, disposed::add, delivered::add)

        assertTrue(gate.complete("grant"))
        gate.cancel()
        executor.runAll()

        assertEquals(listOf("grant"), disposed)
        assertTrue(delivered.isEmpty())
    }

    @Test fun `timeout result wins and later grant is disposed`() {
        val executor = QueuedExecutor()
        val disposed = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val gate = CancellableResultDelivery(executor, disposed::add, delivered::add)

        assertTrue(gate.complete("timeout"))
        executor.runAll()
        assertFalse(gate.complete("late-grant"))

        assertEquals(listOf("timeout"), delivered)
        assertEquals(listOf("late-grant"), disposed)
    }

    @Test fun `callback failure disposes the delivered grant`() {
        val disposed = mutableListOf<String>()
        val gate = CancellableResultDelivery(
            executor = Executor(Runnable::run),
            dispose = disposed::add,
            callback = { throw IllegalStateException("destroyed listener") },
        )

        assertTrue(gate.complete("grant"))

        assertEquals(listOf("grant"), disposed)
    }

    @Test fun `rejected callback executor disposes the queued grant`() {
        val disposed = mutableListOf<String>()
        val gate = CancellableResultDelivery(
            executor = Executor { throw java.util.concurrent.RejectedExecutionException() },
            dispose = disposed::add,
            callback = { error("must not run") },
        )

        assertTrue(gate.complete("grant"))

        assertEquals(listOf("grant"), disposed)
    }

    private fun request(owner: String) = CameraSessionOwnerRequest(
        ownerId = owner,
        cameraAddress = "AA:BB:CC:DD:EE:FF",
        purpose = "test",
    )

    private class FakeDeathToken(
        private val dieWhileLinking: Boolean = false,
    ) : CameraSessionDeathToken {
        private var recipient: (() -> Unit)? = null
        private var alive = true

        override fun matches(other: CameraSessionDeathToken): Boolean = this === other

        override fun linkToDeath(recipient: () -> Unit): Boolean {
            this.recipient = recipient
            if (dieWhileLinking) die()
            return alive
        }

        override fun unlinkToDeath() {
            recipient = null
        }

        fun die() {
            if (!alive) return
            alive = false
            recipient?.invoke()
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
