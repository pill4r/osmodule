package dev.konraditurbe.osmosis.rsdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportCloseBarrierTest {
    @Test fun `resource registered before close is closed exactly once`() {
        val barrier = TransportCloseBarrier()
        val resource = CountingResource()

        assertTrue(barrier.register(resource))
        assertTrue(barrier.close())
        assertFalse(barrier.close())

        assertEquals(1, resource.closes.get())
    }

    @Test fun `resource arriving after close is rejected and closed`() {
        val barrier = TransportCloseBarrier()
        val resource = CountingResource()
        barrier.close()

        assertFalse(barrier.register(resource))
        assertEquals(1, resource.closes.get())
    }

    @Test fun `close waits for accepted wire operation and rejects every later operation`() {
        val barrier = TransportCloseBarrier()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val operation = pool.submit {
                assertTrue(barrier.runIfOpen {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                })
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val closing = pool.submit {
                closeStarted.countDown()
                barrier.close()
                closeReturned.countDown()
            }

            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(closeReturned.await(2, TimeUnit.SECONDS))
            operation.get(2, TimeUnit.SECONDS)
            closing.get(2, TimeUnit.SECONDS)
            assertFalse(barrier.runIfOpen { error("must not run after close") })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private class CountingResource : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() {
            closes.incrementAndGet()
        }
    }
}
