package dev.pillar.osmodule.rsdk

import android.Manifest

/** Keeps runtime permission groups aligned across the plugin home and feature screens. */
object RsdkPermissionPolicy {
    fun remoteControlPermissions(apiLevel: Int): Set<String> = buildSet {
        if (apiLevel >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    fun gpsPermissions(apiLevel: Int): Set<String> = buildSet {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (apiLevel >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun notificationPermissions(apiLevel: Int): Set<String> = buildSet {
        if (apiLevel >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun livePreviewPermissions(apiLevel: Int): Set<String> = buildSet {
        if (apiLevel >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Android 12+ requires coarse and fine location to be requested together when precise location
     * is needed. Re-add the complete pair if either member is missing, even when an OEM reports the
     * unusual state where fine is granted but coarse is not (or vice versa).
     */
    fun pendingRequest(
        apiLevel: Int,
        desired: Set<String>,
        isGranted: (String) -> Boolean,
    ): Set<String> {
        val missing = desired.filterNot(isGranted).toMutableSet()
        val locationMissing = LOCATION_PERMISSIONS.any { it in desired && !isGranted(it) }
        if (apiLevel >= 31 && locationMissing) {
            missing += LOCATION_PERMISSIONS
        }
        return missing
    }

    private val LOCATION_PERMISSIONS = setOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
}
