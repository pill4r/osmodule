package dev.pillar.osmodule.rsdk

import android.net.Network
import android.os.SystemClock
import android.util.Log
import dev.pillar.osmodule.net.DumlTransport
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Osmo 360 local viewfinder transport.
 *
 * R-SDK control remains on BLE. Preview is a separate Mimo-compatible link over the camera SoftAP:
 * a legacy c15f UDP channel plus the 360-specific 92ec session, with TCP 7001 activation.
 * The interoperability flow is adapted from yesbhautik/osmo360 (MIT).
 */
internal class OsmoLiveViewClient(
    private val port: Int,
    private val tcpPoke: Boolean,
    private val listener: Listener,
) : AutoCloseable {
    private enum class VideoSource { LEGACY, SESSION }

    interface Listener {
        fun onDatalinkReady()
        fun onAccessUnit(accessUnit: ByteArray)
        fun onMetrics(videoPackets: Int, accessUnits: Int, videoBytes: Long, droppedFrames: Int)
        fun onFailure(message: String)
    }

    private val lifecycle = TransportCloseBarrier()
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val sendLock = Any()
    private val workers = CopyOnWriteArrayList<Thread>()
    private val videoPackets = AtomicInteger(0)
    private val accessUnits = AtomicInteger(0)
    private val videoBytes = AtomicLong(0)
    private val sourceLock = Any()
    private val legacyAssembler = Osmo360FrameAssembler()
    private val sessionAssembler = Osmo360FrameAssembler()

    @Volatile private var legacySocket: DatagramSocket? = null
    @Volatile private var sessionSocket: DatagramSocket? = null
    @Volatile private var tcpKeepaliveSocket: Socket? = null
    @Volatile private var cameraAddress: InetAddress? = null
    @Volatile private var lastVideoAt = 0L
    @Volatile private var activeVideoSource: VideoSource? = null
    private var openedAt = 0L

    private var legacyCounter = 0xA698
    private var legacyPreviousCounter = 0xA690
    private var legacyControlSequence = 0x0174
    private var legacyDumlSequence = 0xA487

    private var sessionCounter = 0x6498
    private var sessionPreviousCounter = 0x6490
    private var sessionControlSequence = 0x0101
    private var sessionDumlSequence = 0x2B9F
    private var sessionAckSequence = 0x1169
    private var sessionSequenceIndex = 0
    @Volatile private var lastSessionCounter = 0x6490
    @Volatile private var previousSessionCommandCounter = 0x6490
    @Volatile private var cameraStatusCounter = 0x6490
    @Volatile private var cameraMediaCounter = 0x6490

    fun start(network: Network) {
        check(started.compareAndSet(false, true)) { "Live-view client has already been started" }
        worker("osmodule.live.open") { open(network) }
    }

    fun requestKeyframe() {
        if (!lifecycle.isClosed && running.get()) {
            val target = cameraAddress ?: return
            runCatching {
                legacySocket?.let { sendLegacyStartup(it, target, compact = true) }
                sessionSocket?.let { sendSoftResume(it, target) }
            }.onFailure { Log.w(TAG, "live-view refresh failed", it) }
        }
    }

    private fun open(network: Network) {
        try {
            resetSession()
            val target = InetAddress.getByName(CAMERA_HOST)
            cameraAddress = target
            val legacy = openUdp(network, PREFERRED_LEGACY_PORT)
            val session = openUdp(network, PREFERRED_SESSION_PORT)
            legacySocket = legacy
            sessionSocket = session
            if (!lifecycle.runIfOpen { running.set(true) }) return
            openedAt = SystemClock.elapsedRealtime()

            startReceiver(legacy, isSession = false)
            startReceiver(session, isSession = true)
            startSessionAckPump()
            if (tcpPoke) {
                sendTcpActivation(network)
                startTcpKeepalive(network)
            }

            sendLegacyStartup(legacy, target, compact = false)
            sendSessionBootstrap(session, target)
            startKeepalive()
            if (!lifecycle.isClosed) listener.onDatalinkReady()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!lifecycle.isClosed) fail(e.message ?: "实时预览连接失败")
        }
    }

    private fun resetSession() = synchronized(sendLock) {
        legacyCounter = 0xA698
        legacyPreviousCounter = 0xA690
        legacyControlSequence = 0x0174
        legacyDumlSequence = 0xA487
        sessionCounter = 0x6498
        sessionPreviousCounter = 0x6490
        sessionControlSequence = 0x0101
        sessionDumlSequence = 0x2B9F
        sessionAckSequence = 0x1169
        sessionSequenceIndex = 0
        lastSessionCounter = 0x6490
        previousSessionCommandCounter = 0x6490
        cameraStatusCounter = 0x6490
        cameraMediaCounter = 0x6490
        videoPackets.set(0)
        accessUnits.set(0)
        videoBytes.set(0)
        legacyAssembler.reset()
        sessionAssembler.reset()
        activeVideoSource = null
        lastVideoAt = 0L
    }

    private fun openUdp(network: Network, preferredPort: Int): DatagramSocket {
        var preferredError: Exception? = null
        for (localPort in intArrayOf(preferredPort, 0)) {
            val candidate = DatagramSocket(null)
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(Inet4Address.getByName("0.0.0.0"), localPort))
                network.bindSocket(candidate)
                candidate.soTimeout = RECEIVE_TIMEOUT_MS
                runCatching { candidate.receiveBufferSize = UDP_RECEIVE_BUFFER_SIZE }
                candidate.connect(InetSocketAddress(CAMERA_HOST, port))
                if (!lifecycle.register(candidate)) throw InterruptedException("Live-view client closed")
                Log.i(
                    TAG,
                    "UDP $preferredPort opened on ${candidate.localPort}; " +
                        "receive buffer=${candidate.receiveBufferSize} bytes",
                )
                return candidate
            } catch (e: Exception) {
                candidate.close()
                if (localPort == preferredPort) preferredError = e else throw e
            }
        }
        throw preferredError ?: IllegalStateException("Unable to open UDP preview socket")
    }

    private fun sendTcpActivation(network: Network) {
        val subscribePayload = ByteArray(11).apply { this[10] = 0x03 }
        val subscribe = buildDumlFrame(0x08, 0x9988, 0x40, 0x02, 0x09, subscribePayload)
        val control = Socket()
        if (!lifecycle.register(control)) return
        runCatching {
            control.use {
                network.bindSocket(control)
                control.connect(InetSocketAddress(CAMERA_HOST, TCP_CONTROL_PORT), TCP_TIMEOUT_MS)
                control.soTimeout = 300
                writeTcp(control, TCP_ACTIVATION)
                Thread.sleep(120)
                writeTcp(control, subscribe)
            }
            Log.i(TAG, "TCP 7001 activation and live-view subscription sent")
        }.onFailure {
            if (!lifecycle.isClosed) {
                Log.i(TAG, "TCP 7001 activation unavailable; continuing with UDP", it)
            }
        }
        // `use` closed it first, so removing it cannot create an untracked-open-resource window.
        lifecycle.unregister(control)
    }

    private fun startTcpKeepalive(network: Network) = worker("osmodule.live.tcp") {
        val control = Socket()
        if (!lifecycle.register(control)) return@worker
        try {
            network.bindSocket(control)
            control.connect(InetSocketAddress(CAMERA_HOST, TCP_CONTROL_PORT), TCP_TIMEOUT_MS)
            control.soTimeout = 300
            tcpKeepaliveSocket = control
            writeTcp(control, TCP_ACTIVATION)
            while (running.get() && !lifecycle.isClosed) {
                Thread.sleep(TCP_KEEPALIVE_MS)
                writeTcp(control, byteArrayOf(0))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (running.get() && !lifecycle.isClosed) Log.i(TAG, "TCP keepalive stopped", e)
        } finally {
            runCatching { control.close() }
            lifecycle.unregister(control)
            if (tcpKeepaliveSocket === control) tcpKeepaliveSocket = null
        }
    }

    private fun writeTcp(socket: Socket, bytes: ByteArray) {
        check(lifecycle.runIfOpen {
            socket.getOutputStream().apply {
                write(bytes)
                flush()
            }
        }) { "Live-view client closed" }
    }

    private fun startReceiver(datagram: DatagramSocket, isSession: Boolean) = worker(
        if (isSession) "osmodule.live.rx.92ec" else "osmodule.live.rx.c15f",
    ) {
        val source = if (isSession) VideoSource.SESSION else VideoSource.LEGACY
        val sourceAssembler = if (isSession) sessionAssembler else legacyAssembler
        val buffer = ByteArray(MAX_DATAGRAM_SIZE)
        while (running.get() && !lifecycle.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                datagram.receive(packet)
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                // The receive loop must stay non-blocking. Decode/group media before answering any
                // occasional control request; ACKs are emitted by their own 40 Hz window pump.
                Osmo360PacketParser.videoFragment(bytes)?.let { fragment ->
                    videoPackets.incrementAndGet()
                    deliver(source, sourceAssembler.feed(fragment))
                }
                if (isSession) ingestSession(datagram, bytes)
            } catch (_: SocketTimeoutException) {
                deliver(source, sourceAssembler.flushIfStalled())
            } catch (e: Exception) {
                if (running.get() && !lifecycle.isClosed) fail(e.message ?: "实时预览传输中断")
                return@worker
            }
        }
    }

    private fun ingestSession(datagram: DatagramSocket, packet: ByteArray) {
        if (!Osmo360PacketParser.hasSessionMagic(packet) || packet.size < 20) return
        when (packet[6].toInt() and 0xFF) {
            0x01 -> {
                cameraStatusCounter = u16(packet, 10)
                synchronized(sendLock) { answerCameraRequestLocked(datagram, packet) }
            }
            0x02, 0x03 -> cameraMediaCounter = u16(packet, 10)
        }
    }

    /** Mimo advances the media receive window independently at roughly 40 Hz. */
    private fun startSessionAckPump() = worker("osmodule.live.ack.92ec") {
        try {
            while (running.get() && !lifecycle.isClosed) {
                sessionSocket?.let { socket ->
                    // This ACK only snapshots volatile receive cursors and does not consume command
                    // sequence numbers, so it must not wait behind the one-second bootstrap burst.
                    sendLocked(socket, buildSessionAckLocked())
                }
                Thread.sleep(SESSION_ACK_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (running.get() && !lifecycle.isClosed) Log.i(TAG, "92ec ACK pump stopped", e)
        }
    }

    private fun answerCameraRequestLocked(datagram: DatagramSocket, packet: ByteArray) {
        if (packet.size < 33 || (packet[6].toInt() and 0xFF) != 0x01) return
        var offset = SESSION_HEADER_SIZE
        while (offset + 13 <= packet.size) {
            if (packet[offset] != 0x55.toByte()) {
                offset++
                continue
            }
            val length = (packet[offset + 1].toInt() and 0xFF) or
                ((packet[offset + 2].toInt() and 0x03) shl 8)
            if (length < 13 || offset + length > packet.size) {
                offset++
                continue
            }
            val sender = packet[offset + 4].toInt() and 0xFF
            val receiver = packet[offset + 5].toInt() and 0xFF
            val sequence = ((packet[offset + 6].toInt() and 0xFF) shl 8) or
                (packet[offset + 7].toInt() and 0xFF)
            val flags = packet[offset + 8].toInt() and 0xFF
            val set = packet[offset + 9].toInt() and 0xFF
            val command = packet[offset + 10].toInt() and 0xFF
            if (receiver == APP_ADDRESS && flags == 0x40) {
                val response = when {
                    set == 0x00 && (command == 0x99 || command == 0x74) ->
                        buildSessionDumlLocked(sender, sequence, 0xC0, set, command, byteArrayOf(0))
                    set == 0x00 && command == 0x81 ->
                        buildSessionDumlLocked(sender, sequence, 0x80, set, command, APP_STATE)
                    set == 0x00 && command == 0x82 ->
                        buildSessionDumlLocked(sender, sequence, 0x80, set, command, byteArrayOf(0))
                    set == 0x00 && command == 0x88 ->
                        buildSessionDumlLocked(sender, sequence, 0x80, set, command, PREVIEW_PULSE)
                    else -> null
                }
                if (response != null) sendLocked(datagram, response)
            }
            offset += length
        }
    }

    private fun startKeepalive() = worker("osmodule.live.keepalive") {
        var round = 0
        var lastMetricsAt = 0L
        try {
            while (running.get() && !lifecycle.isClosed) {
                Thread.sleep(if (round == 0) 120 else KEEPALIVE_MS)
                round++
                val target = cameraAddress ?: continue
                synchronized(sendLock) {
                    sessionSocket?.let { session ->
                        if (round == 1) {
                            sendSessionDumlLocked(session, 0x28, 0x00, 0x88, PREVIEW_READY)
                            sendSessionDumlLocked(session, 0x48, 0x00, 0x81, APP_STATE, flags = 0x80)
                            sendSessionDumlLocked(session, 0x48, 0x00, 0x82, byteArrayOf(0), flags = 0x80)
                        }
                        sendLocked(session, buildSessionAckLocked())
                        sendSessionDumlLocked(session, 0x01, 0x00, 0x4F, RECEIVER_KEEPALIVE)
                        if (round % LIGHT_SUSTAIN_ROUNDS == 0) {
                            sendSessionDumlLocked(session, 0x48, 0x00, 0x81, APP_STATE, flags = 0x80)
                            sendSessionDumlLocked(session, 0x28, 0x00, 0x88, PREVIEW_PULSE, flags = 0x80)
                        }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                val stalledSince = lastVideoAt.takeIf { it > 0 } ?: openedAt
                if (now - stalledSince >= SOFT_RESUME_GRACE_MS && round % SOFT_RESUME_ROUNDS == 0) {
                    sessionSocket?.let { sendSoftResume(it, target) }
                    legacySocket?.let { sendLegacyStartup(it, target, compact = true) }
                }
                if (now - lastMetricsAt >= METRICS_INTERVAL_MS) {
                    lastMetricsAt = now
                    listener.onMetrics(
                        videoPackets.get(),
                        accessUnits.get(),
                        videoBytes.get(),
                        legacyAssembler.droppedUnits + sessionAssembler.droppedUnits,
                    )
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (running.get() && !lifecycle.isClosed) fail(e.message ?: "实时预览保活失败")
        }
    }

    private fun sendLegacyStartup(datagram: DatagramSocket, target: InetAddress, compact: Boolean) {
        val commands = if (compact) LEGACY_REFRESH_COMMANDS else LEGACY_START_COMMANDS
        synchronized(sendLock) {
            commands.forEach { command ->
                sendLocked(datagram, buildLegacyDumlLocked(command))
                if (!compact) Thread.sleep(12)
            }
            sendLocked(datagram, buildLegacyDumlLocked(Command(0x41, 0x09, 0xA8, LIVE_VIEW_ENABLE)))
        }
        Log.i(TAG, "Osmo 360 live-view probe sent to ${target.hostAddress}:$port")
    }

    private fun sendSessionBootstrap(datagram: DatagramSocket, target: InetAddress) {
        synchronized(sendLock) {
            sendLocked(datagram, SESSION_PRE_BOOTSTRAP)
            Thread.sleep(COMMAND_GAP_MS)
            sendLocked(datagram, buildSessionMarkerLocked(0x6490, 0x6490))
            Thread.sleep(COMMAND_GAP_MS)
            sendSessionDumlLocked(datagram, 0x28, 0x00, 0x88, PREVIEW_READY, sessionSequence = true)
            Thread.sleep(COMMAND_GAP_MS)
            sendSessionDumlLocked(
                datagram,
                0x03,
                0x03,
                0xDA,
                byteArrayOf(0x05, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                sessionSequence = true,
            )
            Thread.sleep(COMMAND_GAP_MS)
            sendSessionDumlLocked(
                datagram,
                0x88,
                0x00,
                0x74,
                SESSION_CAPTURE_STATE,
                sessionSequence = true,
            )
            Thread.sleep(COMMAND_GAP_MS)
            PROPERTY_NAMES.forEachIndexed { index, name ->
                sendSessionDumlLocked(
                    datagram,
                    0x28,
                    0x00,
                    0x99,
                    propertyRequestPayload(0xB7CB + index, name),
                )
                if (name == "cam_fov") {
                    sendLocked(datagram, buildSessionMarkerLocked(0x64F0, 0x6558))
                    sessionPreviousCounter = lastSessionCounter
                }
                Thread.sleep(COMMAND_GAP_MS)
            }
            repeat(9) {
                sendLocked(
                    datagram,
                    buildSessionDumlLocked(0x28, nextAckSequenceLocked(), 0xC0, 0x00, 0x99, byteArrayOf(0)),
                )
                Thread.sleep(COMMAND_GAP_MS)
            }
            sendSessionDumlLocked(datagram, 0x28, 0x00, 0x88, PREVIEW_READY)
            Thread.sleep(COMMAND_GAP_MS)
            sendSessionDumlLocked(datagram, 0x48, 0x08, 0x10, APP_STATE)
            Thread.sleep(COMMAND_GAP_MS)
            SAFE_SESSION_SETUP.forEach {
                sendSessionDumlLocked(datagram, it.receiver, it.set, it.id, it.payload)
                sendLocked(datagram, buildSessionAckLocked())
                Thread.sleep(COMMAND_GAP_MS)
            }
            sendSessionDumlLocked(datagram, 0x28, 0x00, 0x88, PREVIEW_PULSE, flags = 0x80)
        }
        Log.i(TAG, "Osmo 360 92ec session bootstrapped via ${target.hostAddress}:$port")
    }

    private fun sendSoftResume(datagram: DatagramSocket, target: InetAddress) = synchronized(sendLock) {
        sendLocked(datagram, buildSessionAckLocked())
        sendSessionDumlLocked(datagram, 0x28, 0x00, 0x88, PREVIEW_READY)
        sendSessionDumlLocked(datagram, 0x28, 0x00, 0x88, PREVIEW_PULSE, flags = 0x80)
        sendSessionDumlLocked(datagram, 0x48, 0x00, 0x81, APP_STATE, flags = 0x80)
        sendSessionDumlLocked(datagram, 0x48, 0x00, 0x82, byteArrayOf(0), flags = 0x80)
        sendSessionDumlLocked(datagram, 0x01, 0x00, 0x4F, RECEIVER_KEEPALIVE)
        Log.i(TAG, "Osmo 360 media refresh sent to ${target.hostAddress}")
    }

    private fun buildLegacyDumlLocked(command: Command): ByteArray {
        val frame = buildDumlFrame(
            command.receiver,
            legacyDumlSequence++ and 0xFFFF,
            0x40,
            command.set,
            command.id,
            command.payload,
        )
        val counter = legacyCounter and 0xFFFF
        val packet = buildTransportPacket(
            0x5F,
            0xC1,
            counter,
            legacyPreviousCounter,
            legacyControlSequence++ and 0xFFFF,
            frame,
        )
        legacyPreviousCounter = counter
        legacyCounter = (legacyCounter + 8) and 0xFFFF
        return packet
    }

    private fun sendSessionDumlLocked(
        datagram: DatagramSocket,
        receiver: Int,
        set: Int,
        id: Int,
        payload: ByteArray,
        flags: Int = 0x40,
        sessionSequence: Boolean = false,
    ) {
        val sequence = if (sessionSequence) nextSessionSequenceLocked() else nextDumlSequenceLocked()
        sendLocked(datagram, buildSessionDumlLocked(receiver, sequence, flags, set, id, payload))
    }

    private fun buildSessionDumlLocked(
        receiver: Int,
        sequence: Int,
        flags: Int,
        set: Int,
        id: Int,
        payload: ByteArray,
    ): ByteArray {
        val frame = buildDumlFrame(receiver, sequence, flags, set, id, payload)
        val counter = sessionCounter and 0xFFFF
        val packet = buildTransportPacket(
            0x92,
            0xEC,
            counter,
            sessionPreviousCounter,
            sessionControlSequence++ and 0xFFFF,
            frame,
        )
        previousSessionCommandCounter = sessionPreviousCounter
        lastSessionCounter = counter
        sessionPreviousCounter = counter
        sessionCounter = (sessionCounter + 8) and 0xFFFF
        return packet
    }

    private fun buildTransportPacket(
        magic0: Int,
        magic1: Int,
        counter: Int,
        previousCounter: Int,
        controlSequence: Int,
        body: ByteArray,
    ): ByteArray = ByteArray(SESSION_HEADER_SIZE + body.size).apply {
        this[0] = (size and 0xFF).toByte()
        this[1] = (0x80 or ((size ushr 8) and 0x3F)).toByte()
        this[2] = magic0.toByte()
        this[3] = magic1.toByte()
        putU16(this, 4, counter)
        this[6] = 0x05
        this[7] = xor8(this, 0, 7).toByte()
        putU16(this, 8, previousCounter)
        putU16(this, 10, counter)
        putU32(this, 16, controlSequence)
        body.copyInto(this, SESSION_HEADER_SIZE)
    }

    private fun buildSessionAckLocked(): ByteArray = ByteArray(34).apply {
        this[0] = 0x22
        this[1] = 0x80.toByte()
        this[2] = 0x92.toByte()
        this[3] = 0xEC.toByte()
        this[6] = 0x04
        this[7] = xor8(this, 0, 7).toByte()
        putU16(this, 8, cameraStatusCounter)
        putU16(this, 10, cameraStatusCounter)
        putU16(this, 16, cameraMediaCounter)
        putU16(this, 18, cameraMediaCounter)
        putU16(this, 24, previousSessionCommandCounter)
        putU16(this, 26, lastSessionCounter)
    }

    private fun buildSessionMarkerLocked(media: Int, command: Int): ByteArray = ByteArray(34).apply {
        this[0] = 0x22
        this[1] = 0x80.toByte()
        this[2] = 0x92.toByte()
        this[3] = 0xEC.toByte()
        this[6] = 0x04
        this[7] = xor8(this, 0, 7).toByte()
        putU16(this, 8, 0x6490)
        putU16(this, 10, 0x6490)
        putU16(this, 16, media)
        putU16(this, 18, media)
        putU16(this, 24, command)
        putU16(this, 26, lastSessionCounter)
    }

    private fun buildDumlFrame(
        receiver: Int,
        wireSequence: Int,
        flags: Int,
        set: Int,
        id: Int,
        payload: ByteArray,
    ): ByteArray = osmo360DumlFrame(receiver, wireSequence, flags, set, id, payload)

    private fun nextDumlSequenceLocked(): Int = (sessionDumlSequence and 0xFFFF).also {
        sessionDumlSequence = (sessionDumlSequence + 0x0100) and 0xFFFF
    }

    private fun nextAckSequenceLocked(): Int = (sessionAckSequence and 0xFFFF).also {
        sessionAckSequence = (sessionAckSequence + 0x0100) and 0xFFFF
    }

    private fun nextSessionSequenceLocked(): Int {
        val captured = intArrayOf(0x279F, 0x289F, 0x2A9F)
        val index = sessionSequenceIndex++
        return captured.getOrNull(index) ?: (0x2B9F + ((index - captured.size) * 0x0100)) and 0xFFFF
    }

    private fun propertyRequestPayload(id: Int, name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        return ByteArray(19 + bytes.size).apply {
            this[0] = 0x02
            this[1] = 0x02
            putU16(this, 4, id)
            this[11] = (bytes.size + 6).toByte()
            this[13] = bytes.size.toByte()
            bytes.copyInto(this, 15)
        }
    }

    private fun sendLocked(socket: DatagramSocket, bytes: ByteArray) {
        check(lifecycle.runIfOpen {
            socket.send(DatagramPacket(bytes, bytes.size))
        }) { "Live-view client closed" }
    }

    private fun deliver(source: VideoSource, units: List<ByteArray>) {
        if (units.isEmpty() || !selectVideoSource(source, units)) return
        units.forEach { accessUnit ->
            accessUnits.incrementAndGet()
            videoBytes.addAndGet(accessUnit.size.toLong())
            listener.onAccessUnit(accessUnit)
        }
    }

    /**
     * Both discovery sockets can receive media on some firmware. Their H.264 byte streams have
     * independent packet order, so combining them in one Annex-B assembler corrupts inter frames.
     * Keep each stream separate, prefer the 92ec session at an IDR, and fail over only at an IDR.
     */
    private fun selectVideoSource(source: VideoSource, units: List<ByteArray>): Boolean =
        synchronized(sourceLock) {
            val now = SystemClock.elapsedRealtime()
            val current = activeVideoSource
            val hasIdr = units.any(::containsAvcIdr)
            val shouldSwitch = when {
                current == null -> true
                current == source -> false
                source == VideoSource.SESSION && current == VideoSource.LEGACY && hasIdr -> true
                now - lastVideoAt >= VIDEO_SOURCE_STALL_MS && hasIdr -> true
                else -> return@synchronized false
            }
            if (shouldSwitch && current != source) {
                activeVideoSource = source
                Log.i(TAG, "Live-view media source ${source.name.lowercase()}")
            }
            lastVideoAt = now
            true
        }

    private fun containsAvcIdr(accessUnit: ByteArray): Boolean {
        var index = 0
        while (index + 3 < accessUnit.size) {
            val header = when {
                accessUnit[index] == 0.toByte() && accessUnit[index + 1] == 0.toByte() &&
                    accessUnit[index + 2] == 1.toByte() -> index + 3
                index + 4 < accessUnit.size && accessUnit[index] == 0.toByte() &&
                    accessUnit[index + 1] == 0.toByte() && accessUnit[index + 2] == 0.toByte() &&
                    accessUnit[index + 3] == 1.toByte() -> index + 4
                else -> {
                    index++
                    continue
                }
            }
            if ((accessUnit[header].toInt() and 0x1F) == 5) return true
            index = header + 1
        }
        return false
    }

    private fun worker(name: String, action: () -> Unit): Boolean {
        val thread = Thread(action, name).also {
            it.isDaemon = true
        }
        return lifecycle.runIfOpen {
            workers += thread
            thread.start()
        }
    }

    private fun fail(message: String) {
        if (lifecycle.close()) {
            running.set(false)
            listener.onFailure(message)
        }
    }

    override fun close() {
        running.set(false)
        lifecycle.close()
        val current = Thread.currentThread()
        val closingWorkers = workers.toList()
        closingWorkers.forEach { it.interrupt() }
        val joinDeadline = System.nanoTime() + CLOSE_JOIN_BUDGET_MS * 1_000_000L
        closingWorkers.forEach { thread ->
            val remainingMs = ((joinDeadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (thread !== current && remainingMs > 0) runCatching { thread.join(remainingMs) }
        }
        workers.clear()
        legacySocket = null
        sessionSocket = null
        tcpKeepaliveSocket = null
    }

    private data class Command(val receiver: Int, val set: Int, val id: Int, val payload: ByteArray)

    private companion object {
        const val TAG = "Osmo360LiveView"
        const val CAMERA_HOST = "192.168.2.1"
        const val TCP_CONTROL_PORT = 7001
        const val PREFERRED_LEGACY_PORT = 58350
        const val PREFERRED_SESSION_PORT = 58382
        const val TCP_TIMEOUT_MS = 1_500
        const val TCP_KEEPALIVE_MS = 900L
        const val RECEIVE_TIMEOUT_MS = 250
        const val COMMAND_GAP_MS = 12L
        const val KEEPALIVE_MS = 1_000L
        const val SESSION_ACK_INTERVAL_MS = 25L
        const val METRICS_INTERVAL_MS = 1_000L
        const val CLOSE_JOIN_BUDGET_MS = 350L
        const val SOFT_RESUME_GRACE_MS = 7_000L
        const val VIDEO_SOURCE_STALL_MS = 1_000L
        const val SOFT_RESUME_ROUNDS = 5
        const val LIGHT_SUSTAIN_ROUNDS = 10
        const val UDP_RECEIVE_BUFFER_SIZE = 8 * 1024 * 1024
        const val MAX_DATAGRAM_SIZE = 65_535
        const val SESSION_HEADER_SIZE = 20
        const val APP_ADDRESS = 0x02

        val TCP_ACTIVATION = hex("55110492021b299f400745000000009892")
        val SESSION_PRE_BOOTSTRAP = hex(
            "308092ec000000ce906464006400c005140000640000019001c005140000640014006400" +
                "c00514000064000101040102",
        )
        val SESSION_CAPTURE_STATE = hex(
            "2100000100000000060000000000000005000000000000001857b86f0100000005000000" +
                "000000001857b86f010000008d7f8640000c0000300100000000000040296e3601000000" +
                "f900dca7519d17ecb856b86f01000000982a6e36010000000057b86f0100000000ee1902" +
                "01000000e0726a360100000038bd00090100000024edb70201000000ff00000000000000" +
                "0000000000000000f900dca7519d17ec705ab86f01000000985ab86f01000000705ab86f" +
                "010000006857b86f010000003057b86f01000000",
        )
        val LIVE_VIEW_ENABLE = hex("00040200000000000000")
        val PREVIEW_READY = hex("1700002300415050000000000002")
        val PREVIEW_PULSE = hex("1a00000000")
        val RECEIVER_KEEPALIVE = hex("040000000000000000")
        val APP_STATE = hex(
            "004150500000000000000000000000000000000000000000000000000000000000000200" +
                "00000000000208000000000000000000000000000000000000000000",
        )

        val LEGACY_START_COMMANDS = listOf(
            Command(0x01, 0x02, 0x8E, hex("00011400")),
            Command(0x01, 0x02, 0x8E, hex("00011400")),
            Command(0x01, 0x02, 0x8E, hex("00010900")),
            Command(0x01, 0x02, 0xFF, hex("40150000000000000000000000000000000000000000000000000000000000000000")),
            Command(0x01, 0x00, 0x4F, RECEIVER_KEEPALIVE),
        )
        val LEGACY_REFRESH_COMMANDS = listOf(Command(0x01, 0x00, 0x4F, RECEIVER_KEEPALIVE))
        val SAFE_SESSION_SETUP = listOf(
            Command(0x48, 0x00, 0x01, ByteArray(29)),
            Command(0x01, 0x02, 0xFF, hex("40150000000000000000000000000000000000000000000000000000000000000000")),
            Command(0x41, 0x09, 0xA8, LIVE_VIEW_ENABLE),
            Command(0x01, 0x02, 0x8E, hex("00011400")),
            Command(0x01, 0x02, 0x8E, hex("00011500")),
            Command(0x01, 0x02, 0x8E, hex("00010800")),
            Command(0x01, 0x02, 0x8E, hex("00012000")),
            Command(0x01, 0x02, 0x8E, hex("00012900")),
            Command(0x01, 0x02, 0x8E, hex("00010600")),
        )
        val PROPERTY_NAMES = listOf(
            "camcap_mode_profile", "camcap_video_format", "camcap_fov", "camcap_iso",
            "camcap_photo_storage_format", "camcap_color_mode", "camcap_wb", "camcap_photo_size",
            "camcap_video_codec", "camcap_shutter", "camcap_photo_timer_interval",
            "camcap_exposure_mode", "camcap_zoom", "camcap_antiflicker", "camcap_sharpness",
            "camcap_denoise", "camcap_aperture", "camcap_shutter_max", "camcap_eis",
            "camcap_iso_auto_max", "camcap_loop_video_duration", "camcap_hyperlapse_ratio",
            "camcap_slowmotion_ratio", "camcap_timelapse_duration", "camcap_countdown",
            "camcap_photo_time_limited_burst_param", "camcap_pano_mode_type", "camcap_custom_mode",
            "camcap_events", "camcap_style_filter_density", "camcap_style_filter_mode", "cam_storage",
            "cam_status", "cam_record_time", "cam_expo_param", "shutter_param", "cam_photo_param_new",
            "cam_lapse_param", "cam_video_param_v2", "cam_image_effect", "v_quality_enhance_status",
            "cam_fov", "cam_lens_state", "cam_audio_status_v2", "audio_timecode_status", "temp_curve",
            "camcap_common", "cam_imu_calib_info", "timecode_info", "cam_custom_mode_params",
            "cam_super_slowmotion_status", "cam_pano_mode_type", "cam_style_filter_status",
        )

        fun hex(value: String): ByteArray = DumlTransport.hex(value)
        fun xor8(bytes: ByteArray, offset: Int, length: Int): Int {
            var result = 0
            for (index in offset until offset + length) result = result xor (bytes[index].toInt() and 0xFF)
            return result
        }
        fun putU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        fun putU32(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
