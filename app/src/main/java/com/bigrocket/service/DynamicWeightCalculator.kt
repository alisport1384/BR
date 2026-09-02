package com.bigrocket.service

import kotlin.math.abs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Calculates routing shares from persistent user preference and observed path quality.
 *
 * A recovered path is deliberately reintroduced at 10%. It can earn share back only after
 * successful quality evidence; recovery must never reset it directly to the old 50/50 split.
 */
object DynamicWeightCalculator {

    private const val WINDOW_SIZE = 5
    private const val MIN_SAMPLES_FOR_DECISION = WINDOW_SIZE
    private const val ADVANTAGE_RATIO = 1.10
    private const val MAX_WEIGHT = 90
    private const val MIN_WEIGHT = 10
    private const val RECOVERY_START_WEIGHT = 10
    private const val RECOVERY_STEP = 10

    private enum class Path { WIFI, CELLULAR }

    private data class Sample(
        val wifiLatencyMs: Long,
        val cellularLatencyMs: Long
    )

    private val samples = ArrayDeque<Sample>(WINDOW_SIZE)
    private val weightListeners = CopyOnWriteArrayList<(NetworkWeights) -> Unit>()

    @Volatile private var wifiUserScore = 1
    @Volatile private var cellularUserScore = 1
    @Volatile private var currentWeights = NetworkWeights(50, 50)
    @Volatile private var recoveringPath: Path? = null
    @Volatile private var recoveryWeight = RECOVERY_START_WEIGHT

    @Synchronized
    fun configureUserScores(wifiScore: Int, cellularScore: Int) {
        wifiUserScore = wifiScore.coerceIn(1, 5)
        cellularUserScore = cellularScore.coerceIn(1, 5)
        // A user preference change is authoritative immediately. Do not leave an old
        // recovery state capable of overriding the newly selected priority.
        recoveringPath = null
        samples.clear()
        currentWeights = preferredWeights()
        publishWeights(currentWeights)
    }

    fun wifiScore(): Int = wifiUserScore
    fun cellularScore(): Int = cellularUserScore

    /** The path the user currently ranks higher. Equal scores intentionally return null. */
    fun preferredPath(): String? = when {
        wifiUserScore > cellularUserScore -> "wifi"
        cellularUserScore > wifiUserScore -> "cellular"
        else -> null
    }

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
            recoveringPath = null
            currentWeights = NetworkWeights(100, 0)
            return currentWeights
        }
        if (!wifiAvailable && cellularAvailable) {
            samples.clear()
            recoveringPath = null
            currentWeights = NetworkWeights(0, 100)
            return currentWeights
        }

        val wifi = wifiLatency.coerceAtLeast(1)
        val cellular = cellularLatency.coerceAtLeast(1)
        if (samples.size == WINDOW_SIZE) samples.removeFirst()
        samples.addLast(Sample(wifi, cellular))

        // While a path is being reintroduced, never let the normal preferred split immediately
        // jump it back to 50% (or 90%). It starts at 10% and only earns more after evidence.
        val recovery = recoveringPath
        if (recovery != null && samples.size < MIN_SAMPLES_FOR_DECISION) {
            currentWeights = recoveryWeights(recovery)
            return currentWeights
        }

        val wifiLatencies = samples.map { it.wifiLatencyMs }
        val cellularLatencies = samples.map { it.cellularLatencyMs }
        val wifiMedian = median(wifiLatencies)
        val cellularMedian = median(cellularLatencies)
        val wifiStability = medianAbsoluteDeviation(wifiLatencies, wifiMedian)
        val cellularStability = medianAbsoluteDeviation(cellularLatencies, cellularMedian)
        val wifiCost = (wifiMedian + wifiStability * 2L).coerceAtLeast(1L)
        val cellularCost = (cellularMedian + cellularStability * 2L).coerceAtLeast(1L)

        val wifiBetter = wifiCost.toDouble() * ADVANTAGE_RATIO < cellularCost.toDouble()
        val cellularBetter = cellularCost.toDouble() * ADVANTAGE_RATIO < wifiCost.toDouble()

        if (!wifiBetter && !cellularBetter) {
            if (recovery != null) {
                // No quality advantage yet: recovered path remains at its earned share.
                currentWeights = recoveryWeights(recovery)
            } else {
                currentWeights = preferredWeights()
            }
            return currentWeights
        }

        val wifiQuality = 1.0 / wifiCost.toDouble()
        val cellularQuality = 1.0 / cellularCost.toDouble()
        val total = wifiQuality + cellularQuality
        val rawWifi = ((wifiQuality / total) * 100.0).toInt()

        val qualityWeights = if (wifiBetter) {
            NetworkWeights(rawWifi.coerceIn(51, MAX_WEIGHT), 100 - rawWifi.coerceIn(51, MAX_WEIGHT))
        } else {
            NetworkWeights(rawWifi.coerceIn(MIN_WEIGHT, 49), 100 - rawWifi.coerceIn(MIN_WEIGHT, 49))
        }

        if (recovery != null) {
            val recoveredIsBetter = (recovery == Path.WIFI && wifiBetter) ||
                (recovery == Path.CELLULAR && cellularBetter)
            if (recoveredIsBetter) {
                recoveryWeight = (recoveryWeight + RECOVERY_STEP).coerceAtMost(
                    if (recovery == Path.WIFI) preferredWeights().wifiWeight
                    else preferredWeights().cellularWeight
                )
                currentWeights = if (recovery == Path.WIFI) {
                    NetworkWeights(recoveryWeight, 100 - recoveryWeight)
                } else {
                    NetworkWeights(100 - recoveryWeight, recoveryWeight)
                }
            } else {
                currentWeights = recoveryWeights(recovery)
            }
            // Once the recovered path has reached its user-preferred share, normal quality
            // adaptation may take over again.
            val preferred = preferredWeights()
            val reachedPreferred = currentWeights == preferred
            if (reachedPreferred) recoveringPath = null
            return currentWeights
        }

        currentWeights = qualityWeights
        return currentWeights
    }

    /** Reintroduces exactly one recovered path at 10%; the survivor owns the remaining 90%. */
    @Synchronized
    fun resetForPathRecovery(recoveredWifi: Boolean): NetworkWeights {
        samples.clear()
        recoveringPath = if (recoveredWifi) Path.WIFI else Path.CELLULAR
        recoveryWeight = RECOVERY_START_WEIGHT
        currentWeights = recoveryWeights(recoveringPath!!)
        publishWeights(currentWeights)
        return currentWeights
    }

    @Synchronized
    fun clear() {
        samples.clear()
        recoveringPath = null
        recoveryWeight = RECOVERY_START_WEIGHT
        currentWeights = preferredWeights()
        publishWeights(currentWeights)
    }

    fun currentWeights(): NetworkWeights = currentWeights

    /** Registers a listener for routing-weight changes. Returns an unregister action. */
    fun addWeightsListener(listener: (NetworkWeights) -> Unit): () -> Unit {
        weightListeners.add(listener)
        listener(currentWeights)
        return { weightListeners.remove(listener) }
    }

    private fun publishWeights(weights: NetworkWeights) {
        weightListeners.forEach { listener ->
            runCatching { listener(weights) }
        }
    }

    private fun recoveryWeights(path: Path): NetworkWeights = when (path) {
        Path.WIFI -> NetworkWeights(RECOVERY_START_WEIGHT, 100 - RECOVERY_START_WEIGHT)
        Path.CELLULAR -> NetworkWeights(100 - RECOVERY_START_WEIGHT, RECOVERY_START_WEIGHT)
    }.let { base ->
        if (recoveryWeight <= RECOVERY_START_WEIGHT) base else when (path) {
            Path.WIFI -> NetworkWeights(recoveryWeight, 100 - recoveryWeight)
            Path.CELLULAR -> NetworkWeights(100 - recoveryWeight, recoveryWeight)
        }
    }

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
