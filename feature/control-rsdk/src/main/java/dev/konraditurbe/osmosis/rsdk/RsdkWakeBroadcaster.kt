package dev.konraditurbe.osmosis.rsdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** DJI's documented two-second `WKP + reversed MAC` wake advertisement. */
internal object RsdkWakeBroadcaster {
    private class Operation(
        val advertiser: BluetoothLeAdvertiser,
        val mac: ByteArray,
        val result: (Boolean, String?) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        var request: RsdkCameraOwnership.Request? = null
        var ownership: RsdkCameraOwnership.Lease? = null
        var callback: AdvertiseCallback? = null
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var current: Operation? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context, cameraMac: String, result: (Boolean, String?) -> Unit): Boolean {
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
        val advertiser = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bluetoothLeAdvertiser
        if (advertiser == null) {
            result(false, "BLE advertising is unavailable")
            return false
        }

        val operation = Operation(advertiser, mac, result)
        synchronized(lock) {
            if (current != null) {
                result(false, "A wake advertisement is already in progress")
                return false
            }
            current = operation
        }

        val request = RsdkCameraOwnership.acquireAsync(
            context.applicationContext,
            cameraMac,
        ) { acquired ->
            ownershipAcquired(operation, acquired)
        }
        val retain = synchronized(lock) {
            if (current === operation) {
                operation.request = request
                true
            } else {
                false
            }
        }
        if (!retain) request.cancel()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun ownershipAcquired(
        operation: Operation,
        acquired: RsdkCameraOwnership.Result,
    ) {
        if (acquired is RsdkCameraOwnership.Result.Busy) {
            finish(operation, false, acquired.reason)
            return
        }
        val ownership = (acquired as RsdkCameraOwnership.Result.Granted).lease
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(2_000)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(
                DJI_WAKE_MANUFACTURER_ID,
                RsdkProtocol.wakeManufacturerPayload(operation.mac),
            )
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                val stillCurrent = synchronized(lock) { current === operation }
                if (stillCurrent && operation.completed.compareAndSet(false, true)) {
                    runCatching { operation.result(true, "Wake advertisement started for two seconds") }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                finish(operation, false, "BLE advertiser error $errorCode")
            }
        }

        var startError: Throwable? = null
        val started = synchronized(lock) {
            if (current !== operation) {
                false
            } else {
                operation.request = null
                operation.ownership = ownership
                operation.callback = callback
                try {
                    operation.advertiser.startAdvertising(settings, data, callback)
                    true
                } catch (error: Throwable) {
                    startError = error
                    false
                }
            }
        }
        if (!started) {
            if (startError == null) ownership.close()
            else finish(operation, false, startError?.message ?: "Unable to advertise")
            return
        }
        if (synchronized(lock) { current === operation }) {
            main.postDelayed({ finish(operation, null, null) }, ADVERTISE_RELEASE_MS)
        }
    }

    /** Activity teardown cancels a pending grant or synchronously stops the active advertisement. */
    fun cancel() {
        val operation = synchronized(lock) {
            current.also { current = null }
        } ?: return
        operation.completed.set(true)
        releaseTransportThenOwnership(operation)
    }

    private fun finish(operation: Operation, success: Boolean?, detail: String?) {
        val detached = synchronized(lock) {
            if (current !== operation) return
            current = null
            operation
        }
        releaseTransportThenOwnership(detached)
        if (success != null && detached.completed.compareAndSet(false, true)) {
            runCatching { detached.result(success, detail) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun releaseTransportThenOwnership(operation: Operation) {
        operation.request?.cancel()
        operation.request = null
        operation.callback?.let { callback ->
            runCatching { operation.advertiser.stopAdvertising(callback) }
        }
        operation.callback = null
        operation.ownership?.close()
        operation.ownership = null
    }

    private const val DJI_WAKE_MANUFACTURER_ID = 0x4B57
    private const val ADVERTISE_RELEASE_MS = 2_100L
}
