package dev.pillar.osmodule.rsdk

import android.bluetooth.BluetoothManager
import android.content.Context
import java.util.Locale

/**
 * One process-wide R-SDK connection shared by remote-control and GPS consumers.
 *
 * [open] only accepts work; cross-process arbitration completes asynchronously. A generation gate
 * makes a close/reconnect win over a late grant before any GATT transport can be constructed.
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
    private var lease: RsdkCameraOwnership.Lease? = null
    private var ownershipRequest: RsdkCameraOwnership.Request? = null
    private var openingGeneration: Long? = null
    private var cameraAddress: String? = null
    private var cameraName: String? = null
    private var connected = false
    private var generation = 0L
    private var closingGeneration: Long? = null

    fun open(
        context: Context,
        address: String,
        name: String,
        consumerId: String,
        listener: Listener,
    ): Boolean {
        val normalized = address.trim().uppercase(Locale.ROOT)
        val currentGeneration: Long
        synchronized(lock) {
            if (normalized.isBlank()) {
                listener.onFailed("Invalid camera address")
                return false
            }
            if (closingGeneration != null) {
                listener.onFailed("R-SDK is still closing the previous camera connection")
                return false
            }
            controller?.let {
                if (cameraAddress != normalized) {
                    listener.onFailed("R-SDK is already connected to ${cameraName ?: cameraAddress}")
                    return false
                }
                consumers[consumerId] = listener
                listener.onConnecting(normalized, cameraName ?: name)
                if (connected) listener.onConnected()
                return true
            }
            openingGeneration?.let {
                if (cameraAddress != normalized) {
                    listener.onFailed("R-SDK is already opening ${cameraName ?: cameraAddress}")
                    return false
                }
                consumers[consumerId] = listener
                listener.onConnecting(normalized, cameraName ?: name)
                return true
            }

            consumers[consumerId] = listener
            cameraAddress = normalized
            cameraName = name
            connected = false
            currentGeneration = ++generation
            openingGeneration = currentGeneration
            listener.onConnecting(normalized, name)
        }

        val appContext = context.applicationContext
        val request = RsdkCameraOwnership.acquireAsync(appContext, normalized) { result ->
            completeOpen(appContext, currentGeneration, normalized, result)
        }
        val retain = synchronized(lock) {
            if (openingGeneration == currentGeneration) {
                ownershipRequest = request
                true
            } else {
                false
            }
        }
        if (!retain) request.cancel()
        return true
    }

    private fun completeOpen(
        context: Context,
        callbackGeneration: Long,
        normalizedAddress: String,
        result: RsdkCameraOwnership.Result,
    ) {
        when (result) {
            is RsdkCameraOwnership.Result.Busy -> {
                failOpening(callbackGeneration, result.reason)
            }

            is RsdkCameraOwnership.Result.Granted -> {
                val nextController = synchronized(lock) {
                    if (openingGeneration != callbackGeneration ||
                        generation != callbackGeneration ||
                        consumers.isEmpty()
                    ) {
                        null
                    } else {
                        ownershipRequest = null
                        openingGeneration = null
                        lease = result.lease
                        RsdkController(context, SessionCallbacks(callbackGeneration)).also {
                            controller = it
                        }
                    }
                }
                if (nextController == null) {
                    result.lease.close()
                    return
                }

                runCatching {
                    val device = context.getSystemService(BluetoothManager::class.java)
                        ?.adapter
                        ?.getRemoteDevice(normalizedAddress)
                        ?: error("Bluetooth adapter unavailable")
                    nextController.connect(device)
                }.onFailure { error ->
                    failGeneration(
                        callbackGeneration,
                        error.message ?: "Unable to connect to camera",
                    )
                }
            }
        }
    }

    private fun failOpening(callbackGeneration: Long, reason: String) {
        val targets = synchronized(lock) {
            if (openingGeneration != callbackGeneration || generation != callbackGeneration) {
                return
            }
            ownershipRequest = null
            openingGeneration = null
            ++generation
            connected = false
            cameraAddress = null
            cameraName = null
            consumers.values.toList().also { consumers.clear() }
        }
        targets.forEach { it.onFailed(reason) }
    }

    fun close(consumerId: String) {
        var pendingRequest: RsdkCameraOwnership.Request? = null
        var closing: ClosingSession? = null
        synchronized(lock) {
            if (consumers.remove(consumerId) == null) return
            if (consumers.isNotEmpty()) return
            if (openingGeneration != null) {
                pendingRequest = ownershipRequest
                ownershipRequest = null
                openingGeneration = null
                ++generation
                connected = false
                cameraAddress = null
                cameraName = null
            } else if (controller != null || lease != null) {
                closing = clearLocked()
            } else {
                cameraAddress = null
                cameraName = null
            }
        }
        pendingRequest?.cancel()
        closing?.let(::finishClosing)
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

    private fun listeners(callbackGeneration: Long): List<Listener> = synchronized(lock) {
        if (callbackGeneration != generation) emptyList() else consumers.values.toList()
    }

    private fun failGeneration(callbackGeneration: Long, reason: String) {
        val targets: List<Listener>
        val closing: ClosingSession
        synchronized(lock) {
            if (callbackGeneration != generation || controller == null) return
            targets = consumers.values.toList()
            closing = clearLocked()
        }
        finishClosing(closing)
        targets.forEach { it.onFailed(reason) }
    }

    private fun disconnectedGeneration(callbackGeneration: Long) {
        val targets: List<Listener>
        val closing: ClosingSession
        synchronized(lock) {
            if (callbackGeneration != generation || controller == null) return
            targets = consumers.values.toList()
            closing = clearLocked()
        }
        finishClosing(closing)
        targets.forEach { it.onDisconnected() }
    }

    private data class ClosingSession(
        val controller: RsdkController?,
        val lease: RsdkCameraOwnership.Lease?,
        val generation: Long,
    ) {
        /** Keep arbitration held until the old GATT is synchronously disconnected and closed. */
        fun closeTransportThenRelease() {
            try {
                controller?.disconnect()
            } finally {
                lease?.close()
            }
        }
    }

    private fun finishClosing(closing: ClosingSession) {
        try {
            closing.closeTransportThenRelease()
        } finally {
            synchronized(lock) {
                if (closingGeneration == closing.generation) closingGeneration = null
            }
        }
    }

    /** Detaches state under [lock]; the caller tears down transport before releasing ownership. */
    private fun clearLocked(): ClosingSession {
        val closingId = ++generation
        check(closingGeneration == null) { "R-SDK close already in progress" }
        closingGeneration = closingId
        val closing = ClosingSession(controller, lease, closingId)
        controller = null
        lease = null
        ownershipRequest = null
        openingGeneration = null
        connected = false
        consumers.clear()
        cameraAddress = null
        cameraName = null
        return closing
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
}
