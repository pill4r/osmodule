package dev.konraditurbe.osmosis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationResourceSlotTest {
    @Test fun `late pending resource is released after generation invalidation`() {
        val released = mutableListOf<Resource>()
        val slot = GenerationResourceSlot<Resource>(released::add)
        val staleGeneration = slot.begin()
        slot.begin()
        val stale = Resource("stale")

        assertFalse(slot.installPending(staleGeneration, stale))
        assertEquals(listOf(stale), released)
        assertNull(slot.active())
    }

    @Test fun `invalidation detaches a promoted resource before a successor can publish`() {
        val released = mutableListOf<Resource>()
        val slot = GenerationResourceSlot<Resource>(released::add)
        val firstGeneration = slot.begin()
        val first = Resource("first")
        assertTrue(slot.installPending(firstGeneration, first))
        assertTrue(slot.promote(firstGeneration, first))

        val secondGeneration = slot.begin()
        val second = Resource("second")
        assertTrue(slot.installPending(secondGeneration, second))
        assertTrue(slot.promote(secondGeneration, second))

        assertEquals(listOf(first), released)
        assertSame(second, slot.active())
    }

    @Test fun `stale discard cannot clear the current resource`() {
        val released = mutableListOf<Resource>()
        val slot = GenerationResourceSlot<Resource>(released::add)
        val firstGeneration = slot.begin()
        val first = Resource("first")
        assertTrue(slot.installPending(firstGeneration, first))

        val secondGeneration = slot.begin()
        val second = Resource("second")
        assertTrue(slot.installPending(secondGeneration, second))
        slot.discard(first)
        assertTrue(slot.promote(secondGeneration, second))

        assertEquals(listOf(first), released)
        assertSame(second, slot.active())
    }

    private data class Resource(val id: String)
}
