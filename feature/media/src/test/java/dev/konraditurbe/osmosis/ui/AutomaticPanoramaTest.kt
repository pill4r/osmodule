package dev.konraditurbe.osmosis.ui

import dev.konraditurbe.osmosis.core.CameraFile
import dev.konraditurbe.osmosis.modules.DeviceModels
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
}
