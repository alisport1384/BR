package studio.cluvex.aether.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.bigrocket.BuildConfig
import studio.cluvex.aether.model.ConnectionProfile
import java.io.File

/**
 * Runs the native `aether` engine (shipped as libaether.so) as a child
 * process. On Android an executable packaged in jniLibs is extracted to
 * nativeLibraryDir with the exec bit set, which is exactly what we run.
 */
class AetherProcess(
    private val context: Context,
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir,
    private val workingDir: File = context.filesDir,
) {
    private var process: Process? = null

    fun start(profile: ConnectionProfile) {
        val bin = resolveEngineBinary()

        val command = mutableListOf(bin.absolutePath).apply { addAll(profile.toArgs()) }
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            putAll(profile.toEnv())
            // Embedded BigRocket mode always consumes the local SOCKS5 endpoint.
            // Keep the process environment explicit as well as argv-driven so an inherited
            // AETHER_SOCKS value can never redirect the listener away from the hev bridge.
            put("AETHER_SOCKS", "127.0.0.1:1819")
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", workingDir.absolutePath)
        }

        val proc = builder.start()
        process = proc

        DiagnosticsLog.i("engine", "Spawned ${bin.name} ${profile.toArgs().joinToString(" ")}")
        // Drain stdout/stderr so a full pipe never blocks the engine, mirroring
        // every line into both logcat and the in-app diagnostics panel.
        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        // SECURITY FIX: the engine's stdout (endpoints, exit
                        // IPs, config echo) must not be mirrored to Logcat in
                        // release builds — Logcat is world-readable via adb and
                        // ends up in bug reports. The in-app diagnostics panel
                        // (app-private file) still receives every line below.
                        if (BuildConfig.DEBUG) Log.i("aether-engine", it)
                        DiagnosticsLog.d("engine", it)
                        // Desktop-parity info row: pick out the endpoint the
                        // engine selected (no-op for every other line).
                        EngineMeta.ingest(it)
                    }
                }
            } catch (_: Exception) {
            } finally {
                DiagnosticsLog.w("engine", "Engine output stream closed.")
            }
        }, "aether-log").apply { isDaemon = true }.start()
    }


    /**
     * Resolve the Aether executable from the package-managed native library.
     * The Android build explicitly uses legacy JNI packaging so the executable
     * is materialized in ApplicationInfo.nativeLibraryDir and can be launched
     * as a child process.
     */
    private fun resolveEngineBinary(): File {
        // Aether is a real Android executable, not a JNI library. It must be
        // executed from the package-managed native-library directory.
        // Executing an extracted copy from codeCacheDir is rejected on a number
        // of Android 10+ devices/ROMs (typically with EACCES / cannot execute),
        // which produced the observed `code_cache/aether/...` failure.
        val nativeBin = File(nativeLibDir, "libaether.so")
        if (nativeBin.isFile) {
            nativeBin.setExecutable(true, false)
            if (nativeBin.canExecute()) return nativeBin
            throw IllegalStateException(
                "Aether engine is present but not executable: ${nativeBin.absolutePath}"
            )
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "armeabi-v7a"
        } ?: throw IllegalStateException(
            "Unsupported ABI for Aether engine: ${Build.SUPPORTED_ABIS.joinToString()}"
        )

        throw IllegalStateException(
            "Aether engine was not installed in nativeLibraryDir: ${nativeBin.absolutePath} (ABI=$abi)"
        )
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * Blocks until the engine exits or [timeoutMs] elapses; returns true if it
     * exited.
     *
     * 1.2.2 CPU FIX: the VpnService supervisor used to poll `isAlive()` every
     * two seconds for the whole life of the tunnel. That is thousands of
     * pointless wake-ups per session on a connection that is perfectly
     * healthy, and it keeps the CPU out of deep idle. `Process.waitFor` parks
     * the supervisor coroutine on the OS instead: it costs nothing while the
     * engine is running and returns the moment the engine actually dies, so
     * crash detection is FASTER than the old poll while using less power.
     *
     * The bounded overload is used so the supervisor still re-checks its own
     * cancellation state periodically.
     */
    suspend fun awaitExit(timeoutMs: Long): Boolean =
        runCatching {
            // DISCONNECT-LATENCY ROOT CAUSE (fixed): `Process.waitFor` is a
            // BLOCKING java call. Coroutine cancellation cannot interrupt a
            // blocking call, so when the user tapped disconnect the service
            // sat inside this wait until the whole 60 s window expired — which
            // is exactly the 30–50 s "Disconnecting…" freeze. `runInterruptible`
            // maps cancellation onto a real thread interrupt, so `waitFor`
            // throws immediately and the teardown continues within
            // milliseconds — while still costing zero polling when idle.
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                process?.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: true
            }
        }.getOrDefault(false)

    /**
     * Stops the engine and does not return until the OS has really reaped it.
     *
     * 1.2.2 PROTOCOL-SWITCH FIX: this used to be a fire-and-forget
     * `destroy()`. `destroy()` only *asks* the process to exit, so the old
     * engine was often still alive — and still holding the local SOCKS5
     * listener on 127.0.0.1:1819 — while the next connect was already
     * spawning a new engine. The new engine then either failed to bind or the
     * app's port probe saw the DYING engine's socket and declared "port is
     * up" far too early, which is exactly why switching protocols felt like it
     * hung for tens of seconds and then had to retry. We now wait for the
     * process to actually exit and escalate to SIGKILL if it does not.
     */
    fun stop() {
        val proc = process ?: return
        process = null
        runCatching {
            proc.destroy()
            // Give the engine a very short, fixed grace period, then SIGKILL.
            // Deliberately short: the user is waiting for the button to turn
            // grey, and the next connect independently waits for the local
            // proxy port to be released, so nothing depends on a long wait
            // here.
            if (!proc.waitFor(GRACEFUL_EXIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
        }
    }

    private companion object {
        /** How long a polite SIGTERM gets before we escalate to SIGKILL. */
        const val GRACEFUL_EXIT_MS = 250L
    }
}
