package dev.pillar.osmodule.ui

import dev.pillar.osmodule.core.CameraFile
import dev.pillar.osmodule.core.MediaFileType
import dev.pillar.osmodule.modules.DeviceModels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticPanoramaTest {
    @Test
    fun `osmo 360 OSV opens the panorama viewer automatically`() {
        assertTrue(
            shouldAutomaticallyUsePanorama(
                CameraFile("DCIM/CAM_001/CAM_20260902130111_0015_D.OSV", ""),
                DeviceModels.OSMO_360,
            ),
        )
    }

    @Test
    fun `ordinary video and other camera models keep the normal preview`() {
        assertFalse(
            shouldAutomaticallyUsePanorama(
                CameraFile("DCIM/CAM_001/CAM_20260902130111_0015_D.MP4", ""),
                DeviceModels.OSMO_360,
            ),
        )
        assertFalse(
            shouldAutomaticallyUsePanorama(
                CameraFile("DCIM/CAM_001/CAM_20260902130111_0015_D.OSV", ""),
                "pocket3",
            ),
        )
    }

    @Test
    fun `osmo 360 stitched JPEG opens the panorama viewer`() {
        assertTrue(
            shouldAutomaticallyUsePanorama(
                CameraFile(
                    path = "DCIM/CAM_001/CAM_20260902130200_0016_D.JPG",
                    thumbPath = "",
                    resolution = "15520x7760",
                ),
                DeviceModels.OSMO_360,
            ),
        )
        assertTrue(
            shouldAutomaticallyUsePanorama(
                CameraFile(
                    path = "DCIM/CAM_001/CAM_20260902130200_0016_D.JPG",
                    thumbPath = "",
                    mediaType = MediaFileType.PANORAMA,
                ),
                DeviceModels.OSMO_360,
            ),
        )
    }

    @Test
    fun `decoded dimensions recover panorama routing when manifest dimensions are absent`() {
        val photo = CameraFile("DCIM/CAM_001/CAM_20260902130200_0016_D.JPG", "")

        assertFalse(shouldAutomaticallyUsePanorama(photo, DeviceModels.OSMO_360))
        assertTrue(shouldAutomaticallyUsePanorama(photo, DeviceModels.OSMO_360, true))
    }

    @Test
    fun `osmo 360 single-lens JPEG stays in the flat preview`() {
        assertFalse(
            shouldAutomaticallyUsePanorama(
                CameraFile(
                    path = "DCIM/CAM_001/CAM_20260902130200_0016_D.JPG",
                    thumbPath = "",
                    resolution = "6400x4800",
                ),
                DeviceModels.OSMO_360,
            ),
        )
    }
}
