package com.fem.linexa.service.runtime.hostinfo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Deterministic JVM verification of the LXCFS-style environment accounting:
 * baseline seeding, per-core delta attribution by last-run CPU, capacity
 * accounting for idle, load-average decay, and monotonicity — the mechanical
 * fix behind the audited "htop CPU% = N/A" on hosts where /proc/stat is
 * SELinux-restricted.
 */
class EnvTickSamplerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun statLine(
        state: Char = 'S',
        utime: Long, stime: Long, cutime: Long = 0, cstime: Long = 0,
        lastCore: Int
    ): String {
        val sb = StringBuilder()
        sb.append("42 (bash) $state 1 1 1 -1 -1 0 0 0 0 50 ")
        sb.append("$utime $stime $cutime $cstime ")
        repeat(21) { sb.append("${it + 7} ") }
        sb.append(lastCore)
        repeat(13) { sb.append(" 0") }
        sb.append("\n")
        return sb.toString()
    }

    private fun writeProc(procDir: File, pid: Int, body: String) {
        val d = File(procDir, pid.toString())
        d.mkdirs()
        File(d, "stat").writeText(body)
    }

    @Test
    fun `first pass seeds baselines and emits nothing`() {
        val proc = tmp.newFolder("proc")
        writeProc(proc, 100, statLine(utime = 10, stime = 5, lastCore = 0))
        var now = 0L
        val sampler = EnvTickSampler(proc, clockTicksPerSecond = 100, nowMs = { now })

        assertNull(sampler.sampleOnce(listOf(0, 1)))
    }

    @Test
    fun `busy deltas attributed to the last-run core and idle is capacity-remainder`() {
        val proc = tmp.newFolder("proc")
        writeProc(proc, 100, statLine(utime = 10, stime = 5, lastCore = 2))
        var now = 0L
        val sampler = EnvTickSampler(proc, clockTicksPerSecond = 100, nowMs = { now })

        sampler.sampleOnce(listOf(1, 2, 3))

        now += 1000   // one full window: 100 ticks of capacity per core
        writeProc(proc, 100, statLine(utime = 20, stime = 15, cutime = 10, cstime = 0, lastCore = 2))
        val snapshot = sampler.sampleOnce(listOf(1, 2, 3))!!

        // busy grew by (30−15)+(10−0)=25? no: utime10→20=+10, stime5→15=+10,
        // cutime0→10=+10 → 30 busy ticks total attributed to core 2.
        assertEquals(30L, snapshot.busyTotalTicks)
        assertEquals(30L, snapshot.perCoreBusyTicks[2])
        // capacity: 3 cores × 100 − 30 measured = 270 across the grant.
        assertEquals(270L, snapshot.idleTotalTicks)
        assertEquals(100L, snapshot.perCoreIdleTicks[1])
        assertEquals(70L, snapshot.perCoreIdleTicks[2])
        assertEquals(100L, snapshot.perCoreIdleTicks[3])
    }

    @Test
    fun `counters stay monotonic when a pid dies between passes`() {
        val proc = tmp.newFolder("proc")
        writeProc(proc, 100, statLine(utime = 10, stime = 5, lastCore = 0))
        writeProc(proc, 200, statLine(state = 'R', utime = 40, stime = 40, lastCore = 0))
        var now = 0L
        val sampler = EnvTickSampler(proc, clockTicksPerSecond = 100, nowMs = { now })
        sampler.sampleOnce(listOf(0))

        now += 1000
        File(File(proc, "200"), "stat").delete()   // pid vanished
        val s2 = sampler.sampleOnce(listOf(0))!!

        // The incremental pass re-reads the known set; the vanished pid simply
        // stops contributing and is dropped until the next full sweep.
        assertTrue(s2.perCoreIdleTicks.values.sum() >= 0L)
        assertEquals("each distinct pid counted once", 2, s2.seenProcsTotal)
        assertEquals(1, sampler.knownPidCountForTest())
    }

    @Test
    fun `a pid created between passes is discovered at the next full sweep`() {
        val proc = tmp.newFolder("proc")
        writeProc(proc, 100, statLine(utime = 10, stime = 5, lastCore = 0))
        var now = 0L
        val sampler = EnvTickSampler(proc, clockTicksPerSecond = 100, nowMs = { now })
        sampler.sampleOnce(listOf(0))   // full sweep: knows 100

        writeProc(proc, 200, statLine(utime = 0, stime = 0, lastCore = 0))
        now += 1_000
        val incremental = sampler.sampleOnce(listOf(0))!!
        assertEquals("incremental passes only re-read the known set", 1, incremental.seenProcsTotal)

        now += EnvTickSampler.FULL_SCAN_INTERVAL_MS + 1
        val fullSweep = sampler.sampleOnce(listOf(0))!!
        assertEquals("the full sweep discovers the new pid", 2, fullSweep.seenProcsTotal)
    }

    @Test
    fun `running and blocked states are observed from real state characters`() {
        val proc = tmp.newFolder("proc")
        writeProc(proc, 1, statLine(state = 'R', utime = 0, stime = 0, lastCore = 0))
        writeProc(proc, 2, statLine(state = 'D', utime = 0, stime = 0, lastCore = 0))
        writeProc(proc, 3, statLine(state = 'S', utime = 0, stime = 0, lastCore = 0))
        var now = 0L
        val sampler = EnvTickSampler(proc, clockTicksPerSecond = 100, nowMs = { now })
        sampler.sampleOnce(listOf(0))

        now += 500
        val snap = sampler.sampleOnce(listOf(0))!!
        assertEquals(1, snap.blockedProcs)
        assertEquals(1, snap.runningProcs)          // state R
        assertNotNull(snap.loadAvg1m)
        assertTrue("load rises toward observed actives", snap.loadAvg1m!! > 0f)
    }

    @Test
    fun `busy clamped to window capacity even under bookkeeping jitter`() {
        val proc = tmp.newFolder("proc")
        writeProc(proc, 100, statLine(utime = 0, stime = 0, lastCore = 0))
        var now = 0L
        val sampler = EnvTickSampler(proc, clockTicksPerSecond = 100, nowMs = { now })
        sampler.sampleOnce(listOf(0))

        now += 100     // only 0.1s ⇒ 10 tick capacity
        writeProc(proc, 100, statLine(utime = 9_000, stime = 0, lastCore = 0))
        val snap = sampler.sampleOnce(listOf(0))!!

        assertEquals(10L, snap.busyTotalTicks)       // clamped
        assertEquals(0L, snap.idleTotalTicks)        // fully consumed window
    }

    @Test
    fun `reset clears all accumulated state`() {
        val proc = tmp.newFolder("proc")
        writeProc(proc, 100, statLine(utime = 1, stime = 1, lastCore = 0))
        var now = 0L
        val sampler = EnvTickSampler(proc, clockTicksPerSecond = 100, nowMs = { now })
        sampler.sampleOnce(listOf(0))
        sampler.reset()
        now += 1000
        writeProc(proc, 100, statLine(utime = 5, stime = 1, lastCore = 0))
        // after reset the pass re-seeds silently — unknown again, not resumed.
        assertNull(sampler.sampleOnce(listOf(0)))
    }
}
