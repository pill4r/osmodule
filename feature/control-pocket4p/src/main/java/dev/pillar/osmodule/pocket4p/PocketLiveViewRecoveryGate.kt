package dev.pillar.osmodule.pocket4p

/**
 * Small state machine for the Pocket live-view watchdog.
 *
 * Camera SETs can legitimately pause HEVC for a few seconds. Once that grace has elapsed, a
 * silent encoder receives at most two well-spaced `0x09/0xA8` IDR/live-view requests. This avoids
 * both the permanent black preview after a shooting-mode change and the destructive 1 Hz resend
 * loop that continually resets the camera's GOP.
 */
internal class PocketLiveViewRecoveryGate(
    private val stalledAfterMs: Long = STALLED_AFTER_MS,
    private val cameraSetGraceMs: Long = CAMERA_SET_GRACE_MS,
    private val initialEnableGraceMs: Long = INITIAL_ENABLE_GRACE_MS,
    private val recoverySpacingMs: Long = RECOVERY_SPACING_MS,
    private val maxRecoveryEnables: Int = MAX_RECOVERY_ENABLES,
) {
    enum class Action { NONE, RESEND_ENABLE, EXHAUSTED }

    private var startedAtMs = 0L
    private var lastAccessUnitAtMs: Long? = null
    private var lastCameraSetAtMs: Long? = null
    private var lastEnableAtMs = 0L
    private var recoveryEnables = 0
    private var exhaustedReported = false

    fun begin(nowMs: Long) {
        startedAtMs = nowMs
        lastAccessUnitAtMs = null
        lastCameraSetAtMs = null
        lastEnableAtMs = nowMs
        recoveryEnables = 0
        exhaustedReported = false
    }

    /** A complete HEVC access unit, not merely one UDP video fragment, advanced the preview. */
    fun onAccessUnit(nowMs: Long) {
        lastAccessUnitAtMs = nowMs
        recoveryEnables = 0
        exhaustedReported = false
    }

    fun onCameraSet(nowMs: Long) {
        lastCameraSetAtMs = nowMs
    }

    /** Suppress a duplicate watchdog enable while the requested GOP is still arriving. */
    fun onEnableRequested(nowMs: Long) {
        lastEnableAtMs = nowMs
    }

    fun tick(nowMs: Long): Action {
        val videoReference = lastAccessUnitAtMs ?: startedAtMs
        if (nowMs - videoReference < stalledAfterMs) return Action.NONE
        if (lastCameraSetAtMs?.let { nowMs - it < cameraSetGraceMs } == true) return Action.NONE

        val requiredEnableGap = if (lastAccessUnitAtMs == null && recoveryEnables == 0) {
            initialEnableGraceMs
        } else {
            recoverySpacingMs
        }
        if (nowMs - lastEnableAtMs < requiredEnableGap) return Action.NONE

        if (recoveryEnables >= maxRecoveryEnables) {
            if (exhaustedReported) return Action.NONE
            exhaustedReported = true
            return Action.EXHAUSTED
        }

        recoveryEnables++
        lastEnableAtMs = nowMs
        return Action.RESEND_ENABLE
    }

    companion object {
        const val STALLED_AFTER_MS = 2_000L
        const val CAMERA_SET_GRACE_MS = 4_000L
        const val INITIAL_ENABLE_GRACE_MS = 8_000L
        const val RECOVERY_SPACING_MS = 5_000L
        const val MAX_RECOVERY_ENABLES = 2
    }
}
