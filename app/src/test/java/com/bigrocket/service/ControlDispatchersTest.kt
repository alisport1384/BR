package com.bigrocket.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reason the dispatchers exist: a data plane that blocks every Dispatchers.IO thread
 * must not be able to stop control-plane coroutines from waking up.
 */
class ControlDispatchersTest {

    /** Parks 3x the IO pool's thread limit in blocking calls; returns a release latch. */
    private fun jamIo(release: java.util.concurrent.CountDownLatch) = runBlocking {
        repeat(192) { launch(Dispatchers.IO) { release.await() } }
    }

    @Test
    fun `control plane keeps running while Dispatchers IO is completely jammed`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val jam = Thread { jamIo(release) }.apply { isDaemon = true; start() }
        try {
            Thread.sleep(300) // let the jam form
            val result = runBlocking {
                withTimeout(3_000) {
                    withContext(ControlDispatchers.control) {
                        delay(50)
                        42
                    }
                }
            }
            assertEquals(42, result)
        } finally {
            release.countDown()
            jam.join(5_000)
        }
    }

    @Test
    fun `bounded call returns on time even when the blocking code never does`() = runBlocking {
        val started = System.currentTimeMillis()
        val result: String? = BoundedCall.run(300) {
            Thread.sleep(5_000)
            "late"
        }
        val took = System.currentTimeMillis() - started
        assertNull(result)
        assertTrue("took ${took}ms", took < 2_000)
        Unit
    }

    @Test
    fun `bounded call returns the value and null results alike`() = runBlocking {
        assertEquals("ok", BoundedCall.run(1_000) { "ok" })
        val nothing: String? = BoundedCall.run(1_000) { null }
        assertNull(nothing)
        val failed: String? = BoundedCall.run(1_000) { throw IllegalStateException("x") }
        assertNull(failed)
        Unit
    }

    @Test
    fun `bounded call is cancellable while waiting`() = runBlocking {
        val deferred = async(Dispatchers.Default) {
            BoundedCall.run<String>(10_000) { Thread.sleep(10_000); "never" }
        }
        delay(200)
        val started = System.currentTimeMillis()
        deferred.cancel()
        try {
            deferred.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertTrue(System.currentTimeMillis() - started < 2_000)
        Unit
    }
}
