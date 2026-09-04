package dev.pillar.osmodule.ui

import dev.pillar.osmodule.core.CameraFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GallerySnapshotCacheTest {
    @Test
    fun roundTripsEveryWireField() {
        val files = listOf(
            CameraFile(
                path = "/DCIM/DJI_001/DJI_20260905001451_0017_D.JPG",
                thumbPath = "/MISC/THM/DJI_001/DJI_20260905001451_0017_D.SCR",
                storage = 1,
                resLabel = "8192x6144",
                proxyPath = null,
                handle = 0x40100440,
                sizeBytes = 12_345_678,
                starred = true,
                resolution = "8192x6144",
                durationSec = 0,
                cmdHandle = 0x40100440,
                group = 1,
                fileIndex = 0,
                mtimeEpoch = 1_788_539_291,
                storageKnown = true,
                handleShared = false,
                mediaType = 0,
                handleCandidate = 0x40100440,
            ),
        )

        assertEquals(files, GallerySnapshotCache.decode(GallerySnapshotCache.encode(files)))
    }

    @Test
    fun rejectsMissingAndMalformedSnapshots() {
        assertNull(GallerySnapshotCache.decode(null))
        assertNull(GallerySnapshotCache.decode("not base64"))
    }
}
