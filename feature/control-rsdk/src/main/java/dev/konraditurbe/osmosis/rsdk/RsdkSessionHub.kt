package dev.konraditurbe.osmosis.rsdk

import android.bluetooth.BluetoothManager
import android.content.Context
import dev.konraditurbe.osmosis.session.CameraLeaseResult
import dev.konraditurbe.osmosis.session.CameraSessionCoordinator
import dev.konraditurbe.osmosis.session.CameraSessionLease
import dev.konraditurbe.osmosis.session.CameraSessionPurpose

/**
 * One process-wide R-SDK connection shared by remote-control and GPS consumers.
 *
 * A second consumer may attach to the same camera, but a different camera or a live media lease is
 * rejected. The last consumer to detach closes GATT and releases the process camera lease.
 */
internal object RsdkSessionHub {
    interface Listener {
        fun onConnecting(cameraAddress: String, cameraName: String) = Unit
        fun onConnected() = Unit
        fun onStatus(status: RsdkProtocol.CameraStatus) = Unit
        fun onModeInfo(info: RsdkProtocol.ModeInfo) = Unit
        fun onVersion(info: RsdkProtocol.VersionInfo) = Unit
        fun onCommandResult(result: RsdkCommandResult) = Unit
        fun onDisconnected() = Unit
        fun onFailed(reason: String) = Unit
        fun onLog(message: String) = Unit
    }

    private val lock = Any()
    private val consumers = linkedMapOf<String, Listener>()
    private var controller: RsdkController? = null
    private var lease: CameraSessionLease? = null
    private var cameraAddress: String? = null
    private var cameraName: String? = null
    private var connected = false
    private var generation = 0L

    fun open(
        context: Context,
        address: String,
        name: String,
        consumerId: String,
        listener: Listener,
    ): Boolean {
        val normalized = address.uppercase()
        synchronized(lock) {
            val existing = controller
            if (existing != null) {
                if (cameraAddress != normalized) {
                    listener.onFailed("R-SDK is already connected to ${cameraName ?: cameraAddress}")
                    return false
                }
                consumers[consumerId] = listener
                listener.onConnecting(normalized, cameraName ?: name)
                if (connected) listener.onConnected()
                return true
            }

            val acquired = CameraSessionCoordinator.acquire(
                ownerId = OWNER_ID,
                cameraAddress = normalized,
                purpose = CameraSessionPurpose.RSDK_CONTROL,
            )
            if (acquired is CameraLeaseResult.Busy) {
                listener.onFailed(
                    "Camera is busy in ${acquired.active.purpose.name.lowercase().replace('_', ' ')} mode",
                )
                return false
            }

            lease = (acquired as CameraLeaseResult.Granted).lease
            consumers[consumerId] = listener
            cameraAddress = normalized
            cameraName = name
            connected = false
            val currentGeneration = ++generation
            val newController = RsdkController(context.applicationContext, SessionCallbacks(currentGeneration))
            controller = newController
            listener.onConnecting(normalized, name)

            return runCatching {
                val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(normalized)
                    ?: error("Bluetooth adapter unavailable")
                newController.connect(device)
                true
            }.getOrElse { error ->
                failGeneration(currentGeneration, error.message ?: "Unable to connect to camera")
                false
            }
        }
    }

    fun close(consumerId: String) {
        val toClose: RsdkController?
        synchronized(lock) {
            consumers.remove(consumerId)
            if (consumers.isNotEmpty()) return
            toClose = clearLocked()
        }
        toClose?.disconnect()
    }

    fun queryVersion(): Boolean = controller()?.queryVersion() ?: false
    fun capture(): Boolean = controller()?.capture() ?: false
    fun quickSwitch(): Boolean = controller()?.quickSwitch() ?: false
    fun snapshot(): Boolean = controller()?.snapshot() ?: false
    fun switchMode(mode: RsdkProtocol.CameraMode): Boolean = controller()?.switchMode(mode) ?: false
    fun setRecording(start: Boolean): Boolean = controller()?.setRecording(start) ?: false
    fun sleep(): Boolean = controller()?.sleep() ?: false
    fun restart(): Boolean = controller()?.restart() ?: false
    fun sendGpsPayload(payload: ByteArray): Boolean = controller()?.sendGpsPayload(payload) ?: false

    private fun controller(): RsdkController? = synchronized(lock) { controller.takeIf { connected } }

    private fun listeners(generation: Long): List<Listener> = synchronized(lock) {
        if (generation != this.generation) emptyList() else consumers.values.toList()
    }

    private fun failGeneration(callbackGeneration: Long, reason: String) {
        val targets: List<Listener>
        val toClose: RsdkController?
        synchronized(lock) {
            if (callbackGeneration != generation) return
            targets = consumers.values.toList()
            toClose = clearLocked()
        }
        targets.forEach { it.onFailed(reason) }
        toClose?.disconnect()
    }

    private fun disconnectedGeneration(callbackGeneration: Long) {
        val targets: List<Listener>
        val toClose: RsdkController?
        synchronized(lock) {
            if (callbackGeneration != generation) return
            targets = consumers.values.toList()
            toClose = clearLocked()
        }
        targets.forEach { it.onDisconnected() }
        toClose?.disconnect()
    }

    /** Caller closes the returned controller outside the lock. */
    private fun clearLocked(): RsdkController? {
        val old = controller
        controller = null
        connected = false
        consumers.clear()
        cameraAddress = null
        cameraName = null
        lease?.close()
        lease = null
        generation++
        return old
    }

    private class SessionCallbacks(private val callbackGeneration: Long) : RsdkController.Listener {
        override fun onLog(s: String) = RsdkSessionHub.listeners(callbackGeneration).forEach { it.onLog(s) }

        override fun onConnected() {
            synchronized(RsdkSessionHub.lock) {
                if (callbackGeneration != RsdkSessionHub.generation) return
                RsdkSessionHub.connected = true
            }
            RsdkSessionHub.listeners(callbackGeneration).forEach { it.onConnected() }
        }

        override fun onStatus(status: RsdkProtocol.CameraStatus) =
            RsdkSessionHub.listeners(callbackGeneration).forEach { it.onStatus(status) }

        override fun onModeInfo(info: RsdkProtocol.ModeInfo) =
            RsdkSessionHub.listeners(callbackGeneration).forEach { it.onModeInfo(info) }

        override fun onVersion(info: RsdkProtocol.VersionInfo) =
            RsdkSessionHub.listeners(callbackGeneration).forEach { it.onVersion(info) }

        override fun onCommandResult(result: RsdkCommandResult) =
            RsdkSessionHub.listeners(callbackGeneration).forEach { it.onCommandResult(result) }

        override fun onDisconnected() = RsdkSessionHub.disconnectedGeneration(callbackGeneration)
        override fun onFailed(reason: String) = RsdkSessionHub.failGeneration(callbackGeneration, reason)
    }

    private const val OWNER_ID = "rsdk-session-hub"
}
