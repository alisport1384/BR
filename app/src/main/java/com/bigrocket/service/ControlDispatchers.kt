package com.bigrocket.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated thread pools that keep the service's control plane alive when the data plane
 * is jammed.
 *
 * Everything used to share kotlinx's Dispatchers.IO (64 threads): up to 200 TCP and 200
 * UDP sessions of the packet router, each parked in a BLOCKING connect/read, plus every
 * BondingSocksServer relay, plus the supervisor, the engine start/verify coroutines, the
 * path-health probes, the status/notification updates. When a path times out, sessions
 * pile up in connect()/read() for seconds, the 64 threads are all taken, and every
 * coroutine that merely needs to wake from a delay() - including the one drawing the
 * retry countdown - waits for a thread that does not come until the jam clears. That is
 * a frozen service with a healthy app. It also starved Aether's own upstream relay, which
 * is one more way a GOOL handshake could miss its window.
 *
 * Separate pools make that impossible: no amount of data-plane blocking can take a
 * control-plane thread.
 */
internal object ControlDispatchers {

    private fun pool(name: String, threads: Int?): ExecutorService {
        val counter = AtomicInteger(0)
        val factory = ThreadFactory { r ->
            Thread(r, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        return if (threads == null) Executors.newCachedThreadPool(factory)
        else Executors.newFixedThreadPool(threads, factory)
    }

    /** Service control plane: supervisor, engine start/verify, control ops, status updates. */
    val control: CoroutineDispatcher = pool("br-control", 12).asCoroutineDispatcher()

    /** Path health probes (latency and loss). Short blocking connects, kept apart from [control]. */
    val probe: CoroutineDispatcher = pool("br-probe", 8).asCoroutineDispatcher()

    /**
     * BondingSocksServer relays (Aether's / Xray's own outbound): few connections, each
     * blocking, and the engines' handshakes die if they wait. Grows on demand.
     */
    val relay: CoroutineDispatcher = pool("br-relay", null).asCoroutineDispatcher()
}
