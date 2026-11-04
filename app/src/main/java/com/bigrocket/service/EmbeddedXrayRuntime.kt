package com.bigrocket.service

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PortProbe
import java.io.File

/**
 * Runs the embedded Xray core for BigRocket's two Xray chains. Deliberately a
 * structural mirror of EmbeddedAetherRuntime: Service-owned lifecycle, a
 * trafficReady StateFlow the VpnService reacts to, and PortProbe as the
 * ground-truth readiness signal on the local SOCKS listener.
 *
 * [start]'s `chainPort` selects which chain this Xray instance belongs to:
 *  - BondingSocksServer.PORT  → Direct + Xray
 *  - TunnelConfig.SOCKS_PORT  → Direct + Aether + Xray (Xray dials through Aether)
 */
object EmbeddedXrayRuntime {

    const val SOCKS_HOST = "127.0.0.1"

    /** Loopback-only; picked to avoid Aether's 1819 and BondingSocksServer's 12347. */
    const val SOCKS_PORT = 1839

    private const val CONFIG_FILE = "xray-config.json"
    private const val STARTUP_TIMEOUT_MS = 20_000L

    private const val VERIFY_BUDGET_MS = 30_000L
    private const val VERIFY_LIVENESS_CAP_MS = 14_000L
    private const val PORT_RELEASE_TIMEOUT_MS = 3_000L
    private const val START_SLACK_MS = 10_000L
    private const val MONITOR_POLL_MS = 1_000L

    private val scope = CoroutineScope(ControlDispatchers.control + SupervisorJob())

    // Same generation scheme as EmbeddedAetherRuntime (see the long note there): a
    // cancelled or superseded start must never touch shared state or stop a NEWER core.
    private val lock = Any()
    private var job: Job? = null
    private var monitorJob: Job? = null
    @Volatile private var process: XrayProcess? = null
    @Volatile private var generation = 0L
    private val phase = PhaseTracker()

    // What the live core was started with, so applyUpstreamChoice() can detect a
    // stale instance (chain switched, or the user saved a new config) and restart it.
    @Volatile private var activeChainPort = -1
    @Volatile private var activeConfigFingerprint = 0

    private val _trafficReady = MutableStateFlow(false)
    val trafficReady: StateFlow<Boolean> = _trafficReady.asStateFlow()

    // start() is reachable from two coroutines (applyUpstreamChoice() and the readiness
    // collector's second-stage launch); the guard below is atomic under [lock] for exactly
    // that reason - a double core spawn would fight over the SOCKS port.
    fun start(context: Context, chainPort: Int) {
        val gen: Long
        synchronized(lock) {
            if (job?.isActive == true || isRunning()) return
            gen = ++generation
            _trafficReady.value = false
            phase.set(RuntimePhase.STARTING, System.currentTimeMillis() + STARTUP_TIMEOUT_MS + START_SLACK_MS)
        }
        val app = context.applicationContext
        val newJob = scope.launch {
            var engine: XrayProcess? = null
            try {
                AppLogger.log("Xray", "starting core (socks=$SOCKS_PORT chainPort=$chainPort)")
                val raw = XrayConfigStore.read(app)
                    ?: error("هیچ پیکربندی Xray ثبت نشده است - ابتدا کانفیگ را در صفحه اصلی ذخیره کنید")
                val configJson = XrayConfigBuilder.build(raw, SOCKS_PORT, chainPort)
                val configFile = File(app.filesDir, CONFIG_FILE)
                configFile.writeText(configJson)

                // A dying previous core may still hold the port (see the Aether runtime).
                PortProbe.awaitClosed(SOCKS_HOST, SOCKS_PORT, PORT_RELEASE_TIMEOUT_MS)

                val proc = XrayProcess(app.applicationInfo.nativeLibraryDir, app.filesDir)
                synchronized(lock) {
                    ensureCurrent(gen)
                    process = proc
                    activeChainPort = chainPort
                    activeConfigFingerprint = raw.hashCode()
                }
                engine = proc
                proc.start(configFile.absolutePath)

                val open = PortProbe.awaitOpen(
                    SOCKS_HOST,
                    SOCKS_PORT,
                    STARTUP_TIMEOUT_MS,
                    isEngineAlive = { proc.isAlive() },
                )
                ensureCurrent(gen)
                if (!open) error("Xray SOCKS5 listener did not become ready")
                AppLogger.log("Xray", "core listening on $SOCKS_HOST:$SOCKS_PORT (chainPort=$chainPort)")

                synchronized(lock) {
                    ensureCurrent(gen)
                    phase.set(RuntimePhase.VERIFYING, System.currentTimeMillis() + VERIFY_BUDGET_MS + START_SLACK_MS)
                }
                // A listening port proves nothing: Xray accepts SOCKS clients whether or not
                // its server is reachable. Readiness is a real request that came back.
                if (!verifyTunnel(proc)) {
                    error("Xray did not carry any traffic (server unreachable or config rejected)")
                }
                synchronized(lock) {
                    ensureCurrent(gen)
                    phase.set(RuntimePhase.READY)
                    _trafficReady.value = true
                    monitorJob?.cancel()
                    monitorJob = launchMonitor(gen, proc)
                }
                AppLogger.log("Xray", "core ready - carried a real request (chainPort=$chainPort)")
            } catch (e: CancellationException) {
                runCatching { engine?.stop() }
                throw e
            } catch (e: Throwable) {
                handleStartFailure(gen, engine, e)
            }
        }
        synchronized(lock) {
            if (generation == gen) job = newJob else newJob.cancel()
        }
    }

    private suspend fun verifyTunnel(proc: XrayProcess): Boolean {
        val deadline = System.currentTimeMillis() + VERIFY_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) return false
            val alive = BoundedCall.run(VERIFY_LIVENESS_CAP_MS) {
                NetProbe.tunnelAlive(SOCKS_HOST, SOCKS_PORT)
            }
            if (alive == true) return true
            delay(2_000L)
        }
        return false
    }

    private fun ensureCurrent(gen: Long) {
        if (generation != gen) throw CancellationException("superseded by a newer start/stop")
    }

    private fun handleStartFailure(gen: Long, engine: XrayProcess?, error: Throwable) {
        val current = synchronized(lock) {
            if (generation != gen) {
                false
            } else {
                phase.set(RuntimePhase.FAILED, error = error.message ?: error.javaClass.simpleName)
                _trafficReady.value = false
                if (process === engine) {
                    process = null
                    activeChainPort = -1
                    activeConfigFingerprint = 0
                }
                monitorJob?.cancel()
                monitorJob = null
                true
            }
        }
        // it is already a Throwable - wrapping it in another Exception would relabel the
        // logged error's type and bury the real one as .cause.
        runCatching { engine?.stop() }
        if (current) AppLogger.logError("Xray", "start failed", error)
    }

    private fun launchMonitor(gen: Long, proc: XrayProcess): Job = scope.launch {
        while (isActive) {
            delay(MONITOR_POLL_MS)
            if (proc.isAlive()) continue
            val died = synchronized(lock) {
                if (generation != gen || process !== proc || phase.phase() != RuntimePhase.READY) {
                    false
                } else {
                    phase.set(RuntimePhase.FAILED, error = "core exited unexpectedly")
                    _trafficReady.value = false
                    process = null
                    true
                }
            }
            if (died) {
                AppLogger.log("Xray", "core process exited while carrying traffic")
                runCatching { proc.stop() }
            }
            return@launch
        }
    }

    fun stop() {
        val oldJob: Job?
        val oldMonitor: Job?
        val oldProcess: XrayProcess?
        synchronized(lock) {
            generation++ // invalidates any coroutine still working for the old attempt
            _trafficReady.value = false
            oldJob = job
            job = null
            oldMonitor = monitorJob
            monitorJob = null
            oldProcess = process
            process = null
            activeChainPort = -1
            activeConfigFingerprint = 0
            phase.set(RuntimePhase.IDLE)
        }
        oldJob?.cancel()
        oldMonitor?.cancel()
        runCatching { oldProcess?.stop() }
        AppLogger.log("Xray", "core stopped")
    }

    fun isRunning(): Boolean = process?.isAlive() == true

    fun isTrafficReady(): Boolean = _trafficReady.value

    /** Phase snapshot for the supervisor. */
    fun status(): RuntimeStatus = phase.snapshot(isRunning())

    /**
     * True when a live core no longer matches the desired chain target or the
     * currently-saved user config, so the caller must stop() before start().
     */
    fun needsRestart(context: Context, chainPort: Int): Boolean {
        if (!isRunning()) return false
        if (chainPort != activeChainPort) {
            AppLogger.log("Xray", "needsRestart: chainPort $activeChainPort -> $chainPort")
            return true
        }
        val fingerprint = XrayConfigStore.read(context.applicationContext)?.hashCode() ?: 0
        val changed = fingerprint != activeConfigFingerprint
        if (changed) AppLogger.log("Xray", "needsRestart: saved config changed")
        return changed
    }
}
