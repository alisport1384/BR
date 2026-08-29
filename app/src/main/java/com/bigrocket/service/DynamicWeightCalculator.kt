package com.bigrocket.service

import kotlin.math.abs

/**
 * Session-scoped quality decision cache for the two physical paths.
 *
 * User scores define the preferred/default split. Quality history may change that split only
 * after the rolling five-sample window proves a meaningful advantage. The quality cache is
 * cleared on a full disconnect and on path recovery; user scores are not cache state.
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
    private var wifiUserScore = 1

    @Volatile
    private var cellularUserScore = 1

    @Volatile
    private var currentWeights = NetworkWeights(50, 50)

    /**
     * Configures the persistent user preference. Valid range is 1..5 for each path.
     *
     * The requested mapping is intentionally expressed as a preference delta: every point of
     * score difference moves 10 percentage points from the lower-scored path to the higher-scored
     * path. Score 1 is the neutral baseline: equal scores remain 50/50, while 2-vs-1 is 60/40.
     */
    @Synchronized
    fun configureUserScores(wifiScore: Int, cellularScore: Int) {
        wifiUserScore = wifiScore.coerceIn(1, 5)
        cellularUserScore = cellularScore.coerceIn(1, 5)
        currentWeights = preferredWeights()
    }

    fun wifiScore(): Int = wifiUserScore
    fun cellularScore(): Int = cellularUserScore

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

        val preferred = preferredWeights()

        if (samples.size < MIN_SAMPLES_FOR_DECISION) {
            currentWeights = preferred
            return currentWeights
        }

        val wifiLatencies = samples.map { it.wifiLatencyMs }
        val cellularLatencies = samples.map { it.cellularLatencyMs }
        val wifiMedian = median(wifiLatencies)
        val cellularMedian = median(cellularLatencies)

        val wifiStability = medianAbsoluteDeviation(wifiLatencies, wifiMedian)
        val cellularStability = medianAbsoluteDeviation(cellularLatencies, cellularMedian)
        val wifiCost = wifiMedian + (wifiStability * 2L)
        val cellularCost = cellularMedian + (cellularStability * 2L)

        val wifiBetter = wifiCost.toDouble() * ADVANTAGE_RATIO < cellularCost.toDouble()
        val cellularBetter = cellularCost.toDouble() * ADVANTAGE_RATIO < wifiCost.toDouble()

        // No statistically meaningful winner: preserve the user's preferred/default split.
        if (!wifiBetter && !cellularBetter) {
            currentWeights = preferred
            return currentWeights
        }

        val wifiQualityScore = 1.0 / wifiCost.toDouble()
        val cellularQualityScore = 1.0 / cellularCost.toDouble()
        val total = wifiQualityScore + cellularQualityScore
        val rawWifi = ((wifiQualityScore / total) * 100.0).toInt()

        currentWeights = if (wifiBetter) {
            val clamped = rawWifi.coerceIn(51, MAX_WEIGHT)
            NetworkWeights(clamped, 100 - clamped)
        } else {
            val clamped = rawWifi.coerceIn(MIN_WEIGHT, 49)
            NetworkWeights(clamped, 100 - clamped)
        }
        return currentWeights
    }

    /** Clears only session quality history; the persistent user scores remain intact. */
    @Synchronized
    fun resetForPathRecovery(): NetworkWeights {
        samples.clear()
        currentWeights = preferredWeights()
        return currentWeights
    }

    /** Clears all session cache state on a complete BigRocket disconnect. */
    @Synchronized
    fun clear() {
        samples.clear()
        currentWeights = preferredWeights()
    }

    fun currentWeights(): NetworkWeights = currentWeights

    private fun preferredWeights(): NetworkWeights {
        val delta = ((cellularUserScore - wifiUserScore) * 10).coerceIn(-40, 40)
        val cellular = (50 + delta).coerceIn(10, 90)
        return NetworkWeights(100 - cellular, cellular)
    }

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
