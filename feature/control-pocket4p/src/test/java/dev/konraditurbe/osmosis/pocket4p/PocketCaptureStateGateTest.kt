package dev.konraditurbe.osmosis.pocket4p

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketCaptureStateGateTest {
    @Test
    fun `missing camera status fails closed after a bounded wait`() {
        val gate = gate()
        gate.begin(nowMs = 100)

        assertEquals(PocketCaptureStateGate.Decision.WAIT_FOR_STATUS, gate.decision(1_099))
        assertEquals(PocketCaptureStateGate.Decision.STATUS_TIMEOUT, gate.decision(1_100))
    }

    @Test
    fun `playback repeatedly requests exit until a status push confirms capture mode`() {
        val gate = gate()
        gate.begin(nowMs = 0)
        gate.onCameraStatus(playback = true, nowMs = 500)

        assertEquals(PocketCaptureStateGate.Decision.EXIT_PLAYBACK, gate.decision(500))
        assertEquals(PocketCaptureStateGate.Decision.EXIT_PLAYBACK, gate.decision(2_499))

        gate.onCameraStatus(playback = false, nowMs = 2_499)
        assertEquals(PocketCaptureStateGate.Decision.CAPTURE_READY, gate.decision(2_499))
    }

    @Test
    fun `playback timeout starts with the first playback status not the subscription wait`() {
        val gate = gate()
        gate.begin(nowMs = 0)
        gate.onCameraStatus(playback = true, nowMs = 999)

        assertEquals(PocketCaptureStateGate.Decision.EXIT_PLAYBACK, gate.decision(2_998))
        assertEquals(PocketCaptureStateGate.Decision.PLAYBACK_TIMEOUT, gate.decision(2_999))
    }

    @Test
    fun `initial capture status opens the gate without an exit request`() {
        val gate = gate()
        gate.begin(nowMs = 10)
        gate.onCameraStatus(playback = false, nowMs = 20)

        assertEquals(PocketCaptureStateGate.Decision.CAPTURE_READY, gate.decision(20))
    }

    private fun gate() = PocketCaptureStateGate(
        statusTimeoutMs = 1_000,
        playbackTimeoutMs = 2_000,
    )
}
