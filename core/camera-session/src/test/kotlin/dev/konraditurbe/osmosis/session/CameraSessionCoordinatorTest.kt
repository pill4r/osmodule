package dev.konraditurbe.osmosis.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionCoordinatorTest {
    @After fun reset() = CameraSessionCoordinator.resetForTest()

    @Test fun `only one camera transport can own the process`() {
        val media = CameraSessionCoordinator.acquire(
            "media-ui", "aa:bb:cc:dd:ee:ff", CameraSessionPurpose.MEDIA_OFFLOAD,
        ) as CameraLeaseResult.Granted

        val rsdk = CameraSessionCoordinator.acquire(
            "rsdk-hub", "AA:BB:CC:DD:EE:FF", CameraSessionPurpose.RSDK_CONTROL,
        )

        assertTrue(rsdk is CameraLeaseResult.Busy)
        assertEquals(CameraSessionPurpose.MEDIA_OFFLOAD, (rsdk as CameraLeaseResult.Busy).active.purpose)
        assertEquals("AA:BB:CC:DD:EE:FF", CameraSessionCoordinator.current()?.cameraAddress)
        media.lease.close()
        assertNull(CameraSessionCoordinator.current())
    }

    @Test fun `closing a lease twice cannot release its successor`() {
        val first = (CameraSessionCoordinator.acquire(
            "first", "01", CameraSessionPurpose.MEDIA_OFFLOAD,
        ) as CameraLeaseResult.Granted).lease
        first.close()

        val second = (CameraSessionCoordinator.acquire(
            "second", "02", CameraSessionPurpose.RSDK_CONTROL,
        ) as CameraLeaseResult.Granted).lease
        first.close()

        assertEquals("second", CameraSessionCoordinator.current()?.ownerId)
        second.close()
        assertNull(CameraSessionCoordinator.current())
    }
}
