package com.bigrocket.service

import kotlin.math.abs


/**
 * Weight decision cache for the two physical paths.
 *
 * The cache is intentionally scoped to one connected BigRocket session. It keeps a rolling
 * quality history for at most five seconds and never changes a 50/50 split unless the history
 * shows a meaningful quality advantage for one path. A path that disappears is handled as a
 * hard availability event and immediately gets 0%, while the surviving path gets 100%.
 */
object DynamicWeightCalculator {

    private const val WINDOW_SIZE = 5
    private const val MIN_SAMPLES_FOR_DECISION = WINDOW_SIZE
    private const val ADVANTAGE_RATIO = 1.10
    private const val MAX_WEIGHT = 85
    private const val MIN_WEIGHT = 15

    private data class Sample(
        val wifiLatencyMs: Long,
        val cellularLatencyMs: Long
    )

    private val samples = ArrayDeque<Sample>(WINDOW_SIZE)

    @Volatile
    private var currentWeights = NetworkWeights(50, 50)

    /**
     * Adds one quality sample and returns the current routing decision.
     *
     * With both paths healthy the default is always 50/50. A non-equal split is allowed only
     * after the rolling history demonstrates a stable advantage. The history never exceeds
     * five samples (the service samples once per second), so the decision is never more than
     * five seconds behind the real path quality.
     */
    @Synchronized
    fun update(
        wifiAvailable: Boolean,
        wifiLatency: Long,
        cellularAvailable: Boolean,
        cellularLatency: Long
    ): NetworkWeights {
        if (!wifiAvailable && !cellularAvailable) {
            clear()
            return NetworkWeights(0, 0)
        }

        if (wifiAvailable && !cellularAvailable) {
            samples.clear()
            currentWeights = NetworkWeights(100, 0)
            return currentWeights
        }

        if (!wifiAvailable && cellularAvailable) {
            samples.clear()
            currentWeights = NetworkWeights(0, 100)
            return currentWeights
        }

        val wifi = wifiLatency.coerceAtLeast(1)
        val cellular = cellularLatency.coerceAtLeast(1)

        if (samples.size == WINDOW_SIZE) samples.removeFirst()
        samples.addLast(Sample(wifi, cellular))

        if (samples.size < MIN_SAMPLES_FOR_DECISION) {
            currentWeights = NetworkWeights(50, 50)
            return currentWeights
        }

        val wifiLatencies = samples.map { it.wifiLatencyMs }
        val cellularLatencies = samples.map { it.cellularLatencyMs }
        val wifiMedian = median(wifiLatencies)
        val cellularMedian = median(cellularLatencies)

        // Stability is derived from the same rolling samples: median absolute deviation
        // penalizes a path whose latency is erratic even when its median latency is low.
        val wifiStability = medianAbsoluteDeviation(wifiLatencies, wifiMedian)
        val cellularStability = medianAbsoluteDeviation(cellularLatencies, cellularMedian)
        val wifiCost = wifiMedian + (wifiStability * 2L)
        val cellularCost = cellularMedian + (cellularStability * 2L)

        val wifiBetter = wifiCost.toDouble() * ADVANTAGE_RATIO < cellularCost.toDouble()
        val cellularBetter = cellularCost.toDouble() * ADVANTAGE_RATIO < wifiCost.toDouble()

        if (!wifiBetter && !cellularBetter) {
            currentWeights = NetworkWeights(50, 50)
            return currentWeights
        }

        val wifiScore = 1.0 / wifiCost.toDouble()
        val cellularScore = 1.0 / cellularCost.toDouble()
        val total = wifiScore + cellularScore
        val rawWifi = ((wifiScore / total) * 100.0).toInt()

        currentWeights = if (wifiBetter) {
            NetworkWeights(rawWifi.coerceIn(51, MAX_WEIGHT), 100 - rawWifi.coerceIn(51, MAX_WEIGHT))
        } else {
            val clamped = rawWifi.coerceIn(MIN_WEIGHT, 49)
            NetworkWeights(clamped, 100 - clamped)
        }
        return currentWeights
    }

    /** Called when one path returns. Its old quality history must not influence the new path. */
    @Synchronized
    fun resetForPathRecovery(): NetworkWeights {
        samples.clear()
        currentWeights = NetworkWeights(50, 50)
        return currentWeights
    }

    /** Clears all state for a complete BigRocket disconnect. */
    @Synchronized
    fun clear() {
        samples.clear()
        currentWeights = NetworkWeights(50, 50)
    }

    fun currentWeights(): NetworkWeights = currentWeights

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 1L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun medianAbsoluteDeviation(values: List<Long>, median: Long): Long {
        if (values.isEmpty()) return 0L
        return median(values.map { abs(it - median) })
    }
}
