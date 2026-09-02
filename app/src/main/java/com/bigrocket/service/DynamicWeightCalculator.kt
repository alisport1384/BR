package com.bigrocket.service

import kotlin.math.abs

/**
 * Calculates routing share and the independent identity/IP owner for the two physical paths.
 *
 * User scores are authoritative for routing whenever they differ. Observed quality may only
 * influence the routing split when the user gives both paths the same score.
 *
 * Identity/IP selection is intentionally independent from routing share. When scores are equal,
 * connection stability can move the identity owner from a flapping path to the other path.
 * The first owner is allowed 2 reconnects in a rolling minute; every subsequent owner receives
 * the next threshold (3, 4, ...). This escalation is only an identity rule and never changes
 * routing weights.
 */
object DynamicWeightCalculator {

    private const val WINDOW_SIZE = 5
    private const val MIN_SAMPLES_FOR_DECISION = WINDOW_SIZE
    private const val ADVANTAGE_RATIO = 1.10
    private const val MAX_WEIGHT = 85
    private const val MIN_WEIGHT = 15
    private const val RECOVERY_START_WEIGHT = 10
    private const val RECOVERY_STEP = 10

    private const val INITIAL_IDENTITY_RECONNECT_LIMIT = 2
    private const val IDENTITY_RECONNECT_WINDOW_MS = 60_000L

    private enum class Path { WIFI, CELLULAR }

    private data class Sample(
        val wifiLatencyMs: Long,
        val cellularLatencyMs: Long
    )

    private val samples = ArrayDeque<Sample>(WINDOW_SIZE)

    @Volatile private var wifiUserScore = 1
    @Volatile private var cellularUserScore = 1
    @Volatile private var currentWeights = NetworkWeights(50, 50)

    // Recovery/share state. This is deliberately separate from identity state.
    @Volatile private var recoveringPath: Path? = null
    @Volatile private var recoveryWeight = RECOVERY_START_WEIGHT

    // Identity/IP stability state. Never use these values to alter routing share.
    private val wifiReconnects = ArrayDeque<Long>()
    private val cellularReconnects = ArrayDeque<Long>()
    private var identityOwner: Path? = null
    private var identityReconnectLimit = INITIAL_IDENTITY_RECONNECT_LIMIT

    @Synchronized
    fun configureUserScores(wifiScore: Int, cellularScore: Int) {
        wifiUserScore = wifiScore.coerceIn(1, 5)
        cellularUserScore = cellularScore.coerceIn(1, 5)

        // A score change is an explicit user command. It immediately controls routing share.
        recoveringPath = null
        recoveryWeight = RECOVERY_START_WEIGHT
        samples.clear()
        currentWeights = preferredWeights()

        // Identity policy changes only its score-authority mode. Reconnect history remains
        // intact because it is real stability evidence, not routing state.
        if (wifiUserScore != cellularUserScore) {
            identityOwner = null
            identityReconnectLimit = INITIAL_IDENTITY_RECONNECT_LIMIT
        }
    }

    fun wifiScore(): Int = wifiUserScore
    fun cellularScore(): Int = cellularUserScore

    /** The path currently ranked higher by the user. Equal scores intentionally return null. */
    fun preferredPath(): String? = when {
        wifiUserScore > cellularUserScore -> "wifi"
        cellularUserScore > wifiUserScore -> "cellular"
        else -> null
    }

    /**
     * Selects which physical path may determine the externally visible IP/identity.
     *
     * Unequal user scores always win, regardless of latency or stability.
     * Equal scores activate the independent stability policy:
     *   - current identity owner starts with a 2-reconnect/minute allowance;
     *   - when that owner reaches its allowance, identity moves to the other path;
     *   - the new owner receives the next allowance (3, then 4, ...);
     *   - if the next owner reaches its allowance, the same rule continues.
     *
     * Routing weights are never changed by this method.
     */
    @Synchronized
    fun preferredIdentityPath(wifiAvailable: Boolean, cellularAvailable: Boolean): String? {
        if (!wifiAvailable && !cellularAvailable) return null
        if (wifiAvailable && !cellularAvailable) return "wifi"
        if (!wifiAvailable && cellularAvailable) return "cellular"

        // User preference is absolute for identity when scores differ.
        if (wifiUserScore > cellularUserScore) return "wifi"
        if (cellularUserScore > wifiUserScore) return "cellular"

        val wifiCount = reconnectCount(Path.WIFI)
        val cellularCount = reconnectCount(Path.CELLULAR)

        if (identityOwner == null) {
            // Equal scores: establish the first owner from stability evidence. If equally stable,
            // use the current routing split only as a deterministic tie-breaker for identity.
            identityOwner = when {
                wifiCount < cellularCount -> Path.WIFI
                cellularCount < wifiCount -> Path.CELLULAR
                currentWeights.wifiWeight >= currentWeights.cellularWeight -> Path.WIFI
                else -> Path.CELLULAR
            }
            identityReconnectLimit = INITIAL_IDENTITY_RECONNECT_LIMIT
        }

        val owner = identityOwner!!
        val ownerCount = reconnectCount(owner)
        if (ownerCount < identityReconnectLimit) {
            return owner.name.lowercase()
        }

        // The current owner has exceeded its stability allowance. Transfer identity only.
        val other = if (owner == Path.WIFI) Path.CELLULAR else Path.WIFI
        val nextLimit = (identityReconnectLimit + 1).coerceAtLeast(
            INITIAL_IDENTITY_RECONNECT_LIMIT + 1
        )
        val otherCount = reconnectCount(other)

        // The next owner gets the next stability allowance (3, then 4, ...). If it has
        // already exhausted that allowance, it must relinquish identity as well. With only
        // two physical paths there is then deliberately no identity owner until the rolling
        // reconnect history makes a path eligible again. This is safer than bouncing the IP
        // between two unstable paths.
        if (otherCount >= nextLimit) {
            identityOwner = null
            identityReconnectLimit = nextLimit
            return null
        }

        identityOwner = other
        identityReconnectLimit = nextLimit
        return other.name.lowercase()
    }

    /** Returns whether [path] is currently eligible to determine identity/IP. */
    @Synchronized
    fun isIdentityEligible(path: String): Boolean {
        val normalized = path.lowercase()
        if (wifiUserScore != cellularUserScore) {
            return preferredPath() == normalized
        }
        val selected = preferredIdentityPath(true, true)
        return selected == normalized
    }

    private fun reconnectCount(path: Path): Int {
        val now = System.currentTimeMillis()
        val queue = if (path == Path.WIFI) wifiReconnects else cellularReconnects
        while (queue.isNotEmpty() && now - queue.first() >= IDENTITY_RECONNECT_WINDOW_MS) {
            queue.removeFirst()
        }
        return queue.size
    }

    private fun recordReconnect(path: Path) {
        val now = System.currentTimeMillis()
        val queue = if (path == Path.WIFI) wifiReconnects else cellularReconnects
        queue.addLast(now)
        while (queue.isNotEmpty() && now - queue.first() >= IDENTITY_RECONNECT_WINDOW_MS) {
            queue.removeFirst()
        }
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

        // USER SCORES ARE AUTHORITATIVE. Quality is not allowed to override an unequal score.
        if (wifiUserScore != cellularUserScore) {
            recoveringPath = null
            currentWeights = preferredWeights()
            return currentWeights
        }

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
        val wifiCost = (wifiMedian + wifiStability * 2L).coerceAtLeast(1L)
        val cellularCost = (cellularMedian + cellularStability * 2L).coerceAtLeast(1L)

        val wifiBetter = wifiCost.toDouble() * ADVANTAGE_RATIO < cellularCost.toDouble()
        val cellularBetter = cellularCost.toDouble() * ADVANTAGE_RATIO < wifiCost.toDouble()

        if (!wifiBetter && !cellularBetter) {
            currentWeights = preferred
            return currentWeights
        }

        val wifiQualityScore = 1.0 / wifiCost.toDouble()
        val cellularQualityScore = 1.0 / cellularCost.toDouble()
        val total = wifiQualityScore + cellularQualityScore
        val rawWifi = ((wifiQualityScore / total) * 100.0).toInt()

        val qualityWeights = if (wifiBetter) {
            val clamped = rawWifi.coerceIn(51, MAX_WEIGHT)
            NetworkWeights(clamped, 100 - clamped)
        } else {
            val clamped = rawWifi.coerceIn(MIN_WEIGHT, 49)
            NetworkWeights(clamped, 100 - clamped)
        }

        // Recovery share is only a share rule. It remains independent from identity/IP.
        val recovery = recoveringPath
        if (recovery != null) {
            val recoveredIsBetter = (recovery == Path.WIFI && wifiBetter) ||
                (recovery == Path.CELLULAR && cellularBetter)

            if (recoveredIsBetter) {
                recoveryWeight = (recoveryWeight + RECOVERY_STEP).coerceAtMost(
                    if (recovery == Path.WIFI) preferred.wifiWeight else preferred.cellularWeight
                )
                currentWeights = if (recovery == Path.WIFI) {
                    NetworkWeights(recoveryWeight, 100 - recoveryWeight)
                } else {
                    NetworkWeights(100 - recoveryWeight, recoveryWeight)
                }
                if (currentWeights == preferred) recoveringPath = null
            } else {
                currentWeights = if (recovery == Path.WIFI) {
                    NetworkWeights(recoveryWeight, 100 - recoveryWeight)
                } else {
                    NetworkWeights(100 - recoveryWeight, recoveryWeight)
                }
            }
            return currentWeights
        }

        currentWeights = qualityWeights
        return currentWeights
    }

    /** Reintroduces a recovered path at 10%; this records the reconnect for identity stability. */
    @Synchronized
    fun resetForPathRecovery(recoveredWifi: Boolean): NetworkWeights {
        recordReconnect(if (recoveredWifi) Path.WIFI else Path.CELLULAR)
        samples.clear()
        recoveringPath = if (recoveredWifi) Path.WIFI else Path.CELLULAR
        recoveryWeight = RECOVERY_START_WEIGHT

        currentWeights = if (recoveredWifi) {
            NetworkWeights(RECOVERY_START_WEIGHT, 100 - RECOVERY_START_WEIGHT)
        } else {
            NetworkWeights(100 - RECOVERY_START_WEIGHT, RECOVERY_START_WEIGHT)
        }
        return currentWeights
    }

    /** Backward-compatible recovery entry point for callers that do not know the recovered path. */
    @Synchronized
    fun resetForPathRecovery(): NetworkWeights {
        samples.clear()
        recoveringPath = null
        recoveryWeight = RECOVERY_START_WEIGHT
        currentWeights = preferredWeights()
        return currentWeights
    }

    @Synchronized
    fun clear() {
        samples.clear()
        recoveringPath = null
        recoveryWeight = RECOVERY_START_WEIGHT
        currentWeights = preferredWeights()
        identityOwner = null
        identityReconnectLimit = INITIAL_IDENTITY_RECONNECT_LIMIT
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
