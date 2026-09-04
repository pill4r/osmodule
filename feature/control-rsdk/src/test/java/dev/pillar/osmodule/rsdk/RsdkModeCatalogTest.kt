package dev.pillar.osmodule.rsdk

import dev.pillar.osmodule.modules.CameraRemoteMode
import dev.pillar.osmodule.modules.CameraRemoteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsdkModeCatalogTest {
    @Test
    fun cameraModePageContainsTheRequestedSixPlusFourModes() {
        assertEquals(
            listOf(
                CameraRemoteMode.PANORAMIC_PHOTO,
                CameraRemoteMode.PANORAMIC_VIDEO,
                CameraRemoteMode.PANORAMIC_SUPER_NIGHT,
                CameraRemoteMode.SELFIE,
                CameraRemoteMode.VORTEX,
                CameraRemoteMode.PANORAMIC_HYPERLAPSE,
            ),
            RsdkModeCatalog.panorama360,
        )
        assertEquals(
            listOf(
                CameraRemoteMode.PHOTO,
                CameraRemoteMode.BOOST_VIDEO,
                CameraRemoteMode.SINGLE_LENS_SUPER_NIGHT,
                CameraRemoteMode.VIDEO,
            ),
            RsdkModeCatalog.singleLens,
        )
        assertEquals(10, (RsdkModeCatalog.panorama360 + RsdkModeCatalog.singleLens).distinct().size)
    }

    @Test
    fun textualStatusResolvesTheCurrentCameraMode() {
        assertEquals(
            CameraRemoteMode.PANORAMIC_VIDEO,
            RsdkModeCatalog.currentMode(CameraRemoteState(modeLabel = "PANORAMIC VIDEO · 8K30")),
        )
        assertEquals(
            CameraRemoteMode.VIDEO,
            RsdkModeCatalog.currentMode(CameraRemoteState(modeLabel = "VIDEO · 4K60")),
        )
    }

    @Test
    fun physicalShutterPresentationDistinguishesPhotoModes() {
        assertTrue(RsdkModeCatalog.isPhoto(CameraRemoteMode.PANORAMIC_PHOTO))
        assertTrue(RsdkModeCatalog.isPhoto(CameraRemoteMode.SELFIE))
        assertFalse(RsdkModeCatalog.isPhoto(CameraRemoteMode.PANORAMIC_VIDEO))
    }
}
