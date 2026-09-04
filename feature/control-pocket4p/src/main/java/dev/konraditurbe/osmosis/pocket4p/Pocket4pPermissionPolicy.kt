package dev.konraditurbe.osmosis.pocket4p

import android.Manifest

internal object Pocket4pPermissionPolicy {
    fun wifiPermissions(apiLevel: Int): Set<String> = when {
        apiLevel >= 33 -> setOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        apiLevel >= 31 -> setOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> setOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
