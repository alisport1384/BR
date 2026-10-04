package com.bigrocket.service

import android.util.Log
import com.bigrocket.BuildConfig
import studio.cluvex.aether.core.DiagnosticsLog
import java.io.File

/**
 * Runs the native `xray` core (shipped as libxray.so, same legacy-jniLibs
 * extraction mechanism AetherProcess relies on for libaether.so) as a child
 * process: `libxray.so run -c <config.json>`.
 *
 * Mirrors AetherProcess deliberately: spawn from nativeLibraryDir, redirect
 * stderr into stdout, drain the stream on a daemon thread so a full pipe can
 * never block the core, and keep release Logcat clean of engine output.
 */
class XrayProcess(
    private val nativeLibDir: String,
    private val workingDir: File,
) {
    private var process: Process? = null

    fun start(configPath: String) {
        val bin = File(nativeLibDir, "libxray.so")
        if (!bin.exists()) {
            throw IllegalStateException("Xray core binary missing: ${bin.absolutePath}")
        }

        val builder = ProcessBuilder(listOf(bin.absolutePath, "run", "-c", configPath))
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", workingDir.absolutePath)
            // Keep every Xray file lookup (geoip/geosite, if a config ever
            // references them) inside app-private storage.
            put("XRAY_LOCATION_ASSET", workingDir.absolutePath)
        }

        val proc = builder.start()
        process = proc

        DiagnosticsLog.i("xray", "Spawned ${bin.name} run -c $configPath")
        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        // Same policy as AetherProcess: engine output (server
                        // addresses, config echo) must not reach world-readable
                        // Logcat in release builds.
                        if (BuildConfig.DEBUG) Log.i("xray-core", it)
                        DiagnosticsLog.d("xray", it)
                    }
                }
            } catch (_: Exception) {
            } finally {
                DiagnosticsLog.w("xray", "Xray output stream closed.")
            }
        }, "xray-log").apply { isDaemon = true }.start()
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * Stops the core and waits (bounded) until the OS has reaped it, mirroring
     * AetherProcess.stop()'s reasoning: a fire-and-forget destroy() can leave
     * the old process still holding the local SOCKS port exactly when a
     * restart tries to bind it again.
     */
    fun stop() {
        val proc = process ?: return
        process = null
        runCatching { proc.destroy() }
        val deadline = System.currentTimeMillis() + 3_000
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (proc.isAlive) {
            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
        DiagnosticsLog.i("xray", "Xray core stopped (alive=${proc.isAlive})")
    }
}
