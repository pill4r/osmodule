package dev.konraditurbe.osmosis.rsdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.konraditurbe.osmosis.session.CameraSessionCoordinator
import java.util.concurrent.atomic.AtomicBoolean

/** DJI's documented two-second `WKP + reversed MAC` wake advertisement. */
internal object RsdkWakeBroadcaster {
    private const val DJI_WAKE_MANUFACTURER_ID = 0x4B57 // bytes 'W', 'K' on the air (little-endian)

    @SuppressLint("MissingPermission")
    fun start(context: Context, cameraMac: String, result: (Boolean, String?) -> Unit): Boolean {
        if (CameraSessionCoordinator.current() != null) return false
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_ADVERTISE
        else Manifest.permission.BLUETOOTH_ADMIN
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            result(false, "Missing $permission permission")
            return false
        }

        val mac = runCatching {
            cameraMac.split(':').also { require(it.size == 6) }.map { it.toInt(16).toByte() }.toByteArray()
        }.getOrElse {
            result(false, "Invalid camera MAC address")
            return false
        }
        val advertiser = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            result(false, "BLE advertising is unavailable")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(2_000)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(DJI_WAKE_MANUFACTURER_ID, RsdkProtocol.wakeManufacturerPayload(mac))
            .build()
        val completed = AtomicBoolean(false)
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (completed.compareAndSet(false, true)) result(true, "Wake advertisement started for two seconds")
            }

            override fun onStartFailure(errorCode: Int) {
                if (completed.compareAndSet(false, true)) result(false, "BLE advertiser error $errorCode")
            }
        }

        return runCatching {
            advertiser.startAdvertising(settings, data, callback)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { advertiser.stopAdvertising(callback) }
            }, 2_100)
            true
        }.getOrElse {
            if (completed.compareAndSet(false, true)) result(false, it.message ?: "Unable to advertise")
            false
        }
    }
}
