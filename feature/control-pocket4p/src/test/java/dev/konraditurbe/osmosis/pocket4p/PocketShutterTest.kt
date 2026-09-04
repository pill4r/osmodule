package dev.konraditurbe.osmosis.pocket4p

import dev.konraditurbe.osmosis.duml.PocketCameraStatus
import dev.konraditurbe.osmosis.duml.PocketShootingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PocketShutterTest {
    @Test
    fun `photo and panorama modes dispatch the photo shutter`() {
        assertEquals(PocketShutter.Action.PHOTO, PocketShutter.action(status(PocketShootingMode.PHOTO)))
        assertEquals(PocketShutter.Action.PHOTO, PocketShutter.action(status(PocketShootingMode.PANORAMA)))
    }

    @Test
    fun `video-like modes dispatch record start and active recording dispatches stop`() {
        listOf(
            PocketShootingMode.VIDEO,
            PocketShootingMode.LOW_LIGHT_VIDEO,
            PocketShootingMode.SLOW_MOTION,
            PocketShootingMode.STATIC_TIMELAPSE,
        ).forEach { mode ->
            assertEquals(PocketShutter.Action.START_RECORDING, PocketShutter.action(status(mode)))
        }
        assertEquals(
            PocketShutter.Action.STOP_RECORDING,
            PocketShutter.action(status(PocketShootingMode.VIDEO, recording = true)),
        )
    }

    @Test
    fun `missing or transitioning status holds the shutter`() {
        assertEquals(PocketShutter.Action.WAIT, PocketShutter.action(null))
        assertEquals(
            PocketShutter.Action.WAIT,
            PocketShutter.action(status(PocketShootingMode.VIDEO, transitioning = true)),
        )
    }

    private fun status(
        mode: PocketShootingMode,
        recording: Boolean = false,
        transitioning: Boolean = false,
    ) = PocketCameraStatus(
        rawFlags = 0,
        isConnected = true,
        isRecording = recording,
        isRecordingTransitionInProgress = transitioning,
        isInPlayback = false,
        isVideoLike = mode != PocketShootingMode.PHOTO && mode != PocketShootingMode.PANORAMA,
        storageTotalMiB = 0,
        storageFreeMiB = 0,
        remainingRecordSeconds = 0,
        elapsedRecordSeconds = 0,
        shootingModeRaw = mode.wireValue,
        shootingMode = mode,
    )
}
