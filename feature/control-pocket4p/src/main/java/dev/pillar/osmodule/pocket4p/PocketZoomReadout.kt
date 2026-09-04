package dev.pillar.osmodule.pocket4p

import kotlin.math.abs

/** Keeps delayed camera telemetry from fighting the zoom value shown under the user's finger. */
internal class PocketZoomReadout(
    initialFactor: Double = 1.0,
    private val settleTolerance: Double = 0.12,
    private val settleTimeoutMs: Long = 650L,
) {
    var confirmedFactor: Double = initialFactor
        private set
    var requestedFactor: Double = initialFactor
        private set
    var displayFactor: Double = initialFactor
        private set
    var isTracking: Boolean = false
        private set
    val isSettling: Boolean get() = settlingTarget != null

    private var minFactor = 1.0
    private var maxFactor = 12.0
    private var settlingTarget: Double? = null
    private var settleDeadlineMs = 0L

    fun setBounds(minFactor: Double, maxFactor: Double) {
        this.minFactor = minFactor
        this.maxFactor = maxFactor.coerceAtLeast(minFactor)
        confirmedFactor = clamp(confirmedFactor)
        requestedFactor = clamp(requestedFactor)
        displayFactor = clamp(displayFactor)
        settlingTarget = settlingTarget?.let(::clamp)
        if (!isTracking && settlingTarget == null) {
            requestedFactor = confirmedFactor
            displayFactor = confirmedFactor
        }
    }

    /** Returns true when the confirmed value became visible immediately. */
    fun confirm(factor: Double, nowMs: Long): Boolean {
        confirmedFactor = clamp(factor)
        if (isTracking) return false

        val target = settlingTarget
        if (target != null && nowMs < settleDeadlineMs &&
            abs(confirmedFactor - target) > settleTolerance
        ) {
            return false
        }

        clearSettlement()
        requestedFactor = confirmedFactor
        displayFactor = confirmedFactor
        return true
    }

    fun beginTracking() {
        isTracking = true
        clearSettlement()
        requestedFactor = displayFactor
    }

    fun advance(factorPerSecond: Double, elapsedMs: Long) {
        if (!isTracking) return
        requestedFactor = PocketZoomVelocity.advance(
            factor = requestedFactor,
            factorPerSecond = factorPerSecond,
            elapsedMs = elapsedMs,
            minFactor = minFactor,
            maxFactor = maxFactor,
        )
        displayFactor = requestedFactor
    }

    fun finishTracking(nowMs: Long): Double {
        isTracking = false
        val target = requestedFactor
        if (abs(confirmedFactor - target) <= settleTolerance) {
            clearSettlement()
            requestedFactor = confirmedFactor
            displayFactor = confirmedFactor
        } else {
            settlingTarget = target
            settleDeadlineMs = nowMs + settleTimeoutMs
            displayFactor = target
        }
        return target
    }

    fun cancelTracking() {
        isTracking = false
        clearSettlement()
        requestedFactor = confirmedFactor
        displayFactor = confirmedFactor
    }

    fun expireSettlement(nowMs: Long): Boolean {
        if (isTracking || settlingTarget == null || nowMs < settleDeadlineMs) return false
        clearSettlement()
        requestedFactor = confirmedFactor
        displayFactor = confirmedFactor
        return true
    }

    fun millisecondsUntilSettlementExpires(nowMs: Long): Long? =
        settlingTarget?.let { (settleDeadlineMs - nowMs).coerceAtLeast(0L) }

    private fun clearSettlement() {
        settlingTarget = null
        settleDeadlineMs = 0L
    }

    private fun clamp(factor: Double): Double = factor.coerceIn(minFactor, maxFactor)
}
