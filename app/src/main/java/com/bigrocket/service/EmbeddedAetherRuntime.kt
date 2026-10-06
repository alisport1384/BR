package com.bigrocket.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.AetherProcess
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
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var job: Job? = null
    private var process: AetherProcess? = null

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
        if (job?.isActive == true) return
        _enabled.value = true
        _trafficReady.value = false
        val app = context.applicationContext
        activeUpstreamPort = upstreamPort
        job = scope.launch {
            runCatching {
                AetherController.setState(ConnectionState.Launching)
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
                val engine = AetherProcess(app.applicationInfo.nativeLibraryDir, app.filesDir)
                process = engine
                engine.start(embeddedProfile)
                AetherController.setState(ConnectionState.Connecting)
                // GOOL establishes two sequential WireGuard layers before the
                // native SOCKS5 listener is created. Use the protocol's full
                // startup budget here instead of failing while the inner tunnel
                // is still legitimately being established.
                val startupTimeoutMs = embeddedProfile.connectTimeoutMs().coerceAtLeast(10_000L)
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
                    isEngineAlive = { engine.isAlive() },
                )
                if (!open) {
                    AppLogger.log(
                        "Aether",
                        "SOCKS listener NOT ready after ${startupTimeoutMs}ms " +
                            "(engineAlive=${engine.isAlive()}) - giving up this attempt",
                    )
                    error("Aether SOCKS5 listener did not become ready")
                }
                AppLogger.log("Aether", "SOCKS listener ready on ${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}")
                AetherController.setState(ConnectionState.Verifying)
                AetherController.setIpLoading(true)
                val ip = NetProbe.fetchIpInfoViaSocks(
                    TunnelConfig.SOCKS_HOST,
                    TunnelConfig.SOCKS_PORT,
                )
                AppLogger.log(
                    "Aether",
                    if (ip != null) "tunnel verified, exit ip=${ip.ip} country=${ip.countryCode}"
                    else "tunnel up but exit-IP verification returned nothing (continuing)",
                )
                AetherController.setIpInfo(ip?.let { studio.cluvex.aether.core.IpEndpoint(it.ip, it.countryCode, true) })
                AetherController.setIpLoading(false)
                AetherController.setState(ConnectionState.Connected("${TunnelConfig.SOCKS_HOST}:${TunnelConfig.SOCKS_PORT}"))
                _trafficReady.value = true
                AppLogger.log("Aether", "traffic ready - chain entry live")
            }.onFailure {
                AppLogger.logError("Aether", "start failed", it)
                AetherController.setState(ConnectionState.Error(it.message ?: "Aether connection failed"))
                _enabled.value = false
                _trafficReady.value = false
                stopInternal()
            }
        }
    }

    fun stop(context: Context) {
        if (process != null || job?.isActive == true) {
            AppLogger.log("Aether", "stopping engine (upstreamPort=$activeUpstreamPort)")
        }
        _enabled.value = false
        _trafficReady.value = false
        job?.cancel()
        job = null
        stopInternal()
        activeUpstreamPort = -1
        AetherController.setState(ConnectionState.Idle)
        EngineMeta.reset()
    }

    /**
     * True when the live engine's own outbound no longer chains through the SOCKS
     * port the desired chain requires, so the caller must stop() before start().
     */
    fun needsRestart(upstreamPort: Int): Boolean = isRunning() && upstreamPort != activeUpstreamPort

    private fun stopInternal() {
        runCatching { process?.stop() }
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive() == true

    fun isTrafficReady(): Boolean = _trafficReady.value
}
