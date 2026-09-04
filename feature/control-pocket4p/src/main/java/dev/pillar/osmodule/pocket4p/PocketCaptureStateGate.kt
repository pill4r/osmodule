package dev.pillar.osmodule.pocket4p

/**
 * Bounded capture-mode startup gate driven only by `0x02/0x80` camera-state pushes.
 *
 * Pocket refuses live view while its UI is in media playback. A successful exit command write is
 * not sufficient evidence that playback ended, so the gate opens only after a subsequent status
 * reports bit 30 clear.
 */
internal class PocketCaptureStateGate(
    private val statusTimeoutMs: Long,
    private val playbackTimeoutMs: Long,
) {
    enum class Decision {
        WAIT_FOR_STATUS,
        EXIT_PLAYBACK,
        CAPTURE_READY,
        STATUS_TIMEOUT,
        PLAYBACK_TIMEOUT,
    }

    private var started = false
    private var statusDeadlineMs = 0L
    private var playbackDeadlineMs: Long? = null
    private var isInPlayback: Boolean? = null

    init {
        require(statusTimeoutMs > 0)
        require(playbackTimeoutMs > 0)
    }

    fun begin(nowMs: Long) {
        started = true
        statusDeadlineMs = nowMs + statusTimeoutMs
        playbackDeadlineMs = null
        isInPlayback = null
    }

    fun onCameraStatus(playback: Boolean, nowMs: Long) {
        check(started) { "capture-state gate has not started" }
        isInPlayback = playback
        if (playback && playbackDeadlineMs == null) {
            playbackDeadlineMs = nowMs + playbackTimeoutMs
        }
    }

    fun decision(nowMs: Long): Decision {
        check(started) { "capture-state gate has not started" }
        return when (isInPlayback) {
            null -> if (nowMs >= statusDeadlineMs) Decision.STATUS_TIMEOUT else Decision.WAIT_FOR_STATUS
            false -> Decision.CAPTURE_READY
            true -> if (nowMs >= checkNotNull(playbackDeadlineMs)) {
                Decision.PLAYBACK_TIMEOUT
            } else {
                Decision.EXIT_PLAYBACK
            }
        }
    }
}
