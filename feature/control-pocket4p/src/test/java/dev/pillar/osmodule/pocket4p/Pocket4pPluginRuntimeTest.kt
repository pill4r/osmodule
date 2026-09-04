package dev.pillar.osmodule.pocket4p

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pocket4pPluginRuntimeTest {
    private val ownerA = Any()
    private val ownerB = Any()

    @After
    fun tearDown() {
        Pocket4pPluginRuntime.disconnected(ownerA)
        Pocket4pPluginRuntime.disconnected(ownerB)
    }

    @Test
    fun `stale owner cannot clear a replacement session`() {
        Pocket4pPluginRuntime.connected(ownerA, "first")
        Pocket4pPluginRuntime.connected(ownerB, "replacement")

        Pocket4pPluginRuntime.disconnected(ownerA)

        val snapshot = Pocket4pPluginRuntime.snapshot()
        assertTrue(snapshot.active)
        assertEquals("replacement", snapshot.cameraName)
    }

    @Test
    fun `current owner clears its own session`() {
        Pocket4pPluginRuntime.connected(ownerA, "camera")

        Pocket4pPluginRuntime.disconnected(ownerA)

        val snapshot = Pocket4pPluginRuntime.snapshot()
        assertFalse(snapshot.active)
        assertEquals(null, snapshot.cameraName)
    }
}
