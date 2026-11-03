package com.bigrocket.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle phase of one embedded engine (Aether or Xray), as seen by the supervisor.
 *
 * IDLE      - not started (or stopped on purpose).
 * STARTING  - process spawned / waiting for its local SOCKS5 listener.
 * VERIFYING - listener is up; proving a real request can travel the tunnel.
 * READY     - verified; carries (or may carry) traffic.
 * FAILED    - start/verification failed, or the engine died after being ready.
 */
enum class RuntimePhase { IDLE, STARTING, VERIFYING, READY, FAILED }

/**
 * Immutable snapshot of an engine's state. [deadlineMs] is the wall-clock time by which a
 * STARTING/VERIFYING phase must be over (0 = no deadline); it is what lets the supervisor
 * tell "slow" from "stuck" without knowing anything about the engine's own timeouts.
 */
data class RuntimeStatus(
    val phase: RuntimePhase,
    val sinceMs: Long,
    val deadlineMs: Long,
    val processAlive: Boolean,
    val error: String?,
) {
    companion object {
        val IDLE = RuntimeStatus(RuntimePhase.IDLE, 0L, 0L, false, null)
    }
}

/** Thread-safe holder of an engine's phase; every setter stamps the time of the change. */
internal class PhaseTracker {
    private var phase = RuntimePhase.IDLE
    private var since = 0L
    private var deadline = 0L
    private var error: String? = null

    @Synchronized
    fun set(newPhase: RuntimePhase, deadlineMs: Long = 0L, error: String? = null) {
        phase = newPhase
        since = System.currentTimeMillis()
        deadline = deadlineMs
        this.error = error
    }

    @Synchronized
    fun phase(): RuntimePhase = phase

    @Synchronized
    fun snapshot(processAlive: Boolean): RuntimeStatus =
        RuntimeStatus(phase, since, deadline, processAlive, error)
}

/**
 * Runs a blocking call with a hard upper bound on how long the CALLER waits.
 *
 * Plain blocking socket code cannot be cancelled: a coroutine stuck in it stays stuck
 * until the socket's own timeout fires, and cancelling the coroutine does nothing. Here
 * the call runs on a pool thread and the caller waits on a Future instead, so (a) the
 * wait ends at [timeoutMs] no matter what the blocking code does, and (b) cancelling the
 * calling coroutine ends the wait immediately. An abandoned call finishes on its own
 * within the socket timeouts it already has; its result is simply ignored.
 */
internal object BoundedCall {
    private val counter = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool(
        ThreadFactory { r ->
            Thread(r, "bounded-call-${counter.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    /** The block's result, or null when it returned null, threw, or did not finish in time. */
    suspend fun <T : Any> run(timeoutMs: Long, block: () -> T?): T? {
        val future = pool.submit(Callable { block() })
        try {
            return runInterruptible(Dispatchers.IO) {
                try {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    null
                } catch (_: ExecutionException) {
                    null
                }
            }
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }
}
