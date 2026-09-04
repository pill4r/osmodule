package dev.konraditurbe.osmosis.pocket4p

import kotlin.math.abs

/** Pure velocity mapping for the spring-centred Pocket zoom rocker. */
internal object PocketZoomVelocity {
    const val MAX_FACTOR_PER_SECOND = 3.0
    const val DEAD_ZONE_FRACTION = 0.12f

    /** Upward displacement is positive zoom; a held displacement produces a constant rate. */
    fun factorPerSecond(displacementPx: Float, travelPx: Float): Double {
        if (travelPx <= 0f) return 0.0
        val normalized = (-displacementPx / travelPx).coerceIn(-1f, 1f)
        val magnitude = abs(normalized)
        if (magnitude <= DEAD_ZONE_FRACTION) return 0.0
        val scaled = (magnitude - DEAD_ZONE_FRACTION) / (1f - DEAD_ZONE_FRACTION)
        return normalized.sign * scaled.toDouble() * MAX_FACTOR_PER_SECOND
    }

    fun advance(
        factor: Double,
        factorPerSecond: Double,
        elapsedMs: Long,
        minFactor: Double,
        maxFactor: Double,
    ): Double = (factor + factorPerSecond * elapsedMs.coerceIn(0L, MAX_TICK_MS) / 1_000.0)
        .coerceIn(minFactor, maxFactor)

    private val Float.sign: Int get() = when {
        this > 0f -> 1
        this < 0f -> -1
        else -> 0
    }

    private const val MAX_TICK_MS = 100L
}
