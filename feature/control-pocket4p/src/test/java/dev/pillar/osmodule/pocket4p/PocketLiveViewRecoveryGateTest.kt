package dev.pillar.osmodule.pocket4p

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketLiveViewRecoveryGateTest {
    @Test
    fun `first picture gets one long grace then two spaced recovery enables`() {
        val gate = PocketLiveViewRecoveryGate()
        gate.begin(0)

        assertEquals(PocketLiveViewRecoveryGate.Action.NONE, gate.tick(7_999))
        assertEquals(PocketLiveViewRecoveryGate.Action.RESEND_ENABLE, gate.tick(8_000))
        assertEquals(PocketLiveViewRecoveryGate.Action.NONE, gate.tick(12_999))
        assertEquals(PocketLiveViewRecoveryGate.Action.RESEND_ENABLE, gate.tick(13_000))
        assertEquals(PocketLiveViewRecoveryGate.Action.EXHAUSTED, gate.tick(18_000))
        assertEquals(PocketLiveViewRecoveryGate.Action.NONE, gate.tick(19_000))
    }

    @Test
    fun `mode change waits through camera set grace before recovering a stopped feed`() {
        val gate = PocketLiveViewRecoveryGate()
        gate.begin(0)
        gate.onAccessUnit(1_000)
        gate.onCameraSet(1_200)

        assertEquals(PocketLiveViewRecoveryGate.Action.NONE, gate.tick(5_199))
        assertEquals(PocketLiveViewRecoveryGate.Action.RESEND_ENABLE, gate.tick(5_200))
    }

    @Test
    fun `continuous zoom writes postpone recovery until the last write settles`() {
        val gate = PocketLiveViewRecoveryGate()
        gate.begin(0)
        gate.onAccessUnit(1_000)
        gate.onCameraSet(2_000)
        gate.onCameraSet(5_000)

        assertEquals(PocketLiveViewRecoveryGate.Action.NONE, gate.tick(8_999))
        assertEquals(PocketLiveViewRecoveryGate.Action.RESEND_ENABLE, gate.tick(9_000))
    }

    @Test
    fun `new video resets the bounded recovery ladder`() {
        val gate = PocketLiveViewRecoveryGate()
        gate.begin(0)
        assertEquals(PocketLiveViewRecoveryGate.Action.RESEND_ENABLE, gate.tick(8_000))
        gate.onAccessUnit(8_100)

        assertEquals(PocketLiveViewRecoveryGate.Action.NONE, gate.tick(10_099))
        assertEquals(PocketLiveViewRecoveryGate.Action.RESEND_ENABLE, gate.tick(13_000))
    }
}
