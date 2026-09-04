package dev.pillar.osmodule.rsdk

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.pillar.osmodule.ble.GattClient
import dev.pillar.osmodule.duml.DjiMessage
import java.util.ArrayDeque
import java.util.UUID
import kotlin.random.Random

enum class RsdkCommand {
    QUERY_VERSION,
    CAPTURE,
    QUICK_SWITCH,
    SNAPSHOT,
    SWITCH_MODE,
    START_RECORDING,
    STOP_RECORDING,
    SLEEP,
    RESTART,
}

enum class RsdkCommandOutcome { SUCCEEDED, REJECTED, TIMED_OUT, TRANSPORT_FAILED }

data class RsdkCommandResult(
    val command: RsdkCommand,
    val outcome: RsdkCommandOutcome,
    val sequence: Int,
    val returnCode: Int? = null,
    val detail: String? = null,
)

/**
 * Drives the DJI **R-SDK** control session (GPS + status), ported from Osmo-GPS-Controller-Demo's
 * `connect_logic`. Transport is the shared GATT (fff0 / notify fff4 / write fff5) via [GattClient],
 * but the frames are R-SDK ([RsdkProtocol]), not media-path DUML.
 *
 * Handshake ([connect_logic_protocol_connect]): send Connection Request (0x00/0x19) → the camera
 * shows an approval popup on first use → it sends a Connection Request back with `verify_mode=2`
 * (`verify_data=0` = approved) → we ACK it → connected. Then we subscribe to camera status
 * (0x1D/0x05) and stream GPS (0x00/0x17).
 */
class RsdkController(context: Context, private val listener: Listener) : GattClient.Listener {

    interface Listener {
        fun onLog(s: String)
        fun onConnected()
        fun onStatus(status: RsdkProtocol.CameraStatus)
        fun onModeInfo(info: RsdkProtocol.ModeInfo)
        fun onVersion(info: RsdkProtocol.VersionInfo) = Unit
        fun onCommandResult(result: RsdkCommandResult) = Unit
        fun onDisconnected()
        fun onFailed(reason: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var gatt: GattClient? = null
    private var seq = 0
    private var connected = false
    private var connectionAttempt = 0
    private val approvalTimeout = Runnable {
        if (!connected && connectionAttempt < CONNECTION_MAX_ATTEMPTS) {
            listener.onLog("R-SDK: no approval response — retrying the connection request")
            sendConnectionRequest()
        } else {
            fail("Camera didn't approve the R-SDK connection (approve it on-screen, then retry)")
        }
    }

    private data class QueuedCommand(
        val command: RsdkCommand,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray,
        val seq: Int,
        val attempts: Int = 0,
        val fallbackKey: RsdkProtocol.KeyCode? = null,
        val usedBestEffortFallback: Boolean = false,
    )

    private val commandQueue = ArrayDeque<QueuedCommand>()
    private var pendingCommand: QueuedCommand? = null
    private val commandTimeout = Runnable { onCommandTimeout() }

    // Stable per-install controller identity (the camera remembers us by these).
    private val prefs = appContext.getSharedPreferences("osmosis", Context.MODE_PRIVATE)
    private val deviceId: Int by lazy {
        prefs.getInt("rsdk_device_id", 0).takeIf { it != 0 }
            ?: (Random.nextInt() or 1).also { prefs.edit().putInt("rsdk_device_id", it).apply() }
    }
    private val controllerMac: ByteArray by lazy {
        prefs.getString("rsdk_mac", null)?.let { hex -> ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
            ?: ByteArray(6).also { Random.nextBytes(it); prefs.edit().putString("rsdk_mac", it.joinToString("") { b -> "%02x".format(b) }).apply() }
    }

    fun connect(device: BluetoothDevice) {
        if (gatt != null) disconnect()
        listener.onLog("R-SDK: connecting to ${device.address}")
        val gc = GattClient(appContext, this, armPairing = false)
        gatt = gc
        gc.connect(device)
    }

    fun disconnect() {
        main.removeCallbacks(approvalTimeout)
        main.removeCallbacks(statusPoll)
        main.removeCallbacks(commandTimeout)
        cancelCommands("R-SDK connection closed")
        connected = false
        gatt?.disconnect(); gatt?.close(); gatt = null
    }

    private fun nextSeq(): Int { seq = (seq + 1) and 0xFFFF; return seq }

    private fun sendImmediate(cmdSet: Int, cmdId: Int, cmdType: Int, payload: ByteArray, useSeq: Int = nextSeq()): Boolean {
        return gatt?.writeCommand(RsdkProtocol.frame(cmdSet, cmdId, cmdType, payload, useSeq)) ?: false
    }

    private fun enqueue(
        command: RsdkCommand,
        cmdSet: Int,
        cmdId: Int,
        payload: ByteArray,
        fallbackKey: RsdkProtocol.KeyCode? = null,
    ): Boolean {
        if (!connected) return false
        commandQueue += QueuedCommand(command, cmdSet, cmdId, payload, nextSeq(), fallbackKey = fallbackKey)
        pumpCommands()
        return true
    }

    fun queryVersion(): Boolean = enqueue(
        RsdkCommand.QUERY_VERSION, RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_VERSION_QUERY,
        RsdkProtocol.versionQuery(),
    )

    fun capture(): Boolean = enqueueKey(RsdkCommand.CAPTURE, RsdkProtocol.KeyCode.RECORD)
    fun quickSwitch(): Boolean = enqueueKey(RsdkCommand.QUICK_SWITCH, RsdkProtocol.KeyCode.QUICK_SWITCH)
    fun snapshot(): Boolean = enqueueKey(RsdkCommand.SNAPSHOT, RsdkProtocol.KeyCode.SNAPSHOT)

    private fun enqueueKey(command: RsdkCommand, key: RsdkProtocol.KeyCode): Boolean = enqueue(
        command, RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_KEY_REPORT, RsdkProtocol.keyEvent(key),
    )

    fun switchMode(mode: RsdkProtocol.CameraMode): Boolean = enqueue(
        RsdkCommand.SWITCH_MODE, RsdkProtocol.SET_CAMERA, RsdkProtocol.ID_MODE_SWITCH,
        RsdkProtocol.modeSwitch(deviceId, mode),
    )

    fun setRecording(start: Boolean): Boolean = enqueue(
        if (start) RsdkCommand.START_RECORDING else RsdkCommand.STOP_RECORDING,
        RsdkProtocol.SET_CAMERA, RsdkProtocol.ID_RECORD_CTRL, RsdkProtocol.recordControl(deviceId, start),
        fallbackKey = RsdkProtocol.KeyCode.RECORD,
    )

    fun sleep(): Boolean = enqueue(
        RsdkCommand.SLEEP, RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_POWER_MODE,
        RsdkProtocol.powerMode(RsdkProtocol.PowerMode.SLEEP),
    )

    fun restart(): Boolean = enqueue(
        RsdkCommand.RESTART, RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_DEVICE_RESTART,
        RsdkProtocol.deviceRestart(deviceId),
    )

    private fun pumpCommands() {
        if (!connected || pendingCommand != null) return
        val next = commandQueue.poll() ?: return
        transmit(next)
    }

    private fun transmit(command: QueuedCommand) {
        if (!connected) {
            finishCommand(command, RsdkCommandOutcome.TRANSPORT_FAILED, detail = "Not connected")
            return
        }
        val attempt = command.copy(attempts = command.attempts + 1)
        val frame = RsdkProtocol.frame(
            attempt.cmdSet, attempt.cmdId, RsdkProtocol.CMD_WAIT_RESULT, attempt.payload, attempt.seq,
        )
        if (gatt?.writeCommand(frame) != true) {
            pendingCommand = attempt
            if (attempt.attempts < COMMAND_MAX_ATTEMPTS) {
                listener.onLog("R-SDK: GATT busy for ${attempt.command}; retrying write")
                main.postDelayed({
                    if (pendingCommand?.seq == attempt.seq) {
                        pendingCommand = null
                        transmit(attempt)
                    }
                }, COMMAND_WRITE_RETRY_MS)
            } else {
                pendingCommand = null
                finishCommand(attempt, RsdkCommandOutcome.TRANSPORT_FAILED, detail = "BLE write was rejected")
            }
            return
        }
        pendingCommand = attempt
        main.removeCallbacks(commandTimeout)
        main.postDelayed(
            commandTimeout,
            if (attempt.command == RsdkCommand.SWITCH_MODE) MODE_SWITCH_COMMAND_TIMEOUT_MS
            else COMMAND_TIMEOUT_MS,
        )
    }

    private fun onCommandTimeout() {
        val command = pendingCommand ?: return
        pendingCommand = null
        // A mode switch can legitimately keep the camera busy for about three seconds. Re-sending
        // it halfway through that transition restarts/invalidates the transition on some bodies.
        if (command.command != RsdkCommand.SWITCH_MODE && command.attempts < COMMAND_MAX_ATTEMPTS) {
            listener.onLog("R-SDK: no ACK for ${command.command}; retrying (${command.attempts + 1}/$COMMAND_MAX_ATTEMPTS)")
            transmit(command)
        } else finishCommand(command, RsdkCommandOutcome.TIMED_OUT, detail = "No camera response")
    }

    private fun acceptCommandResponse(frame: RsdkProtocol.Frame): Boolean {
        val pending = pendingCommand ?: return false
        if (!frame.isResponse || frame.seq != pending.seq ||
            frame.cmdSet != pending.cmdSet || frame.cmdId != pending.cmdId
        ) return false

        main.removeCallbacks(commandTimeout)
        pendingCommand = null
        val code = RsdkProtocol.responseCode(frame)
        val version = if (pending.command == RsdkCommand.QUERY_VERSION)
            RsdkProtocol.parseVersionInfo(frame.payload) else null
        if (version != null) listener.onVersion(version)
        if (pending.command == RsdkCommand.QUERY_VERSION && version == null) {
            finishCommand(
                pending, RsdkCommandOutcome.REJECTED, returnCode = code, detail = "Malformed version response",
            )
            return true
        }
        finishCommand(
            pending,
            if (code == 0) RsdkCommandOutcome.SUCCEEDED else RsdkCommandOutcome.REJECTED,
            returnCode = code,
            detail = if (code == null) "Malformed response" else null,
        )
        return true
    }

    private fun finishCommand(
        command: QueuedCommand,
        outcome: RsdkCommandOutcome,
        returnCode: Int? = null,
        detail: String? = null,
    ) {
        // RECORD is a toggle, so it is safe only after an explicit rejection proves the idempotent
        // start/stop command did not land. A timeout is ambiguous; falling back then could undo a
        // successful start or restart a recording that the camera already stopped.
        if (outcome == RsdkCommandOutcome.REJECTED && command.fallbackKey != null) {
            listener.onLog(
                "R-SDK: ${command.command} direct control was not accepted — trying the generic RECORD key",
            )
            commandQueue.addFirst(
                QueuedCommand(
                    command = command.command,
                    cmdSet = RsdkProtocol.SET_GENERAL,
                    cmdId = RsdkProtocol.ID_KEY_REPORT,
                    payload = RsdkProtocol.keyEvent(command.fallbackKey),
                    seq = nextSeq(),
                    usedBestEffortFallback = true,
                ),
            )
            main.postDelayed({ pumpCommands() }, COMMAND_SPACING_MS)
            return
        }
        val reportedDetail = if (command.usedBestEffortFallback) {
            listOfNotNull(detail, "best-effort RECORD key fallback").joinToString("; ")
        } else detail
        listener.onCommandResult(
            RsdkCommandResult(command.command, outcome, command.seq, returnCode, reportedDetail),
        )
        main.postDelayed({ pumpCommands() }, COMMAND_SPACING_MS)
    }

    private fun cancelCommands(detail: String) {
        val cancelled = buildList {
            pendingCommand?.let(::add)
            addAll(commandQueue)
        }
        pendingCommand = null
        commandQueue.clear()
        cancelled.forEach {
            listener.onCommandResult(RsdkCommandResult(
                it.command, RsdkCommandOutcome.TRANSPORT_FAILED, it.seq, detail = detail,
            ))
        }
    }

    /** Push one GPS fix (0x00/0x17). Fields already in DJI units — see [RsdkProtocol.gpsPush].
     *  Returns whether the BLE write was actually issued, so a silently dying link is visible. */
    fun sendGpsPayload(payload: ByteArray): Boolean {
        if (!connected || pendingCommand != null || commandQueue.isNotEmpty()) return false
        return sendImmediate(
            RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_GPS_PUSH, RsdkProtocol.CMD_NO_RESPONSE, payload,
        )
    }

    // ---- GattClient.Listener -------------------------------------------------

    override fun onLog(s: String) = listener.onLog(s)

    override fun onReady(gatt: GattClient) {
        // BluetoothGatt callbacks arrive on a Binder thread. Keep the handshake, command queue and
        // timeout state on the main looper with the public controller calls that originate in the UI
        // and foreground service; ArrayDeque and pendingCommand are deliberately not cross-thread.
        main.post {
            if (this.gatt !== gatt) return@post
            connectionAttempt = 0
            sendConnectionRequest()
        }
    }

    private fun sendConnectionRequest() {
        // STEP 1: send the Connection Request. verify_mode=0 → camera decides / shows a code popup.
        connectionAttempt++
        val verifyData = Random.nextInt(0, 10000)
        listener.onLog(
            "R-SDK: sending connection request $connectionAttempt/$CONNECTION_MAX_ATTEMPTS " +
                "(approve on the camera if prompted)…",
        )
        sendImmediate(RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_CONNECTION, RsdkProtocol.CMD_WAIT_RESULT,
            RsdkProtocol.connectionRequest(deviceId, controllerMac, verifyMode = 0, verifyData = verifyData))
        main.removeCallbacks(approvalTimeout)
        main.postDelayed(approvalTimeout, CONNECTION_ATTEMPT_TIMEOUT_MS)
    }

    override fun onNotification(sourceChar: UUID, raw: ByteArray, parsed: DjiMessage?) {
        val snapshot = raw.copyOf()
        main.post {
            if (gatt != null) handleNotification(snapshot)
        }
    }

    private fun handleNotification(raw: ByteArray) {
        val f = RsdkProtocol.parse(raw)
        if (f == null) {
            // Dropping these silently made "the camera sends no status" and "it sends status we can't
            // read" the same observation. A frame the camera addressed to us and we threw away is
            // worth a line — capped, because a mis-framed link would otherwise flood the log.
            if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0xAA && unparsed++ < 20) {
                val len = if (raw.size >= 3) ((raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)) and 0x03FF else -1
                listener.onLog("R-SDK: unparsed frame, ${raw.size}B on the wire, header says ${len}B" +
                    (if (len > raw.size) " (fragmented)" else "") + " — ${raw.take(16).joinToString("") { "%02x".format(it) }}")
            }
            return
        }
        if (acceptCommandResponse(f)) return
        when {
            f.cmdSet == RsdkProtocol.SET_GENERAL && f.cmdId == RsdkProtocol.ID_CONNECTION -> onConnectionFrame(f)
            f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH ->
                RsdkProtocol.parseCameraStatus(f.payload)?.also { lastStatusMs = System.currentTimeMillis() }
                    ?.let(listener::onStatus)
                    ?: listener.onLog("R-SDK: status push too short to decode (${f.payload.size}B)")
            // The "new" status push: an Action 6 reports its mode here as text and never sends the
            // numeric 0x1D/0x02 at all, which is why the mode read as unknown on it.
            f.cmdSet == RsdkProtocol.SET_CAMERA && f.cmdId == RsdkProtocol.ID_STATUS_PUSH_NEW ->
                RsdkProtocol.parseNewCameraStatus(f.payload)?.also { lastStatusMs = System.currentTimeMillis() }
                    ?.let(listener::onModeInfo)
                    ?: listener.onLog("R-SDK: new status push not in the documented shape " +
                        "(${f.payload.size}B) — ${f.payload.take(8).joinToString("") { "%02x".format(it) }}")
            // Anything else the camera volunteers: we asked for a subscription and this is what came
            // back, so name it rather than discard it.
            else -> if (unhandled++ < 20)
                listener.onLog("R-SDK: unhandled %02x/%02x type=0x%02x %dB payload=%s  ascii=%s"
                    .format(f.cmdSet, f.cmdId, f.cmdType, f.payload.size,
                        f.payload.joinToString("") { "%02x".format(it) },
                        f.payload.joinToString("") { b ->
                            val c = b.toInt() and 0xFF
                            if (c in 0x20..0x7E) c.toChar().toString() else "."
                        }))
        }
    }

    private var unparsed = 0
    private var unhandled = 0

    /** Subscribe: push_mode 3 = periodic + on-change, push_freq 20 (2 Hz, fixed) — DJI's own values. */
    private fun subscribeStatus(): Boolean {
        if (pendingCommand != null || commandQueue.isNotEmpty()) return false
        return sendImmediate(
            RsdkProtocol.SET_CAMERA, RsdkProtocol.ID_STATUS_SUB, RsdkProtocol.CMD_NO_RESPONSE,
            RsdkProtocol.statusSubscription(pushMode = 3, pushFreq = 20),
        )
    }

    private var lastStatusMs = 0L
    private var polling = false

    /**
     * Keep the status feed alive on a camera that answers the subscription **once**.
     *
     * An Action 6 replies to `0x1D/0x05` with a single `0x1D/0x06` and then never pushes again — not
     * periodically, not when the mode changes, not when recording starts — so a client that subscribes
     * and waits, as DJI's demo does, shows the mode the camera happened to be in at connect for the
     * rest of the session.
     *
     * Re-subscribing only when the feed has gone quiet leaves a camera that *does* push properly
     * completely alone: on those, this never fires a single extra frame.
     */
    private val statusPoll = object : Runnable {
        override fun run() {
            if (!connected) return
            if (System.currentTimeMillis() - lastStatusMs > STATUS_STALE_MS) {
                if (!polling && lastStatusMs != 0L) {
                    polling = true
                    listener.onLog("R-SDK: camera answers the status subscription once — polling it instead")
                }
                subscribeStatus()
            }
            main.postDelayed(this, STATUS_POLL_MS)
        }
    }

    /** The camera's side of the 0x00/0x19 handshake — its approval/rejection command frame. */
    private fun onConnectionFrame(f: RsdkProtocol.Frame) {
        if (connected) return
        if (f.isResponse) { // an early response frame: ret_code at payload[4]
            if (f.payload.size > 4 && f.payload[4].toInt() != 0) fail("Camera rejected the connection (ret_code=${f.payload[4].toInt()})")
            return
        }
        // Command frame = connection_request_command_frame: verify_mode @26, verify_data @27 (u16 LE).
        if (f.payload.size < 29) return
        val verifyMode = f.payload[26].toInt() and 0xFF
        val verifyData = (f.payload[27].toInt() and 0xFF) or ((f.payload[28].toInt() and 0xFF) shl 8)
        if (verifyMode != 2) { listener.onLog("R-SDK: unexpected verify_mode=$verifyMode"); return }
        if (verifyData != 0) { fail("Camera rejected the R-SDK connection"); return }

        // Approved → ACK with the camera's seq, then we're connected.
        main.removeCallbacks(approvalTimeout)
        sendImmediate(RsdkProtocol.SET_GENERAL, RsdkProtocol.ID_CONNECTION, RsdkProtocol.ACK_NO_RESPONSE,
            RsdkProtocol.connectionResponse(deviceId, retCode = 0, cameraReserved = 0), useSeq = f.seq)
        connected = true
        listener.onLog("R-SDK: connected — subscribing to camera status")
        subscribeStatus()
        main.postDelayed(statusPoll, STATUS_POLL_MS)
        listener.onConnected()
    }

    override fun onDisconnected() {
        main.post {
            // An explicit close nulls gatt before its asynchronous disconnect callback arrives. The
            // hub already advanced its generation in that path, so only a live transport drop needs
            // to mutate this controller or notify its listener.
            if (gatt == null) return@post
            main.removeCallbacks(approvalTimeout)
            main.removeCallbacks(statusPoll)
            main.removeCallbacks(commandTimeout)
            cancelCommands("Camera disconnected")
            connected = false
            listener.onDisconnected()
        }
    }

    private companion object {
        /** How often to check the status feed. Also the poll rate on a camera that needs one. */
        const val STATUS_POLL_MS = 1500L
        /** Quiet for longer than this and the subscription is treated as not running. */
        const val STATUS_STALE_MS = 2000L
        const val COMMAND_TIMEOUT_MS = 1500L
        const val MODE_SWITCH_COMMAND_TIMEOUT_MS = 5_000L
        const val COMMAND_WRITE_RETRY_MS = 120L
        const val COMMAND_SPACING_MS = 80L
        const val COMMAND_MAX_ATTEMPTS = 2
        const val CONNECTION_MAX_ATTEMPTS = 2
        const val CONNECTION_ATTEMPT_TIMEOUT_MS = 25_000L
    }

    private fun fail(reason: String) {
        main.removeCallbacks(approvalTimeout)
        disconnect()
        listener.onFailed(reason)
    }
}
