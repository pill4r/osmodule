package dev.konraditurbe.osmosis.rsdk

import dev.konraditurbe.osmosis.modules.CameraRemotePhase
import dev.konraditurbe.osmosis.modules.CameraRemoteState
import dev.konraditurbe.osmosis.modules.CameraRemoteStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsdkRemotePanelStateTest {
    @Test
    fun disconnectedOffersConnectAndWakeOnly() {
        val panel = RsdkRemotePanelStateMapper.from(CameraRemoteState())

        assertTrue(panel.canConnect)
        assertTrue(panel.canWake)
        assertFalse(panel.canDisconnect)
        assertFalse(panel.commandsEnabled)
    }

    @Test
    fun connectingCanBeCancelledButCannotSendCommands() {
        val panel = RsdkRemotePanelStateMapper.from(
            CameraRemoteState(phase = CameraRemotePhase.CONNECTING),
        )

        assertTrue(panel.connecting)
        assertTrue(panel.canDisconnect)
        assertFalse(panel.canConnect)
        assertFalse(panel.canWake)
        assertFalse(panel.commandsEnabled)
    }

    @Test
    fun connectedEnablesCommandsAndReflectsRecordingState() {
        val panel = RsdkRemotePanelStateMapper.from(
            CameraRemoteState(
                phase = CameraRemotePhase.CONNECTED,
                status = status(recording = true),
            ),
        )

        assertTrue(panel.connected)
        assertTrue(panel.canDisconnect)
        assertTrue(panel.commandsEnabled)
        assertTrue(panel.recording)
        assertFalse(panel.canWake)
    }

    private fun status(recording: Boolean) = CameraRemoteStatus(
        rawMode = 1,
        mode = null,
        modeLabel = "Video",
        rawStatus = 0,
        activeCapture = recording,
        recording = recording,
        preRecording = false,
        resolutionCode = 0,
        fpsCode = 0,
        eisCode = null,
        recordTimeSeconds = 0,
        photoRatioCode = null,
        countdownSeconds = null,
        timelapseIntervalTenths = null,
        timelapseDurationSeconds = null,
        remainingCapacityMb = null,
        remainingPhotos = null,
        remainingRecordSeconds = null,
        customModeIndex = null,
        powerMode = null,
        nextModeCode = null,
        thermalState = null,
        photoCountdownMs = null,
        loopRecordSeconds = null,
        batteryPercent = null,
    )
}
