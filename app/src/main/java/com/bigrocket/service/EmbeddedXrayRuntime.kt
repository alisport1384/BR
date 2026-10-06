package com.bigrocket.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var job: Job? = null
    private var process: XrayProcess? = null

    // What the live core was started with, so applyUpstreamChoice() can detect a
    // stale instance (chain switched, or the user saved a new config) and restart it.
    @Volatile private var activeChainPort = -1
    @Volatile private var activeConfigFingerprint = 0

    private val _trafficReady = MutableStateFlow(false)
    val trafficReady: StateFlow<Boolean> = _trafficReady.asStateFlow()

    // @Synchronized on start/stop: unlike Aether (started only under controlMutex), Xray
    // has two legitimate start paths - applyUpstreamChoice() and the readiness collector's
    // second-stage launch - which run on different coroutines. The guard below must be
    // atomic against both, or a double core spawn would fight over the SOCKS port.
    @Synchronized
    fun start(context: Context, chainPort: Int) {
        if (job?.isActive == true || isRunning()) return
        _trafficReady.value = false
        val app = context.applicationContext
        job = scope.launch {
            runCatching {
                AppLogger.log("Xray", "starting core (socks=$SOCKS_PORT chainPort=$chainPort)")
                val raw = XrayConfigStore.read(app)
                    ?: error("هیچ پیکربندی Xray ثبت نشده است - ابتدا کانفیگ را در صفحه اصلی ذخیره کنید")
                val configJson = XrayConfigBuilder.build(raw, SOCKS_PORT, chainPort)
                val configFile = File(app.filesDir, CONFIG_FILE)
                configFile.writeText(configJson)

                val engine = XrayProcess(app.applicationInfo.nativeLibraryDir, app.filesDir)
                process = engine
                activeChainPort = chainPort
                activeConfigFingerprint = raw.hashCode()
                engine.start(configFile.absolutePath)

                val open = PortProbe.awaitOpen(
                    SOCKS_HOST,
                    SOCKS_PORT,
                    STARTUP_TIMEOUT_MS,
                    isEngineAlive = { engine.isAlive() },
                )
                if (!open) error("Xray SOCKS5 listener did not become ready")
                AppLogger.log("Xray", "core ready on $SOCKS_HOST:$SOCKS_PORT (chainPort=$chainPort)")
                _trafficReady.value = true
            }.onFailure {
                // it is already a Throwable (runCatching) - wrapping it in another Exception
                // would relabel the logged error's type as generic "Exception" and bury the
                // real one (e.g. IllegalStateException from the error()s above) as .cause.
                AppLogger.logError("Xray", "start failed", it)
                _trafficReady.value = false
                stopInternal()
            }
        }
    }

    @Synchronized
    fun stop() {
        _trafficReady.value = false
        job?.cancel()
        job = null
        stopInternal()
        AppLogger.log("Xray", "core stopped")
    }

    private fun stopInternal() {
        runCatching { process?.stop() }
        process = null
        activeChainPort = -1
        activeConfigFingerprint = 0
    }

    fun isRunning(): Boolean = process?.isAlive() == true

    fun isTrafficReady(): Boolean = _trafficReady.value

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
