package com.bigrocket.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the user-visible upstream (Aether / Xray) chain is doing right now. */
enum class UpstreamLevel {
    /** Healthy, or no upstream chosen: nothing to tell the user. */
    OK,

    /** Starting or restarting; normal for a few seconds, a problem if it lasts. */
    WORKING,

    /** The chain is down and a retry is scheduled. */
    RETRYING,

    /** The chain is down because no physical network is available at all. */
    WAITING_NETWORK,
}

data class UpstreamStatus(
    val level: UpstreamLevel,
    val message: String,
    val attempt: Int = 0,
    /** Wall-clock time this non-OK stretch began; 0 when [level] is OK. */
    val sinceMs: Long = 0L,
) {
    companion object {
        val OK = UpstreamStatus(UpstreamLevel.OK, "")
    }
}

/** What the UI and the notification show; written only by [UpstreamSupervisor]. */
object UpstreamHealth {
    private val _status = MutableStateFlow(UpstreamStatus.OK)
    val status: StateFlow<UpstreamStatus> = _status.asStateFlow()

    internal fun publish(status: UpstreamStatus) {
        if (_status.value != status) _status.value = status
    }

    internal fun reset() {
        _status.value = UpstreamStatus.OK
    }
}

/**
 * The rules that decide whether the upstream chain is fine, still coming up, or broken.
 * Pure functions of time and engine snapshots, so every rule is unit-tested.
 */
internal object UpstreamPolicy {
    const val TICK_MS = 1_000L

    /** Past an engine's own phase deadline it gets this much grace before it is called stuck. */
    const val STUCK_GRACE_MS = 10_000L

    /** A required stage idle this long while its predecessor is ready gets started. */
    const val CONVERGE_AFTER_MS = 4_000L

    const val PROBE_INTERVAL_MS = 10_000L
    const val PROBE_FAILURES_TO_RESTART = 3

    /** A chain this long continuously healthy forgives its earlier failures (backoff restarts). */
    const val STABLE_MS = 60_000L

    /** Broken for this long: stop relying on the quiet persistent notification. */
    const val ALERT_AFTER_MS = 15_000L

    /** A first connect shorter than this is normal and shows nothing beyond the engine's own state. */
    const val QUIET_WORKING_MS = 12_000L

    /** Safety net for a chain that keeps "progressing" without ever becoming ready. */
    const val CHAIN_BUDGET_MS = 180_000L

    private val BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

    /** Delay before restart number [attempt] (1-based): 2s, 4s, 8s, 15s, then 30s for good. */
    fun backoffMs(attempt: Int): Long = BACKOFF_MS[(attempt - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

    enum class Stage(val label: String) { AETHER("Aether"), XRAY("Xray") }

    /** The engines a choice needs, in start order (the first one dials the bonding boundary). */
    fun stagesFor(choice: EmbeddedAetherRuntime.UpstreamChoice): List<Stage> = when (choice) {
        EmbeddedAetherRuntime.UpstreamChoice.NONE -> emptyList()
        EmbeddedAetherRuntime.UpstreamChoice.AETHER -> listOf(Stage.AETHER)
        EmbeddedAetherRuntime.UpstreamChoice.XRAY -> listOf(Stage.XRAY)
        EmbeddedAetherRuntime.UpstreamChoice.AETHER_XRAY -> listOf(Stage.AETHER, Stage.XRAY)
        EmbeddedAetherRuntime.UpstreamChoice.XRAY_AETHER -> listOf(Stage.XRAY, Stage.AETHER)
    }

    sealed interface Verdict {
        data object Healthy : Verdict
        data object Progressing : Verdict

        /** A required stage is idle although everything before it is ready: start it. */
        data object NeedsConverge : Verdict
        data class Failed(val reason: String) : Verdict
    }

    fun judge(now: Long, stages: List<Pair<Stage, RuntimeStatus>>): Verdict {
        for ((stage, st) in stages) {
            when {
                st.phase == RuntimePhase.FAILED ->
                    return Verdict.Failed("${stage.label}: ${st.error ?: "failed"}")
                st.phase == RuntimePhase.READY && !st.processAlive ->
                    return Verdict.Failed("${stage.label}: process died")
                (st.phase == RuntimePhase.STARTING || st.phase == RuntimePhase.VERIFYING) &&
                    st.deadlineMs > 0 && now > st.deadlineMs + STUCK_GRACE_MS ->
                    return Verdict.Failed("${stage.label}: stuck in ${st.phase.name.lowercase()}")
            }
        }
        if (stages.all { it.second.phase == RuntimePhase.READY }) return Verdict.Healthy
        val firstPending = stages.indexOfFirst { it.second.phase != RuntimePhase.READY }
        val predecessorsReady = stages.subList(0, firstPending).all { it.second.phase == RuntimePhase.READY }
        return if (stages[firstPending].second.phase == RuntimePhase.IDLE && predecessorsReady) {
            Verdict.NeedsConverge
        } else {
            Verdict.Progressing
        }
    }
}

/**
 * Keeps the upstream chain (Aether / Xray) alive for as long as the VPN is up.
 *
 * Before this existed, an engine that failed to come up (a path timing out at the wrong
 * moment is enough) was simply left dead: nothing retried, nothing noticed an engine that
 * died or stalled after being ready, and the user saw "checking" or a silent fallback to
 * direct traffic indefinitely. Now every tick compares what the user chose with what is
 * actually running and:
 *
 *  - restarts a failed / dead / stuck chain with backoff (2s, 4s, 8s, 15s, then 30s),
 *    never giving up while the VPN is on;
 *  - restarts immediately (1s) when a physical network comes back, instead of waiting out
 *    a long backoff for a path that is already usable again;
 *  - starts a stage that should be running but is not;
 *  - probes the tunnel end to end while "ready" and restarts it after 3 failed probes,
 *    catching a tunnel that stalled while its listener stayed open;
 *  - tells the user, through [UpstreamHealth] and (after 15s) a high-priority alert.
 *
 * All outside effects go through [Host], so the whole state machine runs in unit tests.
 */
internal class UpstreamSupervisor(
    private val host: Host,
    private val scope: CoroutineScope,
) {
    interface Host {
        fun isVpnRunning(): Boolean
        fun choice(): EmbeddedAetherRuntime.UpstreamChoice
        fun stageStatus(stage: UpstreamPolicy.Stage): RuntimeStatus

        /** True when at least one physical path (Wi-Fi / cellular) is currently usable. */
        fun hasUsablePath(): Boolean

        /** Start whatever the chosen chain still needs (idempotent). */
        suspend fun converge()

        /** Stop every engine and start the chosen chain from scratch. */
        suspend fun restartChain(reason: String)

        /** End-to-end request through the chain's entry listener; true when it came back. */
        suspend fun probeChain(): Boolean

        fun showAlert(title: String, text: String)
        fun clearAlert()
        fun log(message: String)
    }

    private var job: Job? = null

    // Failure bookkeeping. Touched only from the single supervisor coroutine, plus
    // onPathsChanged()/reset() which only write simple fields (see @Volatile).
    private var attempt = 0
    @Volatile private var pendingRestartAt = 0L
    private var failingSince = 0L
    private var readySince = 0L
    private var workingSince = 0L
    private var idleSince = 0L
    private var probeFailures = 0
    @Volatile private var lastProbeAt = 0L
    private var alertShown = false

    // Set when the chain still LOOKS ready (engine alive, listener open) but probes prove it
    // is not carrying traffic. Engine snapshots alone would call it healthy again on the
    // next tick and cancel the restart, so this keeps the failure alive until it has been
    // acted on.
    private var stalledReason: String? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    tick(System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    host.log("supervisor tick failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                delay(UpstreamPolicy.TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (alertShown) host.clearAlert()
        alertShown = false
        resetCounters()
        UpstreamHealth.reset()
    }

    /** The user changed the chosen upstream: forget the old chain's failure history. */
    fun onChoiceChanged() {
        resetCounters()
        UpstreamHealth.reset()
    }

    /**
     * A physical network appeared, vanished or was replaced. If the chain is down, retry in
     * ~1s (the path it was waiting for may be exactly this one); if it is up, re-verify it
     * on the next tick (its engine's sockets may have been pinned to the old network).
     */
    fun onPathsChanged(now: Long = System.currentTimeMillis()) {
        val pending = pendingRestartAt
        if (pending != 0L) {
            // ~1s from now: soon, but debounced so a flapping network cannot trigger a
            // restart on every blink. A restart that was merely waiting for a network
            // (already overdue) also gets this 1s, not an instant one.
            pendingRestartAt = if (pending <= now) now + 1_000L else minOf(pending, now + 1_000L)
        }
        lastProbeAt = 0L
    }

    private fun resetCounters() {
        attempt = 0
        stalledReason = null
        pendingRestartAt = 0L
        failingSince = 0L
        readySince = 0L
        workingSince = 0L
        idleSince = 0L
        probeFailures = 0
        lastProbeAt = 0L
    }

    internal suspend fun tick(now: Long) {
        if (!host.isVpnRunning()) return
        val choice = host.choice()
        val stageKeys = UpstreamPolicy.stagesFor(choice)
        if (stageKeys.isEmpty()) {
            if (failingSince != 0L || attempt != 0 || alertShown) {
                if (alertShown) host.clearAlert()
                alertShown = false
                resetCounters()
            }
            UpstreamHealth.publish(UpstreamStatus.OK)
            return
        }
        stalledReason?.let { reason ->
            onFailed(now, reason)
            return
        }
        val stages = stageKeys.map { it to host.stageStatus(it) }
        when (val verdict = UpstreamPolicy.judge(now, stages)) {
            UpstreamPolicy.Verdict.Healthy -> onHealthy(now)
            UpstreamPolicy.Verdict.Progressing -> onProgressing(now)
            UpstreamPolicy.Verdict.NeedsConverge -> onConverge(now)
            is UpstreamPolicy.Verdict.Failed -> onFailed(now, verdict.reason)
        }
    }

    private suspend fun onHealthy(now: Long) {
        workingSince = 0L
        idleSince = 0L
        pendingRestartAt = 0L
        if (readySince == 0L) readySince = now
        if (failingSince != 0L) {
            host.log("upstream recovered after ${now - failingSince}ms (attempt $attempt)")
            failingSince = 0L
            if (alertShown) host.clearAlert()
            alertShown = false
        }
        if (now - readySince >= UpstreamPolicy.STABLE_MS) attempt = 0

        // Nothing to probe through when there is no network at all; the paths' own
        // handling reports that case, and a probe would only fail for the wrong reason.
        if (host.hasUsablePath() && now - lastProbeAt >= UpstreamPolicy.PROBE_INTERVAL_MS) {
            lastProbeAt = now
            if (host.probeChain()) {
                probeFailures = 0
            } else {
                probeFailures++
                host.log("tunnel liveness probe failed ($probeFailures/${UpstreamPolicy.PROBE_FAILURES_TO_RESTART})")
                if (probeFailures >= UpstreamPolicy.PROBE_FAILURES_TO_RESTART) {
                    probeFailures = 0
                    stalledReason = "tunnel stopped carrying traffic"
                    onFailed(now, stalledReason!!)
                    return
                }
            }
        }
        UpstreamHealth.publish(UpstreamStatus.OK)
    }

    private suspend fun onProgressing(now: Long) {
        readySince = 0L
        probeFailures = 0
        if (workingSince == 0L) workingSince = now
        if (now - workingSince > UpstreamPolicy.CHAIN_BUDGET_MS) {
            // An engine can keep cycling STARTING/VERIFYING without ever tripping its own
            // deadline; a chain that has not become ready in this long is treated as failed.
            workingSince = 0L
            // stalledReason keeps the failure alive: the engines' own snapshots still say
            // "progressing", which would otherwise cancel the restart on the next tick.
            stalledReason = "chain did not become ready in time"
            onFailed(now, stalledReason!!)
            return
        }
        publishWorking(now)
        maybeAlert(now)
    }

    private suspend fun onConverge(now: Long) {
        readySince = 0L
        if (idleSince == 0L) idleSince = now
        if (now - idleSince >= UpstreamPolicy.CONVERGE_AFTER_MS) {
            idleSince = 0L
            host.log("converging: a required stage is idle")
            host.converge()
        }
        if (workingSince == 0L) workingSince = now
        publishWorking(now)
    }

    private suspend fun onFailed(now: Long, reason: String) {
        workingSince = 0L
        readySince = 0L
        probeFailures = 0
        idleSince = 0L
        if (failingSince == 0L) failingSince = now
        if (pendingRestartAt == 0L) {
            attempt++
            pendingRestartAt = now + UpstreamPolicy.backoffMs(attempt)
            host.log("upstream failed (attempt $attempt): $reason; retry in ${UpstreamPolicy.backoffMs(attempt)}ms")
        }
        if (!host.hasUsablePath()) {
            // Neither restart into the void nor spend backoff on it: the restart fires
            // ~1s after a network returns (onPathsChanged) or, failing that, at its time.
            UpstreamHealth.publish(
                UpstreamStatus(
                    UpstreamLevel.WAITING_NETWORK,
                    "هیچ مسیر شبکه‌ای در دسترس نیست؛ با بازگشت شبکه اتصال خودکار برقرار می‌شود",
                    attempt,
                    failingSince,
                ),
            )
            maybeAlert(now)
            return
        }
        if (now >= pendingRestartAt) {
            pendingRestartAt = 0L
            UpstreamHealth.publish(
                UpstreamStatus(UpstreamLevel.WORKING, "تلاش $attempt: در حال اتصال مجدد…", attempt, failingSince),
            )
            maybeAlert(now)
            stalledReason = null
            host.restartChain(reason)
            return
        }
        val seconds = ((pendingRestartAt - now + 999) / 1000).coerceAtLeast(1)
        UpstreamHealth.publish(
            UpstreamStatus(
                UpstreamLevel.RETRYING,
                "اتصال تونل برقرار نیست؛ تلاش $attempt — $seconds ثانیه دیگر دوباره امتحان می‌شود. " +
                    "تا برقراری مجدد، ترافیک بدون تونل (مستقیم) عبور می‌کند",
                attempt,
                failingSince,
            ),
        )
        maybeAlert(now)
    }

    private fun publishWorking(now: Long) {
        val since = if (failingSince != 0L) failingSince else workingSince
        val elapsed = if (since == 0L) 0L else now - since
        val message = when {
            attempt > 0 -> "تلاش $attempt: در حال اتصال مجدد…"
            elapsed >= UpstreamPolicy.QUIET_WORKING_MS ->
                "اتصال بیش از حد معمول طول کشیده؛ در حال تلاش… (${elapsed / 1000} ثانیه)"
            else -> ""
        }
        UpstreamHealth.publish(UpstreamStatus(UpstreamLevel.WORKING, message, attempt, since))
    }

    private fun maybeAlert(now: Long) {
        if (alertShown || failingSince == 0L) return
        if (now - failingSince < UpstreamPolicy.ALERT_AFTER_MS) return
        alertShown = true
        host.showAlert(
            "اتصال تونل قطع است",
            "BigRocket در حال تلاش مجدد است. تا برقراری اتصال، ترافیک بدون تونل (مستقیم) عبور می‌کند.",
        )
    }
}

