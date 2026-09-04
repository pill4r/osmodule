package dev.pillar.osmodule.rsdk

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsdkPermissionPolicyTest {
    @Test
    fun remoteControlDoesNotRequireGpsOrNotifications() {
        val permissions = RsdkPermissionPolicy.remoteControlPermissions(36)

        assertEquals(
            setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
            permissions,
        )
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in permissions)
    }

    @Test
    fun notificationIsOptionalAndSeparateFromGps() {
        val gps = RsdkPermissionPolicy.gpsPermissions(36)

        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in gps)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in gps)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in gps)
        assertEquals(
            setOf(Manifest.permission.POST_NOTIFICATIONS),
            RsdkPermissionPolicy.notificationPermissions(36),
        )
    }

    @Test
    fun partialLocationGrantRequestsTheCompletePairOnAndroid12Plus() {
        val desired = RsdkPermissionPolicy.gpsPermissions(36)
        val granted = setOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        val pending = RsdkPermissionPolicy.pendingRequest(36, desired) { it in granted }

        assertEquals(
            setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            pending,
        )
    }

    @Test
    fun previewUsesNearbyWifiOnModernAndroid() {
        assertEquals(
            setOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            RsdkPermissionPolicy.livePreviewPermissions(36),
        )
        assertEquals(
            setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            RsdkPermissionPolicy.livePreviewPermissions(31),
        )
    }
}
