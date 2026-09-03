package dev.konraditurbe.osmosis.rsdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.konraditurbe.osmosis.modules.CameraRemotePanelLauncher
import dev.konraditurbe.osmosis.modules.CameraRemoteTarget
import dev.konraditurbe.osmosis.modules.DeviceModels

internal class RsdkRemotePanelLauncher : CameraRemotePanelLauncher {
    override fun open(context: Context, target: CameraRemoteTarget): Boolean = runCatching {
        require(MAC.matches(target.address)) { "Invalid camera address" }
        require(target.deviceModel == DeviceModels.OSMO_360) { "Remote control supports Osmo 360 only" }
        val intent = Intent(context, RsdkRemoteActivity::class.java)
            .putExtra(RsdkRemoteActivity.EXTRA_CAMERA_ADDRESS, target.address.uppercase())
            .putExtra(RsdkRemoteActivity.EXTRA_CAMERA_NAME, target.name)
            .putExtra(RsdkRemoteActivity.EXTRA_AUTO_CONNECT, target.inRange)
            .putExtra(RsdkRemoteActivity.EXTRA_WIFI_SSID, target.wifiSsid)
            .putExtra(RsdkRemoteActivity.EXTRA_WIFI_PASSPHRASE, target.wifiPassphrase)
            .putExtra(RsdkRemoteActivity.EXTRA_WIFI_WPA3, target.wifiWpa3)
            .putExtra(RsdkRemoteActivity.EXTRA_DATALINK_PORT, target.datalinkPort)
            .putExtra(RsdkRemoteActivity.EXTRA_DATALINK_TCP_POKE, target.datalinkTcpPoke)
            .putStringArrayListExtra(
                RsdkRemoteActivity.EXTRA_PANORAMA_CALIBRATION_STREAMS,
                ArrayList(target.panoramaCalibrationStreams),
            )
            .putExtra(
                RsdkRemoteActivity.EXTRA_PANORAMA_CALIBRATION_DATA,
                target.panoramaCalibrationData,
            )
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private companion object {
        val MAC = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}
