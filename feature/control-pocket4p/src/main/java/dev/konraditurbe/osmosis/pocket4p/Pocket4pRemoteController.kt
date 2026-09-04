package dev.konraditurbe.osmosis.pocket4p

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.konraditurbe.osmosis.duml.PocketCameraStatus
import dev.konraditurbe.osmosis.duml.PocketGimbalTelemetry
import dev.konraditurbe.osmosis.duml.PocketRemoteCommands
import dev.konraditurbe.osmosis.duml.PocketRemoteEvent
import dev.konraditurbe.osmosis.duml.PocketShootingMode
import dev.konraditurbe.osmosis.feature.control.pocket4p.R
import dev.konraditurbe.osmosis.modules.CameraSessionOwnerAcquire
import dev.konraditurbe.osmosis.modules.CameraSessionOwnerClient
import dev.konraditurbe.osmosis.modules.CameraSessionOwnerLease
import dev.konraditurbe.osmosis.modules.CameraSessionOwnerResult
import dev.konraditurbe.osmosis.net.ApJoiner
import dev.konraditurbe.osmosis.session.CameraLeaseResult
import dev.konraditurbe.osmosis.session.CameraSessionCoordinator
import dev.konraditurbe.osmosis.session.CameraSessionLease
import dev.konraditurbe.osmosis.session.CameraSessionPurpose
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

internal enum class Pocket4pConnectionPhase {
    DISCONNECTED,
    JOINING_WIFI,
    OPENING_DATALINK,
    READY,
    DISCONNECTING,
    FAILED,
}

internal data class Pocket4pRemoteState(
    val phase: Pocket4pConnectionPhase = Pocket4pConnectionPhase.DISCONNECTED,
    val cameraStatus: PocketCameraStatus? = null,
    val gimbalTelemetry: PocketGimbalTelemetry? = null,
    val zoomFactor: Double? = null,
    val lastAction: Pocket4pAction? = null,
    val actionSerial: Int = 0,
    val error: String? = null,
) {
    val canControl: Boolean get() = phase == Pocket4pConnectionPhase.READY
}

/** Owns Wi-Fi binding, the process-local camera lease, and one [Pocket4pSession]. */
internal class Pocket4pRemoteController(
    context: Context,
    private val cameraAddress: String,
    private val cameraName: String,
    private val ssid: String,
    private val passphrase: String,
    private val wpa3: Boolean,
    private val datalinkPort: Int,
    private val tcpPoke: Boolean,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onState(state: Pocket4pRemoteState)
        fun onLog(message: String)

        /** Called on the datalink thread; decode off the main/UI thread. */
        fun onAccessUnit(accessUnit: ByteArray)

        /** Called on the datalink thread immediately before a controlled live-view IDR request. */
        fun onLiveViewRestartRequested()
    }

    private class Attempt(
        val generation: Int,
        val lease: CameraSessionLease,
        val ownerLease: CameraSessionOwnerLease,
    ) {
        lateinit var joiner: ApJoiner
        @Volatile var session: Pocket4pSession? = null
        @Volatile var thread: Thread? = null
        val networkStarted = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        @Volatile var failure: String? = null

        fun release() {
            if (!released.compareAndSet(false, true)) return
            joiner.release()
            lease.close()
            ownerLease.close()
        }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val actionSerial = AtomicInteger(0)
    private val stateLock = Any()
    @Volatile private var attempt: Attempt? = null
    @Volatile private var ownerAcquire: CameraSessionOwnerAcquire? = null
    private var pendingOwnerGeneration: Int? = null
    @Volatile private var state = Pocket4pRemoteState()

    fun connect(): Boolean {
        if (ssid.isBlank()) {
            updateState(Pocket4pRemoteState(
                phase = Pocket4pConnectionPhase.FAILED,
                error = appContext.getString(R.string.pocket4p_missing_wifi),
            ))
            return false
        }
        val requestGeneration = synchronized(stateLock) {
            if (attempt != null || pendingOwnerGeneration != null) return false
            generation.incrementAndGet().also {
                pendingOwnerGeneration = it
                publishStateLocked(Pocket4pRemoteState(phase = Pocket4pConnectionPhase.JOINING_WIFI))
            }
        }
        val request = CameraSessionOwnerClient.acquireAsync(
            context = appContext,
            ownerId = SESSION_OWNER,
            cameraAddress = cameraAddress,
            purpose = CameraSessionPurpose.POCKET4P_CONTROL.name,
        ) { result ->
            onOwnerAcquired(requestGeneration, result)
        }
        synchronized(stateLock) {
            if (pendingOwnerGeneration == requestGeneration) ownerAcquire = request
            else request.cancel()
        }
        return true
    }

    private fun onOwnerAcquired(
        requestGeneration: Int,
        result: CameraSessionOwnerResult,
    ) {
        when (result) {
            is CameraSessionOwnerResult.Granted ->
                startAfterOwnerAcquired(requestGeneration, result.lease)
            is CameraSessionOwnerResult.Busy -> failPendingOwner(
                requestGeneration,
                appContext.getString(R.string.pocket4p_session_busy, result.active.ownerId),
            )
            is CameraSessionOwnerResult.Unavailable -> failPendingOwner(
                requestGeneration,
                appContext.getString(R.string.pocket4p_session_busy, result.reason),
            )
        }
    }

    private fun startAfterOwnerAcquired(
        requestGeneration: Int,
        ownerLease: CameraSessionOwnerLease,
    ) {
        if (synchronized(stateLock) { pendingOwnerGeneration != requestGeneration }) {
            ownerLease.close()
            return
        }

        val lease = when (val result = CameraSessionCoordinator.acquire(
            ownerId = SESSION_OWNER,
            cameraAddress = cameraAddress,
            purpose = CameraSessionPurpose.POCKET4P_CONTROL,
        )) {
            is CameraLeaseResult.Granted -> result.lease
            is CameraLeaseResult.Busy -> {
                ownerLease.close()
                failPendingOwner(
                    requestGeneration,
                    appContext.getString(R.string.pocket4p_session_busy, result.active.ownerId),
                )
                return
            }
        }

        val next = Attempt(requestGeneration, lease, ownerLease)
        next.joiner = ApJoiner(appContext, object : ApJoiner.Listener {
            override fun onLog(s: String) = deliverLog(s)

            override fun onNetwork(network: android.net.Network, link: android.net.LinkProperties?) {
                if (!next.networkStarted.compareAndSet(false, true)) return
                if (!updateAttemptState(next) {
                        it.copy(phase = Pocket4pConnectionPhase.OPENING_DATALINK, error = null)
                    }
                ) return
                startSession(next)
            }

            override fun onFailed(reason: String) {
                if (attempt !== next) return
                next.failure = reason
                finishAttempt(next)
            }

            override fun onLost() {
                if (attempt !== next || next.closing.get()) return
                next.failure = appContext.getString(R.string.pocket4p_wifi_lost)
                next.session?.stop()
                if (!next.networkStarted.get()) finishAttempt(next)
            }
        })
        val installed = synchronized(stateLock) {
            if (attempt != null || pendingOwnerGeneration != requestGeneration) {
                false
            } else {
                pendingOwnerGeneration = null
                ownerAcquire = null
                attempt = next
                publishStateLocked(Pocket4pRemoteState(phase = Pocket4pConnectionPhase.JOINING_WIFI))
                true
            }
        }
        if (!installed) {
            next.release()
            return
        }
        Pocket4pPluginRuntime.connected(next, cameraName)
        runCatching {
            next.joiner.join(ssid, passphrase, wpa3)
        }.onFailure { error ->
            next.failure = error.message ?: error.javaClass.simpleName
            finishAttempt(next)
        }
    }

    private fun failPendingOwner(requestGeneration: Int, reason: String) {
        synchronized(stateLock) {
            if (pendingOwnerGeneration != requestGeneration) return
            pendingOwnerGeneration = null
            ownerAcquire = null
            publishStateLocked(Pocket4pRemoteState(
                phase = Pocket4pConnectionPhase.FAILED,
                error = reason,
            ))
        }
    }

    fun disconnect() {
        val pending = synchronized(stateLock) {
            if (pendingOwnerGeneration == null) null
            else {
                pendingOwnerGeneration = null
                ownerAcquire.also { ownerAcquire = null }
            }
        }
        if (pending != null) {
            pending.cancel()
            updateState(Pocket4pRemoteState())
            return
        }
        val current = synchronized(stateLock) {
            val active = attempt
            if (active == null) {
                if (state.phase != Pocket4pConnectionPhase.DISCONNECTED) {
                    publishStateLocked(Pocket4pRemoteState())
                }
                null
            } else {
                active.closing.set(true)
                publishStateLocked(
                    state.copy(phase = Pocket4pConnectionPhase.DISCONNECTING, error = null),
                )
                active
            }
        } ?: return
        current.session?.stop()
        val worker = current.thread
        if (worker == null) {
            finishAttempt(current)
            return
        }
        Thread({
            if (worker !== Thread.currentThread()) runCatching { worker.join(GRACEFUL_CLOSE_MS) }
            if (worker.isAlive) {
                current.session?.forceClose()
                runCatching { worker.join(FORCED_CLOSE_MS) }
            }
            finishAttempt(current)
        }, "pocket4p.disconnect").apply { isDaemon = true }.start()
    }

    fun shootPhoto(): Boolean = activeSession()?.shootPhoto() ?: false
    fun setRecording(recording: Boolean): Boolean = activeSession()?.setRecording(recording) ?: false
    fun setMode(mode: PocketShootingMode): Boolean = activeSession()?.setMode(mode) ?: false
    fun setZoom(factor: Double): Boolean = activeSession()?.setZoom(factor) ?: false
    fun recenter(): Boolean = activeSession()?.recenter() ?: false
    fun flip(): Boolean = activeSession()?.flip() ?: false

    fun updateGimbal(x: Float, y: Float): Boolean {
        val travel = PocketRemoteCommands.GIMBAL_TRAVEL
        val pitch = PocketRemoteCommands.GIMBAL_CENTER + (-y.coerceIn(-1f, 1f) * travel).roundToInt()
        val yaw = PocketRemoteCommands.GIMBAL_CENTER + (x.coerceIn(-1f, 1f) * travel).roundToInt()
        return activeSession()?.updateGimbal(pitch, yaw) ?: false
    }

    fun restGimbal() {
        synchronized(stateLock) { attempt?.session }?.restGimbal()
    }

    private fun activeSession(): Pocket4pSession? = synchronized(stateLock) {
        attempt?.takeIf {
            state.phase == Pocket4pConnectionPhase.READY && !it.closing.get()
        }?.session
    }

    private fun startSession(current: Attempt) {
        val session = Pocket4pSession(
            port = datalinkPort,
            tcpPoke = tcpPoke,
            listener = object : Pocket4pSession.Listener {
                override fun onLog(message: String) = deliverLog(message)

                override fun onReady() {
                    updateAttemptState(current) {
                        it.copy(phase = Pocket4pConnectionPhase.READY, error = null)
                    }
                }

                override fun onEvent(event: PocketRemoteEvent) {
                    updateAttemptState(current) { previous -> when (event) {
                        is PocketRemoteEvent.CameraStatus ->
                            previous.copy(cameraStatus = event.value)
                        is PocketRemoteEvent.GimbalTelemetry ->
                            previous.copy(gimbalTelemetry = event.value)
                        is PocketRemoteEvent.Zoom -> previous.copy(zoomFactor = event.factor)
                    } }
                }

                override fun onAccessUnit(accessUnit: ByteArray) {
                    if (isActiveAttempt(current)) {
                        listener.onAccessUnit(accessUnit)
                    }
                }

                override fun onLiveViewRestartRequested() {
                    if (isActiveAttempt(current)) listener.onLiveViewRestartRequested()
                }

                override fun onActionSent(action: Pocket4pAction) {
                    updateAttemptState(current) {
                        it.copy(
                            lastAction = action,
                            actionSerial = actionSerial.incrementAndGet(),
                        )
                    }
                }
            },
        )
        current.session = session
        val worker = Thread({
            try {
                session.run()
            } catch (error: Throwable) {
                if (!current.closing.get()) {
                    current.failure = error.message ?: error.javaClass.simpleName
                    deliverLog("Pocket 4P: session failed (${current.failure})")
                }
            } finally {
                finishAttempt(current)
            }
        }, "pocket4p.datalink").apply { isDaemon = true }
        synchronized(stateLock) {
            // Disconnect may win while this callback is constructing the session. Never launch a
            // worker that has already lost ownership of its attempt/lease.
            if (attempt !== current || current.closing.get()) {
                session.stop()
                return
            }
            current.session = session
            current.thread = worker
            worker.start()
        }
    }

    private fun finishAttempt(done: Attempt) {
        done.release()
        synchronized(stateLock) {
            if (attempt !== done) return
            attempt = null
            Pocket4pPluginRuntime.disconnected(done)
            publishStateLocked(
                if (done.closing.get()) Pocket4pRemoteState()
                else Pocket4pRemoteState(
                    phase = Pocket4pConnectionPhase.FAILED,
                    error = done.failure ?: appContext.getString(R.string.pocket4p_session_ended),
                ),
            )
        }
    }

    private fun updateState(next: Pocket4pRemoteState) {
        synchronized(stateLock) { publishStateLocked(next) }
    }

    private fun updateAttemptState(
        current: Attempt,
        transform: (Pocket4pRemoteState) -> Pocket4pRemoteState,
    ): Boolean = synchronized(stateLock) {
        if (attempt !== current || current.closing.get()) return@synchronized false
        publishStateLocked(transform(state))
        true
    }

    private fun isActiveAttempt(current: Attempt): Boolean = synchronized(stateLock) {
        attempt === current && !current.closing.get()
    }

    /** Must be called while holding [stateLock] so Handler posts preserve reducer order. */
    private fun publishStateLocked(next: Pocket4pRemoteState) {
        state = next
        main.post { listener.onState(next) }
    }

    private fun deliverLog(message: String) {
        main.post { listener.onLog(message) }
    }

    /** Activity destruction cannot wait for the graceful async path before a replacement reconnects. */
    override fun close() {
        val pending = synchronized(stateLock) {
            pendingOwnerGeneration = null
            ownerAcquire.also { ownerAcquire = null }
        }
        pending?.cancel()
        val current = synchronized(stateLock) {
            val active = attempt
            active?.closing?.set(true)
            attempt = null
            publishStateLocked(Pocket4pRemoteState())
            active
        }
        current?.session?.stop()
        val worker = current?.thread
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(CLOSE_NEUTRAL_WAIT_MS) }
        }
        if (worker?.isAlive == true) current.session?.forceClose()
        current?.release()
        current?.let(Pocket4pPluginRuntime::disconnected)
    }

    private companion object {
        const val SESSION_OWNER = "pocket4p-remote"
        const val GRACEFUL_CLOSE_MS = 800L
        const val FORCED_CLOSE_MS = 300L
        const val CLOSE_NEUTRAL_WAIT_MS = 200L
    }
}
