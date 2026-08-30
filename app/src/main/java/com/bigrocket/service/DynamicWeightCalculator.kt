package com.bigrocket.service

/**
 * Session-scoped quality decision state for the two physical paths.
 *
 * User scores define the preferred/default split. There is deliberately no startup
 * sampling window or rolling 1..5 second cache: every quality update is evaluated
 * immediately from the latest successful latency measurement. The state is cleared
 * on a full disconnect and on path recovery; user scores are not session state.
 */
object DynamicWeightCalculator {

    private const val ADVANTAGE_RATIO = 1.10
    private const val MAX_WEIGHT = 85
    private const val MIN_WEIGHT = 15

    @Volatile
    private var wifiUserScore = 1

    @Volatile
    private var cellularUserScore = 1

    @Volatile
    private var currentWeights = NetworkWeights(50, 50)

    /**
     * Configures the persistent user preference. Valid range is 1..5 for each path.
     * Every point of score difference moves 10 percentage points from the lower-scored
     * path to the higher-scored path. Equal scores remain 50/50.
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
            currentWeights = NetworkWeights(100, 0)
            return currentWeights
        }

        if (!wifiAvailable && cellularAvailable) {
            currentWeights = NetworkWeights(0, 100)
            return currentWeights
        }

        val wifi = wifiLatency.coerceAtLeast(1)
        val cellular = cellularLatency.coerceAtLeast(1)
        val preferred = preferredWeights()

        // No rolling cache/window: the latest measurement is authoritative. A path
        // must be at least 10% better before its share is moved away from the user's
        // preferred/default split. Otherwise the configured preference is preserved.
        val wifiBetter = wifi.toDouble() * ADVANTAGE_RATIO < cellular.toDouble()
        val cellularBetter = cellular.toDouble() * ADVANTAGE_RATIO < wifi.toDouble()

        if (!wifiBetter && !cellularBetter) {
            currentWeights = preferred
            return currentWeights
        }

        val wifiQualityScore = 1.0 / wifi.toDouble()
        val cellularQualityScore = 1.0 / cellular.toDouble()
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

    /** Clears session quality state and restores the user's preferred/default split. */
    @Synchronized
    fun resetForPathRecovery(): NetworkWeights {
        currentWeights = preferredWeights()
        return currentWeights
    }

    /** Clears all session quality state on a complete BigRocket disconnect. */
    @Synchronized
    fun clear() {
        currentWeights = preferredWeights()
    }

    fun currentWeights(): NetworkWeights = currentWeights

    private fun preferredWeights(): NetworkWeights {
        val delta = ((cellularUserScore - wifiUserScore) * 10).coerceIn(-40, 40)
        val cellular = (50 + delta).coerceIn(10, 90)
        return NetworkWeights(100 - cellular, cellular)
    }
}
