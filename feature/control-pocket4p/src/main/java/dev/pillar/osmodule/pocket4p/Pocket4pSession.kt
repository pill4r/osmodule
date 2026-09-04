package dev.pillar.osmodule.pocket4p

import android.util.Log
import dev.pillar.osmodule.duml.OsmoCommands
import dev.pillar.osmodule.duml.PocketDumlCommand
import dev.pillar.osmodule.duml.PocketRemoteCommands
import dev.pillar.osmodule.duml.PocketRemoteDecoder
import dev.pillar.osmodule.duml.PocketRemoteEvent
import dev.pillar.osmodule.duml.PocketShootingMode
import dev.pillar.osmodule.net.DumlAckProfile
import dev.pillar.osmodule.net.DumlTransport
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** User-visible operations serialized onto the single DUML send sequence. */
internal enum class Pocket4pAction {
    SHOOT_PHOTO,
    START_RECORDING,
    STOP_RECORDING,
    SET_MODE,
    SET_ZOOM,
    RECENTER_GIMBAL,
    FLIP_GIMBAL,
}

/**
 * One capture-mode Pocket 4 Pro session.
 *
 * The registration, three-window ACK pump, live-view enable, camera commands, and gimbal cadence are
 * adapted from OpenPocketCine (Apache-2.0), Copyright 2026 Erik Sutton and contributors. This is a
 * focused Kotlin integration rather than an embedded copy of the upstream application.
 *
 * DUML commands and stick writes happen on [run]'s thread. UI calls only update a latest-value mailbox
 * or enqueue a command; the independent ACK pump emits sequence-zero window ACKs through the
 * transport's send lock, so it never mutates or races the command sequence.
 */
internal class Pocket4pSession(
    private val port: Int,
    private val tcpPoke: Boolean,
    private val pairingToken: String = "osmo",
    private val listener: Listener,
) {
    interface Listener {
        fun onLog(message: String)
        fun onReady()
        fun onEvent(event: PocketRemoteEvent)
        fun onAccessUnit(accessUnit: ByteArray)
        fun onLiveViewRestartRequested()
        fun onActionSent(action: Pocket4pAction)
    }

    private data class QueuedAction(
        val action: Pocket4pAction,
        val command: PocketDumlCommand,
    )

    private val transport = DumlTransport(
        log = listener::onLog,
        port = port,
        ackProfile = DumlAckProfile.POCKET_LIVE,
        receiveBufferSizeBytes = LIVE_VIEW_RECEIVE_BUFFER_SIZE,
    )
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val transportFailure = AtomicReference<String?>(null)
    private val commands = ConcurrentLinkedQueue<QueuedAction>()
    /** Decoder-side backpressure asks the datalink thread for one fresh camera GOP. */
    private val decoderGopRequest = AtomicBoolean(false)
    /** Latest-value mailbox so a finger drag cannot build an obsolete zoom-command backlog. */
    private val zoomCommand = AtomicReference<QueuedAction?>(null)
    /** Serializes DUML command sequencing with the final neutral write performed by [stop]. */
    private val commandSendLock = Any()
    private val gimbalLock = Any()
    private val depacketizer = PocketHevcDepacketizer()
    private val liveViewRecovery = PocketLiveViewRecoveryGate()
    private val captureStateGate = PocketCaptureStateGate(
        statusTimeoutMs = CAPTURE_STATUS_TIMEOUT_MS,
        playbackTimeoutMs = PLAYBACK_EXIT_TIMEOUT_MS,
    )

    @Volatile private var registered = false
    private val pokeSocket = AtomicReference<Socket?>()
    private var ackThread: Thread? = null
    private var gimbalPitch = PocketRemoteCommands.GIMBAL_CENTER
    private var gimbalYaw = PocketRemoteCommands.GIMBAL_CENTER
    private var gimbalHeld = false
    private var sendGimbalRest = false
    private var lastLoggedMode: PocketShootingMode? = null

    fun run(host: String = CAMERA_HOST) {
        check(started.compareAndSet(false, true)) { "Pocket session cannot be started twice" }
        running.set(true)
        if (cancelled.get()) {
            running.set(false)
            return
        }
        try {
            transport.open(host)
            if (!running.get()) return
            if (tcpPoke) openTcpPoke(host)
            if (!running.get()) return

            val handshake = handshakePayload(transport.baseSeq)
            if (transport.handshake(handshake) == null) {
                error("Camera did not answer the UDP/$port session handshake")
            }
            transport.syncSeqToPeerChannel()
            listener.onLog(
                "Pocket 4P: datalink ready session=0x%04x base=0x%04x channel=0x%04x"
                    .format(transport.sessionId, transport.baseSeq, transport.cameraChannel),
            )

            // Register in the same order as Mimo/OpenPocketCine. The status subscriptions must settle
            // before 09/A8; enabling in the same burst is ignored by a cold camera.
            sendWindowAck()
            send(appDeviceInfo())
            sendWindowAck()
            send(appPresence())
            sendWindowAck()
            send(PocketRemoteCommands.gimbalInit())
            sendWindowAck()
            captureStateGate.begin(monotonicMs())
            subscribeStatus()
            registered = true
            startAckPump()
            drainFor(SUBSCRIBE_SETTLE_MS)
            awaitCaptureState()
            transportFailure.get()?.let(::error)
            if (!running.get()) return
            send(PocketRemoteCommands.gimbalReadiness())
            send(PocketRemoteCommands.prepareLiveView())
            send(PocketRemoteCommands.enableLiveView())
            liveViewRecovery.begin(monotonicMs())
            listener.onLog(
                "Pocket 4P: capture mode confirmed; live-view prepare + enable sent once",
            )
            Log.i(TAG, "initial live-view enable sent")
            listener.onReady()

            eventLoop()
            transportFailure.get()?.let(::error)
        } finally {
            running.set(false)
            stopAckPump()
            if (registered && transport.isOpen) {
                // A missing neutral packet can leave the physical gimbal moving after touch-up,
                // backgrounding, or Activity teardown. Always make one best-effort stop write.
                runCatching { send(PocketRemoteCommands.gimbalNeutral()) }
                runCatching { transport.sendAck() }
            }
            registered = false
            runCatching { pokeSocket.getAndSet(null)?.close() }
            transport.close()
            depacketizer.reset()
        }
    }

    fun stop() {
        cancelled.set(true)
        commands.clear()
        zoomCommand.set(null)
        synchronized(gimbalLock) {
            gimbalHeld = false
            sendGimbalRest = true
        }
        running.set(false)
        // Closing only from run()'s finally left a short but observable window where 0x00/0x88 and
        // live ACKs could still keep the camera's remote indicator alive after the UI said Disconnect.
        // Make neutral the final command, then close UDP synchronously so recv and the ACK pump stop
        // before the controller gives up the NetworkSpecifier and camera ownership leases.
        if (registered && transport.isOpen) {
            synchronized(commandSendLock) {
                if (transport.isOpen) {
                    val neutral = PocketRemoteCommands.gimbalNeutral()
                    runCatching {
                        transport.sendDuml(
                            set = neutral.cmdSet,
                            cmd = neutral.cmdId,
                            payload = neutral.payload,
                            receiverType = neutral.receiverType,
                            receiverId = neutral.receiverId,
                            cmdType = neutral.cmdType,
                        )
                    }
                    runCatching { transport.sendAck() }
                }
            }
        }
        transport.close()
        // This is also the cancellation handle while TCP/7001 connect is still blocking.
        runCatching { pokeSocket.getAndSet(null)?.close() }
    }

    /** Emergency close after a bounded graceful join. */
    fun forceClose() {
        cancelled.set(true)
        running.set(false)
        transport.close()
        runCatching { pokeSocket.getAndSet(null)?.close() }
    }

    fun shootPhoto(): Boolean = enqueue(Pocket4pAction.SHOOT_PHOTO, PocketRemoteCommands.shootPhoto())

    fun setRecording(recording: Boolean): Boolean = enqueue(
        if (recording) Pocket4pAction.START_RECORDING else Pocket4pAction.STOP_RECORDING,
        PocketRemoteCommands.setRecording(recording),
    )

    fun setMode(mode: PocketShootingMode): Boolean =
        enqueue(Pocket4pAction.SET_MODE, PocketRemoteCommands.setShootingMode(mode))

    fun setZoom(factor: Double): Boolean {
        if (!factor.isFinite() || !registered || !running.get()) return false
        zoomCommand.set(QueuedAction(Pocket4pAction.SET_ZOOM, PocketRemoteCommands.setZoom(factor)))
        return true
    }

    fun requestFreshLiveView(): Boolean {
        if (!registered || !running.get()) return false
        decoderGopRequest.set(true)
        return true
    }

    fun recenter(): Boolean = enqueue(Pocket4pAction.RECENTER_GIMBAL, PocketRemoteCommands.recenter())

    fun flip(): Boolean = enqueue(Pocket4pAction.FLIP_GIMBAL, PocketRemoteCommands.flip())

    fun updateGimbal(pitch: Int, yaw: Int): Boolean {
        if (!registered || !running.get()) return false
        if (pitch !in PocketRemoteCommands.GIMBAL_MIN..PocketRemoteCommands.GIMBAL_MAX ||
            yaw !in PocketRemoteCommands.GIMBAL_MIN..PocketRemoteCommands.GIMBAL_MAX
        ) return false
        synchronized(gimbalLock) {
            gimbalPitch = pitch
            gimbalYaw = yaw
            gimbalHeld = true
            sendGimbalRest = false
        }
        return true
    }

    fun restGimbal() {
        synchronized(gimbalLock) {
            gimbalPitch = PocketRemoteCommands.GIMBAL_CENTER
            gimbalYaw = PocketRemoteCommands.GIMBAL_CENTER
            gimbalHeld = false
            sendGimbalRest = true
        }
    }

    private fun enqueue(action: Pocket4pAction, command: PocketDumlCommand): Boolean {
        if (!registered || !running.get()) return false
        commands += QueuedAction(action, command)
        return true
    }

    private fun eventLoop() {
        var lastInboundAt = monotonicMs()
        var nextPresenceAt = lastInboundAt + PRESENCE_INTERVAL_MS
        var nextGimbalProbeAt = lastInboundAt + GIMBAL_PROBE_INTERVAL_MS
        var nextStickAt = lastInboundAt
        while (running.get()) {
            val incoming = transport.recvAll(RECEIVE_SLICE_MS, RECEIVE_POLL_MS)
            if (!running.get()) break
            if (incoming.isNotEmpty()) lastInboundAt = monotonicMs()
            processIncoming(incoming)
            val now = monotonicMs()
            if (now - lastInboundAt >= INBOUND_TIMEOUT_MS) {
                error("Camera datalink stopped receiving packets for ${INBOUND_TIMEOUT_MS / 1_000}s")
            }
            if (now >= nextPresenceAt) {
                send(appPresence())
                nextPresenceAt = now + PRESENCE_INTERVAL_MS
            }
            if (now >= nextGimbalProbeAt) {
                send(PocketRemoteCommands.gimbalReadiness())
                nextGimbalProbeAt = now + GIMBAL_PROBE_INTERVAL_MS
            }
            if (now >= nextStickAt) {
                tickGimbal()
                nextStickAt = now + STICK_INTERVAL_MS
            }
            var commandSlots = MAX_COMMANDS_PER_TICK
            while (commandSlots-- > 0 && running.get()) {
                val next = zoomCommand.getAndSet(null) ?: commands.poll() ?: break
                send(next.command)
                if (next.action.isCameraSet) liveViewRecovery.onCameraSet(monotonicMs())
                if (next.action != Pocket4pAction.SET_ZOOM) listener.onActionSent(next.action)
            }
            recoverLiveViewIfNeeded(monotonicMs())
        }
    }

    private fun recoverLiveViewIfNeeded(nowMs: Long) {
        if (decoderGopRequest.getAndSet(false)) {
            requestFreshLiveViewGop(nowMs, "decoder dropped a reference frame")
            return
        }
        when (liveViewRecovery.tick(nowMs)) {
            PocketLiveViewRecoveryGate.Action.NONE -> Unit
            PocketLiveViewRecoveryGate.Action.RESEND_ENABLE -> {
                requestFreshLiveViewGop(nowMs, "HEVC stalled")
            }
            PocketLiveViewRecoveryGate.Action.EXHAUSTED -> {
                Log.w(TAG, "HEVC recovery enables exhausted")
                listener.onLog("Pocket 4P: HEVC recovery did not resume the preview")
            }
        }
    }

    private fun requestFreshLiveViewGop(nowMs: Long, reason: String) {
        // Keep the last presented image. Only discard the unfinished UDP AU and make the decoder
        // wait for the clean IRAP requested by 09/A8.
        depacketizer.reset()
        listener.onLiveViewRestartRequested()
        send(PocketRemoteCommands.prepareLiveView())
        send(PocketRemoteCommands.enableLiveView())
        liveViewRecovery.onEnableRequested(nowMs)
        Log.i(TAG, "$reason; requested a fresh live-view GOP")
        listener.onLog("Pocket 4P: $reason; requested a fresh live-view GOP")
    }

    private fun tickGimbal() {
        val axes = synchronized(gimbalLock) {
            when {
                sendGimbalRest -> {
                    sendGimbalRest = false
                    PocketRemoteCommands.GIMBAL_CENTER to PocketRemoteCommands.GIMBAL_CENTER
                }
                gimbalHeld -> gimbalPitch to gimbalYaw
                else -> null
            }
        } ?: return
        send(PocketRemoteCommands.gimbalStick(pitch = axes.first, yaw = axes.second))
    }

    private fun drainFor(durationMs: Long) {
        val deadline = monotonicMs() + durationMs
        while (running.get() && monotonicMs() < deadline) {
            processIncoming(transport.recvAll(RECEIVE_SLICE_MS, RECEIVE_POLL_MS))
        }
    }

    private fun awaitCaptureState() {
        var nextExitAt = 0L
        var nextPresenceAt = monotonicMs() + PRESENCE_INTERVAL_MS
        var exitWrites = 0
        while (running.get()) {
            val now = monotonicMs()
            when (captureStateGate.decision(now)) {
                PocketCaptureStateGate.Decision.WAIT_FOR_STATUS -> Unit
                PocketCaptureStateGate.Decision.EXIT_PLAYBACK -> if (now >= nextExitAt) {
                    send(PocketRemoteCommands.exitPlayback())
                    exitWrites++
                    nextExitAt = now + PLAYBACK_EXIT_INTERVAL_MS
                    listener.onLog("Pocket 4P: requested capture mode (playback exit #$exitWrites)")
                }
                PocketCaptureStateGate.Decision.CAPTURE_READY -> {
                    listener.onLog("Pocket 4P: camera status confirms capture mode")
                    return
                }
                PocketCaptureStateGate.Decision.STATUS_TIMEOUT ->
                    error("Pocket 4P did not publish camera status before live-view startup")
                PocketCaptureStateGate.Decision.PLAYBACK_TIMEOUT ->
                    error("Pocket 4P did not leave media playback before live-view startup")
            }

            if (now >= nextPresenceAt) {
                send(appPresence())
                nextPresenceAt = now + PRESENCE_INTERVAL_MS
            }
            processIncoming(transport.recvAll(RECEIVE_SLICE_MS, RECEIVE_POLL_MS))
        }
    }

    private fun startAckPump() {
        check(ackThread == null) { "Pocket ACK pump is already running" }
        ackThread = Thread({
            var consecutiveFailures = 0
            while (running.get() && registered && transport.isOpen) {
                val cycleStartedAt = monotonicMs()
                if (transport.sendAck()) {
                    consecutiveFailures = 0
                } else if (++consecutiveFailures >= MAX_ACK_SEND_FAILURES) {
                    transportFailure.compareAndSet(null, "Camera datalink ACK writes failed")
                    running.set(false)
                    break
                }
                val remaining = ACK_INTERVAL_MS - (monotonicMs() - cycleStartedAt)
                if (remaining > 0) {
                    try {
                        Thread.sleep(remaining)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "pocket4p.ack").also { thread ->
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun stopAckPump() {
        val worker = ackThread
        ackThread = null
        worker?.interrupt()
        if (worker !== Thread.currentThread()) runCatching { worker?.join(ACK_STOP_JOIN_MS) }
    }

    private fun processIncoming(datagrams: List<ByteArray>) {
        datagrams.forEach { datagram ->
            if (datagram.size > 20 && (datagram[6].toInt() and 0xFF) == 0x02) {
                depacketizer.feed(datagram)?.let { accessUnit ->
                    // A mode switch can leave the camera sending repeated or incomplete UDP
                    // fragments while no decodable HEVC frame is produced. Treating any fragment as
                    // healthy kept the recovery watchdog permanently disarmed. Only a complete AU
                    // proves that the live-view pipeline has actually advanced.
                    liveViewRecovery.onAccessUnit(monotonicMs())
                    listener.onAccessUnit(accessUnit)
                }
                return@forEach
            }
            DumlTransport.scanFrames(datagram).forEach { (set, id, payload) ->
                PocketRemoteDecoder.decode(set, id, payload)?.let { event ->
                    if (event is PocketRemoteEvent.CameraStatus) {
                        captureStateGate.onCameraStatus(
                            playback = event.value.isInPlayback,
                            nowMs = monotonicMs(),
                        )
                        if (event.value.shootingMode != lastLoggedMode) {
                            lastLoggedMode = event.value.shootingMode
                            Log.i(
                                TAG,
                                "camera mode=${event.value.shootingMode ?: event.value.shootingModeRaw}",
                            )
                        }
                    }
                    listener.onEvent(event)
                }
                if (set == 0x09 && id == 0xA8) {
                    val status = payload.firstOrNull()?.toInt()?.and(0xFF)
                    listener.onLog(
                        "Pocket 4P: live-view reply ${status?.let { "0x%02x".format(it) } ?: "empty"}",
                    )
                }
            }
        }
    }

    private fun subscribeStatus() {
        var id = FIRST_SUBSCRIPTION_ID
        for (key in SUBSCRIPTION_KEYS) {
            send(PocketDumlCommand(
                cmdSet = 0x00,
                cmdId = 0x99,
                payload = subscriptionPayload(key, id++),
                receiverType = 0x08,
                receiverId = 1,
            ))
        }
        sendWindowAck()
    }

    private fun send(command: PocketDumlCommand) {
        synchronized(commandSendLock) {
            check(transport.sendDuml(
                set = command.cmdSet,
                cmd = command.cmdId,
                payload = command.payload,
                receiverType = command.receiverType,
                receiverId = command.receiverId,
                cmdType = command.cmdType,
            )) { "Camera datalink write failed for %02x/%02x".format(command.cmdSet, command.cmdId) }
        }
    }

    private fun sendWindowAck() {
        check(transport.sendAck()) { "Camera datalink ACK write failed" }
    }

    private fun openTcpPoke(host: String) {
        val socket = Socket()
        check(pokeSocket.compareAndSet(null, socket)) { "TCP datalink poke is already open" }
        if (!running.get()) {
            pokeSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            return
        }
        runCatching {
            socket.connect(InetSocketAddress(host, TCP_POKE_PORT), TCP_CONNECT_TIMEOUT_MS)
            check(running.get()) { "Pocket session stopped during TCP datalink connect" }
            socket.getOutputStream().apply {
                write(OsmoCommands.setPairingPin(pairingToken))
                flush()
            }
            Thread.sleep(TCP_SETTLE_MS)
        }.onSuccess {
            listener.onLog("Pocket 4P: TCP/$TCP_POKE_PORT datalink poke ready")
        }.onFailure {
            pokeSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            if (running.get()) {
                listener.onLog("Pocket 4P: TCP/$TCP_POKE_PORT poke failed (${it.message}); trying UDP")
            }
        }
    }

    private fun appDeviceInfo() = PocketDumlCommand(
        cmdSet = 0x00,
        cmdId = 0x81,
        payload = OsmoCommands.APP_DEVICE_INFO,
        receiverType = 0x08,
        receiverId = 2,
        cmdType = 4,
    )

    private fun appPresence() = PocketDumlCommand(
        cmdSet = 0x00,
        cmdId = 0x88,
        payload = APP_PRESENCE,
        receiverType = 0x08,
        receiverId = 1,
    )

    private fun subscriptionPayload(name: String, subId: Int): ByteArray {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        val innerLength = bytes.size + 6
        return byteArrayOf(0x02, 0x02, 0x00, 0x00) +
            DumlTransport.le32(subId) + byteArrayOf(0, 0, 0) +
            byteArrayOf((innerLength and 0xFF).toByte(), ((innerLength ushr 8) and 0xFF).toByte()) +
            byteArrayOf((bytes.size and 0xFF).toByte(), ((bytes.size ushr 8) and 0xFF).toByte()) +
            bytes + byteArrayOf(0, 0, 0, 0)
    }

    private fun handshakePayload(baseSeq: Int): ByteArray = DumlTransport.hex(
        "000064006400c005140000640000019001c005140000640014006400c00514000064000101040102",
    ).also {
        it[0] = baseSeq.toByte()
        it[1] = (baseSeq ushr 8).toByte()
    }

    private companion object {
        const val LIVE_VIEW_RECEIVE_BUFFER_SIZE = 8 * 1024 * 1024
        const val TAG = "Pocket4pSession"
        const val CAMERA_HOST = "192.168.2.1"
        const val TCP_POKE_PORT = 7001
        const val TCP_CONNECT_TIMEOUT_MS = 2_000
        const val TCP_SETTLE_MS = 400L
        const val SUBSCRIBE_SETTLE_MS = 150L
        const val CAPTURE_STATUS_TIMEOUT_MS = 2_000L
        const val PLAYBACK_EXIT_TIMEOUT_MS = 4_000L
        const val PLAYBACK_EXIT_INTERVAL_MS = 450L
        const val ACK_INTERVAL_MS = 25L
        const val ACK_STOP_JOIN_MS = 150L
        const val MAX_ACK_SEND_FAILURES = 3
        const val STICK_INTERVAL_MS = 40L
        const val PRESENCE_INTERVAL_MS = 1_000L
        const val GIMBAL_PROBE_INTERVAL_MS = 2_000L
        const val RECEIVE_SLICE_MS = 15L
        const val RECEIVE_POLL_MS = 8
        // Shooting-mode changes can pause both status and HEVC while the camera rebuilds its
        // pipeline. Six seconds produced false disconnects on a healthy SoftAP.
        const val INBOUND_TIMEOUT_MS = 15_000L
        const val MAX_COMMANDS_PER_TICK = 3
        const val FIRST_SUBSCRIPTION_ID = 0x69DF

        val APP_PRESENCE = DumlTransport.hex("170046237c415050000000000002")
        val SUBSCRIPTION_KEYS = listOf(
            "camcap_mode_profile",
            "camcap_video_format",
            "camcap_fov",
            "camcap_iso",
            "camcap_shutter",
            "camcap_photo_storage_format",
            "camcap_color_mode",
            "cam_storage",
            "cam_status",
            "timecode_info",
            "cam_expo_param",
            "cam_video_param_v2",
            "cam_record_time",
            "cam_image_effect",
            "cam_lens_state",
            "cam_fov",
            "cam_audio_status_v2",
        )

        fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

        val Pocket4pAction.isCameraSet: Boolean
            get() = when (this) {
                Pocket4pAction.SHOOT_PHOTO,
                Pocket4pAction.START_RECORDING,
                Pocket4pAction.STOP_RECORDING,
                Pocket4pAction.SET_MODE,
                Pocket4pAction.SET_ZOOM,
                -> true
                Pocket4pAction.RECENTER_GIMBAL,
                Pocket4pAction.FLIP_GIMBAL,
                -> false
            }
    }
}
