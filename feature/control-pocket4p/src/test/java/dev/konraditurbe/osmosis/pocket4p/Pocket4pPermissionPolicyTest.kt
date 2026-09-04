package dev.konraditurbe.osmosis.pocket4p

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class Pocket4pPermissionPolicyTest {
    @Test
    fun `android 13 and newer request nearby wifi`() {
        assertEquals(
            setOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            Pocket4pPermissionPolicy.wifiPermissions(33),
        )
    }

    @Test
    fun `android 12 requests coarse and fine location together`() {
        val expected = setOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        assertEquals(expected, Pocket4pPermissionPolicy.wifiPermissions(31))
        assertEquals(expected, Pocket4pPermissionPolicy.wifiPermissions(32))
    }

    @Test
    fun `older android requests fine location`() {
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            Pocket4pPermissionPolicy.wifiPermissions(30),
        )
    }
}
