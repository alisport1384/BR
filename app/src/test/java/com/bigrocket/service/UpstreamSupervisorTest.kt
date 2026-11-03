package com.bigrocket.service

import com.bigrocket.service.EmbeddedAetherRuntime.UpstreamChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamSupervisorTest {

    private val t0 = 1_000_000L

    private fun status(
        phase: RuntimePhase,
        alive: Boolean = phase == RuntimePhase.READY,
        deadline: Long = 0L,
        error: String? = null,
    ) = RuntimeStatus(phase, 0L, deadline, alive, error)

    private class FakeHost(var choice: UpstreamChoice = UpstreamChoice.AETHER) : UpstreamSupervisor.Host {
        var running = true
        var path = true
        var probeResult = true
        val stages = HashMap<UpstreamPolicy.Stage, RuntimeStatus>()
        var restarts = 0
        var converges = 0
        var probes = 0
        var alerts = 0
        var clears = 0
        val logs = ArrayList<String>()

        override fun isVpnRunning() = running
        override fun choice() = choice
        override fun stageStatus(stage: UpstreamPolicy.Stage) = stages[stage] ?: RuntimeStatus.IDLE
        override fun hasUsablePath() = path
        override suspend fun converge() { converges++ }
        override suspend fun restartChain(reason: String) {
            restarts++
            // A restart resets the engines to "starting", like the real runtimes do.
            for (k in stages.keys.toList()) {
                stages[k] = RuntimeStatus(RuntimePhase.STARTING, 0L, 0L, true, null)
            }
        }
        override suspend fun probeChain(): Boolean { probes++; return probeResult }
        override fun showAlert(title: String, text: String) { alerts++ }
        override fun clearAlert() { clears++ }
        override fun log(message: String) { logs += message }
    }

    private fun supervisor(host: FakeHost) =
        UpstreamSupervisor(host, CoroutineScope(Dispatchers.Unconfined))

    private fun ready() = RuntimeStatus(RuntimePhase.READY, 0L, 0L, true, null)
    private fun failed() = RuntimeStatus(RuntimePhase.FAILED, 0L, 0L, false, "boom")

    // ------------------------------------------------------------ policy

    @Test
    fun `backoff grows then plateaus`() {
        assertEquals(2_000L, UpstreamPolicy.backoffMs(1))
        assertEquals(4_000L, UpstreamPolicy.backoffMs(2))
        assertEquals(8_000L, UpstreamPolicy.backoffMs(3))
        assertEquals(15_000L, UpstreamPolicy.backoffMs(4))
        assertEquals(30_000L, UpstreamPolicy.backoffMs(5))
        assertEquals(30_000L, UpstreamPolicy.backoffMs(500))
        assertEquals(2_000L, UpstreamPolicy.backoffMs(0))
    }

    @Test
    fun `stages follow start order`() {
        assertEquals(emptyList<UpstreamPolicy.Stage>(), UpstreamPolicy.stagesFor(UpstreamChoice.NONE))
        assertEquals(
            listOf(UpstreamPolicy.Stage.AETHER, UpstreamPolicy.Stage.XRAY),
            UpstreamPolicy.stagesFor(UpstreamChoice.AETHER_XRAY),
        )
        assertEquals(
            listOf(UpstreamPolicy.Stage.XRAY, UpstreamPolicy.Stage.AETHER),
            UpstreamPolicy.stagesFor(UpstreamChoice.XRAY_AETHER),
        )
    }

    @Test
    fun `judge classifies engine states`() {
        val a = UpstreamPolicy.Stage.AETHER
        val x = UpstreamPolicy.Stage.XRAY
        assertEquals(UpstreamPolicy.Verdict.Healthy, UpstreamPolicy.judge(t0, listOf(a to ready())))
        assertTrue(UpstreamPolicy.judge(t0, listOf(a to failed())) is UpstreamPolicy.Verdict.Failed)
        // Ready on paper but the process is gone.
        assertTrue(
            UpstreamPolicy.judge(t0, listOf(a to status(RuntimePhase.READY, alive = false)))
                is UpstreamPolicy.Verdict.Failed,
        )
        // Still inside its deadline (plus grace): slow, not stuck.
        val starting = status(RuntimePhase.STARTING, alive = true, deadline = t0 + 5_000)
        assertEquals(UpstreamPolicy.Verdict.Progressing, UpstreamPolicy.judge(t0 + 14_000, listOf(a to starting)))
        // Past deadline + grace: stuck.
        assertTrue(UpstreamPolicy.judge(t0 + 15_001, listOf(a to starting)) is UpstreamPolicy.Verdict.Failed)
        val verifying = status(RuntimePhase.VERIFYING, alive = true, deadline = t0)
        assertTrue(UpstreamPolicy.judge(t0 + 20_000, listOf(a to verifying)) is UpstreamPolicy.Verdict.Failed)
        // Second stage idle behind a ready first stage must be started...
        assertEquals(UpstreamPolicy.Verdict.NeedsConverge, UpstreamPolicy.judge(t0, listOf(a to ready(), x to RuntimeStatus.IDLE)))
        // ...but not while the first stage is still coming up.
        assertEquals(
            UpstreamPolicy.Verdict.Progressing,
            UpstreamPolicy.judge(t0, listOf(a to starting, x to RuntimeStatus.IDLE)),
        )
        // A failure anywhere in the chain fails the chain.
        assertTrue(UpstreamPolicy.judge(t0, listOf(a to ready(), x to failed())) is UpstreamPolicy.Verdict.Failed)
    }

    // ------------------------------------------------------------ supervisor

    @Test
    fun `a failed chain is restarted after the first backoff`() = runBlocking {
        val host = FakeHost()
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val s = supervisor(host)
        s.tick(t0)
        assertEquals(0, host.restarts)
        assertEquals(UpstreamLevel.RETRYING, UpstreamHealth.status.value.level)
        assertTrue(UpstreamHealth.status.value.message.contains("2"))
        s.tick(t0 + 1_000)
        assertEquals(0, host.restarts)
        s.tick(t0 + 2_000)
        assertEquals(1, host.restarts)
        Unit
    }

    @Test
    fun `repeated failures back off further and never give up`() = runBlocking {
        val host = FakeHost()
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val s = supervisor(host)
        var now = t0
        val expectedGaps = listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L)
        for (gap in expectedGaps) {
            host.stages[UpstreamPolicy.Stage.AETHER] = failed()
            val before = host.restarts
            s.tick(now)
            s.tick(now + gap - 1)
            assertEquals(before, host.restarts)
            s.tick(now + gap)
            assertEquals(before + 1, host.restarts)
            now += gap + 1
        }
        Unit
    }

    @Test
    fun `no network means no restart and no wasted attempts`() = runBlocking {
        val host = FakeHost()
        host.path = false
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val s = supervisor(host)
        for (i in 0..60) s.tick(t0 + i * 1_000L)
        assertEquals(0, host.restarts)
        assertEquals(UpstreamLevel.WAITING_NETWORK, UpstreamHealth.status.value.level)
        // The network returns: retry within about a second, not after a long backoff.
        host.path = true
        s.onPathsChanged(t0 + 61_000)
        s.tick(t0 + 61_500)
        assertEquals(0, host.restarts)
        s.tick(t0 + 62_000)
        assertEquals(1, host.restarts)
        Unit
    }

    @Test
    fun `path change shortens a long backoff`() = runBlocking {
        val host = FakeHost()
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val s = supervisor(host)
        // Walk to a 30s backoff.
        var now = t0
        repeat(5) {
            host.stages[UpstreamPolicy.Stage.AETHER] = failed()
            s.tick(now)
            now += UpstreamPolicy.backoffMs(it + 1)
            s.tick(now)
            now += 1
        }
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val before = host.restarts
        s.tick(now) // schedules a 30s wait
        s.onPathsChanged(now + 100)
        s.tick(now + 1_200)
        assertEquals(before + 1, host.restarts)
        Unit
    }

    @Test
    fun `user is alerted after 15 seconds and the alert clears on recovery`() = runBlocking {
        val host = FakeHost()
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val s = supervisor(host)
        s.tick(t0)
        s.tick(t0 + 14_000)
        assertEquals(0, host.alerts)
        s.tick(t0 + 15_000)
        assertEquals(1, host.alerts)
        s.tick(t0 + 16_000)
        assertEquals("alert must not repeat", 1, host.alerts)
        host.stages[UpstreamPolicy.Stage.AETHER] = ready()
        s.tick(t0 + 17_000)
        assertEquals(1, host.clears)
        assertEquals(UpstreamLevel.OK, UpstreamHealth.status.value.level)
        Unit
    }

    @Test
    fun `a stalled tunnel is restarted after three failed probes`() = runBlocking {
        val host = FakeHost()
        host.stages[UpstreamPolicy.Stage.AETHER] = ready()
        host.probeResult = false
        val s = supervisor(host)
        s.tick(t0)            // probe 1 fails
        s.tick(t0 + 5_000)    // too soon for another probe
        assertEquals(1, host.probes)
        s.tick(t0 + 10_000)   // probe 2 fails
        assertEquals(0, host.restarts)
        s.tick(t0 + 20_000)   // probe 3 fails -> failure path, backoff 1
        assertEquals(3, host.probes)
        assertEquals(0, host.restarts)
        s.tick(t0 + 22_000)   // chain still reports ready on paper; backoff elapsed
        assertEquals(1, host.restarts)
        Unit
    }

    @Test
    fun `one good probe resets the failure count`() = runBlocking {
        val host = FakeHost()
        host.stages[UpstreamPolicy.Stage.AETHER] = ready()
        val s = supervisor(host)
        host.probeResult = false
        s.tick(t0)
        s.tick(t0 + 10_000)
        host.probeResult = true
        s.tick(t0 + 20_000)
        host.probeResult = false
        s.tick(t0 + 30_000)
        s.tick(t0 + 40_000)
        assertEquals(0, host.restarts)
        Unit
    }

    @Test
    fun `no probing without a usable network`() = runBlocking {
        val host = FakeHost()
        host.path = false
        host.stages[UpstreamPolicy.Stage.AETHER] = ready()
        val s = supervisor(host)
        for (i in 0..30) s.tick(t0 + i * 1_000L)
        assertEquals(0, host.probes)
        assertEquals(0, host.restarts)
        Unit
    }

    @Test
    fun `an idle required stage is started after a short wait`() = runBlocking {
        val host = FakeHost(UpstreamChoice.AETHER_XRAY)
        host.stages[UpstreamPolicy.Stage.AETHER] = ready()
        host.stages[UpstreamPolicy.Stage.XRAY] = RuntimeStatus.IDLE
        val s = supervisor(host)
        s.tick(t0)
        s.tick(t0 + 3_000)
        assertEquals(0, host.converges)
        s.tick(t0 + 4_000)
        assertEquals(1, host.converges)
        Unit
    }

    @Test
    fun `no upstream chosen means nothing happens`() = runBlocking {
        val host = FakeHost(UpstreamChoice.NONE)
        val s = supervisor(host)
        s.tick(t0)
        assertEquals(UpstreamLevel.OK, UpstreamHealth.status.value.level)
        assertEquals(0, host.restarts + host.converges + host.probes + host.alerts)
        Unit
    }

    @Test
    fun `a stopped vpn is left alone`() = runBlocking {
        val host = FakeHost()
        host.running = false
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val s = supervisor(host)
        s.tick(t0 + 100_000)
        assertEquals(0, host.restarts)
        Unit
    }

    @Test
    fun `a chain that stays healthy long enough forgives past failures`() = runBlocking {
        val host = FakeHost()
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val s = supervisor(host)
        s.tick(t0)
        s.tick(t0 + 2_000) // restart #1 (attempt 1)
        host.stages[UpstreamPolicy.Stage.AETHER] = ready()
        var now = t0 + 3_000
        // Healthy for over a minute...
        while (now < t0 + 3_000 + UpstreamPolicy.STABLE_MS + 5_000) {
            s.tick(now)
            now += 1_000
        }
        // ...so the next failure starts the backoff over at 2s.
        host.stages[UpstreamPolicy.Stage.AETHER] = failed()
        val before = host.restarts
        s.tick(now)
        s.tick(now + 2_000)
        assertEquals(before + 1, host.restarts)
        Unit
    }

    @Test
    fun `a chain that never becomes ready is eventually restarted`() = runBlocking {
        val host = FakeHost()
        // Keeps re-arming its own deadline, so judge() alone never calls it stuck.
        val s = supervisor(host)
        var now = t0
        var restarted = false
        while (now < t0 + UpstreamPolicy.CHAIN_BUDGET_MS + 60_000 && !restarted) {
            host.stages[UpstreamPolicy.Stage.AETHER] = status(RuntimePhase.STARTING, true, now + 60_000)
            s.tick(now)
            restarted = host.restarts > 0
            now += 1_000
        }
        assertTrue(restarted)
        assertFalse(host.logs.isEmpty())
        Unit
    }
}
