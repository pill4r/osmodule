package dev.pillar.osmodule.pocket4p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketZoomReadoutTest {
    @Test
    fun delayedCameraFeedbackDoesNotReplaceReadoutWhileTracking() {
        val readout = PocketZoomReadout(initialFactor = 1.0)

        readout.beginTracking()
        readout.advance(factorPerSecond = 2.0, elapsedMs = 100L)
        assertEquals(1.2, readout.displayFactor, EPSILON)

        assertFalse(readout.confirm(factor = 1.05, nowMs = 100L))
        assertEquals(1.2, readout.displayFactor, EPSILON)
        assertEquals(1.05, readout.confirmedFactor, EPSILON)
    }

    @Test
    fun releasedTargetStaysVisibleUntilCameraCatchesUp() {
        val readout = PocketZoomReadout(initialFactor = 1.0)
        readout.beginTracking()
        readout.advance(factorPerSecond = 3.0, elapsedMs = 100L)

        assertEquals(1.3, readout.finishTracking(nowMs = 200L), EPSILON)
        assertTrue(readout.isSettling)
        assertFalse(readout.confirm(factor = 1.1, nowMs = 300L))
        assertEquals(1.3, readout.displayFactor, EPSILON)

        assertTrue(readout.confirm(factor = 1.22, nowMs = 350L))
        assertFalse(readout.isSettling)
        assertEquals(1.22, readout.displayFactor, EPSILON)
    }

    @Test
    fun settlementFallsBackToLatestCameraValueAfterTimeout() {
        val readout = PocketZoomReadout(
            initialFactor = 2.0,
            settleTimeoutMs = 500L,
        )
        readout.beginTracking()
        readout.advance(factorPerSecond = 2.0, elapsedMs = 100L)
        readout.finishTracking(nowMs = 1_000L)
        readout.confirm(factor = 2.05, nowMs = 1_100L)

        assertFalse(readout.expireSettlement(nowMs = 1_499L))
        assertTrue(readout.expireSettlement(nowMs = 1_500L))
        assertEquals(2.05, readout.displayFactor, EPSILON)
    }

    @Test
    fun changingDirectionContinuesFromTheLocalTarget() {
        val readout = PocketZoomReadout(initialFactor = 3.0)
        readout.beginTracking()
        readout.advance(factorPerSecond = 2.0, elapsedMs = 100L)
        readout.confirm(factor = 3.05, nowMs = 100L)
        readout.advance(factorPerSecond = 0.0, elapsedMs = 100L)
        readout.advance(factorPerSecond = -1.0, elapsedMs = 100L)

        assertEquals(3.1, readout.requestedFactor, EPSILON)
        assertEquals(3.1, readout.displayFactor, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.0001
    }
}
