package com.rummikub.core.network

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff calculator with jitter for resilient network reconnection.
 */
class ExponentialBackoff(
    private val initialDelayMs: Long = 500L,
    private val multiplier: Double = 1.5,
    private val maxDelayMs: Long = 5000L,
    private val jitterRatio: Double = 0.1
) {
    /**
     * Calculates the delay for [attempt] (1-based index).
     */
    fun getDelay(attempt: Int): Long {
        if (attempt <= 1) return initialDelayMs

        val calculated = initialDelayMs * multiplier.pow((attempt - 1).toDouble())
        val capped = min(calculated.toLong(), maxDelayMs)

        // Add pseudo-random jitter (+/- jitterRatio)
        val jitterRange = (capped * jitterRatio).toLong()
        val jitter = if (jitterRange > 0) Random.nextLong(-jitterRange, jitterRange + 1) else 0L

        return (capped + jitter).coerceIn(initialDelayMs, maxDelayMs)
    }
}
