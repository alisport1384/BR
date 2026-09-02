package com.bigrocket.service

/**
 * User-controlled traffic preference for the two physical paths.
 *
 * The configured scores are authoritative while both paths are available. Measured quality is
 * intentionally not allowed to override an explicit user preference: a higher score means a
 * higher routing share, regardless of which path currently has the lower RTT.
 */
object DynamicWeightCalculator {

    @Volatile
    private var wifiUserScore = 1

    @Volatile
    private var cellularUserScore = 1

    @Volatile
    private var currentWeights = NetworkWeights(50, 50)

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(NetworkWeights) -> Unit>()

    @Synchronized
    fun configureUserScores(wifiScore: Int, cellularScore: Int) {
        wifiUserScore = wifiScore.coerceIn(1, 5)
        cellularUserScore = cellularScore.coerceIn(1, 5)
        publish(preferredWeights())
    }

    fun wifiScore(): Int = wifiUserScore
    fun cellularScore(): Int = cellularUserScore

    /**
     * Registers a live consumer of preference changes. This makes a score change effective
     * immediately instead of waiting for the service's next health/weight cycle.
     */
    fun addWeightsListener(listener: (NetworkWeights) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(currentWeights)
        return { listeners.remove(listener) }
    }

    @Synchronized
    fun update(
        wifiAvailable: Boolean,
        wifiLatency: Long,
        cellularAvailable: Boolean,
        cellularLatency: Long
    ): NetworkWeights {
        // Latency is deliberately ignored for routing priority. It remains available to the
        // health/UI layer, but it must never silently reverse an explicit user preference.
        val weights = when {
            !wifiAvailable && !cellularAvailable -> NetworkWeights(0, 0)
            wifiAvailable && !cellularAvailable -> NetworkWeights(100, 0)
            !wifiAvailable && cellularAvailable -> NetworkWeights(0, 100)
            else -> preferredWeights()
        }
        publish(weights)
        return weights
    }

    @Synchronized
    fun resetForPathRecovery(): NetworkWeights {
        val weights = preferredWeights()
        publish(weights)
        return weights
    }

    @Synchronized
    fun clear() {
        publish(preferredWeights())
    }

    fun currentWeights(): NetworkWeights = currentWeights

    private fun preferredWeights(): NetworkWeights {
        val delta = ((cellularUserScore - wifiUserScore) * 10).coerceIn(-40, 40)
        val cellular = (50 + delta).coerceIn(10, 90)
        return NetworkWeights(100 - cellular, cellular)
    }

    @Synchronized
    private fun publish(weights: NetworkWeights) {
        currentWeights = weights
        listeners.forEach { listener -> runCatching { listener(weights) } }
    }
}
