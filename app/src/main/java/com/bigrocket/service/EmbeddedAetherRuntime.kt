package com.bigrocket.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.AetherProcess
import studio.cluvex.aether.core.IpInfo
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Protocol

/** Runs Aether in embedded proxy mode so BigRocket remains the only Android VPN. */
object EmbeddedAetherRuntime {
    // SupervisorJob: one failed child must never cancel the scope for every later start().
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Guards job/process/generation and every state mutation made on behalf of a start().
    private val lock = Any()
    private var job: Job? = null
    private var monitorJob: Job? = null
    @Volatile private var process: AetherProcess? = null

    // Identity of the CURRENT start attempt. stop() and every new start() bump it, which
    // invalidates any coroutine still running for an older attempt: such a coroutine may
    // only clean up its OWN engine and must never touch shared state again. Without this a
    // cancelled attempt (blocked in a socket call that cancellation cannot interrupt)
    // woke up later and either killed the NEW engine from its failure handler or flipped
    // trafficReady=true for an engine that no longer existed - routing the TUN into a dead
    // listener while the UI said "connected" (or stayed on "checking" forever).
    @Volatile private var generation = 0L
    private val phase = PhaseTracker()

    /** Hard ceiling for the whole verification phase (several attempts inside it). */
    private const val VERIFY_BUDGET_MS = 30_000L
    /** One lookup attempt: the raced geo lookup is self-bounded to ~10s, this is the cap. */
    private const val VERIFY_LOOKUP_CAP_MS = 12_000L
    /** One end-to-end liveness attempt (two targets, each with its own socket timeouts). */
    private const val VERIFY_LIVENESS_CAP_MS = 14_000L
    /** How long a dying previous engine gets to release the local SOCKS port. */
    private const val PORT_RELEASE_TIMEOUT_MS = 3_000L
    /** Extra time past the startup timeout before a STARTING phase is called stuck. */
    private const val START_SLACK_MS = 10_000L

    private const val UPSTREAM_PREFS = "bigrocket_upstream"
    private const val KEY_UPSTREAM_CHOICE = "upstream_choice"

    /**
     * Owned here, not in MainActivity: the Service must be able to read (and start/stop
     * Aether from) this choice on its own, independent of whether any Activity currently
     * exists - that is the whole point of this step (see learnings-and-working-style: engine
     * lifecycle must be Service-owned, not Activity-owned, or a recreated/reopened Activity
     * can race a healthy running engine - which is exactly what previously caused the
     * reconnect flap on reopening the app).
     *
     * [XRAY] = Direct + Xray (TUN traffic → Xray → bonding boundary → physical paths).
     * [AETHER_XRAY] = Direct + Aether + Xray (TUN traffic → Xray → Aether → bonding
     * boundary → physical paths). [XRAY_AETHER] = Direct + Xray + Aether (TUN traffic
     * → Aether → Xray → bonding boundary → physical paths: Aether is the chain entry
     * point and its own outbound dials through Xray's local SOCKS instead of the
     * bonding boundary directly). All are orchestrated by BigRocketVpnService's
     * applyUpstreamChoice(), exactly like NONE/AETHER.
     */
    enum class UpstreamChoice { NONE, AETHER, XRAY, AETHER_XRAY, XRAY_AETHER }

    /** Synchronous on purpose - see loadUpstreamChoice()'s callers for why. */
    fun readUpstreamChoice(context: Context): UpstreamChoice {
        val name = context.getSharedPreferences(UPSTREAM_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UPSTREAM_CHOICE, null)
        return runCatching { UpstreamChoice.valueOf(name ?: "") }.getOrDefault(UpstreamChoice.NONE)
    }

    fun saveUpstreamChoice(context: Context, choice: UpstreamChoice) {
        context.getSharedPreferences(UPSTREAM_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UPSTREAM_CHOICE, choice.name)
            .apply()
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _trafficReady = MutableStateFlow(false)
    val trafficReady: StateFlow<Boolean> = _trafficReady.asStateFlow()

    // Which local SOCKS port the LIVE engine chains its own outbound through
    // (BondingSocksServer.PORT, or Xray's listener in the Direct+Xray+Aether chain).
    // Lets applyUpstreamChoice() detect a stale instance after a chain switch.
    @Volatile private var activeUpstreamPort = -1

    fun start(context: Context, profile: ConnectionProfile, upstreamPort: Int = BondingSocksServer.PORT) {
        val gen: Long
        synchronized(lock) {
            if (job?.isActive == true) return
            gen = ++generation
            _enabled.value = true
            _trafficReady.value = false
            activeUpstreamPort = upstreamPort
            // Generous placeholder; tightened below once this protocol's own startup
            // budget is known. A deadline always exists so the supervisor can never wait
            // on a phase that has no end.
            phase.set(RuntimePhase.STARTING, System.currentTimeMillis() + 120_000L)
        }
        val app = context.applicationContext
        val newJob = scope.launch {
            var engine: AetherProcess? = null
            try {
                publish(gen) { AetherController.setState(ConnectionState.Launching) }
                EngineMeta.reset()
                // Aether must consume BigRocket's already-bonded transport. Its own
                // physical dials are therefore chained through a local SOCKS5 boundary
                // instead of going directly to Wi-Fi/Cellular: the bonding boundary
                // itself by default, or Xray's listener when Aether rides on top of
                // Xray in the Direct+Xray+Aether chain.
                //
                // Direct+Xray+Aether ONLY: every default Aether transport (WireGuard,
                // gool, MASQUE over QUIC) is UDP, and here that UDP has to ride
                // UDP-over-VLESS through the user's Xray server. Real-world VLESS
                // provider configs do not carry UDP (they ship `"network":"udp" ->
                // block` rules), so every UDP transport starves silently and "Aether
                // cannot connect after Xray". Probing UDP transit at runtime is NOT
                // reliable either: these same configs hijack port 53 (`"port":53 ->
                // dns-out` with a local DNS answer), so a DNS-shaped probe gets a
                // locally fabricated reply and falsely reports "UDP works" while
                // every WireGuard/QUIC datagram still dies - which is exactly how the
                // first field fix failed. The only deterministic carrier over this
                // upstream is TCP, so this chain ALWAYS runs MASQUE over HTTP/2 -
                // the engine's own documented remedy for TCP-only upstreams (it
                // applies the same h2 fallback for its tor/psiphon reverse chains and
                // for http:// upstreams). TCP transit is guaranteed by construction:
                // Xray only reports trafficReady after carrying real TCP traffic.
                // Chains that dial the bonding boundary directly are untouched.
                val xrayChained = upstreamPort == EmbeddedXrayRuntime.SOCKS_PORT
                val embeddedProfile = if (xrayChained) {
                    DiagnosticsLog.i(
                        "bigrocket",
                        "Aether rides on Xray: forcing MASQUE over HTTP/2 (TCP carrier) - " +
                            "VLESS upstreams cannot be trusted to carry UDP",
                    )
                    profile.copy(
                        proxyMode = true,
                        upstreamProxy = "socks5://127.0.0.1:$upstreamPort",
                        protocol = Protocol.MASQUE,
                        masqueHttp2 = true,
                        // A pinned single endpoint keeps its port on the h2 carrier
                        // (h2_peer() only rewrites it when AETHER_MASQUE_H2_PEER is
                        // set), so a WireGuard-style pin like ip:2408 would dial a
                        // dead TCP port forever. Let the engine scan for an h2
                        // gateway itself; a pinned RANGE stays honoured because the
                        // h2 prober works within it.
                        endpointMode = if (profile.endpointMode == studio.cluvex.aether.model.EndpointMode.MANUAL_PEER) {
                            studio.cluvex.aether.model.EndpointMode.AUTO
                        } else {
                            profile.endpointMode
                        },
                    )
                } else {
                    profile.copy(
                        proxyMode = true,
                        upstreamProxy = "socks5://127.0.0.1:$upstreamPort"
                    )
                }
                // Persist only what every working build persisted (proxy mode + the
                // upstream boundary). The forced MASQUE/h2 transport above is a
                // property of THIS chain's launch, not a user setting: writing it to
                // the store would silently flip the user's chosen protocol for the
                // plain Direct+Aether mode afterwards.
                ProfileStore(app).save(
                    profile.copy(
                        proxyMode = true,
                        upstreamProxy = "socks5://127.0.0.1:$upstreamPort"
                    )
                )
                AppLogger.log(
                    "Aether",
                    "starting engine: upstream=socks5://127.0.0.1:$upstreamPort " +
                        "protocol=${embeddedProfile.protocol} h2=${embeddedProfile.masqueHttp2} " +
                        "scan=${embeddedProfile.scanMode} endpoint=${embeddedProfile.endpointMode}",
                )
                // A dying previous engine may still hold the local SOCKS port; probing it
                // would declare "ready" for a tunnel that does not exist (the original
                // protocol-switch bug). Bounded wait: never block the start on it.
                if (!PortProbe.awaitClosed(
                        TunnelConfig.SOCKS_HOST,
                        TunnelConfig.SOCKS_PORT,
                        PORT_RELEASE_TIMEOUT_MS,
                    )
                ) {
                    AppLogger.log(
                        "Aether",
                        "local SOCKS port still busy after ${PORT_RELEASE_TIMEOUT_MS}ms - starting anyway",
                    )
                }
                val proc = AetherProcess(app.applicationInfo.nativeLibraryDir, app.filesDir)
                synchronized(lock) {
                    ensureCurrent(gen)
                    process = proc
                }
                engine = proc
                proc.start(embeddedProfile)
                publish(gen) { AetherController.setState(ConnectionState.Connecting) }
                // GOOL establishes two sequential WireGuard layers before the
                // native SOCKS5 listener is created. Use the protocol's full
                // startup budget here instead of failing while the inner tunnel
                // is still legitimately being established.
                val startupTimeoutMs = embeddedProfile.connectTimeoutMs().coerceAtLeast(10_000L)
                synchronized(lock) {
                    ensureCurrent(gen)
                    phase.set(
                        RuntimePhase.STARTING,
                        System.currentTimeMillis() + startupTimeoutMs + START_SLACK_MS,
                    )
                }
                // NOTE: embeddedProfile.upstreamProxy ("socks5://127.0.0.1:${BondingSocksServer.PORT}")
                // is where Aether sends ITS OWN outbound traffic (the --upstream target).
                // It is unrelated to the port Aether exposes ITS OWN SOCKS5 relay on for us
                // to consume — that is always the fixed TunnelConfig.SOCKS_PORT (1819).
                // Probing BondingSocksServer's port here always returns "open" immediately
                // (it's up from VPN start), which made every protocol falsely report
                // Connected before Aether's tunnel was actually ready.
                val open = PortProbe.awaitOpen(
                    TunnelConfig.SOCKS_HOST,
                    TunnelConfig.SOCKS_PORT,
                    startupTimeoutMs,
                    isEngineAlive = { proc.isAlive() },
                )
                ensureCurrent(gen)
                if (!open) {
                    AppLogger.log(
                        "Aether",
                        "SOCKS listener NOT ready after ${startupTimeoutMs}ms " +
                            "(engineAlive=${proc.isAlive()}) - giving up this attempt",
                    )
                    error("Aether SOCKS5 listener did not become ready")
                }
                AppLogger.log("Aether", "SOCKS listener ready on ${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}")
                synchronized(lock) {
                    ensureCurrent(gen)
                    phase.set(RuntimePhase.VERIFYING, System.currentTimeMillis() + VERIFY_BUDGET_MS + START_SLACK_MS)
                    AetherController.setState(ConnectionState.Verifying)
                    AetherController.setIpLoading(true)
                }
                val verification = verifyTunnel(proc)
                ensureCurrent(gen)
                if (!verification.ok) {
                    error("Aether tunnel did not carry any traffic (verification failed)")
                }
                val ip = verification.ip
                AppLogger.log(
                    "Aether",
                    if (ip != null) "tunnel verified, exit ip=${ip.ip} country=${ip.countryCode}"
                    else "tunnel verified end-to-end (exit-IP lookup unavailable)",
                )
                synchronized(lock) {
                    ensureCurrent(gen)
                    AetherController.setIpInfo(ip?.let { studio.cluvex.aether.core.IpEndpoint(it.ip, it.countryCode, true) })
                    AetherController.setIpLoading(false)
                    AetherController.setState(ConnectionState.Connected("${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}"))
                    phase.set(RuntimePhase.READY)
                    _trafficReady.value = true
                    monitorJob?.cancel()
                    monitorJob = launchMonitor(gen, proc)
                }
                AppLogger.log("Aether", "traffic ready - chain entry live")
            } catch (e: CancellationException) {
                // Superseded or stopped. Clean up only what THIS attempt created.
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

    private class Verification(val ok: Boolean, val ip: IpInfo?)

    /**
     * Proves a real request can travel the tunnel, within a HARD time budget.
     *
     * Previously this was one serial pass over every geolocation provider, each of which
     * could burn several 9s timeouts on a stalled tunnel, inside a blocking call that
     * cancellation could not interrupt: the state sat on "verifying" for minutes, and a
     * failed lookup was waved through as "continuing", declaring a dead tunnel connected.
     * Now: raced lookup first (exit IP for the UI), then an end-to-end liveness request
     * (so geo providers being blocked cannot fail a working tunnel), retried until the
     * budget is spent. Every blocking call is wrapped in [BoundedCall].
     */
    private suspend fun verifyTunnel(engine: AetherProcess): Verification {
        val deadline = System.currentTimeMillis() + VERIFY_BUDGET_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (!engine.isAlive()) {
                AppLogger.log("Aether", "engine exited during verification")
                return Verification(false, null)
            }
            val ip = BoundedCall.run(VERIFY_LOOKUP_CAP_MS) {
                NetProbe.fetchIpInfoViaSocksRaced(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT)
            }
            if (ip != null) return Verification(true, ip)
            val alive = BoundedCall.run(VERIFY_LIVENESS_CAP_MS) {
                NetProbe.tunnelAlive(TunnelConfig.SOCKS_HOST, TunnelConfig.SOCKS_PORT)
            }
            if (alive == true) return Verification(true, null)
            AppLogger.log("Aether", "verification attempt $attempt failed - retrying")
            delay(2_000L)
        }
        return Verification(false, null)
    }

    /** Throws (as a cancellation, so it is never treated as a failure) when [gen] is no longer current. */
    private fun ensureCurrent(gen: Long) {
        if (generation != gen) throw CancellationException("superseded by a newer start/stop")
    }

    /** Runs [block] only while [gen] is still the current attempt. */
    private fun publish(gen: Long, block: () -> Unit) {
        synchronized(lock) { if (generation == gen) block() }
    }

    private fun handleStartFailure(gen: Long, engine: AetherProcess?, error: Throwable) {
        val current = synchronized(lock) {
            if (generation != gen) {
                false
            } else {
                phase.set(RuntimePhase.FAILED, error = error.message ?: error.javaClass.simpleName)
                _trafficReady.value = false
                if (process === engine) process = null
                monitorJob?.cancel()
                monitorJob = null
                AetherController.setIpLoading(false)
                AetherController.setState(ConnectionState.Error(error.message ?: "Aether connection failed"))
                true
            }
        }
        // Stops only THIS attempt's engine, never whatever `process` happens to be now.
        runCatching { engine?.stop() }
        if (current) AppLogger.logError("Aether", "start failed", error)
    }

    /**
     * Notices the engine dying AFTER it was declared ready (crash, OOM kill, its own fatal
     * error). Nothing watched the process in the embedded runtime, so trafficReady stayed
     * true and the TUN kept feeding a dead listener. Parks on the OS (no polling).
     */
    private fun launchMonitor(gen: Long, proc: AetherProcess): Job = scope.launch {
        while (isActive) {
            val exited = proc.awaitExit(MONITOR_WAIT_MS)
            if (!isActive) return@launch
            if (exited || !proc.isAlive()) {
                onEngineDied(gen, proc)
                return@launch
            }
        }
    }

    private fun onEngineDied(gen: Long, proc: AetherProcess) {
        val died = synchronized(lock) {
            if (generation != gen || process !== proc || phase.phase() != RuntimePhase.READY) {
                false
            } else {
                phase.set(RuntimePhase.FAILED, error = "engine exited unexpectedly")
                _trafficReady.value = false
                process = null
                AetherController.setState(ConnectionState.Error("موتور Aether به‌طور غیرمنتظره متوقف شد"))
                true
            }
        }
        if (died) {
            AppLogger.log("Aether", "engine process exited while carrying traffic")
            runCatching { proc.stop() }
        }
    }

    /**
     * Stops the engine. [keepEnabled] is for supervised restarts: the user's choice has not
     * changed, so the Aether switch must not flip off and on again around the restart.
     */
    fun stop(context: Context, keepEnabled: Boolean = false) {
        val oldJob: Job?
        val oldMonitor: Job?
        val oldProcess: AetherProcess?
        synchronized(lock) {
            if (process != null || job?.isActive == true) {
                AppLogger.log("Aether", "stopping engine (upstreamPort=$activeUpstreamPort)")
            }
            generation++ // invalidates any coroutine still working for the old attempt
            if (!keepEnabled) _enabled.value = false
            _trafficReady.value = false
            oldJob = job
            job = null
            oldMonitor = monitorJob
            monitorJob = null
            oldProcess = process
            process = null
            activeUpstreamPort = -1
            phase.set(RuntimePhase.IDLE)
            AetherController.setIpLoading(false)
            AetherController.setState(ConnectionState.Idle)
        }
        oldJob?.cancel()
        oldMonitor?.cancel()
        runCatching { oldProcess?.stop() }
        EngineMeta.reset()
    }

    /**
     * True when the live engine's own outbound no longer chains through the SOCKS
     * port the desired chain requires, so the caller must stop() before start().
     */
    fun needsRestart(upstreamPort: Int): Boolean = isRunning() && upstreamPort != activeUpstreamPort

    fun isRunning(): Boolean = process?.isAlive() == true

    fun isTrafficReady(): Boolean = _trafficReady.value

    /** Phase snapshot for the supervisor. */
    fun status(): RuntimeStatus = phase.snapshot(isRunning())

    private const val MONITOR_WAIT_MS = 30_000L
}
