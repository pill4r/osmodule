package dev.pillar.osmodule.session

/** The mutually exclusive ways an osmodule process can own a camera connection. */
enum class CameraSessionPurpose {
    MEDIA_OFFLOAD,
    RSDK_CONTROL,
    POCKET4P_CONTROL,
}

data class CameraSessionSnapshot(
    val ownerId: String,
    val cameraAddress: String,
    val purpose: CameraSessionPurpose,
)

sealed interface CameraLeaseResult {
    data class Granted(val lease: CameraSessionLease) : CameraLeaseResult
    data class Busy(val active: CameraSessionSnapshot) : CameraLeaseResult
}

/**
 * A process-wide lease for the camera's single control connection.
 *
 * The lease deliberately owns no Android or BLE types. Transport implementations acquire it before
 * opening a GATT/datalink and close it after every related socket has been torn down. This makes the
 * ownership rule testable without Android and prevents media and R-SDK from racing for one camera.
 */
class CameraSessionLease internal constructor(
    private val token: Long,
    val snapshot: CameraSessionSnapshot,
) : AutoCloseable {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        CameraSessionCoordinator.release(token)
    }
}

object CameraSessionCoordinator {
    private val lock = Any()
    private var nextToken = 1L
    private var active: Pair<Long, CameraSessionSnapshot>? = null

    fun acquire(
        ownerId: String,
        cameraAddress: String,
        purpose: CameraSessionPurpose,
    ): CameraLeaseResult = synchronized(lock) {
        require(ownerId.isNotBlank()) { "Session owner id must not be blank" }
        require(cameraAddress.isNotBlank()) { "Camera address must not be blank" }

        active?.let { return@synchronized CameraLeaseResult.Busy(it.second) }
        val token = nextToken++
        val snapshot = CameraSessionSnapshot(ownerId, cameraAddress.uppercase(), purpose)
        active = token to snapshot
        CameraLeaseResult.Granted(CameraSessionLease(token, snapshot))
    }

    fun current(): CameraSessionSnapshot? = synchronized(lock) { active?.second }

    internal fun release(token: Long) = synchronized(lock) {
        if (active?.first == token) active = null
    }

    /** Test-only reset; production code must release the lease it acquired. */
    internal fun resetForTest() = synchronized(lock) {
        active = null
        nextToken = 1L
    }
}
