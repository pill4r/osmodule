package dev.pillar.osmodule.ui

import dev.pillar.osmodule.modules.DeviceModels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEntryRoutingTest {
    @Test
    fun matchingPocketRemoteSkipsMediaListing() {
        assertTrue(
            shouldLaunchRemoteBeforeMediaListing(
                requestedAddress = "AA:BB:CC:DD:EE:FF",
                currentAddress = "aa:bb:cc:dd:ee:ff",
                deviceModel = DeviceModels.OSMO_POCKET_4_PRO,
            ),
        )
    }

    @Test
    fun galleryAndOtherCameraRoutesStillListMedia() {
        assertFalse(
            shouldLaunchRemoteBeforeMediaListing(
                requestedAddress = null,
                currentAddress = "AA:BB:CC:DD:EE:FF",
                deviceModel = DeviceModels.OSMO_POCKET_4_PRO,
            ),
        )
        assertFalse(
            shouldLaunchRemoteBeforeMediaListing(
                requestedAddress = "11:22:33:44:55:66",
                currentAddress = "AA:BB:CC:DD:EE:FF",
                deviceModel = DeviceModels.OSMO_POCKET_4_PRO,
            ),
        )
        assertFalse(
            shouldLaunchRemoteBeforeMediaListing(
                requestedAddress = "AA:BB:CC:DD:EE:FF",
                currentAddress = "AA:BB:CC:DD:EE:FF",
                deviceModel = DeviceModels.OSMO_360,
            ),
        )
    }
}
