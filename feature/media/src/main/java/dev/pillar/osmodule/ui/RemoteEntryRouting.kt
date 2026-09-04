package dev.pillar.osmodule.ui

import dev.pillar.osmodule.modules.DeviceModels

/**
 * Pocket remote control only needs Base to discover the AP credentials and obtain its [Network].
 * Osmo 360 still enters after media listing because that list supplies its factory-calibration stream.
 */
internal fun shouldLaunchRemoteBeforeMediaListing(
    requestedAddress: String?,
    currentAddress: String?,
    deviceModel: String,
): Boolean = deviceModel == DeviceModels.OSMO_POCKET_4_PRO &&
    requestedAddress != null &&
    currentAddress != null &&
    requestedAddress.equals(currentAddress, ignoreCase = true)
