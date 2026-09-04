package dev.pillar.osmodule.pocket4p

import dev.pillar.osmodule.duml.PocketCameraStatus
import dev.pillar.osmodule.duml.PocketCaptureKind
import dev.pillar.osmodule.duml.PocketShootingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PocketZoomCapabilitiesTest {
    @Test
    fun `photo capture modes stop at nine times`() {
        listOf(PocketShootingMode.PHOTO, PocketShootingMode.PANORAMA).forEach { mode ->
            assertEquals(9.0, PocketZoomCapabilities.maxFactor(status(mode)), EPSILON)
        }
    }

    @Test
    fun `video capture modes allow twelve times`() {
        listOf(
            PocketShootingMode.VIDEO,
            PocketShootingMode.LOW_LIGHT_VIDEO,
            PocketShootingMode.SLOW_MOTION,
            PocketShootingMode.STATIC_TIMELAPSE,
        ).forEach { mode ->
            assertEquals(12.0, PocketZoomCapabilities.maxFactor(status(mode)), EPSILON)
        }
    }

    @Test
    fun `unknown mode falls back to the camera video-like flag`() {
        assertEquals(12.0, PocketZoomCapabilities.maxFactor(status(null, isVideoLike = true)), EPSILON)
        assertEquals(9.0, PocketZoomCapabilities.maxFactor(status(null, isVideoLike = false)), EPSILON)
        assertEquals(12.0, PocketZoomCapabilities.maxFactor(null), EPSILON)
    }

    private fun status(
        mode: PocketShootingMode?,
        isVideoLike: Boolean = mode?.captureKind == PocketCaptureKind.RECORDING,
    ) = PocketCameraStatus(
        rawFlags = 0,
        isConnected = true,
        isRecording = false,
        isRecordingTransitionInProgress = false,
        isInPlayback = false,
        isVideoLike = isVideoLike,
        storageTotalMiB = 0,
        storageFreeMiB = 0,
        remainingRecordSeconds = 0,
        elapsedRecordSeconds = 0,
        shootingModeRaw = mode?.wireValue ?: 0x7F,
        shootingMode = mode,
    )

    private companion object {
        const val EPSILON = 0.0001
    }
}
